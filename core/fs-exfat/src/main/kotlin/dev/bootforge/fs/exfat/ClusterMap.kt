package dev.bootforge.fs.exfat

import dev.bootforge.blockdev.BlockDevice
import dev.bootforge.blockdev.readBlocks
import java.io.IOException

/**
 * Controle de alocação de clusters de um volume exFAT:
 *  - Bitmap de alocação (1 bit por cluster) — mantido inteiro na memória (1 MiB para 8 milhões
 *    de clusters, o que cobre um pendrive de 1 TiB com cluster de 128 KiB);
 *  - FAT (cadeias de clusters), lida e gravada por setor com cache.
 *
 * As alterações ficam em memória até [flush], que grava apenas os setores modificados.
 */
internal class ClusterMap(
    private val dev: BlockDevice,
    private val fatOffset: Long,
    val clusterCount: Long,
    private val bitmapLbas: List<Long>,
    private val bitmapBytes: Int,
) {
    private val bps = dev.blockSize
    val bitmap: ByteArray
    private val dirtyBitmapSectors = sortedSetOf<Int>()
    private val fatCache = HashMap<Long, ByteArray>()
    private val dirtyFat = sortedSetOf<Long>()
    /** Próximo ponto de busca (estratégia "next fit", evita reescanear do início). */
    private var hint = 2L

    init {
        val raw = ByteArray(bitmapLbas.size * bps)
        bitmapLbas.forEachIndexed { i, lba -> dev.read(lba, raw, i * bps, bps) }
        bitmap = raw.copyOf(bitmapBytes)
    }

    // ------------------------------------------------------------ bitmap

    fun isUsed(c: Long): Boolean {
        val i = (c - 2).toInt()
        return (bitmap[i ushr 3].toInt() ushr (i and 7)) and 1 == 1
    }

    fun setUsed(c: Long, used: Boolean) {
        require(c in 2..clusterCount + 1) { "Cluster fora do volume: $c" }
        val i = (c - 2).toInt()
        val byteIdx = i ushr 3
        val mask = 1 shl (i and 7)
        val old = bitmap[byteIdx].toInt()
        bitmap[byteIdx] = (if (used) old or mask else old and mask.inv()).toByte()
        dirtyBitmapSectors += byteIdx / bps
    }

    fun freeClusters(): Long {
        var used = 0L
        val full = (clusterCount / 8).toInt()
        for (i in 0 until full) used += Integer.bitCount(bitmap[i].toInt() and 0xFF)
        val rest = (clusterCount % 8).toInt()
        if (rest > 0) used += Integer.bitCount((bitmap[full].toInt() and 0xFF) and ((1 shl rest) - 1))
        return clusterCount - used
    }

    /**
     * Reserva [n] clusters. Tenta primeiro UMA sequência contígua (melhor desempenho e o modo
     * preferido para imagens ISO); se não existir, junta várias sequências (arquivo fragmentado).
     * @return lista de faixas de clusters, na ordem do arquivo.
     */
    fun allocate(n: Long, preferredStart: Long = hint): List<LongRange> {
        if (n <= 0) return emptyList()
        if (n > freeClusters()) throw IOException("Espaço insuficiente no pendrive")
        findRun(n, preferredStart)?.let { start ->
            val r = start until start + n
            r.forEach { setUsed(it, true) }
            hint = r.last + 1
            return listOf(r)
        }
        // Fragmentado: pega as maiores sequências livres primeiro.
        val runs = freeRuns().sortedByDescending { it.last - it.first }
        val out = mutableListOf<LongRange>()
        var left = n
        for (r in runs) {
            if (left == 0L) break
            val len = minOf(left, r.last - r.first + 1)
            val take = r.first until r.first + len
            take.forEach { setUsed(it, true) }
            out += take
            left -= len
        }
        if (left > 0) throw IOException("Espaço insuficiente no pendrive")
        return out.sortedBy { it.first }
    }

    /** Tenta reservar exatamente o cluster [c] (para crescer arquivos/pastas contiguamente). */
    fun tryAllocateAt(c: Long): Boolean {
        if (c < 2 || c > clusterCount + 1 || isUsed(c)) return false
        setUsed(c, true)
        return true
    }

    private fun findRun(n: Long, from: Long): Long? {
        fun scan(start: Long, end: Long): Long? {
            var runStart = -1L
            var c = start
            while (c <= end) {
                // salto rápido: byte totalmente ocupado
                val i = (c - 2).toInt()
                if (i and 7 == 0 && bitmap[i ushr 3].toInt() == -1 && c + 7 <= end) { runStart = -1; c += 8; continue }
                if (!isUsed(c)) {
                    if (runStart < 0) runStart = c
                    if (c - runStart + 1 >= n) return runStart
                } else runStart = -1
                c++
            }
            return null
        }
        val last = clusterCount + 1
        val start = from.coerceIn(2, last)
        return scan(start, last) ?: scan(2, minOf(last, start + n))
    }

    private fun freeRuns(): List<LongRange> {
        val out = mutableListOf<LongRange>()
        var start = -1L
        for (c in 2..clusterCount + 1) {
            if (!isUsed(c)) { if (start < 0) start = c }
            else if (start >= 0) { out += start until c; start = -1 }
        }
        if (start >= 0) out += start..clusterCount + 1
        return out
    }

    fun free(runs: List<LongRange>) {
        for (r in runs) for (c in r) {
            setUsed(c, false)
            if (fatGet(c) != 0L) fatSet(c, 0)
        }
    }

    // ------------------------------------------------------------ FAT

    private fun fatSector(c: Long): Pair<Long, Int> {
        val byteOff = c * 4
        return (fatOffset + byteOff / bps) to (byteOff % bps).toInt()
    }

    fun fatGet(c: Long): Long {
        val (lba, off) = fatSector(c)
        val data = fatCache.getOrPut(lba) { dev.readBlocks(lba, 1) }
        if (fatCache.size > 512 && dirtyFat.isEmpty()) fatCache.keys.retainAll { it == lba }
        return ExFatEntries.u32(data, off)
    }

    fun fatSet(c: Long, value: Long) {
        val (lba, off) = fatSector(c)
        val data = fatCache.getOrPut(lba) { dev.readBlocks(lba, 1) }
        ExFatEntries.put32(data, off, value)
        dirtyFat += lba
    }

    /** Grava a cadeia FAT que liga todas as faixas (última → fim de cadeia 0xFFFFFFFF). */
    fun writeChain(runs: List<LongRange>) {
        val all = runs.flatMap { it.toList() }
        for (i in all.indices) fatSet(all[i], if (i == all.lastIndex) EOC else all[i + 1])
    }

    /** Segue a cadeia FAT a partir de [first]. */
    fun chain(first: Long): List<Long> {
        val out = mutableListOf<Long>()
        var c = first
        while (c in 2..clusterCount + 1) {
            out += c
            if (out.size > clusterCount) throw IOException("Cadeia FAT em laço (volume corrompido)")
            c = fatGet(c)
        }
        return out
    }

    fun flush() {
        for (sector in dirtyBitmapSectors) {
            val buf = ByteArray(bps)
            val start = sector * bps
            bitmap.copyInto(buf, 0, start, minOf(bitmap.size, start + bps))
            // bytes além do tamanho lógico do bitmap preservados como zero
            dev.write(bitmapLbas[sector], buf)
        }
        dirtyBitmapSectors.clear()
        for (lba in dirtyFat) dev.write(lba, fatCache.getValue(lba))
        dirtyFat.clear()
    }

    companion object {
        const val EOC = 0xFFFFFFFFL
    }
}
