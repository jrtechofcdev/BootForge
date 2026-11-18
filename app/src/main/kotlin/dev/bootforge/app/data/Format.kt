package dev.bootforge.app.data

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Formatação amigável de tamanhos, velocidades e datas (padrão brasileiro). */
object Format {
    private val units = arrayOf("B", "KB", "MB", "GB", "TB")

    /** Tamanho em base 1024 com 1 casa decimal: 15,6 GB. */
    fun bytes(value: Long?): String {
        if (value == null) return "—"
        var v = value.toDouble()
        var i = 0
        while (v >= 1024 && i < units.lastIndex) { v /= 1024; i++ }
        return if (i == 0) "$value B" else String.format(Locale("pt", "BR"), "%.1f %s", v, units[i])
    }

    fun speed(bytesPerSecond: Double): String = bytes(bytesPerSecond.toLong()) + "/s"

    fun eta(seconds: Long?): String {
        if (seconds == null) return "calculando…"
        val m = seconds / 60
        val s = seconds % 60
        return if (m > 0) "${m}min ${s}s" else "${s}s"
    }

    fun dateTime(millis: Long): String = SimpleDateFormat("dd/MM/yyyy HH:mm", Locale("pt", "BR")).format(Date(millis))

    /** Hexdump clássico (offset, bytes, ASCII) — usado no diagnóstico do setor 0. */
    fun hexdump(data: ByteArray, max: Int = 512): String {
        val sb = StringBuilder()
        val n = minOf(max, data.size)
        for (row in 0 until n step 16) {
            sb.append(String.format("%04X  ", row))
            for (i in 0 until 16) {
                if (row + i < n) sb.append(String.format("%02X ", data[row + i])) else sb.append("   ")
            }
            sb.append(' ')
            for (i in 0 until 16) {
                if (row + i >= n) break
                val c = data[row + i].toInt() and 0xFF
                sb.append(if (c in 32..126) c.toChar() else '.')
            }
            sb.append('\n')
        }
        return sb.toString().trimEnd()
    }
}
