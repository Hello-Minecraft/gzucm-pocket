package com.gzucm.pocket.ui.schedule

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
 * 学委教程页：讲清楚「班级日程」怎么发布、改完怎么再发、有哪些配额和禁忌。
 *
 * ## 为什么是独立 Activity
 *
 * 这页正文长（七节、要来回对照），做成弹窗或底部面板时：
 * 面板往下滑会关掉，用户读到第三节手一滑就没了；面板高度也受限，
 * 七节塞进去得在小窗里滚三层，反而比全屏难读。
 * 独立 Activity 有完整的竖向空间，返回键就是返回，没有别的含义。
 *
 * ## 主题色
 *
 * 和 [com.gzucm.pocket.ui.workbench.CampusWebActivity] 一样在 onCreate 里
 * 先 `ThemeState.init(...)`：用户在设置里换过主题色/深浅色，这页不能是默认蓝。
 * 少了这一步，从深色主题进教程页会突然闪一下浅色，很刺眼。
 */
class ClassPlanGuideActivity : ComponentActivity() {

    companion object {
        /** 统一从这里建 Intent，外面不用关心是不是带参数 */
        fun intent(ctx: Context): Intent = Intent(ctx, ClassPlanGuideActivity::class.java)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        // 配色跟主界面保持一致
        runCatching {
            val store = com.gzucm.pocket.core.SessionStore(applicationContext)
            com.gzucm.pocket.ui.theme.ThemeState.init(store.themeSeed, store.darkMode)
        }

        setContent {
            GzucmTheme(
                seed = com.gzucm.pocket.ui.theme.ThemeState.seed,
                darkTheme = com.gzucm.pocket.ui.theme.ThemeState.isDark(
                    androidx.compose.foundation.isSystemInDarkTheme(),
                ),
            ) {
                Surface(Modifier.fillMaxSize()) {
                    ClassPlanGuideScreen(onClose = { finish() })
                }
            }
        }
    }
}
