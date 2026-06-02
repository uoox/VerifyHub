package com.verifyhub.xposed

import com.verifyhub.data.Source
import com.verifyhub.xposed.hooks.NotificationHook
import com.verifyhub.xposed.hooks.PhoneBroadcastHook
import com.verifyhub.xposed.hooks.SmsHook
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

    val phoneHook by lazy { PhoneBroadcastHook() }

    override fun onModuleLoaded(param: ModuleLoadedParam) {
        Logger.xposed = this
        Logger.i("module loaded in ${param.processName}")
    }

    override fun onPackageLoaded(param: PackageLoadedParam) {
        Logger.xposed = this
        if (!param.isFirstPackage) return
        Logger.i("onPackageLoaded ${param.packageName}")

        when (param.packageName) {
            "com.android.phone" -> {
                // 这个进程是大本营：
                //   - SmsHook: 抓 InboundSmsHandler.dispatchIntent；同进程直接 sideEffects
                //   - PhoneBroadcastHook: 监听 ACTION_NEW_CODE 给邮件路径用（NotificationHook
                //     在 Gmail/Outlook 进程，没权限，必须靠跨进程广播让我们这边来做）
                //   平台签名 system app，UID=1001，自带 INJECT_EVENTS / WRITE_SMS 等。
                phoneHook.install(param)
                SmsHook(this, phoneHook).install(param)
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
