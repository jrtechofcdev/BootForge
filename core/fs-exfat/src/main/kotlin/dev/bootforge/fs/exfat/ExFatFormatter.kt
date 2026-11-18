package dev.bootforge.fs.exfat

import dev.bootforge.blockdev.BlockDevice
import dev.bootforge.blockdev.zeroBlocks
import java.io.IOException
import kotlin.random.Random

/**
 * Formatador exFAT em Kotlin puro (equivalente ao `mkexfatfs`/`mkfs.exfat`).
 *
 * Implementado a partir da especificação pública da Microsoft
 * ("exFAT File System Specification", 2019). Formatação rápida: grava as regiões de boot,
 * a FAT, o bitmap de alocação, a tabela de maiúsculas (up-case) e o diretório raiz;
 * NÃO apaga a área de dados (como qualquer formatação rápida).
 *
 * Layout gerado (offsets relativos ao início da partição):
 *  - setores 0..11   região de boot principal (boot sector, 8 setores estendidos, OEM, reservado, checksum)
 *  - setores 12..23  cópia de segurança da região de boot
 *  - [fatOffset]     FAT (1 cópia), alinhada a [Options.alignmentBytes]
 *  - [heapOffset]    área de clusters: bitmap → up-case → diretório raiz
 */
object ExFatFormatter {

    data class Options(
        val label: String = "Ventoy",
        /** Tamanho do cluster em bytes (potência de 2, de 512 B a 32 MiB). */
        val clusterSizeBytes: Int,
        /** LBA do início da partição no disco (campo PartitionOffset do boot sector). */
        val partitionOffsetLba: Long,
        /** Alinhamento da FAT e da área de dados. 1 MiB é o padrão do mkfs.exfat. */
        val alignmentBytes: Int = 1 shl 20,
        val volumeSerial: Long = Random.nextLong() and 0xFFFFFFFFL,
    )

    /** Parâmetros calculados (úteis para testes e para exibir ao usuário). */
    data class Geometry(
        val bytesPerSector: Int,
        val sectorsPerCluster: Int,
        val volumeLength: Long,
        val fatOffset: Long,
        val fatLength: Long,
        val clusterHeapOffset: Long,
        val clusterCount: Long,
        val bitmapCluster: Long,
        val bitmapBytes: Long,
        val upcaseCluster: Long,
        val rootCluster: Long,
    )

    /** Tabela up-case padrão (5836 bytes, compactada), a mesma recomendada pela especificação. */
    val upcaseTable: ByteArray by lazy {
        ExFatFormatter::class.java.getResourceAsStream("upcase.bin")?.use { it.readBytes() }
            ?: throw IllegalStateException("Recurso upcase.bin ausente")
    }

    fun computeGeometry(dev: BlockDevice, opt: Options): Geometry {
        val bps = dev.blockSize
        require(bps in setOf(512, 1024, 2048, 4096)) { "Setor de $bps bytes não suportado pelo exFAT" }
        require(opt.clusterSizeBytes >= bps && Integer.bitCount(opt.clusterSizeBytes) == 1 && opt.clusterSizeBytes <= (32 shl 20)) {
            "Tamanho de cluster inválido: ${opt.clusterSizeBytes}"
        }
        val spc = opt.clusterSizeBytes / bps
        val volLen = dev.blockCount
        // Volumes pequenos (testes) não comportam alinhamento de 1 MiB.
        val alignSectors = if (volLen * bps >= 64L * opt.alignmentBytes) (opt.alignmentBytes / bps).toLong() else maxOf(32L, spc.toLong())
        val fatOffset = alignUp(24, alignSectors)
        val maxClusters = (volLen - fatOffset) / spc
        val fatLenUpper = ceilDiv((maxClusters + 2) * 4, bps.toLong())
        val heapOffset = alignUp(fatOffset + fatLenUpper, alignSectors)
        val clusterCount = minOf((volLen - heapOffset) / spc, 0xFFFFFFF5L)
        if (clusterCount < 16) throw IOException("Partição pequena demais para exFAT")
        val fatLength = ceilDiv((clusterCount + 2) * 4, bps.toLong())
        val bitmapBytes = ceilDiv(clusterCount, 8)
        val bitmapClusters = ceilDiv(bitmapBytes, opt.clusterSizeBytes.toLong())
        val upcaseClusters = ceilDiv(upcaseTable.size.toLong(), opt.clusterSizeBytes.toLong())
        return Geometry(
            bytesPerSector = bps,
            sectorsPerCluster = spc,
            volumeLength = volLen,
            fatOffset = fatOffset,
            fatLength = fatLength,
            clusterHeapOffset = heapOffset,
            clusterCount = clusterCount,
            bitmapCluster = 2,
            bitmapBytes = bitmapBytes,
            upcaseCluster = 2 + bitmapClusters,
            rootCluster = 2 + bitmapClusters + upcaseClusters,
        )
    }

    /** Formata [dev] (normalmente a janela de uma partição). APAGA o sistema de arquivos existente. */
    fun format(dev: BlockDevice, opt: Options): Geometry {
        val g = computeGeometry(dev, opt)
        val bps = g.bytesPerSector
        val clusterBytes = g.sectorsPerCluster * bps
        fun clusterLba(c: Long) = g.clusterHeapOffset + (c - 2) * g.sectorsPerCluster

        // 1) Zera a área entre as regiões de boot e a FAT, e a FAT inteira.
        dev.zeroBlocks(0, g.fatOffset + g.fatLength)

        // 2) FAT: entradas 0 e 1 reservadas + cadeias do bitmap, up-case e raiz.
        val usedClusters = g.rootCluster + 1 - 2 // clusters 2..rootCluster
        val fatHeadBytes = ((usedClusters + 2) * 4).toInt()
        val fatHead = ByteArray(ceilDiv(fatHeadBytes.toLong(), bps.toLong()).toInt() * bps)
        put32(fatHead, 0, 0xFFFFFFF8L)
        put32(fatHead, 4, 0xFFFFFFFFL)
        fun chain(first: Long, lastExclusive: Long) {
            for (c in first until lastExclusive) {
                put32(fatHead, (c * 4).toInt(), if (c == lastExclusive - 1) 0xFFFFFFFFL else c + 1)
            }
        }
        chain(g.bitmapCluster, g.upcaseCluster)
        chain(g.upcaseCluster, g.rootCluster)
        chain(g.rootCluster, g.rootCluster + 1)
        dev.write(g.fatOffset, fatHead)

        // 3) Bitmap de alocação (zera todos os clusters do bitmap e marca os usados).
        val bitmapClusters = g.upcaseCluster - g.bitmapCluster
        dev.zeroBlocks(clusterLba(g.bitmapCluster), bitmapClusters * g.sectorsPerCluster)
        val bitmapHead = ByteArray(ceilDiv(ceilDiv(usedClusters, 8), bps.toLong()).toInt() * bps)
        for (i in 0 until usedClusters.toInt()) bitmapHead[i / 8] = (bitmapHead[i / 8].toInt() or (1 shl (i % 8))).toByte()
        dev.write(clusterLba(g.bitmapCluster), bitmapHead)

        // 4) Tabela up-case.
        val upcaseClusters = g.rootCluster - g.upcaseCluster
        val upcaseBuf = ByteArray((upcaseClusters * clusterBytes).toInt())
        upcaseTable.copyInto(upcaseBuf)
        dev.write(clusterLba(g.upcaseCluster), upcaseBuf)

        // 5) Diretório raiz: rótulo + bitmap + up-case.
        val root = ByteArray(clusterBytes)
        val label = opt.label.take(11)
        if (label.isNotEmpty()) {
            root[0] = 0x83.toByte()
            root[1] = label.length.toByte()
            label.toByteArray(Charsets.UTF_16LE).copyInto(root, 2)
        } else {
            root[0] = 0x03 // entrada de rótulo "vazia"
        }
        root[32] = 0x81.toByte()
        put32(root, 32 + 20, g.bitmapCluster)
        put64(root, 32 + 24, g.bitmapBytes)
        root[64] = 0x82.toByte()
        put32(root, 64 + 4, tableChecksum(upcaseTable))
        put32(root, 64 + 20, g.upcaseCluster)
        put64(root, 64 + 24, upcaseTable.size.toLong())
        dev.write(clusterLba(g.rootCluster), root)

        // 6) Regiões de boot (principal e backup) — gravadas por último.
        val bootRegion = buildBootRegion(g, opt, usedClusters)
        dev.write(12, bootRegion) // backup primeiro
        dev.write(0, bootRegion)
        dev.flush()
        return g
    }

    private fun buildBootRegion(g: Geometry, opt: Options, usedClusters: Long): ByteArray {
        val bps = g.bytesPerSector
        val region = ByteArray(12 * bps)
        // --- Setor 0: Main Boot Sector ---
        region[0] = 0xEB.toByte(); region[1] = 0x76; region[2] = 0x90.toByte()
        "EXFAT   ".toByteArray(Charsets.US_ASCII).copyInto(region, 3)
        put64(region, 64, opt.partitionOffsetLba)
        put64(region, 72, g.volumeLength)
        put32(region, 80, g.fatOffset)
        put32(region, 84, g.fatLength)
        put32(region, 88, g.clusterHeapOffset)
        put32(region, 92, g.clusterCount)
        put32(region, 96, g.rootCluster)
        put32(region, 100, opt.volumeSerial)
        region[104] = 0x00; region[105] = 0x01 // FileSystemRevision 1.00
        region[106] = 0; region[107] = 0       // VolumeFlags
        region[108] = Integer.numberOfTrailingZeros(bps).toByte()
        region[109] = Integer.numberOfTrailingZeros(g.sectorsPerCluster).toByte()
        region[110] = 1                         // NumberOfFats
        region[111] = 0x80.toByte()             // DriveSelect
        region[112] = (usedClusters * 100 / g.clusterCount).toInt().coerceIn(0, 100).toByte() // PercentInUse
        region.fill(0xF4.toByte(), 120, 510)    // BootCode: instrução HLT (não inicializável)
        region[510] = 0x55; region[511] = 0xAA.toByte()
        // --- Setores 1..8: Extended Boot Sectors (assinatura 0xAA550000 no fim) ---
        for (s in 1..8) {
            region[s * bps + bps - 2] = 0x55
            region[s * bps + bps - 1] = 0xAA.toByte()
        }
        // Setores 9 (OEM) e 10 (reservado) ficam zerados.
        // --- Setor 11: checksum repetido ---
        val sum = bootChecksum(region, bps)
        for (o in 0 until bps step 4) put32(region, 11 * bps + o, sum)
        return region
    }

    /** Checksum da região de boot (especificação, seção 3.4), ignorando VolumeFlags e PercentInUse. */
    fun bootChecksum(region: ByteArray, bps: Int): Long {
        var sum = 0L
        for (i in 0 until bps * 11) {
            if (i == 106 || i == 107 || i == 112) continue
            sum = (((sum shl 31) or (sum ushr 1)) + (region[i].toLong() and 0xFF)) and 0xFFFFFFFFL
        }
        return sum
    }

    /** Checksum da tabela up-case (especificação, seção 7.2.2). */
    fun tableChecksum(table: ByteArray): Long {
        var sum = 0L
        for (b in table) sum = (((sum shl 31) or (sum ushr 1)) + (b.toLong() and 0xFF)) and 0xFFFFFFFFL
        return sum
    }

    /**
     * Tamanho de cluster usado pelo Ventoy2Disk (função `FormatPart1exFAT`):
     * disco de até 32 GiB → 32 KiB; acima disso → 128 KiB.
     */
    fun ventoyClusterSize(diskSizeBytes: Long): Int =
        if (diskSizeBytes / 1024 / 1024 / 1024 <= 32) 32 * 1024 else 128 * 1024

    private fun alignUp(v: Long, a: Long) = (v + a - 1) / a * a
    private fun ceilDiv(a: Long, b: Long) = (a + b - 1) / b
    internal fun put32(b: ByteArray, o: Int, v: Long) {
        for (i in 0 until 4) b[o + i] = (v ushr (8 * i)).toByte()
    }
    internal fun put64(b: ByteArray, o: Int, v: Long) {
        for (i in 0 until 8) b[o + i] = (v ushr (8 * i)).toByte()
    }
}
