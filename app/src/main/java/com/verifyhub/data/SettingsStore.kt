package com.verifyhub.data

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow

/**
 * 现在只有一项可调：自动填充开关。
 *
 * 标记 SMS 已读 / 邮件通知划掉 / 复制到剪贴板 / Toast 提示，全部固定为默认行为
 * （成功捕获后自动执行），不暴露开关。
 */
class SettingsStore(context: Context) {

    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences(NAME, Context.MODE_PRIVATE)

    data class State(val autoFill: Boolean)

    private fun snapshot(): State = State(
        autoFill = prefs.getBoolean(KEY_AUTO_FILL, false),
    )

    val state: Flow<State> = callbackFlow {
        val l = SharedPreferences.OnSharedPreferenceChangeListener { _, _ -> trySend(snapshot()) }
        prefs.registerOnSharedPreferenceChangeListener(l)
        trySend(snapshot())
        awaitClose { prefs.unregisterOnSharedPreferenceChangeListener(l) }
    }

    fun setAutoFill(value: Boolean) = prefs.edit { putBoolean(KEY_AUTO_FILL, value) }

    companion object {
        const val NAME = "settings"
        const val REMOTE_PREFS_NAME = NAME
        const val KEY_AUTO_FILL = "autoFill"
        const val REMOTE_KEY_AUTO_FILL = KEY_AUTO_FILL
    }
}
