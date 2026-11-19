package dev.bootforge.app.data

import android.app.Application
import dev.bootforge.usb.UsbDriveManager
import dev.bootforge.ventoy.VentoyPackage
import dev.bootforge.ventoy.VentoyReleaseClient
import java.io.File

/**
 * Objetos únicos do app (um só gerenciador USB, uma só sessão com o pendrive, um só
 * executor de operações). Inicializado em [dev.bootforge.app.BootForgeApplication].
 */
object AppGraph {
    lateinit var app: Application
        private set

    fun init(application: Application) {
        app = application
    }

    val drives: UsbDriveManager by lazy {
        UsbDriveManager(app).also {
            it.transferBytes = usbTransferKb * 1024
            it.start()
        }
    }

    /**
     * Tamanho de cada comando USB em KB (Logs e diagnóstico → Compatibilidade USB).
     * Padrão 64 KB; menor = mais compatível com pendrives problemáticos, porém mais lento.
     */
    var usbTransferKb: Int
        get() = app.getSharedPreferences("usb", 0).getInt("transferKb", 64)
        set(v) {
            app.getSharedPreferences("usb", 0).edit().putInt("transferKb", v).apply()
            drives.transferBytes = v * 1024
            session.close() // a próxima abertura do pendrive já usa o novo tamanho
            dev.bootforge.integrity.Diag.i("USB", "Tamanho do comando USB alterado para $v KB")
        }
    val history: HistoryRepository by lazy { HistoryRepository(app) }
    val session: DriveSession by lazy { DriveSession(drives) }
    val operations: OperationRunner by lazy { OperationRunner(app, history) }

    /** Pacote original do Ventoy embutido no APK (assets/ventoy/...). */
    val embeddedPackage: VentoyPackage by lazy {
        VentoyPackage(
            open = { path -> app.assets.open("ventoy/$path") },
            lister = { dir -> listAssets("ventoy/$dir").map { it.removePrefix("ventoy/") } },
            origin = "embutido no app",
        )
    }

    val releases: VentoyReleaseClient by lazy { VentoyReleaseClient() }

    /** Catálogo de sistemas e downloads (Etapa 6). */
    val downloads: DownloadCenter by lazy { DownloadCenter(app) }

    /** Pasta onde ficam os pacotes do Ventoy baixados do GitHub oficial. */
    val downloadsDir: File get() = File(app.filesDir, "ventoy-releases").apply { mkdirs() }

    private fun listAssets(dir: String): List<String> {
        val children = app.assets.list(dir).orEmpty()
        if (children.isEmpty()) return emptyList()
        return children.flatMap { c ->
            val p = "$dir/$c"
            val sub = app.assets.list(p).orEmpty()
            if (sub.isEmpty()) listOf(p) else listAssets(p)
        }
    }
}
