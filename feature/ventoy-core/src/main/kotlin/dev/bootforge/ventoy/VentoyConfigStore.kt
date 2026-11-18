package dev.bootforge.ventoy

import dev.bootforge.fs.exfat.ExFatVolume
import dev.bootforge.ventoy.config.ConfigIssue
import dev.bootforge.ventoy.config.VentoyConfig
import dev.bootforge.ventoy.config.VentoyConfigValidator
import java.io.ByteArrayInputStream
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Lê e grava o `/ventoy/ventoy.json` da partição de dados, com backup automático.
 *
 * Antes de cada gravação, a versão atual é copiada para
 * `/ventoy/ventoy.json.bak-AAAAMMDD-HHMMSS` (mantém as [MAX_BACKUPS] mais recentes).
 * Nunca grava um arquivo com erro de SINTAXE (o Ventoy ignoraria todo o arquivo).
 */
class VentoyConfigStore(private val vol: ExFatVolume) {

    data class Backup(val name: String, val path: String, val size: Long, val modifiedMillis: Long?)

    fun exists(): Boolean = vol.exists(PATH)

    fun readText(): String? = vol.resolve(PATH)?.let { vol.openFile(PATH).use { String(it.readBytes(), Charsets.UTF_8) } }

    /** Carrega a configuração. Sem arquivo → configuração vazia (o Ventoy usa os padrões). */
    fun load(): Pair<VentoyConfig?, List<ConfigIssue>> {
        val text = readText() ?: return VentoyConfig.empty() to emptyList()
        return VentoyConfig.parse(text)
    }

    /** Validação completa, conferindo se os arquivos referenciados existem no pendrive. */
    fun validate(cfg: VentoyConfig): List<ConfigIssue> = VentoyConfigValidator.validate(cfg) { vol.exists(it) }

    fun save(cfg: VentoyConfig): String? = saveText(cfg.toJsonText())

    /**
     * Grava o texto (editor avançado). Recusa se houver erro de sintaxe.
     * @return nome do backup criado (null se não havia arquivo anterior).
     */
    fun saveText(text: String): String? {
        val (parsed, issues) = VentoyConfig.parse(text)
        if (parsed == null) throw IOException("Não salvo: ${issues.firstOrNull()?.message ?: "JSON inválido"}")
        val clean = text.removePrefix("﻿").toByteArray(Charsets.UTF_8)
        vol.mkdirs("/ventoy")
        val backup = if (exists()) makeBackup() else null
        vol.writeFile(PATH, ByteArrayInputStream(clean), clean.size.toLong(), overwrite = true)
        pruneBackups()
        return backup
    }

    /** Remove o ventoy.json (o Ventoy volta a todos os padrões). Faz backup antes. */
    fun delete(): String? {
        if (!exists()) return null
        val b = makeBackup()
        vol.delete(PATH)
        return b
    }

    fun backups(): List<Backup> = runCatching { vol.list("/ventoy") }.getOrDefault(emptyList())
        .filter { !it.isDirectory && it.name.startsWith("ventoy.json.bak-") }
        .map { Backup(it.name, "/ventoy/${it.name}", it.size, it.modifiedMillis) }
        .sortedByDescending { it.name }

    fun readBackup(name: String): String = vol.openFile("/ventoy/$name").use { String(it.readBytes(), Charsets.UTF_8) }

    /** Restaura um backup (a versão atual também vira backup antes). */
    fun restore(name: String): String? = saveText(readBackup(name))

    fun deleteBackup(name: String) = vol.delete("/ventoy/$name")

    private fun makeBackup(): String {
        var name = "ventoy.json.bak-" + SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
        var n = 1
        while (vol.exists("/ventoy/$name")) name = name.substringBefore('~') + "~" + n++
        vol.copy(PATH, "/ventoy", name)
        return name
    }

    private fun pruneBackups() {
        backups().drop(MAX_BACKUPS).forEach { runCatching { vol.delete(it.path) } }
    }

    companion object {
        const val PATH = "/ventoy/ventoy.json"
        const val MAX_BACKUPS = 20
    }
}
