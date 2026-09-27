package ru.dragonfly.zapret.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import ru.dragonfly.zapret.core.Logger
import ru.dragonfly.zapret.core.Prefs
import ru.dragonfly.zapret.engine.EngineController

class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action !in BOOT_ACTIONS) return
        Prefs.init(context)
        if (!Prefs.autoStart) return

        val strategyId = Prefs.strategyId ?: return
        Logger.i("Автозапуск стратегии $strategyId")
        when (val result = EngineController.start(context, strategyId)) {
            is EngineController.StartResult.NeedsVpnPermission ->
                Logger.w("Автозапуск невозможен: не выдано разрешение VPN, откройте приложение")

            is EngineController.StartResult.Failed -> Logger.e("Автозапуск не удался: ${result.reason}")
            else -> Unit
        }
    }

    private companion object {
        val BOOT_ACTIONS = setOf(
            Intent.ACTION_BOOT_COMPLETED,
            "android.intent.action.QUICKBOOT_POWERON",
            Intent.ACTION_MY_PACKAGE_REPLACED
        )
    }
}
