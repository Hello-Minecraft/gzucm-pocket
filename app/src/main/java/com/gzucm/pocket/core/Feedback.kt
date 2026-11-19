package com.gzucm.pocket.core

/**
 * 问题反馈：问卷地址 + 版本号。
 *
 * ## 为什么走问卷而不是应用内直传
 *
 * 这个项目不维护服务器（见 [Updater] 里「为什么直连 GitHub」那段），
 * 为一年几十条反馈单独上一套后端，等于给自己加一个要续期、要盯着的东西。
 * 腾讯问卷免费版能收图片/视频、填写**不需要登录**，是这里性价比最高的一条路。
 * 它是外部链接，所以也不牵扯 App 的网络权限和隐私承诺。
 *
 * 代价：问卷是别人家的服务，用户点「打开问卷」会跳出 App。
 * 所以反馈页必须把**版本号**大字摆出来 —— 用户跳出去之后回不来，
 * 靠的是他自己记住（或已经复制）这一串。
 */
object Feedback {

    /**
     * 问卷地址。**建好问卷后把链接填这里，否则界面会退化成「复制版本号」。**
     *
     * 腾讯问卷：`https://wj.qq.com/s2/xxxxxxxx/xxxx/`
     * 问卷星：`https://www.wjx.cn/vm/xxxxxxxx.aspx`
     *
     * 建表步骤与题目文案见仓库 `docs/FEEDBACK_SURVEY.md`。
     * 换链接只改这一行，不用动界面。
     *
     * 当前线上问卷：广中医口袋 · 问题反馈（腾讯问卷，2026-11-19 建）
     */
    const val SurveyUrl: String = "https://wj.qq.com/s2/28748880/w40u/"

    /** 有可用问卷地址时才显示「打开问卷」。 */
    val hasSurvey: Boolean get() = SurveyUrl.isNotBlank()

    /**
     * App 版本号，例如 `1.3.6`。
     *
     * 用 `runCatching` 兜底：`BuildConfig` 在极少数单测/预览环境下取不到，
     * 那时候显示 `—` 也比崩掉强。
     */
    val appVersion: String
        get() = runCatching { com.gzucm.pocket.BuildConfig.VERSION_NAME }.getOrDefault("—")
}
