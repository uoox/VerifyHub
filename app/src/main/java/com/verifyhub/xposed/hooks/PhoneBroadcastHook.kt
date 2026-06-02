package com.verifyhub.xposed.hooks

import android.app.Application
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Handler
import android.os.HandlerThread
import android.widget.Toast
import com.verifyhub.common.CodeExtractor
import com.verifyhub.data.HistoryProvider
import com.verifyhub.xposed.InputInjector
import com.verifyhub.xposed.Logger
import io.github.libxposed.api.XposedModuleInterface.PackageLoadedParam

/**
 * 跑在 `com.android.phone` 进程的反应器：监听 [HistoryProvider.ACTION_NEW_CODE]
 * 广播，做用户能感知的副作用——剪贴板、Toast、自动填充、短信标已读。
 *
 * 为什么不放在 system_server：本机 LSPosed v2.0.4 + Android 16 上，新 libxposed
 * API 模块在 system_server 不会被加载（同进程里 XposedBridge 旧 API 的模块能加载，
 * 这是 LSPosed 自己的兼容问题）。`com.android.phone` 是平台签名的 system app，
 * UID=1001 radio，自带 INJECT_EVENTS / WRITE_SECURE_SETTINGS / MODIFY_PHONE_STATE，
 * 能干 system_server 能干的全部事，且加载稳定。
 */
class PhoneBroadcastHook {

    private var receiverInstalled = false
    private val worker by lazy {
        HandlerThread("VerifyHubPhone").apply { start() }.let { Handler(it.looper) }
    }

    fun install(param: PackageLoadedParam) {
        if (param.packageName != "com.android.phone") return
        var attempts = 0
        val tryRegister = object : Runnable {
            override fun run() {
                if (receiverInstalled) return
                val ctx = appContext()
                if (ctx == null && attempts++ < 10) {
                    worker.postDelayed(this, 500)
                    return
                }
                if (ctx == null) {
                    Logger.w("PhoneBroadcastHook: gave up waiting for app context")
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
            Logger.i("PhoneBroadcastHook: receiver registered in com.android.phone")
        } catch (t: Throwable) {
            Logger.w("PhoneBroadcastHook: registerReceiver failed", t)
        }
    }

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val action = intent?.action
            val value = intent?.getStringExtra(HistoryProvider.EXTRA_VALUE)
            val kind = intent?.getStringExtra(HistoryProvider.EXTRA_KIND)
            Logger.i("PhoneBroadcastHook.onReceive action=$action valueLen=${value?.length} kind=$kind")

            if (action != HistoryProvider.ACTION_NEW_CODE) return
            value ?: return
            kind ?: return
            val ctx = context ?: return

            sideEffects(ctx, value, kind)
            // SMS 标已读 + 抑制默认信息 App 通知的逻辑在 SmsHook 里：拦截 dispatchIntent
            // 不向默认信息 App 派发，并自己 insert 一行 read=1 的 inbox 记录。
        }
    }

    /**
     * 共用副作用：剪贴板 / Toast / 注入。可以被 PhoneBroadcastHook 自己用，也可以
     * 被同进程的 SmsHook 直接调用（绕开广播这一跳）。
     *
     * 抓到 CODE 就无脑自动填充——LSPosed 远程 prefs 在 com.android.phone 进程里读
     * 取不稳定（实测拿不到 manager 写入的 autoFill=true 值），与其折腾 IPC 不如
     * 取消开关。LINK 永远不注入，避免把 https://... 当按键打进输入框。
     */
    fun sideEffects(ctx: Context, value: String, kind: String) {
        Logger.i("sideEffects entered len=${value.length} kind=$kind")

        // 1) 剪贴板：UID 1001 的 system app 没有前台限制
        runCatching {
            val cm = ctx.getSystemService(android.content.ClipboardManager::class.java)
            cm?.setPrimaryClip(android.content.ClipData.newPlainText("VerifyHub", value))
        }.onFailure { Logger.w("sideEffects: clipboard write failed", it) }

        // 2) Toast：从 system app 发，不会被后台静默策略吞掉
        runCatching {
            val short = if (value.length > 24) value.take(20) + "…" else value
            Toast.makeText(ctx, "$short 已复制", Toast.LENGTH_SHORT).show()
        }.onFailure { Logger.w("sideEffects: toast failed", it) }

        // 3) 自动填充。一字一字注入有间隔，配合 6 格 OTP 输入框的逐位 focus 跳转。
        if (kind == CodeExtractor.Kind.CODE.name) {
            worker.post {
                runCatching {
                    val ok = InputInjector.sendText(ctx, value)
                    Logger.i("sideEffects: inject ${value.length} chars, ok=$ok")
                }.onFailure { Logger.w("sideEffects: InputInjector threw", it) }
            }
        }
    }

    private fun appContext(): Context? = try {
        val atc = Class.forName("android.app.ActivityThread")
        val current = atc.getMethod("currentActivityThread").invoke(null)
        val app = atc.getMethod("getApplication").invoke(current) as? Application
        app?.applicationContext
    } catch (t: Throwable) {
        null
    }
}
