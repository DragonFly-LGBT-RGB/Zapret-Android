package ru.dragonfly.zapret.core

import android.content.Context
import android.content.SharedPreferences

enum class EngineMode { AUTO, ROOT, VPN }

enum class GameFilter { OFF, TCP, UDP, ALL }

/** Simple typed wrapper around SharedPreferences. Initialised once from [ru.dragonfly.zapret.App]. */
object Prefs {

    private const val FILE = "zapret_prefs"

    private lateinit var sp: SharedPreferences

    fun init(context: Context) {
        if (!::sp.isInitialized) {
            sp = context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)
        }
    }

    var engineMode: EngineMode
        get() = runCatching { EngineMode.valueOf(sp.getString("engine_mode", null) ?: "AUTO") }
            .getOrDefault(EngineMode.AUTO)
        set(value) = sp.edit().putString("engine_mode", value.name).apply()

    var strategyId: String?
        get() = sp.getString("strategy_id", null)
        set(value) = sp.edit().putString("strategy_id", value).apply()

    var gameFilter: GameFilter
        get() = runCatching { GameFilter.valueOf(sp.getString("game_filter", null) ?: "OFF") }
            .getOrDefault(GameFilter.OFF)
        set(value) = sp.edit().putString("game_filter", value.name).apply()

    var queueNumber: Int
        get() = sp.getInt("qnum", 200)
        set(value) = sp.edit().putInt("qnum", value).apply()

    var socksPort: Int
        get() = sp.getInt("socks_port", 10080)
        set(value) = sp.edit().putInt("socks_port", value).apply()

    var autoStart: Boolean
        get() = sp.getBoolean("autostart", false)
        set(value) = sp.edit().putBoolean("autostart", value).apply()

    /** When not blank it fully replaces the translated ByeDPI command line. */
    var customByeDpiArgs: String
        get() = sp.getString("byedpi_args", "") ?: ""
        set(value) = sp.edit().putString("byedpi_args", value).apply()

    /** Limit ByeDPI desync to the domains from the hostlists instead of every connection. */
    var byeDpiUseHostlists: Boolean
        get() = sp.getBoolean("byedpi_hostlists", false)
        set(value) = sp.edit().putBoolean("byedpi_hostlists", value).apply()

    var vpnDns: String
        get() = sp.getString("vpn_dns", "1.1.1.1") ?: "1.1.1.1"
        set(value) = sp.edit().putString("vpn_dns", value).apply()

    var vpnIpv6: Boolean
        get() = sp.getBoolean("vpn_ipv6", false)
        set(value) = sp.edit().putBoolean("vpn_ipv6", value).apply()

    var testTimeoutSec: Int
        get() = sp.getInt("test_timeout", 5)
        set(value) = sp.edit().putInt("test_timeout", value).apply()

    var testWarmupMs: Int
        get() = sp.getInt("test_warmup", 1500)
        set(value) = sp.edit().putInt("test_warmup", value).apply()

    // ---------------------------------------------------------------- лучшая стратегия

    var lastBestStrategy: String?
        get() = sp.getString("last_best", null)
        set(value) = sp.edit().putString("last_best", value).apply()

    var lastBestName: String?
        get() = sp.getString("last_best_name", null)
        set(value) = sp.edit().putString("last_best_name", value).apply()

    var lastBestOk: Int
        get() = sp.getInt("last_best_ok", 0)
        set(value) = sp.edit().putInt("last_best_ok", value).apply()

    var lastBestTotal: Int
        get() = sp.getInt("last_best_total", 0)
        set(value) = sp.edit().putInt("last_best_total", value).apply()

    var lastBestLatency: Long
        get() = sp.getLong("last_best_latency", 0L)
        set(value) = sp.edit().putLong("last_best_latency", value).apply()

    var lastBestTime: Long
        get() = sp.getLong("last_best_time", 0L)
        set(value) = sp.edit().putLong("last_best_time", value).apply()

    /** При запуске приложения автоматически выбирать стратегию, победившую в тесте. */
    var autoApplyBest: Boolean
        get() = sp.getBoolean("auto_apply_best", true)
        set(value) = sp.edit().putBoolean("auto_apply_best", value).apply()

    var assetsVersion: Long
        get() = sp.getLong("assets_version", 0L)
        set(value) = sp.edit().putLong("assets_version", value).apply()
}
