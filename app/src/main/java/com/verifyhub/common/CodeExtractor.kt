package com.verifyhub.common

/**
 * 从短信 / 邮件正文里提取验证码或验证链接。
 *
 * 提取流程：
 *   1) 先扫一遍原文找验证链接（带 verify/confirm/login 等关键词的 URL）。
 *   2) 把正文里的 邮件地址 + URL 涂掉，避免它们的 local-part / path 被
 *      当成验证码。比如 `anglin.liu@gmail.com` 里的 "anglin"。
 *   3) 在洗过的字符串里找验证码：优先选靠近"验证/code"等关键词的数字串。
 */
object CodeExtractor {

    data class Hit(
        val value: String,
        val kind: Kind,
        val index: Int,
    )

    enum class Kind { CODE, LINK }

    private val KEYWORDS = listOf(
        "code", "verification", "verify", "otp", "passcode", "pin",
        "one-time", "one time", "security code", "auth", "2fa", "two-factor",
        "confirm", "login", "sign in", "sign-in", "enter",
        "验证码", "校验码", "动态码", "动态密码", "验证", "校验", "口令", "认证码"
    )

    // 在两侧用 \b 之外再禁掉 .@-_+ 这些常出现在邮箱/URL/标识符里的字符，
    // 这样 "anglin.liu@gmail.com" 里的 anglin 不会被当作 6 位字母码命中。
    private val CODE_REGEX = Regex(
        "(?<![A-Za-z0-9._@\\-+/])([0-9]{4,8}|[A-Z0-9]{4,8}|[A-Za-z0-9]{6,8})(?![A-Za-z0-9._@\\-+/])"
    )

    // 邮箱 / URL —— 用来在提取验证码前涂掉。
    private val EMAIL_REGEX = Regex("[A-Za-z0-9._%+\\-]+@[A-Za-z0-9.\\-]+\\.[A-Za-z]{2,}")
    private val URL_REGEX = Regex(
        "https?://[A-Za-z0-9._~%/?#\\-=&+]+",
        RegexOption.IGNORE_CASE
    )

    private val LINK_VERIFY_HINTS = listOf(
        "verify", "verification", "confirm", "activate", "magic", "signin",
        "sign-in", "login", "authenticate", "auth", "validate", "reset",
        "yanzheng", "queren"
    )

    fun extract(body: String?, subject: String? = null): Hit? {
        if (body.isNullOrBlank()) return null
        val haystack = buildString {
            if (!subject.isNullOrBlank()) appendLine(subject)
            append(body)
        }

        // 1) 先看链接
        val link = bestLink(haystack)

        // 2) 涂掉邮件地址与所有 URL（不仅是验证链接，避免域名也参与匹配）
        val sanitized = haystack
            .replace(EMAIL_REGEX, " ")
            .replace(URL_REGEX, " ")

        val lower = sanitized.lowercase()
        val hasKeyword = KEYWORDS.any { lower.contains(it) }

        // 3) 有关键词时优先取数字码
        if (hasKeyword) {
            bestCode(sanitized, lower)?.let { return it }
        }

        // 4) 有验证链接就返回
        if (link != null) return link

        // 5) 没关键词也没链接：仅在正文较短时兜底试一次
        if (sanitized.length <= 200) {
            return bestCode(sanitized, lower)
        }
        return null
    }

    private fun bestCode(haystack: String, lower: String): Hit? {
        val matches = CODE_REGEX.findAll(haystack).toList()
        if (matches.isEmpty()) return null

        val scored = matches.map { m ->
            val v = m.value
            val idx = m.range.first
            val keywordDistance = KEYWORDS
                .mapNotNull { kw ->
                    val k = lower.indexOf(kw)
                    if (k < 0) null else Math.abs(k - idx)
                }
                .minOrNull() ?: Int.MAX_VALUE
            // 纯字母走的多半是单词，先扣 1000
            val letterPenalty = if (v.all { it.isLetter() }) 1000 else 0
            // 4 位 19xx/20xx 当年份，没有关键词时强烈扣分
            val yearPenalty = if (v.length == 4 && (v.startsWith("19") || v.startsWith("20")) &&
                v.toIntOrNull()?.let { it in 1900..2099 } == true
            ) 500 else 0
            // 纯数字优先
            val digitBoost = if (v.all { it.isDigit() }) -50 else 0
            Triple(m, keywordDistance + letterPenalty + yearPenalty + digitBoost, v)
        }.sortedBy { it.second }

        val winner = scored.first().first
        return Hit(winner.value, Kind.CODE, winner.range.first)
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
