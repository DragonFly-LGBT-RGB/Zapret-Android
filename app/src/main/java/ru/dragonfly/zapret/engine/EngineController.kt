package ru.dragonfly.zapret.engine

import android.content.Context
import android.content.Intent
import android.net.VpnService
import android.os.Build
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import ru.dragonfly.zapret.core.EngineMode
import ru.dragonfly.zapret.core.Logger
import ru.dragonfly.zapret.core.Prefs
import ru.dragonfly.zapret.core.Shell
import ru.dragonfly.zapret.core.Workspace
import ru.dragonfly.zapret.service.NfqwsService
import ru.dragonfly.zapret.service.ZapretVpnService

enum class RunState { STOPPED, STARTING, RUNNING, ERROR }

data class EngineStatus(
    val state: RunState = RunState.STOPPED,
    val mode: EngineMode? = null,
    val strategyName: String? = null,
    val message: String? = null,
    val startedAt: Long = 0L
)

/** Single source of truth about what is running right now. */
object EngineController {

    private val _status = MutableStateFlow(EngineStatus())
    val status: StateFlow<EngineStatus> = _status.asStateFlow()

    sealed interface StartResult {
        data object Started : StartResult
        data class NeedsVpnPermission(val intent: Intent) : StartResult
        data class Failed(val reason: String) : StartResult
    }

    /** Resolves AUTO into the mode that is actually going to be used. */
    fun effectiveMode(context: Context): EngineMode = when (Prefs.engineMode) {
        EngineMode.ROOT -> EngineMode.ROOT
        EngineMode.VPN -> EngineMode.VPN
        EngineMode.AUTO -> {
            val workspace = Workspace(context)
            if (workspace.nfqwsBinary.exists() && Shell.isRootAvailable()) EngineMode.ROOT else EngineMode.VPN
        }
    }

    fun start(context: Context, strategyId: String): StartResult {
        val mode = effectiveMode(context)
        Prefs.strategyId = strategyId

        if (mode == EngineMode.VPN) {
            val prepare = VpnService.prepare(context)
            if (prepare != null) return StartResult.NeedsVpnPermission(prepare)
        }

        setStarting(mode)

        val intent = when (mode) {
            EngineMode.ROOT -> Intent(context, NfqwsService::class.java)
            else -> Intent(context, ZapretVpnService::class.java)
        }.setAction(ACTION_START).putExtra(EXTRA_STRATEGY, strategyId)

        return try {
            if (mode == EngineMode.ROOT && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
            StartResult.Started
        } catch (t: Throwable) {
            Logger.e("Не удалось запустить сервис", t)
            setError(t.message ?: "Не удалось запустить сервис")
            StartResult.Failed(t.message ?: "Не удалось запустить сервис")
        }
    }

    fun stop(context: Context) {
        listOf(
            Intent(context, NfqwsService::class.java),
            Intent(context, ZapretVpnService::class.java)
        ).forEach { intent ->
            runCatching { context.startService(intent.setAction(ACTION_STOP)) }
        }
        setStopped()
    }

    fun toggle(context: Context, strategyId: String?): StartResult {
        return if (status.value.state == RunState.RUNNING || status.value.state == RunState.STARTING) {
            stop(context)
            StartResult.Started
        } else {
            val id = strategyId ?: return StartResult.Failed("Стратегия не выбрана")
            start(context, id)
        }
    }

    fun setStarting(mode: EngineMode) {
        _status.value = EngineStatus(RunState.STARTING, mode, Prefs.strategyId)
    }

    fun setRunning(mode: EngineMode, strategyName: String) {
        _status.value = EngineStatus(RunState.RUNNING, mode, strategyName, null, System.currentTimeMillis())
    }

    fun setStopped() {
        _status.value = EngineStatus(RunState.STOPPED, null, Prefs.strategyId)
    }

    fun setError(reason: String) {
        Logger.e(reason)
        _status.value = EngineStatus(RunState.ERROR, _status.value.mode, _status.value.strategyName, reason)
    }

    const val ACTION_START = "ru.dragonfly.zapret.START"
    const val ACTION_STOP = "ru.dragonfly.zapret.STOP"
    const val EXTRA_STRATEGY = "strategy_id"
}
