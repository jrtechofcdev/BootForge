package dev.bootforge.app.ui

import android.app.Application
import android.net.Uri
import android.provider.OpenableColumns
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dev.bootforge.app.data.Format
import dev.bootforge.app.data.HistoryEntry
import dev.bootforge.app.data.HistoryRepository
import dev.bootforge.blockdev.BlockDevice
import dev.bootforge.blockdev.BlockTransfer
import dev.bootforge.blockdev.Progress
import dev.bootforge.blockdev.ProgressListener
import dev.bootforge.blockdev.ProgressTracker
import dev.bootforge.blockdev.readBlocks
import dev.bootforge.blockdev.slice
import dev.bootforge.fs.exfat.ExFatVolume
import dev.bootforge.integrity.CancellationSignal
import dev.bootforge.integrity.Hashing
import dev.bootforge.integrity.OperationCancelledException
import dev.bootforge.partition.DiskLayout
import dev.bootforge.partition.DiskLayoutReader
import dev.bootforge.partition.FsType
import dev.bootforge.usb.UsbDriveInfo
import dev.bootforge.usb.UsbDriveManager
import dev.bootforge.ventoy.InstallObserver
import dev.bootforge.ventoy.InstallStep
import dev.bootforge.ventoy.VentoyDetector
import dev.bootforge.ventoy.VentoyInfo
import dev.bootforge.ventoy.VentoyInstallOptions
import dev.bootforge.ventoy.VentoyInstaller
import dev.bootforge.ventoy.VentoyPackage
import dev.bootforge.ventoy.VentoyUpdater
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.IOException

/** Imagem inicializável encontrada na partição de dados do Ventoy. */
data class ImageFile(val path: String, val sizeBytes: Long)

/** Tudo o que foi lido de um pendrive (somente leitura). */
data class DriveReport(
    val info: UsbDriveInfo,
    val blockSize: Int,
    val blockCount: Long,
    val layout: DiskLayout,
    val ventoy: VentoyInfo,
    val sector0: ByteArray,
    val images: List<ImageFile>,
) {
    val capacityBytes: Long get() = blockSize.toLong() * blockCount
}

sealed interface InspectState {
    data object Idle : InspectState
    data class Loading(val deviceName: String) : InspectState
    data class Ready(val report: DriveReport) : InspectState
    data class Failed(val deviceName: String, val message: String) : InspectState
}

/** Estado de uma operação longa, exibido na tela de progresso. */
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

class AppViewModel(app: Application) : AndroidViewModel(app) {

    val drives = UsbDriveManager(app)
    val history = HistoryRepository(app)

    /** Pacote original do Ventoy embutido no APK (assets/ventoy/...). */
    val ventoyPackage = VentoyPackage { path -> app.assets.open("ventoy/$path") }

    private val _inspect = MutableStateFlow<InspectState>(InspectState.Idle)
    val inspect: StateFlow<InspectState> = _inspect.asStateFlow()

    private val _operation = MutableStateFlow<OperationState?>(null)
    val operation: StateFlow<OperationState?> = _operation.asStateFlow()

    private val _packageCheck = MutableStateFlow<String?>(null)
    val packageCheck: StateFlow<String?> = _packageCheck.asStateFlow()

    private var cancelSignal = CancellationSignal()
    private var lastOperationDevice: String? = null

    init {
        drives.start()
    }

    override fun onCleared() {
        drives.stop()
    }

    val isBusy: Boolean get() = _operation.value?.status == OperationState.Status.RUNNING

    // ------------------------------------------------------------------ Inspeção (somente leitura)

    fun inspect(deviceName: String) {
        if (isBusy) return
        _inspect.value = InspectState.Loading(deviceName)
        viewModelScope.launch(Dispatchers.IO) {
            val info = drives.drives.value.firstOrNull { it.deviceName == deviceName }
            if (info == null) {
                _inspect.value = InspectState.Failed(deviceName, "Pendrive não encontrado"); return@launch
            }
            _inspect.value = try {
                drives.open(deviceName, readOnly = true).use { dev ->
                    val layout = DiskLayoutReader.read(dev)
                    val ventoy = VentoyDetector.inspect(dev, scanData = true)
                    InspectState.Ready(
                        DriveReport(info, dev.blockSize, dev.blockCount, layout, ventoy, dev.readBlocks(0, 1), listImages(dev, ventoy)),
                    )
                }
            } catch (e: Exception) {
                InspectState.Failed(deviceName, e.message ?: e.javaClass.simpleName)
            }
        }
    }

    private fun listImages(dev: BlockDevice, v: VentoyInfo): List<ImageFile> {
        if (!v.isVentoy || v.data?.fsType != FsType.EXFAT) return emptyList()
        val start = v.part1Start ?: return emptyList()
        val count = v.part1Sectors ?: return emptyList()
        return runCatching {
            val out = mutableListOf<ImageFile>()
            ExFatVolume.open(dev.slice(start, count)).walk { path, e ->
                if (!e.isDirectory && e.name.substringAfterLast('.', "").lowercase() in VentoyDetector.BOOTABLE_EXTENSIONS) {
                    out += ImageFile(path, e.size)
                }
            }
            out.sortedBy { it.path.lowercase() }
        }.getOrDefault(emptyList())
    }

    // ------------------------------------------------------------------ Operações

    /** Executa uma operação longa com estado de progresso, cancelamento e registro no histórico. */
    private fun runOperation(
        title: String,
        info: UsbDriveInfo,
        block: suspend (cancel: CancellationSignal, observer: InstallObserver) -> String,
    ) {
        if (isBusy) return
        val cancel = CancellationSignal().also { cancelSignal = it }
        lastOperationDevice = info.deviceName
        _operation.value = OperationState(title, info.displayName)
        val observer = object : InstallObserver {
            override fun onStep(step: InstallStep, index: Int, total: Int) =
                _operation.update { it?.copy(step = step.label, stepIndex = index, stepCount = total, progress = null) }
            override fun onProgress(progress: Progress) = _operation.update { it?.copy(progress = progress) }
            override fun onLog(message: String) = _operation.update { it?.copy(logs = it.logs + message) }
        }
        viewModelScope.launch(Dispatchers.IO) {
            val (status, message) = try {
                OperationState.Status.SUCCESS to block(cancel, observer)
            } catch (e: OperationCancelledException) {
                OperationState.Status.CANCELLED to "Operação cancelada. O pendrive pode ter ficado em estado incompleto."
            } catch (e: Exception) {
                val reason = when (e) {
                    is IOException -> "Falha de leitura/gravação ou pendrive desconectado: ${e.message}"
                    is SecurityException -> "Sem permissão de acesso ao USB: ${e.message}"
                    else -> e.message ?: e.javaClass.simpleName
                }
                OperationState.Status.FAILED to reason
            }
            _operation.update { it?.copy(status = status, result = message) }
            history.add(HistoryEntry(System.currentTimeMillis(), title, info.displayName, status == OperationState.Status.SUCCESS, message))
        }
    }

    fun cancelOperation() = cancelSignal.cancel()

    /** Fecha o painel e relê o pendrive, para a tela mostrar o estado REAL após a operação. */
    fun dismissOperation() {
        if (isBusy) return
        _operation.value = null
        val d = lastOperationDevice ?: return
        if (drives.drives.value.any { it.deviceName == d && it.hasPermission }) inspect(d)
    }

    /** PoC: mede a velocidade de leitura real (até 256 MiB a partir do início). */
    fun readBenchmark(info: UsbDriveInfo) = runOperation("Teste de leitura", info) { cancel, obs ->
        drives.open(info.deviceName, readOnly = true).use { dev ->
            obs.onStep(InstallStep.VERIFY, 0, 1)
            val total = minOf(256L shl 20, dev.sizeBytes)
            val tracker = ProgressTracker("Lendo setores", total, ProgressListener { obs.onProgress(it) })
            val buf = ByteArray(BlockTransfer.CHUNK)
            val start = System.nanoTime()
            var done = 0L
            while (done < total) {
                cancel.throwIfCancelled()
                val n = minOf(buf.size.toLong(), total - done).toInt() / dev.blockSize * dev.blockSize
                dev.read(done / dev.blockSize, buf, 0, n)
                done += n
                tracker.update(done)
            }
            val secs = (System.nanoTime() - start) / 1e9
            "Leitura de ${Format.bytes(done)} concluída em ${"%.1f".format(secs)} s (${Format.speed(done / secs)})."
        }
    }

    /**
     * PoC: grava um arquivo de imagem (ex.: ISO híbrida Linux) setor a setor e confere
     * relendo o pendrive (SHA-256 do arquivo == SHA-256 do que foi lido).
     */
    fun writeRawImage(info: UsbDriveInfo, uri: Uri, confirmedSize: Long) = runOperation("Gravação RAW + verificação", info) { cancel, obs ->
        val resolver = getApplication<Application>().contentResolver
        val size = resolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use { c ->
            if (c.moveToFirst() && !c.isNull(0)) c.getLong(0) else null
        } ?: throw IOException("Não foi possível obter o tamanho do arquivo")
        val name = resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        } ?: "imagem"
        drives.open(info.deviceName, readOnly = false).use { dev ->
            if (dev.sizeBytes != confirmedSize) throw IllegalStateException("O pendrive conectado não é o mesmo que foi confirmado")
            if (size > dev.sizeBytes) throw IOException("A imagem (${Format.bytes(size)}) é maior que o pendrive (${Format.bytes(dev.sizeBytes)})")
            obs.onStep(InstallStep.WRITE_EFI, 0, 2)
            obs.onLog("Arquivo: $name (${Format.bytes(size)})")
            val written = resolver.openInputStream(uri)?.use { input ->
                BlockTransfer.writeStream(dev, 0, input.buffered(1 shl 20), size, "Gravando", { obs.onProgress(it) }, cancel)
            } ?: throw IOException("Não foi possível abrir o arquivo")
            obs.onLog("SHA-256 do arquivo: ${written.sha256}")
            obs.onStep(InstallStep.VERIFY, 1, 2)
            val readBack = BlockTransfer.hashRegion(dev, 0, size, "Verificando", { obs.onProgress(it) }, cancel)
            obs.onLog("SHA-256 relido do pendrive: $readBack")
            if (!Hashing.matches(written.sha256, readBack)) throw IOException("VERIFICAÇÃO FALHOU: o conteúdo lido difere do gravado")
            "Imagem \"$name\" gravada e verificada (SHA-256 confere: ${written.sha256.take(16)}…)."
        }
    }

    fun installVentoy(info: UsbDriveInfo, options: VentoyInstallOptions, confirmedSize: Long) =
        runOperation("Instalação do Ventoy ${runCatching { ventoyPackage.version }.getOrDefault("")}", info) { cancel, obs ->
            drives.open(info.deviceName, readOnly = false).use { dev ->
                val r = VentoyInstaller(ventoyPackage).install(dev, options, confirmedSize, obs, cancel)
                "Ventoy ${r.version} instalado (${r.layout.style.label}) e verificado. Copie suas ISOs para a partição \"${options.label}\"."
            }
        }

    fun updateVentoy(info: UsbDriveInfo, confirmedSize: Long) =
        runOperation("Atualização do Ventoy", info) { cancel, obs ->
            drives.open(info.deviceName, readOnly = false).use { dev ->
                val r = VentoyUpdater(ventoyPackage).update(dev, confirmedSize, obs, cancel)
                "Ventoy atualizado de ${r.fromVersion ?: "versão desconhecida"} para ${r.toVersion}. Arquivos e ISOs preservados."
            }
        }

    /** Confere o SHA-256 do pacote do Ventoy embutido no APK. */
    fun checkEmbeddedPackage() {
        _packageCheck.value = "Verificando…"
        viewModelScope.launch(Dispatchers.IO) {
            val problems = ventoyPackage.verifyIntegrity()
            _packageCheck.value = if (problems.isEmpty()) {
                "Pacote Ventoy ${ventoyPackage.version} íntegro: SHA-256 de boot.img, core.img.xz e ventoy.disk.img.xz conferem com o pacote oficial."
            } else {
                "PROBLEMA: ${problems.joinToString("; ")}"
            }
        }
    }
}
