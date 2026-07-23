package com.verifyhub.data

import android.content.ContentProvider
import android.content.ContentResolver
import android.content.ContentValues
import android.content.Intent
import android.database.Cursor
import android.net.Uri
import android.os.SystemClock
import com.verifyhub.common.CodeExtractor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Hook 进程把抓到的验证码塞回 Manager App 的入口。Manager UI 直接读 Room；
 * Hook 进程只通过 ContentResolver.insert 写。
 *
 * 这里只做去重 + 落库 + 广播。Toast/剪贴板/自动填充/标已读都挪到
 * `com.android.phone` 进程的 [com.verifyhub.xposed.hooks.PhoneBroadcastHook]
 * 里处理——manager 进程被 OPlus 频繁 freeze 且属于普通 app，做这些副作用要么静默
 * 失败要么权限不够。
 */
class HistoryProvider : ContentProvider() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate(): Boolean = true

    override fun insert(uri: Uri, values: ContentValues?): Uri? {
        val ctx = context ?: return null
        values ?: return null
        // 取代 manifest 上的 signature 权限：用 caller 包名白名单。
        // 原因：hook 跑在 com.android.phone / Gmail / Outlook 这些进程里，它们都不
        // 持我们的签名，signature 权限会把所有跨进程 insert 全拒掉（包括 SMS 路径）。
        val callerPkg = callingPackage
        if (callerPkg != null && callerPkg !in ALLOWED_CALLERS) return null
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

            // 广播给 com.android.phone 的 PhoneBroadcastHook 用——只为邮件/Voice 路径，
            // 那些 hook 跑在 Gmail/Outlook/Voice 进程里无法直接做副作用，必须跨进程。
            // SMS 路径下，SmsHook 已经在 com.android.phone 同进程里直接调过 sideEffects，
            // 不要再发广播，免得重复（双 Toast、重复注入）。
            if (source != Source.SMS.name) {
                // 显式定向到 com.android.phone：广播里带着明文验证码，不能让任意
                // 声明了同 action 的应用截获。接收方还叠加了签名级权限校验（见
                // PhoneBroadcastHook.register），双重限定收发双方。
                ctx.sendBroadcast(
                    Intent(ACTION_NEW_CODE).apply {
                        setPackage("com.android.phone")
                        putExtra(EXTRA_VALUE, value)
                        putExtra(EXTRA_KIND, kind)
                        putExtra(EXTRA_SOURCE, source)
                        putExtra(EXTRA_SENDER, sender)
                        putExtra(EXTRA_RECEIVED_AT, SystemClock.elapsedRealtime())
                    }
                )
            }
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
        /** 签名级权限，限定 ACTION_NEW_CODE 广播只能由持本模块签名的应用发出。 */
        const val PERMISSION_NEW_CODE = "com.verifyhub.permission.NEW_CODE"
        const val EXTRA_VALUE = "value"
        const val EXTRA_KIND = "kind"
        const val EXTRA_SOURCE = "source"
        const val EXTRA_SENDER = "sender"
        const val EXTRA_RECEIVED_AT = "receivedAt"

        private const val DEDUPE_WINDOW_MS = 30_000L

        private val ALLOWED_CALLERS = setOf(
            "com.verifyhub",
            "android",
            "com.android.phone",
            "com.android.providers.telephony",
            "com.google.android.gm",
            "com.microsoft.office.outlook",
            "com.google.android.apps.googlevoice",
        )

        @Suppress("unused")
        fun resolverOf(any: android.content.Context): ContentResolver = any.contentResolver
    }
}
