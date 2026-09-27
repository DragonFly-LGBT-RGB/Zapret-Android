package ru.dragonfly.zapret.service

import android.annotation.SuppressLint
import android.content.Intent
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import androidx.annotation.RequiresApi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import ru.dragonfly.zapret.MainActivity
import ru.dragonfly.zapret.core.Prefs
import ru.dragonfly.zapret.engine.EngineController
import ru.dragonfly.zapret.engine.RunState

@RequiresApi(Build.VERSION_CODES.N)
class ZapretTileService : TileService() {

    private var scope: CoroutineScope? = null

    override fun onStartListening() {
        super.onStartListening()
        Prefs.init(this)
        val newScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
        scope = newScope
        EngineController.status
            .onEach { render(it.state) }
            .launchIn(newScope)
    }

    override fun onStopListening() {
        scope?.cancel()
        scope = null
        super.onStopListening()
    }

    @SuppressLint("StartActivityAndCollapseDeprecated")
    override fun onClick() {
        Prefs.init(this)
        val strategyId = Prefs.strategyId
        if (strategyId == null) {
            openApp()
            return
        }
        when (EngineController.toggle(this, strategyId)) {
            is EngineController.StartResult.NeedsVpnPermission -> openApp()
            else -> Unit
        }
    }

    private fun openApp() {
        val intent = Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        startActivity(intent)
    }

    private fun render(state: RunState) {
        val tile = qsTile ?: return
        tile.state = when (state) {
            RunState.RUNNING -> Tile.STATE_ACTIVE
            RunState.STARTING -> Tile.STATE_UNAVAILABLE
            else -> Tile.STATE_INACTIVE
        }
        tile.label = "Zapret"
        tile.updateTile()
    }
}
