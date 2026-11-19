package dev.bootforge.fs.exfat

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Lição do 2º teste real (19/11/2026): no APK de release o R8 renomeia pacotes, e um recurso
 * carregado por caminho RELATIVO à classe ("upcase.bin") não era encontrado — só no celular.
 * Este teste impede que isso volte: todo getResourceAsStream do projeto precisa usar caminho absoluto.
 */
class ResourcePathsTest {
    @Test
    fun allResourceLookupsUseAbsolutePaths() {
        var root = File("").absoluteFile
        while (!File(root, "settings.gradle.kts").exists()) root = root.parentFile
        val bad = root.walkTopDown()
            .filter { it.isFile && it.extension == "kt" && "/src/main/" in it.path && "/build/" !in it.path }
            .flatMap { f -> f.readLines().mapIndexedNotNull { i, l -> if (Regex("getResource(AsStream)?\\(\"[^/]").containsMatchIn(l)) "${f.relativeTo(root)}:${i + 1}" else null } }
            .toList()
        assertTrue(bad.isEmpty(), "Recursos com caminho relativo (quebram no APK): $bad")
    }

    @Test
    fun upcaseTableIsFound() = assertEquals(5836, ExFatFormatter.upcaseTable.size)
}
