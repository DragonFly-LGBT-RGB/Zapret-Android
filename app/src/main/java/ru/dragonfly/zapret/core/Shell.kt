package ru.dragonfly.zapret.core

import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.util.concurrent.TimeUnit

/** Minimal helper for running commands as root through `su`. */
object Shell {

    data class Result(val exitCode: Int, val output: String) {
        val ok: Boolean get() = exitCode == 0
    }

    @Volatile
    private var cachedRoot: Boolean? = null

    fun isRootAvailable(refresh: Boolean = false): Boolean {
        cachedRoot?.takeIf { !refresh }?.let { return it }
        val available = runCatching {
            val result = su("id -u", timeoutSec = 12)
            result.ok && result.output.trim().lines().lastOrNull()?.trim() == "0"
        }.getOrDefault(false)
        cachedRoot = available
        return available
    }

    fun su(vararg commands: String, timeoutSec: Long = 30): Result = exec("su", commands.toList(), timeoutSec)

    fun sh(vararg commands: String, timeoutSec: Long = 30): Result = exec("sh", commands.toList(), timeoutSec)

    private fun exec(binary: String, commands: List<String>, timeoutSec: Long): Result {
        return try {
            val process = ProcessBuilder(binary)
                .redirectErrorStream(true)
                .start()

            process.outputStream.bufferedWriter().use { writer ->
                commands.forEach { command ->
                    writer.write(command)
                    writer.write("\n")
                }
                writer.write("exit\n")
                writer.flush()
            }

            val output = StringBuilder()
            BufferedReader(InputStreamReader(process.inputStream)).use { reader ->
                var line = reader.readLine()
                while (line != null) {
                    output.append(line).append('\n')
                    line = reader.readLine()
                }
            }

            val finished = process.waitFor(timeoutSec, TimeUnit.SECONDS)
            if (!finished) {
                process.destroy()
                return Result(-1, output.toString() + "\n[timeout]")
            }
            Result(process.exitValue(), output.toString())
        } catch (t: Throwable) {
            Result(-1, t.message ?: t.javaClass.simpleName)
        }
    }

    /** Quotes an argument for POSIX shells. */
    fun quote(value: String): String = "'" + value.replace("'", "'\\''") + "'"

    fun quote(values: List<String>): String = values.joinToString(" ") { quote(it) }

    fun fileExists(path: String): Boolean = File(path).exists()
}
