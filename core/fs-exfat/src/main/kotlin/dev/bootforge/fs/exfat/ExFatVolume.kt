package dev.bootforge.fs.exfat

import dev.bootforge.blockdev.BlockDevice
import dev.bootforge.blockdev.ProgressListener
import dev.bootforge.blockdev.ProgressTracker
import dev.bootforge.blockdev.readBlocks
import dev.bootforge.fs.exfat.ExFatEntries.u16
import dev.bootforge.fs.exfat.ExFatEntries.u32
import dev.bootforge.fs.exfat.ExFatEntries.u64
import dev.bootforge.integrity.CancellationSignal
import dev.bootforge.integrity.HashAlgorithm
import dev.bootforge.integrity.toHex
import java.io.IOException
import java.io.InputStream

/** Arquivo ou pasta dentro de um volume exFAT. */
data class ExFatEntry(
    val name: String,
    val isDirectory: Boolean,
    val size: Long,
    val firstCluster: Long,
    /** true quando os clusters são contíguos e a FAT não é usada (flag NoFatChain). */
    val contiguous: Boolean,
    val attributes: Int,
    /** Data de modificação em milissegundos (UTC), ou null. */
    val modifiedMillis: Long?,
    val createdMillis: Long? = null,
    val location: EntryLocation? = null,
) {
    val isReadOnly: Boolean get() = attributes and ExFatEntries.ATTR_READ_ONLY != 0
}

/** Onde está o conjunto de entradas: pasta-mãe + posição (bytes) dentro dela. Uso interno. */
data class EntryLocation internal constructor(internal val parent: DirHandle, internal val offset: Int, internal val count: Int)

/** Uma pasta aberta: o raiz (entry == null) ou uma subpasta. Uso interno. */
data class DirHandle internal constructor(internal val path: String, internal val entry: ExFatEntry?)

/** Resultado da gravação de um arquivo. */
data class FileWriteResult(val entry: ExFatEntry, val sha256: String, val fragments: Int)

/**
 * Volume exFAT — leitura e escrita, em Kotlin puro, sobre qualquer [BlockDevice].
 *
 * Usado pelo gerenciador de arquivos e de ISOs do Ventoy. Implementado a partir da
 * especificação pública da Microsoft e validado com o `fsck.exfat` (exfatprogs) e com o
 * driver `mount.exfat-fuse` distribuído pelo próprio Ventoy.
 *
 * Segurança das gravações:
 *  - antes de alterar o volume, liga a flag VolumeDirty (um `fsck` saberá que algo ficou no meio);
 *  - criar: reserva clusters → grava os dados → grava a entrada de diretório (por último);
 *  - excluir: remove a entrada primeiro → depois libera os clusters;
 *  - ao final de cada operação: grava bitmap/FAT modificados e desliga a flag.
 *
 * NÃO é seguro para uso simultâneo por várias threads: o app serializa o acesso.
 */
class ExFatVolume private constructor(private val dev: BlockDevice, val isWritable: Boolean) {

    val bytesPerSector: Int
    val sectorsPerCluster: Int
    val clusterCount: Long
    val volumeSerial: Long
    val bootChecksumValid: Boolean
    private val fatOffset: Long
    private val heapOffset: Long
    private val rootCluster: Long

    val clusterSize: Int get() = bytesPerSector * sectorsPerCluster
    val totalBytes: Long get() = clusterCount * clusterSize

    var label: String? = null
        private set
    private var bitmapCluster = 0L
    private var bitmapLength = 0L
    private var upcaseCluster = 0L
    private var upcaseLength = 0L

    internal val upcase: UpcaseTable
    private lateinit var clusters: ClusterMap
    private var dirty = false

    init {
        val region = dev.readBlocks(0, 12)
        if (String(region, 3, 8, Charsets.US_ASCII) != "EXFAT   ") throw IOException("Não é um volume exFAT")
        bytesPerSector = 1 shl region[108].toInt()
        sectorsPerCluster = 1 shl region[109].toInt()
        if (bytesPerSector != dev.blockSize) throw IOException("Setor exFAT ($bytesPerSector) difere do dispositivo (${dev.blockSize})")
        fatOffset = u32(region, 80)
        heapOffset = u32(region, 88)
        clusterCount = u32(region, 92)
        rootCluster = u32(region, 96)
        volumeSerial = u32(region, 100)
        bootChecksumValid = u32(region, 11 * bytesPerSector) == ExFatFormatter.bootChecksum(region, bytesPerSector)
        if (region[110].toInt() != 1 && isWritable) throw IOException("Volumes exFAT com 2 FATs (TexFAT) não são suportados para gravação")
        if (isWritable && dev.isReadOnly) throw IOException("Dispositivo aberto somente para leitura")
        readRootMetadata()
        upcase = runCatching {
            UpcaseTable.decode(readChainBytes(upcaseCluster, false, upcaseLength).copyOf(upcaseLength.toInt()))
        }.getOrDefault(UpcaseTable.DEFAULT)
        val bmpClusterLbas = chainOrRun(bitmapCluster, false, bitmapLength).flatMap { c ->
            (0 until sectorsPerCluster).map { clusterLba(c) + it }
        }
        clusters = ClusterMap(dev, fatOffset, clusterCount, bmpClusterLbas, ((clusterCount + 7) / 8).toInt())
    }

    // ================================================================= leitura

    private fun readRootMetadata() {
        val raw = readChainBytes(rootCluster, contiguous = false, length = -1)
        var i = 0
        while (i + 32 <= raw.size) {
            when (raw[i].toInt() and 0xFF) {
                ExFatEntries.TYPE_END -> break
                ExFatEntries.TYPE_LABEL -> {
                    val n = raw[i + 1].toInt() and 0xFF
                    label = String(raw, i + 2, n.coerceAtMost(11) * 2, Charsets.UTF_16LE)
                }
                ExFatEntries.TYPE_BITMAP -> if (raw[i + 1].toInt() and 1 == 0) {
                    bitmapCluster = u32(raw, i + 20); bitmapLength = u64(raw, i + 24)
                }
                ExFatEntries.TYPE_UPCASE -> { upcaseCluster = u32(raw, i + 20); upcaseLength = u64(raw, i + 24) }
            }
            i += 32
        }
        if (bitmapCluster == 0L) throw IOException("Bitmap de alocação exFAT não encontrado")
    }

    private fun clusterLba(c: Long) = heapOffset + (c - 2) * sectorsPerCluster

    /** Clusters de um arquivo/pasta: sequência contígua (NoFatChain) ou cadeia da FAT. */
    private fun chainOrRun(first: Long, contiguous: Boolean, length: Long): List<Long> {
        if (first < 2) return emptyList()
        if (contiguous) {
            val n = (length + clusterSize - 1) / clusterSize
            return (0 until n).map { first + it }
        }
        if (::clusters.isInitialized) return clusters.chain(first)
        // durante a inicialização (antes de ClusterMap existir) lê a FAT diretamente
        val out = mutableListOf<Long>()
        var c = first
        while (c in 2..clusterCount + 1 && out.size <= clusterCount) {
            out += c
            val byteOff = c * 4
            val sec = dev.readBlocks(fatOffset + byteOff / bytesPerSector, 1)
            c = u32(sec, (byteOff % bytesPerSector).toInt())
        }
        return out
    }

    private fun readChainBytes(first: Long, contiguous: Boolean, length: Long): ByteArray {
        val list = chainOrRun(first, contiguous, length)
        val out = ByteArray(list.size * clusterSize)
        list.forEachIndexed { i, c -> dev.read(clusterLba(c), out, i * clusterSize, clusterSize) }
        return out
    }

    fun freeClusters(): Long = clusters.freeClusters()
    fun freeBytes(): Long = freeClusters() * clusterSize

    /** Conteúdo bruto de uma pasta + lista de clusters (para gravar entradas no lugar certo). */
    private inner class DirData(var handle: DirHandle) {
        val clusterList: MutableList<Long> = handle.entry?.let { chainOrRun(it.firstCluster, it.contiguous, it.size).toMutableList() }
            ?: chainOrRun(rootCluster, false, -1).toMutableList()
        var bytes: ByteArray = ByteArray(clusterList.size * clusterSize).also { buf ->
            clusterList.forEachIndexed { i, c -> dev.read(clusterLba(c), buf, i * clusterSize, clusterSize) }
        }
    }

    private fun parseDirectory(dir: DirData): List<ExFatEntry> {
        val raw = dir.bytes
        val out = mutableListOf<ExFatEntry>()
        var i = 0
        while (i + 32 <= raw.size) {
            val type = raw[i].toInt() and 0xFF
            if (type == ExFatEntries.TYPE_END) break
            if (type != ExFatEntries.TYPE_FILE) { i += 32; continue }
            val secondary = raw[i + 1].toInt() and 0xFF
            val s = i + 32
            if (secondary < 2 || s + 32 > raw.size || (raw[s].toInt() and 0xFF) != ExFatEntries.TYPE_STREAM) { i += 32; continue }
            val flags = raw[s + 1].toInt() and 0xFF
            val nameLen = raw[s + 3].toInt() and 0xFF
            val sb = StringBuilder()
            var n = 2
            while (n <= secondary && sb.length < nameLen) {
                val e = i + n * 32
                if (e + 32 > raw.size || (raw[e].toInt() and 0xFF) != ExFatEntries.TYPE_NAME) break
                sb.append(String(raw, e + 2, 30, Charsets.UTF_16LE))
                n++
            }
            val attrs = u16(raw, i + 4)
            out += ExFatEntry(
                name = sb.toString().take(nameLen),
                isDirectory = attrs and ExFatEntries.ATTR_DIRECTORY != 0,
                size = u64(raw, s + 24),
                firstCluster = u32(raw, s + 20),
                contiguous = flags and ExFatEntries.FLAG_NO_FAT_CHAIN != 0,
                attributes = attrs,
                modifiedMillis = ExFatEntries.readTimestamp(raw, i + 12, i + 21, i + 23),
                createdMillis = ExFatEntries.readTimestamp(raw, i + 8, i + 20, i + 22),
                location = EntryLocation(dir.handle, i, secondary + 1),
            )
            i += (secondary + 1) * 32
        }
        return out
    }

    private fun splitPath(path: String) = path.split('/').filter { it.isNotEmpty() }

    private fun openDir(path: String): DirHandle {
        val parts = splitPath(path)
        if (parts.isEmpty()) return DirHandle("/", null)
        val e = resolve(path) ?: throw IOException("Pasta não encontrada: $path")
        if (!e.isDirectory) throw IOException("Não é uma pasta: $path")
        return DirHandle("/" + parts.joinToString("/"), e)
    }

    fun list(path: String = "/"): List<ExFatEntry> = parseDirectory(DirData(openDir(path)))

    fun resolve(path: String): ExFatEntry? {
        val parts = splitPath(path)
        if (parts.isEmpty()) return null
        var current: ExFatEntry? = null
        var currentPath = ""
        for (p in parts) {
            if (current != null && !current.isDirectory) return null
            val dir = DirData(DirHandle(currentPath.ifEmpty { "/" }, current))
            current = parseDirectory(dir).firstOrNull { upcase.equalsIgnoreCase(it.name, p) } ?: return null
            currentPath += "/" + current.name
        }
        return current
    }

    fun exists(path: String) = resolve(path) != null

    /** Percorre todos os arquivos recursivamente (limite de profundidade evita laços em volumes corrompidos). */
    fun walk(path: String = "/", maxDepth: Int = 32, visit: (String, ExFatEntry) -> Unit) {
        if (maxDepth < 0) return
        for (e in list(path)) {
            val full = path.trimEnd('/') + "/" + e.name
            visit(full, e)
            if (e.isDirectory) walk(full, maxDepth - 1, visit)
        }
    }

    /** Faixas de clusters ocupadas pelo arquivo, na ordem (1 faixa = arquivo contíguo). */
    fun extents(path: String): List<LongRange> {
        val e = resolve(path) ?: throw IOException("Arquivo não encontrado: $path")
        return toRuns(chainOrRun(e.firstCluster, e.contiguous, e.size))
    }

    /**
     * Para arquivos contíguos: primeiro setor (relativo ao início do volume) e quantidade de setores.
     * Usado para formatar um arquivo de persistência "por dentro" sem copiar dados.
     */
    fun contiguousSectorRange(path: String): Pair<Long, Long>? {
        val e = resolve(path) ?: return null
        val runs = extents(path)
        if (runs.size != 1) return null
        return clusterLba(runs[0].first) to (e.size + bytesPerSector - 1) / bytesPerSector
    }

    /** Abre um arquivo para leitura sequencial. */
    fun openFile(path: String): InputStream {
        val e = resolve(path) ?: throw IOException("Arquivo não encontrado: $path")
        if (e.isDirectory) throw IOException("É uma pasta: $path")
        val runs = toRuns(chainOrRun(e.firstCluster, e.contiguous, e.size))
        return ClusterInputStream(runs, e.size)
    }

    private inner class ClusterInputStream(private val runs: List<LongRange>, private val size: Long) : InputStream() {
        private var pos = 0L
        private val buf = ByteArray(maxOf(clusterSize, 1 shl 20) / clusterSize * clusterSize)
        private var bufStart = -1L
        private var bufLen = 0

        /** Converte índice de cluster do arquivo → cluster no volume. */
        private fun clusterAt(index: Long): Long {
            var i = index
            for (r in runs) {
                val len = r.last - r.first + 1
                if (i < len) return r.first + i
                i -= len
            }
            throw IOException("Cadeia de clusters menor que o arquivo")
        }

        private fun fill() {
            val firstIdx = pos / clusterSize
            val first = clusterAt(firstIdx)
            // lê vários clusters consecutivos de uma vez (até 1 MiB)
            var n = 1
            val maxN = buf.size / clusterSize
            while (n < maxN && (firstIdx + n) * clusterSize < size && clusterAt(firstIdx + n) == first + n) n++
            dev.read(clusterLba(first), buf, 0, n * clusterSize)
            bufStart = firstIdx * clusterSize
            bufLen = n * clusterSize
        }

        override fun read(): Int {
            val one = ByteArray(1)
            return if (read(one, 0, 1) < 0) -1 else one[0].toInt() and 0xFF
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (pos >= size) return -1
            if (len == 0) return 0
            if (bufStart < 0 || pos < bufStart || pos >= bufStart + bufLen) fill()
            val inBuf = (pos - bufStart).toInt()
            val n = minOf(len.toLong(), (bufLen - inBuf).toLong(), size - pos).toInt()
            buf.copyInto(b, off, inBuf, inBuf + n)
            pos += n
            return n
        }

        override fun skip(n: Long): Long {
            val k = minOf(n, size - pos).coerceAtLeast(0)
            pos += k
            return k
        }

        override fun available(): Int = minOf(Int.MAX_VALUE.toLong(), size - pos).toInt()
    }

    // ================================================================= escrita

    private fun requireWritable() {
        if (!isWritable) throw IOException("Volume aberto somente para leitura")
    }

    /** Liga a flag VolumeDirty (byte 106, bit 1) no setor de boot principal. */
    private fun markDirty() {
        if (dirty) return
        val s0 = dev.readBlocks(0, 1)
        s0[106] = (s0[106].toInt() or 0x02).toByte()
        dev.write(0, s0)
        dev.flush()
        dirty = true
    }

    /** Grava bitmap/FAT pendentes, atualiza PercentInUse e desliga a flag VolumeDirty. */
    fun flush() {
        if (!isWritable) return
        clusters.flush()
        if (dirty) {
            val s0 = dev.readBlocks(0, 1)
            s0[106] = (s0[106].toInt() and 0x02.inv()).toByte()
            s0[112] = (((clusterCount - freeClusters()) * 100) / maxOf(1, clusterCount)).toInt().coerceIn(0, 100).toByte()
            dev.write(0, s0)
            dirty = false
        }
        dev.flush()
    }

    private inline fun <T> mutation(block: () -> T): T {
        requireWritable()
        markDirty()
        try {
            return block()
        } finally {
            flush()
        }
    }

    private fun splitParent(path: String): Pair<String, String> {
        val parts = splitPath(path)
        if (parts.isEmpty()) throw IOException("Caminho inválido: $path")
        return ("/" + parts.dropLast(1).joinToString("/")) to parts.last()
    }

    private fun findIn(dir: DirData, name: String) = parseDirectory(dir).firstOrNull { upcase.equalsIgnoreCase(it.name, name) }

    /** Cria uma pasta. Retorna a entrada criada. */
    fun mkdir(path: String): ExFatEntry = mutation {
        val (parentPath, name) = splitParent(path)
        ExFatEntries.validateName(name)
        val dir = DirData(openDir(parentPath))
        if (findIn(dir, name) != null) throw IOException("Já existe um item chamado \"$name\"")
        val run = clusters.allocate(1)
        val c = run[0].first
        dev.write(clusterLba(c), ByteArray(clusterSize)) // pasta nova: tudo zero (fim de diretório)
        clusters.writeChain(run) // pastas usam cadeia FAT: crescem sem conversão
        val now = System.currentTimeMillis()
        insertSet(
            dir,
            ExFatEntries.SetData(name, ExFatEntries.ATTR_DIRECTORY, c, clusterSize.toLong(), clusterSize.toLong(), false, now, now, now),
        )
    }

    /** Cria todas as pastas do caminho que ainda não existem. */
    fun mkdirs(path: String) {
        var cur = ""
        for (p in splitPath(path)) {
            cur += "/$p"
            val e = resolve(cur)
            if (e == null) mkdir(cur) else if (!e.isDirectory) throw IOException("\"$cur\" existe e não é uma pasta")
        }
    }

    /**
     * Grava um arquivo a partir de um stream (ex.: uma ISO vinda do celular).
     *
     * @param size tamanho exato em bytes; se o stream terminar antes, a operação FALHA e nada é criado.
     * @param overwrite substitui um arquivo existente: os dados novos são gravados em clusters novos,
     * a entrada é trocada e só então os clusters antigos são liberados (o original fica intacto se falhar).
     * @param requireContiguous falha se não houver espaço contíguo (útil para arquivos de persistência).
     */
    fun writeFile(
        path: String,
        input: InputStream,
        size: Long,
        overwrite: Boolean = false,
        requireContiguous: Boolean = false,
        modifiedMillis: Long = System.currentTimeMillis(),
        listener: ProgressListener = ProgressListener.NONE,
        cancel: CancellationSignal = CancellationSignal.NONE,
    ): FileWriteResult = mutation {
        require(size >= 0)
        val (parentPath, name) = splitParent(path)
        ExFatEntries.validateName(name)
        val parent = openDir(parentPath)
        val existing = findIn(DirData(parent), name)
        if (existing != null) {
            if (!overwrite) throw IOException("Já existe um item chamado \"$name\"")
            if (existing.isDirectory) throw IOException("\"$name\" é uma pasta")
        }
        val needed = (size + clusterSize - 1) / clusterSize
        if (needed > freeClusters()) throw IOException("Espaço insuficiente: faltam ${(needed - freeClusters()) * clusterSize} bytes")
        val runs = clusters.allocate(needed)
        if (requireContiguous && runs.size > 1) {
            clusters.free(runs)
            throw IOException("Não há espaço contíguo suficiente para o arquivo")
        }
        val digest = HashAlgorithm.SHA256.newDigest()
        try {
            writeData(runs, input, size, digest, listener, cancel)
        } catch (e: Throwable) {
            clusters.free(runs) // nada é criado se a gravação falhar
            throw e
        }
        if (runs.size > 1) clusters.writeChain(runs)
        val first = runs.firstOrNull()?.first ?: 0L
        val created = existing?.createdMillis ?: modifiedMillis
        val data = ExFatEntries.SetData(name, ExFatEntries.ATTR_ARCHIVE, first, size, size, runs.size <= 1, created, modifiedMillis, modifiedMillis)
        val entry = if (existing == null) {
            insertSet(DirData(parent), data)
        } else {
            // substituição: grava a nova entrada no lugar da antiga e só então libera os clusters antigos
            val oldRuns = toRuns(chainOrRun(existing.firstCluster, existing.contiguous, existing.size))
            val e = replaceSet(existing, data)
            clusters.free(oldRuns)
            e
        }
        FileWriteResult(entry, digest.digest().toHex(), runs.size)
    }

    private fun writeData(
        runs: List<LongRange>, input: InputStream, size: Long, digest: java.security.MessageDigest,
        listener: ProgressListener, cancel: CancellationSignal,
    ) {
        val tracker = ProgressTracker("Gravando", size, listener)
        val chunkClusters = maxOf(1, (1 shl 20) / clusterSize)
        val buf = ByteArray(chunkClusters * clusterSize)
        var written = 0L
        for (run in runs) {
            var c = run.first
            while (c <= run.last) {
                cancel.throwIfCancelled()
                val n = minOf(chunkClusters.toLong(), run.last - c + 1).toInt()
                val want = minOf(n.toLong() * clusterSize, size - written).toInt()
                var got = 0
                while (got < want) {
                    val r = input.read(buf, got, want - got)
                    if (r < 0) throw IOException("Arquivo de origem terminou antes do esperado ($written/$size bytes)")
                    got += r
                }
                digest.update(buf, 0, got)
                val bytesToWrite = (got + bytesPerSector - 1) / bytesPerSector * bytesPerSector
                buf.fill(0, got, bytesToWrite)
                dev.write(clusterLba(c), buf, 0, bytesToWrite)
                written += got
                c += n
                tracker.update(written)
            }
        }
        if (written != size) throw IOException("Tamanho gravado ($written) difere do esperado ($size)")
        tracker.update(written, force = true)
    }

    /** Exclui um arquivo ou pasta (pastas não vazias exigem [recursive]). */
    fun delete(path: String, recursive: Boolean = false): Unit = mutation { deleteInternal(path, recursive) }

    private fun deleteInternal(path: String, recursive: Boolean) {
        val e = resolve(path) ?: throw IOException("Não encontrado: $path")
        if (e.isDirectory) {
            val children = list(path)
            if (children.isNotEmpty() && !recursive) throw IOException("A pasta não está vazia")
            for (child in children) deleteInternal(path.trimEnd('/') + "/" + child.name, true)
        }
        val fresh = resolve(path) ?: return
        val runs = toRuns(chainOrRun(fresh.firstCluster, fresh.contiguous, fresh.size))
        markSetDeleted(fresh.location!!)
        clusters.free(runs)
    }

    /** Renomeia sem mudar de pasta. */
    fun rename(path: String, newName: String): ExFatEntry = mutation {
        ExFatEntries.validateName(newName)
        val e = resolve(path) ?: throw IOException("Não encontrado: $path")
        val dir = DirData(e.location!!.parent)
        findIn(dir, newName)?.let { if (it.location != e.location) throw IOException("Já existe um item chamado \"$newName\"") }
        replaceSet(e, setDataOf(e).copy(name = newName))
    }

    /** Move para outra pasta (opcionalmente com outro nome). Nenhum dado é copiado. */
    fun move(path: String, destDir: String, newName: String? = null): ExFatEntry = mutation {
        val e = resolve(path) ?: throw IOException("Não encontrado: $path")
        val name = newName ?: e.name
        ExFatEntries.validateName(name)
        val dest = openDir(destDir)
        val srcNorm = "/" + splitPath(path).joinToString("/")
        if (e.isDirectory && (dest.path == srcNorm || dest.path.startsWith("$srcNorm/"))) {
            throw IOException("Não é possível mover uma pasta para dentro dela mesma")
        }
        if (dest.path == e.location!!.parent.path) return@mutation replaceSet(e, setDataOf(e).copy(name = name))
        val destData = DirData(dest)
        if (findIn(destData, name) != null) throw IOException("Já existe \"$name\" no destino")
        val created = insertSet(destData, setDataOf(e).copy(name = name))
        // a entrada antiga pode ter mudado de posição? não: está em outra pasta
        markSetDeleted(e.location)
        created
    }

    /** Copia um arquivo (ou pasta, recursivamente) dentro do próprio pendrive. */
    fun copy(
        path: String, destDir: String, newName: String? = null,
        listener: ProgressListener = ProgressListener.NONE, cancel: CancellationSignal = CancellationSignal.NONE,
    ): ExFatEntry {
        requireWritable()
        val e = resolve(path) ?: throw IOException("Não encontrado: $path")
        val target = destDir.trimEnd('/') + "/" + (newName ?: e.name)
        if (e.isDirectory) {
            val srcNorm = "/" + splitPath(path).joinToString("/")
            val destNorm = "/" + splitPath(destDir).joinToString("/")
            if (destNorm == srcNorm || destNorm.startsWith("$srcNorm/")) throw IOException("Não é possível copiar uma pasta para dentro dela mesma")
            val created = mkdir(target)
            for (child in list(path)) copy(path.trimEnd('/') + "/" + child.name, target, null, listener, cancel)
            return created
        }
        return openFile(path).use { input ->
            writeFile(target, input, e.size, modifiedMillis = e.modifiedMillis ?: System.currentTimeMillis(), listener = listener, cancel = cancel).entry
        }
    }

    // ----------------------------------------------------------------- entradas

    private fun setDataOf(e: ExFatEntry): ExFatEntries.SetData {
        val now = System.currentTimeMillis()
        return ExFatEntries.SetData(
            e.name, e.attributes, e.firstCluster, e.size, e.size, e.contiguous,
            e.createdMillis ?: now, e.modifiedMillis ?: now, now,
        )
    }

    /** Insere um conjunto de entradas na pasta (cresce a pasta se faltar espaço). */
    private fun insertSet(dir: DirData, data: ExFatEntries.SetData): ExFatEntry {
        val set = ExFatEntries.encodeSet(data, upcase)
        val count = set.size / 32
        var offset = findFreeSlots(dir, count)
        while (offset < 0) {
            growDirectory(dir)
            offset = findFreeSlots(dir, count)
        }
        writeDirBytes(dir, offset, set)
        return parseDirectory(dir).first { it.location!!.offset == offset }
    }

    /** Substitui o conjunto de [old] (mesma pasta), mudando de posição se o novo for maior. */
    private fun replaceSet(old: ExFatEntry, data: ExFatEntries.SetData): ExFatEntry {
        val loc = old.location!!
        val dir = DirData(loc.parent)
        val set = ExFatEntries.encodeSet(data, upcase)
        val count = set.size / 32
        if (count <= loc.count) {
            writeDirBytes(dir, loc.offset, set)
            for (k in count until loc.count) clearInUse(dir, loc.offset + k * 32)
            return parseDirectory(dir).first { it.location!!.offset == loc.offset }
        }
        val created = insertSet(dir, data) // grava a nova antes de apagar a antiga
        val fresh = DirData(loc.parent)
        for (k in 0 until loc.count) clearInUse(fresh, loc.offset + k * 32)
        return created
    }

    private fun markSetDeleted(loc: EntryLocation) {
        val dir = DirData(loc.parent)
        for (k in 0 until loc.count) clearInUse(dir, loc.offset + k * 32)
    }

    private fun clearInUse(dir: DirData, off: Int) {
        val t = dir.bytes[off].toInt() and 0xFF
        if (t and ExFatEntries.IN_USE != 0) writeDirBytes(dir, off, byteArrayOf((t and 0x7F).toByte()))
    }

    /** Procura [count] entradas livres seguidas (entradas com o bit InUse desligado). */
    private fun findFreeSlots(dir: DirData, count: Int): Int {
        val raw = dir.bytes
        var run = 0
        var i = 0
        while (i + 32 <= raw.size) {
            val t = raw[i].toInt() and 0xFF
            if (t and ExFatEntries.IN_USE == 0) {
                run++
                if (run == count) return i - (count - 1) * 32
            } else run = 0
            i += 32
        }
        return -1
    }

    /** Acrescenta um cluster zerado à pasta e atualiza o tamanho dela na pasta-mãe. */
    private fun growDirectory(dir: DirData) {
        val last = dir.clusterList.last()
        val entry = dir.handle.entry
        val contiguous = entry?.contiguous == true
        val newCluster = if (clusters.tryAllocateAt(last + 1)) last + 1 else clusters.allocate(1)[0].first
        dev.write(clusterLba(newCluster), ByteArray(clusterSize))
        val stillContiguous = contiguous && newCluster == last + 1
        if (!stillContiguous) {
            if (contiguous) clusters.writeChain(listOf(dir.clusterList.first()..last)) // converte para cadeia
            clusters.fatSet(last, newCluster)
            clusters.fatSet(newCluster, ClusterMap.EOC)
        }
        dir.clusterList += newCluster
        dir.bytes = dir.bytes + ByteArray(clusterSize)
        if (entry != null) {
            val newSize = dir.clusterList.size.toLong() * clusterSize
            val data = setDataOf(entry).copy(dataLength = newSize, validDataLength = newSize, noFatChain = stillContiguous)
            // a pasta passa a se referir à entrada atualizada (tamanho e flag NoFatChain novos)
            dir.handle = dir.handle.copy(entry = replaceSet(entry, data))
        }
    }

    /** Grava bytes dentro do conteúdo da pasta (leitura-modificação-escrita por setor). */
    private fun writeDirBytes(dir: DirData, offset: Int, data: ByteArray) {
        data.copyInto(dir.bytes, offset)
        val bps = bytesPerSector
        val firstSector = offset / bps
        val lastSector = (offset + data.size - 1) / bps
        for (s in firstSector..lastSector) {
            val clusterIdx = (s.toLong() * bps / clusterSize).toInt()
            val inCluster = (s.toLong() * bps % clusterSize) / bps
            val lba = clusterLba(dir.clusterList[clusterIdx]) + inCluster
            dev.write(lba, dir.bytes, s * bps, bps)
        }
    }

    private fun toRuns(list: List<Long>): List<LongRange> {
        val out = mutableListOf<LongRange>()
        var start = -1L
        var prev = -1L
        for (c in list) {
            if (start < 0) { start = c; prev = c; continue }
            if (c == prev + 1) { prev = c; continue }
            out += start..prev
            start = c; prev = c
        }
        if (start >= 0) out += start..prev
        return out
    }

    companion object {
        /** Abre o volume. Com [writable] = true é possível criar, alterar e excluir. */
        fun open(dev: BlockDevice, writable: Boolean = false): ExFatVolume = ExFatVolume(dev, writable)
    }
}
