package dev.bootforge.integrity

/**
 * Leitor de arquivos de checksum no formato publicado pelas distribuições
 * (ex.: `SHA256SUMS` do Ubuntu, `sha256sum.txt` do Linux Mint, `CHECKSUM` do Fedora).
 *
 * Formatos aceitos:
 *  - GNU coreutils:  `<hash>  <arquivo>` ou `<hash> *<arquivo>`
 *  - BSD/Fedora:     `SHA256 (<arquivo>) = <hash>`
 *
 * Linhas de assinatura PGP (`-----BEGIN PGP ...`) e comentários são ignorados.
 * IMPORTANTE: ler o arquivo de checksum NÃO prova autenticidade; isso exige
 * verificar a assinatura digital do próprio arquivo de checksum (pendente).
 */
object ChecksumFile {

    data class Entry(val fileName: String, val hash: String, val algorithm: HashAlgorithm)

    private val gnuLine = Regex("""^([0-9a-fA-F]{64}|[0-9a-fA-F]{128})\s+\*?(.+)$""")
    private val bsdLine = Regex("""^(SHA256|SHA512)\s*\((.+)\)\s*=\s*([0-9a-fA-F]+)$""")

    fun parse(text: String): List<Entry> {
        val result = mutableListOf<Entry>()
        for (raw in text.lineSequence()) {
            val line = raw.trim()
            if (line.isEmpty() || line.startsWith("#") || line.startsWith("-----") ||
                line.startsWith("Hash:")
            ) continue
            val gnu = gnuLine.matchEntire(line)
            if (gnu != null) {
                val hash = gnu.groupValues[1].lowercase()
                val alg = HashAlgorithm.fromHexLength(hash.length) ?: continue
                result += Entry(gnu.groupValues[2].trim(), hash, alg)
                continue
            }
            val bsd = bsdLine.matchEntire(line) ?: continue
            val alg = if (bsd.groupValues[1] == "SHA256") HashAlgorithm.SHA256 else HashAlgorithm.SHA512
            val hash = bsd.groupValues[3].lowercase()
            if (hash.length == alg.hexLength) result += Entry(bsd.groupValues[2].trim(), hash, alg)
        }
        return result
    }

    /** Procura o hash de um arquivo pelo nome (ignora diretórios no caminho). */
    fun find(entries: List<Entry>, fileName: String): Entry? =
        entries.firstOrNull { it.fileName.substringAfterLast('/') == fileName }
}
