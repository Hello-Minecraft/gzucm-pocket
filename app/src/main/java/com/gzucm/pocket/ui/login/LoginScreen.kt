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
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.clickable
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
import com.gzucm.pocket.core.Campus
import com.gzucm.pocket.data.Yjs

private enum class Stage { Jw, Ecard, Graduate }

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
    /** 教务登录成功的瞬间回调（早于一卡通），用来提前开始抓课表 */
    onJwReady: ((Map<String, String>) -> Unit)? = null,
    /** 研究生系统登录成功：只回传 yjsglxt 的 Cookie，没有一卡通 */
    onGraduateLoggedIn: ((yjs: Map<String, String>) -> Unit)? = null,
    /**
     * 进来时默认选中的学段。
     *
     * **默认必须是本科**（致辉 2026-11-02 定）：这个 App 大部分使用者是本科生，
     * 默认停在研究生会让多数人每次都手动切回来。
     *
     * 调用方传的是「用户上次**主动**切换过」的那个学段，
     * 没主动切过就是本科 —— 见 [rememberedCampus]。
     */
    initialCampus: Campus = Campus.UNDERGRAD,
    /** 用户在登录页主动切换学段时回调，用来记住「他自己的选择」 */
    onCampusPicked: (Campus) -> Unit = {},
) {
    /*
     * 学段切换。
     *
     * 两套系统毫无关联：域名不同、Cookie 不同、学期码格式不同，
     * 连登录页都是两个。所以这里让用户明确选一次，不做自动探测 ——
     * 自动探测意味着进登录页前要先盲试两个域名，慢且容易卡住。
     */
    var campus by remember { mutableStateOf(initialCampus) }
    var loading by remember { mutableStateOf(true) }
    var stage by remember(campus) { mutableStateOf(if (campus == Campus.GRADUATE) Stage.Graduate else Stage.Jw) }
    var hint by remember(campus) { mutableStateOf("请用学号和密码登录") }
    var webRef by remember { mutableStateOf<WebView?>(null) }
    // 防止 onPageFinished 与超时兜底重复触发
    var done by remember { mutableStateOf(false) }
    // 一卡通同步的兜底倒计时（秒）
    var countdown by remember { mutableStateOf(8) }
    // 二维码只在桌面版页面里，手机版连 showQrLogin() 函数都没有 —— 必须切 UA
    val qrMode = remember { mutableStateOf(false) }
    val mobileUa = remember { mutableStateOf<String?>(null) }
    val desktopUa =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/122.0.0.0 Safari/537.36"

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

    /**
     * 按具体业务路径采样。根路径取不到 Path=/user 的 JSESSIONID。
     *
     * ⚠️ **判据不能写死成 JSESSIONID**（2026-11-01 致辉报的 bug）。
     *
     * 本科是 Java 应用，登录态在 `JSESSIONID`；
     * 研究生是 ASP.NET，登录态在 **`__LOGINCOOKIE__`**（会话另有一个
     * `ASP.NET_SessionId`）。两套的 cookie 名完全不同。
     *
     * 之前这里只有 `if (c.containsKey("JSESSIONID"))`，
     * 研究生永远命中不了，走兜底合并 —— 而兜底之后
     * 会出现 `__LOGINCOOKIE__` 是空串的情况：
     *   · 空串也 containsKey，Kotlin 侧以为拿到了
     *   · 结果请求课表接口返回空，App 判定「无法获取课表」
     *
     * 现在按域名给不同的「登录凭证名」，并且**要求值非空**才算拿到。
     */
    fun harvest(host: String, vararg paths: String): Map<String, String> =
        LoginFlow.pickSessionSample(host, paths.map { cookieOf("https://$host$it") })

    fun finish() {
        if (done) return
        done = true

        // 研究生学段：只需 yjsglxt 的 Cookie，没有一卡通这一步
        if (campus == Campus.GRADUATE) {
            val yjs = harvest(
                "yjsglxt.gzucm.edu.cn",
                "/gmis5/student/default/index",
                "/gmis5/",
                "/",
            )
            onGraduateLoggedIn?.invoke(yjs)
            return
        }

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
                    // 教务会话已就绪：先把课表抓起来，别死等一卡通那 8 秒
                    onJwReady?.invoke(jwCookie)
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

            Stage.Graduate -> {
                /*
                 * 研究生系统登录。
                 *
                 * 判定比本科简单得多：没有 CAS 跳转，登录页就在 yjsglxt 自己域下，
                 * 登录成功后落到 `/gmis5/student/...`。
                 * 用 URL 判断而不是「等 Cookie」—— 和本科一卡通那次踩的坑同理：
                 * Cookie 的 Path 不确定，等它容易永久卡住。
                 */
                if (LoginFlow.isGraduateLoggedIn(url, finished)) {
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
            /*
             * 学段切换。
             *
             * 放在最顶上，因为它决定「下面这个 WebView 打开哪个系统」——
             * 选错了会登进另一个系统，Cookie 对不上，后面全错。
             * 所以要在输入之前就选清楚，不能藏在设置里。
             */
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .statusBarsPadding()
                    .padding(horizontal = 20.dp, vertical = 10.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Campus.entries.forEach { c ->
                    val on = campus == c
                    Text(
                        c.label,
                        fontSize = 12.sp,
                        fontWeight = if (on) FontWeight.Medium else FontWeight.Normal,
                        color = if (on) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .background(
                                if (on) MaterialTheme.colorScheme.primary.copy(alpha = 0.14f)
                                else MaterialTheme.colorScheme.surface,
                            )
                            .clickable {
                                if (campus == c) return@clickable
                                campus = c
                                /*
                                 * 只有**主动点击**才记住。
                                 *
                                 * 不在别处记忆「上次登录的学段」——
                                 * 那样本科生登录过一次研究生账号（比如帮别人看），
                                 * 之后每次打开登录页都停在研究生，非常坑。
                                 */
                                onCampusPicked(c)
                                done = false
                                qrMode.value = false
                                hint = if (c == Campus.GRADUATE) {
                                    "请用研究生系统的学号密码登录"
                                } else {
                                    "请用学号和密码登录"
                                }
                                /*
                                 * 这里只改状态，**不手动 loadUrl**。
                                 *
                                 * 因为下面 WebView 外面套了 `key(campus)`：
                                 * campus 一变，旧 WebView 整个被销毁、新的重建，
                                 * factory 里会用 `LoginFlow.entryUrl()` 加载
                                 * 对应学段的地址。
                                 *
                                 * 如果这里再补一次 loadUrl，会出现两个问题：
                                 *   · Compose 重组是异步的，此刻 webRef 很可能
                                 *     还指向**旧** WebView —— 于是旧实例去加载
                                 *     研究生页面，旧实例的 onPageFinished 仍会
                                 *     照常跑 finish()，可能把本科学段的状态
                                 *     写进去
                                 *   · 新实例还要再加载一次，白跑一个请求
                                 *
                                 * 一开始没有 key(campus) 时才需要手动 loadUrl
                                 * （reload() 更是错的，它只重载当前地址，
                                 * 切到研究生还停在本科 ssoLogin 上，
                                 * 于是看到统一认证页 —— 就是他报的那个 bug）。
                                 * 现在重建这条路径已经覆盖了，手动那次可以去掉。
                                 */
                                qrMode.value = false
                            }
                            .padding(horizontal = 14.dp, vertical = 7.dp),
                    )
                }
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 20.dp, end = 8.dp, top = 2.dp, bottom = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        when (stage) {
                            Stage.Ecard -> "正在读取校园数据"
                            Stage.Graduate -> "登录研究生系统"
                            Stage.Jw -> "登录广中医"
                        },
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
                // 扫码只在本科教务有；研究生系统没这个入口，别显示一个点了没用的按钮
                if (campus != Campus.GRADUATE) Text(
                    if (qrMode.value) "账号密码" else "扫码登录",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .clickable {
                            val on = !qrMode.value
                            qrMode.value = on
                            webRef?.let { w ->
                                w.settings.userAgentString =
                                    if (on) desktopUa else mobileUa.value
                                hint = if (on) {
                                    "扫码用微信确认身份，不需要教务密码"
                                } else {
                                    "请用学号和密码登录"
                                }
                                w.reload()
                            }
                        }
                        .padding(horizontal = 8.dp, vertical = 6.dp),
                )
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

            StepBar(stage = stage, campus = campus)

            // 网页在上面、提示在下面会被 WebView 整块盖住（Box 里后声明的画在上层），
            // 所以改成纵向排列：提示占自己的一行，网页吃掉剩下的高度。
            Column(
                Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp)
                    .clip(RoundedCornerShape(18.dp))
                    .background(
                        MaterialTheme.colorScheme.surface,
                        RoundedCornerShape(18.dp),
                    ),
            ) {
                // 扫码提示：微信不认「从相册选」的截图，必须另一台设备扫
                if (qrMode.value) {
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 14.dp, vertical = 8.dp)
                            .clip(RoundedCornerShape(10.dp))
                            .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.09f))
                            .padding(horizontal = 12.dp, vertical = 9.dp),
                    ) {
                        Text(
                            "⚠",
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.primary,
                        )
                        Spacer(Modifier.width(8.dp))
                        Column {
                            Text(
                                "二维码要用另一台设备扫",
                                fontSize = 11.5.sp,
                                fontWeight = FontWeight.Medium,
                                color = MaterialTheme.colorScheme.primary,
                            )
                            Spacer(Modifier.height(2.dp))
                            Text(
                                "微信不认相册里的截图。让另一台手机直接扫这个屏幕，" +
                                    "或者把屏幕拍下来、扫那张照片。",
                                fontSize = 10.5.sp,
                                lineHeight = 15.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }

                /*
                 * key(campus)：学段变了就重建 WebView。
                 *
                 * AndroidView 的 factory 只会在组件首次进入组合时执行一次。
                 * 不加 key 的话，用户先切到研究生、再让 WebView 首次创建时，
                 * 用的还是本科的初始 URL —— 又绕回统一认证去了。
                 *
                 * 重建代价可接受：切学段本身就是个低频操作，而且旧的
                 * WebView 里的登录状态在两个系统之间本来也不通用。
                 */
                androidx.compose.runtime.key(campus) {
                AndroidView(
                    modifier = Modifier.fillMaxWidth().weight(1f),
                    factory = { ctx ->
                        WebView(ctx).apply {
                            layoutParams = ViewGroup.LayoutParams(
                                ViewGroup.LayoutParams.MATCH_PARENT,
                                ViewGroup.LayoutParams.MATCH_PARENT,
                            )
                            settings.javaScriptEnabled = true
                            settings.domStorageEnabled = true
                            settings.databaseEnabled = true

                            /*
                             * 页面缩放 —— 只对研究生系统开。
                             *
                             * 研究生系统是**按 PC 设计的固定宽度页面**，它自己
                             * 页面底部就写着「1280*800 分辨率以上浏览本系统」。
                             * 手机上不处理的话左边一大截直接裁掉，而用户名输入框
                             * 恰好就在左边 —— 用户根本没法登。
                             *
                             * 本科教务页本来就是自适应的，用户一直用得好好的。
                             * 给它开这些反而有风险：万一它没有 viewport meta，
                             * 布局视口会从「设备宽」变成 980px，字会突然变小，
                             * 等于制造一个新 bug。所以严格按学段分。
                             *
                             * 【为什么还要在 JS 里再注入一次 viewport】
                             * 只靠下面这两个开关是不够的：安卓 WebView 在
                             * useWideViewPort=true 且页面**没有** viewport meta 时，
                             * 会把布局视口取成 980px（DEFAULT_VIEWPORT_WIDTH），
                             * 而页面内容按 1280 排 → 右边 300px 依然溢出。
                             * 真正的修法是让布局视口等于设计宽度，所以
                             * onPageFinished 里会注入 width=1280 的 viewport，
                             * 再由 loadWithOverviewMode 把整页缩进屏宽。
                             *
                             * setInitialScale 在这里不设：它只改缩放不改布局
                             * 视口，页面仍按 980 排，1280 的内容照样溢出；
                             * 而且它和 loadWithOverviewMode 同时存在时行为
                             * 依安卓版本而异，容易互相覆盖。宁可不设。
                             */
                            if (LoginFlow.needsFitToScreen(campus == Campus.GRADUATE)) {
                                settings.useWideViewPort = true
                                settings.loadWithOverviewMode = true
                                settings.setSupportZoom(true)
                                settings.builtInZoomControls = true
                                settings.displayZoomControls = false
                            }

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
                                    // 桌面版加载完再点开二维码视图（页面自带 showQrLogin）
                                    if (qrMode.value && url?.contains("authserver/login") == true) {
                                        view?.evaluateJavascript(
                                            "try{showQrLogin && showQrLogin();}catch(e){}",
                                            null,
                                        )
                                    }
                                    // 研究生系统是固定 1280 宽的 PC 页面，强制其布局视口也等于 1280
                                    if (LoginFlow.needsFitToScreen(campus == Campus.GRADUATE)) {
                                        view?.evaluateJavascript(
                                            FIT_WIDTH_JS.replace(
                                                "DESIGN_WIDTH",
                                                LoginFlow.GRADUATE_DESIGN_WIDTH.toString(),
                                            ),
                                            null,
                                        )
                                    }
                                    url?.let { handle(it, view, finished = true) }
                                }
                            }
                            mobileUa.value = settings.userAgentString
                            webRef = this
                            loadUrl(LoginFlow.entryUrl(campus == Campus.GRADUATE))
                        }
                    },
                    update = { webRef = it },
                )
                } // key(campus)
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
                    if (campus == Campus.GRADUATE) {
                        "账号密码只提交给研究生系统，App 不保存、不上传"
                    } else {
                        "账号密码只提交给学校认证服务器，App 不保存、不上传"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
/**
 * 登录步骤条。
 *
 * 本科两步（教务 → 校园卡），研究生一步 —— 研究生系统登完就完事，
 * 没有一卡通那一段。硬画两步会让用户以为还有个「校园卡」要等。
 */
private fun StepBar(stage: Stage, campus: Campus) {
    if (campus == Campus.GRADUATE) return
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

/**
 * 把研究生系统的布局视口强行改成 1280（它的设计宽度）。
 *
 * 为什么必须在 JS 里做：
 *   研究生页面按 1280 排（它自己写着「1280*800 分辨率以上浏览本系统」），
 *   但**没有 viewport meta**。这种情况下 WebView 即使开了
 *   `useWideViewPort`，布局视口也只取 980px（DEFAULT_VIEWPORT_WIDTH），
 *   于是右边 300px 永远露不出来 —— 用户看到的是「左边看不到」，
 *   其实是整页比屏幕宽、对齐左上角后右侧溢出了屏幕。
 *
 * 三步：
 *   ① 声明 `width=1280`，让布局视口 = 内容宽度；
 *   ② 降级检查：万一哪天学校加了 `user-scalable=no`，
 *      loadWithOverviewMode 会失效 → 用 CSS zoom 按屏宽硬缩一次；
 *   ③ **不写 initial-scale** —— 写死比例只能对一种屏正确：
 *      按手机算的值在平板上会把页面缩成一小团，反之又超出屏幕。
 *      让 WebView 按设备宽度自己算，两种屏都对。
 *
 * 页面若已有 viewport meta 就改它，没有就新建一个。
 */
private const val FIT_WIDTH_JS = """
(function(){
  try {
    var vp = document.querySelector('meta[name=viewport]');
    if (!vp) {
      vp = document.createElement('meta');
      vp.name = 'viewport';
      (document.head || document.documentElement).appendChild(vp);
    }
    vp.setAttribute('content', 'width=DESIGN_WIDTH, user-scalable=yes');
  } catch (e) {}

  // 降级：等一帧让 WebView 重新布局，仍溢出就按屏宽 CSS 缩放
  setTimeout(function(){
    try {
      var de = document.documentElement;
      var vw = window.innerWidth;
      if (vw > 0 && de.scrollWidth > vw + 8) {
        de.style.zoom = (vw / de.scrollWidth);
      }
    } catch (e) {}
  }, 150);
})();
"""
