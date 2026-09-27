package ru.dragonfly.zapret.engine

import ru.dragonfly.zapret.core.ResolvedStrategy
import java.io.File

/**
 * Translates a zapret/winws strategy into a ByeDPI (ciadpi) command line.
 *
 * The two engines work on different layers: zapret rewrites raw packets, ByeDPI is a local
 * SOCKS5 proxy that splits the TLS/HTTP stream. Most desync techniques therefore have a close
 * but not identical counterpart. Everything that cannot be expressed is reported in
 * [Translation.notes] so the UI can be honest about it.
 */
object ByeDpiTranslator {

    data class Translation(
        val args: List<String>,
        val notes: List<String>
    ) {
        val commandLine: String get() = args.drop(1).joinToString(" ")
    }

    fun translate(
        resolved: ResolvedStrategy,
        socksPort: Int,
        useHostlists: Boolean
    ): Translation {
        val notes = linkedSetOf<String>()
        val args = mutableListOf("ciadpi", "-i", "127.0.0.1", "-p", socksPort.toString())
        args += listOf("-c", "1024", "-T", "4")

        val groups = mutableListOf<List<String>>()

        for (profile in resolved.profiles) {
            val options = ProfileOptions(profile)

            if (options.isUdpOnly) {
                if (options.desync.isNotEmpty()) {
                    notes += "UDP/QUIC-профиль «${options.describe()}» частично перенесён: " +
                        "ByeDPI не умеет desync для QUIC, применён --udp-fake."
                }
                continue
            }
            if (options.desync.isEmpty()) continue

            val group = buildGroup(options, useHostlists, notes)
            if (group.isNotEmpty() && groups.none { it == group }) groups += group
        }

        val udpFakes = resolved.profiles
            .map { ProfileOptions(it) }
            .filter { it.isUdpOnly }
            .mapNotNull { it.repeats }
            .maxOrNull()

        if (groups.isEmpty()) {
            notes += "Не удалось перенести ни один профиль, используется универсальный набор ByeDPI."
            groups += listOf("--split", "1+s", "--disorder", "1", "--tlsrec", "1+s")
        }

        groups.forEachIndexed { index, group ->
            if (index == 0) {
                args += group
            } else {
                // every extra group is a fallback that is tried when the previous one fails
                args += listOf("--auto", "torst,ssl_err,none")
                args += group
            }
        }

        if (udpFakes != null && udpFakes > 0) {
            args += listOf("--udp-fake", udpFakes.coerceAtMost(4).toString())
        }

        return Translation(args, notes.toList())
    }

    private fun buildGroup(
        options: ProfileOptions,
        useHostlists: Boolean,
        notes: MutableSet<String>
    ): List<String> {
        val group = mutableListOf<String>()
        val position = mapPosition(options.splitPos)

        fun addOnce(vararg items: String) {
            // byedpi applies one value per option inside a group, duplicates would override each other
            if (group.contains(items.first())) return
            group.addAll(items)
        }

        for (technique in options.desync) {
            when (technique) {
                "fake" -> {
                    addOnce("--fake", position)
                    addOnce("--ttl", options.ttl ?: "8")
                    options.fakePayload?.let { addOnce("--fake-data", it) }
                }

                "split", "split2", "multisplit" -> addOnce("--split", position)
                "disorder", "disorder2", "multidisorder" -> addOnce("--disorder", position)
                "fakedsplit", "fakedsplit2", "hostfakesplit" -> {
                    addOnce("--fake", position)
                    addOnce("--ttl", options.ttl ?: "8")
                    options.fakePayload?.let { addOnce("--fake-data", it) }
                    addOnce("--split", position)
                }

                "fakeddisorder" -> {
                    addOnce("--fake", position)
                    addOnce("--ttl", options.ttl ?: "8")
                    addOnce("--disorder", position)
                }

                "syndata" -> notes += "syndata не поддерживается ByeDPI (нужен root-режим)."
                "ipfrag1", "ipfrag2" -> notes += "Фрагментация IP не поддерживается ByeDPI."
                "hopbyhop", "destopt", "tamper" -> notes += "$technique недоступен без root."
            }
        }

        if (group.isEmpty()) return emptyList()

        options.seqovl?.let {
            notes += "seqovl=$it не имеет аналога в ByeDPI, вместо него используется обычное разбиение."
        }
        if (options.fooling.contains("md5sig") && !group.contains("--md5sig")) group += "--md5sig"
        if (options.fooling.any { it != "md5sig" }) {
            notes += "fooling=${options.fooling.joinToString(",")} применяется только в root-режиме."
        }

        options.repeats?.let { if (it > 1) addOnce("--round", "1-${it.coerceAtMost(6)}") }

        if (useHostlists && options.hostlists.isNotEmpty()) {
            val existing = options.hostlists.filter { File(it).exists() }
            if (existing.isNotEmpty()) group += listOf("--hosts", existing.first())
            if (existing.size > 1) {
                notes += "ByeDPI принимает один hostlist на группу, использован ${File(existing.first()).name}."
            }
        }

        // TLS record fragmentation greatly improves the success rate of split based strategies
        if (group.none { it == "--tlsrec" }) group += listOf("--tlsrec", "1+s")

        return group
    }

    /** zapret split positions -> ByeDPI `offset[+flags]`. */
    private fun mapPosition(raw: String?): String {
        val value = raw?.trim().orEmpty()
        if (value.isEmpty()) return "1+s"
        return when {
            value.startsWith("sniext") || value.startsWith("sni") -> {
                val offset = value.substringAfter('+', "1").toIntOrNull() ?: 1
                "$offset+s"
            }

            value.startsWith("midsld") -> "0+sm"
            value.startsWith("host") -> {
                val offset = value.substringAfter('+', "1").toIntOrNull() ?: 1
                "$offset+h"
            }

            value.startsWith("endhost") -> "0+he"
            value.startsWith("method") -> "1"
            value.toIntOrNull() != null -> value
            else -> value.split(",").firstOrNull()?.toIntOrNull()?.toString() ?: "1+s"
        }
    }

    /** Parsed view over a single `--new` delimited desync profile. */
    private class ProfileOptions(args: List<String>) {

        private val values = LinkedHashMap<String, String>()
        private val flags = LinkedHashSet<String>()

        init {
            for (arg in args) {
                if (!arg.startsWith("--")) continue
                val body = arg.removePrefix("--")
                val index = body.indexOf('=')
                if (index >= 0) {
                    val key = body.substring(0, index)
                    val value = body.substring(index + 1)
                    // options that may appear several times are joined with ';'
                    values[key] = values[key]?.let { "$it;$value" } ?: value
                } else {
                    flags += body
                }
            }
        }

        val desync: List<String> =
            values["dpi-desync"]?.split(",")?.map { it.trim() }?.filter { it.isNotEmpty() }.orEmpty()

        val splitPos: String? = values["dpi-desync-split-pos"]
        val seqovl: String? = values["dpi-desync-split-seqovl"]
        val ttl: String? = values["dpi-desync-ttl"]
        val repeats: Int? = values["dpi-desync-repeats"]?.toIntOrNull()
        val fooling: List<String> =
            values["dpi-desync-fooling"]?.split(",")?.map { it.trim() }.orEmpty()

        val hostlists: List<String> =
            (values["hostlist"]?.split(";").orEmpty()).filter { it.isNotBlank() }

        val fakePayload: String? = listOf(
            "dpi-desync-fake-tls", "dpi-desync-fake-http", "dpi-desync-split-seqovl-pattern"
        ).firstNotNullOfOrNull { values[it]?.split(";")?.firstOrNull() }
            ?.takeIf { it.startsWith("/") && File(it).exists() }

        val isUdpOnly: Boolean =
            values.containsKey("filter-udp") && !values.containsKey("filter-tcp")

        fun describe(): String = buildString {
            values["filter-udp"]?.let { append("udp:$it ") }
            values["filter-tcp"]?.let { append("tcp:$it ") }
            append(desync.joinToString(","))
        }.trim()
    }
}
