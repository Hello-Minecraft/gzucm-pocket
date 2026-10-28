package com.gzucm.pocket.core

/**
 * 版本更新日志。
 *
 * 规矩（2026-10-28 定）：**每次发包都要往这里加一条**，
 * 文案照「使用说明.md」开头那段 —— 一句话说清这版干了什么，具体到解决了什么问题。
 *
 * 同时要改：build.gradle.kts 的 versionCode/versionName、VersionTest、使用说明.md。
 */
object ReleaseNotes {

    data class Entry(
        val version: String,
        val date: String,
        /** 一句话概要 */
        val headline: String,
        /** 具体条目，可选 */
        val items: List<String> = emptyList(),
    )

    val entries: List<Entry> = listOf(
        Entry(
            version = "1.2.1",
            date = "2026-10-28",
            headline = "电费改成每天首次打开时后台自动更新（不再弹窗），" +
                "电费页加了「电费不准？」说明，关于软件里能看到更新日志。",
            items = listOf(
                "电费：每天首次打开 App 时静默更新一次，不弹窗；想立刻刷新点「更新」",
                "电费页新增「电费不准？」说明：数据来源、到账时间、绑错房间、余额单位等",
                "设置 → 关于软件 里能看到历次更新日志",
                "打赏作者入口（自愿，不解锁任何功能）",
            ),
        ),
        Entry(
            version = "1.2.0",
            date = "2026-10-28",
            headline = "按周抓取课表（每节课都有上课内容、授课教师、真实日期）、" +
                "课表水平翻页动画、课表自定义背景（可调透明度）、" +
                "电费不足 3 天每日提醒、默认启动页可选。",
            items = listOf(
                "课表改成按周抓取，每节课带「本次内容」，比如中医各家学说分别讲王泰林和张锡纯",
                "翻页改成跟手滑动的水平翻页",
                "背景入口移到课表页右上角，可调透明度",
                "电费不足 3 天会推送提醒",
                "设置里可选默认启动页",
                "修复：电费偶尔显示 0 度（未登录时把空值当成了 0）",
            ),
        ),
        Entry(
            version = "1.1.4",
            date = "2026-10-28",
            headline = "电费彻底修好：余额、当月每日明细、用电趋势都能正常显示。",
            items = listOf(
                "余额改为监听页面自己的请求，不再自己拼参数",
                "修复余额带单位「3.23度」解析成 0 的问题",
                "修复每日明细日期字段取错（dateTime）导致列表为空",
            ),
        ),
        Entry(
            version = "1.1.2",
            date = "2026-10-28",
            headline = "电费改为直接读页面数据、修课表顶部对齐、再修底部遮挡。",
        ),
        Entry(
            version = "1.1.0",
            date = "2026-10-27",
            headline = "底栏改悬浮毛玻璃、状态栏跟随深浅色、内容全屏、手势导航条适配。",
        ),
    )
}
