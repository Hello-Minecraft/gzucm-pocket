package com.gzucm.pocket.ui.workbench

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.ui.unit.sp
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.gzucm.pocket.core.CampusApp
import com.gzucm.pocket.ui.adaptive.AdaptiveSheet

/**
 * 学校服务的内嵌网页。
 *
 * ## 为什么不跳系统浏览器
 *
 * 两个原因：一是这些系统多数要登录，跳出去之后回来会很麻烦；
 * 二是学校服务的移动端适配普遍一般，系统浏览器带一堆地址栏/书签栏，
 * 真正能看的区域只剩一半。
 *
 * ## 会话是共享的
 *
 * 用的是系统 `CookieManager`，和登录教务、同步电费那两个 WebView 是同一份
 * cookie 池。也就是说在 App 里登过统一身份认证，点开图书馆/财务通常直接进去。
 *
 * ## 只放校内域名
 *
 * `shouldOverrideUrlLoading` 里非 `*.gzucm.edu.cn` 的跳转一律拦下并提示。
 * 这些页面广告和外部跳转很多（尤其是各类「继续访问」中转页），
 * 放开了用户会被拐到不知道哪儿去，还回不来。
 */
@Composable
fun CampusWebSheet(app: CampusApp?, onDismiss: () -> Unit) {
    AdaptiveSheet(
        visible = app != null,
        onDismiss = onDismiss,
        paneTitle = app?.name,
        paneWidth = 520.dp,
        // 内容自己是 WebView，不能让外层再套一层滚动（两层滚动会崩）
        scrollable = false,
        fillHeight = true,
    ) {
        val url = app?.url
        if (url != null) {
            /*
             * 手机端要自带一条关闭栏。
             *
             * AdaptiveSheet 的 paneTitle 只在**宽屏侧栏**里画；
             * 手机端 ModalBottomSheet 内容区就是一片 WebView 全屏，
             * 没有这条的话用户只能靠返回键或下拉 —— 而学校页面自己也会吃 BACK。
             */
            val wide = androidx.compose.ui.platform.LocalConfiguration.current.screenWidthDp >= 840
            Column(Modifier.fillMaxWidth()) {
                if (!wide) {
                    val scheme = androidx.compose.material3.MaterialTheme.colorScheme
                    androidx.compose.foundation.layout.Row(
                        Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 10.dp, vertical = 6.dp),
                        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                    ) {
                        androidx.compose.material3.IconButton(
                            onClick = onDismiss,
                            modifier = Modifier.size(34.dp),
                        ) {
                            androidx.compose.material3.Icon(
                                com.gzucm.pocket.ui.icons.AppIcons.Close,
                                contentDescription = "关闭",
                                tint = scheme.onSurfaceVariant,
                                modifier = Modifier.size(18.dp),
                            )
                        }
                        Text(
                            app.name,
                            fontSize = 13.sp,
                            fontWeight = androidx.compose.ui.text.font.FontWeight.Medium,
                            color = scheme.onSurface,
                            maxLines = 1,
                            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                            modifier = Modifier.padding(start = 4.dp),
                        )
                    }
                }
                /*
                 * key(url)：换一个应用就换一个 WebView。
                 *
                 * 不加的话 AndroidView 复用同一个实例，from A to B 时页面还停在 A 上
                 * （factory 只跑一次，loadUrl 也只在第一次调用）。
                 */
                androidx.compose.runtime.key(url) {
                    CampusWebView(url = url)
                }
            }
        }
    }
}

/**
 * 是不是校内域名。
 *
 * 必须带那个点：直接 `endsWith("gzucm.edu.cn")` 会连
 * `notgzucm.edu.cn`、`evil-gzucm.edu.cn` 一起放行 —— 这种仿冒域名
 * 加上一个「统一身份认证」的假登录页，就能把学号密码骗走。
 */
private fun isCampusHost(host: String): Boolean =
    host == "gzucm.edu.cn" || host.endsWith(".gzucm.edu.cn")

@SuppressLint("SetJavaScriptEnabled")
@Composable
private fun CampusWebView(url: String) {
    var loading by remember { mutableStateOf(true) }
    var view by remember { mutableStateOf<WebView?>(null) }

    /*
     * 返回键先回退网页，退到头再关面板。
     *
     * 只 enable 在「网页能后退」的时候 —— 否则 BACK 被这个 handler 吃掉，
     * 用户按了没反应，还得再按一次才关面板，比不处理更迷惑。
     */
    BackHandler(enabled = view?.canGoBack() == true) {
        view?.goBack()
    }

    Column(Modifier.fillMaxWidth()) {
        if (loading) {
            LinearProgressIndicator(
                modifier = Modifier.fillMaxWidth(),
                color = androidx.compose.material3.MaterialTheme.colorScheme.primary,
            )
        }
        AndroidView(
            factory = { ctx ->
                WebView(ctx).apply {
                    settings.javaScriptEnabled = true
                    settings.domStorageEnabled = true
                    /*
                     * 让 WebView 尊重页面的 <meta name="viewport">。
                     *
                     * **这是 Chrome 能打开、WebView 打不开的根本原因**：
                     * WebView 默认 useWideViewPort=false，会把页面按 980px 的桌面宽度
                     * 渲染，移动版的样式表和 JS 布局按手机宽度算出来全是 0 ——
                     * 学校那套系统就是整页高度 0、看着白屏，但 DOM 里文字全在
                     * （2026-11-21 探针确认：textLen 148，appH 0）。
                     *
                     * Chrome 移动版默认就是 true，所以同一个地址在浏览器里正常。
                     */
                    settings.useWideViewPort = true
                    settings.loadWithOverviewMode = false
                    /*
                     * 去掉 UA 里的 "; wv" 标记。
                     *
                     * WebView 的默认 UA 会多一段 `; wv`，很多前端框架（包括学校这套）
                     * 会据此判断「这是 App 内嵌浏览器」并走另一套分支。
                     * 2026-11-21 实测：加不加这段，实验室系统在 WebView 里的表现
                     * 完全不同（带 wv 时整页高度算成 0 / 样式全丢）。
                     */
                    settings.userAgentString =
                        settings.userAgentString.replace("; wv", "")
                    // 学校服务不需要读本地文件，关掉少一类攻击面
                    settings.allowFileAccess = false
                    settings.allowContentAccess = false
                    /*
                     * 关掉「算法暗化」。
                     *
                     * Android 13 起 WebView 默认跟随系统深色，把浅色网页**强行反色**。
                     * 学校的系统大多只有浅色版，反色之后：
                     * - 实验室平台 → 白底白字，整页看着像白屏（其实字在那儿）
                     * - 智慧宿管 → 纯黑一片，连标题都看不见
                     * 这两个 2026-11-21 都实测复现过。
                     *
                     * 用的是 androidx.webkit 的兼容 API：API 33+ 的
                     * `settings.forceDark` 已经废弃且不生效，只有
                     * `setAlgorithmicDarkeningAllowed(false)` 管用。
                     */
                    if (
                        androidx.webkit.WebViewFeature.isFeatureSupported(
                            androidx.webkit.WebViewFeature.ALGORITHMIC_DARKENING,
                        )
                    ) {
                        androidx.webkit.WebSettingsCompat.setAlgorithmicDarkeningAllowed(
                            settings,
                            false,
                        )
                    }
                    /*
                     * 允许混合内容。
                     *
                     * 学校这套系统是 https + 8080，但页面内部的接口请求写的是 http
                     * （厂商模板没改干净，HTML 里还留着 `sysglxt.pdsu.edu.cn` 的注释）。
                     * WebView 默认阻止 https 页面加载 http 资源，于是 Vue 挂载到一半
                     * 就停了 —— 表现是整页白屏、`<div id=app>` 空的、无障碍读不到任何文字
                     * （2026-11-21 实测）。Chrome 因为有 HTTPS-First 会自动升级，
                     * 所以同一个地址在浏览器里是好的。
                     */
                    settings.mixedContentMode =
                        android.webkit.WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
                    webViewClient = object : WebViewClient() {
                        override fun onPageStarted(v: WebView?, u: String?, favicon: Bitmap?) {
                            loading = true
                        }

                        override fun onPageFinished(v: WebView?, u: String?) {
                            loading = false
                            /*
                             * 修「容器高度 0」。
                             *
                             * 实验室这套系统的 CSS 里 html/body 是 height:100% 的链式布局，
                             * 在 WebView 里这条链算出来是 0 —— 内容全都渲染好了（探针能读到
                             * 姓名学号、各个菜单），但整页高度 0，看起来就是白屏。
                             * Chrome 里同样地址正常，所以是 WebView 的视口差异。
                             */

                        }

                        override fun shouldOverrideUrlLoading(
                            v: WebView,
                            request: WebResourceRequest,
                        ): Boolean {
                            val uri = request.url
                            /*
                             * 只放 http / https。
                             *
                             * 学校页面里混着 intent://、wxwork://、tel: 这类跳转，
                             * 交给 WebView 处理会直接 ERR_UNKNOWN_URL_SCHEME
                             * （表现为整页白屏），所以一律拦掉。
                             */
                            val scheme = uri.scheme
                            if (scheme != "http" && scheme != "https") return true
                            val host = uri.host ?: return true
                            return if (isCampusHost(host)) {
                                // 校内：留在 WebView 里继续走
                                false
                            } else {
                                /*
                                 * 校外：拦下。这里刻意不给「用浏览器打开」的选项 ——
                                 * 这些页面多数要登录，跳出去也打不开，
                                 * 反而会让用户以为 App 把他踢出去了。
                                 */
                                true
                            }
                        }
                    }
                    loadUrl(url)
                    view = this
                }
            },
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
        )
    }
}
