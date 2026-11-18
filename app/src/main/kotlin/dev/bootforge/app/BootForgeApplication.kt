package dev.bootforge.app

import android.app.Application
import dev.bootforge.app.data.AppGraph

/** Ponto de partida do app: prepara os objetos compartilhados (USB, sessão, operações). */
class BootForgeApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        // Primeiro o log: assim até um erro durante a inicialização fica registrado.
        dev.bootforge.app.data.AppLog.install(this)
        AppGraph.init(this)
    }
}
