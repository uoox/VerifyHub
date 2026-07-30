package com.verifyhub.data

import android.content.ContentProvider
import android.content.ContentResolver
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri
import com.verifyhub.common.CodeExtractor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Hook 进程把抓到的验证码塞回 Manager App 的入口。Manager UI 直接读 Room；
 * Hook 进程只通过 ContentResolver.insert 写。
 *
 * 这里只做去重 + 落库。谁来写：
 *   - SMS 路径：[com.verifyhub.xposed.hooks.SmsHook] 跑在 `com.android.phone`，直接 insert。
 *     `com.android.phone` 是平台签名 system app，不受 Android 11+ 包可见性过滤，能 acquire
 *     本 provider。
 *   - 邮件/Voice 路径：抓取在 Gmail/Outlook/Voice 进程，那些进程受包可见性限制**看不到本包的
 *     provider**（insert 会抛 `Unknown URL`），所以它们**不**写 provider，而是把验证码定向广播给
 *     `com.android.phone`，由 [com.verifyhub.xposed.hooks.PhoneBroadcastHook] 代为 insert。
 *
 * Toast/剪贴板/自动填充/标已读都挪到 `com.android.phone` 进程的 PhoneBroadcastHook 里处理——
 * manager 进程被 OPlus 频繁 freeze 且属于普通 app，做这些副作用要么静默失败要么权限不够。
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
        /**
         * 定向广播的共享令牌。ACTION_NEW_CODE 由 Gmail/Outlook/Voice 进程（非本模块签名、
         * 也无法持有签名级权限）发往 `com.android.phone`，因此接收方无法用签名权限限定发送方；
         * 改为在广播里带上这个编译期内置的令牌 + 显式 `setPackage("com.android.phone")` 定向投递，
         * 接收方校验令牌后才执行副作用，挡掉第三方 app 伪造广播驱动按键注入 / 截获验证码。
         * 令牌随 APK 编译进各注入进程（Gmail/Outlook/Voice/phone 都是同一份 APK 的代码），天然一致。
         */
        const val IPC_TOKEN = "vh-9f13a7c2-4e8b-4d61-9a2f-7c0e5b3d81aa"
        const val EXTRA_VALUE = "value"
        const val EXTRA_KIND = "kind"
        const val EXTRA_SOURCE = "source"
        const val EXTRA_SENDER = "sender"
        const val EXTRA_PREVIEW = "preview"
        const val EXTRA_TOKEN = "token"

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
