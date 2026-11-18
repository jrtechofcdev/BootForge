package dev.bootforge.app.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject
import java.io.File

/** Registro de uma operação realizada (real, nunca simulada). */
data class HistoryEntry(
    val timestamp: Long,
    val operation: String,
    val device: String,
    val success: Boolean,
    val details: String,
)

/**
 * Histórico local de operações, salvo em `files/history.jsonl` (uma linha JSON por operação).
 * Fica apenas no celular; nada é enviado para a internet.
 */
class HistoryRepository(context: Context) {

    private val file = File(context.filesDir, "history.jsonl")
    private val _entries = MutableStateFlow(load())
    val entries: StateFlow<List<HistoryEntry>> = _entries.asStateFlow()

    @Synchronized
    fun add(entry: HistoryEntry) {
        val json = JSONObject()
            .put("t", entry.timestamp)
            .put("op", entry.operation)
            .put("dev", entry.device)
            .put("ok", entry.success)
            .put("details", entry.details)
        file.appendText(json.toString() + "\n")
        _entries.value = listOf(entry) + _entries.value
    }

    @Synchronized
    fun clear() {
        file.delete()
        _entries.value = emptyList()
    }

    private fun load(): List<HistoryEntry> {
        if (!file.exists()) return emptyList()
        return file.readLines().mapNotNull { line ->
            runCatching {
                val o = JSONObject(line)
                HistoryEntry(o.getLong("t"), o.getString("op"), o.getString("dev"), o.getBoolean("ok"), o.optString("details"))
            }.getOrNull()
        }.reversed()
    }
}
