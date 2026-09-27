package ru.dragonfly.zapret.core

import java.io.File

data class Strategy(
    val id: String,
    val name: String,
    val file: File,
    val comments: List<String>,
    val rawArgs: List<String>,
    val builtIn: Boolean
) {
    val notRecommended: Boolean
        get() = comments.any { it.contains("NOT RECOMMENDED", ignoreCase = true) }

    /** Short human readable description, e.g. "fake ×6, multisplit, seqovl". */
    val summary: String
        get() {
            val techniques = LinkedHashSet<String>()
            rawArgs.forEach { arg ->
                when {
                    arg.startsWith("--dpi-desync=") ->
                        arg.removePrefix("--dpi-desync=").split(",").forEach { techniques.add(it) }

                    arg.startsWith("--dpi-desync-split-seqovl=") -> techniques.add("seqovl")
                    arg.startsWith("--dpi-desync-fooling=") ->
                        techniques.add(arg.removePrefix("--dpi-desync-fooling="))

                    arg.startsWith("--dpi-desync-fake-quic=") -> techniques.add("fake-quic")
                    arg.startsWith("--dpi-desync-fake-tls") -> techniques.add("fake-tls")
                }
            }
            return techniques.joinToString(" · ").ifEmpty { "нет параметров обхода" }
        }

    val profileCount: Int
        get() = rawArgs.count { it == "--new" } + 1
}

/**
 * Strategy arguments with all Windows variables expanded and split into
 * something the Android engines can consume.
 */
data class ResolvedStrategy(
    val strategy: Strategy,
    /** Arguments for nfqws (WinDivert filters removed). */
    val engineArgs: List<String>,
    /** Value of --wf-tcp, used to build the NFQUEUE iptables rules. */
    val tcpPorts: String,
    /** Value of --wf-udp. */
    val udpPorts: String
) {
    /** Arguments split by `--new` into independent desync profiles. */
    val profiles: List<List<String>>
        get() {
            val out = mutableListOf<List<String>>()
            var current = mutableListOf<String>()
            for (arg in engineArgs) {
                if (arg == "--new") {
                    out.add(current)
                    current = mutableListOf()
                } else {
                    current.add(arg)
                }
            }
            out.add(current)
            return out.filter { it.isNotEmpty() }
        }

    val commandLine: String
        get() = engineArgs.joinToString(" ") { if (it.contains(' ')) "\"$it\"" else it }
}
