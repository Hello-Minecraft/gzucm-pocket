package com.gzucm.pocket.ui.workbench

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import com.gzucm.pocket.ui.theme.GzucmTheme

/**
 * 学校服务的全屏网页页。
 *
 * ## 为什么是 Activity 而不是面板
 *
 * 网页本身要上下滑动，挂在 `ModalBottomSheet` 上时，「往下翻页」和「下拉关闭」
 * 是同一个手势，手指一划就把整个页面关掉了（2026-11-21 致辉报的）。
 * 独立 Activity 没有任何下拉手势，BACK 也只处理网页前进后退。
 *
 * ## 为什么要单独一行顶栏
 *
 * 网页自己会消费 BACK（学校系统尤其明显），没有明确的关闭入口，用户会困在里面
 * 只能靠杀进程。顶栏那个返回键是唯一的确定出口。
 */
class CampusWebActivity : ComponentActivity() {

    companion object {
        private const val EXTRA_URL = "url"
        private const val EXTRA_TITLE = "title"
        private const val EXTRA_DESKTOP = "desktop"

        /** 统一从这里建 Intent，避免外面手拼 extra 键名写错 */
        fun intent(ctx: Context, url: String, title: String, desktopMode: Boolean = false): Intent =
            Intent(ctx, CampusWebActivity::class.java)
                .putExtra(EXTRA_URL, url)
                .putExtra(EXTRA_TITLE, title)
                .putExtra(EXTRA_DESKTOP, desktopMode)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        // 配色跟主界面保持一致：用户在设置里换过主题色/深浅色，这边不能是默认蓝
        runCatching {
            val store = com.gzucm.pocket.core.SessionStore(applicationContext)
            com.gzucm.pocket.ui.theme.ThemeState.init(store.themeSeed, store.darkMode)
        }

        val url = intent.getStringExtra(EXTRA_URL).orEmpty()
        val title = intent.getStringExtra(EXTRA_TITLE).orEmpty()
        val desktop = intent.getBooleanExtra(EXTRA_DESKTOP, false)
        if (url.isBlank()) {
            finish()
            return
        }

        setContent {
            GzucmTheme(
                seed = com.gzucm.pocket.ui.theme.ThemeState.seed,
                darkTheme = com.gzucm.pocket.ui.theme.ThemeState.isDark(
                    androidx.compose.foundation.isSystemInDarkTheme(),
                ),
            ) {
                Surface(Modifier.fillMaxSize()) {
                    CampusWebScreen(
                        url = url,
                        title = title.ifBlank { "学校服务" },
                        onClose = { finish() },
                        desktopMode = desktop,
                    )
                }
            }
        }
    }
}
