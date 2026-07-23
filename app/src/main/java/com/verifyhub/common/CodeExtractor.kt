package com.verifyhub.common

/**
 * 从短信 / 邮件正文里提取验证码或验证链接。
 *
 * 设计参考 tianma8023/XposedSmsCode 的 `SmsCodeUtils.getSmsCode`，并在此之上做了增强：
 *   1) 必须命中「验证码类」关键词。仅出现登录、确认这类宽泛词不算——否则一封
 *      "您的账户于2026年..."的登录提醒会被误判，把 2026 当验证码。
 *   2) 命中关键词后，在文本里搜所有候选码，按 (匹配度, 距「最近一个」关键词的字符数) 排序：
 *        匹配度  6位纯数字 > 4位纯数字 > 其它纯数字 > 数字+字母混合
 *        距离    小者优先；多关键词时取离候选最近的那个
 *   3) 邮箱、URL 提前涂白，避免地址 local-part / 域名段被当成码。
 *   4) URL 单独走链接通道，命中 verify / confirm / magic 等词的链接返回 [Kind.LINK]。
 *
 * v0.3.0 增强点：
 *   - 关键词覆盖大幅扩充（含繁体、日韩、西法德常见写法，以及银行/支付高频词）。
 *   - 候选码支持「字母前缀」格式 G-123456（Google / 部分 App）。
 *   - 候选码支持「分组数字」格式 123-456、123 456（WhatsApp / Telegram 等）。
 *   - 候选码必须含数字，剔除 please / verify 这类纯字母误命中。
 *   - 边界字符黑名单加入 * # %，避免把掩码手机号 138****8888 的 8888 当验证码。
 *   - 距离按「最近关键词」计算，多关键词短信更准。
 *   - 新增 [diagnose] 诊断接口，供 App 内「提取测试」页排查规则命中情况。
 */
object CodeExtractor {

    data class Hit(
        val value: String,
        val kind: Kind,
        val index: Int,
    )

    enum class Kind { CODE, LINK }

    /** 单个候选码及其评分细节，供诊断页展示「为什么是它 / 为什么不是它」。 */
    data class Candidate(
        /** 归一化后的码（分组/前缀格式会被还原成纯码）。 */
        val value: String,
        /** 在原文中实际匹配到的串（可能带分隔符或前缀）。 */
        val raw: String,
        /** 在涂白后文本中的起始下标。 */
        val index: Int,
        /** 匹配度，见 [matchLevel]。 */
        val level: Int,
        /** 距最近关键词的字符距离；无关键词时为 -1。 */
        val distance: Int,
        /** 是否被选中作为最终结果。 */
        val chosen: Boolean,
    )

    /** [diagnose] 的完整结果，方便「提取测试」页和导出诊断报告。 */
    data class Diagnosis(
        val matched: Boolean,
        val hit: Hit?,
        val matchedKeywords: List<String>,
        val candidates: List<Candidate>,
        val links: List<String>,
    )

    /** 距关键词在此字符数内的候选才算「贴近」，优先从中选。 */
    private const val NEAR_WINDOW = 40

    /**
     * 「验证码」关键词。只用真正暗示"这里有一串验证码"的词。
     * 不收 "login" / "confirm" / "auth" 这种泛词——它们在大量非验证短信里也出现。
     * 全部以小写存储，匹配前会把文本 lowercase。
     */
    private val KEYWORDS = listOf(
        // —— 简体中文 ——
        "验证码", "校验码", "检验码", "确认码", "激活码", "动态码", "安全码", "验证密码",
        "短信验证码", "动态验证码", "登录验证码", "注册验证码", "登陆验证码", "图形验证码",
        "验证代码", "校验代码", "确认代码", "激活代码", "动态代码", "安全代码",
        "短信口令", "动态密码", "随机码", "动态口令", "登入码", "认证码", "识别码", "交易码",
        "登录码", "登陆码", "注册码", "绑定码", "验证号", "校验号", "一次性密码", "一次性验证码",
        "口令为", "口令是", "口令:", "口令：", "验证字符",
        // —— 繁体中文 ——
        "驗證碼", "校驗碼", "檢驗碼", "確認碼", "激活碼", "動態碼", "安全碼", "驗證代碼",
        "動態密碼", "隨機碼", "認證碼", "識別碼", "交易碼", "登入碼", "一次性密碼", "簡訊驗證碼",
        // —— 日语 ——
        "認証コード", "確認コード", "認証番号", "ワンタイムパスワード",
        // —— 韩语 ——
        "인증번호", "인증 번호", "인증코드", "인증 코드", "확인코드", "확인 코드",
        // —— 英文 ——
        "verification code", "verify code", "verification pin", "verfication code",
        "security code", "passcode", "pass code", "one-time", "one time password",
        "one-time password", "one-time passcode", "onetime password", "otp", "otp code",
        "auth code", "authentication code", "confirmation code", "confirm code",
        "login code", "log-in code", "access code", "pin code", "sign-in code",
        "sign in code", "single-use code", "temporary password", "temporary code",
        "activation code", "validation code", "2fa", "two-factor", "two factor", "your pin",
        // —— 西 / 法 / 德 / 葡 / 意 ——
        "código de verificación", "código de verificacion", "código de confirmación",
        "code de vérification", "code de verification", "code de confirmation",
        "bestätigungscode", "bestaetigungscode", "verifizierungscode", "sicherheitscode",
        "código de verificação", "codice di verifica", "codice di conferma",
    )

    // 边界设计说明：
    //   「硬」黑名单 _@+/*#% 与字母数字一律禁止紧贴（掩码号 138****8888、邮件/路径等）。
    //   「软」字符 . 和 - 只有在「紧跟字母数字」时才禁止（拦住 192.168 / 2024-01 这类），
    //   句末的 "123456." 仍可命中。
    private const val LEAD = "(?<![A-Za-z0-9_@+/*#%])(?<![A-Za-z0-9][.\\-])"
    private const val TRAIL = "(?![A-Za-z0-9_@+/*#%])(?![.\\-][A-Za-z0-9])"

    /**
     * 连续字母数字候选码：4-8 位。命中后还会再过滤掉纯字母串（验证码几乎必含数字）。
     */
    private val CODE_REGEX = Regex(LEAD + "([A-Za-z0-9]{4,8})" + TRAIL)

    /** 字母前缀码：G-123456（Google）/ GF-12345 等。归一化时只取数字段。 */
    private val PREFIXED_CODE_REGEX = Regex(
        "(?<![A-Za-z0-9])([A-Za-z]{1,4})-([0-9]{4,8})" + TRAIL
    )

    /** 分组数字码：123-456 / 123 456 / 1234 5678（WhatsApp / Telegram 等）。归一化时拼接数字。 */
    private val SPLIT_CODE_REGEX = Regex(
        "(?<![A-Za-z0-9\\-*#/])(?<![A-Za-z0-9]\\.)" +
            "([0-9]{3,4})[ \\-]([0-9]{3,4})" +
            "(?![A-Za-z0-9\\-*#/])(?!\\.[A-Za-z0-9])"
    )

    /**
     * 弱关键词：英文裸词 "code"。它只在「前一个单词不是 promo/area/zip 这类干扰词」时才算命中，
     * 用来覆盖 "123-456 is your WhatsApp code" / "Your code: 123456" 这类没有强关键词的写法。
     */
    private val WEAK_CODE_REGEX = Regex("\\bcode\\b")
    private val PREV_WORD_REGEX = Regex("([a-z]+)\\s*$")
    private val WEAK_CODE_BLOCK = setOf(
        "promo", "promotional", "coupon", "discount", "area", "zip", "postal", "post",
        "country", "dialing", "dial", "error", "qr", "bar", "barcode", "scan", "redeem",
        "gift", "voucher", "referral", "invite", "invitation", "store", "product",
        "color", "colour", "source", "dress", "sort", "swift", "iban", "morse",
        "marketing", "tracking", "offer",
    )

    private val EMAIL_REGEX = Regex("[A-Za-z0-9._%+\\-]+@[A-Za-z0-9.\\-]+\\.[A-Za-z]{2,}")
    private val URL_REGEX = Regex(
        "https?://[A-Za-z0-9._~%/?#\\-=&+]+",
        RegexOption.IGNORE_CASE
    )

    // 链接通道命中词。只保留「几乎必然是验证/魔法链接」的词。
    // 刻意剔除了 auth / secure / account / reset / login 这类过宽的词：它们大量出现在
    // 普通营销/账户管理邮件的 URL 里（secure.x.com、/my-account、/reset-password…），
    // 一旦误命中，邮件通道会把这封正常邮件当成验证链接并「划掉它的通知」，代价很大。
    // 需要覆盖某站点的登录魔法链接时，宁可在此按域名/路径精确补充，也不放宽泛词。
    private val LINK_VERIFY_HINTS = listOf(
        "verify", "verification", "confirm", "confirmation", "activate", "activation",
        "magic", "magiclink", "signin", "sign-in", "authenticate",
        "validate", "validation", "token", "otp", "verifyemail", "verify-email",
    )

    fun extract(body: String?, subject: String? = null): Hit? = diagnose(body, subject).hit

    /**
     * 完整诊断：返回命中的码/链接、命中的关键词、所有候选码及其评分、所有链接。
     * 「提取测试」页用它把规则匹配的全过程摊开给用户看。
     */
    fun diagnose(body: String?, subject: String? = null): Diagnosis {
        if (body.isNullOrBlank()) {
            return Diagnosis(false, null, emptyList(), emptyList(), emptyList())
        }
        val haystack = buildString {
            if (!subject.isNullOrBlank()) appendLine(subject)
            append(body)
        }

        // 链接通道独立，不依赖关键词命中（"please verify here: https://..." 这种）。
        val links = URL_REGEX.findAll(haystack).map { it.value }.toList()
        val link = bestLink(haystack)

        // 涂掉邮件地址与所有 URL，再去找验证码。
        val sanitized = haystack
            .replace(EMAIL_REGEX, " ")
            .replace(URL_REGEX, " ")
        val lower = sanitized.lowercase()

        val kwHits = keywordHits(lower)
        val kwIndices = kwHits.map { it.second }
        val raws = findCandidates(sanitized)

        // 关键词命中是「这是验证码短信」的硬性条件，没有就不抓码（仍可能走链接通道）。
        var winner: Raw? = null
        if (kwIndices.isNotEmpty() && raws.isNotEmpty()) {
            val scored = raws.map { it to distanceTo(kwIndices, it.index) }
            val near = scored.filter { it.second <= NEAR_WINDOW }
            val pool = if (near.isNotEmpty()) near else scored
            winner = pool
                .sortedWith(
                    compareByDescending<Pair<Raw, Int>> { matchLevel(it.first.normalized) }
                        .thenBy { it.second }
                )
                .first().first
        }

        val codeHit = winner?.let { Hit(it.normalized, Kind.CODE, it.index) }
        val hit = codeHit ?: link

        val candidates = raws.map { r ->
            Candidate(
                value = r.normalized,
                raw = r.raw,
                index = r.index,
                level = matchLevel(r.normalized),
                distance = if (kwIndices.isEmpty()) -1 else distanceTo(kwIndices, r.index),
                chosen = winner != null && r.index == winner.index && r.normalized == winner.normalized,
            )
        }

        return Diagnosis(
            matched = hit != null,
            hit = hit,
            matchedKeywords = kwHits.map { it.first }.distinct(),
            candidates = candidates,
            links = links,
        )
    }

    /** 人类可读的匹配度标签，供诊断页/报告使用。 */
    fun levelLabel(level: Int): String = when (level) {
        5 -> "纯数字·6位"
        4 -> "纯数字·4位"
        3 -> "纯数字"
        2 -> "数字字母混合"
        else -> "其它"
    }

    private data class Raw(val raw: String, val normalized: String, val index: Int, val range: IntRange)

    private fun findCandidates(text: String): List<Raw> {
        val out = mutableListOf<Raw>()
        val claimed = mutableListOf<IntRange>()

        fun overlaps(r: IntRange) = claimed.any { it.first <= r.last && r.first <= it.last }
        fun claim(range: IntRange, raw: String, normalized: String) {
            if (normalized.length !in 4..8) return
            if (overlaps(range)) return
            claimed.add(range)
            out.add(Raw(raw, normalized, range.first, range))
        }

        // 1) 字母前缀码 G-123456（最具体，先认领范围）
        for (m in PREFIXED_CODE_REGEX.findAll(text)) {
            claim(m.range, m.value.trim(), m.groupValues[2])
        }
        // 2) 分组数字码 123-456 / 123 456
        for (m in SPLIT_CODE_REGEX.findAll(text)) {
            claim(m.range, m.value.trim(), m.groupValues[1] + m.groupValues[2])
        }
        // 3) 连续字母数字码（必须含数字，剔除纯字母词）
        for (m in CODE_REGEX.findAll(text)) {
            val v = m.value
            if (v.none { it.isDigit() }) continue
            claim(m.range, v, v)
        }

        return out.sortedBy { it.index }
    }

    private fun distanceTo(kwIndices: List<Int>, idx: Int): Int =
        kwIndices.minOf { kotlin.math.abs(idx - it) }

    private fun keywordHits(lower: String): List<Pair<String, Int>> {
        val hits = mutableListOf<Pair<String, Int>>()
        for (kw in KEYWORDS) {
            var from = 0
            while (true) {
                val i = lower.indexOf(kw, from)
                if (i < 0) break
                hits.add(kw to i)
                from = i + kw.length
            }
        }
        // 弱关键词 "code"：前一个单词在黑名单里则跳过（promo code / area code…）。
        for (m in WEAK_CODE_REGEX.findAll(lower)) {
            val prev = PREV_WORD_REGEX.find(lower.substring(0, m.range.first))?.groupValues?.get(1) ?: ""
            if (prev in WEAK_CODE_BLOCK) continue
            hits.add("code" to m.range.first)
        }
        return hits
    }

    /**
     * 候选码匹配度。借鉴 XposedSmsCode：
     *   6 位纯数字最高（最常见的 OTP 长度）
     *   4 位纯数字次之
     *   其它纯数字
     *   数字+字母混合最低（误命中风险大）
     */
    private fun matchLevel(code: String): Int = when {
        code.matches(Regex("^[0-9]{6}$")) -> 5
        code.matches(Regex("^[0-9]{4}$")) -> 4
        code.matches(Regex("^[0-9]+$")) -> 3
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
