package com.gzucm.pocket.ui.login

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView

private enum class Stage { Jw, Ecard }

/**
 * 登录页。
 *
 * 两段式：
 *  1. WebView 打开教务的 CAS 登录（`/new/ssoLogin`），用户在学校自己的页面输账号密码，
 *     天爱滑动验证码也在这里过。
 *  2. 教务登录成功后，**同一个 WebView 会话**再去一卡通的 SSO 入口，
 *     因为两边共用 authserver，第二次通常免密直接过，只为把 ecard 的 Cookie 落下。
 *
 * App 全程不接触密码，只取学校页面落下的 Cookie。
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun LoginScreen(
    onLoggedIn: (jw: Map<String, String>, ecard: Map<String, String>) -> Unit,
    onEcardData: (balanceJson: String?, dailyJson: String?) -> Unit = { _, _ -> },
) {
    var loading by remember { mutableStateOf(true) }
    var stage by remember { mutableStateOf(Stage.Jw) }
    var hint by remember { mutableStateOf("请用学号和密码登录") }
    var webRef by remember { mutableStateOf<WebView?>(null) }
    // 防止 onPageFinished 与超时兜底重复触发
    var done by remember { mutableStateOf(false) }
    // 一卡通同步的兜底倒计时（秒）
    var countdown by remember { mutableStateOf(8) }
    // 电费取数只注入一次
    var powerInjecting by remember { mutableStateOf(false) }
    var pendingBalance by remember { mutableStateOf<String?>(null) }
    var pendingDaily by remember { mutableStateOf<String?>(null) }

    BackHandler(enabled = true) {
        webRef?.let { if (it.canGoBack()) it.goBack() }
    }

    fun cookieOf(url: String): Map<String, String> {
        val raw = CookieManager.getInstance().getCookie(url) ?: return emptyMap()
        return raw.split(";").mapNotNull { part ->
            val t = part.trim()
            val i = t.indexOf('=')
            if (i <= 0) null else t.substring(0, i) to t.substring(i + 1)
        }.toMap()
    }

    /** 按具体业务路径采样。根路径取不到 Path=/user 的 JSESSIONID。 */
    fun harvest(host: String, vararg paths: String): Map<String, String> {
        for (p in paths) {
            val c = cookieOf("https://$host$p")
            if (c.containsKey("JSESSIONID")) return c
        }
        // 兜底：合并所有路径能看到的
        val merged = LinkedHashMap<String, String>()
        paths.forEach { merged.putAll(cookieOf("https://$host$it")) }
        return merged
    }

    fun finish() {
        if (done) return
        done = true
        val jw = harvest(
            "jw.gzucm.edu.cn",
            "/new/welcome.page?ui=new",
            "/new/index",
            "/",
        )
        // 一卡通的会话在 /user 下；/sso 那条是登录中转，不能当业务会话用
        val ecard = harvest(
            "ecard.gzucm.edu.cn",
            "/user/index",
            "/user/powerfee/index",
            "/",
        )
        onLoggedIn(jw, ecard)
    }

    fun handle(url: String, view: WebView?, finished: Boolean) {
        when (stage) {
            Stage.Jw -> {
                // 教务的 JSESSIONID 是根路径的，从这里取没问题
                val jwCookie = cookieOf("https://jw.gzucm.edu.cn")
                if (LoginFlow.shouldHopToEcard(url, jwCookie.containsKey("JSESSIONID"), finished)) {
                    stage = Stage.Ecard
                    hint = "正在同步校园卡…"
                    view?.loadUrl("https://ecard.gzucm.edu.cn/sso/login?plat=gzzyy")
                }
            }

            Stage.Ecard -> {
                // 只看 URL 不看 cookie：一卡通的 JSESSIONID 在 /user 下，
                // 用根路径取永远是空，原来的「等 cookie」条件永不成立 → 卡死。
                if (LoginFlow.canFinishEcard(url, finished)) {
                    finish()
                }
            }
        }
    }

    /**
     * 一卡通只是「顺带同步」，绝不能成为登录的必过门槛。
     * 用户可能在 SSO 页多停一下、或者需要手点一次，这里给 8 秒兜底：
     * 超时就直接进 App，电费拿不到最多是电费页空着，不影响课表成绩。
     */
    LaunchedEffect(stage) {
        if (stage == Stage.Ecard && !done) {
            countdown = 8
            while (countdown > 0 && !done) {
                kotlinx.coroutines.delay(1_000)
                countdown -= 1
            }
            if (!done) {
                hint = "校园卡稍后再同步，先进入应用"
                finish()
            }
        }
    }

    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(Modifier.fillMaxSize()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .statusBarsPadding()
                    .padding(start = 20.dp, end = 8.dp, top = 14.dp, bottom = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        if (stage == Stage.Ecard) "正在读取校园数据" else "登录广中医",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Medium,
                    )
                    Spacer(Modifier.height(2.dp))
                    Text(
                        if (stage == Stage.Ecard && !done) {
                            "同步校园卡 · ${countdown}s 后自动进入"
                        } else {
                            hint
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = if (stage == Stage.Ecard) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (loading || (stage == Stage.Ecard && !done)) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(18.dp).padding(end = 6.dp),
                        strokeWidth = 2.dp,
                    )
                }
                IconButton(onClick = { webRef?.reload() }) {
                    Icon(
                        Icons.Rounded.Refresh,
                        contentDescription = "刷新",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            // 同步阶段：一条走完的进度条，让人知道还剩多久，而不是干等
            if (stage == Stage.Ecard && !done) {
                androidx.compose.material3.LinearProgressIndicator(
                    progress = { (8 - countdown) / 8f },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(2.dp)
                        .padding(horizontal = 20.dp),
                    strokeCap = androidx.compose.ui.graphics.StrokeCap.Round,
                    color = MaterialTheme.colorScheme.primary,
                    trackColor = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f),
                )
                Spacer(Modifier.height(8.dp))
            }

            StepBar(stage = stage)

            Box(
                Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp)
                    .background(
                        MaterialTheme.colorScheme.surface,
                        RoundedCornerShape(18.dp),
                    ),
            ) {
                AndroidView(
                    modifier = Modifier.fillMaxSize(),
                    factory = { ctx ->
                        WebView(ctx).apply {
                            layoutParams = ViewGroup.LayoutParams(
                                ViewGroup.LayoutParams.MATCH_PARENT,
                                ViewGroup.LayoutParams.MATCH_PARENT,
                            )
                            settings.javaScriptEnabled = true
                            settings.domStorageEnabled = true
                            settings.databaseEnabled = true
                            CookieManager.getInstance().setAcceptCookie(true)
                            CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
                            webViewClient = object : WebViewClient() {
                                override fun onPageStarted(
                                    view: WebView?,
                                    url: String?,
                                    favicon: Bitmap?,
                                ) {
                                    loading = true
                                    // 页面刚开始加载，cookie 还没写入，不做完成判定
                                    url?.let { handle(it, view, finished = false) }
                                }

                                override fun onPageFinished(view: WebView?, url: String?) {
                                    loading = false
                                    url?.let { handle(it, view, finished = true) }
                                }
                            }
                            webRef = this
                            loadUrl("https://jw.gzucm.edu.cn/new/ssoLogin")
                        }
                    },
                    update = { webRef = it },
                )
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    Modifier
                        .size(5.dp)
                        .background(MaterialTheme.colorScheme.primary, CircleShape),
                )
                Text(
                    "账号密码只提交给学校认证服务器，App 不保存、不上传",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun StepBar(stage: Stage) {
    val scheme = MaterialTheme.colorScheme
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        listOf("教务系统" to Stage.Jw, "校园卡" to Stage.Ecard).forEachIndexed { i, (label, s) ->
            val done = stage == Stage.Ecard && s == Stage.Jw
            val active = stage == s
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier
                        .size(6.dp)
                        .background(
                            when {
                                done -> scheme.primary
                                active -> scheme.primary.copy(alpha = 0.8f)
                                else -> scheme.onSurfaceVariant.copy(alpha = 0.25f)
                            },
                            CircleShape,
                        ),
                )
                Spacer(Modifier.width(5.dp))
                Text(
                    label,
                    fontSize = 10.5.sp,
                    color = when {
                        done || active -> scheme.primary
                        else -> scheme.onSurfaceVariant.copy(alpha = 0.6f)
                    },
                )
            }
            if (i == 0) {
                Spacer(Modifier.width(9.dp))
                Box(
                    Modifier
                        .width(16.dp)
                        .height(1.dp)
                        .background(scheme.outlineVariant),
                )
                Spacer(Modifier.width(9.dp))
            }
        }
    }
}