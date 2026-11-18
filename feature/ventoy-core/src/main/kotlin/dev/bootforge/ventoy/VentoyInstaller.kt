package dev.bootforge.ventoy

import dev.bootforge.blockdev.BlockDevice
import dev.bootforge.blockdev.BlockTransfer
import dev.bootforge.blockdev.Progress
import dev.bootforge.blockdev.ProgressListener
import dev.bootforge.blockdev.readBlocks
import dev.bootforge.blockdev.slice
import dev.bootforge.blockdev.zeroBlocks
import dev.bootforge.fs.exfat.ExFatFormatter
import dev.bootforge.integrity.CancellationSignal
import dev.bootforge.integrity.Hashing
import dev.bootforge.partition.Chs
import dev.bootforge.partition.Gpt
import dev.bootforge.partition.GptEntry
import dev.bootforge.partition.GptHeader
import dev.bootforge.partition.Mbr
import dev.bootforge.partition.MbrEntry
import dev.bootforge.partition.crc32
import java.io.IOException
import java.security.SecureRandom
import java.util.UUID

/** Etapas da instalação, na ordem em que são executadas (para exibir na interface). */
enum class InstallStep(val label: String) {
    CHECK_PACKAGE("Verificando os arquivos originais do Ventoy"),
    WIPE("Limpando a tabela de partições antiga"),
    FORMAT_DATA("Formatando a partição de dados (exFAT)"),
    WRITE_EFI("Gravando a partição VTOYEFI (32 MiB)"),
    WRITE_STAGE1("Gravando o carregador de boot (GRUB core.img)"),
    WRITE_TABLE("Gravando a tabela de partições"),
    VERIFY("Verificando a instalação"),
}

/** Callback de acompanhamento: etapa atual + progresso em bytes da etapa. */
interface InstallObserver {
    fun onStep(step: InstallStep, index: Int, total: Int) {}
    fun onProgress(progress: Progress) {}
    fun onLog(message: String) {}
}

data class InstallReport(
    val layout: VentoyLayout,
    val version: String,
    val efiSha256: String,
    val detected: VentoyInfo,
    val log: List<String>,
)

/**
 * Instalação REAL do Ventoy em um [BlockDevice] — reimplementação em Kotlin do processo do
 * Ventoy2Disk 1.1.18 (`InstallVentoy2PhyDrive` em PhyDrive.c + `VentoyFillMBR/VentoyFillGpt`
 * em Utility.c), usando os arquivos originais do pacote.
 *
 * ATENÇÃO: APAGA TODO O CONTEÚDO DO DISPOSITIVO. A confirmação do usuário é responsabilidade
 * da interface — esta classe exige o parâmetro [confirmedSizeBytes] igual ao tamanho do disco
 * como trava adicional contra gravação no dispositivo errado.
 */
class VentoyInstaller(
    private val pkg: VentoyPackage,
    private val random: SecureRandom = SecureRandom(),
) {

    fun install(
        dev: BlockDevice,
        options: VentoyInstallOptions,
        confirmedSizeBytes: Long,
        observer: InstallObserver = object : InstallObserver {},
        cancel: CancellationSignal = CancellationSignal.NONE,
    ): InstallReport {
        val log = mutableListOf<String>()
        fun info(msg: String) { log += msg; observer.onLog(msg) }
        val steps = InstallStep.entries
        fun step(s: InstallStep) { observer.onStep(s, steps.indexOf(s), steps.size); info("» ${s.label}") }

        // ---------- Pré-condições ----------
        if (confirmedSizeBytes != dev.sizeBytes) {
            throw IllegalStateException("Confirmação não corresponde ao dispositivo (${dev.sizeBytes} bytes)")
        }
        if (dev.isReadOnly) throw IOException("Dispositivo aberto somente para leitura")
        if (dev.blockSize != 512) {
            // Mesmo limite do Ventoy oficial: "Currently Ventoy does not support 4K native device."
            throw IOException("Dispositivos com setor nativo de ${dev.blockSize} bytes não são suportados pelo Ventoy")
        }
        val layout = VentoyLayout.compute(dev.blockCount, options)
        info("Disco: ${dev.blockCount} setores; estilo ${layout.style}; partição 1 = ${layout.part1Start}..${layout.part1End}; partição 2 = ${layout.part2Start}..${layout.part2End}")

        step(InstallStep.CHECK_PACKAGE)
        val problems = pkg.verifyIntegrity()
        if (problems.isNotEmpty()) throw IOException("Pacote do Ventoy inválido: ${problems.joinToString()}")
        val bootImg = pkg.bootImg()
        val coreImg = pkg.coreImg()
        info("Pacote Ventoy ${pkg.version} íntegro (SHA-256 conferido)")
        cancel.throwIfCancelled()

        // ---------- 1. Limpeza (equivale ao "clean" do VDS/diskpart: 1 MiB no início e no fim) ----------
        step(InstallStep.WIPE)
        dev.zeroBlocks(0, 2048)
        dev.zeroBlocks(dev.blockCount - 2048, 2048)
        dev.flush()

        // ---------- 2. exFAT na partição 1 ----------
        step(InstallStep.FORMAT_DATA)
        val cluster = ExFatFormatter.ventoyClusterSize(dev.sizeBytes)
        val g = ExFatFormatter.format(
            dev.slice(layout.part1Start, layout.part1Sectors),
            ExFatFormatter.Options(options.label, cluster, partitionOffsetLba = layout.part1Start),
        )
        info("exFAT: cluster ${cluster / 1024} KiB, ${g.clusterCount} clusters")
        cancel.throwIfCancelled()

        // ---------- 3. Partição 2 (VTOYEFI) a partir de ventoy.disk.img ----------
        step(InstallStep.WRITE_EFI)
        val efi = pkg.openDiskImg().use { input ->
            BlockTransfer.writeStream(
                dev, layout.part2Start, input, VentoyPackage.EFI_PART_BYTES, InstallStep.WRITE_EFI.label,
                ProgressListener { observer.onProgress(it) }, cancel,
            )
        }
        info("VTOYEFI gravada: SHA-256 ${efi.sha256}")
        // confere a imagem gravada ANTES de qualquer ajuste
        val efiCheck = BlockTransfer.hashRegion(
            dev, layout.part2Start, VentoyPackage.EFI_PART_BYTES, "Conferindo VTOYEFI",
            ProgressListener { observer.onProgress(it) }, cancel,
        )
        if (!Hashing.matches(efi.sha256, efiCheck)) throw IOException("Verificação falhou: VTOYEFI lida difere da gravada")
        if (!options.secureBoot) {
            VentoyMaintenance.disableSecureBoot(dev.slice(layout.part2Start, layout.part2Sectors))
            info("Secure Boot desligado na VTOYEFI (mesmo procedimento do Ventoy2Disk)")
        }

        // ---------- 4. Stage 1 (core.img) ----------
        step(InstallStep.WRITE_STAGE1)
        val stage1 = buildStage1(coreImg, layout.style)
        dev.write(stage1.first, stage1.second)

        // ---------- 5. Tabela de partições (por último, como no original) ----------
        step(InstallStep.WRITE_TABLE)
        val diskUuid = ByteArray(16).also(random::nextBytes)
        val written = writePartitionTable(dev, layout, bootImg, diskUuid)
        dev.flush()

        // ---------- 6. Verificação ----------
        step(InstallStep.VERIFY)
        val head = dev.readBlocks(0, 2048)
        val expectedHead = ByteArray(2048 * 512).also { buf ->
            written.forEach { (lba, data) -> if (lba < 2048) data.copyInto(buf, (lba * 512).toInt()) }
            stage1.second.copyInto(buf, (stage1.first * 512).toInt())
        }
        if (!head.contentEquals(expectedHead)) throw IOException("Verificação falhou: o primeiro 1 MiB lido difere do gravado")
        val detected = VentoyDetector.inspect(dev, scanData = true)
        if (!detected.isVentoy || detected.version != pkg.version || detected.secureBootSupport != options.secureBoot) {
            throw IOException("Verificação falhou: o Ventoy não foi reconhecido após a instalação")
        }
        info("Instalação verificada: Ventoy ${detected.version} reconhecido; leitura confere com a gravação")
        return InstallReport(layout, pkg.version, efi.sha256, detected, log)
    }

    /**
     * Prepara o core.img na posição correta (função `WriteGrubStage1ToPhyDrive`):
     *  - MBR: a partir do setor 1, ocupando até o setor 2047;
     *  - GPT: a partir do setor 34 (após a tabela GPT), com o byte 500 = 35 (atualiza a
     *    "blocklist" do GRUB para o novo setor inicial) e truncado ao fim do setor 2047.
     */
    internal fun buildStage1(coreImg: ByteArray, style: PartitionStyle): Pair<Long, ByteArray> =
        when (style) {
            PartitionStyle.MBR -> 1L to coreImg.copyOf(VentoyPackage.CORE_MAX_BYTES)
            PartitionStyle.GPT -> {
                val b = coreImg.copyOf(1024 * 1024 - 34 * 512)
                b[500] = 35
                34L to b
            }
        }

    /** Grava MBR ou GPT completos. Retorna a lista (LBA, dados) do que foi gravado. */
    private fun writePartitionTable(dev: BlockDevice, l: VentoyLayout, bootImg: ByteArray, diskUuid: ByteArray): List<Pair<Long, ByteArray>> {
        val diskBytes = dev.sizeBytes
        val mbr = Mbr(bootImg.copyOf())
        diskUuid.copyInto(mbr.raw, 0x180)          // UUID do disco Ventoy
        diskUuid.copyInto(mbr.raw, 0x1B8, 0, 4)    // assinatura do disco = 4 primeiros bytes do mesmo GUID
        for (i in 0 until 4) mbr.setEntry(i, MbrEntry(false, 0, 0, 0, Chs(0, 0, 0), Chs(0, 0, 0)))
        mbr.setBootSignature()

        return when (l.style) {
            PartitionStyle.MBR -> {
                mbr.setEntry(0, mbrEntry(diskBytes, true, Mbr.TYPE_NTFS_EXFAT, l.part1Start, l.part1Sectors), ventoyChsBitfield = true)
                mbr.setEntry(1, mbrEntry(diskBytes, false, Mbr.TYPE_EFI_SYSTEM, l.part2Start, l.part2Sectors), ventoyChsBitfield = true)
                dev.write(0, mbr.raw)
                listOf(0L to mbr.raw)
            }
            PartitionStyle.GPT -> {
                val total = dev.blockCount
                mbr.setEntry(
                    0,
                    MbrEntry(false, Mbr.TYPE_GPT_PROTECTIVE, 1, minOf(total - 1, 0xFFFFFFFFL), Chs(0, 1, 0), Chs(254, 63, 1023)),
                    ventoyChsBitfield = true,
                )
                mbr.raw[92] = 0x22 // core.img começa no setor 34 (0x22)
                // Ambas as partições usam o tipo "Microsoft Basic Data" — decisão do Ventoy
                // ("to fix windows issue" em VentoyFillGpt); a VTOYEFI recebe o atributo 0x8000000000000000.
                val entries = Gpt.encodeEntries(
                    listOf(
                        GptEntry(GptEntry.TYPE_MICROSOFT_BASIC_DATA, randomGuid(), l.part1Start, l.part1End, 0, "Ventoy"),
                        GptEntry(GptEntry.TYPE_MICROSOFT_BASIC_DATA, randomGuid(), l.part2Start, l.part2End, VENTOY_EFI_PART_ATTR, "VTOYEFI"),
                    ),
                )
                val header = GptHeader(
                    currentLba = 1, backupLba = total - 1, firstUsableLba = 34, lastUsableLba = total - 34,
                    diskGuid = randomGuid(), entriesLba = 2, entriesCrc = crc32(entries),
                )
                val backup = header.toBackup()
                dev.write(total - 1, backup.encode())
                dev.write(total - 33, entries)
                val primary = ByteArray(34 * 512)
                mbr.raw.copyInto(primary, 0)
                header.encode().copyInto(primary, 512)
                entries.copyInto(primary, 1024)
                dev.write(0, primary)
                listOf(0L to primary)
            }
        }
    }

    private fun mbrEntry(diskBytes: Long, active: Boolean, type: Int, start: Long, count: Long) = MbrEntry(
        bootable = active, type = type, startLba = start, sectorCount = count,
        startChs = Mbr.ventoyChs(diskBytes, start), endChs = Mbr.ventoyChs(diskBytes, start + count - 1),
    )

    private fun randomGuid(): UUID {
        val b = ByteArray(16).also(random::nextBytes)
        b[6] = ((b[6].toInt() and 0x0F) or 0x40).toByte() // versão 4
        b[8] = ((b[8].toInt() and 0x3F) or 0x80).toByte() // variante RFC 4122
        var msb = 0L; var lsb = 0L
        for (i in 0 until 8) msb = (msb shl 8) or (b[i].toLong() and 0xFF)
        for (i in 8 until 16) lsb = (lsb shl 8) or (b[i].toLong() and 0xFF)
        return UUID(msb, lsb)
    }

    companion object {
        /** Atributo GPT da partição VTOYEFI (`VENTOY_EFI_PART_ATTR` em vtoycli.h). */
        const val VENTOY_EFI_PART_ATTR = Long.MIN_VALUE // 0x8000000000000000
    }
}
