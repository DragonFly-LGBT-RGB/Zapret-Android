package ru.dragonfly.zapret.service

import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import ru.dragonfly.zapret.core.EngineMode
import ru.dragonfly.zapret.core.Logger
import ru.dragonfly.zapret.core.StrategyRepository
import ru.dragonfly.zapret.core.Workspace
import ru.dragonfly.zapret.engine.EngineController
import ru.dragonfly.zapret.engine.NfqwsEngine

/** Foreground service that keeps the root (nfqws + NFQUEUE) engine alive. */
class NfqwsService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private lateinit var workspace: Workspace
    private lateinit var engine: NfqwsEngine

    override fun onCreate() {
        super.onCreate()
        workspace = Workspace(this)
        engine = NfqwsEngine(workspace)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            EngineController.ACTION_STOP -> {
                shutdown()
                return START_NOT_STICKY
            }

            else -> {
                val strategyId = intent?.getStringExtra(EngineController.EXTRA_STRATEGY)
                startEngine(strategyId)
            }
        }
        return START_STICKY
    }

    private fun startEngine(strategyId: String?) {
        goForeground(getString(ru.dragonfly.zapret.R.string.status_starting), strategyId.orEmpty())

        scope.launch {
            val repository = StrategyRepository(workspace)
            val strategy = repository.find(strategyId)
            if (strategy == null) {
                EngineController.setError("Стратегия не найдена: $strategyId")
                stopSelf()
                return@launch
            }

            val resolved = repository.resolve(strategy)
            repository.missingFiles(resolved).forEach { Logger.w("Файл не найден: $it") }

            try {
                engine.start(resolved)
                EngineController.setRunning(EngineMode.ROOT, strategy.name)
                goForeground(getString(ru.dragonfly.zapret.R.string.status_running_root), strategy.name)
            } catch (failure: Throwable) {
                EngineController.setError(failure.message ?: "Не удалось запустить nfqws")
                shutdown()
            }
        }
    }

    private fun goForeground(title: String, text: String) {
        val notification = Notifications.build(this, title, text, NfqwsService::class.java)
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
        runCatching { engine.stop() }
        EngineController.setStopped()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        runCatching { engine.stop(silent = true) }
        scope.cancel()
        EngineController.setStopped()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
