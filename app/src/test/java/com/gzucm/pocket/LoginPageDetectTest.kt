package com.gzucm.pocket

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 会话过期时学校**不返 401**，而是 200 + 各种形态的「没登录」响应：
 *
 * - 页面级请求：HTML 登录页
 * - 接口级请求：JSON `{"code":-401,"message":"尚未登录，请先登录"}`
 *
 * 这两种都必须被认成 Unauthorized，否则上层会拿去解析，
 * 报出「数据格式变了」——用户完全看不懂，实际只要重新登录。
 *
 * 这里直接调 internal 的 looksLikeLoginPage，锁住判据。
 */
class LoginPageDetectTest {

    private fun detect(text: String): Boolean =
        com.gzucm.pocket.core.Net.looksLikeLoginPage(text)

    @Test
    fun `HTML 登录页要认出来`() {
        assertTrue(detect("<html><title>统一身份认证平台</title><body>账号登录</body></html>"))
        assertTrue(detect("<html><form action=\"https://authserver.gzucm.edu.cn/authserver/login\"><input name=\"execution\"></form></html>"))
    }

    @Test
    fun `JSON 未登录要认出来`() {
        assertTrue(detect("""{"code":-401,"message":"尚未登录，请先登录"}"""))
        assertTrue(detect("""{"code":-401,"message":"对不起，您的帐号[2023014093]已在别处登录，您已被迫退出。"}"""))
    }

    @Test
    fun `正常 JSON 不能误判`() {
        assertEquals(false, detect("""{"code":0,"data":[[{"type":"zc","value":"1"}]]}"""))
        assertEquals(false, detect("""{"code":0,"rows":[{"kcmc":"中医基础理论"}]}"""))
        assertEquals(false, detect("""{"code":-1,"message":"参数错误"}"""))
    }

    @Test
    fun `空响应不误判`() {
        assertEquals(false, detect(""))
        assertEquals(false, detect("   "))
    }
}
