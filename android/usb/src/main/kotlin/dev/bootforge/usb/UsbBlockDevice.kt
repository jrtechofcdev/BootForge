package dev.bootforge.usb

import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import android.hardware.usb.UsbManager
import dev.bootforge.blockdev.BlockDevice
import dev.bootforge.blockdev.ReadOnlyDeviceException
import me.jahnen.libaums.core.driver.scsi.ScsiBlockDevice
import me.jahnen.libaums.core.usb.UsbCommunication
import me.jahnen.libaums.core.usb.UsbCommunicationFactory
import java.io.IOException
import java.nio.ByteBuffer

/**
 * Pendrive acessado DIRETAMENTE por setores via USB OTG, sem root.
 *
 * Como funciona:
 *  1. o Android (USB Host API) entrega ao app a interface USB "Mass Storage" do pendrive;
 *  2. o app assume a interface (o sistema desmonta o volume, se estiver montado);
 *  3. a libaums envia comandos SCSI (READ(10), WRITE(10), READ CAPACITY) pelo protocolo
 *     Bulk-Only Transport — o mesmo que um PC usa.
 *
 * Limitações conhecidas (documentadas em docs/01-relatorio-tecnico.md):
 *  - READ(10)/WRITE(10) usam LBA de 32 bits: até 2 TiB com setor de 512 bytes;
 *  - somente a LUN 0 (leitores de cartão com várias entradas expõem só a primeira);
 *  - não há comando SYNCHRONIZE CACHE na libaums: a integridade é garantida relendo e
 *    comparando o hash dos dados gravados.
 */
class UsbBlockDevice private constructor(
    private val communication: UsbCommunication,
    private val driver: ScsiBlockDevice,
    override val isReadOnly: Boolean,
) : BlockDevice {

    override val blockSize: Int = driver.blockSize
    override val blockCount: Long = driver.blocks

    @Volatile
    private var closed = false

    init {
        if (blockSize <= 0 || blockCount <= 0) {
            close()
            throw IOException("Capacidade inválida informada pelo dispositivo (setor=$blockSize, setores=$blockCount). Dispositivos acima de 1 TiB ainda não são suportados.")
        }
    }

    override fun read(lba: Long, buffer: ByteArray, offset: Int, length: Int) {
        check(lba, length)
        var done = 0
        while (done < length) {
            val n = minOf(MAX_TRANSFER, length - done)
            driver.read(lba + done / blockSize, ByteBuffer.wrap(buffer, offset + done, n))
            done += n
        }
    }

    override fun write(lba: Long, buffer: ByteArray, offset: Int, length: Int) {
        if (isReadOnly) throw ReadOnlyDeviceException()
        check(lba, length)
        var done = 0
        while (done < length) {
            val n = minOf(MAX_TRANSFER, length - done)
            driver.write(lba + done / blockSize, ByteBuffer.wrap(buffer, offset + done, n))
            done += n
        }
    }

    private fun check(lba: Long, length: Int) {
        if (closed) throw IOException("Dispositivo USB desconectado ou fechado")
        if (length % blockSize != 0) throw IllegalArgumentException("Tamanho não alinhado ao setor")
        if (lba < 0 || lba + length / blockSize > blockCount) throw IOException("Acesso fora do dispositivo")
        if (lba + length / blockSize > 0xFFFFFFFFL) throw IOException("LBA acima de 32 bits não suportado (READ(10)/WRITE(10))")
    }

    /** Pendrives USB costumam gravar de forma síncrona; a confirmação real é a releitura + hash. */
    override fun flush() {
        if (closed) throw IOException("Dispositivo USB desconectado ou fechado")
    }

    override fun close() {
        if (closed) return
        closed = true
        runCatching { communication.close() }
    }

    companion object {
        /** Até 1 MiB por comando SCSI (o READ(10)/WRITE(10) aceita no máximo 65535 blocos). */
        private const val MAX_TRANSFER = 1 shl 20

        /** Classe/subclasse/protocolo de um pendrive: Mass Storage / SCSI transparente / Bulk-Only. */
        fun findMassStorageInterface(device: UsbDevice): UsbInterface? =
            (0 until device.interfaceCount).map(device::getInterface).firstOrNull {
                it.interfaceClass == UsbConstants.USB_CLASS_MASS_STORAGE &&
                    it.interfaceSubclass == 6 && it.interfaceProtocol == 0x50
            }

        /**
         * Abre o pendrive. Requer que o usuário já tenha concedido a permissão USB.
         * @throws IOException se o dispositivo não responder ou não for um pendrive compatível.
         */
        fun open(usbManager: UsbManager, device: UsbDevice, readOnly: Boolean): UsbBlockDevice {
            if (!usbManager.hasPermission(device)) throw SecurityException("Permissão USB não concedida para ${device.deviceName}")
            val iface = findMassStorageInterface(device) ?: throw IOException("O dispositivo não é um armazenamento USB (Mass Storage Bulk-Only)")
            var inEp: UsbEndpoint? = null
            var outEp: UsbEndpoint? = null
            for (i in 0 until iface.endpointCount) {
                val ep = iface.getEndpoint(i)
                if (ep.type == UsbConstants.USB_ENDPOINT_XFER_BULK) {
                    if (ep.direction == UsbConstants.USB_DIR_IN) inEp = ep else outEp = ep
                }
            }
            if (inEp == null || outEp == null) throw IOException("Endpoints bulk não encontrados")
            val comm = UsbCommunicationFactory.createUsbCommunication(usbManager, device, iface, outEp, inEp)
            try {
                val driver = ScsiBlockDevice(comm, 0)
                driver.init() // INQUIRY + TEST UNIT READY + READ CAPACITY
                return UsbBlockDevice(comm, driver, readOnly)
            } catch (e: Exception) {
                runCatching { comm.close() }
                throw if (e is IOException) e else IOException("Falha ao inicializar o pendrive: ${e.message}", e)
            }
        }
    }
}
