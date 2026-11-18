package dev.bootforge.app.ui

import android.app.Application
import android.net.Uri
import android.provider.OpenableColumns
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dev.bootforge.app.data.AppGraph
import dev.bootforge.app.data.Format
import dev.bootforge.app.data.UriRandomReader
import dev.bootforge.isowriter.IsoAnalysis
import dev.bootforge.isowriter.IsoAnalyzer
import dev.bootforge.isowriter.IsoWriter
import dev.bootforge.isowriter.PartitionScheme
import dev.bootforge.isowriter.WriteMethod
import dev.bootforge.isowriter.WriteRequest
import dev.bootforge.usb.UsbDriveInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.IOException

/** Imagem escolhida e o resultado da análise. */
sealed interface IsoSource {
    data object None : IsoSource
    data class Analyzing(val name: String) : IsoSource
    data class Ready(val uri: Uri, val name: String, val analysis: IsoAnalysis) : IsoSource
    data class Failed(val name: String, val message: String) : IsoSource
}

/** Escolhas do usuário na tela "Gravar ISO". */
data class IsoWriterUi(
    val source: IsoSource = IsoSource.None,
    val method: WriteMethod? = null,
    val scheme: PartitionScheme = PartitionScheme.MBR,
    val verify: Boolean = true,
    /** Pendrive de destino (deviceName). */
    val target: String? = null,
)

/**
 * Etapa 3 — gravação inteligente de ISOs. A análise roda direto sobre o arquivo do celular
 * (sem cópia) e a gravação usa o motor `feature:iso-writer` via [dev.bootforge.app.data.OperationRunner]
 * (serviço em primeiro plano, progresso real, cancelamento e histórico).
 */
class IsoWriterViewModel(app: Application) : AndroidViewModel(app) {

    private val session = AppGraph.session
    private val ops = AppGraph.operations

    private val _ui = MutableStateFlow(IsoWriterUi())
    val ui: StateFlow<IsoWriterUi> = _ui.asStateFlow()

    fun pickIso(uri: Uri) {
        val resolver = getApplication<Application>().contentResolver
        val name = runCatching {
            resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
        }.getOrNull() ?: "imagem.iso"
        _ui.update { it.copy(source = IsoSource.Analyzing(name), method = null) }
        viewModelScope.launch(Dispatchers.IO) {
            val result = runCatching {
                UriRandomReader.open(getApplication(), uri).use { IsoAnalyzer.analyze(name, it) }
            }
            _ui.update { s ->
                result.fold(
                    { a -> s.copy(source = IsoSource.Ready(uri, name, a), method = a.recommended, scheme = a.recommendedScheme) },
                    { e -> s.copy(source = IsoSource.Failed(name, e.message ?: e.javaClass.simpleName)) },
                )
            }
        }
    }

    fun selectMethod(m: WriteMethod) = _ui.update { it.copy(method = m) }
    fun selectScheme(s: PartitionScheme) = _ui.update { it.copy(scheme = s) }
    fun setVerify(v: Boolean) = _ui.update { it.copy(verify = v) }
    fun selectTarget(deviceName: String?) = _ui.update { it.copy(target = deviceName) }
    fun clear() = _ui.update { IsoWriterUi() }

    /** Grava. Só é chamado depois da confirmação (digitar APAGAR) na tela. */
    fun write(info: UsbDriveInfo, confirmedSize: Long) {
        val s = _ui.value
        val src = s.source as? IsoSource.Ready ?: return
        val method = s.method ?: return
        val req = WriteRequest(method, s.scheme, verify = s.verify)
        ops.run("Gravar ISO: ${src.name}", info.displayName, info.deviceName) { cancel, obs ->
            UriRandomReader.open(getApplication(), src.uri).use { reader ->
                if (reader.size != src.analysis.info.size) throw IOException("O arquivo mudou desde a análise; escolha-o novamente")
                session.withDevice(info.deviceName) { dev ->
                    if (dev.sizeBytes != confirmedSize) throw IllegalStateException("O pendrive conectado não é o mesmo que foi confirmado")
                    obs.step(method.title, 0, 1)
                    obs.onLog("Imagem: ${src.name} (${Format.bytes(reader.size)})")
                    if (method != WriteMethod.DD) obs.onLog("Partição: ${s.scheme.title} + FAT32")
                    var lastStage = ""
                    val r = IsoWriter.write(dev, reader, src.analysis, req, { p ->
                        if (p.stage != lastStage) { lastStage = p.stage; obs.onLog("» ${p.stage}") }
                        obs.onProgress(p)
                    }, cancel)
                    r.wimParts.takeIf { it.isNotEmpty() }?.let { obs.onLog("install.wim dividido em: ${it.joinToString { p -> p.substringAfterLast('/') }}") }
                    r.imageSha256?.let { obs.onLog("SHA-256 da imagem: $it") }
                    buildString {
                        append("\"${src.name}\" gravada (${method.title}")
                        if (method != WriteMethod.DD) append(", ${s.scheme.title}, rótulo ${r.label}")
                        append("). ")
                        append(if (r.verified) "Verificação concluída: tudo o que foi relido confere." else "Verificação desativada.")
                        if (method != WriteMethod.DD) append(" Inicie o PC em modo UEFI.")
                    }
                }
            }
        }
    }
}
