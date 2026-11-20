package com.gzucm.pocket.ui.workbench

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebChromeClient
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.gzucm.pocket.ui.icons.AppIcons

/**
 * 学校服务的全屏网页容器。
 *
 * ## 2026-11-21 从底部弹窗改成 Activity
 *
 * 原来挂在 `AdaptiveSheet` 的 `ModalBottomSheet` 上，问题是**手指往下滑一下就把
 * 整个网页关掉了**：网页本身是滚动内容，从上往下翻页和「下拉关闭」是同一个手势，
 * 几乎必然会误触（致辉报的）。
 *
 * 换成独立 Activity 之后：
 * - 没有任何下拉手势，手指随便划
 * - BACK 先回退网页、退到头才关页面（不再和「关面板」抢同一个键）
 * - 屏幕旋转、切后台、返回栈都交给系统
 *
 * `AdaptiveSheet` 本身**没动**：日程面板、今日页显示面板那些是真的面板，
 * 它们需要下滑关闭，而且当年踩过「拦关闭手势导致 Dialog 关不掉」的坑。
 */

/**
 * 建一个配好策略的 WebView。
 *
 * 抽成函数是为了让 Activity 和别处共用同一套设置，这几条每一条都对应一个踩过的坑，
 * 分开写迟早会漂移。
 */
@SuppressLint("SetJavaScriptEnabled")
fun buildCampusWebView(ctx: Context, desktopMode: Boolean = false): WebView = WebView(ctx).apply {
    settings.javaScriptEnabled = true
    settings.domStorageEnabled = true
    // 学校服务不需要读本地文件，关掉少一类攻击面
    settings.allowFileAccess = false
    settings.allowContentAccess = false
    /*
     * 让 WebView 尊重页面的 <meta name="viewport">。
     *
     * WebView 默认 useWideViewPort=false，会按 980px 的桌面宽度渲染，
     * 移动版的样式和布局按手机宽度算出来可能全是 0。
     */
    settings.useWideViewPort = true
    /*
     * desktopMode = 按桌面宽度渲染再整体缩放。
     *
     * 学校有几个系统只有 PC 版（教务、路路通、库巴扎），左侧固定菜单会把正文挤出
     * 视口，表现是「菜单出来了、右边永远空白」。开了这个至少整页可见，代价是字变小。
     * 移动版页面必须保持 false，否则会被整体缩小、白占两边。
     */
    settings.loadWithOverviewMode = desktopMode
    /*
     * 去掉 UA 里的 "; wv" 标记。
     *
     * 不少前端框架会据此判断「这是 App 内嵌浏览器」并走另一套分支。
     */
    settings.userAgentString = settings.userAgentString.replace("; wv", "")
    /*
     * 允许混合内容。
     *
     * 学校有些系统是 https 页面里套 http 接口，WebView 默认会拦掉，
     * 表现是页面框架在、数据全空。
     */
    settings.mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
    /*
     * 关掉「算法暗化」。
     *
     * Android 13 起 WebView 默认跟随系统深色把浅色网页强行反色，学校系统大多只有
     * 浅色版，反色之后不是黑底黑字就是白底白字（2026-11-21 实测）。
     * API 33+ 的 settings.forceDark 已废弃且无效，只有这个正规入口管用。
     */
    if (
        androidx.webkit.WebViewFeature.isFeatureSupported(
            androidx.webkit.WebViewFeature.ALGORITHMIC_DARKENING,
        )
    ) {
        androidx.webkit.WebSettingsCompat.setAlgorithmicDarkeningAllowed(settings, false)
    }
}

/**
 * 是不是校内域名。
 *
 * 必须带那个点：直接 `endsWith("gzucm.edu.cn")` 会连 `notgzucm.edu.cn`、
 * `evil-gzucm.edu.cn` 一起放行，这种仿冒域名加一个假的统一身份认证页，
 * 就能把学号密码骗走。
 */
internal fun isCampusHost(host: String): Boolean =
    host == "gzucm.edu.cn" || host.endsWith(".gzucm.edu.cn")

/**
 * 拦截跳转：只放校内 http/https，其余一律拦住。
 *
 * 校外链接刻意不给「用浏览器打开」的选项，这些页面多数要登录，跳出去也打不开，
 * 反而会让用户以为 App 把他踢出去了。
 *
 * @return true = 拦下
 */
internal fun shouldBlockNavigation(url: android.net.Uri): Boolean {
    val scheme = url.scheme
    // intent://、wxwork://、tel: 交给 WebView 会 ERR_UNKNOWN_URL_SCHEME（白屏）
    if (scheme != "http" && scheme != "https") return true
    val host = url.host ?: return true
    return !isCampusHost(host)
}

/**
 * 全屏网页界面：顶部一条返回栏 + 进度条 + 占满剩余空间的 WebView。
 *
 * 顶栏是必需的：网页自己也会吃 BACK，没有明确的关闭入口用户会困在里面。
 */
/**
 * 全屏网页界面：顶部一条返回栏 + 真实进度条 + 占满剩余空间的 WebView。
 *
 * ## 加载态为什么做得这么啰嗦
 *
 * 学校有几个系统是「HTML 秒回、内容十几秒才出来」的 SPA（杏林教学实测 18 秒）。
 * 只靠 `onPageFinished` 判断的话，那个回调在 2 秒就触发了，剩下 16 秒用户对着
 * 一片白，不知道是卡死还是没加载 —— 2026-11-21 致辉说「看到黑屏觉得这很奇怪」。
 *
 * 所以这里做了三件事：
 * 1. WebView 底色刷成主题背景色（不刷的话深色模式下先闪一块纯黑）
 * 2. `onProgressChanged` 驱动真实百分比进度条
 * 3. `onPageFinished` 之后再轮询 `document.body.innerText.length`，
 *    真的读到内容才算加载完 —— SPA 挂载完的那一刻遮罩才撤
 */
@Composable
fun CampusWebScreen(url: String, title: String, onClose: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    var progress by remember { mutableStateOf(0) }
    var loading by remember { mutableStateOf(true) }
    var canGoBack by remember { mutableStateOf(false) }
    val web = remember { mutableStateOf<WebView?>(null) }

    /*
     * BACK 先回退网页，退到头才让 Activity 收（= 关页面）。
     *
     * enabled 只挂在「网页还能后退」上：不能后退时不接管，系统直接 finish，
     * 用户按一次就出去，不会出现「按了没反应」。
     */
    BackHandler(enabled = canGoBack) { web.value?.goBack() }

    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier
                .fillMaxWidth()
                .statusBarsPadding()
                .padding(horizontal = 6.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onClose, modifier = Modifier.size(38.dp)) {
                Icon(
                    AppIcons.ArrowBack,
                    contentDescription = "关闭",
                    tint = scheme.onSurface,
                    modifier = Modifier.size(20.dp),
                )
            }
            Text(
                title,
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium,
                color = scheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.size(38.dp))
        }

        // 进度条：加载中显示真实百分比，加载完变成一条细的完成线再淡出
        if (loading) {
            LinearProgressIndicator(
                progress = { progress / 100f },
                modifier = Modifier.fillMaxWidth(),
                color = scheme.primary,
                trackColor = scheme.primary.copy(alpha = 0.15f),
            )
        }

        Box(
            Modifier
                .fillMaxWidth()
                .weight(1f),
        ) {
            AndroidView(
                factory = { ctx ->
                    buildCampusWebView(ctx).apply {
                        /*
                         * 底色必须显式设。
                         *
                         * WebView 默认背景是白的，但硬加速下的合成层在深色主题里
                         * 会先显示一块纯黑 —— 用户看到的就是「打开先黑一下」。
                         */
                        setBackgroundColor(scheme.background.toArgb())

                        webChromeClient = object : WebChromeClient() {
                            override fun onProgressChanged(view: WebView?, newProgress: Int) {
                                progress = newProgress
                            }
                        }

                        webViewClient = object : WebViewClient() {
                            override fun onPageStarted(v: WebView?, u: String?, favicon: Bitmap?) {
                                progress = 0
                                loading = true
                                canGoBack = v?.canGoBack() == true
                            }

                            override fun onPageFinished(v: WebView?, u: String?) {
                                canGoBack = v?.canGoBack() == true
                                if (v == null) {
                                    loading = false
                                    return
                                }
                                /*
                                 * 轮询到真的有内容为止（最多约 25 秒）。
                                 *
                                 * 判断依据是 `innerText.length`：SPA 挂载完成后正文
                                 * 一定有几十个字符；白屏时是 0。比固定延时可靠 ——
                                 * 固定 3 秒对杏林教学完全不够，对图书馆又白等。
                                 */
                                var attempt = 0
                                fun checkReady() {
                                    if (attempt++ > 25) {
                                        loading = false
                                        return
                                    }
                                    v.postDelayed({
                                        v.evaluateJavascript(
                                            "(document.body ? document.body.innerText.length : 0)",
                                        ) { raw ->
                                            val len = raw?.trim('"')?.toIntOrNull() ?: 0
                                            if (len > 20) {
                                                progress = 100
                                                loading = false
                                            } else {
                                                // 还没内容，进度条卡在 90% 等着，别让它满格骗人
                                                if (progress > 90) progress = 90
                                                checkReady()
                                            }
                                        }
                                    }, 900)
                                }
                                checkReady()
                            }

                            override fun shouldOverrideUrlLoading(
                                v: WebView,
                                request: WebResourceRequest,
                            ): Boolean = shouldBlockNavigation(request.url)
                        }
                        loadUrl(url)
                        web.value = this
                    }
                },
                modifier = Modifier
                    .fillMaxSize()
                    .navigationBarsPadding(),
            )

            /*
             * 加载遮罩：不是黑屏，也不是白屏，是一张说明卡片。
             *
             * 用户至少知道「在加载、加载到哪、要等多久」，不会以为 App 挂了直接退。
             */
            if (loading) {
                Box(
                    Modifier
                        .fillMaxSize()
                        .background(scheme.background),
                    contentAlignment = Alignment.Center,
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(34.dp),
                            color = scheme.primary,
                            strokeWidth = 3.dp,
                        )
                        Spacer(Modifier.height(16.dp))
                        Text(
                            "正在加载 $title",
                            fontSize = 13.sp,
                            color = scheme.onSurface,
                        )
                        Spacer(Modifier.height(6.dp))
                        Text(
                            if (progress >= 100) "页面已就绪，正在渲染内容…"
                            else "已加载 $progress%",
                            fontSize = 11.5.sp,
                            color = scheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}
