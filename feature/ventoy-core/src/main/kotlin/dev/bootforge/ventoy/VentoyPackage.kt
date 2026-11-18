package dev.bootforge.ventoy

import dev.bootforge.integrity.HashAlgorithm
import dev.bootforge.integrity.toHex
import org.tukaani.xz.XZInputStream
import java.io.File
import java.io.IOException
import java.io.InputStream

/**
 * Acesso aos componentes ORIGINAIS do pacote oficial do Ventoy usados na instalação:
 *
 *  | Caminho no pacote            | Uso                                                            |
 *  |------------------------------|----------------------------------------------------------------|
 *  | `boot/boot.img`              | 512 bytes: código de boot do MBR (GRUB stage 1, BIOS)          |
 *  | `boot/core.img.xz`           | GRUB core (stage 1.5/2 BIOS), gravado logo após o MBR/GPT      |
 *  | `ventoy/ventoy.disk.img.xz`  | imagem de 32 MiB da partição VTOYEFI (FAT16: GRUB, EFI, temas) |
 *  | `ventoy/version`             | versão do pacote (ex.: 1.1.18)                                 |
 *
 * Os arquivos NÃO são modificados: são lidos, descompactados (XZ) e gravados no pendrive
 * exatamente como o Ventoy2Disk faz.
 *
 * @param open função que abre um arquivo do pacote pelo caminho relativo
 *             (no Android lê dos assets do APK; no PC lê de uma pasta).
 */
class VentoyPackage(private val open: (String) -> InputStream) {

    val version: String by lazy { open(VERSION).use { String(it.readBytes(), Charsets.US_ASCII).trim() } }

    /** Os 512 bytes do `boot.img` (cópia nova a cada chamada). */
    fun bootImg(): ByteArray {
        val b = open(BOOT_IMG).use { it.readBytes() }
        if (b.size != 512) throw IOException("boot.img com tamanho inesperado: ${b.size}")
        return b
    }

    /** `core.img` descompactado (1.048.064 bytes na versão 1.1.18). */
    fun coreImg(): ByteArray {
        val b = XZInputStream(open(CORE_IMG_XZ).buffered()).use { it.readBytes() }
        if (b.size > CORE_MAX_BYTES) throw IOException("core.img maior que o espaço reservado (${b.size} bytes)")
        return b
    }

    /** Stream da imagem VTOYEFI descompactada (exatamente [EFI_PART_BYTES] bytes). */
    fun openDiskImg(): InputStream = XZInputStream(open(DISK_IMG_XZ).buffered(1 shl 16))

    /**
     * Confere o SHA-256 dos arquivos do pacote contra os valores conhecidos do pacote
     * oficial (ver [KNOWN_SHA256]). Garante que o APK não foi corrompido/alterado.
     * @return lista de problemas (vazia = tudo certo). Versão desconhecida gera um aviso.
     */
    fun verifyIntegrity(): List<String> {
        val ver = runCatching { version }.getOrElse { return listOf("Pacote do Ventoy não encontrado ou ilegível (${it.message})") }
        val known = KNOWN_SHA256[ver] ?: return listOf("Versão $ver sem hashes de referência cadastrados")
        return known.mapNotNull { (path, expected) ->
            val actual = runCatching {
                open(path).use { input ->
                    val md = HashAlgorithm.SHA256.newDigest()
                    val buf = ByteArray(1 shl 16)
                    while (true) { val n = input.read(buf); if (n < 0) break; md.update(buf, 0, n) }
                    md.digest().toHex()
                }
            }.getOrElse { return@mapNotNull "Não foi possível ler $path: ${it.message}" }
            if (actual != expected) "Hash divergente em $path" else null
        }
    }

    companion object {
        const val BOOT_IMG = "boot/boot.img"
        const val CORE_IMG_XZ = "boot/core.img.xz"
        const val DISK_IMG_XZ = "ventoy/ventoy.disk.img.xz"
        const val VERSION = "ventoy/version"

        /** Partição VTOYEFI: 32 MiB = 65536 setores de 512 bytes (VENTOY_EFI_PART_SIZE). */
        const val EFI_PART_BYTES = 32L * 1024 * 1024
        const val EFI_PART_SECTORS = 65536L

        /** O core.img precisa caber entre o setor 1 e o setor 2047 (1 MiB - 512). */
        const val CORE_MAX_BYTES = 1024 * 1024 - 512

        /**
         * SHA-256 dos arquivos do pacote oficial `ventoy-1.1.18-windows.zip`
         * (SHA-256 do zip: 082c478e8c432e1ac653899fcf50e3a4077a2851bc6112b5ddd30545962164a9).
         */
        val KNOWN_SHA256: Map<String, Map<String, String>> = mapOf(
            "1.1.18" to mapOf(
                BOOT_IMG to "85b4b080765738ca96620c32d8182edddbaf6a36149162946d12aa1f9802bc44",
                CORE_IMG_XZ to "97d70bf738a6e8d72fae6eb1a8993ceb4ac8bdc92570f2ff87bcd9aef3723431",
                DISK_IMG_XZ to "19174f133c4df8acda4423b1bc40ae69961773ef54e286ffd8c42fefaf6f174f",
            ),
        )

        /** Pacote em uma pasta do computador (ex.: `third_party/ventoy-1.1.18`). */
        fun fromDirectory(dir: File): VentoyPackage = VentoyPackage { path -> File(dir, path).inputStream() }
    }
}
