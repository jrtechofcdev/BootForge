package dev.bootforge.app

import android.app.Application
import dev.bootforge.app.data.AppGraph

/** Ponto de partida do app: prepara os objetos compartilhados (USB, sessão, operações). */
class BootForgeApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        AppGraph.init(this)
    }
}
