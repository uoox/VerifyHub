package com.verifyhub.common

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [CodeExtractor] 匹配规则的回归测试。
 *
 * 新增/调整规则时，把代表性的短信样本加进来，保证旧用例不退化。
 * 当用户反馈某条短信识别错误时，先在这里加一条断言复现，再改规则。
 */
class CodeExtractorTest {

    private fun code(body: String): String? {
        val hit = CodeExtractor.extract(body)
        return if (hit?.kind == CodeExtractor.Kind.CODE) hit.value else null
    }

    // —— 中文基础场景 ——

    @Test fun chinese_basic_6digit() {
        assertEquals("123456", code("【某App】您的验证码为123456，5分钟内有效，请勿泄露。"))
    }

    @Test fun chinese_colon_4digit() {
        assertEquals("8888", code("验证码：8888，请在页面输入完成登录。"))
    }

    @Test fun chinese_code_before_keyword() {
        assertEquals("654321", code("654321 是您的本次登录验证码。"))
    }

    @Test fun chinese_dynamic_password() {
        assertEquals("246810", code("您的动态密码为246810，请勿告知他人。"))
    }

    @Test fun traditional_chinese() {
        assertEquals("135790", code("您的驗證碼為135790，請勿洩漏給他人。"))
    }

    // —— 英文场景 ——

    @Test fun english_code_is() {
        assertEquals("482913", code("Your verification code is 482913. It expires in 10 minutes."))
    }

    @Test fun english_otp() {
        assertEquals("9021", code("9021 is your OTP for login. Do not share it."))
    }

    @Test fun english_code_at_end_of_sentence() {
        // 句末紧跟句号的码不能被边界吃掉（旧版本的回归点）
        assertEquals("482913", code("Your verification code is 482913."))
    }

    @Test fun english_weak_code_keyword() {
        // 没有强关键词，仅靠裸词 "code"（前词非干扰词）即可命中
        assertEquals("55571", code("Your Telegram code is 55571"))
    }

    @Test fun google_prefixed_format() {
        assertEquals("773829", code("G-773829 is your Google verification code."))
    }

    @Test fun whatsapp_split_format() {
        assertEquals("123456", code("123-456 is your WhatsApp code. Don't share it with anyone."))
    }

    @Test fun spaced_split_format() {
        assertEquals("445566", code("您的验证码是 445 566，请尽快输入。"))
    }

    // —— 防误命中场景 ——

    @Test fun no_keyword_means_no_code() {
        // 没有验证码关键词，纯提醒短信不该被抓
        assertNull(code("您的账户于2026年1月1日成功登录，如非本人操作请修改密码。"))
    }

    @Test fun masked_phone_not_captured_as_code() {
        // 掩码手机号 8888 不该被当成验证码；这条短信本身没有真正的码
        assertNull(code("验证码已发送至 138****8888，请注意查收。"))
    }

    @Test fun masked_phone_with_real_code() {
        // 同时有掩码号和真码时，应取真码而不是掩码段
        assertEquals("246802", code("验证码 246802 已发送至 138****8888，请查收。"))
    }

    @Test fun pure_alpha_word_not_a_code() {
        // 纯字母单词不算候选码
        assertNull(code("Please verify your account to continue."))
    }

    @Test fun email_localpart_not_captured() {
        assertEquals("135246", code("您的验证码为135246。如有疑问联系 support12345@example.com。"))
    }

    @Test fun promo_qualifier_does_not_trigger_weak_code() {
        // "promo code" 不算关键词，但同句有强关键词时仍取真验证码
        assertEquals("333444", code("Use promo code at checkout. Your verification code is 333444."))
    }

    @Test fun area_code_not_a_keyword() {
        assertNull(code("Your area code is 415 now, nothing else here."))
    }

    @Test fun ip_address_segments_not_captured() {
        assertEquals("778812", code("Server 192.168.1.100 verification code 778812 ready."))
    }

    // —— 链接通道 ——

    @Test fun magic_link_without_keyword() {
        val hit = CodeExtractor.extract("Click to sign in: https://app.example.com/verify?token=abc123")
        assertEquals(CodeExtractor.Kind.LINK, hit?.kind)
        assertTrue(hit!!.value.startsWith("https://app.example.com/verify"))
    }

    @Test fun signin_magic_link() {
        val hit = CodeExtractor.extract("Tap to continue: https://auth.example.com/signin/xyz")
        assertEquals(CodeExtractor.Kind.LINK, hit?.kind)
    }

    @Test fun plain_account_link_is_not_a_verification_link() {
        // 普通账户管理链接（无验证码关键词、无验证语义）不该被当成验证链接，
        // 否则邮件通道会误划掉这封正常邮件的通知。
        assertNull(CodeExtractor.extract("Manage your subscription here: https://shop.example.com/my-account/settings"))
    }

    @Test fun plain_secure_host_link_is_not_a_verification_link() {
        assertNull(CodeExtractor.extract("Your receipt is ready: https://secure.example.com/orders/8891"))
    }

    @Test fun password_reset_link_is_not_captured() {
        // 密码重置链接不是「验证码」，也不该触发通知划除。
        assertNull(CodeExtractor.extract("Forgot your password? Reset it at https://example.com/reset-password?u=42"))
    }

    // —— 诊断接口 ——

    @Test fun diagnose_reports_keyword_and_candidate() {
        val d = CodeExtractor.diagnose("您的验证码为123456，请勿泄露。")
        assertTrue(d.matched)
        assertEquals("123456", d.hit?.value)
        assertTrue(d.matchedKeywords.contains("验证码"))
        assertTrue(d.candidates.any { it.chosen && it.value == "123456" })
    }

    @Test fun diagnose_explains_miss_when_no_keyword() {
        val d = CodeExtractor.diagnose("您的账户余额为123456元。")
        // 有候选码 123456，但没命中关键词 → 不抓
        assertNull(d.hit)
        assertTrue(d.matchedKeywords.isEmpty())
        assertTrue(d.candidates.any { it.value == "123456" })
    }
}
