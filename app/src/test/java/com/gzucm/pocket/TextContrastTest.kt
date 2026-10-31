package com.gzucm.pocket

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import com.gzucm.pocket.ui.schedule.textColorFor
import com.gzucm.pocket.ui.theme.courseColor
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 课表字色的可读性守卫。
 *
 * 踩过的坑：把字色写死成「亮度至少 0.82」，深色主题下没问题，
 * 但浅色主题是浅底 —— 提亮等于白字压白底，整张课表几乎看不见字。
 *
 * 现在分深浅两套：深色提亮、浅色压暗。这个测试对**所有课程色**
 * 逐一算对比度，两个主题都必须达到 WCAG AA（4.5:1）。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class TextContrastTest {

    /** 相对亮度对比度，WCAG 定义：(L1 + 0.05) / (L2 + 0.05) */
    private fun contrast(a: Color, b: Color): Float {
        val l1 = a.luminance() + 0.05f
        val l2 = b.luminance() + 0.05f
        return if (l1 > l2) l1 / l2 else l2 / l1
    }

    /**
     * 「前景色按 alpha 叠在背景色上」的结果。
     *
     * 注意 Compose 的 Color 通道是 0..1 的 Float，不是 0..255 ——
     * 我第一次写测试时按 255 算，结果全都不达标，白折腾一轮。
     */
    private fun blendOver(fg: Color, bg: Color, alpha: Float) = Color(
        red = fg.red * alpha + bg.red * (1 - alpha),
        green = fg.green * alpha + bg.green * (1 - alpha),
        blue = fg.blue * alpha + bg.blue * (1 - alpha),
    )

    /** WCAG 阈值：正文 4.5:1 */
    private val minRatio = 4.5f

    // 模拟两种主题的卡片底色（课程色低透明度叠在 surface 上）
    private val darkSurface = Color(0xFF101512)
    private val lightSurface = Color(0xFFF7F9F7)

    @Test
    fun `深色主题下所有课程字色都够清楚`() {
        // 遍历一批典型课名，覆盖调色板各色
        val names = listOf(
            "中医儿科学", "五官学", "外科学(2)", "龙氏治脊疗法", "中医各家学说",
            "中医骨伤科学", "中医妇科学", "岭南医家医著选介", "中医模拟实践课(4)",
            "就业指导(2)", "中医人文与社会实践课(4)", "方剂学", "中药学", "中医诊断学",
            "毛泽东思想和中国特色社会主义理论体系概论", "医学伦理学", "伤寒论",
        )
        names.forEach { name ->
            val base = courseColor(name)
            val text = textColorFor(base, dark = true)
            // 卡片底色 = 课程色按 alpha 叠在 surface 上
            val cardBg = blendOver(base, darkSurface, 0.26f)
            val ratio = contrast(text, cardBg)
            assertTrue(
                "【深色】「$name」字色对比度只有 %.2f:1，低于 %.1f".format(ratio, minRatio),
                ratio >= minRatio,
            )
        }
    }

    @Test
    fun `浅色主题下所有课程字色都够清楚`() {
        val names = listOf(
            "中医儿科学", "五官学", "外科学(2)", "龙氏治脊疗法", "中医各家学说",
            "中医骨伤科学", "中医妇科学", "岭南医家医著选介", "中医模拟实践课(4)",
            "就业指导(2)", "中医人文与社会实践课(4)", "方剂学", "中药学", "中医诊断学",
            "毛泽东思想和中国特色社会主义理论体系概论", "医学伦理学", "伤寒论",
        )
        names.forEach { name ->
            val base = courseColor(name)
            val text = textColorFor(base, dark = false)
            val cardBg = blendOver(base, lightSurface, 0.14f)
            val ratio = contrast(text, cardBg)
            assertTrue(
                "【浅色】「$name」字色对比度只有 %.2f:1，低于 %.1f".format(ratio, minRatio),
                ratio >= minRatio,
            )
        }
    }
}
