package dev.bootforge.ventoy

/** Estilo da tabela de partições usada na instalação. */
enum class PartitionStyle(val label: String, val description: String) {
    MBR(
        "MBR",
        "Compatível com praticamente todos os PCs (BIOS/Legacy e UEFI). Limite de 2 TiB por disco.",
    ),
    GPT(
        "GPT",
        "Padrão moderno para UEFI. Alguns PCs muito antigos só com BIOS podem não reconhecer o pendrive.",
    ),
}

/** Opções de instalação (equivalentes às do menu "Opções" do Ventoy2Disk). */
data class VentoyInstallOptions(
    val style: PartitionStyle = PartitionStyle.MBR,
    /** Espaço reservado no fim do disco, em MiB (opção "Configure reserved space"). */
    val reserveMiB: Long = 0,
    /** Alinhar as partições em 4 KB (opção "Align partitions with 4KB"). */
    val align4k: Boolean = true,
    /**
     * Suporte a Secure Boot. Nesta versão só `true` é suportado: a partição VTOYEFI é gravada
     * exatamente como vem no pacote (com shim/MokManager). Desativar exige editar o FAT16
     * (remover/renomear arquivos EFI), o que ainda não foi implementado.
     */
    val secureBoot: Boolean = true,
    /** Rótulo da partição de dados exFAT. */
    val label: String = "Ventoy",
)

/**
 * Posições das partições do Ventoy — cálculo idêntico às funções oficiais
 * `VentoyFillMBR` e `VentoyFillGpt` (Ventoy2Disk/Utility.c, versão 1.1.18).
 */
data class VentoyLayout(
    val style: PartitionStyle,
    val diskSectors: Long,
    val part1Start: Long,
    val part1Sectors: Long,
    val part2Start: Long,
    val part2Sectors: Long,
) {
    val part1End: Long get() = part1Start + part1Sectors - 1
    val part2End: Long get() = part2Start + part2Sectors - 1

    companion object {
        const val PART1_START = 2048L
        /** Limite do MBR: contadores de 32 bits de setores de 512 bytes (2 TiB). */
        const val MBR_MAX_SECTORS = 0xFFFFFFFFL

        fun compute(diskSectors: Long, opt: VentoyInstallOptions): VentoyLayout {
            require(opt.reserveMiB >= 0) { "Espaço reservado não pode ser negativo" }
            val efi = VentoyPackage.EFI_PART_SECTORS
            val part1Count: Long = when (opt.style) {
                PartitionStyle.MBR -> {
                    if (diskSectors > MBR_MAX_SECTORS) {
                        throw IllegalArgumentException("Disco maior que 2 TiB: use GPT (o MBR não endereça além de 2 TiB)")
                    }
                    var reserved = opt.reserveMiB * 2048
                    if (opt.align4k) reserved += diskSectors % 8
                    diskSectors - reserved - efi - PART1_START
                }
                PartitionStyle.GPT -> {
                    // 33 setores no fim: tabela GPT de backup (32) + cabeçalho de backup (1)
                    val reserved = 33 + opt.reserveMiB * 2048
                    var count = diskSectors - reserved - efi - PART1_START
                    if (opt.align4k) count -= count % 8
                    count
                }
            }
            // Pelo menos 32 MiB de área de dados, senão não faz sentido instalar.
            if (part1Count < 65536) {
                throw IllegalArgumentException("Espaço insuficiente no dispositivo para o Ventoy com essas opções")
            }
            return VentoyLayout(opt.style, diskSectors, PART1_START, part1Count, PART1_START + part1Count, efi)
        }
    }
}
