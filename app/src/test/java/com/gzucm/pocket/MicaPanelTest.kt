package com.gzucm.pocket

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import com.gzucm.pocket.ui.components.MicaDarkFill
import com.gzucm.pocket.ui.components.MicaSheetFill
import com.gzucm.pocket.ui.components.micaElevation
import com.gzucm.pocket.ui.components.micaPanelFill
import com.gzucm.pocket.ui.components.micaSheetFill
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 云母面板的底色规则（致辉 2026-11-19 定）。
 *
 * 只测纯函数这一层：阴影本身是 RenderNode 画的，Robolectric 里根本不出像素，
 * 断言"有没有阴影"只会得到一个永远为真的假测试。真机观感靠截图，不靠这里。
 */
class MicaPanelTest {

    /** 浅色不动：白卡贴 #F6F7F9 的底色，对比交给阴影。 */
    @Test
    fun light_keepsThemeSurface() {
        val surface = Color(0xFFFFFFFF)
        assertEquals(surface, micaPanelFill(surface, dark = false))
    }

    /** 深色必须提亮：黑色阴影叠在 #0A0C0F 上等于没加。 */
    @Test
    fun dark_liftsOneStep() {
        val surface = Color(0xFF12151A)
        assertEquals(MicaDarkFill, micaPanelFill(surface, dark = true))
    }

    /**
     * 提亮幅度要够但别过头：
     * 太浅（<2%）看不见分层，太亮（>20%）会变成一块灰板，跟底栏抢注意力。
     */
    @Test
    fun dark_lift_is_visible_but_not_gray() {
        val surface = Color(0xFF12151A)
        val d = MicaDarkFill.luminance() - surface.luminance()
        assertTrue("提亮不够，深色下看不出分层：$d", d > 0.002f)
        assertTrue("提亮过头，面板会发灰：$d", d < 0.02f)
    }

    /** 提亮后的底色仍然比正文色暗得多，白字压上去要保持对比。 */
    @Test
    fun dark_fill_stays_dark_enough_for_white_text() {
        assertTrue(MicaDarkFill.luminance() < 0.05f)
    }

    /**
     * 深色三层必须是 页面 < Sheet < 卡片。
     *
     * 2026-11-19 致辉报「深色设置页底色跟面板完全一样，看不出分组」——
     * 当时 Sheet 容器和里面的卡片都取 [MicaDarkFill]，浅色还能靠 7dp 阴影分开，
     * 深色下阴影是黑叠黑、只剩 3dp，等于没有，整页塌成一块平板。
     *
     * 这条同时钉住「别再把 Sheet 调回卡片色」，以及「两段差不能一边倒」：
     * 页面→Sheet 太细会让 Sheet 浮不起来，Sheet→卡片 太细则分组还是看不清。
     */
    @Test
    fun dark_sheet_sits_between_background_and_panel() {
        val bg = Color(0xFF0A0C0F)
        val sheet = micaSheetFill(Color(0xFF12151A), dark = true)
        assertEquals("深色 Sheet 必须走 MicaSheetFill", MicaSheetFill, sheet)

        assertTrue(
            "Sheet 必须比页面背景亮，否则浮不起来：${sheet.luminance()} vs ${bg.luminance()}",
            sheet.luminance() > bg.luminance(),
        )
        assertTrue(
            "Sheet 必须比卡片暗，否则分组消失：${sheet.luminance()} vs ${MicaDarkFill.luminance()}",
            sheet.luminance() < MicaDarkFill.luminance(),
        )

        val toCard = MicaDarkFill.luminance() - sheet.luminance()
        assertTrue("Sheet 与卡片差太细，等于白改：$toCard", toCard > 0.002f)
        assertTrue("Sheet 与卡片差太大，卡片会变成一块亮板：$toCard", toCard < 0.02f)
    }

    /** 浅色不参与分层：白 Sheet + 白卡片，7dp 阴影已经画出边界。 */
    @Test
    fun light_sheet_keepsThemeSurface() {
        val surface = Color(0xFFFFFFFF)
        assertEquals(surface, micaSheetFill(surface, dark = false))
    }


    /**
     * 深色给得比浅色**低**。
     *
     * 近黑底（#0A0C0F）上叠高 elevation 会压成纯黑，面板四周出现一圈脏光晕；
     * 深色的立体感靠底色提亮 + 顶边高光，阴影只留一层极弱轮廓。
     */
    @Test
    fun dark_does_not_over_shadow() {
        assertTrue(
            "深色阴影必须低于浅色",
            micaElevation(dark = true).value < micaElevation(dark = false).value,
        )
    }
}
