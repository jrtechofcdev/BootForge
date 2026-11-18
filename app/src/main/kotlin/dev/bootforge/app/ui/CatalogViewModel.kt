package dev.bootforge.app.ui

import android.app.Application
import android.net.Uri
import androidx.core.content.FileProvider
import androidx.lifecycle.AndroidViewModel
import dev.bootforge.app.data.AppGraph
import dev.bootforge.app.data.Format
import dev.bootforge.blockdev.ProgressListener
import dev.bootforge.blockdev.ProgressTracker
import dev.bootforge.catalog.LibraryItem
import dev.bootforge.catalog.ResolvedImage
import dev.bootforge.integrity.HashAlgorithm
import dev.bootforge.integrity.toHex
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.IOException

/** Resultado da verificação de uma ISO baixada fora do app (ex.: Windows pelo navegador). */
data class ExternalCheck(val name: String, val algorithm: String, val actual: String, val expected: String) {
    val matches get() = actual.equals(expected.trim(), ignoreCase = true)
}

/** Telas Catálogo e Downloads (Etapa 6). A lógica fica na [dev.bootforge.app.data.DownloadCenter]. */
class CatalogViewModel(app: Application) : AndroidViewModel(app) {
    private val center = AppGraph.downloads
    private val ops = AppGraph.operations
    val state = center.state

    private val _external = MutableStateFlow<ExternalCheck?>(null)
    val external: StateFlow<ExternalCheck?> = _external.asStateFlow()

    var catalogUrl: String
        get() = center.catalogUrl
        set(v) { center.catalogUrl = v }

    fun checkVersions() = center.checkVersions()
    fun updateCatalog() = center.updateCatalog()
    fun download(r: ResolvedImage) = center.enqueue(r)
    fun pause(name: String) = center.pause(name)
    fun resume(name: String) = center.resume(name)
    fun cancel(name: String) = center.cancel(name)
    fun remove(item: LibraryItem) = center.removeFromLibrary(item)
    fun consumeMessage() = center.consumeMessage()
    fun dismissExternal() { _external.value = null }

    /** Uri (FileProvider) de uma imagem da biblioteca, para abrir no gravador, no editor ou no Ventoy. */
    fun uriFor(item: LibraryItem): Uri {
        val ctx = getApplication<Application>()
        return FileProvider.getUriForFile(ctx, "${ctx.packageName}.files", center.library.file(item))
    }

    /** Copia uma imagem da biblioteca para uma pasta do celular (Downloads, cartão SD…). */
    fun export(item: LibraryItem, target: Uri) = ops.run("Salvar ${item.fileName} no celular", "Biblioteca", null) { cancel, obs ->
        val f = center.library.file(item)
        obs.step("Copiando", 0, 1)
        val tracker = ProgressTracker("Copiando", f.length(), ProgressListener { obs.onProgress(it) })
        getApplication<Application>().contentResolver.openOutputStream(target, "wt")?.use { out ->
            f.inputStream().use { input ->
                val buf = ByteArray(1 shl 20); var done = 0L
                while (true) { cancel.throwIfCancelled(); val n = input.read(buf); if (n < 0) break; out.write(buf, 0, n); done += n; tracker.update(done) }
            }
        } ?: throw IOException("Não foi possível gravar no destino")
        "Cópia salva (${Format.bytes(f.length())})."
    }

    /**
     * Confere uma ISO baixada pelo navegador contra o checksum que a página oficial mostra
     * (a Microsoft publica o SHA-256 em "Verify your download"). Nada é copiado.
     */
    fun verifyExternal(uri: Uri, expected: String) {
        val exp = expected.trim().lowercase()
        if (exp.length != 64 && exp.length != 128 || !exp.all { it in "0123456789abcdef" }) {
            _external.value = ExternalCheck("—", "?", "", "checksum inválido: cole os 64 (SHA-256) ou 128 (SHA-512) caracteres hexadecimais")
            return
        }
        val algo = if (exp.length == 128) HashAlgorithm.SHA512 else HashAlgorithm.SHA256
        val ctx = getApplication<Application>()
        val name = runCatching {
            ctx.contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
        }.getOrNull() ?: "imagem"
        ops.run("Verificar $name", "Biblioteca", null) { cancel, obs ->
            obs.step("Calculando ${if (algo == HashAlgorithm.SHA512) "SHA-512" else "SHA-256"}", 0, 1)
            val size = ctx.contentResolver.openFileDescriptor(uri, "r")?.use { it.statSize } ?: -1
            val tracker = ProgressTracker("Verificando", size, ProgressListener { obs.onProgress(it) })
            val md = algo.newDigest()
            ctx.contentResolver.openInputStream(uri)?.use { input ->
                val buf = ByteArray(1 shl 20); var done = 0L
                while (true) { cancel.throwIfCancelled(); val n = input.read(buf); if (n < 0) break; md.update(buf, 0, n); done += n; tracker.update(done) }
            } ?: throw IOException("Não foi possível abrir o arquivo")
            val check = ExternalCheck(name, if (algo == HashAlgorithm.SHA512) "SHA-512" else "SHA-256", md.digest().toHex(), exp)
            _external.value = check
            if (!check.matches) throw IOException("O checksum NÃO confere: o arquivo está corrompido, incompleto ou não é o publicado oficialmente.")
            "Checksum confere com o informado: \"$name\" é idêntico ao publicado."
        }
    }
}
