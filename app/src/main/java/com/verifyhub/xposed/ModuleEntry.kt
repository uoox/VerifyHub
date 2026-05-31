package com.verifyhub.xposed

import com.verifyhub.data.Source
import com.verifyhub.xposed.hooks.NotificationHook
import com.verifyhub.xposed.hooks.SmsHook
import com.verifyhub.xposed.hooks.SystemAutoFillHook
import com.verifyhub.xposed.hooks.TelephonyProviderHook
import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedModuleInterface.ModuleLoadedParam
import io.github.libxposed.api.XposedModuleInterface.PackageLoadedParam

/**
 * 唯一入口。分派策略：
 *
 *   - `android` (system_server)             → SMS 捕获 + 自动填充注入
 *   - `com.android.providers.telephony`     → SMS 落库 + 已读改写
 *   - Gmail / Outlook / Google Voice        → NotificationHook（抓通知 + 划掉）
 */
class ModuleEntry : XposedModule() {

    private val settings by lazy { HookSettings(this) }
    private val autoFillHook by lazy { SystemAutoFillHook(settings) }

    override fun onModuleLoaded(param: ModuleLoadedParam) {
        Logger.xposed = this
        Logger.i("module loaded in ${param.processName}")
    }

    override fun onPackageLoaded(param: PackageLoadedParam) {
        Logger.xposed = this
        if (!param.isFirstPackage) return
        Logger.i("onPackageLoaded ${param.packageName}")

        when (param.packageName) {
            "android" -> {
                SmsHook(this).install(param)
                autoFillHook.install(param)
            }

            "com.android.providers.telephony" -> {
                TelephonyProviderHook(this).install(param)
            }

            "com.google.android.gm" -> {
                NotificationHook(this, Source.GMAIL).install(param)
            }

            "com.microsoft.office.outlook" -> {
                NotificationHook(this, Source.OUTLOOK).install(param)
            }

            "com.google.android.apps.googlevoice" -> {
                NotificationHook(this, Source.GOOGLE_VOICE).install(param)
            }
        }
    }
}
