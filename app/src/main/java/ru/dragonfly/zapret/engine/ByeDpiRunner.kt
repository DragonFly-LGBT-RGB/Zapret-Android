package ru.dragonfly.zapret.engine

import ru.dragonfly.zapret.core.ByeDpiNative
import ru.dragonfly.zapret.core.Logger
import java.net.InetSocketAddress
import java.net.Socket

/** Runs the byedpi SOCKS5 server on a background thread. */
class ByeDpiRunner {

    @Volatile
    private var worker: Thread? = null

    @Volatile
    var lastExitCode: Int? = null
        private set

    val isRunning: Boolean
        get() = worker?.isAlive == true

    class StartFailure(message: String) : Exception(message)

    @Throws(StartFailure::class)
    fun start(args: List<String>, port: Int) {
        if (isRunning) stop()
        if (!ByeDpiNative.isUsable()) {
            throw StartFailure("Движок ByeDPI не собран в этой сборке APK")
        }

        lastExitCode = null
        Logger.i("Запуск ByeDPI: ${args.drop(1).joinToString(" ")}")

        val thread = Thread({
            val code = runCatching { ByeDpiNative.nativeStart(args.toTypedArray()) }
                .onFailure { Logger.e("Сбой ByeDPI", it) }
                .getOrDefault(-1)
            lastExitCode = code
            Logger.i("ByeDPI остановлен (код $code)")
        }, "byedpi")
        thread.isDaemon = true
        thread.start()
        worker = thread

        if (!awaitPort(port, timeoutMs = 4000)) {
            stop()
            throw StartFailure(
                "ByeDPI не открыл порт $port" + (lastExitCode?.let { " (код $it)" } ?: "") +
                    ". Проверьте аргументы стратегии."
            )
        }
        Logger.i("ByeDPI слушает 127.0.0.1:$port")
    }

    fun stop() {
        runCatching { ByeDpiNative.nativeStop() }
        val thread = worker ?: return
        runCatching { thread.join(2500) }
        worker = null
    }

    private fun awaitPort(port: Int, timeoutMs: Long): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (worker?.isAlive != true && lastExitCode != null) return false
            val connected = runCatching {
                Socket().use { socket ->
                    socket.connect(InetSocketAddress("127.0.0.1", port), 400)
                    true
                }
            }.getOrDefault(false)
            if (connected) return true
            Thread.sleep(150)
        }
        return false
    }
}
