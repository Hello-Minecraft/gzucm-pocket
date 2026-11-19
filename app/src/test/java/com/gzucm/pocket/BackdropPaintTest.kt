package com.gzucm.pocket

import com.gzucm.pocket.ui.components.backdropPaint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 浅色背景「灰蒙」修复的口径闸门。
 *
 * 旧实现里图片按 alpha 半透明画，浅色页面底色从下面透上来，
 * 默认 0.45 时浅灰白占 63%。新实现必须保证：
 * 浅色下图片满绘、滑块只调上面的遮罩；深色不许跟着改。
 */
class BackdropPaintTest {

    @Test
    fun `浅色时图片始终满绘`() {
        for (a in listOf(0.15f, 0.45f, 0.8f, 1f)) {
            assertEquals(
                "浓度 $a 下图片也要满绘，不能再让页面底色透上来",
                1f,
                backdropPaint(a, light = true).imageAlpha,
                0f,
            )
        }
    }

    @Test
    fun `浅色遮罩随滑块变薄 拉满归零`() {
        assertEquals(0f, backdropPaint(1f, light = true).tintAlpha, 0.0001f)

        val default = backdropPaint(0.45f, light = true).tintAlpha
        assertEquals((1f - 0.45f) * 0.6f, default, 0.0001f)
        assertTrue("默认浓度下遮罩不能超过 40%，否则又是灰蒙一片", default < 0.4f)

        assertTrue(
            "滑块调高必须让遮罩更薄",
            backdropPaint(0.8f, light = true).tintAlpha < default,
        )
    }

    @Test
    fun `深色模式保持原来的口径`() {
        val p = backdropPaint(0.45f, light = false)
        assertEquals("深色仍按图片透明度画", 0.45f, p.imageAlpha, 0f)
        assertEquals("深色那层压高光的 tint 不能动", 0.18f, p.tintAlpha, 0f)
    }

    @Test
    fun `越界浓度被夹到合法区间`() {
        assertEquals(1f, backdropPaint(5f, light = true).imageAlpha, 0f)
        assertEquals(0f, backdropPaint(5f, light = true).tintAlpha, 0f)
        assertEquals(0.15f, backdropPaint(-1f, light = false).imageAlpha, 0f)
    }
}
