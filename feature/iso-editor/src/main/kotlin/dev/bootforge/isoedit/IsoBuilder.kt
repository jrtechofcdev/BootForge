package dev.bootforge.isoedit

import dev.bootforge.blockdev.ProgressListener
import dev.bootforge.integrity.CancellationSignal
import dev.bootforge.partition.crc32
import java.io.IOException
import java.io.OutputStream
import java.time.ZonedDateTime
import java.util.IdentityHashMap

/** Como a nova ISO é montada. */
enum class RebuildMode(val title: String, val summary: String) {
    PRESERVE(
        "Preservar inicialização",
        "Os arquivos não modificados ficam exatamente onde estavam; só pastas e arquivos novos são gravados no fim. " +
            "Mantém El Torito, MBR/GPT híbridos e carregadores. Recomendado para ISOs Linux.",
    ),
    FULL(
        "Reconstrução completa",
        "Gera uma ISO nova do zero (ISO9660 + Joliet + Rock Ridge/UDF + El Torito), sem espaço desperdiçado. " +
            "Necessária para ISOs do Windows (UDF). A tabela de partições híbrida não é recriada.",
    ),
}

/** Uma imagem de boot na ISO nova (para a validação conferir). */
data class PlannedBoot(val entry: BootEntry, val path: String?, val newLba: Long, val size: Long, val expected: () -> ByteArray)

/** Resultado do planejamento: tamanho final, avisos e a "receita" para gravar. */
class BuildPlan internal constructor(
    val mode: RebuildMode,
    val totalBytes: Long,
    val warnings: List<String>,
    /** O que acontece com a inicialização (texto honesto para o usuário). */
    val bootSummary: String,
    val boots: List<PlannedBoot>,
    /** A ISO nova terá MBR/GPT híbrido? */
    val hybrid: Boolean,
    val hasUdf: Boolean,
    val hasJoliet: Boolean,
    val hasRockRidge: Boolean,
    /** Bytes de dados antigos que continuam na imagem sem uso (modo Preservar). */
    val wastedBytes: Long,
    internal val root: VDir,
    internal val layout: ImageLayout,
    private val session: EditSession,
) {
    /** Grava a nova ISO em [out] (sequencialmente). Devolve o SHA-256 do arquivo gerado. */
    fun write(out: OutputStream, listener: ProgressListener = ProgressListener.NONE, cancel: CancellationSignal = CancellationSignal.NONE): String =
        layout.write(session.source.reader, out, listener, cancel)
}

/**
 * Monta a nova ISO a partir da sessão de edição. Nunca altera a imagem original.
 */
object IsoBuilder {

    /** Modos possíveis para esta imagem e, se não for possível, o motivo. */
    fun availability(src: IsoSource): Map<RebuildMode, String?> = mapOf(
        RebuildMode.PRESERVE to when {
            src.blockers.isNotEmpty() -> src.blockers.first()
            src.hasUdf -> "A imagem usa UDF (ex.: Windows): os dados do UDF ficariam desatualizados. Use a reconstrução completa."
            else -> null
        },
        RebuildMode.FULL to src.blockers.firstOrNull(),
    )

    fun recommended(src: IsoSource): RebuildMode = if (availability(src)[RebuildMode.PRESERVE] == null) RebuildMode.PRESERVE else RebuildMode.FULL

    fun plan(session: EditSession, mode: RebuildMode, volumeId: String? = null): BuildPlan {
        availability(session.source)[mode]?.let { throw IOException(it) }
        return when (mode) {
            RebuildMode.PRESERVE -> PreservePlanner(session, volumeId).plan()
            RebuildMode.FULL -> FullPlanner(session, volumeId).plan()
        }
    }

    // ------------------------------------------------------------------ utilidades comuns

    /** Divide um arquivo em trechos de no máximo 4 GiB − 2048 (ISO9660 multi-extent). */
    internal fun contiguousExtents(lba: Long, size: Long): FileExtents {
        if (size == 0L) return listOf(0L to 0L)
        val out = mutableListOf<Pair<Long, Long>>()
        var left = size; var l = lba
        while (left > 0) { val n = minOf(left, MAX_EXTENT); out += l to n; l += (n + 2047) / 2048; left -= n }
        return out
    }

    /** Aplica nos bytes de um descritor de volume a nova árvore e o tamanho do volume. */
    internal fun patchDescriptor(d: ByteArray, tree: DirTreeWriter, totalSectors: Long, volumeId: String?) {
        Susp.bb(d, 80, totalSectors)
        Susp.bb(d, 132, tree.pathTableBytes)
        Susp.put32le(d, 140, tree.lPathLba); Susp.put32le(d, 144, 0)
        Susp.put32be(d, 148, tree.mPathLba); Susp.put32be(d, 152, 0)
        tree.rootRecord().copyInto(d, 156)
        if (volumeId != null) {
            val joliet = d[0].toInt() == 2 && d[88].toInt() == 0x25
            val bytes = if (joliet) volumeId.take(16).padEnd(16, ' ').toByteArray(Charsets.UTF_16BE)
            else volumeId.uppercase().map { if (it in 'A'..'Z' || it in '0'..'9' || it == '_') it else '_' }.joinToString("").take(32).padEnd(32, ' ').toByteArray(Charsets.ISO_8859_1)
            bytes.copyInto(d, 40)
        }
        // data de modificação do volume (17 bytes ASCII + fuso)
        val now = ZonedDateTime.now()
        val txt = String.format(java.util.Locale.ROOT, "%04d%02d%02d%02d%02d%02d00", now.year, now.monthValue, now.dayOfMonth, now.hour, now.minute, now.second)
        txt.toByteArray(Charsets.US_ASCII).copyInto(d, 830); d[846] = (now.offset.totalSeconds / 900).toByte()
    }

    internal fun sizeText(b: Long) = String.format(java.util.Locale.ROOT, "%.1f MB", b / 1048576.0)
}

/**
 * Modo PRESERVAR: a imagem original é copiada inteira (com correções pontuais) e o que é novo
 * vai para o fim. Como os arquivos não modificados mantêm seus setores, tudo que aponta para
 * eles por número de setor continua válido: catálogo El Torito, código do MBR isohybrid,
 * partições do MBR/GPT que apontam para a imagem EFI, boot-info-table do isolinux etc.
 */
private class PreservePlanner(private val session: EditSession, private val volumeId: String?) {
    private val src = session.source
    private val root = session.root
    private val warnings = mutableListOf<String>()

    fun plan(): BuildPlan {
        val origSectors = (src.size + 2047) / 2048
        val alloc = SectorAllocator(origSectors)
        val extents = IdentityHashMap<VFile, FileExtents>()
        val pieces = mutableListOf<Piece>()
        val byLba = HashMap<Long, VFile>()
        val newFiles = IdentityHashMap<VFile, Long>()

        // 1) dados dos arquivos: originais ficam onde estão; novos vão para o fim
        Paths.walk(root) { p, n ->
            if (n !is VFile) return@walk
            when (val s = n.source) {
                is Source.Original -> {
                    extents[n] = s.extents.map { (if (it.offset < 0) 0 else it.offset / 2048) to it.length }.ifEmpty { listOf(0L to 0L) }
                    s.extents.firstOrNull()?.let { byLba.putIfAbsent(it.offset / 2048, n) }
                }
                is Source.External -> {
                    val lba = if (n.size == 0L) 0L else alloc.allocBytes(n.size)
                    extents[n] = IsoBuilder.contiguousExtents(lba, n.size)
                    if (n.size > 0) { newFiles[n] = lba; pieces += StreamPiece(lba * 2048, n.size, p) { session.open(n) } }
                }
            }
        }

        // 2) imagens de boot: as não modificadas continuam no mesmo setor
        val relocated = HashMap<BootEntry, Pair<Long, Long>>() // entrada → (novo LBA, novo tamanho)
        val boots = mutableListOf<PlannedBoot>()
        for (bi in src.bootImages) {
            val e = bi.entry
            val same = byLba[e.lba]
            if (same != null || bi.path == null) {
                val size = same?.size ?: bi.size
                boots += PlannedBoot(e, bi.path, e.lba, size) { readOriginal(e.lba * 2048, size) }
                continue
            }
            val now = Paths.find(root, bi.path) as? VFile
            val lba = now?.let { newFiles[it] }
            if (now == null || lba == null) {
                warnings += "A imagem de boot ${bi.path} foi removida da árvore, mas os dados dela continuam na ISO: a inicialização (${e.platformName}) continua funcionando."
                boots += PlannedBoot(e, null, e.lba, bi.size) { readOriginal(e.lba * 2048, bi.size) }
                continue
            }
            // imagem de boot substituída: recalcula as tabelas internas e corrige as referências
            if (now.size > 64L shl 20) throw IOException("Imagem de boot grande demais: ${bi.path}")
            val data = session.open(now).use { it.readBytes() }
            // ordem importa: o grub2-boot-info fica dentro da área somada pela boot-info-table
            if (bi.grub2BootInfo) ElTorito.applyGrub2BootInfo(data, lba)
            if (bi.bootInfoTable) ElTorito.applyBootInfoTable(data, lba)
            pieces.removeAll { it is StreamPiece && it.offset == lba * 2048 }
            pieces += BytesPiece(lba * 2048, data)
            relocated[e] = lba to now.size
            boots += PlannedBoot(e, bi.path, lba, now.size) { data }
            warnings += "A imagem de boot ${bi.path} foi substituída: o catálogo El Torito e as referências foram atualizados, mas a inicialização ${e.platformName} depende do novo conteúdo."
        }

        // 3) pastas (ISO9660 + Rock Ridge e Joliet), áreas de continuação e tabelas de caminho
        val now = ZonedDateTime.now()
        val rr = src.rrRootSusp?.let { RrConfig(it, src.rrPxWithIno) }
        val primary = DirTreeWriter(root, false, rr, extents, now).apply { measure(); assign(alloc) }
        val joliet = src.joliet?.let { DirTreeWriter(root, true, null, extents, now).apply { measure(); assign(alloc) } }
        pieces += primary.encode()
        joliet?.let { pieces += it.encode() }

        // 4) tamanho final (com espaço para a cópia do GPT no fim)
        var totalSectors = alloc.next
        val gpt = if (src.hasGpt) src.systemArea.copyOfRange(512, 1024) else null
        val gptEntriesLba = gpt?.let { ElTorito.u64(it, 72) } ?: 0
        val gptEntriesBytes = gpt?.let { Susp.u32le(it, 80) * Susp.u32le(it, 84) } ?: 0
        val gptEntries512 = (gptEntriesBytes + 511) / 512
        if (gpt != null) totalSectors = ((totalSectors * 4 + gptEntries512 + 1) + 3) / 4
        // ISOs híbridas costumam ter tamanho múltiplo de 1 MiB ("cilindro" do isohybrid): mantém
        if (src.isHybrid && src.size % (1L shl 20) == 0L) totalSectors = (totalSectors + 511) / 512 * 512
        val total512 = totalSectors * 4
        val old512 = src.size / 512

        // 5) correções na parte original
        val patches = mutableListOf<Pair<Long, ByteArray>>()
        for (vd in src.descriptors) {
            val d = vd.data.copyOf()
            when {
                vd.type == 1 -> IsoBuilder.patchDescriptor(d, primary, totalSectors, volumeId)
                vd.isJoliet -> IsoBuilder.patchDescriptor(d, joliet!!, totalSectors, volumeId)
                vd.type == 2 -> IsoBuilder.patchDescriptor(d, primary, totalSectors, null) // ISO9660:1999 etc.
                else -> continue
            }
            patches += vd.lba * 2048 to d
        }
        if (relocated.isNotEmpty() && src.catalog != null) {
            val cat = src.catalog
            val counts = relocated.filter { (e, v) -> e.sectorCount.toLong() == (oldSizeOf(e) + 511) / 512 && v.second <= 65535L * 512 }
                .mapValues { ((it.value.second + 511) / 512).toInt() }
            patches += cat.lba * 2048 to cat.patched(relocated.mapValues { it.value.first }, counts)
        }
        val moves = relocated.map { (e, v) -> Triple(e.lba * 4, v.first * 4, (oldSizeOf(e) + 511) / 512 to (v.second + 511) / 512) }
        if (src.hasMbr) patches += 0L to patchMbr(src.systemArea.copyOf(512), old512, total512, moves)
        if (gpt != null) {
            val entriesOff = gptEntriesLba * 512
            val entries = readOriginal(entriesOff, gptEntriesBytes.toInt())
            val oldLastUsable = ElTorito.u64(gpt, 48)
            val backupHeader = total512 - 1
            val backupEntries = backupHeader - gptEntries512
            val newLastUsable = backupEntries - 1
            val entrySize = Susp.u32le(gpt, 84).toInt()
            for (i in 0 until Susp.u32le(gpt, 80).toInt()) {
                val o = i * entrySize
                if ((0 until 16).all { entries[o + it].toInt() == 0 }) continue
                var first = ElTorito.u64(entries, o + 32); var last = ElTorito.u64(entries, o + 40)
                moves.firstOrNull { it.first == first }?.let { (_, to, sizes) ->
                    if (last - first + 1 == sizes.first) last = to + sizes.second - 1 else last += to - first
                    first = to
                }
                if (last >= oldLastUsable - 64 && last <= oldLastUsable) last += newLastUsable - oldLastUsable
                ElTorito.put64(entries, o + 32, first); ElTorito.put64(entries, o + 40, last)
            }
            val header = gpt.copyOf()
            ElTorito.put64(header, 32, backupHeader); ElTorito.put64(header, 48, newLastUsable)
            Susp.put32le(header, 88, crc32(entries))
            gptCrc(header)
            val backup = header.copyOf()
            ElTorito.put64(backup, 24, backupHeader); ElTorito.put64(backup, 32, 1); ElTorito.put64(backup, 72, backupEntries)
            gptCrc(backup)
            patches += 512L to header
            patches += entriesOff to entries
            pieces += BytesPiece(backupEntries * 512, entries.copyOf((gptEntries512 * 512).toInt()) + backup)
        }
        if (src.hasApm) warnings += "A imagem tem mapa de partições Apple (Macs antigos), que não é atualizado."

        pieces += CopyPiece(0, 0, src.size, patches.filter { it.first < src.size })

        // espaço que ficou sem uso (dados antigos de arquivos removidos/substituídos)
        val usedNow = HashSet<Long>(); Paths.walk(root) { _, n -> ((n as? VFile)?.source as? Source.Original)?.extents?.firstOrNull()?.let { usedNow += it.offset } }
        var wasted = 0L
        Paths.walk(src.root) { _, n ->
            val f = n as? VFile ?: return@walk
            val off = (f.source as? Source.Original)?.extents?.firstOrNull()?.offset ?: return@walk
            if (off !in usedNow && f.size > 0) { wasted += f.size; usedNow += off }
        }
        if (wasted > 1L shl 20) warnings += "${IsoBuilder.sizeText(wasted)} de dados antigos (arquivos removidos ou substituídos) continuam dentro da ISO. Isso é o que permite preservar a inicialização."

        val bootSummary = when {
            src.catalog == null && !src.isHybrid -> "A imagem original não é inicializável."
            else -> "Estruturas de inicialização mantidas no lugar: " + listOfNotNull(
                src.catalog?.let { "El Torito (${it.entries.joinToString { e -> e.platformName }})" },
                "MBR".takeIf { src.hasMbr }, "GPT".takeIf { src.hasGpt },
            ).joinToString(", ") + ". Serão conferidas na validação."
        }
        return BuildPlan(
            RebuildMode.PRESERVE, totalSectors * 2048, warnings + src.warnings, bootSummary, boots, src.isHybrid,
            hasUdf = false, hasJoliet = joliet != null, hasRockRidge = rr != null, wastedBytes = wasted,
            root = root, layout = ImageLayout(pieces, totalSectors * 2048), session = session,
        )
    }

    private fun oldSizeOf(e: BootEntry): Long = src.bootImages.first { it.entry == e }.size

    private fun readOriginal(off: Long, len: Int): ByteArray = src.reader.readFully(off, len)
    private fun readOriginal(off: Long, len: Long): ByteArray = src.reader.readFully(off, len.toInt())

    /** Ajusta as partições do MBR: a que cobre a ISO inteira cresce; as que apontam para imagens movidas acompanham. */
    private fun patchMbr(mbr: ByteArray, old512: Long, new512: Long, moves: List<Triple<Long, Long, Pair<Long, Long>>>): ByteArray {
        for (i in 0 until 4) {
            val o = 0x1BE + i * 16
            if ((mbr[o + 4].toInt() and 0xFF) == 0 && Susp.u32le(mbr, o + 12) == 0L) continue
            var start = Susp.u32le(mbr, o + 8); var count = Susp.u32le(mbr, o + 12)
            val mv = moves.firstOrNull { it.first == start }
            if (mv != null) {
                count = if (count == mv.third.first) mv.third.second else count
                start = mv.second
            } else if (start + count >= old512 - 64 && start + count <= old512 + 64 && (mbr[o + 4].toInt() and 0xFF) != 0xEE) {
                count += new512 - old512
            } else if ((mbr[o + 4].toInt() and 0xFF) == 0xEE) {
                count = minOf(new512 - 1, 0xFFFFFFFFL)
            }
            val oldEnd = Susp.u32le(mbr, o + 8) + Susp.u32le(mbr, o + 12) - 1
            Susp.put32le(mbr, o + 8, start); Susp.put32le(mbr, o + 12, minOf(count, 0xFFFFFFFFL))
            // CHS (legado): recalcula com a mesma geometria usada no original
            geometry(mbr, o + 5, oldEnd)?.let { (h, sct) ->
                putChs(mbr, o + 1, start, h, sct); putChs(mbr, o + 5, start + count - 1, h, sct)
            }
        }
        // código isohybrid: LBA×4 da imagem de boot BIOS no byte 0x1B0
        val ref = ElTorito.u64(mbr, 0x1B0)
        moves.firstOrNull { ref >= it.first && ref <= it.first + 5 }?.let { ElTorito.put64(mbr, 0x1B0, it.second + (ref - it.first)) }
        return mbr
    }

    /** Descobre cabeças/setores (ex.: 64/32 do isohybrid, 255/63) que geram o CHS gravado. */
    private fun geometry(b: ByteArray, o: Int, lba: Long): Pair<Int, Int>? {
        val head = b[o].toInt() and 0xFF
        val sec = b[o + 1].toInt() and 0x3F
        val cyl = ((b[o + 1].toInt() and 0xC0) shl 2) or (b[o + 2].toInt() and 0xFF)
        if (sec == 0) return null
        return listOf(64 to 32, 255 to 63, 16 to 63, 128 to 32, 32 to 32, 255 to 32).firstOrNull { (h, s) ->
            val c = lba / (h * s)
            c <= 1023 && c.toInt() == cyl && ((lba / s) % h).toInt() == head && (lba % s + 1).toInt() == sec
        }
    }

    private fun putChs(b: ByteArray, o: Int, lba: Long, h: Int, s: Int) {
        var c = lba / (h * s); var hh = ((lba / s) % h).toInt(); var ss = (lba % s + 1).toInt()
        if (c > 1023) { c = 1023; hh = h - 1; ss = s }
        b[o] = hh.toByte(); b[o + 1] = (ss or ((c.toInt() shr 2) and 0xC0)).toByte(); b[o + 2] = (c and 0xFF).toByte()
    }

    private fun gptCrc(h: ByteArray) {
        val size = Susp.u32le(h, 12).toInt()
        Susp.put32le(h, 16, 0)
        Susp.put32le(h, 16, crc32(h, 0, size))
    }
}

/**
 * Modo RECONSTRUÇÃO COMPLETA: monta uma ISO nova do zero. Ordem dos setores:
 *  0–15 área de sistema (zerada) · 16+ descritores de volume (+ sequência UDF) ·
 *  [UDF: descritores em 32–255, âncora em 256, partição a partir de 257] ·
 *  catálogo El Torito · imagens de boot · pastas e tabelas · dados dos arquivos ·
 *  [UDF: âncora final no último setor].
 */
private class FullPlanner(private val session: EditSession, private val volumeId: String?) {
    private val src = session.source
    private val root = session.root
    private val warnings = mutableListOf<String>()

    fun plan(): BuildPlan {
        val udf = src.hasUdf
        val catalog = src.catalog
        val pieces = mutableListOf<Piece>()
        val extents = IdentityHashMap<VFile, FileExtents>()
        val now = ZonedDateTime.now()

        // descritores: PVD, Boot Record, Joliet, terminador [+ BEA01/NSR02/TEA01]
        var vdLba = 16L
        val pvdLba = vdLba++
        val brLba = if (catalog != null) vdLba++ else -1
        val jolLba = vdLba++
        val termLba = vdLba++
        val vrsLba = if (udf) vdLba.also { vdLba += 3 } else -1
        val alloc = SectorAllocator(if (udf) UdfWriter.PARTITION_START else vdLba)

        // catálogo e imagens de boot logo no início (firmwares antigos preferem assim)
        val catLba = catalog?.let { alloc.alloc((it.raw.size / 2048).toLong()) } ?: 0
        val files = LinkedHashMap<VFile, String>()
        Paths.walk(root) { p, n -> if (n is VFile) files[n] = p }
        val byOrigLba = HashMap<Long, VFile>()
        files.keys.forEach { f -> ((f.source as? Source.Original)?.extents?.firstOrNull())?.let { byOrigLba.putIfAbsent(it.offset / 2048, f) } }

        val boots = mutableListOf<PlannedBoot>()
        val newLba = HashMap<BootEntry, Long>()
        val placedImage = HashMap<Long, Long>() // LBA original → novo (imagens compartilhadas por várias entradas)
        for (bi in src.bootImages) {
            val e = bi.entry
            val shared = placedImage[e.lba]
            if (shared != null) { newLba[e] = shared; boots += boots.first { it.newLba == shared }.copy(entry = e); continue }
            val node = byOrigLba[e.lba] ?: bi.path?.let { Paths.find(root, it) as? VFile }
            val size = node?.size ?: bi.size
            val lba = alloc.allocBytes(size)
            val data = if (node != null) session.open(node).use { it.readBytes() } else src.reader.readFully(e.lba * 2048, size.toInt())
            if (node == null && bi.path != null) warnings += "A imagem de boot ${bi.path} foi removida da árvore; ela foi mantida oculta para a ISO continuar inicializando."
            if (node != null && node.source is Source.External) warnings += "A imagem de boot ${bi.path} foi substituída: a inicialização ${e.platformName} depende do novo conteúdo."
            // ordem importa: o grub2-boot-info fica dentro da área somada pela boot-info-table
            if (bi.grub2BootInfo) ElTorito.applyGrub2BootInfo(data, lba)
            if (bi.bootInfoTable) ElTorito.applyBootInfoTable(data, lba)
            pieces += BytesPiece(lba * 2048, data)
            node?.let { extents[it] = IsoBuilder.contiguousExtents(lba, it.size) }
            newLba[e] = lba; placedImage[e.lba] = lba
            boots += PlannedBoot(e, bi.path, lba, size) { data }
        }
        // o próprio catálogo pode aparecer como arquivo (ex.: /boot/syslinux/boot.cat)
        if (catalog != null) byOrigLba[catalog.lba]?.let { extents[it] = listOf(catLba to it.size) }

        // pastas
        val rr = src.rrRootSusp?.let { RrConfig(it, src.rrPxWithIno) }
        val primary = DirTreeWriter(root, false, rr, extents, now)
        val joliet = DirTreeWriter(root, true, null, extents, now)
        primary.measure(); joliet.measure()
        val udfTree = if (udf) UdfWriter(root, now, volumeId ?: src.volumeId) else null
        primary.assign(alloc); joliet.assign(alloc)
        udfTree?.assign(alloc)

        // dados dos arquivos (na ordem da árvore)
        for ((f, p) in files) {
            if (extents.containsKey(f)) continue
            val lba = if (f.size == 0L) 0L else alloc.allocBytes(f.size)
            extents[f] = IsoBuilder.contiguousExtents(lba, f.size)
            if (f.size > 0) pieces += StreamPiece(lba * 2048, f.size, p) { session.open(f) }
        }
        val totalSectors = alloc.next + (if (udf) 1 else 0) // UDF: âncora final no último setor
        pieces += primary.encode()
        pieces += joliet.encode()

        // descritores de volume
        val pvd = src.pvd.data.copyOf()
        IsoBuilder.patchDescriptor(pvd, primary, totalSectors, volumeId)
        pieces += BytesPiece(pvdLba * 2048, pvd)
        val jol = (src.joliet?.data ?: pvd).copyOf().also { d ->
            if (src.joliet == null) {
                d[0] = 2; d[88] = 0x25; d[89] = 0x2F; d[90] = 0x45
                (volumeId ?: src.volumeId).take(16).padEnd(16, ' ').toByteArray(Charsets.UTF_16BE).copyInto(d, 40)
            }
        }
        IsoBuilder.patchDescriptor(jol, joliet, totalSectors, volumeId)
        pieces += BytesPiece(jolLba * 2048, jol)
        if (catalog != null) {
            val br = src.descriptors.first { it.type == 0 }.data.copyOf()
            Susp.put32le(br, 0x47, catLba)
            pieces += BytesPiece(brLba * 2048, br)
            pieces += BytesPiece(catLba * 2048, catalog.patched(newLba))
        }
        pieces += BytesPiece(termLba * 2048, ByteArray(2048).also { it[0] = 0xFF.toByte(); "CD001".toByteArray().copyInto(it, 1); it[6] = 1 })
        if (udfTree != null) {
            pieces += UdfWriter.recognitionSequence(vrsLba)
            pieces += udfTree.encode(extents, totalSectors)
        }

        if (src.isHybrid) warnings += "A tabela de partições híbrida (MBR/GPT) da ISO original não é recriada: a nova ISO funciona em DVD, no Ventoy e com gravação por extração, mas não gravada em modo DD. Para manter, use \"${RebuildMode.PRESERVE.title}\"."
        val bootSummary = when {
            catalog == null -> "A imagem original não é inicializável (sem catálogo El Torito)."
            else -> "Catálogo El Torito recriado com ${boots.size} imagem(ns) de boot (${boots.joinToString { it.entry.platformName }}). Será conferido na validação."
        }
        return BuildPlan(
            RebuildMode.FULL, totalSectors * 2048, warnings + src.warnings, bootSummary, boots, hybrid = false,
            hasUdf = udf, hasJoliet = true, hasRockRidge = rr != null, wastedBytes = 0,
            root = root, layout = ImageLayout(pieces, totalSectors * 2048), session = session,
        )
    }
}
