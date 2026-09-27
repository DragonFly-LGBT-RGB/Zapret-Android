package ru.dragonfly.zapret.core

/**
 * JNI wrapper around hev-socks5-tunnel (libhev-socks5-tunnel.so).
 * The names below must match PKGNAME/CLSNAME passed to the native build.
 */
object TProxyService {

    @Volatile
    var loaded: Boolean = false
        private set

    init {
        loaded = runCatching { System.loadLibrary("hev-socks5-tunnel") }.isSuccess
        if (!loaded) Logger.e("Не удалось загрузить libhev-socks5-tunnel.so")
    }

    @JvmStatic
    external fun TProxyStartService(configPath: String, fd: Int)

    @JvmStatic
    external fun TProxyStopService()

    @JvmStatic
    external fun TProxyGetStats(): LongArray
}
