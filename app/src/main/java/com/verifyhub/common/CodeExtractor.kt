package com.verifyhub.common

/**
 * 从短信 / 邮件正文里提取验证码或验证链接。
 *
 * 设计参考 tianma8023/XposedSmsCode 的 `SmsCodeUtils.getSmsCode`：
 *   1) 必须命中「验证码类」关键词。仅出现登录、确认这类宽泛词不算——否则一封
 *      "您的账户于2026年..."的登录提醒会被误判，把 2026 当验证码。
 *   2) 命中关键词后，在文本里搜所有候选码，按 (匹配度, 距关键词字符数) 排序：
 *        匹配度  6位纯数字 > 4位纯数字 > 其它纯数字 > 数字+字母 > 纯字母
 *        距离    小者优先
 *   3) 邮箱、URL 提前涂白，避免地址 local-part / 域名段被当成码。
 *   4) URL 单独走链接通道，命中 verify / confirm / magic 等词的链接返回 [Kind.LINK]。
 */
object CodeExtractor {

    data class Hit(
        val value: String,
        val kind: Kind,
        val index: Int,
    )

    enum class Kind { CODE, LINK }

    /**
     * 「验证码」严格关键词。只用真正暗示"这里有一串验证码"的词。
     * 不收 "login" / "confirm" / "auth" 这种泛词——它们在大量非验证短信里也出现。
     */
    private val KEYWORDS = listOf(
        // 中文
        "验证码", "校验码", "检验码", "确认码", "激活码", "动态码", "安全码",
        "验证代码", "校验代码", "确认代码", "激活代码", "动态代码", "安全代码",
        "短信口令", "动态密码", "随机码", "动态口令", "登入码", "认证码", "识别码", "交易码",
        // 繁中
        "驗證碼", "校驗碼", "確認碼", "激活碼", "動態碼",
        // 英文
        "verification code", "verify code", "security code", "passcode", "one-time",
        "one time password", "otp", "auth code", "authentication code", "code is",
        "your code", "use code", "code:",
    )

    // 候选码正则：4-8 位「数字或字母」，两侧禁止 .@-_+/ 等紧贴的字符，避免捕到
    // 邮件 local-part 段、域名段、IP 段等。
    private val CODE_REGEX = Regex(
        "(?<![A-Za-z0-9._@\\-+/])([0-9]{4,8}|[A-Z0-9]{4,8}|[A-Za-z0-9]{6,8})(?![A-Za-z0-9._@\\-+/])"
    )

    private val EMAIL_REGEX = Regex("[A-Za-z0-9._%+\\-]+@[A-Za-z0-9.\\-]+\\.[A-Za-z]{2,}")
    private val URL_REGEX = Regex(
        "https?://[A-Za-z0-9._~%/?#\\-=&+]+",
        RegexOption.IGNORE_CASE
    )

    private val LINK_VERIFY_HINTS = listOf(
        "verify", "verification", "confirm", "activate", "magic", "signin",
        "sign-in", "login", "authenticate", "auth", "validate", "reset",
    )

    fun extract(body: String?, subject: String? = null): Hit? {
        if (body.isNullOrBlank()) return null
        val haystack = buildString {
            if (!subject.isNullOrBlank()) appendLine(subject)
            append(body)
        }

        // 链接通道独立，不依赖关键词命中（"please verify here: https://..." 这种）。
        val link = bestLink(haystack)

        // 涂掉邮件地址与所有 URL，再去找验证码。
        val sanitized = haystack
            .replace(EMAIL_REGEX, " ")
            .replace(URL_REGEX, " ")
        val lower = sanitized.lowercase()

        // 关键词命中是「这是验证码短信」的硬性条件，没有就不抓。
        val keywordIdx = firstKeywordIndex(lower)
        if (keywordIdx >= 0) {
            bestCode(sanitized, keywordIdx)?.let { return it }
        }

        // 没有关键词命中 → 退化为只看链接通道
        return link
    }

    private fun firstKeywordIndex(lowerText: String): Int {
        var best = -1
        for (kw in KEYWORDS) {
            val i = lowerText.indexOf(kw)
            if (i >= 0 && (best < 0 || i < best)) best = i
        }
        return best
    }

    private fun bestCode(haystack: String, keywordIdx: Int): Hit? {
        val matches = CODE_REGEX.findAll(haystack).toList()
        if (matches.isEmpty()) return null

        // 优先取距关键词 ≤ 30 字符的候选；如果都不近，退而求其次取全部。
        val near = matches.filter { kotlin.math.abs(it.range.first - keywordIdx) <= 30 }
        val pool = if (near.isNotEmpty()) near else matches

        val winner = pool
            .map { m -> Triple(m, matchLevel(m.value), kotlin.math.abs(m.range.first - keywordIdx)) }
            .sortedWith(compareByDescending<Triple<MatchResult, Int, Int>> { it.second }.thenBy { it.third })
            .first().first
        return Hit(winner.value, Kind.CODE, winner.range.first)
    }

    /**
     * 候选码匹配度。借鉴 XposedSmsCode：
     *   6 位纯数字最高（最常见的 OTP 长度）
     *   4 位纯数字次之
     *   其它纯数字
     *   数字+字母混合
     *   纯字母最低（误命中风险大）
     */
    private fun matchLevel(s: String): Int = when {
        s.matches(Regex("^[0-9]{6}$")) -> 5
        s.matches(Regex("^[0-9]{4}$")) -> 4
        s.matches(Regex("^[0-9]+$")) -> 3
        s.matches(Regex("^[A-Za-z]+$")) -> 1
        else -> 2  // 数字字母混合
    }

    private fun bestLink(haystack: String): Hit? {
        val matches = URL_REGEX.findAll(haystack).toList()
        val verifyish = matches.firstOrNull { m ->
            val u = m.value.lowercase()
            LINK_VERIFY_HINTS.any { u.contains(it) }
        } ?: return null
        return Hit(verifyish.value, Kind.LINK, verifyish.range.first)
    }
}
