package ru.dragonfly.zapret.core

import android.content.Context
import java.io.File

/**
 * The on-device copy of the desktop zapret folder:
 *
 *   files/strategies   - *.bat strategies (assets + user imported)
 *   files/lists        - hostlists / ipsets, editable by the user
 *   files/bin          - fake payloads (*.bin) referenced by the strategies
 *   files/test_results - saved reports of the strategy tester
 *   files/run          - pid/log files of the running engine
 */
class Workspace(context: Context) {

    private val app = context.applicationContext

    val root: File = app.filesDir
    val strategies: File = File(root, "strategies")
    val lists: File = File(root, "lists")
    val bin: File = File(root, "bin")
    val results: File = File(root, "test_results")
    val run: File = File(root, "run")

    val targetsFile: File = File(root, "targets.txt")
    val nfqwsLog: File = File(run, "nfqws.log")
    val nfqwsPid: File = File(run, "nfqws.pid")
    val tunnelConfig: File = File(run, "tunnel.yml")

    /** nfqws is shipped inside the APK as a native library so that it stays executable. */
    val nfqwsBinary: File = File(app.applicationInfo.nativeLibraryDir, "libnfqws.so")

    fun ensureDirs() {
        listOf(strategies, lists, bin, results, run).forEach { it.mkdirs() }
    }

    /** User editable lists are never overwritten by asset updates. */
    fun isUserList(name: String): Boolean = name.contains("-user")

    fun listFiles(): List<File> =
        lists.listFiles()?.filter { it.isFile && it.extension == "txt" }?.sortedBy { it.name }.orEmpty()

    fun strategyFiles(): List<File> =
        strategies.listFiles()?.filter { it.isFile && it.extension.equals("bat", true) }
            ?.sortedWith(compareBy(NATURAL) { it.name }).orEmpty()

    companion object {
        /** "general (ALT2).bat" must sort before "general (ALT10).bat". */
        val NATURAL: Comparator<String> = Comparator { a, b ->
            val pattern = Regex("\\d+")
            val pa = pattern.replace(a) { it.value.padStart(8, '0') }
            val pb = pattern.replace(b) { it.value.padStart(8, '0') }
            pa.compareTo(pb, ignoreCase = true)
        }
    }
}
