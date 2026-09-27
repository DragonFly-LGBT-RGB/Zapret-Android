package ru.dragonfly.zapret.engine

import ru.dragonfly.zapret.core.Logger
import ru.dragonfly.zapret.core.Prefs
import ru.dragonfly.zapret.core.ResolvedStrategy
import ru.dragonfly.zapret.core.Shell
import ru.dragonfly.zapret.core.Workspace
import java.io.File

/**
 * Root engine: runs the very same strategy arguments as the desktop version through `nfqws`,
 * the Linux/Android sibling of `winws`. Packets are delivered to it with iptables NFQUEUE,
 * which is the exact counterpart of the WinDivert `--wf-tcp` / `--wf-udp` filters.
 */
class NfqwsEngine(private val workspace: Workspace) {

    companion object {
        /** nfqws marks its own fake packets with this mark, they must not be re-queued. */
        private const val DESYNC_MARK = "0x40000000"
        private const val CONNBYTES_OUT = "1:8"
        private const val CONNBYTES_IN = "1:3"
    }

    class StartFailure(message: String) : Exception(message)

    fun isBinaryAvailable(): Boolean = workspace.nfqwsBinary.exists()

    fun isRunning(): Boolean {
        val pid = readPid() ?: return false
        return File("/proc/$pid").exists()
    }

    @Throws(StartFailure::class)
    fun start(resolved: ResolvedStrategy) {
        if (!isBinaryAvailable()) {
            throw StartFailure("Бинарник nfqws не найден в APK (${workspace.nfqwsBinary.path})")
        }
        if (!Shell.isRootAvailable(refresh = true)) {
            throw StartFailure("Нет прав root. Выберите режим «Без root (VPN)» в настройках.")
        }

        stop(silent = true)

        workspace.ensureDirs()
        workspace.nfqwsLog.writeText("")

        val qnum = Prefs.queueNumber
        val script = buildStartScript(resolved, qnum)
        val scriptFile = File(workspace.run, "start.sh")
        scriptFile.writeText(script)
        scriptFile.setReadable(true, false)

        Logger.i("Запуск nfqws (qnum=$qnum): ${resolved.strategy.name}")
        val result = Shell.su("sh ${Shell.quote(scriptFile.absolutePath)}", timeoutSec = 40)
        Logger.i(result.output.trim().ifEmpty { "iptables: правила добавлены" })

        Thread.sleep(700)
        if (!isRunning()) {
            val log = workspace.nfqwsLog.takeIf { it.exists() }?.readText()?.trim().orEmpty()
            stop(silent = true)
            throw StartFailure(
                "nfqws не запустился. " + (log.takeIf { it.isNotEmpty() } ?: result.output.trim())
            )
        }
        Logger.i("nfqws работает, pid=${readPid()}")
    }

    fun stop(silent: Boolean = false) {
        val scriptFile = File(workspace.run, "stop.sh")
        runCatching {
            workspace.ensureDirs()
            scriptFile.writeText(buildStopScript())
            scriptFile.setReadable(true, false)
            val result = Shell.su("sh ${Shell.quote(scriptFile.absolutePath)}", timeoutSec = 30)
            if (!silent) Logger.i("Остановка nfqws: ${result.output.trim()}")
        }.onFailure { if (!silent) Logger.w("Ошибка остановки: ${it.message}") }
        workspace.nfqwsPid.delete()
    }

    fun readLog(): String = runCatching { workspace.nfqwsLog.readText() }.getOrDefault("")

    private fun readPid(): Int? = runCatching {
        workspace.nfqwsPid.takeIf { it.exists() }?.readText()?.trim()?.toIntOrNull()
    }.getOrNull()

    private fun buildStartScript(resolved: ResolvedStrategy, qnum: Int): String {
        val args = buildList {
            add("--qnum=$qnum")
            addAll(resolved.engineArgs)
        }

        return buildString {
            appendLine("#!/system/bin/sh")
            appendLine("IPT=\$(command -v iptables 2>/dev/null); [ -z \"\$IPT\" ] && IPT=/system/bin/iptables")
            appendLine("IPT6=\$(command -v ip6tables 2>/dev/null); [ -z \"\$IPT6\" ] && IPT6=/system/bin/ip6tables")
            appendLine()
            appendLine("# remove leftovers from a previous run")
            appendLine(cleanupBlock(qnum))
            appendLine()
            appendLine("# NFQUEUE rules (WinDivert --wf-tcp/--wf-udp equivalent)")
            appendLine(rulesBlock(add = true, resolved = resolved, qnum = qnum))
            appendLine()
            appendLine("cd ${Shell.quote(workspace.bin.absolutePath)}")
            append("nohup ${Shell.quote(workspace.nfqwsBinary.absolutePath)} ")
            append(Shell.quote(args))
            appendLine(" >> ${Shell.quote(workspace.nfqwsLog.absolutePath)} 2>&1 &")
            appendLine("echo \$! > ${Shell.quote(workspace.nfqwsPid.absolutePath)}")
            appendLine("sleep 1")
            appendLine("echo \"nfqws pid: \$(cat ${Shell.quote(workspace.nfqwsPid.absolutePath)})\"")
        }
    }

    private fun buildStopScript(): String = buildString {
        appendLine("#!/system/bin/sh")
        appendLine("IPT=\$(command -v iptables 2>/dev/null); [ -z \"\$IPT\" ] && IPT=/system/bin/iptables")
        appendLine("IPT6=\$(command -v ip6tables 2>/dev/null); [ -z \"\$IPT6\" ] && IPT6=/system/bin/ip6tables")
        appendLine("PID=\$(cat ${Shell.quote(workspace.nfqwsPid.absolutePath)} 2>/dev/null)")
        appendLine("[ -n \"\$PID\" ] && kill \$PID 2>/dev/null")
        appendLine("killall nfqws 2>/dev/null")
        appendLine("killall libnfqws.so 2>/dev/null")
        appendLine(cleanupBlock(Prefs.queueNumber))
        appendLine("echo stopped")
    }

    /** Flushes every NFQUEUE rule that points at our queue number. */
    private fun cleanupBlock(qnum: Int): String = buildString {
        appendLine("for T in mangle; do")
        appendLine("  for C in POSTROUTING INPUT FORWARD OUTPUT; do")
        appendLine("    while \$IPT -t \$T -S \$C 2>/dev/null | grep -q -- '--queue-num $qnum '; do")
        appendLine("      RULE=\$(\$IPT -t \$T -S \$C 2>/dev/null | grep -m1 -- '--queue-num $qnum ' | sed 's/^-A /-D /')")
        appendLine("      [ -z \"\$RULE\" ] && break")
        appendLine("      \$IPT -t \$T \$RULE 2>/dev/null || break")
        appendLine("    done")
        appendLine("    while \$IPT6 -t \$T -S \$C 2>/dev/null | grep -q -- '--queue-num $qnum '; do")
        appendLine("      RULE=\$(\$IPT6 -t \$T -S \$C 2>/dev/null | grep -m1 -- '--queue-num $qnum ' | sed 's/^-A /-D /')")
        appendLine("      [ -z \"\$RULE\" ] && break")
        appendLine("      \$IPT6 -t \$T \$RULE 2>/dev/null || break")
        appendLine("    done")
        appendLine("  done")
        appendLine("done")
    }

    private fun rulesBlock(add: Boolean, resolved: ResolvedStrategy, qnum: Int): String {
        val op = if (add) "-I" else "-D"
        val tcp = normalizePorts(resolved.tcpPorts)
        val udp = normalizePorts(resolved.udpPorts)
        val queue = "-j NFQUEUE --queue-num $qnum --queue-bypass"
        val noMark = "-m mark ! --mark $DESYNC_MARK/$DESYNC_MARK"

        return buildString {
            for (ipt in listOf("\$IPT", "\$IPT6")) {
                if (tcp.isNotEmpty()) {
                    appendLine(
                        "$ipt -t mangle $op POSTROUTING -p tcp -m multiport --dports $tcp $noMark " +
                            "-m connbytes --connbytes-dir=original --connbytes-mode=packets --connbytes $CONNBYTES_OUT $queue 2>/dev/null"
                    )
                    appendLine(
                        "$ipt -t mangle $op INPUT -p tcp -m multiport --sports $tcp " +
                            "-m connbytes --connbytes-dir=reply --connbytes-mode=packets --connbytes $CONNBYTES_IN $queue 2>/dev/null"
                    )
                }
                if (udp.isNotEmpty()) {
                    appendLine(
                        "$ipt -t mangle $op POSTROUTING -p udp -m multiport --dports $udp $noMark " +
                            "-m connbytes --connbytes-dir=original --connbytes-mode=packets --connbytes $CONNBYTES_OUT $queue 2>/dev/null"
                    )
                }
            }
        }
    }

    /**
     * iptables multiport accepts at most 15 ports (a range counts as two) and uses ':' for ranges.
     */
    private fun normalizePorts(ports: String): String {
        val items = ports.split(",")
            .map { it.trim() }
            .filter { it.isNotEmpty() && it != "12" }
            .map { it.replace("-", ":") }

        var weight = 0
        val accepted = mutableListOf<String>()
        for (item in items) {
            val cost = if (item.contains(':')) 2 else 1
            if (weight + cost > 15) break
            accepted.add(item)
            weight += cost
        }
        if (accepted.size < items.size) {
            Logger.w("Слишком много портов для multiport, использованы первые: ${accepted.joinToString(",")}")
        }
        return accepted.joinToString(",")
    }
}
