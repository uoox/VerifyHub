package com.verifyhub.xposed.hooks

import android.content.BroadcastReceiver
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Binder
import android.os.Message
import com.verifyhub.data.Source
import com.verifyhub.xposed.HistoryUploader
import com.verifyhub.xposed.Logger
import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedModuleInterface.PackageLoadedParam

/**
 * 一层捕获：在 `com.android.phone` 进程里 hook telephony 栈的 SMS 派发点。
 *
 * 关键事实：`InboundSmsHandler` 状态机实例（GsmInboundSmsHandler / CdmaInboundSmsHandler）
 * 跑在 `com.android.phone`（UID 1001 radio）进程里——必须装在这里。
 *
 * 抓到验证码时直接「吞掉」这条 SMS：跳过 dispatchIntent 派发给默认短信 App，
 * 同时清掉 InboundSmsHandler.raw 表里这条记录、给状态机补发
 * EVENT_BROADCAST_COMPLETE 让它推进到下一条。这条思路抄的是
 * magisk317/XposedSmsCode 的 `InboundSmsBlocker`。
 *
 * 拦截成功后再把 SMS 手动 insert 到 `content://sms/inbox`（read=1, seen=1），
 * 这样用户在默认短信 App 里仍能看到这条短信，但是已读、没有通知。
 */
class SmsHook(
    private val module: XposedModule,
    private val phoneHook: PhoneBroadcastHook,
) {

    fun install(param: PackageLoadedParam) {
        if (param.packageName != "com.android.phone") return
        val cl = param.defaultClassLoader

        runCatching { hookInboundSmsHandler(cl) }
            .onFailure { Logger.w("InboundSmsHandler hook failed", it) }

        // 兜底：部分 OEM 在 GSM/CDMA 子类里重写 dispatchIntent
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
        targets.forEach { m -> installOn(m) }
        Logger.i("hooked InboundSmsHandler.dispatchIntent x${targets.size}")
    }

    private fun hookSubclasses(cl: ClassLoader) {
        val names = listOf(
            "com.android.internal.telephony.GsmInboundSmsHandler",
            "com.android.internal.telephony.CdmaInboundSmsHandler",
        )
        for (name in names) {
            val cls = runCatching { Class.forName(name, false, cl) }.getOrNull() ?: continue
            cls.declaredMethods.filter { it.name == "dispatchIntent" }.forEach(::installOn)
        }
    }

    private fun installOn(method: java.lang.reflect.Method) {
        module.hook(method).intercept { chain ->
            val intent = chain.args.firstOrNull() as? Intent
            val handler = chain.thisObject
            val receiver = chain.args.firstOrNull { it is BroadcastReceiver }
            val blocked = if (intent != null && handler != null) {
                handleSmsIntent(intent, handler, receiver as? BroadcastReceiver)
            } else false
            if (!blocked) chain.proceed()
        }
    }

    /**
     * @return true 表示已经把这条 SMS 完全吞掉（不调 chain.proceed），false 则正常派发。
     */
    private fun handleSmsIntent(
        intent: Intent,
        handler: Any,
        receiver: BroadcastReceiver?,
    ): Boolean {
        val action = intent.action
        if (action != "android.provider.Telephony.SMS_RECEIVED" &&
            action != "android.provider.Telephony.SMS_DELIVER"
        ) return false

        val msgs = runCatching {
            android.provider.Telephony.Sms.Intents.getMessagesFromIntent(intent)
        }.getOrNull() ?: return false
        if (msgs.isEmpty()) return false

        val body = msgs.joinToString("") { it.messageBody.orEmpty() }
        val sender = msgs.firstOrNull()?.originatingAddress
        val timestamp = msgs.firstOrNull()?.timestampMillis ?: System.currentTimeMillis()
        val ctx = appContext() ?: return false

        val hit = HistoryUploader.report(
            ctx, body, subject = null, sender = sender, source = Source.SMS,
        ) ?: return false

        // 同进程直接做副作用——绕过 HistoryProvider 的广播这一跳（事实证明跨进程
        // 广播在某些场景投递到 dynamic receiver 不稳定，且也不必要）。
        runCatching { phoneHook.sideEffects(ctx, hit.value, hit.kind.name) }
            .onFailure { Logger.w("SmsHook: sideEffects threw", it) }

        // 只在 SMS_DELIVER 时拦截，SMS_RECEIVED 让其它监听者继续收（这是个非定向广播，
        // 拦了影响面会扩散）。
        if (action != "android.provider.Telephony.SMS_DELIVER") return false

        // 1) 让 InboundSmsHandler 状态机推进，不会卡在 WaitingState
        val handled = runCatching {
            blockInbound(handler, receiver)
        }.onFailure { Logger.w("blockInbound failed; will proceed normally", it) }
            .getOrDefault(false)

        if (!handled) return false

        // 2) 既然拦了，就自己把 SMS 写到 inbox（read=1），让用户在默认信息 App
        //    还能看到这条短信，但不会有通知。
        runCatching {
            val cv = ContentValues().apply {
                put("address", sender)
                put("body", body)
                put("date", timestamp)
                put("date_sent", timestamp)
                put("read", 1)
                put("seen", 1)
                put("type", 1)               // 1 = inbox
            }
            ctx.contentResolver.insert(Uri.parse("content://sms/inbox"), cv)
        }.onFailure { Logger.w("inbox insert failed", it) }

        Logger.i("blocked ${hit.kind} from default SMS app, code=${hit.value.length} chars")
        return true
    }

    /**
     * 阻断这条 SMS 派发到默认信息 App：清 raw 行 + 给 InboundSmsHandler 发
     * EVENT_BROADCAST_COMPLETE 让状态机推进。参考 XposedSmsCode 的 InboundSmsBlocker。
     */
    private fun blockInbound(handler: Any, receiver: BroadcastReceiver?): Boolean {
        val token = Binder.clearCallingIdentity()
        try {
            // ---- 删 raw 行 ----
            if (receiver != null) {
                val whereField = receiver.javaClass.declaredFields
                    .firstOrNull { it.name == "mDeleteWhere" }
                val whereArgsField = receiver.javaClass.declaredFields
                    .firstOrNull { it.name == "mDeleteWhereArgs" }
                if (whereField != null && whereArgsField != null) {
                    whereField.isAccessible = true
                    whereArgsField.isAccessible = true
                    val where = whereField.get(receiver) as? String
                    val args = whereArgsField.get(receiver) as? Array<String>
                    val markDeleted = 2
                    // 找 deleteFromRawTable(String, String[], int) 重载（一些版本只有 2 参）
                    val candidates = handler.javaClass.collectInherited("deleteFromRawTable")
                    var deleted = false
                    for (m in candidates) {
                        val params = m.parameterTypes
                        try {
                            when (params.size) {
                                3 -> { m.isAccessible = true; m.invoke(handler, where, args, markDeleted); deleted = true }
                                2 -> { m.isAccessible = true; m.invoke(handler, where, args); deleted = true }
                                else -> continue
                            }
                            if (deleted) break
                        } catch (_: Throwable) { /* try next */ }
                    }
                    if (!deleted) Logger.w("deleteFromRawTable: no callable overload")
                }
            }

            // ---- 给状态机发 EVENT_BROADCAST_COMPLETE=3 ----
            return sendBroadcastComplete(handler)
        } finally {
            Binder.restoreCallingIdentity(token)
        }
    }

    private fun sendBroadcastComplete(handler: Any): Boolean {
        // 路线 A: sendMessage(int)
        runCatching {
            val m = handler.javaClass.collectInherited("sendMessage").firstOrNull {
                it.parameterTypes.size == 1 && it.parameterTypes[0] == Int::class.javaPrimitiveType
            }
            if (m != null) {
                m.isAccessible = true
                m.invoke(handler, EVENT_BROADCAST_COMPLETE)
                return true
            }
        }
        // 路线 B: sendMessage(Message) + obtainMessage(int)
        runCatching {
            val obtain = handler.javaClass.collectInherited("obtainMessage").firstOrNull {
                it.parameterTypes.size == 1 && it.parameterTypes[0] == Int::class.javaPrimitiveType
            } ?: return@runCatching
            obtain.isAccessible = true
            val msg = obtain.invoke(handler, EVENT_BROADCAST_COMPLETE) as? Message
                ?: Message.obtain().apply { what = EVENT_BROADCAST_COMPLETE }
            val send = handler.javaClass.collectInherited("sendMessage").firstOrNull {
                it.parameterTypes.size == 1 && Message::class.java.isAssignableFrom(it.parameterTypes[0])
            } ?: return@runCatching
            send.isAccessible = true
            send.invoke(handler, msg)
            return true
        }
        return false
    }

    private fun Class<*>.collectInherited(name: String): List<java.lang.reflect.Method> {
        val out = mutableListOf<java.lang.reflect.Method>()
        var c: Class<*>? = this
        while (c != null) {
            c.declaredMethods.filter { it.name == name }.forEach(out::add)
            c = c.superclass
        }
        return out
    }

    private fun appContext(): Context? = try {
        val atc = Class.forName("android.app.ActivityThread")
        val current = atc.getMethod("currentActivityThread").invoke(null)
        val app = atc.getMethod("getApplication").invoke(current) as? android.app.Application
        app?.applicationContext
    } catch (t: Throwable) {
        Logger.w("appContext lookup failed in com.android.phone", t); null
    }

    companion object {
        // InboundSmsHandler.EVENT_BROADCAST_COMPLETE = 3（StateMachine 内部常量）
        private const val EVENT_BROADCAST_COMPLETE = 3
    }
}
