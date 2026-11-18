package dev.bootforge.iso

import dev.bootforge.blockdev.RandomReader

/** Categorias sugeridas para organizar o pendrive (nunca impostas). */
enum class ImageCategory(val folder: String) {
    WINDOWS("Windows"), LINUX("Linux"), TOOLS("Ferramentas"), RESCUE("Resgate"), DIAGNOSTIC("Diagnostico")
}

/** Formato do arquivo de imagem, detectado pelo CONTEÚDO (não pela extensão). */
enum class ImageFormat(val label: String) {
    ISO9660("ISO9660"), UDF("UDF"), ISO_UDF_BRIDGE("ISO9660 + UDF"), DISK_IMAGE("Imagem de disco (MBR/GPT)"),
    VHD("VHD"), VHDX("VHDX"), WIM("WIM"), EFI("Executável EFI"), UNKNOWN("Desconhecido")
}

/** Sistema operacional identificado e a evidência usada (para mostrar ao usuário). */
data class OsGuess(val name: String, val category: ImageCategory?, val evidence: String)

data class ImageInfo(
    val fileName: String,
    val size: Long,
    val format: ImageFormat,
    val volumeId: String? = null,
    val publisher: String? = null,
    val application: String? = null,
    /** Tamanho declarado pelo ISO9660 (blocos × 2048). */
    val declaredSize: Long? = null,
    val joliet: Boolean = false,
    val elTorito: Boolean = false,
    val bootBios: Boolean = false,
    val bootUefi: Boolean = false,
    val hybridMbr: Boolean = false,
    val hybridGpt: Boolean = false,
    /** Conteúdo de /.disk/info (Ubuntu, Debian, Mint… descrevem a versão aqui). */
    val diskInfo: String? = null,
    val os: OsGuess? = null,
    /** Problemas encontrados (ex.: arquivo truncado). Vazio = nada suspeito. */
    val problems: List<String> = emptyList(),
) {
    val isTruncated: Boolean get() = declaredSize != null && declaredSize > size
}

/**
 * Analisa um arquivo de imagem (ISO, IMG, VHD, WIM…) lendo só os setores necessários.
 * Nada é alterado. Funciona com arquivos do celular e arquivos dentro do pendrive.
 */
object ImageInspector {

    fun inspect(fileName: String, r: RandomReader): ImageInfo {
        val problems = mutableListOf<String>()
        val size = r.size
        if (size < 512) return ImageInfo(fileName, size, ImageFormat.UNKNOWN, problems = listOf("Arquivo pequeno demais para ser uma imagem"))
        val head = r.readFully(0, minOf(size, 4096).toInt())

        // Formatos não-ISO identificados por assinatura
        if (startsWith(head, "MSWIM\u0000\u0000\u0000")) return ImageInfo(fileName, size, ImageFormat.WIM, os = OsGuess("Imagem WIM do Windows", ImageCategory.WINDOWS, "assinatura MSWIM"))
        if (startsWith(head, "vhdxfile")) return ImageInfo(fileName, size, ImageFormat.VHDX)
        if (size >= 512 && startsWith(r.readFully(size - 512, 8), "conectix")) return ImageInfo(fileName, size, ImageFormat.VHD)
        if (head[0] == 'M'.code.toByte() && head[1] == 'Z'.code.toByte() && fileName.endsWith(".efi", true)) return ImageInfo(fileName, size, ImageFormat.EFI)

        val hybridMbr = (head[510].toInt() and 0xFF) == 0x55 && (head[511].toInt() and 0xFF) == 0xAA &&
            (0 until 4).any { (head[0x1BE + it * 16 + 4].toInt() and 0xFF) != 0 }
        val hybridGpt = head.size >= 520 && String(head, 512, 8, Charsets.US_ASCII) == "EFI PART"

        // Descritores de volume (ISO9660 e sequência de reconhecimento UDF)
        var pvd: ByteArray? = null
        var bootRecord: ByteArray? = null
        var udf = false
        for (i in 16 until 16 + 64) {
            if ((i + 1L) * 2048 > size) break
            val d = r.readFully(i * 2048L, 2048)
            val id = String(d, 1, 5, Charsets.US_ASCII)
            when (id) {
                "CD001" -> when (d[0].toInt() and 0xFF) {
                    0 -> if (String(d, 7, 23, Charsets.US_ASCII) == "EL TORITO SPECIFICATION") bootRecord = d
                    1 -> pvd = d
                }
                "NSR02", "NSR03" -> udf = true
                "BEA01", "TEA01", "BOOT2", "CDW02" -> Unit
                else -> if (d.all { it.toInt() == 0 }) Unit else break
            }
        }
        if (pvd == null && !udf) {
            return ImageInfo(
                fileName, size, if (hybridMbr || hybridGpt) ImageFormat.DISK_IMAGE else ImageFormat.UNKNOWN,
                hybridMbr = hybridMbr, hybridGpt = hybridGpt,
                problems = if (!hybridMbr && !hybridGpt) listOf("Nenhum sistema de arquivos de imagem reconhecido (ISO9660/UDF/MBR/GPT)") else emptyList(),
            )
        }
        val format = when {
            pvd != null && udf -> ImageFormat.ISO_UDF_BRIDGE
            pvd != null -> ImageFormat.ISO9660
            else -> ImageFormat.UDF
        }

        var volumeId: String? = null
        var publisher: String? = null
        var application: String? = null
        var declared: Long? = null
        pvd?.let { d ->
            volumeId = text(d, 40, 32)
            publisher = text(d, 318, 128)
            application = text(d, 574, 128)
            val blocks = IsoFileSystem.u32(d, 80)
            val blockSize = IsoFileSystem.u16(d, 128).let { if (it == 0) 2048 else it }
            declared = blocks * blockSize
        }
        if (declared != null && declared!! > size) {
            problems += "Arquivo TRUNCADO: o ISO declara ${declared} bytes, mas o arquivo tem $size (download incompleto?)"
        }

        // El Torito: plataformas de boot
        var bios = false
        var uefi = false
        bootRecord?.let { br ->
            val catalogLba = IsoFileSystem.u32(br, 0x47)
            runCatching {
                val cat = r.readFully(catalogLba * 2048, 2048)
                if ((cat[0].toInt() and 0xFF) == 0x01 && (cat[30].toInt() and 0xFF) == 0x55 && (cat[31].toInt() and 0xFF) == 0xAA) {
                    when (cat[1].toInt() and 0xFF) { 0 -> bios = true; 0xEF -> uefi = true }
                    var p = 64
                    while (p + 32 <= cat.size) {
                        val h = cat[p].toInt() and 0xFF
                        if (h != 0x90 && h != 0x91) break
                        val plat = cat[p + 1].toInt() and 0xFF
                        val n = IsoFileSystem.u16(cat, p + 2)
                        if (plat == 0) bios = true
                        if (plat == 0xEF) uefi = true
                        p += 32 + n * 32
                        if (h == 0x91) break
                    }
                } else problems += "Catálogo El Torito inválido"
            }.onFailure { problems += "Não foi possível ler o catálogo El Torito" }
        }

        // Conteúdo: /.disk/info e marcadores de pastas
        var diskInfo: String? = null
        val markers = mutableSetOf<String>()
        runCatching {
            IsoFileSystem.open(r)?.let { fs ->
                fs.find("/.disk/info")?.takeIf { !it.isDirectory && it.size in 1..4096 }?.let {
                    diskInfo = String(fs.readSmallFile(it), Charsets.UTF_8).trim().lines().firstOrNull()?.trim()
                }
                val rootNames = fs.list("/").map { it.name.lowercase() }.toSet()
                markers += rootNames
                if ("sources" in rootNames) fs.list("/sources").forEach { markers += "sources/" + it.name.lowercase() }
            }
        }

        val os = guessOs(volumeId, diskInfo, markers, udf)
        return ImageInfo(
            fileName, size, format, volumeId, publisher, application, declared,
            joliet = runCatching { IsoFileSystem.open(r)?.joliet == true }.getOrDefault(false),
            elTorito = bootRecord != null, bootBios = bios, bootUefi = uefi,
            hybridMbr = hybridMbr, hybridGpt = hybridGpt, diskInfo = diskInfo, os = os, problems = problems,
        )
    }

    /** Tabela de identificação. Ordem importa: o primeiro que casar vence. */
    private data class Rule(val pattern: Regex, val name: String, val category: ImageCategory)

    private val rules = listOf(
        Rule(Regex("linux ?mint", RegexOption.IGNORE_CASE), "Linux Mint", ImageCategory.LINUX),
        Rule(Regex("pop[_!]*_?os", RegexOption.IGNORE_CASE), "Pop!_OS", ImageCategory.LINUX),
        Rule(Regex("zorin", RegexOption.IGNORE_CASE), "Zorin OS", ImageCategory.LINUX),
        Rule(Regex("linux ?lite", RegexOption.IGNORE_CASE), "Linux Lite", ImageCategory.LINUX),
        Rule(Regex("kali", RegexOption.IGNORE_CASE), "Kali Linux", ImageCategory.LINUX),
        Rule(Regex("elementary", RegexOption.IGNORE_CASE), "elementary OS", ImageCategory.LINUX),
        Rule(Regex("(k|x|l)?ubuntu", RegexOption.IGNORE_CASE), "Ubuntu", ImageCategory.LINUX),
        Rule(Regex("debian", RegexOption.IGNORE_CASE), "Debian", ImageCategory.LINUX),
        Rule(Regex("fedora", RegexOption.IGNORE_CASE), "Fedora", ImageCategory.LINUX),
        Rule(Regex("almalinux|rocky|rhel|centos", RegexOption.IGNORE_CASE), "Enterprise Linux (RHEL/Alma/Rocky/CentOS)", ImageCategory.LINUX),
        Rule(Regex("opensuse|suse", RegexOption.IGNORE_CASE), "openSUSE / SUSE", ImageCategory.LINUX),
        Rule(Regex("manjaro", RegexOption.IGNORE_CASE), "Manjaro", ImageCategory.LINUX),
        Rule(Regex("^arch_", RegexOption.IGNORE_CASE), "Arch Linux", ImageCategory.LINUX),
        Rule(Regex("gparted", RegexOption.IGNORE_CASE), "GParted Live", ImageCategory.RESCUE),
        Rule(Regex("clonezilla", RegexOption.IGNORE_CASE), "Clonezilla", ImageCategory.RESCUE),
        Rule(Regex("rescuezilla", RegexOption.IGNORE_CASE), "Rescuezilla", ImageCategory.RESCUE),
        Rule(Regex("sysrcd|systemrescue", RegexOption.IGNORE_CASE), "SystemRescue", ImageCategory.RESCUE),
        Rule(Regex("hbcd|hiren", RegexOption.IGNORE_CASE), "Hiren's BootCD PE", ImageCategory.TOOLS),
        Rule(Regex("memtest", RegexOption.IGNORE_CASE), "Memtest86", ImageCategory.DIAGNOSTIC),
        // Rótulos típicos das ISOs oficiais da Microsoft (ex.: CCCOMA_X64FRE_PT-BR_DV9)
        Rule(Regex("^(cccoma|cpba|cena|ccsa|cpra|ccoma|j_ccsa|sss_x64|ir[3-6]_|grmc|gsp1)", RegexOption.IGNORE_CASE), "Windows", ImageCategory.WINDOWS),
    )

    internal fun guessOs(volumeId: String?, diskInfo: String?, markers: Set<String>, udf: Boolean): OsGuess? {
        diskInfo?.let { info -> rules.firstOrNull { it.pattern.containsMatchIn(info) }?.let { return OsGuess(info, it.category, "/.disk/info") } }
        volumeId?.let { v -> rules.firstOrNull { it.pattern.containsMatchIn(v) }?.let { return OsGuess(it.name, it.category, "rótulo do volume \"$v\"") } }
        if (markers.any { it == "sources/install.wim" || it == "sources/install.esd" || it == "sources/boot.wim" } || "bootmgr" in markers) {
            return OsGuess("Windows (instalação)", ImageCategory.WINDOWS, "pasta /sources com install.wim/boot.wim")
        }
        if (udf && markers.isNotEmpty() && markers.all { it == "readme.txt" }) {
            return OsGuess("Windows (provável)", ImageCategory.WINDOWS, "ISO UDF com apenas README.TXT na parte ISO9660 (padrão das ISOs da Microsoft)")
        }
        if ("casper" in markers) return OsGuess("Linux baseado em Ubuntu", ImageCategory.LINUX, "pasta /casper")
        if ("live" in markers) return OsGuess("Linux Live (Debian ou derivado)", ImageCategory.LINUX, "pasta /live")
        if ("liveos" in markers || "images" in markers && "isolinux" in markers) return OsGuess("Linux (Fedora/RHEL ou derivado)", ImageCategory.LINUX, "pasta /LiveOS ou /images")
        if ("arch" in markers) return OsGuess("Linux baseado em Arch", ImageCategory.LINUX, "pasta /arch")
        return null
    }

    private fun text(b: ByteArray, off: Int, len: Int) =
        String(b, off, len, Charsets.US_ASCII).trim().trimEnd('\u0000').trim().ifEmpty { null }

    private fun startsWith(b: ByteArray, s: String) = b.size >= s.length && s.indices.all { b[it] == s[it].code.toByte() }
}
