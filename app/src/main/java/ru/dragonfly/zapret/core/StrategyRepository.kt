package ru.dragonfly.zapret.core

import java.io.File

/** Loads the `*.bat` strategies from files/strategies and resolves them for the engines. */
class StrategyRepository(private val workspace: Workspace) {

    /** Options that only exist in the WinDivert build and must not reach nfqws. */
    private val windowsOnlyOptions = listOf(
        "--wf-tcp=", "--wf-udp=", "--wf-raw=", "--wf-l3=", "--wf-save=",
        "--ssid-filter=", "--nlm-filter=", "--wf-iface="
    )

    fun load(): List<Strategy> = workspace.strategyFiles().mapNotNull { file ->
        runCatching {
            val parsed = BatParser.parseFile(file)
            if (parsed.isEmpty) return@runCatching null
            Strategy(
                id = file.name,
                name = file.nameWithoutExtension,
                file = file,
                comments = parsed.comments,
                rawArgs = parsed.rawArgs,
                builtIn = !file.name.startsWith("custom_")
            )
        }.onFailure { Logger.w("Не удалось разобрать ${file.name}: ${it.message}") }.getOrNull()
    }

    fun find(id: String?): Strategy? {
        if (id == null) return null
        return load().firstOrNull { it.id == id }
    }

    fun resolve(strategy: Strategy, gameFilter: GameFilter = Prefs.gameFilter): ResolvedStrategy {
        val gamePorts = when (gameFilter) {
            GameFilter.OFF -> Ports("12", "12")
            GameFilter.TCP -> Ports("1024-65535", "12")
            GameFilter.UDP -> Ports("12", "1024-65535")
            GameFilter.ALL -> Ports("1024-65535", "1024-65535")
        }

        val binPath = workspace.bin.absolutePath + "/"
        val listsPath = workspace.lists.absolutePath + "/"

        var tcpPorts = ""
        var udpPorts = ""
        val engineArgs = mutableListOf<String>()

        for (rawArg in strategy.rawArgs) {
            val arg = rawArg
                .replace("%BIN%", binPath)
                .replace("%LISTS%", listsPath)
                .replace("%~dp0bin\\", binPath)
                .replace("%~dp0lists\\", listsPath)
                .replace("%~dp0", workspace.root.absolutePath + "/")
                .replace("%GameFilterTCP%", gamePorts.tcp)
                .replace("%GameFilterUDP%", gamePorts.udp)
                .replace("%GameFilter%", gamePorts.tcp)
                .replace('\\', '/')

            when {
                arg.startsWith("--wf-tcp=") -> tcpPorts = arg.removePrefix("--wf-tcp=")
                arg.startsWith("--wf-udp=") -> udpPorts = arg.removePrefix("--wf-udp=")
                windowsOnlyOptions.any { arg.startsWith(it) } -> Unit
                arg.equals("start", true) || arg.equals("/min", true) -> Unit
                else -> engineArgs.add(arg)
            }
        }

        return ResolvedStrategy(
            strategy = strategy,
            engineArgs = engineArgs,
            tcpPorts = tcpPorts.ifBlank { "80,443" },
            udpPorts = udpPorts.ifBlank { "443" }
        )
    }

    /** Saves a user provided strategy (raw winws/nfqws command line) as a new *.bat file. */
    fun saveCustom(name: String, commandLine: String): Strategy? {
        val safeName = name.trim().ifEmpty { "custom" }.replace(Regex("[^\\p{L}\\p{N}\\s().\\-_]"), "_")
        val file = File(workspace.strategies, "custom_$safeName.bat")
        val body = buildString {
            appendLine("@echo off")
            appendLine(":: Custom strategy created in ZapretAndroid")
            appendLine()
            append("start \"zapret: %~n0\" /min \"%BIN%winws.exe\" ")
            append(commandLine.replace("\n", " ").trim())
            appendLine()
        }
        file.writeText(body)
        Logger.i("Стратегия сохранена: ${file.name}")
        return find(file.name)
    }

    fun delete(strategy: Strategy): Boolean = strategy.file.delete()

    /** Verifies that every file referenced by the strategy really exists. */
    fun missingFiles(resolved: ResolvedStrategy): List<String> {
        val prefixes = listOf(
            "--hostlist=", "--hostlist-exclude=", "--hostlist-auto=", "--ipset=", "--ipset-exclude=",
            "--dpi-desync-fake-quic=", "--dpi-desync-fake-tls=", "--dpi-desync-fake-unknown-udp=",
            "--dpi-desync-fake-discord=", "--dpi-desync-fake-stun=", "--dpi-desync-split-seqovl-pattern=",
            "--dpi-desync-fake-http=", "--dpi-desync-fake-unknown="
        )
        return resolved.engineArgs.mapNotNull { arg ->
            val prefix = prefixes.firstOrNull { arg.startsWith(it) } ?: return@mapNotNull null
            val path = arg.removePrefix(prefix)
            if (path.startsWith("/") && !File(path).exists()) path else null
        }.distinct()
    }

    private data class Ports(val tcp: String, val udp: String)
}
