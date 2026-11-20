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
    /**
     * 桌面版页面：按桌面宽度渲染再整体缩放（手机浏览器看 PC 网页那种效果）。
     *
     * 学校有几个系统只有 PC 版，左侧固定菜单占掉三分之一屏，正文被挤到视口外：
     * 教务系统的右侧正文空白、路路通只有顶栏没有内容、库巴扎的登录表单被裁掉。
     * 开这个开关让 WebView 按 1280 渲染再缩放塞进屏幕 —— 字会变小、要双指放大，
     * 但至少看得见、点得到。
     *
     * 移动版页面**不要开**：它们本来就按手机宽度设计，开了反而被缩小。
     */
    val desktopMode: Boolean = false,
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
         * ## 结论：App 内置浏览器打不开，只能企业微信（2026-11-21 实测定稿）
         *
         * 这套系统的前端对 WebView 环境不兼容。**CAS 登录本身是成功的**
         * （探针能读到 `广州中医药大学 / 学生 / 2023014093-朱致辉 /
         * 实验培训与准入考试 / 大型仪器共享 / 实验室开放管理…` 整页菜单），
         * 但页面要么高度算成 0（整页白屏），要么样式全丢（白字 + 空背景）。
         *
         * 试过并逐一排除的：
         * - 三个入口：`gz_h5/#/home`、`/base/wz/login/pc`、`?type=h5`
         * - 关掉算法暗化（Android 13+ 的强制反色）
         * - 打开 mixedContentMode 允许混合内容
         * - 清空 WebView cookie
         * - `useWideViewPort = true`（让 WebView 尊重 viewport meta）
         * - 去掉 UA 里的 `; wv` 标记
         * - 注入 CSS 强制 `html/body/#app` 高度（能撑到 776px，但内容挤在顶部一条）
         * - WebView 153 / Chrome 156，版本都够新
         * - 厂商模板里还留着 `sysglxt.pdsu.edu.cn`（平顶山学院）的注释，
         *   说明这是定制产品的残留，WebView 兼容性大概率没人测过
         *
         * Chrome 里同一个地址完全正常 —— 所以不是网络、不是登录态、不是证书。
         *
         * ## 所以按「不能就说明并跳对应 app」处理
         *
         * 走企业微信工作台。**不跳浏览器**：浏览器里还要再登一次统一身份认证，
         * 等于把 App 已有的登录态白白扔掉（致辉 2026-11-21 明确否掉的方案）。
         */
        "实验室平台",
        kind = CampusAppKind.WEWORK,
        badge = "企微",
        note = "这个系统的页面在 App 内置浏览器里渲染不出来（样式和高度都会丢），" +
            "只能跳企业微信打开",
    ),
    CampusApp("library", "图书馆", "https://library.gzucm.edu.cn/index.htm"),
    /*
     * 智慧宿管：**实测在 App 里白屏**（2026-11-21 逐个测出来的）。
     *
     * CAS 入口 `acs.gzucm.edu.cn/platform/casLogin.php?system=dormitory` 在浏览器里
     * 会正常跳统一身份认证，但在 App 的 WebView 里连 DOM 都没渲染出来
     * （只有顶栏，正文一个元素没有）。同类的 i.gzucm 系页面都正常，
     * 所以不是 cookie 或 TLS 的问题，偏这套系统自己的前端兼容性。
     *
     * 企业微信里有它的入口且能正常用，所以归到「只能企业微信」。
     */
    CampusApp(
        "dorm",
        "智慧宿管",
        kind = CampusAppKind.WEWORK,
        badge = "企微",
        note = "这个系统在 App 内置浏览器里渲染不出来，只能跳企业微信打开",
    ),

    // ── 业务系统 ──
    CampusApp("student-affairs", "学生工作", "https://xgxt.gzucm.edu.cn/mobile/sso"),
    CampusApp(
        "finance",
        "财务平台",
        "https://cwptxmk.gzucm.edu.cn/ValidateLoginToUrl/ExternalLogin.aspx",
    ),
    /*
     * 教务系统的网页版**不放**：
     *
     * App 里的课表、成绩、考试都是直接调它的接口，信息比网页版还全；
     * 而网页版是正方那套 PC 布局，在 WebView 里只出来左侧菜单、右侧正文永久空白
     * （2026-11-21 实测，DOM 里除了顶栏一个元素都没有）。
     * 放一个点开白屏的格子不如不放。
     */
    CampusApp(
        "pass",
        "路路通",
        kind = CampusAppKind.WEWORK,
        badge = "企微",
        note = "这个系统在 App 内置浏览器里只出来一个空壳（左侧蓝块 + 一个空白 tab），" +
            "只能跳企业微信打开",
    ),

    // ── 其他 ──
    CampusApp("booking", "预约中心", "https://i.gzucm.edu.cn/yy-sys/"),
    CampusApp(
        "smartedu",
        "杏林教学",
        "https://smartedu.gzucm.edu.cn/login/index.php?authCAS=CAS",
    ),
    CampusApp("equipment", "大仪共享", "https://yqgx.gzucm.edu.cn/"),
    CampusApp("software", "正版软件", "https://ms.gzucm.edu.cn/login"),
    CampusApp(
        "reagent",
        "库巴扎试剂",
        kind = CampusAppKind.WEWORK,
        badge = "企微",
        note = "这个系统的登录页在 App 内置浏览器里只渲染出上半截，只能跳企业微信打开",
    ),
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
 * 预约中心第三。
 *
 * **不塞企微的那几个**（实验室平台、智慧宿管、路路通、库巴扎）：它们点了会跳出 App，
 * 放进常用只会让「常用」变成「跳转列表」。
 */
val CampusDefaultCommon = listOf("library", "hall", "booking")

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
