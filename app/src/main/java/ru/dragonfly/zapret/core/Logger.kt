package ru.dragonfly.zapret.core

import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** In-memory ring buffer of log lines shown on the "Журнал" screen. */
object Logger {

    private const val TAG = "Zapret"
    private const val MAX_LINES = 600

    private val timeFormat = SimpleDateFormat("HH:mm:ss", Locale.US)

    private val _lines = MutableStateFlow<List<String>>(emptyList())
    val lines: StateFlow<List<String>> = _lines

    fun i(message: String) = append("i", message)

    fun w(message: String) = append("!", message)

    fun e(message: String, error: Throwable? = null) {
        append("E", if (error == null) message else "$message: ${error.message}")
        if (error != null) Log.e(TAG, message, error)
    }

    fun clear() {
        _lines.value = emptyList()
    }

    fun dump(): String = _lines.value.joinToString("\n")

    private fun append(level: String, message: String) {
        Log.i(TAG, "[$level] $message")
        val line = "${timeFormat.format(Date())} $level $message"
        val current = _lines.value
        val updated = if (current.size >= MAX_LINES) {
            current.drop(current.size - MAX_LINES + 1) + line
        } else {
            current + line
        }
        _lines.value = updated
    }
}
