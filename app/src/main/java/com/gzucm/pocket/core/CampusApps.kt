package com.gzucm.pocket.core

/**
 * 学校服务入口。
 *
 * 数据来自合作者提交的「广中医工作台」原型（2026-11-20），
 * 这里**只保留有正式地址的**：
 *
 * 原型里列了 21 个入口，其中 14 个没有稳定地址（智慧宿管、大仪平台、预约中心、
 * 报修、路路通…都在等学校确认），点开只能弹「入口待确认」。
 * 一排点不开的格子会让人以为 App 坏了，所以先不放 —— 等确认一个补一条
 * [url]，它自己就会出现在网格里，UI 不用改。
 *
 * ## 为什么不搬原型的图标
 *
 * 原型用的是 `material-icons-extended`（约 20MB 依赖，含上万枚图标）。
 * 为了八个格子引一整包图标，包体积涨得比功能还多。
 * 这里改用「首字方块 + 主题色」，和 App 现有的一套视觉更贴。
 */
data class CampusApp(
    val id: String,
    val name: String,
    /** null / 空串 = 入口还没确认，列表里不显示 */
    val url: String? = null,
) {
    val ready: Boolean get() = !url.isNullOrBlank()
}

/**
 * 全部学校服务。
 *
 * 排序按使用频率的直觉：查资料（图书馆）→ 办事（大仪、财务）→ 校外访问（VPN）
 * → 部门主页（教技中心、学工、团委）。常用区会按真实打开次数重新排，
 * 这里只是没打开过时的兜底顺序。
 */
val CampusApps: List<CampusApp> = listOf(
    CampusApp(
        "portal",
        "门户应用中心",
        "https://ehall.gzucm.edu.cn/pages/appmarketsite/site/application/home1?gid=1",
    ),
    CampusApp("library", "图书馆", "https://library.gzucm.edu.cn/index.htm"),
    CampusApp("equipment", "大仪共享平台", "https://yqgx.gzucm.edu.cn/"),
    CampusApp("finance", "财务平台", "https://cwptxmk.gzucm.edu.cn/SFP_Cms/"),
    CampusApp("intranet", "访问内网资源", "https://vpn.sangfor.gzucm.edu.cn:5443"),
    CampusApp("education-tech", "现代教育技术中心", "https://itc.gzucm.edu.cn/"),
    CampusApp("student-affairs", "学工部", "https://xsc.gzucm.edu.cn/"),
    CampusApp("youth-league", "校团委", "https://tw.gzucm.edu.cn/index.htm"),
).filter { it.ready }

/**
 * 常用区默认显示哪几个（还没打开记录时）。
 *
 * 图书馆排第一：查资料是学生最高频的校园网操作，其次才是办事大厅类。
 */
val CampusDefaultCommon = listOf("library", "portal", "equipment")
