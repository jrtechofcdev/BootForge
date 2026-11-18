package dev.bootforge.isoedit

import dev.bootforge.blockdev.ProgressListener
import dev.bootforge.blockdev.ProgressTracker
import dev.bootforge.integrity.CancellationSignal
import java.io.File
import java.io.InputStream

/** Destino da extração (pasta do PC, pasta do celular via SAF, etc.). */
interface ExtractSink {
    fun directory(path: String)
    fun file(path: String, size: Long, data: InputStream)
}

data class ExtractResult(val files: Int, val dirs: Int, val bytes: Long)

/**
 * Extrai arquivos e pastas da árvore ATUAL da sessão (já com as edições), sem alterar a ISO.
 * [paths] vazio = tudo.
 */
object IsoExtractor {

    fun extract(
        session: EditSession, paths: List<String>, sink: ExtractSink,
        listener: ProgressListener = ProgressListener.NONE, cancel: CancellationSignal = CancellationSignal.NONE,
    ): ExtractResult {
        val items = mutableListOf<Pair<String, VNode>>()
        val roots = paths.ifEmpty { listOf("/") }
        for (p in roots) {
            val node = session.find(p) ?: throw IllegalArgumentException("Não encontrado: $p")
            val base = Paths.parent(p)
            fun rel(full: String) = if (base == "/") full else full.removePrefix(base)
            if (node is VDir) {
                if (Paths.split(p).isNotEmpty()) items += rel(p) to node
                Paths.walk(node, p) { cp, n -> items += rel(cp) to n }
            } else items += rel(p) to node
        }
        val total = items.sumOf { (it.second as? VFile)?.size ?: 0L }
        val tracker = ProgressTracker("Extraindo", total, listener)
        var done = 0L; var files = 0; var dirs = 0
        for ((rel, n) in items) {
            cancel.throwIfCancelled()
            when (n) {
                is VDir -> { sink.directory(rel); dirs++ }
                is VFile -> {
                    val base = done
                    session.open(n).use { input ->
                        sink.file(rel, n.size, object : InputStream() {
                            var read = 0L
                            override fun read(): Int = input.read().also { if (it >= 0) { read++; tracker.update(base + read) } }
                            override fun read(b: ByteArray, off: Int, len: Int): Int {
                                cancel.throwIfCancelled()
                                return input.read(b, off, len).also { if (it > 0) { read += it; tracker.update(base + read) } }
                            }
                        })
                    }
                    done += n.size; files++
                }
            }
        }
        tracker.update(done, force = true)
        return ExtractResult(files, dirs, done)
    }
}

/** Extração para uma pasta local (CLI e testes). */
class DirectorySink(private val dir: File) : ExtractSink {
    override fun directory(path: String) { File(dir, path).mkdirs() }
    override fun file(path: String, size: Long, data: InputStream) {
        val f = File(dir, path)
        f.parentFile?.mkdirs()
        f.outputStream().use { data.copyTo(it) }
    }
}
