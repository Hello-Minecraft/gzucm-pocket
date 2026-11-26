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

        // 主题色与深浅色：先把上次选的读出来，再交给 GzucmTheme。
        // ThemeState 是 Compose State，设置页改它就会整棵树重建配色。
        runCatching {
            val store = com.gzucm.pocket.core.SessionStore(applicationContext)
            com.gzucm.pocket.ui.theme.ThemeState.init(store.themeSeed, store.darkMode)
        }

        setContent {
            GzucmTheme(
                seed = com.gzucm.pocket.ui.theme.ThemeState.seed,
                /*
                 * 深浅色走 ThemeState 的解析，不直接用 isSystemInDarkTheme()。
                 *
                 * 用户可以在设置里强制深色/浅色；只有选「跟随系统」时
                 * 才让系统的值进来。这里不传的话，手动设置毫无作用。
                 */
                darkTheme = com.gzucm.pocket.ui.theme.ThemeState.isDark(
                    androidx.compose.foundation.isSystemInDarkTheme()
                ),
            ) {
                Surface(modifier = Modifier.fillMaxSize()) {
                    /*
                     * 上次崩过就先摆一个说明，盖在主界面上面。
                     *
                     * 读文件放 LaunchedEffect：`filesDir` 是磁盘 IO，
                     * 在组合里同步做会拖慢首帧（冷启动本来就是最卡的时候）。
                     * 读完才赋值，所以正常启动只是多一次「读不到 → null」。
                     */
                    val crashState = androidx.compose.runtime.remember {
                        androidx.compose.runtime.mutableStateOf<com.gzucm.pocket.core.CrashLog.Report?>(null)
                    }
                    androidx.compose.runtime.LaunchedEffect(Unit) {
                        crashState.value = com.gzucm.pocket.core.CrashLog.read(applicationContext)
                    }

                    RootScreen()

                    crashState.value?.let { report ->
                        com.gzucm.pocket.ui.CrashReportDialog(
                            report = report,
                            onDismiss = {
                                com.gzucm.pocket.core.CrashLog.clear(applicationContext)
                                crashState.value = null
                            },
                        )
                    }
                }
            }
        }
    }

    /**
     * 回到前台就刷一遍桌面部件。
     *
     * 这是所有刷新触发点里最可靠的一个：用户刚在 App 里看过课表，
     * 桌面就不该还停在旧内容上（后台被杀、隔夜没刷新这些事在这里补齐）。
     *
     * 放后台线程：要读一次 SharedPreferences、再给每个实例构造 RemoteViews，
     * 虽然不重，也没必要占 onResume 的主线程。
     */
    override fun onResume() {
        super.onResume()
        val app = applicationContext
        Thread {
            runCatching { com.gzucm.pocket.widget.WidgetUpdate.refreshAll(app) }
                .onFailure { android.util.Log.w("Widget", "App 打开后刷新部件失败", it) }
        }.start()
    }
}
