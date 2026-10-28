package com.gzucm.pocket

import com.gzucm.pocket.ui.login.LoginFlow
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 登录状态机回归测试。
 *
 * 事故复现条件：一卡通域下的 JSESSIONID 在 Path=/user，用根路径取永远为空。
 * 旧实现要求「根路径有 JSESSIONID」才结束，于是永久卡在「正在同步校园卡」。
 * 现在只按 URL 判定，这些用例确保任何一种真实跳转序列都能走到终点。
 */
class LoginFlowTest {

    // 教务 → CAS → 回跳
    private val jwLogin = "https://jw.gzucm.edu.cn/new/ssoLogin"
    private val authPage = "https://authserver.gzucm.edu.cn/authserver/login?service=x"
    private val jwWelcome = "https://jw.gzucm.edu.cn/new/welcome.page?ui=new"
    private val jwIndex = "https://jw.gzucm.edu.cn/new/index"

    // 一卡通
    private val ecardSsoEntry = "https://ecard.gzucm.edu.cn/sso/login?plat=gzzyy"
    private val ecardSsoIndex = "https://ecard.gzucm.edu.cn/sso/index"
    private val ecardUserIndex = "https://ecard.gzucm.edu.cn/user/index"
    private val ecardPower = "https://ecard.gzucm.edu.cn/user/powerfee/index"

    // ── 第一步：教务 → 一卡通 ───────────────────────────────────────────

    @Test
    fun `教务登录成功后应跳一卡通`() {
        assertTrue(LoginFlow.shouldHopToEcard(jwWelcome, hasJwSession = true, pageFinished = true))
        assertTrue(LoginFlow.shouldHopToEcard(jwIndex, hasJwSession = true, pageFinished = true))
    }

    @Test
    fun `没有教务会话时不跳`() {
        assertFalse(LoginFlow.shouldHopToEcard(jwWelcome, hasJwSession = false, pageFinished = true))
    }

    @Test
    fun `还停在认证页时不跳`() {
        assertFalse(LoginFlow.shouldHopToEcard(authPage, hasJwSession = true, pageFinished = true))
    }

    @Test
    fun `页面没加载完不跳`() {
        // onPageStarted 时 cookie 尚未写入，过早判定会导致拿不到会话
        assertFalse(LoginFlow.shouldHopToEcard(jwWelcome, hasJwSession = true, pageFinished = false))
    }

    // ── 第二步：一卡通何时算完成（这里是旧 bug 所在）─────────────────────

    @Test
    fun `落到一卡通业务页就算完成`() {
        assertTrue(LoginFlow.canFinishEcard(ecardUserIndex, pageFinished = true))
        assertTrue(LoginFlow.canFinishEcard(ecardPower, pageFinished = true))
    }

    @Test
    fun `sso 中转页不算完成`() {
        assertFalse(LoginFlow.canFinishEcard(ecardSsoEntry, pageFinished = true))
        assertFalse(LoginFlow.canFinishEcard(ecardSsoIndex, pageFinished = true))
    }

    @Test
    fun `还在认证页不算完成`() {
        assertFalse(LoginFlow.canFinishEcard(authPage, pageFinished = true))
    }

    @Test
    fun `不在教务或一卡通域的地址不算完成`() {
        assertFalse(LoginFlow.canFinishEcard("https://jw.gzucm.edu.cn/new/index", pageFinished = true))
        assertFalse(LoginFlow.canFinishEcard("about:blank", pageFinished = true))
    }

    /**
     * 核心回归：即便一卡通的 cookie 完全拿不到（事实就是根路径取不到），
     * 只要 URL 到站也必须放行 —— 否则就会卡死。
     */
    @Test
    fun `cookie 缺失也不该卡住`() {
        // canFinishEcard 的签名里根本没有 cookie 参数，这就是修复本身
        assertTrue(LoginFlow.canFinishEcard(ecardUserIndex, pageFinished = true))
    }

    // ── 完整序列走一遍 ──────────────────────────────────────────────────

    @Test
    fun `典型跳转序列能一路走到完成`() {
        val seq = listOf(jwLogin, authPage, jwWelcome, ecardSsoEntry, ecardSsoIndex, ecardUserIndex)
        var stageEcard = false
        var finished = false
        for (url in seq) {
            if (!stageEcard) {
                if (LoginFlow.shouldHopToEcard(url, hasJwSession = true, pageFinished = true)) {
                    stageEcard = true
                }
            } else if (LoginFlow.canFinishEcard(url, pageFinished = true)) {
                finished = true
                break
            }
        }
        assertTrue("整条序列必须能走到完成，否则就是卡死", finished)
    }
}