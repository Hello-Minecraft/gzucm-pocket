package com.gzucm.pocket.core

/**
 * 学校服务的打开方式。
 *
 * 2026-11-21 定稿：**绝大多数服务都是统一身份认证（CAS）的普通网页**，
 * App 的 WebView 登录一次教务就全部免登。
 *
 * 下面三个类型保留是为了以后可能的变化，当前数据全是 [WEB]。
 */
enum class CampusAppKind {
    /** App 内 WebView 直接打开。走统一身份认证或本来就免登录 */
    WEB,

    /**
     * 只能企业微信：点了跳「企业微信 → 工作台」。
     *
     * **当前没有应用用它**（2026-11-21 更新）。
     *
     * 之前以为智慧宿管必须企业微信，是因为从企业微信工作台点进去看到的是
     * `open.weixin.qq.com/connect/oauth2/authorize`。后来在信息门户的
     * 应用市场里找到了它的 CAS 入口
     * `acs.gzucm.edu.cn/platform/casLogin.php?system=dormitory`，
     * 一样能用学号密码登录。所以这条路径留作兜底，暂不启用。
     */
    WEWORK,

    /** 已知打不开（证书过期、系统下线），卡片置灰只作告知 */
    BROKEN,
}

data class CampusApp(
    val id: String,
    val name: String,
    /** null / 空串 = 入口还没确认，列表里不显示 */
    val url: String? = null,
    val kind: CampusAppKind = CampusAppKind.WEB,
    /**
     * 只能用系统浏览器打开（App 内 WebView 跑不起来）。
     *
     * 目前只有「实验室平台」是这种情况，原因见下面那条注释。
     */
    val external: Boolean = false,
    /** 卡片右上角的小角标，如「企微」 */
    val badge: String = "",
    /** [CampusAppKind.BROKEN] 的原因，点卡片时 Toast 出来 */
    val note: String = "",
) {
    val ready: Boolean get() = !url.isNullOrBlank() || kind == CampusAppKind.WEWORK
}

/**
 * 全部学校服务。
 *
 * ## 数据来源（2026-11-21 定了口径）
 *
 * **学校信息门户的应用市场才是权威目录**：
 * `ehall.gzucm.edu.cn/pages/appmarketsite/site/application/home1?gid=1`
 *
 * 它背后是 `GET /appmarket/frontend/homepage/apps`，返回 93 个应用，分十类。
 * 其中「系统直通车」14 个是真正的业务系统，其余 79 个是办事流程
 * （申请、证明、电子签章），那些通过「办事大厅」和「全部应用」进。
 *
 * 之前从**企业微信工作台**抄来的那份目录是错的：企业微信里的入口多包了一层
 * 企业微信 OAuth，看起来像「必须企业微信」，其实学校系统本身都支持 CAS。
 *
 * ## 为什么不放「实验动物管理」
 *
 * 它是 `http://sydwzx.gzucm.edu.cn/`，只有明文没有 https。为了一个低频系统
 * 把 `usesCleartextTraffic` 放开不划算；真要加的话，单独给它开域名白名单。
 *
 * ## 为什么去掉了「网络服务」
 *
 * 实测 `SSL_DATE_INVALID`（证书过期），在企业微信里也打不开 —— 学校那边的
 * 问题，等网络中心修。放进来只会让用户以为 App 坏了。
 */
val CampusApps: List<CampusApp> = listOf(
    // ── 高频 ──
    CampusApp("hall", "办事大厅", "https://i.gzucm.edu.cn/"),
    CampusApp(
        "lab",
        /*
         * 全称「实验室智能综合管理系统」/「实验室与大仪系统」。
         *
         * ## 为什么要走外部浏览器（2026-11-21 实测定稿）
         *
         * 这套系统在 App 的 WebView 里**起不来**：页面 HTML 加载了（标题「实验平台」在），
         * 但 `<div id=app>` 是空的、无障碍读不到一个字符、Vue 的 vendor chunk
         * 一声不响地不挂载。同一个地址在 Chrome 里完全正常（蓝色登录页）。
         *
         * 试过并排除的：
         * - `#/home` vs `/base/wz/login/pc` vs `?type=h5` —— 三个入口都白屏。
         *   致辉那边还看到过「没带 auth」的提示，说明学校的 CAS 集成没能把
         *   `id_token` 传给这套系统（HTML 里就在找 `getQueryString("id_token")`）
         * - 关掉算法暗化                —— 无效
         * - 打开 mixedContentMode 允许混合内容 —— 无效
         * - 清空 cookie                —— 无效
         * - WebView 153 / Chrome 156，版本都够新 —— 不是语法问题
         * - 厂商模板里还留着 `sysglxt.pdsu.edu.cn`（平顶山学院）的注释，
         *   说明这是定制产品的残留，WebView 兼容性大概率没人测过
         *
         * **入口必须走 CAS**（`/base/wz/login/pc`），不是那个蓝色账密页 ——
         * 致辉 2026-11-21 明确纠正过：这套系统是统一身份认证进的，
         * 蓝色登录页（`gz_h5/#/home` 直开）是错的路径。
         *
         * 所以只能用浏览器 + CAS 入口。代价是跳出 App，但**总比白屏强**。
         *
         * 卡角标标「外链」，点之前就知道会跳出去。
         */
        "实验室平台",
        "https://sysgl.gzucm.edu.cn:8080/base/wz/login/pc",
        external = true,
        badge = "外链",
    ),
    CampusApp("library", "图书馆", "https://library.gzucm.edu.cn/index.htm"),
    CampusApp(
        "dorm",
        "智慧宿管",
        // CAS 入口。不是企业微信 OAuth —— 那条是 `acs.gzucm.edu.cn/dormitory/m/home`，
        // 在浏览器里直接报「请在企业微信客户端打开链接」
        "https://acs.gzucm.edu.cn/platform/casLogin.php?system=dormitory",
    ),

    // ── 业务系统 ──
    CampusApp("student-affairs", "学生工作", "https://xgxt.gzucm.edu.cn/mobile/sso"),
    CampusApp(
        "finance",
        "财务平台",
        "https://cwptxmk.gzucm.edu.cn/ValidateLoginToUrl/ExternalLogin.aspx",
    ),
    CampusApp("jwxt", "教务系统", "https://jw.gzucm.edu.cn/new/ssoLogin"),
    CampusApp("pass", "路路通", "https://fangyimgr.gzucm.edu.cn/door/index.html"),

    // ── 其他 ──
    CampusApp("booking", "预约中心", "https://i.gzucm.edu.cn/yy-sys/"),
    CampusApp(
        "smartedu",
        "杏林教学",
        "https://smartedu.gzucm.edu.cn/login/index.php?authCAS=CAS",
    ),
    CampusApp("equipment", "大仪共享", "https://yqgx.gzucm.edu.cn/"),
    CampusApp("software", "正版软件", "https://ms.gzucm.edu.cn/login"),
    CampusApp("reagent", "库巴扎试剂", "https://hcgl.gzucm.edu.cn"),
    CampusApp("intranet", "访问内网", "https://vpn.sangfor.gzucm.edu.cn:5443"),

    /*
     * 兜底入口：ehall 应用市场。
     *
     * 上面 14 个是「系统直通车」，剩下 79 个办事流程（奖学金申请、在读证明、
     * 成绩单、电子签章…）不单独列格子 —— 全摆出来要滚三屏，
     * 不如留一个入口进去搜。
     */
    CampusApp(
        "allapps",
        "全部应用",
        "https://ehall.gzucm.edu.cn/pages/appmarketsite/site/application/home1?gid=1",
    ),
).filter { it.ready }

/**
 * 常用区默认显示哪几个（还没打开记录时）。
 *
 * 图书馆第一：查资料最高频；办事大厅第二（证明、申请都从这儿进）；
 * 实验室第三 —— 致辉说师兄们天天用。
 */
val CampusDefaultCommon = listOf("library", "hall", "lab")

/**
 * 跳企业微信工作台的 scheme。
 *
 * 当前没有应用用它，留着是因为：万一以后又冒出「真的只能用企业微信」的服务，
 * 这是唯一实测能用的路径（`wxwork://jump?target=workbench`）。
 *
 * **只能跳到工作台，跳不到具体某个应用**。官方另外列了
 * `wxwork://openurl`（参数 `redirecturl`）和 `wxwork://jump?target=open_h5`，
 * 但从外部 App 调用时全被静默拒绝；`ACTION_VIEW` + https 更是不行 ——
 * 企业微信根本没注册 http 的接收器。详见 `WeWorkLauncher.kt`。
 */
const val WEWORK_WORKBENCH_SCHEME = "wxwork://jump?target=workbench"

/** 企业微信的包名，用于检查是否安装 */
const val WEWORK_PACKAGE = "com.tencent.wework"
