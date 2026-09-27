package ru.dragonfly.zapret.core

import android.content.Context
import java.io.File

/** Unpacks the strategies / lists / fake payloads bundled in the APK into files/. */
class AssetInstaller(private val context: Context, private val workspace: Workspace) {

    fun installIfNeeded(force: Boolean = false) {
        val stamp = runCatching {
            context.packageManager.getPackageInfo(context.packageName, 0).lastUpdateTime
        }.getOrDefault(0L)

        if (!force && Prefs.assetsVersion == stamp && workspace.strategies.listFiles()?.isNotEmpty() == true) {
            return
        }

        workspace.ensureDirs()
        Logger.i("Распаковка встроенных данных…")

        copyDir("strategies", workspace.strategies, overwrite = true)
        copyDir("bin", workspace.bin, overwrite = true)
        // lists are editable: built-in ones are refreshed, *-user.txt are kept as is
        copyDir("lists", workspace.lists, overwrite = true) { name -> !workspace.isUserList(name) }
        copyAsset("targets.txt", workspace.targetsFile, overwrite = true)

        // make sure the user lists exist even on a fresh install
        listOf(
            "list-general-user.txt",
            "list-exclude-user.txt",
            "ipset-exclude-user.txt",
            "ipset-all.txt"
        ).forEach { name ->
            val file = File(workspace.lists, name)
            if (!file.exists()) file.writeText("")
        }

        Prefs.assetsVersion = stamp
        Logger.i("Данные распакованы в ${workspace.root.absolutePath}")
    }

    private fun copyDir(
        assetDir: String,
        target: File,
        overwrite: Boolean,
        filter: (String) -> Boolean = { true }
    ) {
        target.mkdirs()
        val names = runCatching { context.assets.list(assetDir) }.getOrNull().orEmpty()
        for (name in names) {
            if (!filter(name)) continue
            val out = File(target, name)
            if (out.exists() && !overwrite) continue
            copyAsset("$assetDir/$name", out, overwrite = true)
        }
    }

    private fun copyAsset(assetPath: String, target: File, overwrite: Boolean) {
        if (target.exists() && !overwrite) return
        runCatching {
            context.assets.open(assetPath).use { input ->
                target.outputStream().use { output -> input.copyTo(output) }
            }
        }.onFailure { Logger.w("Не удалось распаковать $assetPath: ${it.message}") }
    }
}
