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
                CampusWebView(url = url)
            }
        }
    }
}

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
                    // 学校服务不需要读本地文件，关掉少一类攻击面
                    settings.allowFileAccess = false
                    settings.allowContentAccess = false
                    webViewClient = object : WebViewClient() {
                        override fun onPageStarted(v: WebView?, u: String?, favicon: Bitmap?) {
                            loading = true
                        }

                        override fun onPageFinished(v: WebView?, u: String?) {
                            loading = false
                        }

                        override fun shouldOverrideUrlLoading(
                            v: WebView,
                            request: WebResourceRequest,
                        ): Boolean {
                            val host = request.url.host ?: return true
                            return if (host.endsWith("gzucm.edu.cn")) {
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
