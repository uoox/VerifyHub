package com.verifyhub.xposed.hooks

import android.app.Application
import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import android.os.Handler
import android.os.Looper
import com.verifyhub.data.Source
import com.verifyhub.xposed.HistoryUploader
import com.verifyhub.xposed.Logger
import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedModuleInterface.PackageLoadedParam

/**
 * Hook 目标 App 内的 `NotificationManager.notify`，抓邮件 / 语音消息里的
 * 验证码，并在抓到后把这条通知划掉。
 *
 * 为什么没用 NotificationListenerService：
 *   - 重新走 Xposed 是为了能拿到 NotificationManager 实例和原始 tag/id，
 *     从而精确地 `cancel` 这条通知（不依赖系统通知监听权限）。
 *   - 同时给后续直接调 App 内部 API 删邮件 / 标记已读留出空间。
 */
class NotificationHook(
    private val module: XposedModule,
    private val source: Source,
) {
    private val main = Handler(Looper.getMainLooper())

    fun install(param: PackageLoadedParam) {
        val cl = param.defaultClassLoader
        runCatching { installOn(cl) }
            .onFailure { Logger.w("notification hook install (${source.name}) failed", it) }
    }

    private fun installOn(cl: ClassLoader) {
        val nm = Class.forName("android.app.NotificationManager", false, cl)
        val candidates = nm.declaredMethods.filter { it.name == "notify" }
        if (candidates.isEmpty()) {
            Logger.w("NotificationManager.notify not found in ${source.name}")
            return
        }
        candidates.forEach { method ->
            module.hook(method).intercept { chain ->
                val args = chain.args
                val notif = args.lastOrNull() as? Notification
                val tag = args.firstOrNull { it is String } as? String
                val id = (args.firstOrNull { it is Int } as? Int) ?: 0
                val mgr = chain.thisObject as? NotificationManager

                chain.proceed()  // 让通知正常显示，免得用户瞬间看不到任何反馈

                if (notif != null) {
                    handle(notif, mgr, tag, id)
                }
            }
        }
        Logger.i("hooked NotificationManager.notify x${candidates.size} (${source.name})")
    }

    private fun handle(notif: Notification, mgr: NotificationManager?, tag: String?, id: Int) {
        val ext = notif.extras ?: return
        val title = ext.getCharSequence(Notification.EXTRA_TITLE)?.toString()
        val text = ext.getCharSequence(Notification.EXTRA_TEXT)?.toString()
        val bigText = ext.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString()
        val sub = ext.getCharSequence(Notification.EXTRA_SUB_TEXT)?.toString()
        val body = listOfNotNull(bigText, text, sub).firstOrNull { it.isNotBlank() } ?: return
        val ctx = appContext() ?: return

        // 邮件 / Voice 路径：本进程（Gmail / Outlook / Voice）看不到本模块的 provider，
        // 也做不了受限的副作用；把验证码定向广播给 com.android.phone 代办。
        val hit = HistoryUploader.relayToPhone(
            context = ctx,
            body = body,
            subject = title,
            sender = title,
            source = source,
        ) ?: return

        // 抓到后划掉这条通知。延迟 200ms 让系统先派完 notify 流程，
        // 避免在 notify 回调里 cancel 引发的内部状态竞争。
        if (mgr != null) {
            main.postDelayed({
                runCatching {
                    if (tag != null) mgr.cancel(tag, id) else mgr.cancel(id)
                    Logger.i("dismissed ${source.name} notification (tag=$tag,id=$id) after capturing ${hit.kind}")
                }.onFailure { Logger.w("cancel notification failed", it) }
            }, 200)
        }
    }

    private fun appContext(): Context? = try {
        val atc = Class.forName("android.app.ActivityThread")
        val current = atc.getMethod("currentActivityThread").invoke(null)
        (atc.getMethod("getApplication").invoke(current) as? Application)?.applicationContext
    } catch (t: Throwable) {
        Logger.w("appContext lookup failed in ${source.name}", t); null
    }
}
