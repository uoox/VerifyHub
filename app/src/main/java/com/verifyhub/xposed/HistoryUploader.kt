package com.verifyhub.xposed

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import com.verifyhub.common.CodeExtractor
import com.verifyhub.data.HistoryProvider
import com.verifyhub.data.Source

/**
 * Hook 进程统一的"我抓到一条验证码"出口。跑提取器 [CodeExtractor]，然后按来源分两条路把
 * 结果交出去（剪贴板 / Toast / 注入 / 标已读都不在这里做——见 [HistoryProvider] 与
 * [com.verifyhub.xposed.hooks.PhoneBroadcastHook]）：
 *
 *   - [report]（SMS 路径，运行在 `com.android.phone`）：直接 `ContentResolver.insert` 落库。
 *     `com.android.phone` 是平台签名 system app，不受 Android 11+ 包可见性过滤，能 acquire
 *     本模块的 provider。副作用由 SmsHook 在同进程直接调 sideEffects。
 *
 *   - [relayToPhone]（邮件 / Voice 路径，运行在 Gmail / Outlook / Voice 进程）：这些进程受包
 *     可见性限制看不到本模块的 provider（insert 会抛 `IllegalArgumentException: Unknown URL`，
 *     正是「只有短信生效」这个 bug 的根因），所以**不**直接落库，而是把验证码定向广播给
 *     `com.android.phone`，由那边的 PhoneBroadcastHook 代为落库 + 做副作用（剪贴板 / 注入等
 *     需要 uid 1001 的特权，普通 app 做不了）。
 */
object HistoryUploader {

    /**
     * SMS 路径专用：提取 + 落库。只应在 `com.android.phone` 进程调用（那里能 acquire provider）。
     */
    fun report(
        context: Context?,
        body: String?,
        subject: String?,
        sender: String?,
        source: Source,
    ): CodeExtractor.Hit? {
        if (context == null || body.isNullOrBlank()) return null
        val hit = CodeExtractor.extract(body, subject) ?: return null

        // 落库（HistoryProvider 内会做去重）。
        try {
            val cv = ContentValues().apply {
                put(HistoryProvider.COL_VALUE, hit.value)
                put(HistoryProvider.COL_KIND, hit.kind.name)
                put(HistoryProvider.COL_SOURCE, source.name)
                put(HistoryProvider.COL_SENDER, sender)
                put(HistoryProvider.COL_PREVIEW, body.take(280))
                put(HistoryProvider.COL_TIMESTAMP, System.currentTimeMillis())
            }
            context.contentResolver.insert(HistoryProvider.URI, cv)
        } catch (t: Throwable) {
            Logger.w("history insert failed", t)
        }
        Logger.i("captured ${hit.kind} '${hit.value.redact()}' from ${source.name}")
        return hit
    }

    /**
     * 邮件 / Voice 路径专用：提取 + 把验证码定向广播给 `com.android.phone`。
     * 落库与所有副作用（剪贴板 / Toast / 注入）都由那边的 PhoneBroadcastHook 完成——本进程
     * （Gmail / Outlook / Voice）既 acquire 不到 provider、也做不了前台受限 / 需特权的副作用。
     */
    fun relayToPhone(
        context: Context?,
        body: String?,
        subject: String?,
        sender: String?,
        source: Source,
    ): CodeExtractor.Hit? {
        if (context == null || body.isNullOrBlank()) return null
        val hit = CodeExtractor.extract(body, subject) ?: return null

        try {
            // 显式定向到 com.android.phone（它是 forceQueryable 的 system app，对任意进程都可见，
            // 所以本进程能 setPackage 投过去）。带上编译期内置令牌，接收方校验后才动手。
            val intent = Intent(HistoryProvider.ACTION_NEW_CODE).apply {
                setPackage("com.android.phone")
                putExtra(HistoryProvider.EXTRA_TOKEN, HistoryProvider.IPC_TOKEN)
                putExtra(HistoryProvider.EXTRA_VALUE, hit.value)
                putExtra(HistoryProvider.EXTRA_KIND, hit.kind.name)
                putExtra(HistoryProvider.EXTRA_SOURCE, source.name)
                putExtra(HistoryProvider.EXTRA_SENDER, sender)
                putExtra(HistoryProvider.EXTRA_PREVIEW, body.take(280))
            }
            context.sendBroadcast(intent)
        } catch (t: Throwable) {
            Logger.w("relayToPhone broadcast failed", t)
        }
        Logger.i("captured ${hit.kind} '${hit.value.redact()}' from ${source.name} -> relayed to phone")
        return hit
    }

    private fun String.redact(): String =
        if (length <= 2) "*" else first() + "*".repeat(length - 2) + last()
}
