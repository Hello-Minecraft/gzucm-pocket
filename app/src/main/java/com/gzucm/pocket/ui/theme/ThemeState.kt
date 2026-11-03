package com.gzucm.pocket.ui.theme

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * 当前主题（配色种子 + 深浅色）。
 *
 * 主题要在 [GzucmTheme] 这一层生效，而改主题的入口在设置页（很里面），
 * 一层层往上传参要穿透 RootScreen / SettingsScreen 两层，容易漏。
 * 用一个全局 State：MainActivity 读它、设置页写它，改完立刻重建配色。
 */
object ThemeState {

    /**
     * 深浅色模式。
     *
     * 三种取值缺一不可：只给「深 / 浅」二选一的话，用户一旦手动切过，
     * 就再也回不到「跟系统」了 —— 而 Android 12+ 按时间自动切深色
     * 是很多人依赖的行为，回不去很恼人。
     */
    enum class DarkMode(val label: String) {
        SYSTEM("跟随系统"),
        DARK("深色"),
        LIGHT("浅色"),
        ;

        companion object {
            /** 存的是序号；越界（旧版本脏数据）一律退回跟随系统 */
            fun of(index: Int): DarkMode = entries.getOrElse(index) { SYSTEM }
        }
    }

    var seed by mutableStateOf(ThemeColor.DEFAULT_SEED)
        private set

    var darkMode by mutableStateOf(DarkMode.SYSTEM)
        private set

    /** 启动时从本地读一次；之后只靠 [set] / [setDarkMode] 改 */
    fun init(savedSeed: Long, savedDarkMode: Int = 0) {
        seed = savedSeed
        darkMode = DarkMode.of(savedDarkMode)
    }

    fun set(value: Long, persist: (Long) -> Unit) {
        seed = value
        persist(value)
    }

    fun setDarkMode(mode: DarkMode, persist: (Int) -> Unit) {
        darkMode = mode
        persist(mode.ordinal)
    }

    /**
     * 实际该用深色还是浅色。
     *
     * **所有判断深浅的地方都必须走这里**，不要再直接调 `isSystemInDarkTheme()` ——
     * 那样用户手动选了「浅色」而系统是深色时，色卡这类局部元素会按系统算，
     * 和整体配色对不上，看起来像花屏。
     *
     * @param systemDark 系统当前是不是深色（调用方从 `isSystemInDarkTheme()` 取）
     */
    fun isDark(systemDark: Boolean): Boolean = when (darkMode) {
        DarkMode.SYSTEM -> systemDark
        DarkMode.DARK -> true
        DarkMode.LIGHT -> false
    }
}
