package dev.bootforge.isowriter

import dev.bootforge.blockdev.BlockDevice
import dev.bootforge.blockdev.BlockTransfer
import dev.bootforge.blockdev.ProgressListener
import dev.bootforge.blockdev.ProgressTracker
import dev.bootforge.blockdev.RandomReader
import dev.bootforge.blockdev.slice
import dev.bootforge.blockdev.zeroBlocks
import dev.bootforge.fs.fat.Fat32Builder
import dev.bootforge.fs.fat.FatVolume
import dev.bootforge.integrity.CancellationSignal
import dev.bootforge.integrity.Hashing
import dev.bootforge.iso.ExtentRandomReader
import dev.bootforge.iso.ImageTree
import dev.bootforge.partition.Chs
import dev.bootforge.partition.Gpt
import dev.bootforge.partition.GptEntry
import dev.bootforge.partition.GptHeader
import dev.bootforge.partition.Mbr
import dev.bootforge.partition.MbrEntry
import dev.bootforge.partition.crc32
import dev.bootforge.wim.WimSplitter
import java.io.IOException
import java.io.InputStream
import java.util.UUID

/** O que o usuário escolheu na tela de gravação. */
data class WriteRequest(
    val method: WriteMethod,
    val scheme: PartitionScheme = PartitionScheme.MBR,
    /** Rótulo do volume FAT32 (máx. 11 caracteres). null = rótulo da ISO. */
    val label: String? = null,
    /** Reler o pendrive no fim e conferir o SHA-256 de tudo que foi gravado. */
    val verify: Boolean = true,
    /** Tamanho máximo de cada parte .swm (só muda nos testes). */
    val wimPartLimit: Long = WimSplitter.FAT32_MAX,
)

data class WriteReport(
    val method: WriteMethod,
    val scheme: PartitionScheme?,
    val bytesWritten: Long,
    /** SHA-256 da imagem inteira (método DD). */
    val imageSha256: String?,
    val filesWritten: Int,
    /** Partes geradas a partir do install.wim (vazio se não foi dividido). */
    val wimParts: List<String>,
    val verified: Boolean,
    val label: String?,
)

/**
 * Grava uma imagem no pendrive pelo método escolhido. **Apaga todo o conteúdo do dispositivo.**
 *
 * Ordem segura nos métodos FAT32: (1) apaga o início e o fim do disco (tabelas antigas, inclusive
 * a cópia do GPT no final); (2) cria o sistema de arquivos e copia os arquivos; (3) grava a tabela
 * de partições por último — se algo falhar no meio, o pendrive não aparenta estar pronto;
 * (4) relê tudo e confere o SHA-256 de cada arquivo.
 */
object IsoWriter {

    /** Primeira partição em 1 MiB (alinhamento padrão de todas as ferramentas modernas). */
    const val PART_START = 2048L

    fun write(
        dev: BlockDevice,
        source: RandomReader,
        analysis: IsoAnalysis,
        request: WriteRequest,
        listener: ProgressListener = ProgressListener.NONE,
        cancel: CancellationSignal = CancellationSignal.NONE,
    ): WriteReport {
        require(dev.blockSize == 512) { "Apenas dispositivos com setor de 512 bytes são suportados" }
        val option = analysis.option(request.method)
        if (!option.available) throw IOException("Método indisponível: ${option.blockers.joinToString("; ")}")
        val capacity = dev.blockCount * dev.blockSize
        if (option.requiredBytes > capacity) {
            throw IOException("O pendrive (${IsoAnalyzer.gb(capacity)}) é menor que o necessário (${IsoAnalyzer.gb(option.requiredBytes)})")
        }
        return when (request.method) {
            WriteMethod.DD -> writeDd(dev, source, request, listener, cancel)
            WriteMethod.WINDOWS_FAT32, WriteMethod.EXTRACT_FAT32 -> writeFat32(dev, source, analysis, request, listener, cancel)
        }
    }

    // ------------------------------------------------------------------ DD

    private fun writeDd(dev: BlockDevice, src: RandomReader, req: WriteRequest, listener: ProgressListener, cancel: CancellationSignal): WriteReport {
        val size = src.size
        val sectors = (size + 511) / 512
        // Apaga o último 1 MiB: remove a cópia do GPT de uma gravação anterior, que confundiria o sistema.
        val tail = minOf(2048L, dev.blockCount - sectors)
        if (tail > 0) dev.zeroBlocks(dev.blockCount - tail, tail)
        val res = BlockTransfer.writeStream(dev, 0, src.stream(), size, "Gravando imagem", listener, cancel)
        if (req.verify) {
            val back = BlockTransfer.hashRegion(dev, 0, size, "Verificando gravação", listener, cancel)
            if (back != res.sha256) throw IOException("Verificação falhou: o conteúdo relido do pendrive é diferente da imagem (SHA-256 $back ≠ ${res.sha256})")
        }
        return WriteReport(WriteMethod.DD, null, size, res.sha256, 0, emptyList(), req.verify, null)
    }

    // ------------------------------------------------------------------ FAT32

    private fun writeFat32(
        dev: BlockDevice, src: RandomReader, analysis: IsoAnalysis, req: WriteRequest,
        listener: ProgressListener, cancel: CancellationSignal,
    ): WriteReport {
        val tree = ImageTree.open(src) ?: throw IOException("Não foi possível ler os arquivos da imagem")
        val root = Fat32Builder.Dir("/")
        val wimParts = mutableListOf<String>()
        val splitPath = analysis.windowsImage?.takeIf { req.method == WriteMethod.WINDOWS_FAT32 && it.size > req.wimPartLimit }?.path

        tree.walk("/") { path, e ->
            val parts = path.trim('/').split('/')
            var d = root
            parts.dropLast(1).forEach { d = d.dir(it) }
            if (e.isDirectory) { d.dir(parts.last()); return@walk }
            if (splitPath != null && path.equals(splitPath, ignoreCase = true)) {
                val wim = ExtentRandomReader(src, e)
                val base = parts.last().substringBeforeLast('.')
                WimSplitter.plan(wim, req.wimPartLimit, base).forEach { part ->
                    d.file(part.fileName, part.size) { part.open(wim) }
                    wimParts += (parts.dropLast(1) + part.fileName).joinToString("/", prefix = "/")
                }
            } else {
                d.file(parts.last(), e.size) { tree.open(e) }
            }
        }

        val total = dev.blockCount
        val (partStart, partSectors) = partitionRange(total, req.scheme)
        val label = sanitizeLabel(req.label ?: analysis.info.volumeId ?: "BOOTFORGE")
        val opts = Fat32Builder.Options(label = label, partitionOffsetLba = partStart)
        Fat32Builder.plan(partSectors, root, opts) // falha cedo se não couber, antes de apagar qualquer coisa

        // 1) apaga tabelas antigas (início e fim do disco)
        cancel.throwIfCancelled()
        dev.zeroBlocks(0, minOf(PART_START, total))
        dev.zeroBlocks(maxOf(0, total - 2048), minOf(2048L, total))
        dev.flush()

        // 2) sistema de arquivos + arquivos
        val part = dev.slice(partStart, partSectors)
        val built = Fat32Builder.build(part, root, opts, listener, cancel)

        // 3) tabela de partições por último
        cancel.throwIfCancelled()
        when (req.scheme) {
            PartitionScheme.MBR -> writeMbr(dev, partStart, partSectors)
            PartitionScheme.GPT -> writeGpt(dev, partStart, partSectors)
        }
        dev.flush()

        // 4) verificação: relê cada arquivo do pendrive e compara o SHA-256
        if (req.verify) verifyFat(part, built.sha256, built.totalBytes, listener, cancel)
        return WriteReport(req.method, req.scheme, built.totalBytes, null, built.sha256.size, wimParts, req.verify, label)
    }

    /** Relê os arquivos gravados e compara com os hashes calculados durante a gravação. */
    fun verifyFat(part: BlockDevice, hashes: Map<String, String>, totalBytes: Long, listener: ProgressListener, cancel: CancellationSignal) {
        val fat = FatVolume.open(part)
        val tracker = ProgressTracker("Verificando arquivos", totalBytes, listener)
        var done = 0L
        for ((path, expected) in hashes) {
            val base = done
            val got = fat.openFile(path).use { Hashing.hash(it, cancel = cancel) { n -> tracker.update(base + n) } }
            if (got != expected) throw IOException("Verificação falhou em $path: o conteúdo relido é diferente do gravado")
            done += fat.resolve(path)!!.size
        }
        tracker.update(done, force = true)
    }

    /** Início e tamanho da partição: de 1 MiB até o fim (alinhado em 1 MiB). */
    fun partitionRange(totalSectors: Long, scheme: PartitionScheme): Pair<Long, Long> {
        val end = when (scheme) {
            PartitionScheme.MBR -> minOf(totalSectors, 0xFFFFFFFFL)
            PartitionScheme.GPT -> totalSectors - 33 // deixa espaço para a cópia do GPT
        }
        val alignedEnd = end / 2048 * 2048
        if (alignedEnd - PART_START < 2048) throw IOException("Dispositivo pequeno demais")
        return PART_START to (alignedEnd - PART_START)
    }

    private fun writeMbr(dev: BlockDevice, start: Long, count: Long) {
        val mbr = Mbr(mbrBootCode())
        mbr.setEntry(0, MbrEntry(true, Mbr.TYPE_FAT32_LBA, start, count, chs(start), chs(start + count - 1)))
        putDiskSignature(mbr.raw)
        mbr.setBootSignature()
        dev.write(0, mbr.raw)
    }

    private fun writeGpt(dev: BlockDevice, start: Long, count: Long) {
        val total = dev.blockCount
        val mbr = Mbr(mbrBootCode())
        mbr.setEntry(0, MbrEntry(false, Mbr.TYPE_GPT_PROTECTIVE, 1, minOf(total - 1, 0xFFFFFFFFL), Chs(0, 2, 0), Chs(254, 63, 1023)))
        mbr.setBootSignature()
        // "Microsoft Basic Data": o firmware UEFI inicia de qualquer FAT32 em mídia removível, e o
        // Windows/celular continuam mostrando a partição (o tipo "EFI System" a esconderia).
        val entries = Gpt.encodeEntries(listOf(GptEntry(GptEntry.TYPE_MICROSOFT_BASIC_DATA, UUID.randomUUID(), start, start + count - 1, 0, "BootForge")))
        val header = GptHeader(
            currentLba = 1, backupLba = total - 1, firstUsableLba = 34, lastUsableLba = total - 34,
            diskGuid = UUID.randomUUID(), entriesLba = 2, entriesCrc = crc32(entries),
        )
        dev.write(total - 33, entries)
        dev.write(total - 1, header.toBackup().encode())
        val primary = ByteArray(34 * 512)
        mbr.raw.copyInto(primary, 0)
        header.encode().copyInto(primary, 512)
        entries.copyInto(primary, 1024)
        dev.write(0, primary)
    }

    /**
     * Código de boot do MBR: em modo BIOS/Legacy apenas mostra uma mensagem e para — esta mídia
     * só inicia em UEFI (evita o PC executar lixo ou travar sem explicação).
     */
    internal fun mbrBootCode(): ByteArray {
        val b = ByteArray(512)
        val msg = "BootForge: esta midia inicia somente em modo UEFI.\r\nAtive UEFI na BIOS ou use o Ventoy para modo Legacy.\r\n".toByteArray(Charsets.US_ASCII)
        // cli; xor ax,ax; mov ds,ax; mov ss,ax; mov sp,7C00h; sti; cld; mov si,msg
        // loop: lodsb; test al,al; jz hang; mov ah,0Eh; mov bx,7; int 10h; jmp loop
        // hang: hlt; jmp hang
        val code = byteArrayOf(
            0xFA.toByte(), 0x31, 0xC0.toByte(), 0x8E.toByte(), 0xD8.toByte(), 0x8E.toByte(), 0xD0.toByte(),
            0xBC.toByte(), 0x00, 0x7C, 0xFB.toByte(), 0xFC.toByte(), 0xBE.toByte(), 0, 0,
            0xAC.toByte(), 0x84.toByte(), 0xC0.toByte(), 0x74, 0x09, 0xB4.toByte(), 0x0E, 0xBB.toByte(), 0x07, 0x00,
            0xCD.toByte(), 0x10, 0xEB.toByte(), 0xF2.toByte(), 0xF4.toByte(), 0xEB.toByte(), 0xFD.toByte(),
        )
        val msgAddr = 0x7C00 + code.size
        code[13] = msgAddr.toByte(); code[14] = (msgAddr ushr 8).toByte()
        code.copyInto(b, 0); msg.copyInto(b, code.size)
        check(code.size + msg.size <= 440)
        return b
    }

    private fun putDiskSignature(raw: ByteArray) {
        val sig = UUID.randomUUID().leastSignificantBits
        for (i in 0 until 4) raw[0x1B8 + i] = (sig ushr (8 * i)).toByte()
    }

    /** CHS no padrão IBM (255 cabeças × 63 setores); acima de 8 GB usa o valor máximo. */
    private fun chs(lba: Long): Chs {
        val cyl = lba / (255 * 63)
        if (cyl > 1023) return Chs(254, 63, 1023)
        return Chs(((lba / 63) % 255).toInt(), (lba % 63 + 1).toInt(), cyl.toInt())
    }

    /** Rótulo FAT: até 11 caracteres permitidos, em maiúsculas. */
    fun sanitizeLabel(s: String): String =
        s.uppercase().map { if (it.code in 0x20..0x7E && it !in "\"*+,./:;<=>?[\\]|") it else '_' }.joinToString("").trim().take(11).ifEmpty { "BOOTFORGE" }

    private fun RandomReader.stream(): InputStream = object : InputStream() {
        var pos = 0L
        override fun read(): Int { val b = ByteArray(1); return if (read(b, 0, 1) < 0) -1 else b[0].toInt() and 0xFF }
        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (pos >= size) return -1
            val n = this@stream.read(pos, b, off, minOf(len.toLong(), size - pos).toInt())
            if (n > 0) pos += n
            return n
        }
    }
}
