package com.gzucm.pocket.ui.power

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.util.Log
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.gzucm.pocket.core.PowerDaily
import com.gzucm.pocket.core.PowerInfo
import com.gzucm.pocket.data.Ecard

/**
 * 静默同步电费：**不弹窗、不打断用户**。
 *
 * 为什么必须用 WebView：一卡通的会话只存在于 WebView 里，
 * OkHttp 那条路拿不到（这个坑踩过很多次）。所以做成一个 1dp、不可见、
 * 不接收点击的 WebView，挂在页面树里跑一遍，拿到数据就销毁。
 *
 * 触发时机由调用方控制（现在是「每天首次打开 App」）。
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun PowerSilentSync(
    active: Boolean,
    onDone: (PowerInfo, List<PowerDaily>) -> Unit,
    onFail: (String) -> Unit = {},
) {
    if (!active) return

    // 供桥接层延迟重试时拿到 WebView
    val holder = remember { object { var web: WebView? = null } }
    val main = remember { android.os.Handler(android.os.Looper.getMainLooper()) }

    val bridge = remember {
        object {
            var balanceRaw: String? = null
            var pageHtml: String = ""
            var dailyJson: String? = null
            var finished = false

            @JavascriptInterface
            fun balance(json: String, html: String) {
                balanceRaw = json
                pageHtml = html
                tryFinish()
            }

            var retriedDaily = false

            @JavascriptInterface
            fun dailyJson(json: String) {
                // 明细空不一定是真没数据 —— 页面刚起来时分页可能还没跑完，重试一次
                if (!retriedDaily && Ecard.parseDailyJson(json).isEmpty()) {
                    retriedDaily = true
                    Log.i("EcardSilent", "daily empty, retry once")
                    main.postDelayed({ holder.web?.evaluateJavascript(EXTRACT_JS, null) }, 1_500)
                    return
                }
                dailyJson = json
                tryFinish()
            }

            @JavascriptInterface
            fun daily(html: String) {
                // 静默模式不需要 HTML 兜底
            }

            @JavascriptInterface
            fun log(msg: String) {
                Log.i("EcardSilent", msg)
            }

            @JavascriptInterface
            fun fail(msg: String) {
                if (finished) return
                finished = true
                Log.w("EcardSilent", "fail: $msg")
                onFail(msg)
            }

            fun tryFinish() {
                if (finished) return
                val b = balanceRaw ?: return
                val d = dailyJson ?: return
                val info = Ecard.parseBalance(b, pageHtml) ?: run {
                    finished = true
                    onFail("余额解析失败")
                    return
                }
                finished = true
                onDone(info, Ecard.parseDailyJson(d))
            }
        }
    }

    AndroidView(
        modifier = Modifier.size(2.dp),
        factory = { ctx ->
            WebView(ctx).apply {
                holder.web = this
                setBackgroundColor(0)
                alpha = 0.01f
                settings.javaScriptEnabled = true
                settings.domStorageEnabled = true
                settings.userAgentString = (settings.userAgentString ?: "") + " GzucmPocket"
                CookieSync.attach(this)
                addJavascriptInterface(bridge, "GzBridge")

                webViewClient = object : WebViewClient() {
                    override fun onPageStarted(v: WebView?, url: String?, favicon: Bitmap?) {
                        if (url != null && url.contains("ecard.gzucm.edu.cn")) {
                            v?.evaluateJavascript(HOOK_JS, null)
                        }
                    }

                    override fun onPageFinished(v: WebView?, url: String?) {
                        if (url == null) return
                        if (!url.contains("ecard.gzucm.edu.cn")) return
                        if (url.contains("/sso/login") || url.contains("authserver")) {
                            // 会话失效：静默失败，别打扰用户
                            onFail("未登录")
                            return
                        }
                        if (url.contains("/user/powerfee/")) {
                            v?.evaluateJavascript(EXTRACT_JS, null)
                        } else {
                            v?.loadUrl("${Ecard.BASE}/user/powerfee/index")
                        }
                    }
                }
                loadUrl("${Ecard.BASE}/user/powerfee/index")
            }
        },
        update = { },
    )

    // 跑完就销毁，别留着占内存
    DisposableEffect(Unit) {
        onDispose { }
    }
}

/**
 * 把 App 已经种下的 cookie 灌给 WebView。
 *
 * 登录时抓到的 JSESSIONID 在 Net.cookies 里，WebView 默认看不到，
 * 不同步过去的话静默同步会一直跳登录页。
 */
internal object CookieSync {
    fun attach(web: WebView) {
        runCatching {
            val jar = com.gzucm.pocket.core.Net.cookies
            val cm = android.webkit.CookieManager.getInstance()
            for (host in listOf("ecard.gzucm.edu.cn", "jw.gzucm.edu.cn")) {
                // snapshot 返回 name→value；一卡通在 /user 下还有一个同名 JSESSIONID，
                // 这里按根路径灌一次就够，WebView 自己会带上
                jar.snapshot(host).forEach { (name, value) ->
                    if (value.isNotBlank()) {
                        cm.setCookie("https://$host", "$name=$value; path=/")
                        cm.setCookie("https://$host", "$name=$value; path=/user")
                    }
                }
            }
            cm.flush()
        }
    }
}
