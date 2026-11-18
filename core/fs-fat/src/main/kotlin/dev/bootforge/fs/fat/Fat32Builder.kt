package dev.bootforge.fs.fat

import dev.bootforge.blockdev.BlockDevice
import dev.bootforge.blockdev.ProgressListener
import dev.bootforge.blockdev.ProgressTracker
import dev.bootforge.blockdev.zeroBlocks
import dev.bootforge.integrity.CancellationSignal
import dev.bootforge.integrity.HashAlgorithm
import dev.bootforge.integrity.toHex
import java.io.IOException
import java.io.InputStream
import java.time.LocalDateTime
import kotlin.random.Random

/**
 * Cria um volume FAT32 COMPLETO de uma só vez, a partir de uma árvore de pastas e arquivos
 * (ex.: o conteúdo de uma ISO do Windows). Equivale a "formatar + copiar tudo", mas:
 *  - cada arquivo fica em clusters contíguos (leitura rápida pelo firmware UEFI);
 *  - nomes longos (LFN/VFAT) com nome curto 8.3 gerado como no Windows;
 *  - o SHA-256 de cada arquivo é calculado durante a gravação (para verificação posterior).
 *
 * Por que FAT32: é o único sistema de arquivos que TODO firmware UEFI lê. Limite: arquivos de
 * no máximo 4 GiB − 1 byte (por isso o install.wim do Windows é dividido antes).
 */
object Fat32Builder {

    const val MAX_FILE = 0xFFFFFFFFL

    sealed class Node(val name: String)
    class Dir(name: String) : Node(name) {
        val children = mutableListOf<Node>()
        /** Garante a subpasta [name] (sem diferenciar maiúsculas). */
        fun dir(name: String): Dir =
            children.firstOrNull { it is Dir && it.name.equals(name, ignoreCase = true) } as Dir? ?: Dir(name).also { children += it }
        fun file(name: String, size: Long, source: () -> InputStream) { children += File(name, size, source) }
    }
    class File(name: String, val size: Long, val source: () -> InputStream) : Node(name)

    data class Options(
        val label: String = "BOOTFORGE",
        /** LBA do início da partição (campo "hidden sectors" do BPB). */
        val partitionOffsetLba: Long = 2048,
        /** 0 = automático (tabela padrão da Microsoft). */
        val clusterSize: Int = 0,
        val volumeId: Long = Random.nextLong() and 0xFFFFFFFFL,
    )

    data class Layout(
        val sectorsPerCluster: Int, val reservedSectors: Int, val fatSectors: Long, val clusterCount: Long,
        val dataStart: Long, val usedClusters: Long,
    ) {
        val clusterSize get() = sectorsPerCluster * 512
    }

    data class Result(val layout: Layout, val sha256: Map<String, String>, val totalBytes: Long)

    /** Tamanho de cluster padrão da Microsoft para FAT32. */
    fun defaultClusterSize(volumeBytes: Long): Int = when {
        volumeBytes <= 8L shl 30 -> 4096
        volumeBytes <= 16L shl 30 -> 8192
        volumeBytes <= 32L shl 30 -> 16384
        else -> 32768
    }

    // ------------------------------------------------------------------ planejamento

    private class Planned(val node: Node, val path: String, var firstCluster: Long = 0, var clusters: Long = 0, var dirData: ByteArray? = null)

    /** Calcula o layout; lança exceção se não couber ou houver arquivo > 4 GiB. */
    fun plan(volumeSectors: Long, root: Dir, opt: Options): Layout = computeLayout(volumeSectors, root, opt).first

    private fun computeLayout(volumeSectors: Long, root: Dir, opt: Options, autoCluster: Int = 0): Pair<Layout, List<Planned>> {
        val clusterBytes = when {
            opt.clusterSize > 0 -> opt.clusterSize
            autoCluster > 0 -> autoCluster
            else -> defaultClusterSize(volumeSectors * 512)
        }
        val spc = clusterBytes / 512
        // FAT: (clusters + 2) entradas de 4 bytes; resolvido por iteração
        var reserved = 32
        var fat = 1L
        var clusters: Long
        while (true) {
            clusters = (volumeSectors - reserved - 2 * fat) / spc
            val need = ((clusters + 2) * 4 + 511) / 512
            if (need <= fat) break
            fat = need
        }
        // alinha o início dos dados em 1 MiB (relativo ao disco) aumentando os setores reservados
        val dataStartAbs = opt.partitionOffsetLba + reserved + 2 * fat
        val pad = ((2048 - dataStartAbs % 2048) % 2048).toInt()
        reserved += pad
        clusters = (volumeSectors - reserved - 2 * fat) / spc
        if (clusters < 65525 && opt.clusterSize == 0 && spc > 1) {
            // volume pequeno: tenta clusters menores antes de desistir
            return computeLayout(volumeSectors, root, opt, clusterBytes / 2)
        }
        if (clusters < 65525) throw IOException("Volume pequeno demais para FAT32 (mínimo ~ ${65525L * clusterBytes / 1048576} MiB com cluster de ${clusterBytes / 1024} KiB)")
        if (clusters > 0x0FFFFFF5) throw IOException("Volume grande demais para FAT32")

        // pastas primeiro (raiz = cluster 2), depois arquivos
        val planned = mutableListOf<Planned>()
        val dirs = mutableListOf<Planned>()
        val files = mutableListOf<Planned>()
        fun collect(d: Dir, path: String) {
            val p = Planned(d, path); dirs += p; planned += p
            for (c in d.children) {
                val cp = path.trimEnd('/') + "/" + c.name
                when (c) {
                    is Dir -> collect(c, cp)
                    is File -> {
                        if (c.size > MAX_FILE) throw IOException("Arquivo maior que 4 GiB não cabe em FAT32: $cp")
                        files += Planned(c, cp).also { planned += it }
                    }
                }
            }
        }
        collect(root, "/")
        var next = 2L
        for (d in dirs) {
            val entries = directoryEntryCount(d.node as Dir, d.path == "/")
            d.clusters = maxOf(1, (entries * 32L + clusterBytes - 1) / clusterBytes)
            d.firstCluster = next; next += d.clusters
        }
        for (f in files) {
            val size = (f.node as File).size
            f.clusters = (size + clusterBytes - 1) / clusterBytes
            if (f.clusters > 0) { f.firstCluster = next; next += f.clusters }
        }
        val used = next - 2
        if (used > clusters) throw IOException("Conteúdo (${used * clusterBytes} bytes) não cabe no volume (${clusters * clusterBytes} bytes)")
        return Layout(spc, reserved, fat, clusters, reserved + 2 * fat, used) to (dirs + files)
    }

    // ------------------------------------------------------------------ gravação

    fun build(
        dev: BlockDevice, root: Dir, opt: Options,
        listener: ProgressListener = ProgressListener.NONE, cancel: CancellationSignal = CancellationSignal.NONE,
    ): Result {
        require(dev.blockSize == 512) { "FAT32 exige setor de 512 bytes" }
        val (layout, planned) = computeLayout(dev.blockCount, root, opt)
        val spc = layout.sectorsPerCluster
        val clusterBytes = layout.clusterSize
        fun clusterLba(c: Long) = layout.dataStart + (c - 2) * spc

        // 1) diretórios (precisam dos clusters já definidos)
        val byNode = planned.associateBy { it.node }
        val now = LocalDateTime.now()
        for (d in planned.filter { it.node is Dir }) {
            val dir = d.node as Dir
            val buf = ByteArray((d.clusters * clusterBytes).toInt())
            var off = 0
            val used = mutableSetOf<String>()
            if (d.path == "/") {
                off = putShort(buf, off, labelBytes(opt.label), 0x08, 0, 0, now)
            } else {
                val parent = planned.first { p -> p.node is Dir && (p.node as Dir).children.contains(dir) }
                off = putShort(buf, off, ".          ".toByteArray(Charsets.US_ASCII), 0x10, d.firstCluster, 0, now)
                off = putShort(buf, off, "..         ".toByteArray(Charsets.US_ASCII), 0x10, if (parent.path == "/") 0 else parent.firstCluster, 0, now)
            }
            for (c in dir.children) {
                val p = byNode.getValue(c)
                val size = if (c is File) c.size else 0L
                off = putNamed(buf, off, c.name, used, if (c is Dir) 0x10 else 0x20, p.firstCluster, size, now)
            }
            d.dirData = buf
        }

        // 2) região reservada, setores de boot e FSInfo
        dev.zeroBlocks(0, layout.reservedSectors.toLong())
        val boot = bootSector(dev.blockCount, layout, opt)
        val freeClusters = layout.clusterCount - layout.usedClusters
        val fsinfo = fsInfo(freeClusters, layout.usedClusters + 2)
        dev.write(0, boot); dev.write(1, fsinfo); dev.write(6, boot); dev.write(7, fsinfo)

        // 3) FATs (gera a tabela em blocos, sem segurar tudo na memória)
        val chains = planned.filter { it.clusters > 0 }.sortedBy { it.firstCluster }
        val fatEntries = layout.fatSectors * 128
        val chunkSectors = 2048
        var sector = 0L
        var chainIdx = 0
        while (sector < layout.fatSectors) {
            cancel.throwIfCancelled()
            val n = minOf(chunkSectors.toLong(), layout.fatSectors - sector).toInt()
            val buf = ByteArray(n * 512)
            val firstEntry = sector * 128
            for (i in 0 until n * 128) {
                val e = firstEntry + i
                if (e >= fatEntries) break
                val v: Long = when {
                    e == 0L -> 0x0FFFFFF8
                    e == 1L -> 0x0FFFFFFF
                    else -> {
                        while (chainIdx < chains.size && e >= chains[chainIdx].firstCluster + chains[chainIdx].clusters) chainIdx++
                        val ch = chains.getOrNull(chainIdx)
                        if (ch != null && e >= ch.firstCluster) { if (e == ch.firstCluster + ch.clusters - 1) 0x0FFFFFFF else e + 1 } else 0
                    }
                }
                put32(buf, i * 4, v)
            }
            dev.write(layout.reservedSectors + sector, buf)
            dev.write(layout.reservedSectors + layout.fatSectors + sector, buf)
            sector += n
        }

        // 4) conteúdo das pastas
        for (d in planned.filter { it.node is Dir }) dev.write(clusterLba(d.firstCluster), d.dirData!!)

        // 5) arquivos, com SHA-256 e progresso real
        val files = planned.filter { it.node is File }
        val total = files.sumOf { (it.node as File).size }
        val tracker = ProgressTracker("Gravando arquivos", total, listener)
        val hashes = LinkedHashMap<String, String>()
        var done = 0L
        val chunk = ByteArray(maxOf(clusterBytes, 1 shl 20) / clusterBytes * clusterBytes)
        for (p in files) {
            val f = p.node as File
            val md = HashAlgorithm.SHA256.newDigest()
            f.source().use { input ->
                var written = 0L
                var lba = clusterLba(p.firstCluster)
                while (written < f.size) {
                    cancel.throwIfCancelled()
                    val want = minOf(chunk.size.toLong(), f.size - written).toInt()
                    var got = 0
                    while (got < want) {
                        val r = input.read(chunk, got, want - got)
                        if (r < 0) throw IOException("Fonte terminou antes do esperado: ${p.path}")
                        got += r
                    }
                    md.update(chunk, 0, got)
                    val sectors = (got + 511) / 512
                    chunk.fill(0, got, sectors * 512)
                    dev.write(lba, chunk, 0, sectors * 512)
                    lba += sectors
                    written += got
                    done += got
                    tracker.update(done)
                }
            }
            hashes[p.path] = md.digest().toHex()
        }
        tracker.update(done, force = true)
        dev.flush()
        return Result(layout, hashes, total)
    }

    // ------------------------------------------------------------------ estruturas

    private fun bootSector(totalSectors: Long, l: Layout, opt: Options): ByteArray {
        val b = ByteArray(512)
        b[0] = 0xEB.toByte(); b[1] = 0x58; b[2] = 0x90.toByte()
        "MSWIN4.1".toByteArray(Charsets.US_ASCII).copyInto(b, 3) // OEM mais compatível
        put16(b, 11, 512); b[13] = l.sectorsPerCluster.toByte(); put16(b, 14, l.reservedSectors)
        b[16] = 2; put16(b, 17, 0); put16(b, 19, 0); b[21] = 0xF8.toByte(); put16(b, 22, 0)
        put16(b, 24, 63); put16(b, 26, 255); put32(b, 28, opt.partitionOffsetLba); put32(b, 32, totalSectors)
        put32(b, 36, l.fatSectors); put16(b, 40, 0); put16(b, 42, 0); put32(b, 44, 2); put16(b, 48, 1); put16(b, 50, 6)
        b[64] = 0x80.toByte(); b[66] = 0x29; put32(b, 67, opt.volumeId)
        labelBytes(opt.label).copyInto(b, 71)
        "FAT32   ".toByteArray(Charsets.US_ASCII).copyInto(b, 82)
        // Código de boot BIOS: apenas mostra uma mensagem (mídia preparada para UEFI).
        val msg = "Midia UEFI: inicie o PC em modo UEFI.\r\n".toByteArray(Charsets.US_ASCII)
        val code = byteArrayOf(0xFA.toByte(), 0x31, 0xC0.toByte(), 0x8E.toByte(), 0xD8.toByte(), 0xBE.toByte(), 0, 0, // cli; xor ax,ax; mov ds,ax; mov si,msg
            0xFC.toByte(), 0xAC.toByte(), 0x84.toByte(), 0xC0.toByte(), 0x74, 0x06, 0xB4.toByte(), 0x0E, 0xCD.toByte(), 0x10, 0xEB.toByte(), 0xF5.toByte(), 0xF4.toByte(), 0xEB.toByte(), 0xFD.toByte())
        val msgAddr = 0x7C00 + 90 + code.size
        put16(code, 6, msgAddr)
        code.copyInto(b, 90); msg.copyInto(b, 90 + code.size)
        b[510] = 0x55; b[511] = 0xAA.toByte()
        return b
    }

    private fun fsInfo(free: Long, nextFree: Long): ByteArray {
        val b = ByteArray(512)
        put32(b, 0, 0x41615252); put32(b, 484, 0x61417272); put32(b, 488, free); put32(b, 492, nextFree)
        put32(b, 508, 0xAA550000)
        return b
    }

    private fun labelBytes(label: String): ByteArray {
        val clean = label.uppercase().filter { it.code in 0x20..0x7E && it !in "\"*+,./:;<=>?[\\]|" }.take(11)
        return clean.padEnd(11, ' ').toByteArray(Charsets.US_ASCII)
    }

    /** Quantas entradas de 32 bytes a pasta precisa (LFN + 8.3, mais ".", ".." ou o rótulo). */
    private fun directoryEntryCount(d: Dir, isRoot: Boolean): Int {
        var n = if (isRoot) 1 else 2
        for (c in d.children) n += 1 + (if (needsLfn(c.name)) (c.name.length + 12) / 13 else 0)
        return n + 1 // entrada final zerada
    }

    private const val SHORT_CHARS = "ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789!#$%&'()-@^_`{}~"

    /** Nome já é um 8.3 válido em MAIÚSCULAS? Então não precisa de nome longo. */
    private fun needsLfn(name: String): Boolean {
        val base = name.substringBeforeLast('.', name)
        val ext = if (name.contains('.')) name.substringAfterLast('.') else ""
        if (name.count { it == '.' } > 1 || base.isEmpty() || base.length > 8 || ext.length > 3) return true
        return !(base + ext).all { it in SHORT_CHARS }
    }

    private fun putNamed(buf: ByteArray, start: Int, name: String, used: MutableSet<String>, attr: Int, cluster: Long, size: Long, now: LocalDateTime): Int {
        if (name.isEmpty() || name.length > 255 || name.any { it.code < 0x20 || it in "\"*/:<>?\\|" }) throw IOException("Nome inválido para FAT32: $name")
        var off = start
        if (!needsLfn(name)) {
            val base = name.substringBeforeLast('.', name)
            val ext = if (name.contains('.')) name.substringAfterLast('.') else ""
            val sfn = (base.padEnd(8, ' ') + ext.padEnd(3, ' ')).toByteArray(Charsets.US_ASCII)
            if (!used.add(String(sfn))) throw IOException("Nome duplicado: $name")
            return putShort(buf, off, sfn, attr, cluster, size, now)
        }
        val sfn = shortName(name, used)
        var sum = 0
        for (c in sfn) sum = (((sum and 1) shl 7) + (sum ushr 1) + (c.toInt() and 0xFF)) and 0xFF
        val units = name.toCharArray()
        val count = (units.size + 12) / 13
        for (k in count downTo 1) {
            val e = ByteArray(32)
            e[0] = (k or (if (k == count) 0x40 else 0)).toByte()
            e[11] = 0x0F; e[13] = sum.toByte()
            val slots = intArrayOf(1, 3, 5, 7, 9, 14, 16, 18, 20, 22, 24, 28, 30)
            for (j in 0 until 13) {
                val idx = (k - 1) * 13 + j
                val v = when {
                    idx < units.size -> units[idx].code
                    idx == units.size -> 0
                    else -> 0xFFFF
                }
                put16(e, slots[j], v)
            }
            e.copyInto(buf, off); off += 32
        }
        return putShort(buf, off, sfn, attr, cluster, size, now)
    }

    /** Gera o nome curto no estilo do Windows: NOMELO~1.EXT (único na pasta). */
    private fun shortName(name: String, used: MutableSet<String>): ByteArray {
        fun clean(s: String) = s.uppercase().filter { it != ' ' && it != '.' }.map { if (it in SHORT_CHARS) it else '_' }.joinToString("")
        val base = clean(name.substringBeforeLast('.', name)).ifEmpty { "ARQ" }
        val ext = if (name.contains('.') && !name.startsWith(".")) clean(name.substringAfterLast('.')).take(3) else ""
        for (n in 1..999_999) {
            val tail = "~$n"
            val b = base.take(8 - tail.length) + tail
            val s = b.padEnd(8, ' ') + ext.padEnd(3, ' ')
            if (used.add(s)) return s.toByteArray(Charsets.US_ASCII)
        }
        throw IOException("Não foi possível gerar nome curto para $name")
    }

    private fun putShort(buf: ByteArray, off: Int, sfn: ByteArray, attr: Int, cluster: Long, size: Long, now: LocalDateTime): Int {
        sfn.copyInto(buf, off, 0, 11)
        buf[off + 11] = attr.toByte()
        val time = (now.hour shl 11) or (now.minute shl 5) or (now.second / 2)
        val date = ((now.year - 1980).coerceIn(0, 127) shl 9) or (now.monthValue shl 5) or now.dayOfMonth
        if (attr and 0x08 == 0) {
            put16(buf, off + 14, time); put16(buf, off + 16, date); put16(buf, off + 18, date)
        }
        put16(buf, off + 20, ((cluster ushr 16) and 0xFFFF).toInt())
        put16(buf, off + 22, time); put16(buf, off + 24, date)
        put16(buf, off + 26, (cluster and 0xFFFF).toInt())
        put32(buf, off + 28, if (attr and 0x10 != 0) 0 else size)
        return off + 32
    }

    private fun put16(b: ByteArray, o: Int, v: Int) { b[o] = v.toByte(); b[o + 1] = (v ushr 8).toByte() }
    private fun put32(b: ByteArray, o: Int, v: Long) { for (i in 0 until 4) b[o + i] = (v ushr (8 * i)).toByte() }
}
