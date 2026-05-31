package com.verifyhub.xposed.hooks

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.net.Uri
import com.verifyhub.data.Source
import com.verifyhub.xposed.HistoryUploader
import com.verifyhub.xposed.Logger
import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedModuleInterface.PackageLoadedParam

/**
 * Hook `com.android.providers.telephony` 进程里的 SmsProvider。
 * 任何最终落库到 `content://sms/inbox` 的短信都会经过 ContentProvider.insert，
 * 这是版本最稳的 SMS 捕获层。
 *
 * 拦到验证码后顺手把这条插入的 ContentValues 里的 read/seen 改成 1，
 * 让系统通知栏不显示新短信通知（"成功捕获验证码 = 自动标已读"是默认行为）。
 */
class TelephonyProviderHook(private val module: XposedModule) {

    fun install(param: PackageLoadedParam) {
        if (param.packageName != "com.android.providers.telephony") return
        val cl = param.defaultClassLoader

        val cls = runCatching {
            Class.forName("com.android.providers.telephony.SmsProvider", false, cl)
        }.getOrNull() ?: run {
            Logger.w("SmsProvider class not found"); return
        }

        val insertMethods = cls.declaredMethods.filter {
            it.name == "insert" &&
                it.parameterTypes.size in 2..3 &&
                it.parameterTypes[0] == Uri::class.java &&
                it.parameterTypes[1] == ContentValues::class.java
        }
        if (insertMethods.isEmpty()) {
            Logger.w("SmsProvider.insert not found"); return
        }
        insertMethods.forEach { m ->
            module.hook(m).intercept { chain ->
                val uri = chain.args[0] as? Uri
                val values = chain.args[1] as? ContentValues
                // 改写 values 必须在 proceed() 之前
                if (uri != null && values != null) {
                    runCatching { preprocess(chain.thisObject as? ContentProvider, uri, values) }
                        .onFailure { Logger.w("SmsProvider hook preprocess failed", it) }
                }
                chain.proceed()
            }
        }
        Logger.i("hooked SmsProvider.insert x${insertMethods.size}")
    }

    private fun preprocess(provider: ContentProvider?, uri: Uri, values: ContentValues) {
        val path = uri.path.orEmpty()
        if (!path.contains("inbox") && uri.lastPathSegment != "inbox") return

        val body = values.getAsString("body") ?: return
        val address = values.getAsString("address")
        val ctx = provider?.context ?: systemContext() ?: return

        val hit = HistoryUploader.report(
            context = ctx,
            body = body,
            subject = null,
            sender = address,
            source = Source.SMS,
        ) ?: return

        // 抓到了 → 直接把这次 insert 的 ContentValues 改成已读
        values.put("read", 1)
        values.put("seen", 1)
        Logger.i("SMS marked read-on-insert (captured ${hit.kind})")
    }

    private fun systemContext(): Context? = try {
        val atc = Class.forName("android.app.ActivityThread")
        val current = atc.getMethod("currentActivityThread").invoke(null)
        atc.getMethod("getSystemContext").invoke(current) as? Context
    } catch (t: Throwable) {
        null
    }
}
