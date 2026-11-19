package dev.bootforge.usb

import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import android.hardware.usb.UsbManager
import dev.bootforge.blockdev.AdaptiveChunker
import dev.bootforge.blockdev.BlockDevice
import dev.bootforge.blockdev.ReadOnlyDeviceException
import me.jahnen.libaums.core.driver.scsi.ScsiBlockDevice
import me.jahnen.libaums.core.usb.UsbCommunication
import me.jahnen.libaums.core.usb.UsbCommunicationFactory
import java.io.IOException
import dev.bootforge.integrity.Diag
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
    private var driver: ScsiBlockDevice,
    override val isReadOnly: Boolean,
    transferBytes: Int,
) : BlockDevice {

    override val blockSize: Int = driver.blockSize
    override val blockCount: Long = driver.blocks

    @Volatile
    private var closed = false

    /** Comandos de tamanho adaptável (64 KB por padrão; diminui sozinho se o pendrive recusar). */
    private val chunker = AdaptiveChunker(transferBytes, blockSize)

    /** Tamanho atual de cada comando USB (para o log e a tela de diagnóstico). */
    val currentTransferBytes: Int get() = chunker.chunkBytes

    init {
        if (blockSize <= 0 || blockCount <= 0) {
            close()
            throw IOException("Capacidade inválida informada pelo dispositivo (setor=$blockSize, setores=$blockCount). Dispositivos acima de 1 TiB ainda não são suportados.")
        }
        Diag.i("USB", "Tamanho de cada comando USB: ${chunker.chunkBytes / 1024} KB")
    }

    override fun read(lba: Long, buffer: ByteArray, offset: Int, length: Int) {
        check(lba, length)
        chunker.run(lba, length, { at, off, n ->
            Diag.d("SCSI") { "READ(10) LBA $at, ${n / blockSize} setores" }
            try {
                driver.read(at, ByteBuffer.wrap(buffer, offset + off, n))
            } catch (e: Exception) {
                Diag.e("SCSI", "Falha de LEITURA no LBA $at (${n / blockSize} setores)", e)
                throw if (e is IOException) e else IOException("Falha de leitura no setor $at: ${e.message}", e)
            }
        }, ::recover, ::shrunk)
    }

    override fun write(lba: Long, buffer: ByteArray, offset: Int, length: Int) {
        if (isReadOnly) throw ReadOnlyDeviceException()
        check(lba, length)
        chunker.run(lba, length, { at, off, n ->
            Diag.d("SCSI") { "WRITE(10) LBA $at, ${n / blockSize} setores" }
            try {
                driver.write(at, ByteBuffer.wrap(buffer, offset + off, n))
            } catch (e: Exception) {
                Diag.e("SCSI", "Falha de GRAVAÇÃO no LBA $at (${n / blockSize} setores)", e)
                throw if (e is IOException) e else IOException("Falha de gravação no setor $at: ${e.message}", e)
            }
        }, ::recover, ::shrunk)
    }

    /** Reinicia a porta USB e o protocolo do pendrive, sem precisar desconectar. */
    private fun recover() {
        if (closed) throw IOException("Dispositivo USB desconectado ou fechado")
        Diag.w("USB", "Recuperando a comunicação (reinício da porta USB)…")
        runCatching { communication.resetDevice() }.onFailure { Diag.w("USB", "Reinício da porta USB não aceito: ${it.message}") }
        Thread.sleep(500)
        val d = ScsiBlockDevice(communication, 0)
        d.init()
        if (d.blockSize != blockSize || d.blocks != blockCount) throw IOException("O pendrive respondeu com outra capacidade depois de reiniciar: operação interrompida por segurança")
        driver = d
        Diag.i("USB", "Comunicação recuperada")
    }

    private fun shrunk(bytes: Int, cause: Throwable) {
        Diag.w("USB", "O pendrive recusou comandos maiores; reduzindo para ${bytes / 1024} KB por comando (${cause.message})")
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
        Diag.i("USB", "Conexão com o pendrive fechada")
        runCatching { communication.close() }
    }

    companion object {
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
        fun open(usbManager: UsbManager, device: UsbDevice, readOnly: Boolean, transferBytes: Int = AdaptiveChunker.DEFAULT_BYTES): UsbBlockDevice {
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
                val driver = try {
                    ScsiBlockDevice(comm, 0).also { it.init() } // INQUIRY + TEST UNIT READY + READ CAPACITY
                } catch (first: Exception) {
                    // pendrive "travado" por uma falha anterior: reinicia a porta USB e tenta mais uma vez
                    Diag.w("USB", "O pendrive não respondeu ao abrir; reiniciando a porta USB e tentando de novo (${first.message})")
                    runCatching { comm.resetDevice() }
                    Thread.sleep(800)
                    try {
                        ScsiBlockDevice(comm, 0).also { it.init() }
                    } catch (second: Exception) {
                        second.addSuppressed(first)
                        throw IOException("O pendrive não respondeu. Desconecte e conecte de novo (de preferência sem hub/extensão) e tente outra vez.", second)
                    }
                }
                return UsbBlockDevice(comm, driver, readOnly, transferBytes)
            } catch (e: Exception) {
                runCatching { comm.close() }
                throw if (e is IOException) e else IOException("Falha ao inicializar o pendrive: ${e.message}", e)
            }
        }
    }
}
