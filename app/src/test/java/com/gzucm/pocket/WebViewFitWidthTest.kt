package com.gzucm.pocket

import com.gzucm.pocket.core.Campus
import com.gzucm.pocket.ui.login.LoginFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 研究生登录页的适配宽度策略。
 *
 * 背景（2026-11-01 致辉报的 bug）：
 *   研究生系统是按 PC 设计的固定 1280 宽页面，手机上打开时左侧被裁掉，
 *   而用户名输入框恰好在左边 —— 用户根本没法登录。
 *
 * 这里断言的是 [LoginFlow] 里**真实被 LoginScreen 调用**的那几个函数，
 * 不是测试里另抄一份逻辑（那样等于测自己，改实现也不会红）。
 */
class WebViewFitWidthTest {

    @Test
    fun `只有研究生学段需要把页面缩进屏宽`() {
        // 本科教务是自适应页面，用户一直用得好好的，不能被强制缩放
        assertFalse(
            "本科教务是响应式的，套固定宽度会制造新问题",
            LoginFlow.needsFitToScreen(graduate = false),
        )
        assertTrue(
            "研究生系统是固定 1280 宽的 PC 页面，必须缩放",
            LoginFlow.needsFitToScreen(graduate = true),
        )
    }

    @Test
    fun `缩放目标宽度是研究生系统的设计宽度 1280`() {
        assertEquals(1280, LoginFlow.GRADUATE_DESIGN_WIDTH)
    }

    @Test
    fun `研究生登录入口在 yjsglxt 而不是统一认证`() {
        val url = LoginFlow.entryUrl(graduate = true)
        assertTrue("必须是研究生系统的域名", url.contains("yjsglxt.gzucm.edu.cn"))
        // 上一版 bug 就是切了学段还停在 CAS 上
        assertTrue("不能跳到统一身份认证", !url.contains("authserver"))
    }

    @Test
    fun `本科入口保持原样不受影响`() {
        assertEquals(
            "https://jw.gzucm.edu.cn/new/ssoLogin",
            LoginFlow.entryUrl(graduate = false),
        )
    }

    @Test
    fun `学段枚举与缩放判断对得上`() {
        // 防「有人加了个学段但忘了处理缩放」
        Campus.entries.forEach { c ->
            val expect = c == Campus.GRADUATE
            assertEquals(
                "$c 的缩放判断应为 $expect",
                expect,
                LoginFlow.needsFitToScreen(graduate = c == Campus.GRADUATE),
            )
        }
    }

    // ── 电费 Tab 与一卡通独立登录（2026-11-01 晚，致辉提）─────────────

    @Test
    fun `两个学段都有电费 Tab，不再对研究生隐藏`() {
        /*
         * 之前研究生学段把电费 Tab 过滤掉了，理由是「一卡通是独立登录」。
         * 但那等于这个功能对研究生直接消失 —— 研究生一样要交电费。
         * 现在改成保留 Tab，进去再看有没有会话、没有就提示登录。
         */
        val grad = com.gzucm.pocket.ui.tabsOf(Campus.GRADUATE)
        val under = com.gzucm.pocket.ui.tabsOf(Campus.UNDERGRAD)
        assertTrue("研究生必须有电费入口", grad.any { it.label == "电费" })
        assertTrue("本科当然也有", under.any { it.label == "电费" })
        assertEquals("两个学段的 Tab 数量应一致", under.size, grad.size)
    }
}