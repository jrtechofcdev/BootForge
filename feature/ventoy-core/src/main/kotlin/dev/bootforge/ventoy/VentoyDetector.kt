package dev.bootforge.ventoy

import dev.bootforge.blockdev.BlockDevice
import dev.bootforge.blockdev.slice
import dev.bootforge.fs.exfat.ExFatVolume
import dev.bootforge.fs.fat.FatVolume
import dev.bootforge.partition.DiskLayout
import dev.bootforge.partition.DiskLayoutReader
import dev.bootforge.partition.FsType
import dev.bootforge.partition.PartitionScheme
import dev.bootforge.integrity.toHex

/** Estatísticas da partição de dados (onde ficam as ISOs). */
data class VentoyDataStats(
    val fsType: FsType,
    val label: String?,
    val totalBytes: Long?,
    val freeBytes: Long?,
    /** Arquivos que o Ventoy consegue inicializar (.iso, .wim, .img, .vhd, .vhdx, .efi, .vtoy). */
    val bootableImageCount: Int?,
    val isoCount: Int?,
    val fileCount: Int?,
    val directoryCount: Int?,
    /** Motivo quando não foi possível ler o conteúdo (ex.: NTFS ainda não suportado). */
    val note: String? = null,
)

/** Resultado da inspeção de um dispositivo. */
data class VentoyInfo(
    val isVentoy: Boolean,
    /** Evidências verificadas (o que bateu e o que não bateu). */
    val checks: List<Check>,
    val layout: DiskLayout,
    val version: String? = null,
    val secureBootSupport: Boolean? = null,
    val style: PartitionStyle? = null,
    val part1Start: Long? = null,
    val part1Sectors: Long? = null,
    val part2Start: Long? = null,
    /** UUID do disco Ventoy (16 bytes em 0x180 do MBR). */
    val diskUuid: String? = null,
    val data: VentoyDataStats? = null,
    val warnings: List<String> = emptyList(),
) {
    data class Check(val description: String, val passed: Boolean)
}

/**
 * Identifica uma instalação do Ventoy usando as MESMAS regras do Ventoy2Disk
 * (`IsVentoyPhyDrive` em Ventoy2Disk.c e `is_disk_contains_ventoy` em ventoy_lib.sh):
 *
 *  1. Partição 1 começa no setor 2048;
 *  2. Partição 2 começa imediatamente após a partição 1 e tem exatamente 65536 setores (32 MiB);
 *  3. Em GPT, a partição 2 se chama "VTOYEFI";
 *  4. A partição 2 contém um FAT com `/grub/grub.cfg` definindo `VENTOY_VERSION`.
 *
 * Uma simples pasta "ventoy" NÃO é considerada prova de instalação.
 */
object VentoyDetector {

    /** Extensões que o Ventoy lista no menu de boot. */
    val BOOTABLE_EXTENSIONS = setOf("iso", "wim", "img", "vhd", "vhdx", "efi", "vtoy")

    fun inspect(dev: BlockDevice, scanData: Boolean = true): VentoyInfo {
        val layout = DiskLayoutReader.read(dev, probeFilesystems = true)
        val checks = mutableListOf<VentoyInfo.Check>()
        val warnings = mutableListOf<String>()

        if (dev.blockSize != 512) {
            checks += VentoyInfo.Check("Setor lógico de 512 bytes", false)
            return VentoyInfo(false, checks, layout)
        }
        val p1 = layout.partitions.firstOrNull { it.number == 1 }
        val p2 = layout.partitions.firstOrNull { it.number == 2 }
        val style = when (layout.scheme) {
            PartitionScheme.MBR -> PartitionStyle.MBR
            PartitionScheme.GPT -> PartitionStyle.GPT
            else -> null
        }
        checks += VentoyInfo.Check("Tabela de partições MBR ou GPT", style != null)
        checks += VentoyInfo.Check("Partição 1 inicia no setor 2048", p1?.startLba == 2048L)
        checks += VentoyInfo.Check(
            "Partição 2 logo após a partição 1",
            p1 != null && p2 != null && p2.startLba == p1.startLba + p1.sectorCount,
        )
        checks += VentoyInfo.Check("Partição 2 com 32 MiB (65536 setores)", p2?.sectorCount == VentoyPackage.EFI_PART_SECTORS)
        if (style == PartitionStyle.GPT) {
            checks += VentoyInfo.Check("Partição 2 com nome GPT \"VTOYEFI\"", p2?.name == "VTOYEFI")
        }
        if (checks.any { !it.passed } || p1 == null || p2 == null) {
            return VentoyInfo(false, checks, layout, style = style)
        }

        // Lê a versão no FAT da partição VTOYEFI.
        var version: String? = null
        var secureBoot: Boolean? = null
        runCatching {
            val fat = FatVolume.open(dev.slice(p2.startLba, p2.sectorCount))
            val cfg = String(fat.readFile("/grub/grub.cfg"), Charsets.UTF_8)
            version = parseVersion(cfg)
            secureBoot = fat.exists("/EFI/BOOT/grubx64_real.efi")
        }.onFailure { warnings += "Não foi possível ler a partição VTOYEFI: ${it.message}" }
        checks += VentoyInfo.Check("VTOYEFI contém /grub/grub.cfg com VENTOY_VERSION", version != null)

        if (style == PartitionStyle.GPT && p2.gptAttributes != VentoyInstaller.VENTOY_EFI_PART_ATTR) {
            warnings += "Atributo GPT da VTOYEFI (0x%016x) difere do padrão do Ventoy".format(p2.gptAttributes ?: 0)
        }
        if (style == PartitionStyle.MBR && !p1.bootable) {
            warnings += "Partição 1 não está marcada como ativa (o Ventoy marca como ativa)"
        }
        warnings += layout.warnings

        val mbrRaw = layout.mbr?.raw
        val uuid = mbrRaw?.copyOfRange(0x180, 0x190)?.toHex()

        val data = if (scanData) scanDataPartition(dev, p1.startLba, p1.sectorCount, p1.fs?.type) else null

        return VentoyInfo(
            isVentoy = version != null,
            checks = checks,
            layout = layout,
            version = version,
            secureBootSupport = secureBoot,
            style = style,
            part1Start = p1.startLba,
            part1Sectors = p1.sectorCount,
            part2Start = p2.startLba,
            diskUuid = uuid,
            data = data,
            warnings = warnings,
        )
    }

    /** Mesma lógica de `ParseVentoyVersionFromString` (Utility.c). */
    fun parseVersion(cfg: String): String? {
        val idx = cfg.indexOf("VENTOY_VERSION=")
        if (idx < 0) return null
        var rest = cfg.substring(idx + "VENTOY_VERSION=".length)
        if (rest.startsWith("\"")) rest = rest.substring(1)
        return rest.takeWhile { it != '"' && it != '\r' && it != '\n' }.ifEmpty { null }
    }

    private fun scanDataPartition(dev: BlockDevice, start: Long, count: Long, fs: FsType?): VentoyDataStats {
        if (fs != FsType.EXFAT) {
            return VentoyDataStats(
                fs ?: FsType.UNKNOWN, null, count * dev.blockSize, null, null, null, null, null,
                note = "Leitura do conteúdo disponível apenas para exFAT nesta versão",
            )
        }
        return runCatching {
            val vol = ExFatVolume.open(dev.slice(start, count))
            var images = 0; var isos = 0; var files = 0; var dirs = 0
            vol.walk { _, e ->
                if (e.isDirectory) { dirs++; return@walk }
                files++
                val ext = e.name.substringAfterLast('.', "").lowercase()
                if (ext in BOOTABLE_EXTENSIONS) images++
                if (ext == "iso") isos++
            }
            VentoyDataStats(FsType.EXFAT, vol.label, vol.totalBytes, vol.freeBytes(), images, isos, files, dirs)
        }.getOrElse {
            VentoyDataStats(FsType.EXFAT, null, null, null, null, null, null, null, note = "Falha ao ler o exFAT: ${it.message}")
        }
    }
}
