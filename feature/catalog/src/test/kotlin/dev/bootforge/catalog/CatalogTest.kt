package dev.bootforge.catalog

import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Catálogo e verificação de assinaturas com respostas REAIS das fontes oficiais (salvas em
 * src/test/resources/real em 18/11/2026), sem precisar de internet.
 */
class CatalogTest {

    private fun real(name: String): ByteArray = javaClass.getResourceAsStream("/real/$name")!!.use { it.readBytes() }

    private val def = CatalogDefinition.bundled()
    private val keys = def.keys.mapValues { OpenPgp.loadKeys(it.value) }

    /** "Internet" de mentira: URL → resposta real salva. */
    private class FakeHttp(val pages: Map<String, ByteArray>) : Http {
        val requested = mutableListOf<String>()
        override fun bytes(url: String, maxBytes: Int): ByteArray { requested += url; return pages[url] ?: throw HttpException(404, url) }
        override fun contentLength(url: String): Long? = 123L
    }

    private fun resolver(pages: Map<String, ByteArray>) = Resolvers(FakeHttp(pages)) { keys.getValue(it) }
    private fun sys(id: String) = def.systems.first { it.id == id }

    @Test
    fun bundledCatalogIsValidAndKeysMatchPublishedFingerprints() {
        assertEquals(15, def.systems.size)
        assertTrue(def.systems.any { it.browserOnly && it.id == "windows11" })
        for ((id, k) in keys) assertEquals(def.keys.getValue(id).fingerprints, k.loadedFingerprints, id)
        // impressões digitais publicadas nos sites oficiais (conferidas em 18/11/2026)
        assertTrue("843938DF228D22F7B3742BC0D94AA3F0EFE21092" in def.keys.getValue("ubuntu").fingerprints)
        assertTrue("DF9B9C49EAA9298432589D76DA87E80D6294BE9B" in def.keys.getValue("debian").fingerprints)
        assertTrue("27DEB15644C6B3CF3BD7D291300F846BA25BAE09" in def.keys.getValue("linuxmint").fingerprints)
    }

    @Test
    fun invalidCatalogsAreRejected() {
        val ok = String(CatalogDefinition::class.java.getResourceAsStream("/dev/bootforge/catalog/catalog.json")!!.readBytes())
        assertFailsWith<IOException> { CatalogDefinition.parse("{ isto não é json") }
        assertFailsWith<IOException> { CatalogDefinition.parse(ok.replace("\"schema\": 1", "\"schema\": 99")) }
        assertFailsWith<IOException> { CatalogDefinition.parse(ok.replace("\"type\": \"fedora-releases\"", "\"type\": \"site-desconhecido\"")) }
        assertFailsWith<IOException> { CatalogDefinition.parse(ok.replace("\"keys\": \"debian\"", "\"keys\": \"inexistente\"")) }
    }

    @Test
    fun realSignaturesVerifyAndTamperingIsDetected() {
        val cases = listOf(
            Triple("ubuntu-26.04.1-SHA256SUMS", "ubuntu-26.04.1-SHA256SUMS.gpg", "ubuntu"),
            Triple("debian-13.7.0-SHA512SUMS", "debian-13.7.0-SHA512SUMS.sign", "debian"),
            Triple("mint-22.3-sha256sum.txt", "mint-22.3-sha256sum.txt.gpg", "linuxmint"),
        )
        for ((data, sig, group) in cases) {
            val r = OpenPgp.verifyDetached(real(data), real(sig), keys.getValue(group))
            assertTrue(r.valid, "$data: ${r.message}")
            // um único caractere trocado no arquivo de checksums invalida a assinatura
            val tampered = real(data).also { it[10] = (if (it[10] == 'a'.code.toByte()) 'b' else 'a').code.toByte() }
            assertFalse(OpenPgp.verifyDetached(tampered, real(sig), keys.getValue(group)).valid, "$data adulterado")
        }
        // assinatura do Ubuntu NÃO é aceita com as chaves do Debian
        val cross = OpenPgp.verifyDetached(real("ubuntu-26.04.1-SHA256SUMS"), real("ubuntu-26.04.1-SHA256SUMS.gpg"), keys.getValue("debian"))
        assertFalse(cross.valid); assertTrue(cross.message.contains("desconhecida"), cross.message)
    }

    /** O mesmo resultado do gpgv (implementação independente), quando instalado. */
    @Test
    fun agreesWithGpgv() {
        val gpgv = listOf("/usr/bin/gpgv").firstOrNull { File(it).exists() } ?: return
        val gpg = listOf("/usr/bin/gpg").firstOrNull { File(it).exists() } ?: return
        val work = File.createTempFile("bf-gpg", "").apply { delete(); mkdirs() }
        try {
            val ring = File(work, "keys.gpg")
            val armored = def.keys.values.flatMap { it.files }.joinToString("") { String(javaClass.getResourceAsStream("/dev/bootforge/catalog/keys/$it")!!.readBytes()) }
            val p = ProcessBuilder(gpg, "--homedir", work.path, "--dearmor").redirectOutput(ring).start()
            p.outputStream.use { it.write(armored.toByteArray()) }; p.waitFor(60, TimeUnit.SECONDS)
            fun gpgvOk(data: ByteArray, sig: ByteArray): Boolean {
                val d = File(work, "d").apply { writeBytes(data) }; val s = File(work, "s").apply { writeBytes(sig) }
                val pr = ProcessBuilder(gpgv, "--keyring", ring.path, s.path, d.path).redirectErrorStream(true).start()
                pr.inputStream.readBytes(); pr.waitFor(60, TimeUnit.SECONDS)
                return pr.exitValue() == 0
            }
            val data = real("debian-13.7.0-SHA512SUMS"); val sig = real("debian-13.7.0-SHA512SUMS.sign")
            assertEquals(gpgvOk(data, sig), OpenPgp.verifyDetached(data, sig, keys.getValue("debian")).valid)
            val bad = data.copyOf().also { it[0] = (it[0] + 1).toByte() }
            assertEquals(gpgvOk(bad, sig), OpenPgp.verifyDetached(bad, sig, keys.getValue("debian")).valid)
            assertTrue(gpgvOk(data, sig))
        } finally {
            work.deleteRecursively()
        }
    }

    @Test
    fun ubuntuIsResolvedFromMetaReleaseWithSignedChecksum() {
        val base = "https://releases.ubuntu.com/26.04.1/"
        val r = resolver(mapOf(
            "https://changelogs.ubuntu.com/meta-release-lts" to real("ubuntu-meta-release-lts.txt"),
            base + "SHA256SUMS" to real("ubuntu-26.04.1-SHA256SUMS"),
            base + "SHA256SUMS.gpg" to real("ubuntu-26.04.1-SHA256SUMS.gpg"),
        )).resolve(sys("ubuntu-desktop"))
        assertEquals("26.04.1", r.version)
        assertEquals(base + "ubuntu-26.04.1-desktop-amd64.iso", r.downloadUrl)
        assertEquals(Verification.SIGNED, r.verification)
        assertEquals("843938DF228D22F7B3742BC0D94AA3F0EFE21092", r.signedBy)
        assertEquals("601e30fbf5d97759367c632e2c33630665039b7e2158fd068403da3ccf1bda1f", r.checksum!!.hex)
    }

    @Test
    fun tamperedChecksumFileBlocksTheDownload() {
        val base = "https://releases.ubuntu.com/26.04.1/"
        val evil = String(real("ubuntu-26.04.1-SHA256SUMS")).replace("601e30fb", "deadbeef").toByteArray()
        val e = assertFailsWith<IOException> {
            resolver(mapOf(
                "https://changelogs.ubuntu.com/meta-release-lts" to real("ubuntu-meta-release-lts.txt"),
                base + "SHA256SUMS" to evil, base + "SHA256SUMS.gpg" to real("ubuntu-26.04.1-SHA256SUMS.gpg"),
            )).resolve(sys("ubuntu-desktop"))
        }
        assertTrue(e.message!!.contains("NÃO CONFERE"), e.message)
    }

    @Test
    fun debianAndMintAreResolvedFromOfficialIndexes() {
        val idx = "https://cdimage.debian.org/debian-cd/current/amd64/iso-cd/"
        val deb = resolver(mapOf(idx to real("debian-iso-cd-index.html"), idx + "SHA512SUMS" to real("debian-13.7.0-SHA512SUMS"), idx + "SHA512SUMS.sign" to real("debian-13.7.0-SHA512SUMS.sign")))
            .resolve(sys("debian-netinst"))
        assertEquals("13.7.0", deb.version); assertEquals("SHA-512", deb.checksum!!.algorithm); assertEquals(Verification.SIGNED, deb.verification)
        assertEquals(idx + "debian-13.7.0-amd64-netinst.iso", deb.downloadUrl)

        val mi = "https://mirrors.edge.kernel.org/linuxmint/stable/"
        val mint = resolver(mapOf(mi to real("mint-stable-index.html"), mi + "22.3/sha256sum.txt" to real("mint-22.3-sha256sum.txt"), mi + "22.3/sha256sum.txt.gpg" to real("mint-22.3-sha256sum.txt.gpg")))
            .resolve(sys("linuxmint-cinnamon"))
        assertEquals("22.3", mint.version) // maior versão do índice (não "22.10" < "22.3" por texto)
        assertEquals("a081ab202cfda17f6924128dbd2de8b63518ac0531bcfe3f1a1b88097c459bd4", mint.checksum!!.hex)
        assertEquals("27DEB15644C6B3CF3BD7D291300F846BA25BAE09", mint.signedBy)
    }

    @Test
    fun jsonYamlAndHtmlSourcesAreParsed() {
        val fed = resolver(mapOf("https://fedoraproject.org/releases.json" to real("fedora-releases.json"))).resolve(sys("fedora-workstation"))
        assertTrue(fed.version!!.all { it.isDigit() }, "não deve escolher Beta: ${fed.version}")
        assertNotNull(fed.checksum); assertTrue(fed.sizeBytes!! > 1_000_000_000)

        val pop = resolver(mapOf("https://api.pop-os.org/builds/24.04/intel" to real("popos-24.04-intel.json"))).resolve(sys("popos-intel"))
        assertEquals("a0ef3842ab710db4f4407cf3499560b59dddbbcd59bee17beab7b0e99dc22b4c", pop.checksum!!.hex)

        val alp = resolver(mapOf("https://dl-cdn.alpinelinux.org/alpine/latest-stable/releases/x86_64/latest-releases.yaml" to real("alpine-latest-releases.yaml"))).resolve(sys("alpine-standard"))
        assertTrue(alp.downloadUrl!!.startsWith("https://dl-cdn.alpinelinux.org/alpine/v3.") && alp.downloadUrl!!.endsWith(".iso"), alp.downloadUrl)
        assertEquals("SHA-512", alp.checksum!!.algorithm)

        val zor = resolver(mapOf(
            "https://zorin.com/os/download/18/core/" to real("zorin-download-core.html"),
            "https://help.zorin.com/docs/getting-started/check-the-integrity-of-your-copy-of-zorin-os/" to real("zorin-checksums.html"),
        )).resolve(sys("zorin-core"))
        assertEquals("18.1", zor.version)
        assertEquals("44649b97bd307fc4c8529205d098ebbf98575a1e1ba2ee7d7005b697af1721d5", zor.checksum!!.hex)

        val win = resolver(emptyMap()).resolve(sys("windows11"))
        assertEquals(Verification.NONE, win.verification); assertEquals(null, win.downloadUrl)
    }

    @Test
    fun githubReleaseUsesPublishedSha256() {
        val api = "https://api.github.com/repos/ventoy/Ventoy/releases/latest"
        val json = """{"tag_name":"v1.1.18","assets":[
            {"name":"ventoy-1.1.18-linux.tar.gz","size":17000000,"browser_download_url":"https://github.com/ventoy/Ventoy/releases/download/v1.1.18/ventoy-1.1.18-linux.tar.gz"},
            {"name":"sha256.txt","size":500,"browser_download_url":"https://github.com/ventoy/Ventoy/releases/download/v1.1.18/sha256.txt"}]}"""
        val sums = "a".repeat(64) + "  ventoy-1.1.18-linux.tar.gz\n" + "b".repeat(64) + "  ventoy-1.1.18-windows.zip\n"
        val r = resolver(mapOf(api to json.toByteArray(), "https://github.com/ventoy/Ventoy/releases/download/v1.1.18/sha256.txt" to sums.toByteArray())).resolve(sys("ventoy"))
        assertEquals("1.1.18", r.version); assertEquals("a".repeat(64), r.checksum!!.hex)
    }

    @Test
    fun versionsCompareNumerically() {
        assertTrue(Resolvers.compareVersions("22.10", "22.9") > 0)
        assertTrue(Resolvers.compareVersions("13.10.0", "13.7.0") > 0)
        assertEquals(0, Resolvers.compareVersions("24.04", "24.04"))
        assertEquals("26.04.1", Resolvers.ubuntuVersion(String(real("ubuntu-meta-release-lts.txt"))))
        assertEquals(ChecksumFile.find("SHA256 (Fedora.iso) = " + "C".repeat(64), "Fedora.iso")!!.hex, "c".repeat(64))
    }
}
