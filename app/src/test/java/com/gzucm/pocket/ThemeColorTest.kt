package com.gzucm.pocket

import androidx.compose.ui.graphics.Color
import com.gzucm.pocket.ui.theme.ThemeColor
import com.gzucm.pocket.ui.theme.hsl
import com.gzucm.pocket.ui.theme.toHsl
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 主题色算法。
 *
 * 重点不是「颜色好不好看」（那是主观的），而是**对比度不会崩**：
 * 深色主题的主色必须够亮，否则在 #0A0C0F 的底上看着像脏灰；
 * 浅色主题的主色必须够暗，否则白底上的字看不清。
 * 每个预设都过一遍，以后加新色也不会漏。
 */
class ThemeColorTest {

    @Test
    fun `纯红转换正确`() {
        val (h, s, l) = Color(0xFFFF0000).toHsl()
        assertEquals(0f, h, 0.5f)
        assertEquals(1f, s, 0.01f)
        assertEquals(0.5f, l, 0.01f)
    }

    @Test
    fun `纯绿纯蓝色相正确`() {
        assertEquals(120f, Color(0xFF00FF00).toHsl().first, 0.5f)
        assertEquals(240f, Color(0xFF0000FF).toHsl().first, 0.5f)
    }

    @Test
    fun `灰度色饱和度为0`() {
        val (_, s, l) = Color(0xFF808080).toHsl()
        assertEquals(0f, s, 0.01f)
        assertTrue(l in 0.49f..0.51f)
    }

    @Test
    fun `hsl 与 toHsl 能往返`() {
        val c = hsl(210f, 0.5f, 0.4f)
        val (h, s, l) = c.toHsl()
        assertEquals(210f, h, 1.5f)
        assertEquals(0.5f, s, 0.02f)
        assertEquals(0.4f, l, 0.02f)
    }

    @Test
    fun `深色主题下每个预设的主色都够亮`() {
        ThemeColor.presets.forEach { p ->
            val a = ThemeColor.accentFor(Color(p.seed), dark = true)
            val l = a.primary.toHsl().third
            // 背景 #0A0C0F 亮度约 0.05，主色低于 0.55 就开始糊
            assertTrue("${p.label} 深色下太暗：L=$l", l >= 0.55f)
        }
    }

    @Test
    fun `浅色主题下每个预设的主色都够深`() {
        ThemeColor.presets.forEach { p ->
            val a = ThemeColor.accentFor(Color(p.seed), dark = false)
            val l = a.primary.toHsl().third
            // 白底上字要能看清，L 太高就发白
            assertTrue("${p.label} 浅色下太浅：L=$l", l <= 0.50f)
        }
    }

    @Test
    fun `九个预设颜色互不相同`() {
        assertEquals(9, ThemeColor.presets.size)
        assertEquals(9, ThemeColor.presets.map { it.seed }.toSet().size)
    }

    @Test
    fun `默认种子是0且不是任何预设`() {
        assertEquals(0L, ThemeColor.DEFAULT_SEED)
        assertTrue(ThemeColor.presets.none { it.seed == ThemeColor.DEFAULT_SEED })
    }

    @Test
    fun `默认档的色卡颜色就是原来的薄荷`() {
        assertEquals(Color(0xFF4ADE9B), ThemeColor.swatchColor(0L, dark = true))
        assertEquals(Color(0xFF1D9E75), ThemeColor.swatchColor(0L, dark = false))
    }
}
