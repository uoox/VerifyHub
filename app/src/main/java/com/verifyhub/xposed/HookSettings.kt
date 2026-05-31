package com.verifyhub.xposed

import android.content.SharedPreferences
import com.verifyhub.data.SettingsStore
import io.github.libxposed.api.XposedModule

class HookSettings(private val module: XposedModule) {
    @Volatile private var cached: SharedPreferences? = null

    private fun prefs(): SharedPreferences? = cached ?: try {
        module.getRemotePreferences(SettingsStore.REMOTE_PREFS_NAME).also { cached = it }
    } catch (t: Throwable) {
        Logger.w("getRemotePreferences failed; defaulting to false", t)
        null
    }

    val autoFill: Boolean
        get() = prefs()?.getBoolean(SettingsStore.REMOTE_KEY_AUTO_FILL, false) ?: false
}
