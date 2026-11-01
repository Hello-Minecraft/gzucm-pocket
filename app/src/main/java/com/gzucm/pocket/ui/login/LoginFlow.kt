package com.gzucm.pocket.ui.login

/**
 * 登录状态机的判定逻辑。
 *
 * 抽成纯函数是为了能单测 —— 这段逻辑出过一次线上事故：
 * 一卡通的 JSESSIONID 在 `Path=/user` 与 `/sso` 下，用根路径取永远是空，
 * 于是「等一卡通 cookie」这个条件永不成立，界面就永久卡在「正在同步校园卡」。
 *
 * 现在的原则：**一卡通只是顺带同步，不许成为登录的门槛。**
 * 只要页面落到一卡通域、且不是登录中转页，就直接放行进 App；
 * 就算 cookie 没拿到，最坏情况是电费页空着，课表成绩照常。
 */
internal object LoginFlow {

    private const val AUTH_HOST = "authserver.gzucm.edu.cn"
    private const val JW_HOST = "jw.gzucm.edu.cn"
    private const val ECARD_HOST = "ecard.gzucm.edu.cn"

    private const val YJS_HOST = "yjsglxt.gzucm.edu.cn"

    /** 本科教务登录入口：会 302 到 CAS（authserver） */
    const val UNDERGRAD_ENTRY = "https://$JW_HOST/new/ssoLogin"

    /** 研究生系统登录入口：就在自己域下，**不经过** CAS */
    const val GRADUATE_ENTRY = "https://$YJS_HOST/gmis5/student/default/index"

    fun isAuthHost(url: String): Boolean = url.contains(AUTH_HOST)

    /**
     * 该学段的登录页地址。
     *
     * 单独抽成函数是为了能单测 —— 这里曾经写死过：
     * 切换学段时只调了 `WebView.reload()`，而 reload 只会重载**当前** URL，
     * 于是切到研究生后还停在本科的 ssoLogin 上，用户看到的是统一认证平台，
     * 以为「研究生要绑统一认证」。
     */
    fun entryUrl(graduate: Boolean): String =
        if (graduate) GRADUATE_ENTRY else UNDERGRAD_ENTRY

    /**
     * 研究生系统页面的设计宽度（CSS px）。
     *
     * 依据是它自己页脚写的那句「1280*800 分辨率以上浏览本系统」。
     * 实测页面也没有 viewport meta，所以在手机上会被当成 980 宽排版，
     * 而内容按 1280 排 → 右侧溢出、左侧看不全。
     */
    const val GRADUATE_DESIGN_WIDTH = 1280

    /**
     * 这个学段要不要把页面「缩进屏宽」。
     *
     * **只有研究生要。**
     *
     * 本科教务页是自适应的，用户一直用得好好的 —— 给它开
     * useWideViewPort 反而有风险：万一它没有 viewport meta，
     * 布局视口会从「设备宽」变成 980px，字会突然变小，
     * 等于为了修一个问题制造另一个问题。
     *
     * 抽成函数而不是内联判断，是为了让 [com.gzucm.pocket.WebViewFitWidthTest]
     * 测的是**真实实现**，而不是测试里另抄一份镜像逻辑（那样测了个寂寞）。
     */
    fun needsFitToScreen(graduate: Boolean): Boolean = graduate

    /**
     * 登录页该默认选中哪个学段。
     *
     * **没主动选过 → 本科**（致辉 2026-11-02 定）。
     *
     * 这个 App 的大部分使用者是本科生，默认停在研究生等于坑大多数人：
     * 每次打开都要手动点回「本科生」。
     *
     * 关键是**只认「主动选择」**，不认「上次登录用的学段」。
     * 后者会这样坑人：本科生帮研究生同学看课表 → 登录过一次研究生 →
     * 之后自己打开 App，登录页永远停在研究生。
     *
     * @param picked 用户主动点过的学段，没点过是 null
     */
    fun loginDefaultCampus(picked: com.gzucm.pocket.core.Campus?): com.gzucm.pocket.core.Campus =
        picked ?: com.gzucm.pocket.core.Campus.UNDERGRAD

    /**
     * 这套系统的「登录凭证」是哪个 cookie 名。
     *
     * 本科是 Java（`JSESSIONID`），研究生是 ASP.NET（`__LOGINCOOKIE__`）。
     * 两套完全不同，判断「拿到登录态没有」时必须按域名区分 ——
     * 写死 JSESSIONID 会让研究生永远判成「没登录」。
     */
    fun sessionCookieName(host: String): String = when {
        host.contains("yjsglxt") -> "__LOGINCOOKIE__"
        host.contains("ecard") -> "JSESSIONID"
        else -> "JSESSIONID"
    }

    /**
     * 从一堆采样结果里挑出「真的带了登录凭证」的那份。
     *
     * 关键点：ASP.NET 会先把 `__LOGINCOOKIE__` 写成**空串**占位。
     * 只判 `containsKey` 会把空串当成有效会话，
     * 于是上层以为登录成功、拿空 cookie 去请求，接口返回空 → 「无法获取课表」。
     *
     * @param samples 按路径顺序采到的 cookie 表
     * @return 第一份凭证非空的表；都没有时返回合并结果（仍可能不含凭证，
     *         调用方需要自己判断是不是真的登上了）
     */
    fun pickSessionSample(
        host: String,
        samples: List<Map<String, String>>,
    ): Map<String, String> {
        val key = sessionCookieName(host)
        for (c in samples) {
            if (!c[key].isNullOrBlank()) return c
        }
        // 兜底：合并并丢掉空值，别把 __LOGINCOOKIE__="" 带出去
        val merged = LinkedHashMap<String, String>()
        samples.forEach { c -> c.forEach { (k, v) -> if (v.isNotBlank()) merged[k] = v } }
        return merged
    }

    /** 这份 cookie 里有没有有效的登录凭证 */
    fun hasSession(host: String, cookies: Map<String, String>): Boolean =
        !cookies[sessionCookieName(host)].isNullOrBlank()

    /**
     * 研究生系统登录成功了吗？
     *
     * 判据比本科简单：没有 CAS 跳转，登录后 URL 落在 `/gmis5/student/` 下。
     * 反过来，只要还在 authserver 或登录页，就一律不算。
     */
    fun isGraduateLoggedIn(url: String, pageFinished: Boolean): Boolean {
        if (!pageFinished) return false
        if (url.contains(AUTH_HOST)) return false
        if (!url.contains(YJS_HOST)) return false
        if (url.contains("/login") || url.contains("/Login")) return false
        return url.contains("/gmis5/student/")
    }

    /**
     * 教务登录成功了吗？成功则应该跳去一卡通。
     *
     * 必须等页面**加载完成**（cookie 已写入）再判，否则 onPageStarted 时会误判。
     */
    fun shouldHopToEcard(
        url: String,
        hasJwSession: Boolean,
        pageFinished: Boolean,
    ): Boolean {
        if (!pageFinished) return false
        if (isAuthHost(url)) return false
        val onJw = url.contains(JW_HOST) || url.contains("welcome.page")
        return onJw && hasJwSession
    }

    /**
     * 一卡通这一步可以结束了吗？
     *
     * 只看 URL，**不看 cookie** —— 这是修复卡死的关键。
     */
    fun canFinishEcard(url: String, pageFinished: Boolean): Boolean {
        if (!pageFinished) return false
        if (isAuthHost(url)) return false
        if (!url.contains(ECARD_HOST)) return false
        // /sso/login 是跳转入口，/sso/index 是 CAS 回跳中转，都还不算到站
        return !url.contains("/sso/login") && !url.contains("/sso/index")
    }
}