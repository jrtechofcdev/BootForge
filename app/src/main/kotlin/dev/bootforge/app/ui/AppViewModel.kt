package dev.bootforge.app.ui

import android.app.Application
import android.net.Uri
import android.provider.OpenableColumns
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dev.bootforge.app.data.AppGraph
import dev.bootforge.app.data.Format
import dev.bootforge.app.data.OperationState
import dev.bootforge.blockdev.BlockDevice
import dev.bootforge.blockdev.BlockTransfer
import dev.bootforge.blockdev.CapacityTest
import dev.bootforge.integrity.Diag
import dev.bootforge.blockdev.ProgressListener
import dev.bootforge.blockdev.ProgressTracker
import dev.bootforge.blockdev.readBlocks
import dev.bootforge.blockdev.slice
import dev.bootforge.fs.exfat.ExFatVolume
import dev.bootforge.integrity.Hashing
import dev.bootforge.partition.DiskLayout
import dev.bootforge.partition.DiskLayoutReader
import dev.bootforge.partition.FsType
import dev.bootforge.usb.UsbDriveInfo
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

/**
 * ViewModel principal: inspeção de pendrives e operações das Etapas 1–2 (diagnóstico,
 * instalação/atualização do Ventoy). As ferramentas da Etapa 4 ficam no [VentoyViewModel].
 */
class AppViewModel(app: Application) : AndroidViewModel(app) {

    val drives = AppGraph.drives
    val history = AppGraph.history
    private val session = AppGraph.session
    private val ops = AppGraph.operations

    /** Pacote original do Ventoy embutido no APK. */
    val ventoyPackage: VentoyPackage = AppGraph.embeddedPackage

    private val _inspect = MutableStateFlow<InspectState>(InspectState.Idle)
    val inspect: StateFlow<InspectState> = _inspect.asStateFlow()

    val operation: StateFlow<OperationState?> = ops.state

    private val _packageCheck = MutableStateFlow<String?>(null)
    val packageCheck: StateFlow<String?> = _packageCheck.asStateFlow()

    init {
        // Pendrive removido → fecha a sessão; operação concluída → relê o pendrive afetado.
        viewModelScope.launch {
            drives.drives.collect { list -> session.closeIfDetached(list.map { it.deviceName }.toSet()) }
        }
        viewModelScope.launch {
            ops.finished.collect { name ->
                val cur = (_inspect.value as? InspectState.Ready)?.report?.info?.deviceName
                if (name != null && name == cur) inspect(name)
            }
        }
    }

    val isBusy: Boolean get() = ops.isBusy

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
                session.withDevice(deviceName) { dev ->
                    val layout = DiskLayoutReader.read(dev)
                    val ventoy = VentoyDetector.inspect(dev, scanData = true)
                    InspectState.Ready(
                        DriveReport(info, dev.blockSize, dev.blockCount, layout, ventoy, dev.readBlocks(0, 1), listImages(dev, ventoy)),
                    )
                }
            } catch (e: Exception) {
                dev.bootforge.integrity.Diag.e("Inspeção", "Falha ao ler o pendrive $deviceName", e)
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

    fun cancelOperation() = ops.cancel()

    fun dismissOperation() = ops.dismiss()

    /** PoC: mede a velocidade de leitura real (até 256 MiB a partir do início). */
    fun readBenchmark(info: UsbDriveInfo) = ops.run("Teste de leitura", info.displayName, info.deviceName) { cancel, obs ->
        session.withDevice(info.deviceName) { dev ->
            obs.step("Lendo setores", 0, 1)
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
     * Teste de capacidade real (pendrive falsificado). Não apaga dados: grava marcas em pontos
     * espalhados, confere e devolve o conteúdo original. Ver [dev.bootforge.blockdev.CapacityTest].
     */
    fun capacityTest(info: UsbDriveInfo, confirmedSize: Long) = ops.run("Teste de capacidade real", info.displayName, info.deviceName) { cancel, obs ->
        session.withDevice(info.deviceName) { dev ->
            if (dev.sizeBytes != confirmedSize) throw IllegalStateException("O pendrive conectado não é o mesmo que foi confirmado")
            obs.step("Gravando e conferindo marcas em pontos do pendrive", 0, 1)
            val r = CapacityTest.run(dev, listener = { obs.onProgress(it) }, cancel = cancel)
            obs.onLog("Pontos testados: ${r.points}; com falha: ${r.badPoints}; conteúdo original devolvido: ${if (r.restored) "sim" else "NÃO"}")
            Diag.i("Capacidade", "Anunciada ${r.declaredBytes} bytes; real estimada ${r.realBytesEstimate}; falhas ${r.badPoints}/${r.points}; volta=${r.wraps}; restaurado=${r.restored}")
            if (!r.restored) throw IOException("Não foi possível devolver o conteúdo original de alguns pontos: o pendrive apresenta defeito.")
            if (r.genuine) "Capacidade real confirmada: ${Format.bytes(r.declaredBytes)} (${r.points} pontos conferidos de ponta a ponta). Seus dados foram preservados."
            else throw IOException(
                "PENDRIVE COM CAPACIDADE FALSA: anuncia ${Format.bytes(r.declaredBytes)}, mas só cerca de ${Format.bytes(r.realBytesEstimate)} funcionam de verdade " +
                    "(${r.badPoints} de ${r.points} pontos falharam${if (r.wraps) "; gravar além disso sobrescreve o começo" else ""}). " +
                    "Não use para guardar arquivos nem para o Ventoy. Os dados que estavam nele foram devolvidos onde a memória é real.",
            )
        }
    }

    /**
     * PoC: grava um arquivo de imagem (ex.: ISO híbrida Linux) setor a setor e confere
     * relendo o pendrive (SHA-256 do arquivo == SHA-256 do que foi lido).
     */
    fun writeRawImage(info: UsbDriveInfo, uri: Uri, confirmedSize: Long) =
        ops.run("Gravação RAW + verificação", info.displayName, info.deviceName) { cancel, obs ->
            val resolver = getApplication<Application>().contentResolver
            val size = resolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use { c ->
                if (c.moveToFirst() && !c.isNull(0)) c.getLong(0) else null
            } ?: throw IOException("Não foi possível obter o tamanho do arquivo")
            val name = resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                if (c.moveToFirst()) c.getString(0) else null
            } ?: "imagem"
            session.withDevice(info.deviceName) { dev ->
                if (dev.sizeBytes != confirmedSize) throw IllegalStateException("O pendrive conectado não é o mesmo que foi confirmado")
                if (size > dev.sizeBytes) throw IOException("A imagem (${Format.bytes(size)}) é maior que o pendrive (${Format.bytes(dev.sizeBytes)})")
                obs.step("Gravando imagem", 0, 2)
                obs.onLog("Arquivo: $name (${Format.bytes(size)})")
                val written = resolver.openInputStream(uri)?.use { input ->
                    BlockTransfer.writeStream(dev, 0, input.buffered(1 shl 20), size, "Gravando", { obs.onProgress(it) }, cancel)
                } ?: throw IOException("Não foi possível abrir o arquivo")
                obs.onLog("SHA-256 do arquivo: ${written.sha256}")
                obs.step("Verificando (releitura)", 1, 2)
                val readBack = BlockTransfer.hashRegion(dev, 0, size, "Verificando", { obs.onProgress(it) }, cancel)
                obs.onLog("SHA-256 relido do pendrive: $readBack")
                if (!Hashing.matches(written.sha256, readBack)) throw IOException("VERIFICAÇÃO FALHOU: o conteúdo lido difere do gravado")
                "Imagem \"$name\" gravada e verificada (SHA-256 confere: ${written.sha256.take(16)}…)."
            }
        }

    fun installVentoy(info: UsbDriveInfo, options: VentoyInstallOptions, confirmedSize: Long, pkg: VentoyPackage = ventoyPackage) =
        ops.run("Instalação do Ventoy ${runCatching { pkg.version }.getOrDefault("")}", info.displayName, info.deviceName) { cancel, obs ->
            session.withDevice(info.deviceName) { dev ->
                val r = VentoyInstaller(pkg).install(dev, options, confirmedSize, obs, cancel)
                "Ventoy ${r.version} instalado (${r.layout.style.label}${if (options.secureBoot) "" else ", sem Secure Boot"}) e verificado. Copie suas ISOs para a partição \"${options.label}\"."
            }
        }

    fun updateVentoy(info: UsbDriveInfo, confirmedSize: Long, pkg: VentoyPackage = ventoyPackage, secureBoot: Boolean? = null) =
        ops.run(if (secureBoot == null) "Atualização do Ventoy" else "Secure Boot ${if (secureBoot) "ligar" else "desligar"}", info.displayName, info.deviceName) { cancel, obs ->
            session.withDevice(info.deviceName) { dev ->
                val r = VentoyUpdater(pkg).update(dev, confirmedSize, secureBoot, obs, cancel)
                "Ventoy ${r.fromVersion ?: "desconhecido"} → ${r.toVersion}; Secure Boot ${if (r.detected.secureBootSupport == true) "ligado" else "desligado"}. Arquivos e ISOs preservados."
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
