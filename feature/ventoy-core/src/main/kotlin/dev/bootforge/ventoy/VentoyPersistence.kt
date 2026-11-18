package dev.bootforge.ventoy

import dev.bootforge.blockdev.BlockDevice
import dev.bootforge.blockdev.ProgressListener
import dev.bootforge.blockdev.slice
import dev.bootforge.fs.exfat.ExFatVolume
import dev.bootforge.fs.ext.ExtFormatter
import dev.bootforge.integrity.CancellationSignal
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import java.io.IOException

/**
 * Cria arquivos de persistência para distribuições Linux "live" — equivalente ao
 * `CreatePersistentImg.sh` oficial do Ventoy, feito direto no pendrive pelo celular.
 *
 * Como funciona: reserva um arquivo CONTÍGUO na partição de dados (o Ventoy mapeia o arquivo
 * diretamente como um disco), formata ext2/ext3 dentro dele e, opcionalmente, liga o arquivo a
 * uma ISO no plugin `persistence` do ventoy.json.
 */
object VentoyPersistence {

    /** Predefinições conhecidas (rótulo e arquivo de configuração que cada família espera). */
    enum class Preset(val label: String, val title: String, val configFile: Pair<String, String>?) {
        UBUNTU("casper-rw", "Ubuntu, Mint, Zorin, Pop!_OS, Linux Lite (casper)", null),
        DEBIAN("persistence", "Debian, Kali e derivados (live-boot)", "persistence.conf" to "/ union\n"),
    }

    data class Request(
        val path: String,
        val sizeMiB: Long,
        val type: ExtFormatter.Type = ExtFormatter.Type.EXT3,
        val label: String = Preset.UBUNTU.label,
        val configFile: Pair<String, String>? = null,
        /** Zerar o conteúdo (mais lento; evita expor dados antigos do pendrive dentro do .dat). */
        val zeroFill: Boolean = false,
        /** ISO que usará esta persistência (adiciona a regra no ventoy.json). */
        val linkImage: String? = null,
    )

    fun create(
        dataDev: BlockDevice,
        vol: ExFatVolume,
        store: VentoyConfigStore,
        req: Request,
        listener: ProgressListener = ProgressListener.NONE,
        cancel: CancellationSignal = CancellationSignal.NONE,
    ): ExtFormatter.Result {
        if (req.sizeMiB < 4) throw IOException("Tamanho mínimo: 4 MiB")
        if (!req.path.startsWith("/") || req.path.endsWith("/")) throw IOException("Caminho inválido: ${req.path}")
        if (vol.exists(req.path)) throw IOException("Já existe ${req.path}")
        val size = req.sizeMiB shl 20
        if (size > vol.freeBytes()) throw IOException("Espaço livre insuficiente no pendrive")
        req.linkImage?.let { if (!vol.exists(it)) throw IOException("Imagem não encontrada: $it") }
        val (cfg, _) = store.load()
        if (req.linkImage != null && cfg == null) throw IOException("O ventoy.json atual tem erro de sintaxe; corrija antes de vincular")

        vol.mkdirs(req.path.substringBeforeLast('/').ifEmpty { "/" })
        vol.createContiguousFile(req.path, size, req.zeroFill, listener, cancel)
        val result = try {
            val (start, count) = vol.contiguousSectorRange(req.path) ?: throw IOException("O arquivo não ficou contíguo")
            ExtFormatter.format(dataDev.slice(start, count), ExtFormatter.Options(req.type, req.label, req.configFile))
        } catch (e: Throwable) {
            runCatching { vol.delete(req.path) } // não deixa um .dat pela metade
            throw e
        }
        if (req.linkImage != null) store.save(link(cfg!!, req.linkImage, req.path))
        return result
    }

    /** Acrescenta (ou completa) a regra {image, backend} do plugin persistence. */
    fun link(cfg: dev.bootforge.ventoy.config.VentoyConfig, image: String, backend: String): dev.bootforge.ventoy.config.VentoyConfig {
        val rules = (cfg.plugin("persistence") as? JsonArray)?.toMutableList() ?: mutableListOf()
        val idx = rules.indexOfFirst { ((it as? JsonObject)?.get("image") as? JsonPrimitive)?.contentOrNull == image }
        if (idx < 0) {
            rules += JsonObject(mapOf("image" to JsonPrimitive(image), "backend" to JsonPrimitive(backend)))
        } else {
            val rule = LinkedHashMap<String, JsonElement>(rules[idx] as JsonObject)
            val current = when (val b = rule["backend"]) {
                is JsonPrimitive -> listOfNotNull(b.contentOrNull)
                is JsonArray -> b.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
                else -> emptyList()
            }
            val all = (current + backend).distinct()
            rule["backend"] = if (all.size == 1) JsonPrimitive(all[0]) else JsonArray(all.map { JsonPrimitive(it) })
            rules[idx] = JsonObject(rule)
        }
        return cfg.withPlugin("persistence", JsonArray(rules))
    }
}
