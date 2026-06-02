package com.verifyhub.xposed

import android.content.ContentValues
import android.content.Context
import com.verifyhub.common.CodeExtractor
import com.verifyhub.data.HistoryProvider
import com.verifyhub.data.Source

/**
 * Hook 进程统一的"我抓到一条验证码"出口。负责：
 *   - 跑提取器
 *   - 写剪贴板
 *   - 通过 HistoryProvider 落库（顺带触发 ACTION_NEW_CODE 广播，让
 *     SystemAutoFillHook 接到后注入按键）
 */
object HistoryUploader {

    fun report(
        context: Context?,
        body: String?,
        subject: String?,
        sender: String?,
        source: Source,
    ): CodeExtractor.Hit? {
        if (context == null || body.isNullOrBlank()) return null
        val hit = CodeExtractor.extract(body, subject) ?: return null

        // 落库（HistoryProvider 内会做去重、Toast、广播 ACTION_NEW_CODE）。
        // 剪贴板写入挪到 system_server 的 SystemAutoFillHook 里完成——它有 uid 1000
        // 的特权可绕过 ClipboardService 的前台校验；hook 进程（radio / Gmail / Outlook）
        // 都没有这个特权，强行写会拿到 SecurityException。
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

    private fun String.redact(): String =
        if (length <= 2) "*" else first() + "*".repeat(length - 2) + last()
}
