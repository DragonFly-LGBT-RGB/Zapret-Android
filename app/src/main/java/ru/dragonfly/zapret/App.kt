package ru.dragonfly.zapret

import android.app.Application
import kotlin.concurrent.thread
import ru.dragonfly.zapret.core.AssetInstaller
import ru.dragonfly.zapret.core.Logger
import ru.dragonfly.zapret.core.Prefs
import ru.dragonfly.zapret.core.Workspace
import ru.dragonfly.zapret.service.Notifications

class App : Application() {

    override fun onCreate() {
        super.onCreate()
        Prefs.init(this)
        Notifications.ensureChannel(this)

        thread(name = "assets-install") {
            runCatching {
                val workspace = Workspace(this)
                workspace.ensureDirs()
                AssetInstaller(this, workspace).installIfNeeded()
            }.onFailure { Logger.e("Ошибка распаковки ресурсов", it) }
        }
    }
}
