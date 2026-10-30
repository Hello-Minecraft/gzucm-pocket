package com.gzucm.pocket.ui.theme

import androidx.compose.ui.graphics.Color
import kotlin.math.abs

/**
 * 主题色。
 *
 * 设计原则：**只换强调色，不动中性色**。
 * 背景、表面、正文、分割线全部沿用原来的值，换主题时深色底不会突然变灰或发紫，
 * 只是按钮、数字、高亮这些地方换颜色。这样每个预设都能保证对比度不崩。
 *
 * 强调色不是手写死的，而是从一个「种子色」按 HSL 推出来（见 [accentFor]）：
 * 深色主题把亮度提上去、饱和度补一点（低饱和的莫兰迪色在深底上会灰成一片）；
 * 浅色主题反过来压暗。这样同一个种子在深浅两套里都可用。
 */
object ThemeColor {

    /** 0 表示「默认」——完全沿用原来的薄荷配色，不走算法 */
    const val DEFAULT_SEED = 0L

    /**
     * 9 个预设。
     *
     * 前 5 个是莫兰迪系（低饱和、掺灰），后 4 个是鲜艳强调色。
     * 名字只用于无障碍描述，界面里不显示 —— 色卡看颜色就够了。
     */
    val presets: List<Preset> = listOf(
        Preset("雾蓝", 0xFF7A93A8),
        Preset("豆沙", 0xFFC2857F),
        Preset("苔绿", 0xFF8CA07A),
        Preset("藕荷", 0xFF9B8FA8),
        Preset("燕麦", 0xFFB49B78),
        Preset("靛蓝", 0xFF3B82F6),
        Preset("珊瑚", 0xFFFF7A59),
        Preset("品红", 0xFFE255A1),
        Preset("青柠", 0xFFA3D655),
    )

    data class Preset(val label: String, val seed: Long)

    /** 色卡上显示的颜色（就是种子色本身，深色主题下略提亮一点，否则看着发暗） */
    fun swatchColor(seed: Long, dark: Boolean): Color {
        if (seed == DEFAULT_SEED) return if (dark) Color(0xFF4ADE9B) else Color(0xFF1D9E75)
        val c = Color(seed)
        if (!dark) return c
        val (h, s, l) = c.toHsl()
        return hsl(h, (s * 1.25f).coerceIn(0f, 1f), (l + 0.08f).coerceIn(0f, 1f))
    }

    /**
     * 由种子色推出这一套强调色。
     *
     * @param dark 深色主题下把亮度拉高，保证在 #0A0C0F 的底上够亮
     */
    fun accentFor(seed: Color, dark: Boolean): Accent {
        val (h, s, l) = seed.toHsl()

        return if (dark) {
            Accent(
                primary = hsl(h, (s * 1.35f).coerceIn(0.35f, 0.85f), (l + 0.14f).coerceIn(0.60f, 0.78f)),
                onPrimary = hsl(h, 0.55f, 0.10f),
                primaryContainer = hsl(h, 0.45f, 0.16f),
                onPrimaryContainer = hsl(h, 0.70f, 0.78f),
                secondary = hsl((h + 35f) % 360f, (s * 1.2f).coerceIn(0.30f, 0.80f), 0.70f),
                tertiary = hsl((h + 320f) % 360f, (s * 1.2f).coerceIn(0.30f, 0.80f), 0.72f),
            )
        } else {
            Accent(
                primary = hsl(h, (s * 1.15f).coerceIn(0.30f, 0.85f), (l - 0.12f).coerceIn(0.30f, 0.44f)),
                onPrimary = Color.White,
                primaryContainer = hsl(h, (s * 0.9f).coerceIn(0.20f, 0.70f), 0.91f),
                onPrimaryContainer = hsl(h, 0.60f, 0.22f),
                secondary = hsl((h + 35f) % 360f, (s * 1.1f).coerceIn(0.25f, 0.75f), 0.40f),
                tertiary = hsl((h + 320f) % 360f, (s * 1.1f).coerceIn(0.25f, 0.75f), 0.38f),
            )
        }
    }

    data class Accent(
        val primary: Color,
        val onPrimary: Color,
        val primaryContainer: Color,
        val onPrimaryContainer: Color,
        val secondary: Color,
        val tertiary: Color,
    )
}

// ── HSL ────────────────────────────────────────────────────────────────

/** @return [h(0..360), s(0..1), l(0..1)] */
fun Color.toHsl(): Triple<Float, Float, Float> {
    val r = red
    val g = green
    val b = blue
    val max = maxOf(r, g, b)
    val min = minOf(r, g, b)
    val l = (max + min) / 2f
    val d = max - min
    if (d == 0f) return Triple(0f, 0f, l)
    val s = d / (1f - abs(2f * l - 1f)).coerceAtLeast(1e-6f)
    val h = when (max) {
        r -> 60f * (((g - b) / d) % 6f)
        g -> 60f * (((b - r) / d) + 2f)
        else -> 60f * (((r - g) / d) + 4f)
    }
    return Triple((h + 360f) % 360f, s.coerceIn(0f, 1f), l)
}

fun hsl(h: Float, s: Float, l: Float): Color {
    val ss = s.coerceIn(0f, 1f)
    val ll = l.coerceIn(0f, 1f)
    val c = (1f - abs(2f * ll - 1f)) * ss
    val hp = ((h % 360f) + 360f) % 360f / 60f
    val x = c * (1f - abs(hp % 2f - 1f))
    val (r1, g1, b1) = when (hp.toInt()) {
        0 -> Triple(c, x, 0f)
        1 -> Triple(x, c, 0f)
        2 -> Triple(0f, c, x)
        3 -> Triple(0f, x, c)
        4 -> Triple(x, 0f, c)
        else -> Triple(c, 0f, x)
    }
    val m = ll - c / 2f
    return Color(
        red = (r1 + m).coerceIn(0f, 1f),
        green = (g1 + m).coerceIn(0f, 1f),
        blue = (b1 + m).coerceIn(0f, 1f),
    )
}
