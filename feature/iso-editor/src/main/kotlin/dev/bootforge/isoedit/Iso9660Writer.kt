package dev.bootforge.isoedit

import java.time.ZonedDateTime
import java.util.IdentityHashMap

/** Onde estão os dados de um arquivo na imagem final: (LBA, tamanho em bytes) de cada parte. */
internal typealias FileExtents = List<Pair<Long, Long>>

/** Maior trecho de um registro ISO9660 (4 GiB − 2048); arquivos maiores usam vários registros. */
internal const val MAX_EXTENT = 0xFFFFF800L

/** Configuração Rock Ridge para a árvore primária. */
internal class RrConfig(
    /** Entradas SUSP do "." da raiz (SP primeiro, ER…). */
    val rootSusp: List<SuspEntry>,
    val pxWithIno: Boolean,
)

/**
 * Gera as pastas ISO9660 (primária, opcionalmente com Rock Ridge) ou Joliet a partir do modelo.
 *
 * Etapas: [measure] (calcula tamanhos — independe dos LBAs) → [assign] (recebe os LBAs) →
 * [encode] (gera os bytes das pastas, das áreas de continuação e das tabelas de caminho).
 */
internal class DirTreeWriter(
    private val root: VDir,
    private val joliet: Boolean,
    private val rr: RrConfig?,
    private val extents: IdentityHashMap<VFile, FileExtents>,
    private val now: ZonedDateTime,
) {
    inner class DirInfo(val dir: VDir, val parent: DirInfo?, val ident: ByteArray, val depth: Int) {
        var number = 0
        var lba = 0L
        var size = 0L
        lateinit var children: List<Pair<ByteArray, VNode>>
        val subdirs = mutableListOf<DirInfo>()
    }

    /** Registro planejado: SUSP que fica no registro e o que vai para a área de continuação. */
    private class Planned(val inRecord: List<SuspEntry>, val overflow: List<SuspEntry>) {
        var ceLba = 0L
        var ceOffset = 0
        val overflowBytes get() = overflow.sumOf { it.length }
    }

    lateinit var dirs: List<DirInfo>
    private val planned = IdentityHashMap<Any, Planned>() // chave: VNode ou par (dir,"."|"..")
    private val dotKeys = IdentityHashMap<DirInfo, Pair<Any, Any>>()
    var ceSectors = 0L; private set
    var pathTableBytes = 0L; private set
    private var ino = 1L

    // ------------------------------------------------------------------ 1) medir

    fun measure() {
        val all = mutableListOf<DirInfo>()
        val rootInfo = DirInfo(root, null, byteArrayOf(0), 0)
        // Ordem das tabelas de caminho: por nível, depois pelo nº da pasta-mãe, depois pelo nome
        var level = listOf(rootInfo)
        while (level.isNotEmpty()) {
            val next = mutableListOf<DirInfo>()
            for (d in level) {
                all += d
                d.number = all.size
                d.children = IsoNames.assign(d.dir.children, joliet, d.depth)
                for ((id, n) in d.children) if (n is VDir) DirInfo(n, d, id, d.depth + 1).also { d.subdirs += it; next += it }
            }
            level = next
        }
        dirs = all
        pathTableBytes = all.sumOf { 8L + it.ident.size + (it.ident.size and 1) }

        // tamanho de cada pasta e as continuações necessárias
        val ceAreas = mutableListOf<Planned>()
        for (d in dirs) {
            val dotKey = Any(); val dotdotKey = Any()
            dotKeys[d] = dotKey to dotdotKey
            val recs = mutableListOf<Int>()
            fun add(key: Any, idLen: Int, susp: List<SuspEntry>) {
                val p = split(idLen, susp)
                planned[key] = p
                if (p.overflow.isNotEmpty()) ceAreas += p
                recs += recordLen(idLen, p.inRecord.sumOf { it.length } + (if (p.overflow.isNotEmpty()) 28 else 0))
            }
            add(dotKey, 1, if (rr == null) emptyList() else if (d.parent == null) rr.rootSusp + dirExtras(d.dir, true) else dirSusp(d.dir))
            add(dotdotKey, 1, if (rr == null) emptyList() else dirSusp((d.parent ?: d).dir))
            for ((id, n) in d.children) {
                val count = if (n is VFile) parts(n) else 1
                repeat(count) { i -> add(if (i == 0) n else Any(), id.size, if (rr == null || i > 0) emptyList() else childSusp(n)) }
            }
            d.size = packedSize(recs)
        }
        // áreas de continuação: empacotadas em setores (uma área não cruza setores)
        var sector = 0L; var off = 0
        for (a in ceAreas) {
            val len = a.overflowBytes
            if (len > 2048) throw IllegalStateException("Metadados Rock Ridge grandes demais em um item")
            if (off + len > 2048) { sector++; off = 0 }
            a.ceLba = sector; a.ceOffset = off; off += len
        }
        ceSectors = if (ceAreas.isEmpty()) 0 else sector + 1
    }

    /** Setores necessários para todas as pastas. */
    val dirSectors: Long get() = dirs.sumOf { (it.size + 2047) / 2048 }

    // ------------------------------------------------------------------ 2) posições

    private var ceBase = 0L
    var lPathLba = 0L; private set
    var mPathLba = 0L; private set

    fun assign(alloc: SectorAllocator) {
        for (d in dirs) d.lba = alloc.allocBytes(d.size)
        ceBase = if (ceSectors > 0) alloc.alloc(ceSectors) else 0
        lPathLba = alloc.allocBytes(pathTableBytes)
        mPathLba = alloc.allocBytes(pathTableBytes)
    }

    // ------------------------------------------------------------------ 3) bytes

    fun encode(): List<BytesPiece> {
        val out = mutableListOf<BytesPiece>()
        val ce = if (ceSectors > 0) ByteArray((ceSectors * 2048).toInt()) else null
        for (d in dirs) {
            val buf = ByteArray(((d.size + 2047) / 2048 * 2048).toInt())
            var pos = 0
            fun put(rec: ByteArray) {
                if (pos % 2048 + rec.size > 2048) pos = (pos / 2048 + 1) * 2048
                rec.copyInto(buf, pos); pos += rec.size
            }
            val (dotKey, dotdotKey) = dotKeys.getValue(d)
            val parent = d.parent ?: d
            put(record(byteArrayOf(0), d.lba, d.size, 0x02, d.dir.meta.recDate, planned.getValue(dotKey), ce))
            put(record(byteArrayOf(1), parent.lba, parent.size, 0x02, parent.dir.meta.recDate, planned.getValue(dotdotKey), ce))
            val sub = d.subdirs.iterator()
            for ((id, n) in d.children) {
                when (n) {
                    is VDir -> { val s = sub.next(); put(record(id, s.lba, s.size, 0x02 or hid(n), n.meta.recDate, planned.getValue(n), ce)) }
                    is VFile -> {
                        val ext = extents[n] ?: throw IllegalStateException("Arquivo sem posição: ${n.name}")
                        ext.forEachIndexed { i, (lba, len) ->
                            val flags = hid(n) or (if (i < ext.lastIndex) 0x80 else 0)
                            val p = if (i == 0) planned.getValue(n) else Planned(emptyList(), emptyList())
                            put(record(id, lba, len, flags, n.meta.recDate, p, ce))
                        }
                    }
                }
            }
            out += BytesPiece(d.lba * 2048, buf)
        }
        if (ce != null) out += BytesPiece(ceBase * 2048, ce)
        out += BytesPiece(lPathLba * 2048, pathTable(littleEndian = true))
        out += BytesPiece(mPathLba * 2048, pathTable(littleEndian = false))
        return out
    }

    /** Registro de 34 bytes da raiz, para o descritor de volume. */
    fun rootRecord(): ByteArray {
        val r = dirs.first()
        val b = ByteArray(34)
        b[0] = 34
        Susp.bb(b, 2, r.lba); Susp.bb(b, 10, r.size)
        (r.dir.meta.recDate ?: Susp.shortDate(now)).copyInto(b, 18)
        b[25] = 2; Susp.bb16(b, 28, 1); b[32] = 1; b[33] = 0
        return b
    }

    // ------------------------------------------------------------------ auxiliares

    private fun hid(n: VNode) = if (n.meta.hidden) 0x01 else 0

    /** Nº de registros de um arquivo (> 4 GiB usa vários, "multi-extent"). */
    private fun parts(f: VFile) = extents[f]?.size ?: maxOf(1L, (f.size + MAX_EXTENT - 1) / MAX_EXTENT).toInt()

    private fun record(id: ByteArray, lba: Long, size: Long, flags: Int, date: ByteArray?, p: Planned, ce: ByteArray?): ByteArray {
        val su = mutableListOf<SuspEntry>()
        su += p.inRecord
        if (p.overflow.isNotEmpty()) {
            su += Susp.ceEntry(ceBase + p.ceLba, p.ceOffset, p.overflowBytes)
            var o = (p.ceLba * 2048).toInt() + p.ceOffset
            for (e in p.overflow) { e.encodeInto(ce!!, o); o += e.length }
        }
        val suLen = su.sumOf { it.length }
        val len = recordLen(id.size, suLen)
        val b = ByteArray(len)
        b[0] = len.toByte()
        Susp.bb(b, 2, lba); Susp.bb(b, 10, size)
        (date ?: Susp.shortDate(now)).copyInto(b, 18)
        b[25] = flags.toByte(); Susp.bb16(b, 28, 1); b[32] = id.size.toByte()
        id.copyInto(b, 33)
        var o = 33 + id.size + (if (id.size % 2 == 0) 1 else 0)
        for (e in su) { e.encodeInto(b, o); o += e.length }
        return b
    }

    private fun recordLen(idLen: Int, suLen: Int): Int {
        val base = 33 + idLen + (if (idLen % 2 == 0) 1 else 0) + suLen
        return base + (base and 1)
    }

    /** Divide o SUSP entre o registro (máx. 255 bytes) e a área de continuação. */
    private fun split(idLen: Int, su: List<SuspEntry>): Planned {
        val base = 33 + idLen + (if (idLen % 2 == 0) 1 else 0)
        if (recordLen(idLen, su.sumOf { it.length }) <= 254) return Planned(su, emptyList())
        val inRec = mutableListOf<SuspEntry>()
        var used = base + 28
        var i = 0
        while (i < su.size && used + su[i].length <= 254) { inRec += su[i]; used += su[i].length; i++ }
        return Planned(inRec, su.drop(i))
    }

    private fun packedSize(recs: List<Int>): Long {
        var pos = 0L
        for (r in recs) { if (pos % 2048 + r > 2048) pos = (pos / 2048 + 1) * 2048; pos += r }
        return (pos + 2047) / 2048 * 2048
    }

    private fun dirSusp(d: VDir): List<SuspEntry> = d.meta.susp ?: listOf(Susp.px(0x41ED, 2, ino++, rr!!.pxWithIno), Susp.tf(now))
    private fun dirExtras(d: VDir, root: Boolean): List<SuspEntry> =
        if (root && rr!!.rootSusp.none { it.sig == "PX" }) listOf(Susp.px(0x41ED, 2, ino++, rr.pxWithIno), Susp.tf(now)) else emptyList()

    private fun childSusp(n: VNode): List<SuspEntry> {
        val base = n.meta.susp ?: when (n) {
            is VDir -> listOf(Susp.px(0x41ED, 2, ino++, rr!!.pxWithIno), Susp.tf(now))
            is VFile -> listOf(Susp.px(0x81A4, 1, ino++, rr!!.pxWithIno), Susp.tf(now))
        }
        return base + Susp.nm(n.name)
    }

    private fun pathTable(littleEndian: Boolean): ByteArray {
        val b = ByteArray(pathTableBytes.toInt())
        var o = 0
        for (d in dirs) {
            b[o] = d.ident.size.toByte()
            if (littleEndian) { Susp.put32le(b, o + 2, d.lba); Susp.put16le(b, o + 6, d.parent?.number ?: 1) }
            else { Susp.put32be(b, o + 2, d.lba); Susp.put16be(b, o + 6, d.parent?.number ?: 1) }
            d.ident.copyInto(b, o + 8)
            o += 8 + d.ident.size + (d.ident.size and 1)
        }
        return b
    }
}

/** Nomes ISO9660 (curtos, maiúsculos) e Joliet (Unicode), sem repetições e na ordem exigida. */
internal object IsoNames {
    private val D_CHARS = ('A'..'Z').toSet() + ('0'..'9').toSet() + '_'

    /** Identificadores de todos os filhos de uma pasta, já ordenados como manda a norma. */
    fun assign(children: List<VNode>, joliet: Boolean, depth: Int): List<Pair<ByteArray, VNode>> {
        val used = HashSet<String>()
        val out = mutableListOf<Pair<String, VNode>>()
        val rest = mutableListOf<VNode>()
        // primeiro quem já tem nome curto original (mantém o que carregadores antigos procuram)
        for (n in children) {
            val id = if (joliet) null else n.meta.isoName?.takeIf { validIso(it) }
            if (id != null && used.add(key(id))) out += id to n else rest += n
        }
        for (n in rest) out += (if (joliet) jolietName(n.name, n is VDir, used) else isoName(n.name, n is VDir, used)) to n
        return if (joliet) {
            out.sortedWith(compareBy(BYTES) { it.first.toByteArray(Charsets.UTF_16BE) }).map { it.first.toByteArray(Charsets.UTF_16BE) to it.second }
        } else {
            out.sortedWith { a, b -> compareIso(a.first, b.first) }.map { it.first.toByteArray(Charsets.ISO_8859_1) to it.second }
        }
    }

    private fun validIso(id: String): Boolean = id.isNotEmpty() && id.length <= 200 && id[0].code > 1

    /** Chave de unicidade: identificador sem versão e sem ponto final. */
    private fun key(id: String) = id.substringBefore(';').trimEnd('.')

    /** Nome ISO9660 nível 2: até 30 caracteres d-chars + ";1" (pastas: sem ponto). */
    fun isoName(name: String, dir: Boolean, used: MutableSet<String>): String {
        fun clean(s: String) = s.uppercase().map { if (it in D_CHARS) it else '_' }.joinToString("")
        val dot = name.lastIndexOf('.')
        var base = clean(if (!dir && dot > 0) name.substring(0, dot) else name.replace('.', '_'))
        val ext = if (!dir && dot > 0) clean(name.substring(dot + 1)).take(8) else ""
        if (base.isEmpty()) base = "_"
        val maxBase = (if (dir) 31 else 30 - ext.length - 1).coerceAtLeast(1)
        var candidate = base.take(maxBase)
        var n = 1
        while (!used.add(key(candidate + (if (ext.isNotEmpty()) ".$ext" else "")))) {
            val suffix = "_$n"; n++
            candidate = base.take(maxBase - suffix.length) + suffix
        }
        val full = candidate + (if (ext.isNotEmpty()) ".$ext" else "")
        return if (dir) full else (if (ext.isEmpty()) "$full." else full) + ";1"
    }

    /** Nome Joliet: até 64 caracteres Unicode, sem * / : ; ? \. */
    fun jolietName(name: String, dir: Boolean, used: MutableSet<String>): String {
        val clean = name.map { if (it.code < 32 || it in "*/:;?\\") '_' else it }.joinToString("")
        val dot = clean.lastIndexOf('.')
        val ext = if (!dir && dot > 0 && clean.length - dot <= 10) clean.substring(dot) else ""
        val base = if (ext.isNotEmpty()) clean.substring(0, dot) else clean
        var candidate = (base.take(64 - ext.length) + ext)
        var n = 1
        while (!used.add(candidate.lowercase())) {
            val suffix = "~$n"; n++
            candidate = base.take(64 - ext.length - suffix.length) + suffix + ext
        }
        return if (dir) candidate else "$candidate;1"
    }

    /** Ordem da ECMA-119 9.3: nome (completado com espaços), extensão, versão. */
    fun compareIso(a: String, b: String): Int {
        fun parts(s: String): Pair<String, String> {
            val noVer = s.substringBefore(';')
            val dot = noVer.indexOf('.')
            return if (dot < 0) noVer to "" else noVer.substring(0, dot) to noVer.substring(dot + 1)
        }
        val (an, ae) = parts(a); val (bn, be) = parts(b)
        val n = maxOf(an.length, bn.length)
        val c = an.padEnd(n, ' ').compareTo(bn.padEnd(n, ' '))
        if (c != 0) return c
        val m = maxOf(ae.length, be.length)
        return ae.padEnd(m, ' ').compareTo(be.padEnd(m, ' '))
    }

    private val BYTES = Comparator<ByteArray> { x, y ->
        val n = maxOf(x.size, y.size)
        for (i in 0 until n) {
            val a = if (i < x.size) x[i].toInt() and 0xFF else 0
            val b = if (i < y.size) y[i].toInt() and 0xFF else 0
            if (a != b) return@Comparator a - b
        }
        0
    }
}
