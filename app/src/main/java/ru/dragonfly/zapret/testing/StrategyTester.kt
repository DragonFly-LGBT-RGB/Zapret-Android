package ru.dragonfly.zapret.testing

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import ru.dragonfly.zapret.core.EngineMode
import ru.dragonfly.zapret.core.Logger
import ru.dragonfly.zapret.core.Prefs
import ru.dragonfly.zapret.core.Strategy
import ru.dragonfly.zapret.core.StrategyRepository
import ru.dragonfly.zapret.core.Workspace
import ru.dragonfly.zapret.engine.ByeDpiRunner
import ru.dragonfly.zapret.engine.ByeDpiTranslator
import ru.dragonfly.zapret.engine.NfqwsEngine
import java.io.File
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.Socket
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.net.ssl.SNIHostName
import javax.net.ssl.SNIServerName
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory

data class SiteResult(
    val target: Target,
    val ok: Boolean,
    val latencyMs: Long?,
    val detail: String
)

data class StrategyReport(
    val strategyId: String,
    val strategyName: String,
    val results: List<SiteResult>,
    val error: String? = null
) {
    val okCount: Int get() = results.count { it.ok }
    val total: Int get() = results.size
    val avgLatencyMs: Long
        get() = results.mapNotNull { it.latencyMs }.takeIf { it.isNotEmpty() }?.average()?.toLong() ?: Long.MAX_VALUE
    val score: Double
        get() = if (total == 0) 0.0 else okCount * 100.0 / total
}

data class TestProgress(
    val strategyIndex: Int,
    val strategyCount: Int,
    val strategyName: String,
    val stage: String,
    val finished: List<StrategyReport>
)

/**
 * Runs every selected strategy one after another and measures how many target sites
 * become reachable. Mirrors `utils/test zapret.ps1` from the desktop version.
 */
class StrategyTester(
    @Suppress("unused") private val context: Context,
    private val workspace: Workspace,
    private val repository: StrategyRepository
) {

    private val nfqws = NfqwsEngine(workspace)

    suspend fun run(
        strategies: List<Strategy>,
        targets: List<Target>,
        mode: EngineMode,
        includeBaseline: Boolean = true,
        onProgress: (TestProgress) -> Unit = {}
    ): List<StrategyReport> = withContext(Dispatchers.IO) {
        val reports = mutableListOf<StrategyReport>()
        val total = strategies.size + if (includeBaseline) 1 else 0
        var index = 0

        if (includeBaseline) {
            onProgress(TestProgress(index, total, BASELINE, "проверка без обхода", reports.toList()))
            reports += StrategyReport(BASELINE, BASELINE, probeAll(targets, null))
            index++
        }

        val testPort = Prefs.socksPort + 1
        val byeDpiRunner = ByeDpiRunner()

        for (strategy in strategies) {
            onProgress(TestProgress(index, total, strategy.name, "запуск", reports.toList()))
            var failure: String? = null
            var proxy: Proxy? = null

            try {
                val resolved = repository.resolve(strategy)
                when (mode) {
                    EngineMode.ROOT -> {
                        nfqws.start(resolved)
                    }

                    else -> {
                        val translation = ByeDpiTranslator.translate(resolved, testPort, Prefs.byeDpiUseHostlists)
                        byeDpiRunner.start(translation.args, testPort)
                        proxy = Proxy(Proxy.Type.SOCKS, InetSocketAddress("127.0.0.1", testPort))
                    }
                }
                delay(Prefs.testWarmupMs.toLong())

                onProgress(TestProgress(index, total, strategy.name, "проверка сайтов", reports.toList()))
                reports += StrategyReport(strategy.id, strategy.name, probeAll(targets, proxy))
            } catch (t: Throwable) {
                failure = t.message ?: t.javaClass.simpleName
                Logger.w("Стратегия ${strategy.name}: $failure")
                reports += StrategyReport(strategy.id, strategy.name, emptyList(), failure)
            } finally {
                runCatching { if (mode == EngineMode.ROOT) nfqws.stop(silent = true) else byeDpiRunner.stop() }
            }
            index++
        }

        onProgress(TestProgress(total, total, "", "готово", reports.toList()))
        reports
    }

    fun best(reports: List<StrategyReport>): StrategyReport? =
        reports.filter { it.strategyId != BASELINE && it.error == null && it.okCount > 0 }
            .sortedWith(compareByDescending<StrategyReport> { it.okCount }.thenBy { it.avgLatencyMs })
            .firstOrNull()

    fun saveReport(reports: List<StrategyReport>): File {
        workspace.ensureDirs()
        val stamp = SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.US).format(Date())
        val file = File(workspace.results, "test_results_$stamp.txt")
        val best = best(reports)

        file.writeText(buildString {
            appendLine("ZapretAndroid — результаты теста стратегий ($stamp)")
            appendLine()
            reports.forEach { report ->
                appendLine("Config: ${report.strategyName}")
                if (report.error != null) {
                    appendLine("  ОШИБКА: ${report.error}")
                } else {
                    report.results.forEach { site ->
                        val status = if (site.ok) "OK   " else "ERROR"
                        val latency = site.latencyMs?.let { "$it ms" } ?: "-"
                        appendLine("  ${site.target.name} : $status | $latency | ${site.detail}")
                    }
                    appendLine("  Итого: ${report.okCount}/${report.total}")
                }
                appendLine()
            }
            appendLine("Best strategy: ${best?.strategyName ?: "не определена"}")
        })
        Logger.i("Отчёт сохранён: ${file.name}")
        return file
    }

    private suspend fun probeAll(targets: List<Target>, proxy: Proxy?): List<SiteResult> = coroutineScope {
        targets.chunked(6).flatMap { chunk ->
            chunk.map { target -> async { probe(target, proxy) } }.awaitAll()
        }
    }

    private fun probe(target: Target, proxy: Proxy?): SiteResult {
        val timeout = Prefs.testTimeoutSec * 1000
        val started = System.currentTimeMillis()

        return try {
            if (target.pingOnly) {
                openSocket(proxy).use { socket ->
                    socket.connect(address(target.host, 53, proxy), timeout)
                }
                SiteResult(target, true, System.currentTimeMillis() - started, "tcp/53")
            } else {
                val socket = openSocket(proxy)
                socket.connect(address(target.host, 443, proxy), timeout)
                socket.soTimeout = timeout

                val factory = SSLSocketFactory.getDefault() as SSLSocketFactory
                val ssl = factory.createSocket(socket, target.host, 443, true) as SSLSocket
                ssl.soTimeout = timeout
                ssl.sslParameters = ssl.sslParameters.apply {
                    serverNames = listOf<SNIServerName>(SNIHostName(target.host))
                }
                ssl.use { tls ->
                    tls.startHandshake()
                    val request = "HEAD / HTTP/1.1\r\nHost: ${target.host}\r\n" +
                        "User-Agent: ZapretAndroid\r\nConnection: close\r\n\r\n"
                    tls.outputStream.write(request.toByteArray())
                    tls.outputStream.flush()
                    val response = tls.inputStream.bufferedReader().readLine().orEmpty()
                    val ok = response.startsWith("HTTP/")
                    SiteResult(
                        target,
                        ok,
                        System.currentTimeMillis() - started,
                        response.take(24).ifEmpty { "нет ответа" }
                    )
                }
            }
        } catch (t: Throwable) {
            SiteResult(target, false, null, t.message?.take(40) ?: t.javaClass.simpleName)
        }
    }

    private fun openSocket(proxy: Proxy?): Socket = if (proxy == null) Socket() else Socket(proxy)

    private fun address(host: String, port: Int, proxy: Proxy?): InetSocketAddress =
        if (proxy == null) InetSocketAddress(host, port) else InetSocketAddress.createUnresolved(host, port)

    companion object {
        const val BASELINE = "Без обхода (контроль)"
    }
}
