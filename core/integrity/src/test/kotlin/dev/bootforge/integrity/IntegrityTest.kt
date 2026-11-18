package dev.bootforge.integrity

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

class IntegrityTest {

    @Test
    fun sha256OfKnownString() {
        // Vetor de teste conhecido: SHA-256("abc")
        assertEquals(
            "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
            Hashing.hash("abc".toByteArray()),
        )
    }

    @Test
    fun parsesGnuAndBsdChecksumFiles() {
        val text = """
            -----BEGIN PGP SIGNED MESSAGE-----
            Hash: SHA256

            ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad *ubuntu-24.04-desktop-amd64.iso
            SHA256 (Fedora-Workstation-Live-41.iso) = BA7816BF8F01CFEA414140DE5DAE2223B00361A396177A9CB410FF61F20015AD
        """.trimIndent()
        val entries = ChecksumFile.parse(text)
        assertEquals(2, entries.size)
        assertNotNull(ChecksumFile.find(entries, "ubuntu-24.04-desktop-amd64.iso"))
        assertEquals(HashAlgorithm.SHA256, ChecksumFile.find(entries, "Fedora-Workstation-Live-41.iso")!!.algorithm)
    }
}
