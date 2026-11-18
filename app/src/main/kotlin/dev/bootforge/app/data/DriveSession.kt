package dev.bootforge.app.data

import dev.bootforge.blockdev.BlockDevice
import dev.bootforge.usb.UsbBlockDevice
import dev.bootforge.usb.UsbDriveManager
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.IOException

/**
 * Sessão com UM pendrive: mantém a conexão USB aberta enquanto o usuário navega (evita
 * reconectar a cada clique) e garante que só uma operação por vez acesse o dispositivo.
 *
 * A conexão é fechada ao trocar de pendrive, ao chamar [close] ou quando ocorre erro de E/S
 * (ex.: pendrive removido) — a próxima chamada reabre do zero.
 */
class DriveSession(private val drives: UsbDriveManager) {

    private val mutex = Mutex()
    private var device: UsbBlockDevice? = null
    private var deviceName: String? = null

    /** Executa [block] com acesso exclusivo ao pendrive (leitura e escrita). */
    suspend fun <T> withDevice(name: String, block: (BlockDevice) -> T): T = mutex.withLock {
        val dev = ensureOpen(name)
        try {
            block(dev)
        } catch (e: IOException) {
            closeLocked() // pendrive pode ter sido removido: força reconexão na próxima vez
            throw e
        }
    }

    private fun ensureOpen(name: String): UsbBlockDevice {
        if (deviceName == name) device?.let { return it }
        closeLocked()
        val d = drives.open(name, readOnly = false)
        device = d
        deviceName = name
        return d
    }

    private fun closeLocked() {
        runCatching { device?.close() }
        device = null
        deviceName = null
    }

    /** Fecha a conexão (se nenhuma operação estiver usando o pendrive no momento). */
    fun close() {
        if (mutex.tryLock()) {
            try { closeLocked() } finally { mutex.unlock() }
        }
    }

    /** Fecha se o pendrive da sessão não está mais conectado. */
    fun closeIfDetached(connected: Set<String>) {
        if (deviceName != null && deviceName !in connected) close()
    }
}
