package dev.bootforge.ventoy

import dev.bootforge.blockdev.BlockDevice
import dev.bootforge.blockdev.BlockTransfer
import dev.bootforge.blockdev.MemoryBlockDevice
import dev.bootforge.blockdev.readBlocks
import dev.bootforge.blockdev.slice
import dev.bootforge.fs.exfat.ExFatVolume
import dev.bootforge.fs.fat.FatVolume
import dev.bootforge.integrity.Hashing
import dev.bootforge.partition.PartitionScheme
import dev.bootforge.ventoy.config.ConfigIssue
import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/** Item do relatório de verificação. */
data class CheckItem(val level: Level, val title: String, val detail: String = "") {
    enum class Level { OK, WARNING, ERROR, INFO }
}

/** Ferramentas de manutenção do Ventoy (equivalentes às opções do Ventoy2Disk e vtoycli). */
object VentoyMaintenance {

    /** Constante `g_check_mbr_data` do Ventoy (ventoy_cmd.c): bytes 0x00–0x2F e 0x190–0x19F do MBR. */
    private val CHECK_MBR_DATA: ByteArray = ByteArray(0x30).also { it[0] = 0xEB.toByte(); it[1] = 0x63; it[2] = 0x90.toByte() } +
        byteArrayOf(0x56, 0x54, 0x00, 0x47, 0x65, 0x00, 0x48, 0x44, 0x00, 0x52, 0x64, 0x00, 0x20, 0x45, 0x72, 0x0D)

    // ================================================================= Secure Boot

    /**
     * Desliga o suporte a Secure Boot na VTOYEFI — mesmo procedimento do `secureboot_proc`
     * (vtoycli/partresize.c) e do `VentoyProcSecureBoot` (Ventoy2Disk):
     *  - x64: conteúdo de grubx64_real.efi passa a ser o BOOTX64.EFI; remove grubx64.efi,
     *    grubx64_real.efi, MokManager.efi, mmx64.efi e ENROLL_THIS_KEY_IN_MOKMANAGER.cer;
     *  - ia32: grubia32_real.efi → BOOTIA32.EFI; remove grubia32.efi, grubia32_real.efi, mmia32.efi.
     * @param efiPart a partição VTOYEFI (janela de 65536 setores).
     */
    fun disableSecureBoot(efiPart: BlockDevice) {
        val fat = FatVolume.open(efiPart, writable = true)
        if (!fat.exists("/EFI/BOOT/grubx64_real.efi")) throw IOException("Esta instalação já está sem Secure Boot")
        val x64 = fat.readFile("/EFI/BOOT/grubx64_real.efi")
        fat.replaceFile("/EFI/BOOT/BOOTX64.EFI", x64)
        listOf("grubx64.efi", "grubx64_real.efi", "MokManager.efi", "mmx64.efi").forEach { n ->
            if (fat.exists("/EFI/BOOT/$n")) fat.deleteFile("/EFI/BOOT/$n")
        }
        if (fat.exists("/ENROLL_THIS_KEY_IN_MOKMANAGER.cer")) fat.deleteFile("/ENROLL_THIS_KEY_IN_MOKMANAGER.cer")
        if (fat.exists("/EFI/BOOT/grubia32_real.efi")) {
            val ia32 = fat.readFile("/EFI/BOOT/grubia32_real.efi")
            fat.replaceFile("/EFI/BOOT/BOOTIA32.EFI", ia32)
            listOf("grubia32.efi", "grubia32_real.efi", "mmia32.efi").forEach { n ->
                if (fat.exists("/EFI/BOOT/$n")) fat.deleteFile("/EFI/BOOT/$n")
            }
        }
        fat.flush()
    }

    /** SHA-256 que a VTOYEFI deve ter para a versão do pacote, com ou sem Secure Boot. */
    fun expectedEfiSha256(pkg: VentoyPackage, secureBoot: Boolean): String {
        val img = pkg.openDiskImg().use { it.readBytes() }
        if (!secureBoot) disableSecureBoot(MemoryBlockDevice(img))
        return Hashing.hash(img)
    }

    // ================================================================= verificação

    /**
     * Verifica a instalação SEM alterar nada. Compara com o pacote quando a versão instalada é a
     * mesma do pacote informado; caso contrário, faz só as checagens estruturais.
     */
    fun checkInstallation(dev: BlockDevice, pkg: VentoyPackage?): List<CheckItem> {
        val out = mutableListOf<CheckItem>()
        val info = VentoyDetector.inspect(dev, scanData = false)
        info.checks.forEach { out += CheckItem(if (it.passed) CheckItem.Level.OK else CheckItem.Level.ERROR, it.description) }
        if (!info.isVentoy || info.part2Start == null) {
            out += CheckItem(CheckItem.Level.ERROR, "Ventoy não reconhecido", "As demais verificações dependem de uma instalação reconhecível")
            return out
        }
        val s0 = dev.readBlocks(0, 1)
        val mbrOk = s0.copyOfRange(0, 0x30).contentEquals(CHECK_MBR_DATA.copyOfRange(0, 0x30)) &&
            s0.copyOfRange(0x190, 0x1A0).contentEquals(CHECK_MBR_DATA.copyOfRange(0x30, 0x40))
        out += CheckItem(if (mbrOk) CheckItem.Level.OK else CheckItem.Level.ERROR, "Assinatura de boot do MBR (mesma checagem do Ventoy ao iniciar)",
            if (mbrOk) "" else "O Ventoy mostrará \"MBR check failed\". Use Atualizar/Reinstalar componentes.")
        if (info.layout.scheme == PartitionScheme.GPT) {
            val ok = info.layout.gptEntriesCrcValid == true && info.layout.gptHeader?.headerCrcValid == true
            out += CheckItem(if (ok) CheckItem.Level.OK else CheckItem.Level.ERROR, "Tabela GPT íntegra (CRC32)")
            out += CheckItem(if (info.layout.backupGptValid == true) CheckItem.Level.OK else CheckItem.Level.WARNING, "GPT de backup no fim do disco")
        }
        info.warnings.forEach { out += CheckItem(CheckItem.Level.WARNING, it) }
        out += CheckItem(CheckItem.Level.INFO, "Versão instalada: ${info.version}", "Secure Boot: ${if (info.secureBootSupport == true) "ligado" else "desligado"}")

        val samePkg = pkg != null && runCatching { pkg.version }.getOrNull() == info.version
        if (samePkg) {
            pkg!!
            // core.img: setores 1..2039 (MBR) ou 34..2039 (GPT); 2040..2047 são preservados nas atualizações
            val (start, data) = VentoyInstaller(pkg).buildStage1(pkg.coreImg(), info.style!!)
            val count = (2040 - start).toInt()
            val coreOk = dev.readBlocks(start, count).contentEquals(data.copyOf(count * 512))
            out += CheckItem(if (coreOk) CheckItem.Level.OK else CheckItem.Level.ERROR, "Carregador GRUB (core.img) igual ao do pacote ${pkg.version}",
                if (coreOk) "" else "Use Atualizar/Reinstalar componentes para corrigir")
            val expected = expectedEfiSha256(pkg, info.secureBootSupport != false)
            val actual = BlockTransfer.hashRegion(dev, info.part2Start, VentoyPackage.EFI_PART_BYTES, "Conferindo VTOYEFI")
            val efiOk = Hashing.matches(expected, actual)
            val sbText = if (info.secureBootSupport != false) "pacote oficial" else "pacote oficial com Secure Boot desligado"
            out += CheckItem(if (efiOk) CheckItem.Level.OK else CheckItem.Level.ERROR, "Partição VTOYEFI idêntica à esperada ($sbText, SHA-256)",
                if (efiOk) actual else "Conteúdo diferente do pacote oficial (corrompido ou incompleto). Use Reinstalar componentes.")
        } else {
            out += CheckItem(CheckItem.Level.INFO, "Comparação byte a byte não realizada", "A versão instalada (${info.version}) difere do pacote disponível no app")
            runCatching {
                val fat = FatVolume.open(dev.slice(info.part2Start, VentoyPackage.EFI_PART_SECTORS))
                val missing = listOf("/grub/grub.cfg", "/EFI/BOOT/BOOTX64.EFI", "/ventoy/ventoy.cpio").filterNot(fat::exists)
                out += CheckItem(if (missing.isEmpty()) CheckItem.Level.OK else CheckItem.Level.ERROR, "Arquivos essenciais da VTOYEFI", missing.joinToString())
            }.onFailure { out += CheckItem(CheckItem.Level.ERROR, "VTOYEFI ilegível", it.message ?: "") }
        }

        runCatching {
            val vol = ExFatVolume.open(dev.slice(info.part1Start!!, info.part1Sectors!!))
            val r = vol.checkConsistency()
            out += CheckItem(if (r.errors.isEmpty()) CheckItem.Level.OK else CheckItem.Level.ERROR, "Sistema de arquivos exFAT da partição de dados",
                "${r.files} arquivos, ${r.directories} pastas" + if (r.errors.isNotEmpty()) "\n" + r.errors.take(10).joinToString("\n") else "")
            r.warnings.forEach { out += CheckItem(CheckItem.Level.WARNING, it) }
            val store = VentoyConfigStore(vol)
            if (store.exists()) {
                val (cfg, syntax) = store.load()
                val issues = syntax + (cfg?.let(store::validate) ?: emptyList())
                val errors = issues.count { it.severity == ConfigIssue.Severity.ERROR }
                out += CheckItem(if (errors == 0) CheckItem.Level.OK else CheckItem.Level.ERROR, "ventoy.json",
                    if (issues.isEmpty()) "sem problemas" else issues.take(10).joinToString("\n") { "${it.where}: ${it.message}" })
            } else out += CheckItem(CheckItem.Level.INFO, "ventoy.json", "não existe (o Ventoy usa as configurações padrão)")
        }.onFailure { out += CheckItem(CheckItem.Level.WARNING, "Partição de dados não verificada", it.message ?: "") }
        return out
    }

    // ================================================================= backup

    /** Salva a pasta /ventoy (ventoy.json, temas, scripts…) em um .zip. Retorna a quantidade de arquivos. */
    fun backup(vol: ExFatVolume, out: OutputStream, includeLargeFiles: Boolean = false): Int {
        if (!vol.exists("/ventoy")) throw IOException("O pendrive não tem a pasta /ventoy (nada para salvar)")
        var count = 0
        ZipOutputStream(out).use { zip ->
            vol.walk("/ventoy", maxDepth = 16) { path, e ->
                if (e.isDirectory) return@walk
                if (!includeLargeFiles && e.size > LARGE_FILE) return@walk
                zip.putNextEntry(ZipEntry(path.removePrefix("/")).apply { e.modifiedMillis?.let { time = it } })
                vol.openFile(path).use { it.copyTo(zip, 1 shl 16) }
                zip.closeEntry()
                count++
            }
        }
        return count
    }

    /** Lista o conteúdo de um backup (para prévia antes de restaurar). */
    fun listBackup(input: InputStream): List<Pair<String, Long>> {
        val out = mutableListOf<Pair<String, Long>>()
        ZipInputStream(input).use { zip ->
            while (true) {
                val e = zip.nextEntry ?: break
                if (!e.isDirectory) out += e.name to zip.readBytes().size.toLong()
            }
        }
        return out
    }

    /**
     * Restaura um backup gerado por [backup]. Só aceita arquivos dentro de "ventoy/".
     * @return arquivos restaurados.
     */
    fun restoreBackup(vol: ExFatVolume, input: InputStream, overwrite: Boolean): List<String> {
        val restored = mutableListOf<String>()
        ZipInputStream(input).use { zip ->
            while (true) {
                val e = zip.nextEntry ?: break
                if (e.isDirectory) continue
                val name = e.name.replace('\\', '/').trimStart('/')
                if (!name.startsWith("ventoy/") || name.split('/').any { it == ".." }) throw IOException("Backup inválido: $name")
                val path = "/$name"
                val data = zip.readBytes()
                if (vol.exists(path) && !overwrite) continue
                vol.mkdirs(path.substringBeforeLast('/'))
                vol.writeFile(path, ByteArrayInputStream(data), data.size.toLong(), overwrite = true)
                restored += path
            }
        }
        return restored
    }

    const val LARGE_FILE = 64L shl 20
}
