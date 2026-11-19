package dev.bootforge.blockdev

import dev.bootforge.integrity.CancellationSignal
import java.io.IOException
import java.security.SecureRandom

/**
 * Teste de CAPACIDADE REAL (detecta pendrive falsificado), sem apagar os dados.
 *
 * Pendrives falsificados anunciam, por exemplo, 64 GB mas só têm 4–8 GB de memória: o que é
 * gravado além disso some ou sobrescreve o começo do pendrive, sem nenhum aviso.
 *
 * Como funciona (rápido, ~10–20 MB lidos/gravados):
 *  1. escolhe ~[points] pontos do início ao fim, espaçados por uma potência de 2;
 *  2. guarda o conteúdo original de cada ponto (na memória);
 *  3. grava em cada ponto uma marca única (sessão + posição + dados pseudoaleatórios);
 *  4. relê TODOS os pontos: num pendrive falso, as marcas do fim somem ou aparecem no lugar
 *     das do começo;
 *  5. devolve o conteúdo original (do fim para o começo, para que a parte real fique certa
 *     mesmo num pendrive falso) e confere.
 *
 * É um teste por amostragem: confirma a capacidade com boa confiança, mas não substitui um
 * teste completo de superfície (que grava o pendrive inteiro).
 */
object CapacityTest {

    data class Result(
        val declaredBytes: Long,
        val points: Int,
        val badPoints: Int,
        /** Posição (bytes) do primeiro ponto que falhou, ou null. */
        val firstBadOffset: Long?,
        /** Capacidade que realmente funcionou (até o último ponto bom antes da primeira falha). */
        val realBytesEstimate: Long,
        /** Conteúdo original devolvido e conferido nos pontos que funcionam. */
        val restored: Boolean,
        /** O pendrive "dá a volta": gravar além da capacidade real sobrescreve o começo. */
        val wraps: Boolean = false,
    ) {
        val genuine: Boolean get() = badPoints == 0
    }

    fun run(
        dev: BlockDevice, points: Int = 128, sampleBytes: Int = 64 * 1024,
        listener: ProgressListener = ProgressListener.NONE, cancel: CancellationSignal = CancellationSignal.NONE,
    ): Result {
        if (dev.isReadOnly) throw IOException("Dispositivo aberto somente para leitura")
        val bs = dev.blockSize
        val sample = sampleBytes / bs * bs
        val declared = dev.sizeBytes
        if (declared < 4L shl 20) throw IOException("Dispositivo pequeno demais para o teste")
        // Pontos a cada potência de 2 (deslocados 1 MiB, para não tocar na tabela de partições).
        // Pendrives falsos costumam "dar a volta" num tamanho potência de 2 (4, 8, 16, 32 GB):
        // assim a marca gravada além da capacidade real cai EXATAMENTE sobre a de outro ponto,
        // e a diferença entre os dois revela a capacidade real.
        val first = 1L shl 20
        val step = maxOf(1L shl 20, java.lang.Long.highestOneBit(declared / points))
        val offsets = (generateSequence(first) { it + step }.takeWhile { it + sample <= declared } +
            ((declared - sample) / bs * bs)).distinct().sorted().toList()
        val session = SecureRandom().nextLong()
        val total = offsets.size.toLong() * sample * 5
        val tracker = ProgressTracker("Testando capacidade", total, listener)
        var done = 0L
        fun step() { done += sample; tracker.update(done) }

        // 1) guarda o original
        val originals = offsets.map { off -> ByteArray(sample).also { dev.read(off / bs, it); cancel.throwIfCancelled(); step() } }
        val bad = mutableListOf<Long>()
        val wraps = mutableListOf<Long>()
        var restored = true
        try {
            // 2) grava as marcas
            for (off in offsets) { cancel.throwIfCancelled(); dev.write(off / bs, pattern(session, off, sample)); step() }
            dev.flush()
            // 3) confere todas; se um ponto tiver a marca de OUTRO ponto, o pendrive "deu a volta"
            val buf = ByteArray(sample)
            for (off in offsets) {
                cancel.throwIfCancelled()
                runCatching { dev.read(off / bs, buf) }.onFailure { bad += off }.onSuccess {
                    if (!buf.contentEquals(pattern(session, off, sample))) {
                        bad += off
                        foreignOffset(buf, session)?.takeIf { it != off }?.let { wraps += kotlin.math.abs(it - off) }
                    }
                }
                step()
            }
        } finally {
            // 4) devolve o original, do fim para o começo (num pendrive falso a parte real fica por último = certa)
            for (i in offsets.indices.reversed()) {
                runCatching { dev.write(offsets[i] / bs, originals[i]) }.onFailure { if (offsets[i] !in bad) restored = false }
                done += sample; tracker.update(done)
            }
            runCatching { dev.flush() }
        }
        // 5) confere a restauração onde o pendrive funciona
        val buf = ByteArray(sample)
        offsets.forEachIndexed { i, off ->
            if (off !in bad) {
                val ok = runCatching { dev.read(off / bs, buf); buf.contentEquals(originals[i]) }.getOrDefault(false)
                if (!ok) restored = false
            }
            step()
        }
        tracker.update(total, force = true)
        val firstBad = bad.minOrNull()
        val real = when {
            firstBad == null -> declared
            wraps.isNotEmpty() -> minOf(declared, wraps.min()) // "deu a volta": a menor distância é o tamanho real
            else -> offsets.filter { it < firstBad }.maxOrNull()?.plus(sample) ?: 0L
        }
        return Result(declared, offsets.size, bad.size, firstBad, real, restored, wraps.isNotEmpty())
    }

    /** Se o bloco contém a marca desta sessão, devolve a posição gravada nela. */
    private fun foreignOffset(b: ByteArray, session: Long): Long? {
        val head = "BOOTFORGE-CAPACIDADE".toByteArray()
        if (!b.copyOf(head.size).contentEquals(head)) return null
        var s = 0L; var o = 0L
        for (i in 0 until 8) { s = s or ((b[24 + i].toLong() and 0xFF) shl (8 * i)); o = o or ((b[32 + i].toLong() and 0xFF) shl (8 * i)) }
        return if (s == session) o else null
    }

    /** Marca única de um ponto: cabeçalho legível + sessão + posição + bytes pseudoaleatórios. */
    internal fun pattern(session: Long, offset: Long, size: Int): ByteArray {
        val b = ByteArray(size)
        val head = "BOOTFORGE-CAPACIDADE".toByteArray()
        head.copyInto(b)
        for (i in 0 until 8) { b[24 + i] = (session ushr (8 * i)).toByte(); b[32 + i] = (offset ushr (8 * i)).toByte() }
        var x = session xor offset xor 0x5DEECE66DL
        for (i in 64 until size) {
            x = x xor (x shl 13); x = x xor (x ushr 7); x = x xor (x shl 17) // xorshift64
            b[i] = x.toByte()
        }
        return b
    }
}
