package com.gzucm.pocket.ui.theme

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * 当前主题色。
 *
 * 主题要在 [GzucmTheme] 这一层生效，而改主题的入口在设置页（很里面），
 * 一层层往上传参要穿透 RootScreen / SettingsScreen 两层，容易漏。
 * 用一个全局 State：MainActivity 读它、设置页写它，改完立刻重建配色。
 */
object ThemeState {

    var seed by mutableStateOf(ThemeColor.DEFAULT_SEED)
        private set

    /** 启动时从本地读一次；之后只靠 [set] 改 */
    fun init(saved: Long) {
        seed = saved
    }

    fun set(value: Long, persist: (Long) -> Unit) {
        seed = value
        persist(value)
    }
}
