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

    fun isAuthHost(url: String): Boolean = url.contains(AUTH_HOST)

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