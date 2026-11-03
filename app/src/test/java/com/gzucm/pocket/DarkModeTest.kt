package com.gzucm.pocket

import com.gzucm.pocket.ui.theme.ThemeState
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 深浅色模式的三选一。
 *
 * 这里只测纯逻辑（存储序号 ↔ 枚举 ↔ 最终用不用深色），
 * 具体配色由 `ThemeColorTest` 守。
 */
class DarkModeTest {

    private val saved = ThemeState.darkMode

    @After
    fun restore() {
        // ThemeState 是全局单例，不还原会污染同一 JVM 里的其他测试
        ThemeState.init(ThemeState.seed, saved.ordinal)
    }

    @Test
    fun `序号与枚举对应`() {
        assertEquals(ThemeState.DarkMode.SYSTEM, ThemeState.DarkMode.of(0))
        assertEquals(ThemeState.DarkMode.DARK, ThemeState.DarkMode.of(1))
        assertEquals(ThemeState.DarkMode.LIGHT, ThemeState.DarkMode.of(2))
    }

    @Test
    fun `脏数据退回跟随系统`() {
        // 以后如果调整枚举顺序/删档，老用户 prefs 里可能是越界值
        assertEquals(ThemeState.DarkMode.SYSTEM, ThemeState.DarkMode.of(99))
        assertEquals(ThemeState.DarkMode.SYSTEM, ThemeState.DarkMode.of(-1))
    }

    @Test
    fun `跟随系统时听系统的`() {
        ThemeState.init(0, ThemeState.DarkMode.SYSTEM.ordinal)
        assertTrue("系统深色就是深色", ThemeState.isDark(systemDark = true))
        assertFalse("系统浅色就是浅色", ThemeState.isDark(systemDark = false))
    }

    @Test
    fun `强制深色时无视系统`() {
        ThemeState.init(0, ThemeState.DarkMode.DARK.ordinal)
        assertTrue(ThemeState.isDark(systemDark = false))
        assertTrue(ThemeState.isDark(systemDark = true))
    }

    @Test
    fun `强制浅色时无视系统`() {
        ThemeState.init(0, ThemeState.DarkMode.LIGHT.ordinal)
        assertFalse(ThemeState.isDark(systemDark = true))
        assertFalse(ThemeState.isDark(systemDark = false))
    }

    @Test
    fun `选完会写回存储`() {
        var written = -1
        ThemeState.setDarkMode(ThemeState.DarkMode.LIGHT) { written = it }
        assertEquals("存的是序号，读的时候靠 of() 还原", 2, written)
        assertEquals(ThemeState.DarkMode.LIGHT, ThemeState.darkMode)
    }
}
