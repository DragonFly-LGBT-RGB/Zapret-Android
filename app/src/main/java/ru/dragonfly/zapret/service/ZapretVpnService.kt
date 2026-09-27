package ru.dragonfly.zapret.service

import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import ru.dragonfly.zapret.R
import ru.dragonfly.zapret.core.EngineMode
import ru.dragonfly.zapret.core.Logger
import ru.dragonfly.zapret.core.Prefs
import ru.dragonfly.zapret.core.StrategyRepository
import ru.dragonfly.zapret.core.TProxyService
import ru.dragonfly.zapret.core.Workspace
import ru.dragonfly.zapret.engine.ByeDpiRunner
import ru.dragonfly.zapret.engine.ByeDpiTranslator
import ru.dragonfly.zapret.engine.EngineController

/**
 * No-root engine: byedpi provides a local SOCKS5 server, hev-socks5-tunnel forwards
 * the whole device traffic from the VpnService tun device into it.
 */
class ZapretVpnService : VpnService() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val runner = ByeDpiRunner()

    private lateinit var workspace: Workspace
    private var tun: ParcelFileDescriptor? = null
    private var tunnelStarted = false

    override fun onCreate() {
        super.onCreate()
        workspace = Workspace(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            EngineController.ACTION_STOP -> {
                shutdown()
                return START_NOT_STICKY
            }

            else -> start(intent?.getStringExtra(EngineController.EXTRA_STRATEGY) ?: Prefs.strategyId)
        }
        return START_STICKY
    }

    override fun onRevoke() {
        Logger.w("VPN-разрешение отозвано системой")
        shutdown()
    }

    private fun start(strategyId: String?) {
        goForeground(getString(R.string.status_starting), strategyId.orEmpty())

        scope.launch {
            val repository = StrategyRepository(workspace)
            val strategy = repository.find(strategyId)
            if (strategy == null) {
                EngineController.setError("Стратегия не найдена: $strategyId")
                shutdown()
                return@launch
            }

            try {
                val port = Prefs.socksPort
                val custom = Prefs.customByeDpiArgs.trim()
                val args = if (custom.isNotEmpty()) {
                    buildList {
                        add("ciadpi")
                        add("-i"); add("127.0.0.1")
                        add("-p"); add(port.toString())
                        addAll(custom.split(Regex("\\s+")).filter { it.isNotBlank() })
                    }
                } else {
                    val resolved = repository.resolve(strategy)
                    val translation = ByeDpiTranslator.translate(resolved, port, Prefs.byeDpiUseHostlists)
                    translation.notes.forEach { Logger.w(it) }
                    translation.args
                }

                runner.start(args, port)
                establishTunnel(port)

                EngineController.setRunning(EngineMode.VPN, strategy.name)
                goForeground(getString(R.string.status_running_vpn), strategy.name)
            } catch (failure: Throwable) {
                EngineController.setError(failure.message ?: "Не удалось запустить ByeDPI")
                shutdown()
            }
        }
    }

    private fun establishTunnel(socksPort: Int) {
        val config = buildString {
            appendLine("tunnel:")
            appendLine("  mtu: 8500")
            appendLine("misc:")
            appendLine("  task-stack-size: 81920")
            appendLine("  log-level: warn")
            appendLine("socks5:")
            appendLine("  address: 127.0.0.1")
            appendLine("  port: $socksPort")
            appendLine("  udp: udp")
        }
        workspace.ensureDirs()
        workspace.tunnelConfig.writeText(config)

        val builder = Builder()
            .setSession("Zapret")
            .setMtu(8500)
            .addAddress("198.18.0.1", 32)
            .addRoute("0.0.0.0", 0)
            .addDisallowedApplication(packageName)

        if (Prefs.vpnIpv6) {
            builder.addAddress("fc00::1", 128)
            builder.addRoute("::", 0)
        }
        Prefs.vpnDns.split(",").map { it.trim() }.filter { it.isNotEmpty() }.forEach {
            runCatching { builder.addDnsServer(it) }
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            builder.setMetered(false)
        }

        val descriptor = builder.establish() ?: throw IllegalStateException("Система не выдала tun-устройство")
        tun = descriptor

        TProxyService.TProxyStartService(workspace.tunnelConfig.absolutePath, descriptor.fd)
        tunnelStarted = true
        Logger.i("Туннель поднят, трафик идёт через ByeDPI :$socksPort")
    }

    private fun goForeground(title: String, text: String) {
        val notification = Notifications.build(this, title, text, ZapretVpnService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(
                Notifications.NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            )
        } else {
            startForeground(Notifications.NOTIFICATION_ID, notification)
        }
    }

    private fun shutdown() {
        if (tunnelStarted) {
            runCatching { TProxyService.TProxyStopService() }
            tunnelStarted = false
        }
        runCatching { runner.stop() }
        runCatching { tun?.close() }
        tun = null
        EngineController.setStopped()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        if (tunnelStarted) runCatching { TProxyService.TProxyStopService() }
        runCatching { runner.stop() }
        runCatching { tun?.close() }
        scope.cancel()
        EngineController.setStopped()
        super.onDestroy()
    }
}
