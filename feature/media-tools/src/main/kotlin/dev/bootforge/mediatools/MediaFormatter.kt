package dev.bootforge.mediatools

import dev.bootforge.blockdev.BlockDevice
import dev.bootforge.blockdev.Progress
import dev.bootforge.blockdev.ProgressListener
import dev.bootforge.blockdev.slice
import dev.bootforge.fs.exfat.ExFatFormatter
import dev.bootforge.fs.exfat.ExFatVolume
import dev.bootforge.fs.fat.Fat32Builder
import dev.bootforge.fs.fat.FatVolume
import dev.bootforge.integrity.CancellationSignal
import dev.bootforge.integrity.Diag
import dev.bootforge.partition.FsProbe
import dev.bootforge.partition.FsType
import dev.bootforge.partition.Mbr
import dev.bootforge.partition.PartitionWriter
import java.io.IOException

/** Sistema de arquivos para formatar. */
enum class MediaFs(val title: String, val summary: String) {
    FAT32("FAT32", "Funciona em tudo: Windows, Mac, Linux, TVs, som do carro, videogames e PCs antigos. Arquivo de no máximo 4 GB."),
    EXFAT("exFAT", "Para arquivos maiores que 4 GB (vídeos, ISOs). Funciona no Windows, Mac, Linux e Android recentes; aparelhos antigos podem não ler."),
}

data class FormatRequest(
    val fs: MediaFs,
    /** false = MBR (mais compatível); true = GPT. */
    val gpt: Boolean = false,
    val label: String = "PENDRIVE",
)

data class FormatResult(val fs: MediaFs, val gpt: Boolean, val label: String, val partitionStart: Long, val partitionSectors: Long, val clusterBytes: Int)

/**
 * Formata um pendrive/cartão para USO NORMAL (depois de gravar ISO ou Ventoy, por exemplo):
 * uma partição, FAT32 ou exFAT, MBR ou GPT. **Apaga tudo.**
 *
 * Ordem segura: apaga tabelas antigas → cria o sistema de arquivos → grava a tabela de partições
 * por último → relê e confere (tipo detectado e volume abrindo normalmente).
 */
object MediaFormatter {

    /** Mensagem do setor de boot para um volume de dados (aparece se alguém tentar dar boot nele). */
    const val NOT_BOOTABLE = "Este disco nao e inicializavel. Remova o pendrive e reinicie."

    fun format(dev: BlockDevice, req: FormatRequest, listener: ProgressListener = ProgressListener.NONE, cancel: CancellationSignal = CancellationSignal.NONE): FormatResult {
        require(dev.blockSize == 512) { "Apenas dispositivos com setor de 512 bytes são suportados" }
        if (dev.isReadOnly) throw IOException("Dispositivo aberto somente para leitura")
        val (start, count) = PartitionWriter.range(dev.blockCount, req.gpt)
        val bytes = count * 512
        val label = sanitizeLabel(req.label, req.fs)
        Diag.i("Formatar", "${req.fs.title} ${if (req.gpt) "GPT" else "MBR"} \"$label\": partição $start + $count setores")

        // confere ANTES de apagar qualquer coisa
        if (req.fs == MediaFs.FAT32) Fat32Builder.plan(count, Fat32Builder.Dir("/"), fatOptions(label, start))

        cancel.throwIfCancelled()
        listener.onProgress(Progress("Apagando tabelas antigas", 0, 3, 0.0))
        PartitionWriter.wipeTables(dev)
        val part = dev.slice(start, count)
        listener.onProgress(Progress("Criando o sistema de arquivos", 1, 3, 0.0))
        val cluster = when (req.fs) {
            MediaFs.FAT32 -> Fat32Builder.build(part, Fat32Builder.Dir("/"), fatOptions(label, start), cancel = cancel).layout.clusterSize
            MediaFs.EXFAT -> {
                val c = exfatCluster(bytes)
                ExFatFormatter.format(part, ExFatFormatter.Options(label = label, clusterSizeBytes = c, partitionOffsetLba = start))
                c
            }
        }
        cancel.throwIfCancelled()
        listener.onProgress(Progress("Gravando a tabela de partições", 2, 3, 0.0))
        val type = if (req.fs == MediaFs.FAT32) Mbr.TYPE_FAT32_LBA else Mbr.TYPE_NTFS_EXFAT
        PartitionWriter.writeSingle(dev, req.gpt, start, count, type, name = label)
        dev.flush()

        verify(part, req.fs)
        listener.onProgress(Progress("Concluído", 3, 3, 0.0))
        return FormatResult(req.fs, req.gpt, label, start, count, cluster)
    }

    /** Relê o volume criado: o tipo precisa ser o pedido e o volume precisa abrir sem erro. */
    fun verify(part: BlockDevice, fs: MediaFs) {
        val probed = FsProbe.probe(part).type
        val expected = if (fs == MediaFs.FAT32) FsType.FAT32 else FsType.EXFAT
        if (probed != expected) throw IOException("Verificação falhou: esperado ${expected.label}, encontrado ${probed.label}")
        when (fs) {
            MediaFs.FAT32 -> FatVolume.open(part).list("/")
            MediaFs.EXFAT -> {
                val v = ExFatVolume.open(part)
                v.list("/")
                val check = v.checkConsistency()
                if (check.errors.isNotEmpty()) throw IOException("Verificação falhou: ${check.errors.first()}")
            }
        }
    }

    /** Tamanho de cluster exFAT padrão da Microsoft. */
    fun exfatCluster(bytes: Long): Int = when {
        bytes <= 256L shl 20 -> 4096
        bytes <= 32L shl 30 -> 32 * 1024
        else -> 128 * 1024
    }

    /** Rótulo: FAT32 aceita 11 caracteres ASCII em maiúsculas; exFAT, 11 caracteres Unicode. */
    fun sanitizeLabel(s: String, fs: MediaFs): String {
        val clean = s.trim().ifEmpty { "PENDRIVE" }
        return when (fs) {
            MediaFs.FAT32 -> clean.uppercase().map { if (it.code in 0x20..0x7E && it !in "\"*+,./:;<=>?[\\]|") it else '_' }.joinToString("").take(11).trim().ifEmpty { "PENDRIVE" }
            MediaFs.EXFAT -> clean.filter { it.code >= 0x20 && it !in "\"*/:<>?\\|" }.take(11).ifEmpty { "PENDRIVE" }
        }
    }

    private fun fatOptions(label: String, start: Long) = Fat32Builder.Options(label = label, partitionOffsetLba = start, bootMessage = NOT_BOOTABLE)
}
