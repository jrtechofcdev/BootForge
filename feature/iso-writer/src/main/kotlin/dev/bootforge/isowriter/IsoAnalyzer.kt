package dev.bootforge.isowriter

import dev.bootforge.blockdev.RandomReader
import dev.bootforge.iso.ExtentRandomReader
import dev.bootforge.iso.ImageFormat
import dev.bootforge.iso.ImageInfo
import dev.bootforge.iso.ImageInspector
import dev.bootforge.iso.ImageTree
import dev.bootforge.iso.TreeEntry
import dev.bootforge.wim.WimSplitter

/** Como a imagem será gravada no pendrive. */
enum class WriteMethod(val title: String, val summary: String) {
    DD(
        "Imagem direta (DD)",
        "Copia a ISO bit a bit. Ideal para ISOs híbridas (a maioria das distribuições Linux). Apaga tudo e o pendrive fica com o formato da ISO.",
    ),
    WINDOWS_FAT32(
        "Instalador do Windows (FAT32, UEFI)",
        "Cria uma partição FAT32 e copia os arquivos do instalador. Se o install.wim passar de 4 GB, ele é dividido em install.swm, install2.swm… (método oficial da Microsoft).",
    ),
    EXTRACT_FAT32(
        "Extrair arquivos (FAT32, UEFI)",
        "Cria uma partição FAT32 e copia os arquivos da ISO. Inicia em computadores UEFI pelos arquivos EFI/BOOT da própria ISO.",
    ),
}

/** Esquema de partição usado pelos métodos FAT32. */
enum class PartitionScheme(val title: String, val summary: String) {
    MBR("MBR", "Mais compatível: funciona em UEFI e é reconhecido por qualquer sistema."),
    GPT("GPT", "Padrão moderno de UEFI. Alguns PCs antigos não iniciam por pendrive GPT."),
}

/** Disponibilidade de um método para esta imagem, com os motivos (nada é escondido). */
data class MethodOption(
    val method: WriteMethod,
    val available: Boolean,
    /** Motivos que impedem o método (vazio quando disponível). */
    val blockers: List<String> = emptyList(),
    /** Avisos e observações. */
    val notes: List<String> = emptyList(),
    /** Espaço mínimo necessário no pendrive, em bytes. */
    val requiredBytes: Long = 0,
)

/** Arquivo grande demais para FAT32. */
data class LargeFile(val path: String, val size: Long)

/** Arquivo de instalação do Windows (install.wim / install.esd). */
data class WindowsImage(val path: String, val size: Long, val isEsd: Boolean, val splittable: Boolean, val problem: String?)

data class IsoAnalysis(
    val info: ImageInfo,
    /** Formato da árvore de arquivos lida (UDF ou ISO9660), null quando não há árvore. */
    val treeFormat: String?,
    val isWindows: Boolean,
    val windowsImage: WindowsImage?,
    /** Carregadores UEFI encontrados (ex.: /efi/boot/bootx64.efi). */
    val efiLoaders: List<String>,
    val largeFiles: List<LargeFile>,
    /** Nomes que só diferem em maiúsculas/minúsculas na mesma pasta (FAT não diferencia). */
    val nameConflicts: List<String>,
    val fileCount: Int,
    val totalFileBytes: Long,
    val methods: List<MethodOption>,
    val recommended: WriteMethod?,
    val recommendedScheme: PartitionScheme,
    /** Explicação curta da recomendação. */
    val recommendationReason: String,
) {
    fun option(m: WriteMethod): MethodOption = methods.first { it.method == m }
}

/**
 * Analisa uma imagem e recomenda o método de gravação. Só leitura.
 *
 * Regras (as mesmas usadas por ferramentas consagradas como Rufus e Fedora Media Writer):
 *  - ISO **híbrida** (tem MBR/GPT dentro dela): DD é o método certo — foi feita para isso.
 *  - **Windows** (UDF com sources/boot.wim): nunca DD (não inicia); copiar os arquivos para FAT32,
 *    dividindo install.wim > 4 GB em .swm.
 *  - Outras ISOs com carregador UEFI (`/efi/boot/boot*.efi`): extrair para FAT32.
 */
object IsoAnalyzer {

    private const val FAT32_OVERHEAD = 64L shl 20 // FATs, pastas e arredondamento de clusters

    fun analyze(fileName: String, src: RandomReader): IsoAnalysis {
        val info = ImageInspector.inspect(fileName, src)
        val tree = if (info.format == ImageFormat.ISO9660 || info.format == ImageFormat.UDF || info.format == ImageFormat.ISO_UDF_BRIDGE) runCatching { ImageTree.open(src) }.getOrNull() else null

        val files = mutableListOf<Pair<String, TreeEntry>>()
        val conflicts = mutableListOf<String>()
        if (tree != null) {
            runCatching {
                tree.walk("/") { path, e -> if (!e.isDirectory) files += path to e }
                collectConflicts(tree, "/", conflicts, 0)
            }
        }
        val lower = files.associateBy { it.first.lowercase() }
        val efiLoaders = files.map { it.first }.filter { Regex("^/efi/boot/boot[a-z0-9]+\\.efi$").matches(it.lowercase()) }
        val isWindows = files.any { it.first.lowercase().endsWith("/sources/boot.wim") } &&
            (lower.containsKey("/bootmgr") || lower.containsKey("/bootmgr.efi") || info.os?.name?.contains("Windows") == true)

        val winEntry = files.firstOrNull { (p, _) -> Regex("(^|/)sources/install\\.(wim|esd)$").containsMatchIn(p.lowercase()) }
        val windowsImage = winEntry?.let { (path, e) ->
            val esd = path.lowercase().endsWith(".esd")
            if (e.size <= WimSplitter.FAT32_MAX) WindowsImage(path, e.size, esd, splittable = true, problem = null)
            else {
                val problem = runCatching {
                    val inf = WimSplitter.info(ExtentRandomReader(src, e))
                    when {
                        inf.solid -> "${path.substringAfterLast('/')} usa compressão \"sólida\" (ESD/LZMS) e não pode ser dividido sem recompactar"
                        inf.totalParts != 1 -> "O arquivo já está dividido"
                        else -> null
                    }
                }.getOrElse { "Não foi possível ler o cabeçalho WIM: ${it.message}" }
                WindowsImage(path, e.size, esd, splittable = problem == null, problem = problem)
            }
        }
        val large = files.filter { it.second.size > WimSplitter.FAT32_MAX }.map { LargeFile(it.first, it.second.size) }
        val total = files.sumOf { it.second.size }

        val hybrid = info.hybridMbr || info.hybridGpt
        val methods = mutableListOf<MethodOption>()

        // ---- DD
        run {
            val blockers = mutableListOf<String>()
            val notes = mutableListOf<String>()
            if (info.isTruncated) blockers += "A imagem está incompleta (menor que o tamanho declarado): baixe novamente"
            if (isWindows) blockers += "ISOs do Windows não iniciam quando gravadas em modo DD"
            if (info.format == ImageFormat.UNKNOWN) notes += "Formato não reconhecido: será copiado byte a byte sem análise"
            if (!hybrid && !isWindows && info.format != ImageFormat.UNKNOWN) {
                notes += "Esta ISO não é híbrida (não tem tabela de partição própria): gravada em DD provavelmente NÃO inicia pelo pendrive"
            }
            if (hybrid) notes += "ISO híbrida: inicia em ${bootModes(info)} do mesmo jeito que pelo DVD"
            notes += "O pendrive fica somente leitura para o Windows/celular (formato ISO) até ser formatado de novo"
            methods += MethodOption(WriteMethod.DD, blockers.isEmpty(), blockers, notes, info.size)
        }

        // ---- Windows FAT32
        run {
            val blockers = mutableListOf<String>()
            val notes = mutableListOf<String>()
            if (!isWindows) blockers += "Não é um instalador do Windows (falta sources/boot.wim)"
            if (tree == null) blockers += "Não foi possível ler os arquivos da imagem"
            if (isWindows && efiLoaders.isEmpty()) blockers += "A ISO não tem carregador UEFI (efi/boot/boot*.efi)"
            if (conflicts.isNotEmpty()) blockers += "Nomes repetidos (só mudam maiúsculas): ${conflicts.take(3).joinToString()}"
            val other = large.filter { windowsImage == null || !it.path.equals(windowsImage.path, true) }
            if (other.isNotEmpty()) blockers += "Arquivos maiores que 4 GB: ${other.joinToString { it.path }}"
            if (windowsImage != null && !windowsImage.splittable) blockers += windowsImage.problem ?: "install.wim não pode ser dividido"
            if (windowsImage != null && windowsImage.size > WimSplitter.FAT32_MAX && windowsImage.splittable) {
                notes += "${windowsImage.path.substringAfterLast('/')} tem ${gb(windowsImage.size)} e será dividido em partes .swm (o instalador do Windows reconhece automaticamente)"
            }
            notes += "Inicia somente em modo UEFI (todos os PCs compatíveis com Windows 8 ou mais novo). Para BIOS/Legacy use o Ventoy"
            notes += "Secure Boot: funciona ligado (usa o carregador assinado da própria ISO)"
            methods += MethodOption(WriteMethod.WINDOWS_FAT32, blockers.isEmpty(), blockers, notes, total + FAT32_OVERHEAD)
        }

        // ---- Extrair FAT32 (genérico)
        run {
            val blockers = mutableListOf<String>()
            val notes = mutableListOf<String>()
            if (tree == null) blockers += "Não foi possível ler os arquivos da imagem"
            if (isWindows) blockers += "Para o Windows use o método \"${WriteMethod.WINDOWS_FAT32.title}\""
            if (tree != null && efiLoaders.isEmpty()) blockers += "A ISO não tem carregador UEFI (efi/boot/boot*.efi)"
            if (large.isNotEmpty() && !isWindows) blockers += "Arquivos maiores que 4 GB: ${large.joinToString { it.path }}"
            if (conflicts.isNotEmpty()) blockers += "Nomes repetidos (só mudam maiúsculas): ${conflicts.take(3).joinToString()}"
            notes += "Inicia somente em modo UEFI"
            val vol = info.volumeId
            if (vol != null && vol.length > 11) {
                notes += "O rótulo da ISO (\"$vol\") tem mais de 11 caracteres e será encurtado no FAT32: sistemas que procuram o disco pelo rótulo (ex.: CDLABEL=) podem não iniciar — nesse caso use DD"
            }
            notes += "Links simbólicos e permissões do Linux não existem em FAT32"
            methods += MethodOption(WriteMethod.EXTRACT_FAT32, blockers.isEmpty(), blockers, notes, total + FAT32_OVERHEAD)
        }

        val avail = methods.filter { it.available }.map { it.method }.toSet()
        val (rec, reason) = when {
            isWindows && WriteMethod.WINDOWS_FAT32 in avail -> WriteMethod.WINDOWS_FAT32 to "Instalador do Windows detectado"
            hybrid && WriteMethod.DD in avail -> WriteMethod.DD to "ISO híbrida: foi feita para ser gravada em DD"
            WriteMethod.EXTRACT_FAT32 in avail -> WriteMethod.EXTRACT_FAT32 to "ISO não híbrida com carregador UEFI"
            WriteMethod.DD in avail -> WriteMethod.DD to "Único método possível para esta imagem"
            else -> null to "Nenhum método disponível para esta imagem"
        }
        return IsoAnalysis(
            info, tree?.formatName, isWindows, windowsImage, efiLoaders, large, conflicts,
            files.size, total, methods, rec, PartitionScheme.MBR, reason,
        )
    }

    private fun collectConflicts(tree: ImageTree, path: String, out: MutableList<String>, depth: Int) {
        if (depth > 64 || out.size > 20) return
        val list = tree.listDir(path)
        list.groupBy { it.name.lowercase() }.values.filter { it.size > 1 }.forEach { out += path.trimEnd('/') + "/" + it.first().name }
        list.filter { it.isDirectory }.forEach { collectConflicts(tree, path.trimEnd('/') + "/" + it.name, out, depth + 1) }
    }

    private fun bootModes(i: ImageInfo) = listOfNotNull("BIOS".takeIf { i.bootBios }, "UEFI".takeIf { i.bootUefi || i.hybridGpt })
        .ifEmpty { listOf("modo BIOS/UEFI") }.joinToString(" e ")

    internal fun gb(b: Long) = String.format(java.util.Locale.ROOT, "%.2f GB", b / 1e9)
}
