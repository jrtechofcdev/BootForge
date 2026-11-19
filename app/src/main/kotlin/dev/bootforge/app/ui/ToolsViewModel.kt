package dev.bootforge.app.ui

import android.app.Application
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import androidx.lifecycle.AndroidViewModel
import dev.bootforge.app.data.AppGraph
import dev.bootforge.app.data.Format
import dev.bootforge.app.data.OperationObserver
import dev.bootforge.blockdev.ProgressListener
import dev.bootforge.blockdev.slice
import dev.bootforge.fs.exfat.ExFatVolume
import dev.bootforge.integrity.Diag
import dev.bootforge.integrity.HashAlgorithm
import dev.bootforge.mediatools.Checksums
import dev.bootforge.mediatools.EraseMode
import dev.bootforge.mediatools.FormatRequest
import dev.bootforge.mediatools.ImageBackup
import dev.bootforge.mediatools.MediaFormatter
import dev.bootforge.mediatools.MediaFs
import dev.bootforge.mediatools.SecureErase
import dev.bootforge.mediatools.SurfaceTest
import dev.bootforge.usb.UsbDriveInfo
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.io.IOException
import java.io.InputStream

/** Ferramentas do módulo "Ferramentas" (Etapa 8). */
enum class MediaTool(val title: String, val summary: String, val destructive: Boolean) {
    FORMAT("Formatar", "Volta o pendrive ao uso normal (FAT32 ou exFAT) depois de gravar ISO ou Ventoy.", true),
    SURFACE("Teste de superfície", "Grava e relê o pendrive INTEIRO: setores ruins, capacidade real e velocidade.", true),
    ERASE("Apagar com segurança", "Apaga as tabelas (rápido) ou grava zeros em tudo (completo).", true),
    BACKUP("Backup do pendrive", "Copia o pendrive/cartão inteiro para uma imagem .img.gz no celular.", false),
    RESTORE("Restaurar imagem", "Grava uma imagem .img ou .img.gz de volta no pendrive, com verificação.", true),
    CHECKSUM("Checksum", "MD5, SHA-1, SHA-256 e SHA-512 de um arquivo do celular ou do pendrive Ventoy.", false),
}

/** Arquivo escolhido no celular. */
data class PickedFile(val uri: Uri, val name: String, val size: Long?)

/** Origem do cálculo de checksum. */
sealed interface ChecksumSource {
    val name: String
    data class Phone(val file: PickedFile) : ChecksumSource { override val name get() = file.name }
    /** Imagem dentro da partição de dados do Ventoy (exFAT). */
    data class Drive(val path: String, val size: Long) : ChecksumSource { override val name get() = path.substringAfterLast('/') }
}

data class ChecksumOutcome(val source: String, val values: Map<HashAlgorithm, String>)

data class ToolsUi(
    val tool: MediaTool = MediaTool.FORMAT,
    val target: String? = null,
    // formatar
    val fs: MediaFs = MediaFs.EXFAT,
    val gpt: Boolean = false,
    val label: String = "PENDRIVE",
    // apagar
    val erase: EraseMode = EraseMode.QUICK,
    // restaurar
    val restoreFile: PickedFile? = null,
    // checksum
    val checksumSource: ChecksumSource? = null,
    val checksum: ChecksumOutcome? = null,
    val expected: String = "",
    // teste de superfície
    val surface: SurfaceTest.Result? = null,
)

/**
 * Etapa 8 — ferramentas de pendrive e cartão. Toda operação longa passa pelo
 * [dev.bootforge.app.data.OperationRunner] (serviço em primeiro plano, progresso real,
 * cancelamento, histórico e log). As operações que apagam só são chamadas depois da
 * confirmação APAGAR na tela e conferem se o pendrive ainda é o mesmo (capacidade).
 */
class ToolsViewModel(app: Application) : AndroidViewModel(app) {

    private val session = AppGraph.session
    private val ops = AppGraph.operations
    private val resolver get() = getApplication<Application>().contentResolver

    private val _ui = MutableStateFlow(ToolsUi())
    val ui: StateFlow<ToolsUi> = _ui.asStateFlow()

    fun selectTool(t: MediaTool) = _ui.update { it.copy(tool = t) }
    fun selectTarget(name: String?) = _ui.update { it.copy(target = name, checksumSource = (it.checksumSource as? ChecksumSource.Phone)) }
    fun setFs(fs: MediaFs) = _ui.update { it.copy(fs = fs) }
    fun setGpt(gpt: Boolean) = _ui.update { it.copy(gpt = gpt) }
    fun setLabel(label: String) = _ui.update { it.copy(label = label.take(11)) }
    fun setErase(m: EraseMode) = _ui.update { it.copy(erase = m) }
    fun setExpected(s: String) = _ui.update { it.copy(expected = s) }
    fun pickRestore(uri: Uri) = _ui.update { it.copy(restoreFile = describe(uri)) }
    fun pickChecksumFile(uri: Uri) = _ui.update { it.copy(checksumSource = ChecksumSource.Phone(describe(uri)), checksum = null) }
    fun pickChecksumDrive(path: String, size: Long) = _ui.update { it.copy(checksumSource = ChecksumSource.Drive(path, size), checksum = null) }

    private fun describe(uri: Uri): PickedFile {
        var name = "arquivo"; var size: Long? = null
        runCatching {
            resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { c ->
                if (c.moveToFirst()) {
                    c.getString(0)?.let { name = it }
                    if (!c.isNull(1)) size = c.getLong(1)
                }
            }
        }
        return PickedFile(uri, name, size)
    }

    private fun listener(obs: OperationObserver) = ProgressListener { obs.onProgress(it) }

    private fun sameDrive(actual: Long, confirmed: Long) {
        if (actual != confirmed) throw IllegalStateException("O pendrive conectado não é o mesmo que foi confirmado")
    }

    // ------------------------------------------------------------------ Formatar

    fun format(info: UsbDriveInfo, confirmedSize: Long) {
        val s = _ui.value
        val req = FormatRequest(s.fs, s.gpt, s.label)
        ops.run("Formatar (${s.fs.title})", info.displayName, info.deviceName) { cancel, obs ->
            session.withDevice(info.deviceName) { dev ->
                sameDrive(dev.sizeBytes, confirmedSize)
                obs.step("Formatando em ${s.fs.title} (${if (s.gpt) "GPT" else "MBR"})", 0, 1)
                val r = MediaFormatter.format(dev, req, listener(obs), cancel)
                obs.onLog("Partição: setor ${r.partitionStart}, ${Format.bytes(r.partitionSectors * 512)}; cluster de ${Format.bytes(r.clusterBytes.toLong())}")
                "Pendrive formatado em ${r.fs.title} (${if (r.gpt) "GPT" else "MBR"}), rótulo \"${r.label}\", ${Format.bytes(r.partitionSectors * 512)}. " +
                    "Verificação concluída: o volume foi relido e abre normalmente."
            }
        }
    }

    // ------------------------------------------------------------------ Teste de superfície

    fun surfaceTest(info: UsbDriveInfo, confirmedSize: Long) {
        _ui.update { it.copy(surface = null) }
        ops.run("Teste de superfície", info.displayName, info.deviceName) { cancel, obs ->
            session.withDevice(info.deviceName) { dev ->
                sameDrive(dev.sizeBytes, confirmedSize)
                var stage = ""
                val r = SurfaceTest.run(dev, { p ->
                    if (p.stage != stage) {
                        stage = p.stage
                        when {
                            p.stage.startsWith("Gravando") -> obs.step("Gravando o pendrive inteiro", 0, 2)
                            p.stage.startsWith("Conferindo") -> obs.step("Relendo e conferindo tudo", 1, 2)
                        }
                    }
                    obs.onProgress(p)
                }, cancel)
                _ui.update { it.copy(surface = r) }
                obs.onLog("Velocidade média: gravação ${Format.speed(r.writeBytesPerSec)}, leitura ${Format.speed(r.readBytesPerSec)}")
                r.badRanges.take(20).forEach { obs.onLog("Trecho ruim: ${Format.bytes(it.startByte)} (+${Format.bytes(it.bytes)})") }
                val after = " O conteúdo foi apagado pelo teste: use \"Formatar\" para voltar a usar o pendrive."
                when {
                    r.fake -> throw IOException(
                        "PENDRIVE COM CAPACIDADE FALSA: anuncia ${Format.bytes(r.declaredBytes)}, mas só ${Format.bytes(r.realBytes)} guardam dados de verdade" +
                            (if (r.wraps) " (o endereço \"dá a volta\" e sobrescreve o começo)" else "") + ". Não use para arquivos importantes." + after,
                    )
                    r.badBytes > 0 -> throw IOException(
                        "Foram encontrados ${Format.bytes(r.badBytes)} com defeito em ${r.badRanges.size} trecho(s). O pendrive pode perder dados: substitua-o." + after,
                    )
                    else -> "Superfície 100% íntegra: ${Format.bytes(r.declaredBytes)} gravados e conferidos sem nenhum erro. " +
                        "Gravação ${Format.speed(r.writeBytesPerSec)}, leitura ${Format.speed(r.readBytesPerSec)}." + after
                }
            }
        }
    }

    // ------------------------------------------------------------------ Apagar

    fun erase(info: UsbDriveInfo, confirmedSize: Long) {
        val mode = _ui.value.erase
        ops.run("Apagar (${mode.title.lowercase()})", info.displayName, info.deviceName) { cancel, obs ->
            session.withDevice(info.deviceName) { dev ->
                sameDrive(dev.sizeBytes, confirmedSize)
                obs.step(if (mode == EraseMode.FULL) "Gravando zeros no pendrive inteiro" else "Apagando tabelas e estruturas", 0, 1)
                val r = SecureErase.run(dev, mode, listener(obs), cancel)
                "Apagamento ${mode.title.lowercase()} concluído: ${Format.bytes(r.bytesZeroed)} zerados e ${r.samplesChecked} pontos relidos e conferidos. " +
                    "O pendrive está sem partições: use \"Formatar\" para voltar a usá-lo."
            }
        }
    }

    // ------------------------------------------------------------------ Backup / restauração

    fun backup(info: UsbDriveInfo, dest: Uri) {
        val name = describe(dest).name
        ops.run("Backup do pendrive → $name", info.displayName, info.deviceName) { cancel, obs ->
            try {
                session.withDevice(info.deviceName) { dev ->
                    obs.step("Copiando e compactando (${Format.bytes(dev.sizeBytes)})", 0, 1)
                    val r = (resolver.openOutputStream(dest, "w") ?: throw IOException("Não foi possível criar o arquivo")).use { out ->
                        ImageBackup.backup(dev, out.buffered(1 shl 20), compress = true, listener(obs), cancel)
                    }
                    obs.onLog("SHA-256 dos dados (descompactados): ${r.sha256}")
                    "Backup concluído: \"$name\" (${Format.bytes(r.bytes)} do pendrive, compactado). SHA-256: ${r.sha256}. " +
                        "Para restaurar: Ferramentas → Restaurar imagem (ou balenaEtcher/Rufus no PC)."
                }
            } catch (e: Throwable) {
                // não deixa um arquivo incompleto que pareça um backup válido
                runCatching { DocumentsContract.deleteDocument(resolver, dest) }.onSuccess { Diag.w("Backup", "Arquivo incompleto removido: $name") }
                throw e
            }
        }
    }

    fun restore(info: UsbDriveInfo, confirmedSize: Long) {
        val file = _ui.value.restoreFile ?: return
        ops.run("Restaurar imagem: ${file.name}", info.displayName, info.deviceName) { cancel, obs ->
            val gz = open(file.uri).use { s -> ByteArray(2).also { var n = 0; while (n < 2) { val r = s.read(it, n, 2 - n); if (r < 0) break; n += r } }.let(ImageBackup::isGzip) }
            obs.step("Conferindo o tamanho da imagem", 0, 3)
            val size = if (!gz && file.size != null) file.size else open(file.uri).use { ImageBackup.measure(it, listener(obs), cancel) }
            obs.onLog("Imagem ${if (gz) "compactada (.gz)" else "sem compactação"}: ${Format.bytes(size)} de dados")
            session.withDevice(info.deviceName) { dev ->
                sameDrive(dev.sizeBytes, confirmedSize)
                obs.step("Gravando a imagem", 1, 3)
                var verifying = false
                val r = open(file.uri).use { input ->
                    ImageBackup.restore(dev, input, size, { p ->
                        if (!verifying && p.stage.startsWith("Verificando")) { verifying = true; obs.step("Verificando (releitura)", 2, 3) }
                        obs.onProgress(p)
                    }, cancel)
                }
                obs.onLog("SHA-256: ${r.sha256}")
                "Imagem \"${file.name}\" restaurada (${Format.bytes(r.bytes)}) e verificada: o conteúdo relido do pendrive confere (SHA-256 ${r.sha256.take(16)}…)."
            }
        }
    }

    private fun open(uri: Uri): InputStream = resolver.openInputStream(uri) ?: throw IOException("Não foi possível abrir o arquivo")

    // ------------------------------------------------------------------ Checksum

    /** [ventoyPart] = (início, setores) da partição de dados do Ventoy, para arquivos do pendrive. */
    fun checksum(info: UsbDriveInfo?, ventoyPart: Pair<Long, Long>?) {
        val src = _ui.value.checksumSource ?: return
        _ui.update { it.copy(checksum = null) }
        val algs = HashAlgorithm.entries
        ops.run("Checksum: ${src.name}", info?.displayName ?: "Arquivo do celular", info?.deviceName) { cancel, obs ->
            obs.step("Calculando MD5, SHA-1, SHA-256 e SHA-512 (uma única leitura)", 0, 1)
            val values = when (src) {
                is ChecksumSource.Phone -> open(src.file.uri).use { Checksums.compute(it.buffered(1 shl 20), algs, src.file.size, listener(obs), cancel) }
                is ChecksumSource.Drive -> {
                    val i = info ?: throw IOException("Pendrive não selecionado")
                    val (start, count) = ventoyPart ?: throw IOException("Partição de dados do Ventoy não encontrada")
                    session.withDevice(i.deviceName) { dev ->
                        ExFatVolume.open(dev.slice(start, count)).openFile(src.path).use { Checksums.compute(it, algs, src.size, listener(obs), cancel) }
                    }
                }
            }
            values.forEach { (a, v) -> obs.onLog("${a.label}: $v") }
            _ui.update { it.copy(checksum = ChecksumOutcome(src.name, values)) }
            val expected = _ui.value.expected
            if (expected.isNotBlank()) {
                val m = Checksums.match(expected, values)
                if (m == null) throw IOException("O valor informado NÃO confere com nenhum checksum de \"${src.name}\": o arquivo está diferente (corrompido, incompleto ou outra versão).")
                "\"${src.name}\": o ${m.label} confere com o valor informado. Arquivo íntegro."
            } else "Checksums de \"${src.name}\" calculados. Cole o valor oficial na tela para comparar."
        }
    }
}

