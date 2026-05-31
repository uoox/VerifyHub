package com.verifyhub.data

import android.content.ContentProvider
import android.content.ContentResolver
import android.content.ContentValues
import android.content.Intent
import android.database.Cursor
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.widget.Toast
import com.verifyhub.common.CodeExtractor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Hook 进程把抓到的验证码塞回 Manager App 的入口。Manager UI 直接读 Room；
 * Hook 进程只通过 ContentResolver.insert 写。
 *
 * 副作用顺序：
 *   1) 去重（同值 30 秒窗口）
 *   2) 写 Room
 *   3) Toast: "{code} 已复制"
 *   4) 广播 ACTION_NEW_CODE  → SystemAutoFillHook 接到后按需注入
 */
class HistoryProvider : ContentProvider() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val main = Handler(Looper.getMainLooper())

    override fun onCreate(): Boolean = true

    override fun insert(uri: Uri, values: ContentValues?): Uri? {
        val ctx = context ?: return null
        values ?: return null
        val value = values.getAsString(COL_VALUE) ?: return null
        val kind = values.getAsString(COL_KIND) ?: CodeExtractor.Kind.CODE.name
        val source = values.getAsString(COL_SOURCE) ?: "UNKNOWN"
        val sender = values.getAsString(COL_SENDER)
        val preview = values.getAsString(COL_PREVIEW).orEmpty().take(280)
        val ts = values.getAsLong(COL_TIMESTAMP) ?: System.currentTimeMillis()

        val dao = AppDatabase.get(ctx).codeRecords()
        scope.launch {
            if (dao.existsSince(value, ts - DEDUPE_WINDOW_MS)) return@launch
            dao.insert(
                CodeRecord(
                    value = value, kind = kind, source = source,
                    sender = sender, preview = preview, timestamp = ts,
                    handled = true,
                )
            )

            // Toast 提示在 UI 主线程
            main.post {
                runCatching {
                    val short = if (value.length > 24) value.take(20) + "…" else value
                    Toast.makeText(ctx, "$short 已复制", Toast.LENGTH_SHORT).show()
                }
            }

            // 广播 → system_server 的 SystemAutoFillHook
            ctx.sendBroadcast(
                Intent(ACTION_NEW_CODE).apply {
                    setPackage(ctx.packageName)
                    putExtra(EXTRA_VALUE, value)
                    putExtra(EXTRA_KIND, kind)
                    putExtra(EXTRA_SOURCE, source)
                    putExtra(EXTRA_SENDER, sender)
                    putExtra(EXTRA_RECEIVED_AT, SystemClock.elapsedRealtime())
                }
            )
        }
        return Uri.withAppendedPath(URI, "queued")
    }

    override fun query(
        uri: Uri, projection: Array<String>?, selection: String?,
        selectionArgs: Array<String>?, sortOrder: String?,
    ): Cursor? = null

    override fun getType(uri: Uri): String? = null
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<String>?): Int = 0
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<String>?): Int = 0

    companion object {
        const val AUTHORITY = "com.verifyhub.provider"
        val URI: Uri = Uri.parse("content://$AUTHORITY/codes")

        const val COL_VALUE = "value"
        const val COL_KIND = "kind"
        const val COL_SOURCE = "source"
        const val COL_SENDER = "sender"
        const val COL_PREVIEW = "preview"
        const val COL_TIMESTAMP = "timestamp"

        const val ACTION_NEW_CODE = "com.verifyhub.action.NEW_CODE"
        const val EXTRA_VALUE = "value"
        const val EXTRA_KIND = "kind"
        const val EXTRA_SOURCE = "source"
        const val EXTRA_SENDER = "sender"
        const val EXTRA_RECEIVED_AT = "receivedAt"

        private const val DEDUPE_WINDOW_MS = 30_000L

        @Suppress("unused")
        fun resolverOf(any: android.content.Context): ContentResolver = any.contentResolver
    }
}
