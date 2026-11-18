package dev.bootforge.ventoy.config

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class VentoyConfigTest {

    private val official = File(System.getProperty("bootforge.ventoyDir"), "plugin/ventoy/ventoy.json").readText()

    @Test
    fun officialExampleIsValid() {
        val (cfg, issues) = VentoyConfig.parse(official)
        assertNotNull(cfg)
        assertTrue(issues.isEmpty())
        val problems = VentoyConfigValidator.validate(cfg)
        assertTrue(problems.none { it.severity == ConfigIssue.Severity.ERROR }, problems.toString())
        assertEquals("1", cfg.control()["VTOY_DEFAULT_MENU_MODE"])
    }

    @Test
    fun missingFilesAreReported() {
        val (cfg, _) = VentoyConfig.parse(official)
        val issues = VentoyConfigValidator.validate(cfg!!) { it == "/ventoy/theme/theme.txt" }
        // o exemplo oficial referencia scripts que não existem num pendrive novo
        assertTrue(issues.any { it.message.contains("/ventoy/script/windows_unattended.cfg") })
        assertTrue(issues.none { it.message.contains("theme.txt") })
    }

    @Test
    fun syntaxErrorHasLineAndColumn() {
        val (cfg, issues) = VentoyConfig.parse("{\n  \"control\": [\n    { \"VTOY_MENU_TIMEOUT\": \"5\" },\n  ]\n}")
        assertNull(cfg)
        assertEquals(ConfigIssue.Severity.ERROR, issues.single().severity)
        assertEquals(4, issues.single().line)
    }

    @Test
    fun semanticErrorsAndWarnings() {
        val text = """
            {
              "control": [ { "VTOY_DEFAULT_MENU_MODE": "2" }, { "VTOY_INVENTADO": "1" }, { "VTOY_MENU_TIMEOUT": "x" } ],
              "theme_uefi": { "file": "/ventoy/t/theme.txt", "display_mode": "GUI", "default_file": "1" },
              "menu_alias": [ { "alias": "sem alvo" }, { "image": "relativo.iso", "alias": "x" } ],
              "password": { "bootpwd": "123", "isopwd": "md5#0123456789abcdef0123456789abcdef" },
              "plugin_que_nao_existe": {}
            }
        """.trimIndent()
        val (cfg, _) = VentoyConfig.parse(text)
        val issues = VentoyConfigValidator.validate(cfg!!)
        fun has(where: String, sev: ConfigIssue.Severity) = issues.any { it.where.startsWith(where) && it.severity == sev }
        assertTrue(has("control.VTOY_DEFAULT_MENU_MODE", ConfigIssue.Severity.ERROR))
        assertTrue(has("control.VTOY_INVENTADO", ConfigIssue.Severity.WARNING))
        assertTrue(has("control.VTOY_MENU_TIMEOUT", ConfigIssue.Severity.ERROR))
        assertTrue(has("theme_uefi.default_file", ConfigIssue.Severity.ERROR)) // número entre aspas
        assertTrue(has("menu_alias[1]", ConfigIssue.Severity.ERROR))
        assertTrue(has("menu_alias[2].image", ConfigIssue.Severity.ERROR))
        assertTrue(has("password.bootpwd", ConfigIssue.Severity.ERROR))
        assertTrue(issues.none { it.where == "password.isopwd" })
        assertTrue(has("plugin_que_nao_existe", ConfigIssue.Severity.WARNING))
    }

    @Test
    fun editingKeepsOrderAndRoundTrips() {
        val (cfg, _) = VentoyConfig.parse(official)
        val edited = cfg!!.withControl("VTOY_MENU_TIMEOUT", "10").withControl("VTOY_FILT_DOT_UNDERSCORE_FILE", null)
        val text = edited.toJsonText()
        val (again, issues) = VentoyConfig.parse(text)
        assertTrue(issues.isEmpty())
        assertEquals(listOf("control", "theme", "menu_class", "menu_alias", "auto_install"), again!!.root.keys.toList())
        assertEquals(mapOf("VTOY_DEFAULT_MENU_MODE" to "1", "VTOY_MENU_TIMEOUT" to "10"), again.control())
        assertTrue(text.contains("    \"control\""), "indentação de 4 espaços como no exemplo oficial")
    }

    @Test
    fun archSuffixIsRecognized() {
        assertEquals("theme" to "uefi", VentoySchema.parseKey("theme_uefi")!!.let { it.first.key to it.second })
        assertNull(VentoySchema.parseKey("theme_xyz"))
        assertEquals(15, VentoySchema.PLUGINS.size)
        assertEquals(25, VentoySchema.CONTROL_OPTIONS.size)
    }
}
