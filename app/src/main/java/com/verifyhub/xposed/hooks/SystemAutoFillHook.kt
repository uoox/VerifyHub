package com.verifyhub.xposed.hooks

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Handler
import android.os.HandlerThread
import com.verifyhub.common.CodeExtractor
import com.verifyhub.data.HistoryProvider
import com.verifyhub.xposed.HookSettings
import com.verifyhub.xposed.InputInjector
import com.verifyhub.xposed.Logger
import io.github.libxposed.api.XposedModuleInterface.PackageLoadedParam

/**
 * 在 system_server 进程内监听 [HistoryProvider.ACTION_NEW_CODE] 广播，触发
 * 自动填充。
 *
 * 之所以放在 system_server，是因为 [InputInjector] 调用的
 * `InputManager.injectInputEvent` 需要 `INJECT_EVENTS` 权限，而 system uid
 * 天生拥有此权限——不需要单独 Hook PermissionManagerService 去授权。
 *
 * 上游谁触发的不重要：
 *   - SMS 路径：[SmsHook] 捕获 → HistoryProvider 广播
 *   - 邮件路径：Manager 进程的 NotificationListener 捕获 → HistoryProvider 广播
 * 两边最终都会在这里被接住。
 */
class SystemAutoFillHook(private val settings: HookSettings) {

    private var receiverInstalled = false
    private val worker by lazy {
        HandlerThread("VerifyHubInject").apply { start() }.let { Handler(it.looper) }
    }

    fun install(param: PackageLoadedParam) {
        if (param.packageName != "android") return
        // system_server 的 Context 可能在 onPackageLoaded 时还没 ready，
        // 用 Handler 多次重试。
        var attempts = 0
        val tryRegister = object : Runnable {
            override fun run() {
                if (receiverInstalled) return
                val ctx = systemContext()
                if (ctx == null && attempts++ < 10) {
                    worker.postDelayed(this, 500)
                    return
                }
                if (ctx == null) {
                    Logger.w("SystemAutoFillHook: gave up waiting for system context")
                    return
                }
                register(ctx)
            }
        }
        worker.post(tryRegister)
    }

    private fun register(ctx: Context) {
        try {
            val filter = IntentFilter(HistoryProvider.ACTION_NEW_CODE)
            ctx.registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED)
            receiverInstalled = true
            Logger.i("SystemAutoFillHook: receiver registered in system_server")
        } catch (t: Throwable) {
            Logger.w("SystemAutoFillHook: registerReceiver failed", t)
        }
    }

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action != HistoryProvider.ACTION_NEW_CODE) return
            if (!settings.autoFill) return

            val kind = intent.getStringExtra(HistoryProvider.EXTRA_KIND) ?: return
            // 链接绝不注入——会把 https://... 当按键打到输入框里
            if (kind == CodeExtractor.Kind.LINK.name) return

            val value = intent.getStringExtra(HistoryProvider.EXTRA_VALUE) ?: return
            // 在工作线程里注入，避免阻塞广播分发
            worker.post {
                val ok = InputInjector.sendText(context, value)
                Logger.i("SystemAutoFillHook: inject ${value.length} chars, ok=$ok")
            }
        }
    }

    private fun systemContext(): Context? = try {
        val atc = Class.forName("android.app.ActivityThread")
        val current = atc.getMethod("currentActivityThread").invoke(null)
        atc.getMethod("getSystemContext").invoke(current) as? Context
    } catch (t: Throwable) {
        null
    }
}
