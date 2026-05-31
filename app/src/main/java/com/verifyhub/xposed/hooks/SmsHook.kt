package com.verifyhub.xposed.hooks

import android.content.Context
import android.content.Intent
import com.verifyhub.data.Source
import com.verifyhub.xposed.HistoryUploader
import com.verifyhub.xposed.Logger
import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedModuleInterface.PackageLoadedParam

/**
 * 一层捕获：在 system_server 里 hook telephony 栈的 SMS 派发点。
 *
 * `com.android.internal.telephony.InboundSmsHandler` 在所有 AOSP 主线版本
 * 里都存在；它的 `dispatchIntent` 签名随着版本会增减 UserHandle / subId /
 * appOp 等参数，所以这里按方法名取所有重载，不绑定参数表。
 *
 * 这一层捕获只负责"看到验证码并落库"；标记已读交给下游的
 * [TelephonyProviderHook] 在 insert 时完成，这样不需要延迟匹配，更稳定。
 */
class SmsHook(private val module: XposedModule) {

    fun install(param: PackageLoadedParam) {
        if (param.packageName != "android") return
        val cl = param.defaultClassLoader

        // 主路径
        runCatching { hookInboundSmsHandler(cl) }
            .onFailure { Logger.w("InboundSmsHandler hook failed", it) }

        // 兜底：部分 OEM 在 GSM/CDMA 子类里重写
        runCatching { hookSubclasses(cl) }
    }

    private fun hookInboundSmsHandler(cl: ClassLoader) {
        val cls = Class.forName(
            "com.android.internal.telephony.InboundSmsHandler", false, cl
        )
        val targets = cls.declaredMethods.filter {
            it.name == "dispatchIntent" &&
                it.parameterTypes.isNotEmpty() &&
                it.parameterTypes[0] == Intent::class.java
        }
        if (targets.isEmpty()) {
            Logger.w("InboundSmsHandler.dispatchIntent: no matching overload")
            return
        }
        targets.forEach { m ->
            module.hook(m).intercept { chain ->
                (chain.args.firstOrNull() as? Intent)?.let(::handleSmsIntent)
                chain.proceed()
            }
        }
        Logger.i("hooked InboundSmsHandler.dispatchIntent x${targets.size}")
    }

    private fun hookSubclasses(cl: ClassLoader) {
        val names = listOf(
            "com.android.internal.telephony.GsmInboundSmsHandler",
            "com.android.internal.telephony.CdmaInboundSmsHandler",
        )
        for (name in names) {
            val cls = runCatching { Class.forName(name, false, cl) }.getOrNull() ?: continue
            cls.declaredMethods
                .filter { it.name == "dispatchIntent" }
                .forEach { m ->
                    module.hook(m).intercept { chain ->
                        (chain.args.firstOrNull() as? Intent)?.let(::handleSmsIntent)
                        chain.proceed()
                    }
                }
        }
    }

    private fun handleSmsIntent(intent: Intent) {
        val action = intent.action
        if (action != "android.provider.Telephony.SMS_RECEIVED" &&
            action != "android.provider.Telephony.SMS_DELIVER"
        ) return

        val msgs = runCatching {
            android.provider.Telephony.Sms.Intents.getMessagesFromIntent(intent)
        }.getOrNull() ?: return
        if (msgs.isEmpty()) return

        val body = msgs.joinToString("") { it.messageBody.orEmpty() }
        val sender = msgs.firstOrNull()?.originatingAddress
        val ctx = systemContext() ?: return
        HistoryUploader.report(ctx, body, subject = null, sender = sender, source = Source.SMS)
    }

    private fun systemContext(): Context? = try {
        val atc = Class.forName("android.app.ActivityThread")
        val current = atc.getMethod("currentActivityThread").invoke(null)
        atc.getMethod("getSystemContext").invoke(current) as? Context
    } catch (t: Throwable) {
        Logger.w("systemContext lookup failed", t); null
    }
}
