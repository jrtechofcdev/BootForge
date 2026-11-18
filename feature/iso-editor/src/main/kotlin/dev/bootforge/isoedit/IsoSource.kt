package dev.bootforge.isoedit

import dev.bootforge.blockdev.RandomReader
import dev.bootforge.iso.Extent
import dev.bootforge.iso.ImageTree
import dev.bootforge.iso.TreeEntry
import dev.bootforge.iso.UdfFileSystem
import java.io.IOException

/** Imagem de boot do catálogo El Torito e onde estão os dados dela. */
data class BootImage(
    val entry: BootEntry,
    /** Caminho na árvore quando a imagem é um arquivo visível (ex.: /boot/syslinux/isolinux.bin). */
    val path: String?,
    val size: Long,
    /** Tem "boot info table" (precisa ser recalculada se mudar de lugar). */
    val bootInfoTable: Boolean,
    val grub2BootInfo: Boolean,
)

/** Uma entrada de descritor de volume (setores 16+). */
class VolumeDescriptor(val lba: Long, val type: Int, val data: ByteArray) {
    val isJoliet: Boolean
        get() = type == 2 && data[88].toInt() == 0x25 && data[89].toInt() == 0x2F && data[90].toInt() in listOf(0x40, 0x43, 0x45)
}

/** Como os nomes da árvore foram obtidos. */
enum class TreeKind(val label: String) {
    ROCK_RIDGE("ISO9660 + Rock Ridge"), JOLIET("ISO9660 + Joliet"), ISO9660("ISO9660"), UDF("UDF")
}

/**
 * Imagem ISO aberta para edição. Lê tudo o que a reconstrução precisa preservar:
 * descritores de volume, árvore (com Rock Ridge/datas/nomes curtos originais), catálogo
 * El Torito, imagens de boot e a área de sistema (MBR/GPT híbridos, primeiros 32 KiB).
 * Nada é gravado na imagem original.
 */
class IsoSource private constructor(
    val reader: RandomReader,
    val descriptors: List<VolumeDescriptor>,
    val treeKind: TreeKind,
    val root: VDir,
    val catalog: ElToritoCatalog?,
    val bootImages: List<BootImage>,
    val systemArea: ByteArray,
    val rrRootSusp: List<SuspEntry>?,
    val rrPxWithIno: Boolean,
    val hasUdf: Boolean,
    /** Problemas que impedem a edição segura (vazio = pode editar). */
    val blockers: List<String>,
    val warnings: List<String>,
) {
    val size: Long get() = reader.size
    val pvd: VolumeDescriptor get() = descriptors.first { it.type == 1 }
    val joliet: VolumeDescriptor? get() = descriptors.firstOrNull { it.isJoliet }
    val volumeId: String get() = String(pvd.data, 40, 32, Charsets.ISO_8859_1).trim()
    val volumeSpaceSectors: Long get() = Susp.u32le(pvd.data, 80)
    val hasRockRidge: Boolean get() = rrRootSusp != null
    val hasMbr: Boolean get() = (systemArea[510].toInt() and 0xFF) == 0x55 && (systemArea[511].toInt() and 0xFF) == 0xAA &&
        (0 until 4).any { (systemArea[0x1BE + it * 16 + 4].toInt() and 0xFF) != 0 || Susp.u32le(systemArea, 0x1BE + it * 16 + 12) != 0L }
    val hasGpt: Boolean get() = String(systemArea, 512, 8, Charsets.US_ASCII) == "EFI PART"
    val hasApm: Boolean get() = systemArea[0] == 'E'.code.toByte() && systemArea[1] == 'R'.code.toByte() && String(systemArea, 2048, 2, Charsets.US_ASCII) == "PM"
    val isHybrid: Boolean get() = hasMbr || hasGpt

    /** Nome do formato para a tela. */
    val formatLabel: String
        get() = buildString {
            append(if (hasUdf && treeKind == TreeKind.UDF) "UDF + ISO9660" else treeKind.label)
            if (joliet != null && treeKind != TreeKind.JOLIET) append(" + Joliet")
            if (catalog != null) append(" · El Torito")
            if (isHybrid) append(" · híbrida (${listOfNotNull("MBR".takeIf { hasMbr }, "GPT".takeIf { hasGpt }).joinToString("+")})")
        }

    /** Lê os dados originais de um arquivo da árvore. */
    fun openOriginal(src: Source.Original, size: Long): java.io.InputStream = ExtentStream(reader, src.extents, size)

    companion object {
        const val SECTOR = 2048

        fun open(reader: RandomReader): IsoSource {
            if (reader.size < 17L * SECTOR) throw IOException("Arquivo pequeno demais para ser uma ISO")
            val descriptors = mutableListOf<VolumeDescriptor>()
            var terminator = -1L
            for (i in 16L until 16 + 64) {
                if ((i + 1) * SECTOR > reader.size) break
                val d = reader.readFully(i * SECTOR, SECTOR)
                if (String(d, 1, 5, Charsets.US_ASCII) != "CD001") break
                val type = d[0].toInt() and 0xFF
                if (type == 255) { terminator = i; break }
                descriptors += VolumeDescriptor(i, type, d)
            }
            val pvd = descriptors.firstOrNull { it.type == 1 } ?: throw IOException("Não é uma imagem ISO9660 (descritor primário não encontrado)")
            if (Susp.u16le(pvd.data, 128) != SECTOR) throw IOException("Tamanho de bloco ISO diferente de 2048 não suportado")
            val hasUdf = terminator > 0 && (terminator + 1 until terminator + 16).any { s ->
                (s + 1) * SECTOR <= reader.size && String(reader.readFully(s * SECTOR + 1, 5), Charsets.US_ASCII).let { it == "NSR02" || it == "NSR03" }
            }
            val systemArea = reader.readFully(0, 16 * SECTOR)
            val warnings = mutableListOf<String>()
            val blockers = mutableListOf<String>()

            // Rock Ridge: a entrada SP no "." da raiz indica SUSP
            val rootRec = pvd.data.copyOfRange(156, 156 + 34)
            val rootLba = Susp.u32le(rootRec, 2)
            val rootSize = Susp.u32le(rootRec, 10)
            val walker = RecordReader(reader)
            val rootDot = walker.read(rootLba, rootSize, joliet = false, suspSkip = 0, wantDots = true).firstOrNull()
            val sp = rootDot?.susp?.firstOrNull()
            val rr = sp != null && sp.sig == "SP" && sp.data.size >= 3 && (sp.data[0].toInt() and 0xFF) == 0xBE && (sp.data[1].toInt() and 0xFF) == 0xEF
            val skip = if (rr) sp!!.data[2].toInt() and 0xFF else 0
            val rrRootSusp = if (rr) rootDot!!.susp.filter { it.sig != "CE" && it.sig != "ST" } else null
            val pxWithIno = rrRootSusp?.firstOrNull { it.sig == "ER" }?.let { er ->
                val idLen = er.data[0].toInt() and 0xFF
                String(er.data, 4, idLen, Charsets.ISO_8859_1).let { it.startsWith("IEEE_1282") || it.startsWith("IEEE_P1282") }
            } ?: false

            val joliet = descriptors.firstOrNull { it.isJoliet }
            val (kind, root) = when {
                hasUdf -> {
                    val udf = UdfFileSystem.open(reader)
                    TreeKind.UDF to fromImageTree(udf)
                }
                rr -> TreeKind.ROCK_RIDGE to walker.primaryTree(rootLba, rootSize, skip, rockRidge = true)
                joliet != null -> {
                    val primaryNames = walker.primaryNamesByLba(rootLba, rootSize)
                    TreeKind.JOLIET to walker.jolietTree(Susp.u32le(joliet.data, 158), Susp.u32le(joliet.data, 166), primaryNames)
                }
                else -> TreeKind.ISO9660 to walker.primaryTree(rootLba, rootSize, 0, rockRidge = false)
            }
            if (walker.relocated) blockers += "A ISO usa realocação de pastas do Rock Ridge (rr_moved/CL/RE). A edição desse formato ainda não é suportada."
            if (walker.zisofs) warnings += "Há arquivos compactados por zisofs (ZF). Eles são mantidos como estão; se forem substituídos, o novo conteúdo fica sem compactação."

            // El Torito
            val br = descriptors.firstOrNull { it.type == 0 }
            val catalog = br?.let { runCatching { ElTorito.parse(reader, it.data) }.getOrNull() }
            if (br != null && catalog == null) warnings += "Registro de boot El Torito presente, mas o catálogo é inválido"
            val byLba = HashMap<Long, Pair<String, VFile>>()
            Paths.walk(root) { p, n ->
                if (n is VFile) ((n.source as? Source.Original)?.extents?.firstOrNull())?.let { if (it.offset >= 0) byLba.putIfAbsent(it.offset / SECTOR, p to n) }
            }
            val images = catalog?.entries?.map { e ->
                val f = byLba[e.lba]
                if (f != null) {
                    val head = if (f.second.size <= 8L shl 20) f.second.let { openOriginal(reader, it) } else null
                    BootImage(e, f.first, f.second.size, head?.let { ElTorito.hasBootInfoTable(it, e.lba) } ?: false, head?.let { ElTorito.hasGrub2BootInfo(it, e.lba) } ?: false)
                } else {
                    BootImage(e, null, ElTorito.hiddenImageSize(reader, e), false, false)
                }
            } ?: emptyList()

            return IsoSource(reader, descriptors, kind, root, catalog, images, systemArea, rrRootSusp, pxWithIno, hasUdf, blockers, warnings)
        }

        private fun openOriginal(r: RandomReader, f: VFile): ByteArray =
            ExtentStream(r, (f.source as Source.Original).extents, f.size).use { it.readBytes() }

        /** Árvore UDF (ISOs do Windows): nomes e extents, sem metadados ISO9660. */
        private fun fromImageTree(t: ImageTree): VDir {
            fun rec(path: String, name: String): VDir {
                val children = t.listDir(path).map { e: TreeEntry ->
                    val p = Paths.join(path, e.name)
                    if (e.isDirectory) rec(p, e.name) else VFile(e.name, e.size, Source.Original(e.extents))
                }
                return VDir(name, children)
            }
            return rec("/", "/")
        }
    }
}

/** Registro de diretório ISO9660 lido "cru" (com as entradas SUSP já resolvidas). */
internal class RawRecord(
    val id: ByteArray, val flags: Int, val extent: Long, val size: Long, val recDate: ByteArray, val susp: List<SuspEntry>,
) {
    val isDir get() = flags and 0x02 != 0
    val isMulti get() = flags and 0x80 != 0
    val hidden get() = flags and 0x01 != 0
    val isDot get() = id.size == 1 && (id[0].toInt() == 0 || id[0].toInt() == 1)
}

/** Lê pastas ISO9660 com SUSP/Rock Ridge (seguindo as áreas de continuação CE). */
internal class RecordReader(private val r: RandomReader) {
    var relocated = false
    var zisofs = false

    fun read(lba: Long, size: Long, joliet: Boolean, suspSkip: Int, wantDots: Boolean = false): List<RawRecord> {
        if (size > 64L shl 20) throw IOException("Diretório ISO grande demais (corrompido?)")
        val raw = r.readFully(lba * 2048, size.toInt())
        val out = mutableListOf<RawRecord>()
        var pos = 0
        while (pos < raw.size) {
            val len = raw[pos].toInt() and 0xFF
            if (len == 0) { pos = (pos / 2048 + 1) * 2048; continue }
            if (pos + len > raw.size || len < 34) break
            val nameLen = raw[pos + 32].toInt() and 0xFF
            val id = raw.copyOfRange(pos + 33, pos + 33 + nameLen)
            val suStart = pos + 33 + nameLen + (if (nameLen % 2 == 0) 1 else 0) + suspSkip
            val susp = if (joliet || suStart >= pos + len) emptyList() else resolve(Susp.parse(raw, suStart, pos + len), 0)
            val rec = RawRecord(id, raw[pos + 25].toInt() and 0xFF, Susp.u32le(raw, pos + 2), Susp.u32le(raw, pos + 10), raw.copyOfRange(pos + 18, pos + 25), susp)
            if (wantDots || !rec.isDot) out += rec
            pos += len
        }
        return out
    }

    /** Segue entradas CE (continuação) e devolve a lista completa, sem as CE. */
    private fun resolve(list: List<SuspEntry>, depth: Int): List<SuspEntry> {
        val ce = list.firstOrNull { it.sig == "CE" } ?: return list
        if (depth > 16) return list.filter { it.sig != "CE" }
        val (block, off, len) = Susp.ce(ce)
        val more = runCatching {
            val area = r.readFully(block * 2048 + off, len)
            resolve(Susp.parse(area, 0, area.size), depth + 1)
        }.getOrDefault(emptyList())
        return list.filter { it.sig != "CE" } + more
    }

    private fun rrName(susp: List<SuspEntry>): String? {
        val nms = susp.filter { it.sig == "NM" && it.data.isNotEmpty() && (it.data[0].toInt() and 0x06) == 0 }
        if (nms.isEmpty()) return null
        return nms.joinToString("") { String(it.data, 1, it.data.size - 1, Charsets.UTF_8) }
    }

    private fun isoName(rec: RawRecord): String {
        val s = String(rec.id, Charsets.ISO_8859_1).substringBefore(';')
        return if (s.endsWith(".")) s.dropLast(1) else s
    }

    private fun keepSusp(s: List<SuspEntry>) = s.filter { it.sig !in setOf("NM", "CE", "ST", "SP", "ER", "RR", "PD", "ES") }

    fun primaryTree(lba: Long, size: Long, skip: Int, rockRidge: Boolean, name: String = "/", meta: Meta = Meta(), depth: Int = 0): VDir {
        if (depth > 64) throw IOException("Pastas aninhadas demais")
        val recs = read(lba, size, false, skip)
        val children = mutableListOf<VNode>()
        var i = 0
        while (i < recs.size) {
            val rec = recs[i]
            if (rec.susp.any { it.sig == "CL" || it.sig == "RE" }) relocated = true
            if (rec.susp.any { it.sig == "ZF" }) zisofs = true
            val nm = (if (rockRidge) rrName(rec.susp) else null) ?: isoName(rec)
            val m = Meta(rec.recDate, if (rockRidge) keepSusp(rec.susp) else null, String(rec.id, Charsets.ISO_8859_1), rec.hidden)
            if (rec.isDir) {
                children += primaryTree(rec.extent, rec.size, skip, rockRidge, nm, m, depth + 1)
                i++
            } else {
                val parts = mutableListOf(rec)
                while (parts.last().isMulti && i + parts.size < recs.size) parts += recs[i + parts.size]
                children += VFile(nm, parts.sumOf { it.size }, Source.Original(parts.map { Extent(it.extent * 2048, it.size) }), m)
                i += parts.size
            }
        }
        return VDir(name, children, meta)
    }

    /** Nomes ISO9660 originais por LBA (para manter os nomes curtos quando a árvore vem do Joliet). */
    fun primaryNamesByLba(lba: Long, size: Long): Map<Long, String> {
        val out = HashMap<Long, String>()
        fun rec(l: Long, s: Long, depth: Int) {
            if (depth > 64) return
            for (r in read(l, s, false, 0)) {
                if (r.isDir) rec(r.extent, r.size, depth + 1) else if (r.size > 0) out.putIfAbsent(r.extent, String(r.id, Charsets.ISO_8859_1))
            }
        }
        rec(lba, size, 0)
        return out
    }

    fun jolietTree(lba: Long, size: Long, primaryNames: Map<Long, String>, name: String = "/", meta: Meta = Meta(), depth: Int = 0): VDir {
        if (depth > 64) throw IOException("Pastas aninhadas demais")
        val recs = read(lba, size, true, 0)
        val children = mutableListOf<VNode>()
        var i = 0
        while (i < recs.size) {
            val rec = recs[i]
            val nm = String(rec.id, Charsets.UTF_16BE).substringBefore(';')
            if (rec.isDir) {
                children += jolietTree(rec.extent, rec.size, primaryNames, nm, Meta(rec.recDate, null, null, rec.hidden), depth + 1)
                i++
            } else {
                val parts = mutableListOf(rec)
                while (parts.last().isMulti && i + parts.size < recs.size) parts += recs[i + parts.size]
                val m = Meta(rec.recDate, null, if (rec.size > 0) primaryNames[rec.extent] else null, rec.hidden)
                children += VFile(nm, parts.sumOf { it.size }, Source.Original(parts.map { Extent(it.extent * 2048, it.size) }), m)
                i += parts.size
            }
        }
        return VDir(name, children, meta)
    }
}

/** Lê os trechos de um arquivo da imagem em sequência (trecho com offset < 0 = zeros). */
class ExtentStream(private val r: RandomReader, private val extents: List<Extent>, private val size: Long) : java.io.InputStream() {
    private var idx = 0
    private var inExt = 0L
    private var total = 0L

    override fun read(): Int { val b = ByteArray(1); return if (read(b, 0, 1) < 0) -1 else b[0].toInt() and 0xFF }

    override fun read(b: ByteArray, off: Int, len: Int): Int {
        if (total >= size || len == 0) return if (len == 0) 0 else -1
        while (idx < extents.size && inExt >= extents[idx].length) { idx++; inExt = 0 }
        if (idx >= extents.size) throw IOException("Dados do arquivo terminaram antes do tamanho declarado")
        val e = extents[idx]
        val n = minOf(len.toLong(), e.length - inExt, size - total).toInt()
        val got = if (e.offset < 0) { b.fill(0, off, off + n); n } else r.read(e.offset + inExt, b, off, n)
        if (got <= 0) throw IOException("Fim inesperado da imagem")
        inExt += got; total += got
        return got
    }
}
