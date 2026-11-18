package dev.bootforge.catalog

import org.bouncycastle.jce.provider.BouncyCastleProvider
import org.bouncycastle.openpgp.PGPCompressedData
import org.bouncycastle.openpgp.PGPPublicKeyRing
import org.bouncycastle.openpgp.PGPPublicKeyRingCollection
import org.bouncycastle.openpgp.PGPSignatureList
import org.bouncycastle.openpgp.PGPUtil
import org.bouncycastle.openpgp.jcajce.JcaPGPObjectFactory
import org.bouncycastle.openpgp.operator.jcajce.JcaKeyFingerprintCalculator
import org.bouncycastle.openpgp.operator.jcajce.JcaPGPContentVerifierBuilderProvider
import java.io.IOException
import java.util.Date

/** Resultado da verificação de uma assinatura OpenPGP. */
data class SignatureResult(val valid: Boolean, val fingerprint: String?, val userId: String?, val created: Date?, val message: String)

/**
 * Verificação de assinaturas OpenPGP "destacadas" (ex.: SHA256SUMS.gpg do Ubuntu, SHA512SUMS.sign
 * do Debian, sha256sum.txt.gpg do Linux Mint) com as chaves OFICIAIS embutidas no app.
 *
 * Só são aceitas chaves cuja impressão digital (da chave principal) está na lista publicada pelo
 * projeto (catalog.json → keys). Implementação: BouncyCastle (licença MIT).
 */
object OpenPgp {
    /** Provedor próprio (o Android tem um "BC" interno reduzido; usamos a instância diretamente). */
    private val provider = BouncyCastleProvider()

    /** Chaves de um grupo, carregadas dos recursos do app. */
    class Keys(val group: KeyGroup, internal val rings: PGPPublicKeyRingCollection) {
        /** Impressões digitais reais das chaves carregadas. */
        val loadedFingerprints: Set<String> get() = rings.keyRings.asSequence().map { fp(it) }.toSet()
    }

    fun loadKeys(group: KeyGroup, read: (String) -> ByteArray = ::resource): Keys {
        val rings = mutableListOf<PGPPublicKeyRing>()
        for (f in group.files) {
            val coll = PGPPublicKeyRingCollection(PGPUtil.getDecoderStream(read(f).inputStream()), JcaKeyFingerprintCalculator())
            rings += coll.keyRings.asSequence()
        }
        val keys = Keys(group, PGPPublicKeyRingCollection(rings))
        // as chaves embutidas precisam ser exatamente as anunciadas no catálogo
        val unexpected = keys.loadedFingerprints - group.fingerprints
        if (unexpected.isNotEmpty()) throw IOException("Chave inesperada no grupo ${group.id}: $unexpected")
        return keys
    }

    fun verifyDetached(data: ByteArray, signature: ByteArray, keys: Keys): SignatureResult {
        val sigs = runCatching {
            var obj = JcaPGPObjectFactory(PGPUtil.getDecoderStream(signature.inputStream())).nextObject()
            if (obj is PGPCompressedData) obj = JcaPGPObjectFactory(obj.dataStream).nextObject()
            obj as? PGPSignatureList
        }.getOrNull() ?: return SignatureResult(false, null, null, null, "Arquivo de assinatura ilegível")
        val unknown = mutableListOf<String>()
        for (s in sigs) {
            val key = keys.rings.getPublicKey(s.keyID)
            val ring = keys.rings.getPublicKeyRing(s.keyID)
            if (key == null || ring == null) { unknown += "%016X".format(s.keyID); continue }
            val primary = fp(ring)
            if (primary !in keys.group.fingerprints) { unknown += primary; continue }
            s.init(JcaPGPContentVerifierBuilderProvider().setProvider(provider), key)
            s.update(data)
            val uid = ring.publicKey.userIDs.asSequence().firstOrNull()
            return if (s.verify()) SignatureResult(true, primary, uid, s.creationTime, "Assinatura válida de \"$uid\"")
            else SignatureResult(false, primary, uid, s.creationTime, "ASSINATURA NÃO CONFERE: o arquivo de checksum foi alterado ou corrompido")
        }
        return SignatureResult(false, null, null, null, "Assinado por chave desconhecida (${unknown.joinToString()}); não é uma chave oficial conhecida")
    }

    private fun fp(ring: PGPPublicKeyRing) = ring.publicKey.fingerprint.joinToString("") { "%02X".format(it) }

    private fun resource(name: String): ByteArray =
        OpenPgp::class.java.getResourceAsStream("/dev/bootforge/catalog/keys/$name")?.use { it.readBytes() }
            ?: throw IOException("Chave embutida não encontrada: $name")
}
