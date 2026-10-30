package com.gzucm.pocket

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import com.gzucm.pocket.ui.RootScreen
import com.gzucm.pocket.ui.theme.GzucmTheme

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        // edge-to-edge：状态栏/导航栏透明，内容铺到整屏。
        // 状态栏区域的底色由 Surface 的主题背景提供，
        // 各页面再用 statusBarsPadding 把文字避开状态栏。
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)

        // 电费提醒：建通道 + 排每日巡检（KEEP，不会重复排）
        runCatching {
            com.gzucm.pocket.core.PowerAlert.ensureChannel(applicationContext)
            com.gzucm.pocket.core.PowerAlert.schedule(applicationContext)
        }

        // Android 13+ 需要通知权限，没授权就顺手要一次
        if (android.os.Build.VERSION.SDK_INT >= 33) {
            val need = androidx.core.content.ContextCompat.checkSelfPermission(
                this, android.Manifest.permission.POST_NOTIFICATIONS
            ) != android.content.pm.PackageManager.PERMISSION_GRANTED
            if (need) {
                androidx.core.app.ActivityCompat.requestPermissions(
                    this, arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 9001
                )
            }
        }

        // 主题色：先把上次选的读出来，再交给 GzucmTheme。
        // ThemeState 是 Compose State，设置页改它就会整棵树重建配色。
        runCatching {
            com.gzucm.pocket.ui.theme.ThemeState.init(
                com.gzucm.pocket.core.SessionStore(applicationContext).themeSeed
            )
        }

        setContent {
            GzucmTheme(
                seed = com.gzucm.pocket.ui.theme.ThemeState.seed,
            ) {
                Surface(modifier = Modifier.fillMaxSize()) {
                    RootScreen()
                }
            }
        }
    }
}
