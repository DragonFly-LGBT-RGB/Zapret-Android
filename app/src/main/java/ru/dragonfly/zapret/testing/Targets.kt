package ru.dragonfly.zapret.testing

import java.io.File

data class Target(
    val name: String,
    /** https://host/... for HTTP+TLS checks, null for ping-only entries. */
    val url: String?,
    val host: String
) {
    val pingOnly: Boolean get() = url == null
}

/** Reads utils/targets.txt in the same format the desktop PowerShell tester uses. */
object Targets {

    fun parse(file: File): List<Target> =
        if (file.exists()) parse(file.readText()) else emptyList()

    fun parse(text: String): List<Target> {
        val result = mutableListOf<Target>()
        for (raw in text.lines()) {
            val line = raw.trim()
            if (line.isEmpty() || line.startsWith("#")) continue
            val separator = line.indexOf('=')
            if (separator <= 0) continue

            val name = line.substring(0, separator).trim()
            val value = line.substring(separator + 1).trim().trim('"')
            if (name.isEmpty() || value.isEmpty()) continue

            if (value.startsWith("PING:", ignoreCase = true)) {
                val host = value.substringAfter(":").trim()
                if (host.isNotEmpty()) result.add(Target(name, null, host))
            } else {
                val host = value.removePrefix("https://").removePrefix("http://").substringBefore("/")
                if (host.isNotEmpty()) result.add(Target(name, value, host))
            }
        }
        return result
    }

    fun serialize(targets: List<Target>): String = buildString {
        appendLine("# targets.txt - список проверяемых адресов")
        appendLine("# Формат: Имя = \"https://host\"  или  Имя = \"PING:1.2.3.4\"")
        appendLine()
        targets.forEach { target ->
            val value = target.url ?: "PING:${target.host}"
            appendLine("${target.name} = \"$value\"")
        }
    }
}
