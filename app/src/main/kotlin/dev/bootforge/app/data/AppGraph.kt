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

    val drives: UsbDriveManager by lazy { UsbDriveManager(app).also { it.start() } }
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
