package dev.bootforge.fs.exfat

/**
 * Tabela de conversão para maiúsculas do exFAT (especificação, seção 7.2).
 *
 * O exFAT compara nomes SEM diferenciar maiúsculas/minúsculas usando esta tabela, que fica
 * gravada no próprio volume. Ela também é usada no cálculo do NameHash das entradas.
 *
 * Formato compactado: sequência de caracteres UTF-16; o valor 0xFFFF seguido de N significa
 * "os próximos N caracteres mapeiam para si mesmos".
 */
class UpcaseTable private constructor(private val map: CharArray) {

    fun upcase(c: Char): Char = map[c.code]

    fun upcase(s: String): String {
        val out = CharArray(s.length)
        for (i in s.indices) out[i] = map[s[i].code]
        return String(out)
    }

    fun equalsIgnoreCase(a: String, b: String): Boolean =
        a.length == b.length && a.indices.all { map[a[it].code] == map[b[it].code] }

    companion object {
        /** Decodifica a tabela (compactada ou não) lida do volume. */
        fun decode(raw: ByteArray): UpcaseTable {
            val map = CharArray(65536) { it.toChar() }
            var index = 0
            var i = 0
            while (i + 1 < raw.size && index < 65536) {
                val v = (raw[i].toInt() and 0xFF) or ((raw[i + 1].toInt() and 0xFF) shl 8)
                i += 2
                if (v == 0xFFFF && i + 1 < raw.size) {
                    val skip = (raw[i].toInt() and 0xFF) or ((raw[i + 1].toInt() and 0xFF) shl 8)
                    i += 2
                    index += skip // identidade (já preenchida)
                } else {
                    map[index++] = v.toChar()
                }
            }
            return UpcaseTable(map)
        }

        /** Tabela padrão recomendada pela especificação (a que o nosso formatador grava). */
        val DEFAULT: UpcaseTable by lazy { decode(ExFatFormatter.upcaseTable) }
    }
}
