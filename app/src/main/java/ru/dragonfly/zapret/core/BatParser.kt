package ru.dragonfly.zapret.core

import java.io.File

/**
 * Parses a desktop zapret `*.bat` launcher into the argument list of the DPI bypass engine.
 *
 * A strategy file looks like this:
 *
 *   start "zapret: %~n0" /min "%BIN%winws.exe" --wf-tcp=80,443 ^
 *   --filter-tcp=443 --hostlist="%LISTS%list-general.txt" --dpi-desync=fake --new ^
 *   --filter-udp=443 ...
 *
 * On Android the very same arguments are understood by `nfqws`, the Linux sibling of `winws`,
 * except for the WinDivert specific `--wf-*` filters which become iptables/NFQUEUE rules.
 */
object BatParser {

    private const val WINWS = "winws.exe"

    data class ParsedBat(
        val comments: List<String>,
        val rawArgs: List<String>
    ) {
        val isEmpty: Boolean get() = rawArgs.isEmpty()
    }

    fun parseFile(file: File): ParsedBat = parse(file.readText(Charsets.UTF_8))

    fun parse(text: String): ParsedBat {
        val lines = text.replace("\r\n", "\n").replace('\r', '\n').split("\n")

        val comments = lines
            .map { it.trim() }
            .filter { it.startsWith("::") }
            .map { it.removePrefix("::").trim() }
            .filter { it.isNotEmpty() && !it.startsWith("65001") }

        val start = lines.indexOfFirst { it.contains(WINWS, ignoreCase = true) }
        if (start < 0) return ParsedBat(comments, emptyList())

        val command = StringBuilder()
        var index = start
        while (index < lines.size) {
            var line = lines[index].trim()
            val continues = line.endsWith("^")
            if (continues) line = line.dropLast(1)
            command.append(line).append(' ')
            if (!continues) break
            index++
        }

        var tokens = tokenize(command.toString())
        val binaryIndex = tokens.indexOfFirst { it.contains(WINWS, ignoreCase = true) }
        if (binaryIndex >= 0) tokens = tokens.drop(binaryIndex + 1)

        return ParsedBat(comments, tokens)
    }

    /** Splits a Windows command line into arguments honouring double quotes. */
    fun tokenize(line: String): List<String> {
        val result = mutableListOf<String>()
        val current = StringBuilder()
        var quoted = false
        var hasContent = false

        for (ch in line) {
            when {
                ch == '"' -> {
                    quoted = !quoted
                    hasContent = true
                }

                ch.isWhitespace() && !quoted -> {
                    if (hasContent || current.isNotEmpty()) {
                        result.add(current.toString())
                        current.setLength(0)
                        hasContent = false
                    }
                }

                else -> {
                    current.append(ch)
                    hasContent = true
                }
            }
        }
        if (hasContent || current.isNotEmpty()) result.add(current.toString())
        return result.filter { it.isNotEmpty() }
    }
}
