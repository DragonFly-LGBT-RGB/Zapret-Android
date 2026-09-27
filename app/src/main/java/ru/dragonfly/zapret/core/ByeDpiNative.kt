package ru.dragonfly.zapret.core

/** JNI wrapper around the byedpi engine (libbyedpi.so). */
object ByeDpiNative {

    @Volatile
    var loaded: Boolean = false
        private set

    init {
        loaded = runCatching { System.loadLibrary("byedpi") }.isSuccess
        if (!loaded) Logger.e("Не удалось загрузить libbyedpi.so")
    }

    /** Blocks until [nativeStop] is called. Must be executed on a background thread. */
    external fun nativeStart(args: Array<String>): Int

    external fun nativeStop(): Int

    external fun nativeIsRunning(): Boolean

    /** False when the APK was built without the byedpi sources. */
    external fun nativeIsAvailable(): Boolean

    fun isUsable(): Boolean = loaded && runCatching { nativeIsAvailable() }.getOrDefault(false)
}
