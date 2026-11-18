package dev.bootforge.ventoy

import dev.bootforge.blockdev.BlockDevice
import dev.bootforge.blockdev.BlockTransfer
import dev.bootforge.blockdev.ProgressListener
import dev.bootforge.blockdev.readBlocks
import dev.bootforge.integrity.CancellationSignal
import dev.bootforge.integrity.Hashing
import dev.bootforge.partition.Gpt
import dev.bootforge.partition.GptHeader
import dev.bootforge.partition.LE
import dev.bootforge.partition.crc32
import java.io.IOException

data class UpdateReport(val fromVersion: String?, val toVersion: String, val detected: VentoyInfo, val log: List<String>)

/**
 * Atualização NÃO destrutiva do Ventoy (preserva a partição 1: ISOs e demais arquivos).
 *
 * Reproduz o ramo "update" do VentoyWorker.sh oficial (1.1.18):
 *  1. preserva o UUID do disco (bytes 0x180..0x18F) e a tabela de partições;
 *  2. grava os 440 bytes de código do `boot.img` (GPT: byte 92 = 0x22);
 *  3. grava o `core.img` (MBR: setor 1; GPT: setor 34 com byte 500 = 35),
 *     preservando os setores 2040..2047 ("rsvdata");
 *  4. MBR: se a partição 2 estiver ativa e a 1 não, inverte (como o original);
 *  5. regrava a partição VTOYEFI com a nova `ventoy.disk.img`;
 *  6. GPT: garante o atributo 0x8000000000000000 na VTOYEFI (equivale a `vtoycli gpt -f`).
 *
 * Nada da partição 1 é escrito.
 */
class VentoyUpdater(private val pkg: VentoyPackage) {

    fun update(
        dev: BlockDevice,
        confirmedSizeBytes: Long,
        observer: InstallObserver = object : InstallObserver {},
        cancel: CancellationSignal = CancellationSignal.NONE,
    ): UpdateReport {
        val log = mutableListOf<String>()
        fun info(msg: String) { log += msg; observer.onLog(msg) }
        if (confirmedSizeBytes != dev.sizeBytes) throw IllegalStateException("Confirmação não corresponde ao dispositivo")
        if (dev.isReadOnly) throw IOException("Dispositivo aberto somente para leitura")

        val before = VentoyDetector.inspect(dev, scanData = false)
        // Mesma regra do original: geometria do Ventoy é obrigatória; versão ilegível vira "desconhecida".
        val geometryOk = before.checks.filter { !it.description.startsWith("VTOYEFI") }.all { it.passed }
        if (!geometryOk || before.part2Start == null || before.style == null) {
            throw IOException("O dispositivo não contém uma instalação do Ventoy reconhecível")
        }
        if (before.secureBootSupport == false) {
            throw UnsupportedOperationException("Instalação atual sem Secure Boot: atualização desse modo ainda não implementada")
        }
        val part2Start = before.part2Start
        info("Atualizando Ventoy ${before.version ?: "desconhecido"} → ${pkg.version}")

        observer.onStep(InstallStep.CHECK_PACKAGE, 0, 4)
        val problems = pkg.verifyIntegrity()
        if (problems.isNotEmpty()) throw IOException("Pacote do Ventoy inválido: ${problems.joinToString()}")
        val bootImg = pkg.bootImg()
        val core = pkg.coreImg()
        cancel.throwIfCancelled()

        observer.onStep(InstallStep.WRITE_STAGE1, 1, 4)
        val sector0 = dev.readBlocks(0, 1)
        val rsvData = dev.readBlocks(2040, 8)
        bootImg.copyInto(sector0, 0, 0, 440) // só o código; preserva assinatura e tabela
        // O UUID (0x180) está dentro dos 440 bytes: restaura o original
        before.layout.mbr!!.raw.copyInto(sector0, 0x180, 0x180, 0x190)
        val stage1 = VentoyInstaller(pkg).buildStage1(core, before.style)
        if (before.style == PartitionStyle.GPT) {
            sector0[92] = 0x22
        } else {
            val a1 = sector0[446].toInt() and 0xFF
            val a2 = sector0[462].toInt() and 0xFF
            if (a1 == 0x00 && a2 == 0x80) { sector0[446] = 0x80.toByte(); sector0[462] = 0 }
        }
        dev.write(0, sector0)
        dev.write(stage1.first, stage1.second)
        dev.write(2040, rsvData)
        dev.flush()

        observer.onStep(InstallStep.WRITE_EFI, 2, 4)
        val efi = pkg.openDiskImg().use { input ->
            BlockTransfer.writeStream(dev, part2Start, input, VentoyPackage.EFI_PART_BYTES, InstallStep.WRITE_EFI.label,
                ProgressListener { observer.onProgress(it) }, cancel)
        }
        if (before.style == PartitionStyle.GPT) fixGptEfiAttribute(dev)?.let(::info)
        dev.flush()

        observer.onStep(InstallStep.VERIFY, 3, 4)
        val check = BlockTransfer.hashRegion(dev, part2Start, VentoyPackage.EFI_PART_BYTES, "Conferindo VTOYEFI",
            ProgressListener { observer.onProgress(it) }, cancel)
        if (!Hashing.matches(efi.sha256, check)) throw IOException("Verificação falhou: VTOYEFI lida difere da gravada")
        val after = VentoyDetector.inspect(dev, scanData = true)
        if (!after.isVentoy || after.version != pkg.version) throw IOException("Verificação falhou após a atualização")
        info("Atualização concluída e verificada: Ventoy ${after.version}")
        return UpdateReport(before.version, pkg.version, after, log)
    }

    /**
     * Ajusta o atributo da VTOYEFI nas tabelas GPT primária e de backup, se necessário.
     * Altera somente os 8 bytes do atributo (offset 48 da entrada 2) e recalcula os CRCs.
     */
    private fun fixGptEfiAttribute(dev: BlockDevice): String? {
        val primary = GptHeader.decode(dev.readBlocks(1, 1)) ?: return "Cabeçalho GPT não encontrado"
        val sectors = (primary.entryCount * primary.entrySize + 511) / 512
        val raw = dev.readBlocks(primary.entriesLba, sectors)
        val efi = Gpt.decodeEntries(raw, primary.entryCount, primary.entrySize)[1]
        if (!efi.name.startsWith("VTOY") || efi.attributes == VentoyInstaller.VENTOY_EFI_PART_ATTR) return null
        LE.put64(raw, primary.entrySize + 48, VentoyInstaller.VENTOY_EFI_PART_ATTR)
        val newPrimary = primary.copy(entriesCrc = crc32(raw, 0, primary.entryCount * primary.entrySize))
        val newBackup = newPrimary.toBackup()
        dev.write(newBackup.entriesLba, raw)
        dev.write(newBackup.currentLba, newBackup.encode())
        dev.write(primary.entriesLba, raw)
        dev.write(1, newPrimary.encode())
        return "Atributo GPT da VTOYEFI corrigido para 0x8000000000000000"
    }
}
