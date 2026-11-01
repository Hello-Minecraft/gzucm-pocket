package com.gzucm.pocket

import com.gzucm.pocket.ui.login.LoginFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 两套系统的登录凭证不是同一个 cookie。
 *
 * 背景（2026-11-01 致辉报「重新登录了还是无法获取课表」）：
 *   抓 cookie 的 `harvest()` 用 `containsKey("JSESSIONID")` 判断「抓全了没」。
 *   但研究生是 ASP.NET，凭证在 `__LOGINCOOKIE__`，**没有 JSESSIONID** ——
 *   于是永远命中不了，走兜底合并。
 *
 *   更糟的是 ASP.NET 会先把 `__LOGINCOOKIE__` 写成**空串**占位。
 *   空串也 `containsKey`，上层以为登录成功，拿空凭证去请求，
 *   课表接口返回空 → App 报「无法获取课表」。
 *
 * 这里锁的是 `pickSessionSample` —— `harvest()` 真正调用的入口。
 */
class LoginCookieTest {

    private val yjs = "yjsglxt.gzucm.edu.cn"
    private val jw = "jw.gzucm.edu.cn"

    // ── 凭证名必须按域名区分 ────────────────────────────────

    @Test
    fun `研究生用 LOGINCOOKIE，本科用 JSESSIONID`() {
        assertEquals("__LOGINCOOKIE__", LoginFlow.sessionCookieName(yjs))
        assertEquals("JSESSIONID", LoginFlow.sessionCookieName(jw))
    }

    @Test
    fun `一卡通也用 JSESSIONID`() {
        assertEquals("JSESSIONID", LoginFlow.sessionCookieName("ecard.gzucm.edu.cn"))
    }

    // ── 空串不能当成登录成功 ────────────────────────────────

    @Test
    fun `__LOGINCOOKIE__ 是空串时不算已登录`() {
        // 这就是致辉机器上的真实状态
        val realWorld = mapOf(
            "ASP.NET_SessionId" to "wi3kwkm33uyoppvfxmxqzvyv",
            "__LOGINCOOKIE__" to "",
        )
        assertFalse(
            "空串等于没登录，必须判 false",
            LoginFlow.hasSession(yjs, realWorld),
        )
    }

    @Test
    fun `__LOGINCOOKIE__ 有值时才算已登录`() {
        val ok = mapOf(
            "ASP.NET_SessionId" to "abc",
            "__LOGINCOOKIE__" to "20261120283|somehash",
        )
        assertTrue(LoginFlow.hasSession(yjs, ok))
    }

    @Test
    fun `本科没有 JSESSIONID 时不算已登录`() {
        val noSession = mapOf("token" to "node01xxx")
        assertFalse(LoginFlow.hasSession(jw, noSession))
    }

    // ── 采样挑选：必须挑到带凭证的那份 ──────────────────────

    @Test
    fun `多个路径采样时，挑出带 LOGINCOOKIE 的那份`() {
        val samples = listOf(
            // 根路径：只有会话 id，凭证还是空串
            mapOf("ASP.NET_SessionId" to "abc", "__LOGINCOOKIE__" to ""),
            // 业务路径：凭证有值
            mapOf("ASP.NET_SessionId" to "abc", "__LOGINCOOKIE__" to "real-token"),
        )
        val picked = LoginFlow.pickSessionSample(yjs, samples)
        assertEquals("必须挑到带凭证的那份", "real-token", picked["__LOGINCOOKIE__"])
        assertTrue(LoginFlow.hasSession(yjs, picked))
    }

    @Test
    fun `全都只有空串时，兜底结果里不能带空值`() {
        val samples = listOf(
            mapOf("ASP.NET_SessionId" to "abc", "__LOGINCOOKIE__" to ""),
            mapOf("ASP.NET_SessionId" to "abd", "__LOGINCOOKIE__" to "  "),
        )
        val picked = LoginFlow.pickSessionSample(yjs, samples)
        assertFalse(
            "空凭证不能出现在结果里，否则上层会误判",
            picked.containsKey("__LOGINCOOKIE__"),
        )
        assertFalse(LoginFlow.hasSession(yjs, picked))
    }

    @Test
    fun `本科照旧挑 JSESSIONID`() {
        val samples = listOf(
            mapOf("token" to "t1"),
            mapOf("JSESSIONID" to "5EB1EE2C", "token" to "t2"),
        )
        val picked = LoginFlow.pickSessionSample(jw, samples)
        assertEquals("5EB1EE2C", picked["JSESSIONID"])
        assertTrue(LoginFlow.hasSession(jw, picked))
    }

    @Test
    fun `采样为空列表时不崩`() {
        val picked = LoginFlow.pickSessionSample(yjs, emptyList())
        assertTrue(picked.isEmpty())
        assertFalse(LoginFlow.hasSession(yjs, picked))
    }

    // ── 回归：旧逻辑为什么会挂 ──────────────────────────────

    @Test
    fun `旧逻辑 containsKey 会把空串当成功，新逻辑不会`() {
        val realWorld = mapOf("__LOGINCOOKIE__" to "")

        // 旧写法（模拟）
        val oldJudgement = realWorld.containsKey("__LOGINCOOKIE__")
        assertTrue("旧逻辑确实会误判成 true", oldJudgement)

        // 新写法
        assertFalse("新逻辑判 false", LoginFlow.hasSession(yjs, realWorld))
    }

    // ── 更精准：pickSessionSample 必须按域名选凭证名 ─────────
    //
    // 教训：上一版注入 bug（把 key 写死成 JSESSIONID）只红了 1 项，
    // 因为 hasSession 自己用 isNullOrBlank 兜住了。
    // 下面这条直接断言「研究生采样时，不能因为看到 JSESSIONID 就返回」。

    @Test
    fun `研究生采样不会因为出现 JSESSIONID 就提前返回`() {
        /*
         * 构造一个刁钻场景：某个路径下同时有 JSESSIONID 和空的 __LOGINCOOKIE__，
         * 另一个路径下有真正的 __LOGINCOOKIE__。
         *
         * 写死 JSESSIONID 的旧逻辑会在第一份就 return，
         * 把真正有效的那份丢掉 → 上层拿到空凭证 → 课表拿不到。
         */
        val samples = listOf(
            mapOf("JSESSIONID" to "dummy", "__LOGINCOOKIE__" to ""),
            mapOf("JSESSIONID" to "dummy", "__LOGINCOOKIE__" to "real-token"),
        )
        val picked = LoginFlow.pickSessionSample(yjs, samples)
        assertEquals(
            "必须跳过只有空凭证的第一份，拿到真正有效的第二份",
            "real-token",
            picked["__LOGINCOOKIE__"],
        )
    }

    @Test
    fun `本科采样不会因为出现 LOGINCOOKIE 就提前返回`() {
        val samples = listOf(
            mapOf("__LOGINCOOKIE__" to "yjs-junk", "JSESSIONID" to ""),
            mapOf("__LOGINCOOKIE__" to "yjs-junk", "JSESSIONID" to "real-jw"),
        )
        val picked = LoginFlow.pickSessionSample(jw, samples)
        assertEquals("real-jw", picked["JSESSIONID"])
    }
}