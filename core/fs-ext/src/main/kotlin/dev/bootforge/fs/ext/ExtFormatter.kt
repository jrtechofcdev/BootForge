package dev.bootforge.fs.ext

import dev.bootforge.blockdev.BlockDevice
import dev.bootforge.blockdev.zeroBlocks
import java.io.IOException
import java.util.UUID

/**
 * Formatador ext2/ext3 em Kotlin puro (equivalente a `mkfs.ext2` / `mkfs.ext3`).
 *
 * Usado para criar os arquivos de persistência do Ventoy (`.dat`), como o script oficial
 * `CreatePersistentImg.sh` (que aceita ext2, ext3 e ext4). Gera um sistema de arquivos com:
 *  - superbloco "rev 1", inodes de 128 bytes, recursos `filetype`, `sparse_super`, `large_file`;
 *  - diretório raiz, `lost+found` e, opcionalmente, um arquivo de configuração na raiz
 *    (ex.: `persistence.conf` com "/ union" para Debian/Kali);
 *  - ext3: journal interno (inode 8, formato jbd2 v2), como o `mke2fs -j`.
 *
 * Validado com `e2fsck -fn` (e2fsprogs) nos testes.
 */
object ExtFormatter {

    enum class Type { EXT2, EXT3 }

    data class Options(
        val type: Type = Type.EXT3,
        /** Rótulo do volume (máx. 16 bytes). O padrão do Ventoy/Ubuntu é "casper-rw". */
        val label: String = "casper-rw",
        /** Arquivo opcional na raiz: nome → conteúdo (ex.: "persistence.conf" → "/ union\n"). */
        val configFile: Pair<String, String>? = null,
        val uuid: UUID = UUID.randomUUID(),
        val nowSeconds: Long = System.currentTimeMillis() / 1000,
    )

    data class Result(val blockSize: Int, val blocks: Long, val groups: Int, val inodes: Long, val journalBlocks: Long)

    private const val INODE_SIZE = 128
    private const val ROOT_INO = 2
    private const val JOURNAL_INO = 8
    private const val LPF_INO = 11
    private const val CONFIG_INO = 12

    fun format(dev: BlockDevice, opt: Options): Result {
        if (dev.isReadOnly) throw IOException("Dispositivo somente leitura")
        require(opt.label.toByteArray().size <= 16) { "Rótulo com mais de 16 bytes" }
        val totalBytes = dev.sizeBytes
        if (totalBytes < 1L shl 20) throw IOException("Tamanho mínimo: 1 MiB")
        val bs = if (totalBytes < 512L shl 20) 1024 else 4096
        if (bs % dev.blockSize != 0) throw IOException("Setor do dispositivo incompatível")
        val firstDataBlock = if (bs == 1024) 1L else 0L
        val blocksPerGroup = 8L * bs
        val inodeRatio = if (totalBytes < 512L shl 20) 4096L else 16384L
        val inodesPerBlock = bs / INODE_SIZE

        // ---- geometria (remove o último grupo se for pequeno demais, como o mke2fs)
        var blocks = minOf(totalBytes / bs, 0xFFFFFFFFL)
        var groups: Int
        var inodesPerGroup: Long
        var gdtBlocks: Long
        var itableBlocks: Long
        while (true) {
            groups = ((blocks - firstDataBlock + blocksPerGroup - 1) / blocksPerGroup).toInt()
            val wantInodes = maxOf(16L, blocks * bs / inodeRatio)
            inodesPerGroup = (wantInodes + groups - 1) / groups
            inodesPerGroup = ((inodesPerGroup + inodesPerBlock - 1) / inodesPerBlock) * inodesPerBlock
            inodesPerGroup = minOf(inodesPerGroup, blocksPerGroup).coerceAtLeast(16)
            if (inodesPerGroup % 8 != 0L) inodesPerGroup += 8 - inodesPerGroup % 8
            gdtBlocks = (groups * 32L + bs - 1) / bs
            itableBlocks = inodesPerGroup * INODE_SIZE / bs
            val lastStart = firstDataBlock + (groups - 1) * blocksPerGroup
            val lastSize = blocks - lastStart
            val lastOverhead = (if (hasSuper(groups - 1)) 1 + gdtBlocks else 0) + 2 + itableBlocks
            if (groups > 1 && lastSize < lastOverhead + 50) { blocks = lastStart; continue }
            if (lastSize < lastOverhead + 10) throw IOException("Tamanho pequeno demais para ext2")
            break
        }
        val totalInodes = inodesPerGroup * groups

        // ---- layout de cada grupo
        val groupStart = LongArray(groups) { firstDataBlock + it * blocksPerGroup }
        val groupSize = LongArray(groups) { minOf(blocksPerGroup, blocks - groupStart[it]) }
        val blockBitmapAt = LongArray(groups)
        val inodeBitmapAt = LongArray(groups)
        val itableAt = LongArray(groups)
        val used = Array(groups) { java.util.BitSet(blocksPerGroup.toInt()) }
        fun markUsed(b: Long) {
            val g = ((b - firstDataBlock) / blocksPerGroup).toInt()
            used[g].set((b - groupStart[g]).toInt())
        }
        for (g in 0 until groups) {
            var b = groupStart[g]
            if (hasSuper(g)) { repeat((1 + gdtBlocks).toInt()) { markUsed(b++) } }
            blockBitmapAt[g] = b; markUsed(b++)
            inodeBitmapAt[g] = b; markUsed(b++)
            itableAt[g] = b
            repeat(itableBlocks.toInt()) { markUsed(b++) }
        }
        // alocador sequencial de blocos de dados
        var cursor = itableAt[0] + itableBlocks
        fun allocBlock(): Long {
            while (cursor < blocks) {
                val g = ((cursor - firstDataBlock) / blocksPerGroup).toInt()
                if (!used[g].get((cursor - groupStart[g]).toInt())) { markUsed(cursor); return cursor++ }
                cursor++
            }
            throw IOException("Espaço insuficiente para os metadados do ext")
        }

        fun write(block: Long, data: ByteArray) {
            require(data.size % dev.blockSize == 0)
            dev.write(block * bs / dev.blockSize, data)
        }
        fun zero(block: Long, count: Long) = dev.zeroBlocks(block * bs / dev.blockSize, count * bs / dev.blockSize)

        val now = opt.nowSeconds
        val inodes = HashMap<Int, ByteArray>()
        fun inode(mode: Int, size: Long, links: Int, blockList: List<Long>, extraBlocks: Int): ByteArray {
            val i = ByteArray(INODE_SIZE)
            put16(i, 0, mode)
            put32(i, 4, size and 0xFFFFFFFFL)
            put32(i, 8, now); put32(i, 12, now); put32(i, 16, now)
            put16(i, 26, links)
            put32(i, 28, (blockList.size + extraBlocks).toLong() * (bs / 512))
            put32(i, 108, size ushr 32)
            return i
        }

        // ---- diretório raiz
        val rootBlock = allocBlock()
        // lost+found com até 16 KiB (como o mke2fs), limitado aos 12 blocos diretos do inode
        val lpfBlocks = (0 until minOf(12, maxOf(1, 16384 / bs))).map { allocBlock() }
        val root = ByteArray(bs)
        var p = dirEntry(root, 0, ROOT_INO, ".", 2, 12)
        p = dirEntry(root, p, ROOT_INO, "..", 2, 12)
        val cfg = opt.configFile
        if (cfg == null) {
            dirEntry(root, p, LPF_INO, "lost+found", 2, bs - p)
        } else {
            require(cfg.first.isNotEmpty() && cfg.first.length <= 255 && '/' !in cfg.first)
            val lpfLen = recLen("lost+found")
            p = dirEntry(root, p, LPF_INO, "lost+found", 2, lpfLen)
            dirEntry(root, p, CONFIG_INO, cfg.first, 1, bs - p)
        }
        write(rootBlock, root)
        inodes[ROOT_INO] = inode(0x41ED, bs.toLong(), 3, listOf(rootBlock), 0).also { setDirect(it, listOf(rootBlock)) }

        // ---- lost+found
        val lpf0 = ByteArray(bs)
        val q = dirEntry(lpf0, 0, LPF_INO, ".", 2, 12)
        dirEntry(lpf0, q, ROOT_INO, "..", 2, bs - q)
        write(lpfBlocks[0], lpf0)
        for (b in lpfBlocks.drop(1)) write(b, ByteArray(bs).also { put16(it, 4, bs) }) // entrada vazia (inode 0)
        inodes[LPF_INO] = inode(0x41C0, lpfBlocks.size.toLong() * bs, 2, lpfBlocks, 0).also { setDirect(it, lpfBlocks) }

        // ---- arquivo de configuração opcional
        if (cfg != null) {
            val content = cfg.second.toByteArray(Charsets.UTF_8)
            require(content.size <= bs) { "Arquivo de configuração maior que um bloco" }
            val b = allocBlock()
            write(b, content.copyOf(bs))
            inodes[CONFIG_INO] = inode(0x81A4, content.size.toLong(), 1, listOf(b), 0).also { setDirect(it, listOf(b)) }
        }

        // ---- journal (ext3)
        var journalBlocks = 0L
        if (opt.type == Type.EXT3) {
            journalBlocks = defaultJournalBlocks(blocks)
            if (journalBlocks <= 0) throw IOException("Tamanho pequeno demais para ext3 (use ext2)")
            val data = (0 until journalBlocks).map { allocBlock() }
            val map = BlockMap(bs, data, ::allocBlock)
            data.forEach { zero(it, 1) }
            write(data[0], journalSuperblock(bs, journalBlocks, opt.uuid))
            map.indirectBlocks.forEach { (b, content) -> write(b, content) }
            inodes[JOURNAL_INO] = inode(0x8180, journalBlocks * bs, 1, data, map.indirectBlocks.size).also {
                System.arraycopy(map.iBlock, 0, it, 40, 60)
            }
        }

        // ---- tabelas de inodes, bitmaps e descritores
        val usedInodes = if (cfg != null) CONFIG_INO else LPF_INO
        val gdt = ByteArray((gdtBlocks * bs).toInt())
        var freeBlocksTotal = 0L
        for (g in 0 until groups) {
            zero(itableAt[g], itableBlocks)
            // bitmap de blocos (bits além do fim do grupo ficam marcados como "usados": padding)
            val bb = ByteArray(bs)
            for (i in 0 until (8 * bs)) if (i >= groupSize[g] || used[g].get(i)) bb[i / 8] = (bb[i / 8].toInt() or (1 shl (i % 8))).toByte()
            write(blockBitmapAt[g], bb)
            val freeBlocks = groupSize[g] - used[g].cardinality()
            freeBlocksTotal += freeBlocks
            // bitmap de inodes
            val ib = ByteArray(bs)
            for (i in 0 until (8 * bs)) {
                val ino = g * inodesPerGroup + i + 1
                val isUsed = i >= inodesPerGroup || ino <= usedInodes
                if (isUsed) ib[i / 8] = (ib[i / 8].toInt() or (1 shl (i % 8))).toByte()
            }
            write(inodeBitmapAt[g], ib)
            val usedInGroup = (0 until inodesPerGroup).count { g * inodesPerGroup + it + 1 <= usedInodes }
            val o = g * 32
            put32(gdt, o, blockBitmapAt[g]); put32(gdt, o + 4, inodeBitmapAt[g]); put32(gdt, o + 8, itableAt[g])
            put16(gdt, o + 12, freeBlocks.toInt()); put16(gdt, o + 14, (inodesPerGroup - usedInGroup).toInt())
            put16(gdt, o + 16, if (g == 0) 2 else 0)
        }
        // grava os inodes usados (todos no grupo 0)
        for ((ino, data) in inodes) {
            val index = ino - 1
            val blk = itableAt[0] + (index.toLong() * INODE_SIZE) / bs
            val off = (index * INODE_SIZE) % bs
            val sec = ByteArray(bs)
            dev.read(blk * bs / dev.blockSize, sec)
            data.copyInto(sec, off)
            write(blk, sec)
        }

        // ---- superbloco principal + cópias (sparse_super)
        for (g in 0 until groups) {
            if (!hasSuper(g)) continue
            val sb = superblock(opt, bs, blocks, totalInodes, freeBlocksTotal, totalInodes - usedInodes, firstDataBlock,
                blocksPerGroup, inodesPerGroup, g, journalBlocks > 0)
            val start = groupStart[g]
            if (g == 0) {
                // grupo 0: superbloco no byte 1024 do disco
                val first = ByteArray(maxOf(bs, 2048))
                dev.read(0, first, 0, first.size)
                sb.copyInto(first, 1024)
                dev.write(0, first, 0, first.size)
            } else {
                val blk = ByteArray(bs)
                sb.copyInto(blk, 0)
                write(start, blk)
            }
            write(start + 1, gdt)
        }
        dev.flush()
        return Result(bs, blocks, groups, totalInodes, journalBlocks)
    }

    /** Tamanho padrão do journal (tabela clássica do e2fsprogs). */
    fun defaultJournalBlocks(blocks: Long): Long = when {
        blocks < 2048 -> -1
        blocks < 32768 -> 1024
        blocks < 256 * 1024 -> 4096
        blocks < 512 * 1024 -> 8192
        else -> 16384
    }

    private fun hasSuper(g: Int): Boolean {
        if (g <= 1) return true
        for (base in intArrayOf(3, 5, 7)) {
            var v = base.toLong()
            while (v < g) v *= base
            if (v == g.toLong()) return true
        }
        return false
    }

    private fun superblock(
        opt: Options, bs: Int, blocks: Long, inodes: Long, freeBlocks: Long, freeInodes: Long, firstDataBlock: Long,
        blocksPerGroup: Long, inodesPerGroup: Long, groupNr: Int, journal: Boolean,
    ): ByteArray {
        val s = ByteArray(1024)
        put32(s, 0, inodes); put32(s, 4, blocks); put32(s, 8, 0)
        put32(s, 12, freeBlocks); put32(s, 16, freeInodes); put32(s, 20, firstDataBlock)
        val log = Integer.numberOfTrailingZeros(bs) - 10
        put32(s, 24, log.toLong()); put32(s, 28, log.toLong())
        put32(s, 32, blocksPerGroup); put32(s, 36, blocksPerGroup); put32(s, 40, inodesPerGroup)
        put32(s, 44, 0); put32(s, 48, opt.nowSeconds)
        put16(s, 52, 0); put16(s, 54, 0xFFFF)
        put16(s, 56, 0xEF53); put16(s, 58, 1); put16(s, 60, 1); put16(s, 62, 0)
        put32(s, 64, opt.nowSeconds); put32(s, 68, 0); put32(s, 72, 0); put32(s, 76, 1)
        put32(s, 84, 11); put16(s, 88, INODE_SIZE); put16(s, 90, groupNr)
        put32(s, 92, if (journal) 0x0004 else 0)       // compat: has_journal
        put32(s, 96, 0x0002)                            // incompat: filetype
        put32(s, 100, 0x0001 or 0x0002)                 // ro_compat: sparse_super, large_file
        uuidBytes(opt.uuid).copyInto(s, 104)
        opt.label.toByteArray(Charsets.UTF_8).copyInto(s, 120)
        if (journal) put32(s, 224, JOURNAL_INO.toLong())
        put32(s, 264, opt.nowSeconds)
        return s
    }

    private fun journalSuperblock(bs: Int, blocks: Long, uuid: UUID): ByteArray {
        val j = ByteArray(bs)
        be32(j, 0, 0xC03B3998L); be32(j, 4, 4); be32(j, 8, 0)
        be32(j, 12, bs.toLong()); be32(j, 16, blocks); be32(j, 20, 1); be32(j, 24, 1); be32(j, 28, 0)
        uuidBytes(uuid).copyInto(j, 48)
        be32(j, 64, 1)
        return j
    }

    /** Mapa de blocos do inode (12 diretos + indireto simples + duplo). */
    private class BlockMap(bs: Int, data: List<Long>, alloc: () -> Long) {
        val iBlock = ByteArray(60)
        val indirectBlocks = mutableListOf<Pair<Long, ByteArray>>()
        init {
            val per = bs / 4
            var i = 0
            while (i < data.size && i < 12) { put32(iBlock, i * 4, data[i]); i++ }
            if (i < data.size) {
                val ind = alloc()
                val buf = ByteArray(bs)
                var k = 0
                while (i < data.size && k < per) { put32(buf, k * 4, data[i]); i++; k++ }
                indirectBlocks += ind to buf
                put32(iBlock, 12 * 4, ind)
            }
            if (i < data.size) {
                val dind = alloc()
                val dbuf = ByteArray(bs)
                var k = 0
                while (i < data.size && k < per) {
                    val ind = alloc()
                    val buf = ByteArray(bs)
                    var m = 0
                    while (i < data.size && m < per) { put32(buf, m * 4, data[i]); i++; m++ }
                    indirectBlocks += ind to buf
                    put32(dbuf, k * 4, ind)
                    k++
                }
                indirectBlocks += dind to dbuf
                put32(iBlock, 13 * 4, dind)
            }
            if (i < data.size) throw IOException("Journal grande demais para o mapa de blocos")
        }
    }

    private fun setDirect(inode: ByteArray, blocks: List<Long>) {
        require(blocks.size <= 12)
        blocks.forEachIndexed { k, b -> put32(inode, 40 + k * 4, b) }
    }

    private fun recLen(name: String) = (8 + name.toByteArray().size + 3) / 4 * 4

    private fun dirEntry(buf: ByteArray, off: Int, ino: Int, name: String, type: Int, recLen: Int): Int {
        val n = name.toByteArray(Charsets.UTF_8)
        put32(buf, off, ino.toLong()); put16(buf, off + 4, recLen)
        buf[off + 6] = n.size.toByte(); buf[off + 7] = type.toByte()
        n.copyInto(buf, off + 8)
        return off + recLen
    }

    private fun uuidBytes(u: UUID): ByteArray {
        val b = ByteArray(16)
        for (i in 0 until 8) b[i] = (u.mostSignificantBits ushr (56 - 8 * i)).toByte()
        for (i in 0 until 8) b[8 + i] = (u.leastSignificantBits ushr (56 - 8 * i)).toByte()
        return b
    }

    private fun put16(b: ByteArray, o: Int, v: Int) { b[o] = v.toByte(); b[o + 1] = (v ushr 8).toByte() }
    private fun put32(b: ByteArray, o: Int, v: Long) { for (i in 0 until 4) b[o + i] = (v ushr (8 * i)).toByte() }
    private fun be32(b: ByteArray, o: Int, v: Long) { for (i in 0 until 4) b[o + i] = (v ushr (24 - 8 * i)).toByte() }
}
