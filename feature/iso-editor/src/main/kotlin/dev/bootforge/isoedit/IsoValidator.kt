package dev.bootforge.isoedit

import dev.bootforge.blockdev.ProgressListener
import dev.bootforge.blockdev.ProgressTracker
import dev.bootforge.blockdev.RandomReader
import dev.bootforge.integrity.CancellationSignal
import dev.bootforge.integrity.HashAlgorithm
import dev.bootforge.integrity.toHex
import dev.bootforge.partition.crc32
import java.io.InputStream

/** Uma conferência da validação. */
data class Check(val ok: Boolean, val title: String, val detail: String = "")

/** Situação da inicialização da ISO gerada — nunca "inicializável" sem conferência. */
enum class BootStatus(val label: String) {
    VERIFIED("Estruturas de inicialização conferidas"),
    NOT_BOOTABLE("Imagem sem inicialização (o original também não tinha)"),
    FAILED("Inicialização NÃO pode ser garantida"),
}

data class ValidationReport(val checks: List<Check>, val boot: BootStatus, val filesChecked: Int, val bytesChecked: Long) {
    val ok: Boolean get() = checks.all { it.ok }
    val failures: List<Check> get() = checks.filter { !it.ok }
}

/**
 * Valida a ISO gerada RELENDO o arquivo: estrutura, árvores (Rock Ridge, Joliet, UDF), conteúdo
 * de cada arquivo (SHA-256 comparado com o esperado), catálogo El Torito, imagens de boot e
 * tabelas de partição híbridas.
 *
 * Observação honesta: isto confirma que tudo o que o firmware precisa está no lugar e íntegro.
 * A prova definitiva é iniciar num PC ou máquina virtual.
 */
object IsoValidator {

    fun validate(
        plan: BuildPlan, session: EditSession, output: RandomReader,
        expectedSha256: String? = null, deep: Boolean = true,
        listener: ProgressListener = ProgressListener.NONE, cancel: CancellationSignal = CancellationSignal.NONE,
    ): ValidationReport {
        val checks = mutableListOf<Check>()
        checks += Check(output.size == plan.totalBytes, "Tamanho do arquivo", "${output.size} bytes (esperado ${plan.totalBytes})")

        if (expectedSha256 != null) {
            val tracker = ProgressTracker("Conferindo o arquivo gerado", output.size, listener)
            val got = hashReader(output, cancel) { tracker.update(it) }
            checks += Check(got.equals(expectedSha256, true), "SHA-256 do arquivo gerado", got)
        }

        val out = runCatching { IsoSource.open(output) }.getOrElse {
            checks += Check(false, "Leitura da nova ISO", it.message ?: it.toString())
            return ValidationReport(checks, BootStatus.FAILED, 0, 0)
        }
        checks += Check(true, "Leitura da nova ISO", out.formatLabel)
        checks += Check(out.volumeSpaceSectors * 2048 <= output.size, "Tamanho do volume ISO9660", "${out.volumeSpaceSectors} setores")
        if (plan.hasJoliet) checks += Check(out.joliet != null, "Joliet (nomes para Windows)", if (out.joliet != null) "presente" else "ausente")
        if (plan.hasRockRidge) checks += Check(out.hasRockRidge, "Rock Ridge (nomes e permissões Linux)", if (out.hasRockRidge) "presente" else "ausente")
        if (plan.hasUdf) checks += Check(out.hasUdf, "UDF (sistema de arquivos do Windows)", if (out.hasUdf) "presente" else "ausente")
        checks += pathTables(out)

        // árvores: a "principal" (UDF ou Rock Ridge) tem de ter exatamente os mesmos caminhos
        val expected = LinkedHashMap<String, VFile>()
        Paths.walk(plan.root) { p, n -> if (n is VFile) expected[p] = n }
        val got = LinkedHashMap<String, VFile>()
        Paths.walk(out.root) { p, n -> if (n is VFile) got[p] = n }
        val exactNames = out.treeKind == TreeKind.UDF || out.treeKind == TreeKind.ROCK_RIDGE
        if (exactNames) {
            val missing = expected.keys - got.keys
            val extra = got.keys - expected.keys
            checks += Check(missing.isEmpty() && extra.isEmpty(), "Árvore de arquivos (${out.treeKind.label})",
                if (missing.isEmpty() && extra.isEmpty()) "${got.size} arquivos, todos nos caminhos certos"
                else "faltando: ${missing.take(5)} · sobrando: ${extra.take(5)}")
        } else {
            checks += Check(got.size == expected.size, "Árvore de arquivos (${out.treeKind.label})", "${got.size} de ${expected.size} arquivos")
        }
        val badSize = expected.filter { (p, f) -> got[p]?.let { it.size != f.size } == true }.keys
        checks += Check(badSize.isEmpty(), "Tamanhos dos arquivos", if (badSize.isEmpty()) "conferem" else "diferentes: ${badSize.take(5)}")

        // conteúdo: relê cada arquivo da nova ISO e compara com o esperado
        var files = 0; var bytes = 0L
        if (deep) {
            val total = expected.values.sumOf { it.size } * 2
            val tracker = ProgressTracker("Verificando arquivos", total, listener)
            var done = 0L
            val bad = mutableListOf<String>()
            val bootPaths = plan.boots.mapNotNull { it.path }.toMutableSet()
            // o arquivo do catálogo El Torito (ex.: boot.cat) tem os setores atualizados: conferido abaixo
            out.catalog?.let { c -> got.entries.firstOrNull { ((it.value.source as? Source.Original)?.extents?.firstOrNull()?.offset ?: -1) == c.lba * 2048 }?.let { bootPaths += it.key } }
            for ((p, f) in expected) {
                cancel.throwIfCancelled()
                val g = got[p] ?: continue
                if (p in bootPaths) { files++; continue } // conferidas abaixo (têm tabelas recalculadas)
                val base = done
                val h1 = session.open(f).use { hash(it, cancel) { n -> tracker.update(base + n) } }
                done += f.size
                val base2 = done
                val h2 = out.openOriginal(g.source as Source.Original, g.size).use { hash(it, cancel) { n -> tracker.update(base2 + n) } }
                done += g.size
                if (h1 != h2) bad += p
                files++; bytes += f.size
            }
            tracker.update(done, force = true)
            checks += Check(bad.isEmpty(), "Conteúdo dos arquivos (SHA-256)", if (bad.isEmpty()) "$files arquivos idênticos ao esperado" else "diferentes: ${bad.take(5)}")
            // Joliet: confere que aponta para os mesmos dados
            if (out.joliet != null && exactNames) checks += jolietConsistency(out, got)
        }

        // El Torito e imagens de boot
        val src = session.source
        var bootOk = true
        if (src.catalog != null) {
            val cat = out.catalog
            if (cat == null) { checks += Check(false, "Catálogo El Torito", "não encontrado"); bootOk = false }
            else {
                val okCount = cat.entries.size == plan.boots.size
                checks += Check(okCount, "Catálogo El Torito", "${cat.entries.size} entradas (${cat.entries.joinToString { it.platformName }})")
                bootOk = bootOk && okCount
                for (pb in plan.boots) {
                    val e = cat.entries.firstOrNull { it.platform == pb.entry.platform && it.lba == pb.newLba }
                    val data = runCatching { output.readFully(pb.newLba * 2048, pb.size.toInt()) }.getOrNull()
                    val same = e != null && data != null && data.contentEquals(pb.expected())
                    val bi = src.bootImages.firstOrNull { it.entry == pb.entry }
                    val tableOk = bi == null || !bi.bootInfoTable || (data != null && ElTorito.hasBootInfoTable(data, pb.newLba))
                    val g2Ok = bi == null || !bi.grub2BootInfo || (data != null && ElTorito.hasGrub2BootInfo(data, pb.newLba))
                    val ok = same && tableOk && g2Ok
                    bootOk = bootOk && ok
                    checks += Check(ok, "Imagem de boot ${pb.entry.platformName} (${pb.path ?: "oculta"})",
                        if (ok) "setor ${pb.newLba}, ${pb.size} bytes idênticos ao esperado" + (if (bi?.bootInfoTable == true) ", boot-info-table correta" else "")
                        else "divergente (entrada=${e != null}, conteúdo=$same, tabela=$tableOk)")
                }
            }
        }
        if (plan.hybrid) {
            val hy = hybridChecks(output, out)
            checks += hy
            bootOk = bootOk && hy.all { it.ok }
        }
        if (plan.hasUdf) checks += udfAnchors(output)

        val boot = when {
            src.catalog == null && !src.isHybrid -> BootStatus.NOT_BOOTABLE
            bootOk && checks.all { it.ok } -> BootStatus.VERIFIED
            else -> BootStatus.FAILED
        }
        return ValidationReport(checks, boot, files, bytes)
    }

    // ------------------------------------------------------------------ conferências

    private fun pathTables(out: IsoSource): List<Check> {
        val res = mutableListOf<Check>()
        for (vd in listOfNotNull(out.pvd, out.joliet)) {
            val d = vd.data
            val size = Susp.u32le(d, 132).toInt()
            val l = Susp.u32le(d, 140)
            val ok = runCatching {
                val t = out.reader.readFully(l * 2048, size)
                val lbas = mutableSetOf<Long>()
                var o = 0
                while (o < t.size) { val n = t[o].toInt() and 0xFF; if (n == 0) break; lbas += Susp.u32le(t, o + 2); o += 8 + n + (n and 1) }
                val dirs = mutableSetOf<Long>()
                val rr = RecordReader(out.reader)
                fun walk(lba: Long, sz: Long, depth: Int) {
                    dirs += lba
                    if (depth < 64) rr.read(lba, sz, vd.isJoliet, 0).filter { it.isDir }.forEach { walk(it.extent, it.size, depth + 1) }
                }
                walk(Susp.u32le(d, 158), Susp.u32le(d, 166), 0)
                lbas == dirs
            }.getOrDefault(false)
            res += Check(ok, "Tabela de caminhos ${if (vd.isJoliet) "Joliet" else "ISO9660"}", if (ok) "consistente com as pastas" else "não confere com as pastas")
        }
        return res
    }

    private fun jolietConsistency(out: IsoSource, main: Map<String, VFile>): Check {
        val j = runCatching {
            val rr = RecordReader(out.reader)
            val jd = out.joliet!!.data
            rr.jolietTree(Susp.u32le(jd, 158), Susp.u32le(jd, 166), emptyMap())
        }.getOrNull() ?: return Check(false, "Árvore Joliet", "ilegível")
        val mainExt = main.values.mapNotNull { (it.source as? Source.Original)?.extents?.firstOrNull()?.offset }.toSet()
        var count = 0; var bad = 0
        Paths.walk(j) { _, n -> if (n is VFile) { count++; val o = (n.source as Source.Original).extents.first().offset; if (n.size > 0 && o !in mainExt) bad++ } }
        return Check(count == main.size && bad == 0, "Árvore Joliet", "$count arquivos" + if (bad > 0) ", $bad apontam para dados errados" else ", mesmos dados da árvore principal")
    }

    private fun hybridChecks(r: RandomReader, out: IsoSource): List<Check> {
        val res = mutableListOf<Check>()
        val total512 = r.size / 512
        val sa = out.systemArea
        if (out.hasMbr) {
            val inside = (0 until 4).all { i ->
                val o = 0x1BE + i * 16
                val type = sa[o + 4].toInt() and 0xFF
                type == 0xEE || Susp.u32le(sa, o + 8) + Susp.u32le(sa, o + 12) <= total512
            }
            res += Check((sa[510].toInt() and 0xFF) == 0x55 && inside, "MBR híbrido", if (inside) "partições dentro da imagem" else "partição além do fim da imagem")
        }
        if (out.hasGpt) {
            val h = sa.copyOfRange(512, 1024)
            val hs = Susp.u32le(h, 12).toInt()
            val hc = h.copyOf(); Susp.put32le(hc, 16, 0)
            val headerOk = crc32(hc, 0, hs) == Susp.u32le(h, 16)
            val entries = r.readFully(ElTorito.u64(h, 72) * 512, (Susp.u32le(h, 80) * Susp.u32le(h, 84)).toInt())
            val entriesOk = crc32(entries) == Susp.u32le(h, 88)
            val backupLba = ElTorito.u64(h, 32)
            val backup = runCatching { r.readFully(backupLba * 512, 512) }.getOrNull()
            val backupOk = backup != null && String(backup, 0, 8, Charsets.US_ASCII) == "EFI PART" && backupLba == total512 - 1 &&
                ElTorito.u64(backup, 24) == backupLba && Susp.u32le(backup, 88) == Susp.u32le(h, 88)
            res += Check(headerOk && entriesOk && backupOk, "GPT híbrido",
                "cabeçalho ${ok(headerOk)}, entradas ${ok(entriesOk)}, cópia no fim ${ok(backupOk)}")
        }
        return res
    }

    private fun udfAnchors(r: RandomReader): Check {
        fun tagOk(lba: Long, id: Int): Boolean = runCatching {
            val b = r.readFully(lba * 2048, 512)
            var sum = 0
            for (i in 0 until 16) if (i != 4) sum += b[i].toInt() and 0xFF
            Susp.u16le(b, 0) == id && (sum and 0xFF) == (b[4].toInt() and 0xFF) && Susp.u32le(b, 12) == lba &&
                UdfWriter.crcItu(b, 16, Susp.u16le(b, 10)) == Susp.u16le(b, 8)
        }.getOrDefault(false)
        val first = tagOk(256, 2)
        val last = tagOk(r.size / 2048 - 1, 2)
        return Check(first && last, "Âncoras UDF", "setor 256 ${ok(first)}, último setor ${ok(last)}")
    }

    private fun ok(b: Boolean) = if (b) "ok" else "ERRO"

    private fun hash(input: InputStream, cancel: CancellationSignal, progress: (Long) -> Unit): String {
        val md = HashAlgorithm.SHA256.newDigest()
        val buf = ByteArray(1 shl 20)
        var n = 0L
        while (true) {
            cancel.throwIfCancelled()
            val k = input.read(buf); if (k < 0) break
            md.update(buf, 0, k); n += k; progress(n)
        }
        return md.digest().toHex()
    }

    private fun hashReader(r: RandomReader, cancel: CancellationSignal, progress: (Long) -> Unit): String {
        val md = HashAlgorithm.SHA256.newDigest()
        val buf = ByteArray(1 shl 20)
        var pos = 0L
        while (pos < r.size) {
            cancel.throwIfCancelled()
            val k = r.read(pos, buf, 0, minOf(buf.size.toLong(), r.size - pos).toInt())
            if (k <= 0) break
            md.update(buf, 0, k); pos += k; progress(pos)
        }
        return md.digest().toHex()
    }
}
