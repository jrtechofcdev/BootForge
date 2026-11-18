package dev.bootforge.usb

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Build
import androidx.core.content.ContextCompat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Informações de um pendrive conectado (somente o que o Android realmente informa). */
data class UsbDriveInfo(
    /** Identificador do sistema, ex.: /dev/bus/usb/001/003 — muda a cada conexão. */
    val deviceName: String,
    val vendorId: Int,
    val productId: Int,
    /** Fabricante, modelo e número de série: o Android só libera após a permissão (Android 10+). */
    val manufacturer: String?,
    val product: String?,
    val serial: String?,
    val hasPermission: Boolean,
) {
    val displayName: String
        get() = listOfNotNull(manufacturer?.trim()?.ifEmpty { null }, product?.trim()?.ifEmpty { null })
            .joinToString(" ").ifEmpty { "Dispositivo USB %04X:%04X".format(vendorId, productId) }
}

/**
 * Lista os pendrives conectados, acompanha conexão/desconexão e pede permissão de acesso.
 * Chame [start] ao abrir a tela e [stop] ao sair.
 */
class UsbDriveManager(context: Context) {

    private val appContext = context.applicationContext
    val usbManager: UsbManager = appContext.getSystemService(Context.USB_SERVICE) as UsbManager

    private val _drives = MutableStateFlow<List<UsbDriveInfo>>(emptyList())
    val drives: StateFlow<List<UsbDriveInfo>> = _drives.asStateFlow()

    /** Último evento relevante (para avisar o usuário, ex.: "pendrive removido durante a operação"). */
    private val _events = MutableStateFlow<String?>(null)
    val events: StateFlow<String?> = _events.asStateFlow()

    private var registered = false

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                UsbManager.ACTION_USB_DEVICE_ATTACHED -> _events.value = "Dispositivo USB conectado"
                UsbManager.ACTION_USB_DEVICE_DETACHED -> _events.value = "Dispositivo USB removido"
                ACTION_USB_PERMISSION -> {
                    val granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)
                    _events.value = if (granted) "Permissão USB concedida" else "Permissão USB negada"
                }
            }
            refresh()
        }
    }

    fun start() {
        if (registered) return
        val filter = IntentFilter().apply {
            addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED)
            addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
            addAction(ACTION_USB_PERMISSION)
        }
        ContextCompat.registerReceiver(appContext, receiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
        registered = true
        refresh()
    }

    fun stop() {
        if (!registered) return
        runCatching { appContext.unregisterReceiver(receiver) }
        registered = false
    }

    fun consumeEvent() { _events.value = null }

    /** Atualiza a lista de pendrives conectados. */
    fun refresh() {
        _drives.value = usbManager.deviceList.values
            .filter { UsbBlockDevice.findMassStorageInterface(it) != null }
            .map(::describe)
            .sortedBy { it.deviceName }
    }

    fun findDevice(deviceName: String): UsbDevice? = usbManager.deviceList[deviceName]

    private fun describe(d: UsbDevice): UsbDriveInfo {
        val perm = usbManager.hasPermission(d)
        // Sem permissão, ler serial/fabricante lança SecurityException no Android 10+.
        fun safe(get: () -> String?): String? = if (!perm) null else runCatching(get).getOrNull()
        return UsbDriveInfo(
            deviceName = d.deviceName,
            vendorId = d.vendorId,
            productId = d.productId,
            manufacturer = safe { d.manufacturerName },
            product = safe { d.productName },
            serial = safe { d.serialNumber },
            hasPermission = perm,
        )
    }

    /** Abre a caixa de diálogo do sistema pedindo acesso ao pendrive. */
    fun requestPermission(deviceName: String) {
        val device = findDevice(deviceName) ?: return
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0
        val intent = Intent(ACTION_USB_PERMISSION).setPackage(appContext.packageName)
        val pi = PendingIntent.getBroadcast(appContext, 0, intent, flags)
        usbManager.requestPermission(device, pi)
    }

    /** Abre o pendrive para acesso por setores. Lembre-se de fechar ([UsbBlockDevice.close]). */
    fun open(deviceName: String, readOnly: Boolean): UsbBlockDevice {
        val device = findDevice(deviceName) ?: throw java.io.IOException("Pendrive não está mais conectado")
        return UsbBlockDevice.open(usbManager, device, readOnly)
    }

    companion object {
        const val ACTION_USB_PERMISSION = "dev.bootforge.USB_PERMISSION"
    }
}
