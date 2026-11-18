package dev.bootforge.app.data

import android.app.Application
import dev.bootforge.app.OperationService
import dev.bootforge.blockdev.Progress
import dev.bootforge.integrity.CancellationSignal
import dev.bootforge.integrity.OperationCancelledException
import dev.bootforge.ventoy.InstallObserver
import dev.bootforge.ventoy.InstallStep
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.IOException
import dev.bootforge.integrity.Diag

/** Estado de uma operação longa (sempre com dados REAIS de progresso). */
data class OperationState(
    val title: String,
    val deviceLabel: String,
    val step: String = "Preparando…",
    val stepIndex: Int = 0,
    val stepCount: Int = 0,
    val progress: Progress? = null,
    val logs: List<String> = emptyList(),
    val status: Status = Status.RUNNING,
    val result: String? = null,
) {
    enum class Status { RUNNING, SUCCESS, FAILED, CANCELLED }
}

/** Observador com atalhos para etapas livres (sem enum). */
interface OperationObserver : InstallObserver {
    fun step(label: String, index: Int, total: Int)
}

/**
 * Executa UMA operação longa por vez, fora da tela (sobrevive à rotação e à troca de telas),
 * com cancelamento, registro no histórico e serviço em primeiro plano (notificação de progresso).
 */
class OperationRunner(private val app: Application, private val history: HistoryRepository) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _state = MutableStateFlow<OperationState?>(null)
    val state: StateFlow<OperationState?> = _state.asStateFlow()

    /** Emite o pendrive afetado quando uma operação termina (para as telas recarregarem). */
    private val _finished = MutableSharedFlow<String?>(extraBufferCapacity = 4)
    val finished: SharedFlow<String?> = _finished

    private var cancel = CancellationSignal()

    val isBusy: Boolean get() = _state.value?.status == OperationState.Status.RUNNING

    /** @return false se já existe uma operação em andamento. */
    fun run(
        title: String,
        deviceLabel: String,
        deviceName: String?,
        block: suspend (cancel: CancellationSignal, observer: OperationObserver) -> String,
    ): Boolean {
        if (isBusy) return false
        val c = CancellationSignal().also { cancel = it }
        _state.value = OperationState(title, deviceLabel)
        Diag.i("Operação", "INÍCIO: $title — $deviceLabel")
        OperationService.start(app)
        val observer = object : OperationObserver {
            override fun onStep(step: InstallStep, index: Int, total: Int) = step(step.label, index, total)
            override fun step(label: String, index: Int, total: Int) {
                Diag.i("Operação", "Etapa ${index + 1}/$total: $label")
                _state.update { it?.copy(step = label, stepIndex = index, stepCount = total, progress = null) }
            }
            override fun onProgress(progress: Progress) = _state.update { it?.copy(progress = progress) }
            override fun onLog(message: String) {
                Diag.i("Operação", message)
                _state.update { it?.copy(logs = it.logs + message) }
            }
        }
        scope.launch {
            val (status, message) = try {
                OperationState.Status.SUCCESS to block(c, observer)
            } catch (e: OperationCancelledException) {
                Diag.w("Operação", "CANCELADA: $title")
                OperationState.Status.CANCELLED to "Operação cancelada. O que já tinha sido concluído foi mantido; o item em andamento foi descartado."
            } catch (e: Throwable) {
                val reason = when (e) {
                    is IOException -> "Falha de leitura/gravação ou pendrive desconectado: ${e.message}"
                    is SecurityException -> "Sem permissão de acesso ao USB: ${e.message}"
                    is OutOfMemoryError -> "Memória insuficiente no aparelho"
                    else -> e.message ?: e.javaClass.simpleName
                }
                Diag.e("Operação", "FALHOU: $title — $reason (progresso: ${_state.value?.progress?.let { "${it.bytesDone}/${it.bytesTotal} bytes" } ?: "-"}, etapa: ${_state.value?.step})", e)
                OperationState.Status.FAILED to reason
            }
            if (status == OperationState.Status.SUCCESS) Diag.i("Operação", "CONCLUÍDA: $title — $message")
            _state.update { it?.copy(status = status, result = message) }
            history.add(HistoryEntry(System.currentTimeMillis(), title, deviceLabel, status == OperationState.Status.SUCCESS, message))
            _finished.tryEmit(deviceName)
        }
        return true
    }

    fun cancel() = cancel.cancel()

    fun dismiss() {
        if (!isBusy) _state.value = null
    }
}
