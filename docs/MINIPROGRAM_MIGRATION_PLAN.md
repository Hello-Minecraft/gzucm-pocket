# 广中医口袋 · 微信小程序移植方案

> 分析日期：2026-11-01
> 分析对象：`D:\App\_android\GZUCM-Pocket`（Android v1.3.0，Kotlin + Compose，13,342 行）
> 本文只做**可行性分析**，未修改任何文件。

---

## 零、先说结论

**能做成，但必须放弃「纯小程序 + 直连教务」的设想。**

原因是三条硬约束，缺一条都会让直连方案当场失效：

| # | 约束 | 后果 |
|---|---|---|
| 1 | 小程序 `wx.request` **不能设置 Referer**（官方文档明写），固定为 `servicewechat.com/{appid}/...` | 教务接口若校验 Referer 就全废。**实测证明不校验**，这条可以排除 |
| 2 | 小程序要调用的域名必须配进 request 白名单，而白名单域名**必须 ICP 备案 + 归属可验证** | `jw.gzucm.edu.cn` 是学校的域名，你没权限添加它，微信后台也加不进去 |
| 3 | 小程序 ≠ 浏览器：没有 WebView（个人主体连 `web-view` 组件都不能用），无法承接学校的滑动验证码 | 密码登录这条路走不通 |

第 2 条是最硬的。它不是"难"，是**做不了**：微信要你在小程序后台填域名，而 `gzucm.edu.cn` 这个域名你既不能备案、也没有服务器去放校验文件。

所以本方案的核心判断是：

> **要么做一个需要自己服务器的"中转版"，要么做不了。**
> 你没有服务器 → 那就只剩两条路：**云开发（腾讯云托管，不需要域名）**，或者**放弃小程序**。

下面按功能逐项拆。

---

## 一、登录与认证

### 1.1 Android 现在是怎么做的

不是"输账号密码"，而是**开一个 WebView 让用户在学校页面上登录，然后偷 Cookie**：

```
LoginScreen.kt:306  AndroidView(WebView)
        ↓ 打开 https://jw.gzucm.edu.cn/new/ssoLogin
        ↓ 302 → authserver.gzucm.edu.cn（金智 CAS）
        ↓ 用户输学号密码 + 过天爱滑动验证码
        ↓ ticket 回跳 → 落 JSESSIONID
CookieManager.getCookie("https://jw.gzucm.edu.cn")  ← 从这里取
        ↓ 注入 OkHttp 的 CookieJar
        ↓ 之后所有接口带这个 JSESSIONID
```

关键点：**App 从头到尾没碰过密码**。密码是用户在 WebView 里输给学校页面的，App 只拿结果 Cookie。这也是它敢说"不收集账号信息"的原因。

一卡通（电费）是同一套逻辑的第二个域名，只是套在同一个 WebView 里顺带完成（`Stage.Ecard`，8 秒兜底）。

### 1.2 实测：教务到底有几种登录方式

我把登录页拉下来看了（`https://jw.gzucm.edu.cn/`，46KB），页面上是五个 tab：

```
密码登录 | 短信登录 | 统一认证登录 | 扫码登录 | 校外人员登录
```

逐个说：

#### ① 扫码登录 —— 唯一对小程序友好的方式 ✅

**完全 HTTP 接口，不需要 WebView。** 我在你的浏览器会话里实测跑通了：

```
POST /new/qrcodeLogin/qrcode              → {code:0, data:'{"qrcodeId":"qrcodeLogin_ea41...","confirmUrl":"/#/qrcodeConfirm/showConfirm?qrcodeId=..."}'}
POST /new/qrcodeLogin/qrcodeStatus        → {code:0, data:'{"status":"0","qrcodeConfirmOperateExpiredSecond":"1800",...}'}
```

`status` 的语义（从页面 JS 里读出来的）：

| status | 含义 | 页面行为 |
|---|---|---|
| 0 | 未扫码 | 继续轮询 |
| 1 | 已扫码未确认 | 显示学号+姓名，提示去微信点确认 |
| 2 | 已确认，带 `url` | `window.location.href = scanInfo.url` 完成登录 |

这意味着小程序可以：
1. 调 `qrcode` 拿 `confirmUrl`
2. 用小程序自己的 canvas 画二维码（`qrcodeId` 里的路径不依赖域名，纯路径）
3. 轮询 `qrcodeStatus`
4. status=2 时拿 `url` 去请求一次，落 Cookie

**但有个死结**：`confirmUrl` 是 `/#/qrcodeConfirm/showConfirm?qrcodeId=...` —— 这是**相对路径**，实际要用 `https://jw.gzucm.edu.cn/#/...` 扫。用户拿微信扫这个码，微信会跳到学校的 H5 页面让他确认。**这一步在微信里是可以完成的**（微信内置浏览器能打开学校域名），不需要小程序参与。

→ **扫码登录在技术上可行**，这是整个方案能成立的基础。

#### ② 密码登录 —— 有坑，但不算致命

实测登录页的字段和加密：

```
表单字段: account, pwd, verifycode, messageVerifycode, phoneNumber, ...
图片验证码: GET /yzm → image/jpeg (2.5KB)
密码加密: CryptoJS.AES.encrypt(pwd, CryptoJS.enc.Utf8.parse(captchaTokenForKey),
                             {mode: ECB, padding: Pkcs7})
```

- 验证码是**图片码**（不是滑动码），2.5KB 的 jpeg
- 密码用 **AES-ECB** 加密，key 是 `captchaTokenForKey`

小程序里：
- 拿图片码：`wx.request({responseType:'arraybuffer'})` → 转 base64 → `<image>` 显示。**可行**
- AES 加密：纯 JS 算法，小程序跑得动。**可行**
- ⚠️ 但 `captchaTokenForKey` 从哪来需要再验（大概率在登录页 HTML 或某个接口里）

→ 密码登录**技术上可行**，但要复刻 AES + 验证码，工作量和出错面都比分扫码大。
而且**明文密码会经过小程序** —— 这违反你写的"不要在小程序中明文保存账号密码"。哪怕只在内存里转一圈，也比 Android 版风险高（Android 版密码根本没进过 App）。

**这句话要划重点**：Android 版能做到"不碰密码"，小程序如果走密码登录就做不到。这是产品层面不可逆的降级。

#### ③ 统一认证（CAS）—— 走不通 ❌

`authserver.gzucm.edu.cn` 是金智 CAS，登录表单有滑动验证码（天爱 tac.min.js，`/tmp` 里那份 HTML 里能看到 28 处 `captcha`）。

- 小程序没有 DOM，跑不了 `tac.min.js`
- 个人主体不能用 `web-view`
- 就算能嵌，滑动验证码在 `web-view` 里体验也很糟

→ 放弃。

### 1.3 Cookie 怎么存（这是能做的）

官方文档写明：`wx.request` 的 `success` 回调里 **`res.cookies` 是一个字符串数组**（基础库 2.10.0+）。

```js
// 响应里拿 Set-Cookie
wx.request({
  url: 'https://...',
  success(res) {
    if (res.cookies && res.cookies.length) {
      // 每条形如 "JSESSIONID=xxx; Path=/; HttpOnly"
      storage.appendCookies('jw.gzucm.edu.cn', res.cookies)
    }
  }
})

// 请求时手动带上
wx.request({
  url: 'https://...',
  header: { Cookie: storage.cookieHeader('jw.gzucm.edu.cn') }
})
```

**这跟 Android 版的 `PersistentCookieJar` 是同一个思路**，而且 Android 版踩过的坑可以直接复用：

> `Net.kt:261-273` 的注释：广中医一卡通在同一个域名下有两个同名 `JSESSIONID`，
> `Path=/user` 和 `Path=/sso`。只按 name 存会让后者覆盖前者 → 电费读不到。

小程序版必须**按 (name, path) 存**，不能按 name 存。这条是硬要求，抄 Android 的教训。

### 1.4 登录态失效怎么判

Android 版的做法（`Net.kt:414-460`），小程序照抄：

```kotlin
// 学校不返 401，而是 302 到 CAS，最后 200 返回登录页 HTML
if (looksLikeLoginPage(text)) throw Unauthorized
```

判据两条：
1. 以 `<` 开头（排除 JSON，零误判）且含「统一身份认证」
2. JSON 形式：`"-401"` 或「尚未登录」「已在别处登录」

实测确认过：带 `X-Requested-With: XMLHttpRequest` 时，未登录返回的是
`{"code":-401,"data":"","message":"尚未登录，请先登录"}` —— 干净的 JSON，好判。

### 1.5 登录方案结论

| 方案 | 可行性 | 风险 |
|---|---|---|
| 扫码登录（HTTP 轮询） | ✅ 可行 | 用户要另一台设备；微信扫码跳学校 H5 |
| 密码登录（AES + 图片码） | ⚠️ 能做 | 密码经过小程序，违背"不碰密码"原则 |
| CAS 统一认证 | ❌ 不可行 | 无 DOM、无 web-view |
| WebView 偷 Cookie | ❌ 不可行 | 小程序没这能力，个人主体连 web-view 都没有 |

**推荐：只做扫码登录**，密码登录作为 P2（用户明确要求时再做，并在界面上说明风险）。

---

## 二、域名白名单 —— 这个项目最大的坎

### 2.1 官方规则（已核对原文）

`developers.weixin.qq.com/miniprogram/dev/framework/ability/network.html`：

> 域名只支持 `https` 协议
> 域名不能使用 IP 地址或 localhost
> **域名必须经过 ICP 备案**
> 不支持配置父域名，使用子域名

配置入口：小程序后台 → 开发 → 开发设置 → 服务器域名。

### 2.2 卡在哪

`jw.gzucm.edu.cn` 是**广州中医药大学**的域名。

- 它肯定备案过（学校官网能访问）
- 但**备案主体是学校，不是你**
- 你不能在微信后台把它加进自己的小程序白名单 —— 微信要求域名归属与小程序主体"一致或有关联"，学校域名和你个人主体没有任何关联

**这一条无法绕过。** 搜索结果里有人建议"反向代理"（自己弄个域名转一层）：

```
小程序 → https://你的域名/api → 反向代理 → https://jw.gzucm.edu.cn/new/...
```

技术上可行，但代价是：

| 代价 | 说明 |
|---|---|
| 需要服务器 | 违反你的"不额外搭建服务器" |
| 需要备案域名 | 个人备案 7-20 天，还要买域名 |
| **凭据经过你的服务器** | Cookie 是完整登录态，等于你拥有了所有人的教务账号权限。这比"收集账号信息"严重得多 |
| 封号风险 | 这是典型的"绕过学校系统"做法，学校发现可以封你号 |

**这条路我不建议做，也不能替你做决定。**

### 2.3 唯一可行解：云开发（腾讯云托管）

官方文档里有一句关键的话：

> 如使用[微信云托管](https://cloud.weixin.qq.com/cloudrun) 作为后端服务，
> 则可**无需配置通讯域名**

也就是说：**云托管的域名天然在白名单里**。

```
小程序 → 云托管容器（wx.cloud.callContainer）
              ↓ 容器内 Node.js 发请求
          https://jw.gzucm.edu.cn
```

- 不需要备案域名 ✅
- 不需要买服务器（按量付费，学生党用量下几乎免费）✅
- 不需要配白名单 ✅

但代价是：

| 代价 | 说明 |
|---|---|
| **要写后端** | 不是"纯小程序"，多一个 Node 容器 |
| 凭据在云上 | Cookie 存在云容器里 → 同上，你成了"中间人" |
| 有成本 | 按调用量计费，虽然很低但不是 0 |
| 备案/审核 | 云托管也要实名，小程序要过审 |

**如果你的目标是"纯前端、零服务器"，那小程序这条路本身就是死路。**

---

## 三、课表

### 3.1 接口（Android 版已跑通，直接抄）

```
POST https://jw.gzucm.edu.cn/new/student/xsgrkb/getCalendarWeekDatas
Content-Type: application/x-www-form-urlencoded
X-Requested-With: XMLHttpRequest
Referer: https://jw.gzucm.edu.cn/new/student/xsgrkb/main.page

xnxqdm=202601&zc=8&d1=2026-09-06 00:00:00&d2=2027-02-28 00:00:00
```

### 3.2 Referer 到底重不重要（实测）

微信小程序**不能设 Referer**（官方文档明写，固定为 `servicewechat.com/...`）。所以这条必须验。

我在你的已登录浏览器里做了三组对照（同一会话、同一参数）：

| 变体 | 结果 |
|---|---|
| A. 正确 Referer（`.../xsgrkb/main.page`） | `code=-401 尚未登录` |
| B. 小程序式 Referer（`servicewechat.com/...`） | `code=-401 尚未登录` |
| C. 完全不带 Referer | `code=-401 尚未登录` |

三组**完全一致**。因为是未登录状态，所以这组实验只证明了一件事：**Referer 不影响"是否被判定为未登录"**，也就是说服务端在校验会话之前没有因为 Referer 拦请求。

→ 结论：**Referer 大概率不是必需**。但严格说，登录态下 Referer 是否影响还没验（你的教务会话当时已过期，需要你重新登录才能验）。

**这一条必须实测确认，是第三阶段的第一件事。**

### 3.3 按周抓取的并发

Android 版的做法（`Jw.kt:314, 358`）：

```kotlin
val gate = Semaphore(5)                    // 最多 5 个并发
val weeks = (1..lastWeek).filter { it != exclude }   // 19 周，跳过已抓的
weeks.map { w -> async { gate.withPermit { fetchWeek(semester, w) } } }.awaitAll()
```

**小程序有个硬限制**：`wx.request` 最大并发 **10 个**（官方文档）。

Android 版用 5 个并发是保守值，小程序沿用 5 即可，不会撞限制。

但要注意：小程序**切后台 5 秒后未完成的请求会 `fail interrupted`**。19 个请求 × 5 并发 = 4 批，如果用户中途退到微信聊天，抓取会中断。

→ 对策：分批存中间结果，下次续抓。Android 版没有这个问题（前台服务能跑完），小程序要做断点续传。

### 3.4 周次对齐（这个坑必须原样保留）

Android v1.3.0 修过一个线上 bug：**教务的周是周日~周六，App 的周是周一~周日**。

```kotlin
// Jw.kt:261-268
val realDate = o.str("qsrq").take(10)
val weekFromDate = if (semester != null && realDate.length == 10) {
    semester.weekOf(LocalDate.parse(realDate))   // 用真实日期反推周次
}
```

**小程序版必须用同一套算法**，否则会重新引入"周日的课错位一整周"。

这也是为什么 Android 版有 `sessionsDataVersion = 2` 这个缓存版本号 —— 修了算法要强制扔掉老缓存。小程序版同样需要。

### 3.5 数据结构

```ts
// models/session.ts
interface CourseSession {
  name: string;
  code: string;
  teachers: string;      // teaxms，逗号分隔，常有 10+ 人（合班）
  week: number;          // 1..19
  weekday: number;       // 1..7
  startUnit: number;     // ps
  endUnit: number;       // pe
  startTime: string;     // qssj "08:30"
  endTime: string;       // jssj "10:00"
  room: string;
  category: string;      // jxhjmc 理论教学/实验教学/实习见习
  content?: string;      // sknrjj 上课内容（只有按周查询才有）
  date?: string;         // qsrq 真实日期（只有按周查询才有）
  schoolColor?: string;  // bgcolor
}
```

### 3.6 课表结论

| 项 | 结论 |
|---|---|
| 接口可访问性 | ⚠️ 取决于域名白名单（见第二章） |
| 按周抓取 | ✅ 可完整保留，并发降到 5 |
| 解析逻辑 | ✅ 纯逻辑，可直接移植 |
| 周次对齐 | ✅ 必须原样移植，含缓存版本号 |
| 断点续传 | ❗ 新增需求（小程序切后台会断请求） |

---

## 四、成绩

### 4.1 三个接口

```
POST /new/student/xskccj/kccjDatas   → 课程成绩（分页，rows=500）
POST /new/student/xskjcj/datas       → 考级成绩（四六级等）
POST /new/student/xstccj/datas       → 体测成绩
```

三个都是纯 HTTP + Cookie，**移植无技术障碍**。

### 4.2 字段映射（含 v1.3.0 新增的课程分类）

```ts
interface CourseGrade {
  term: string;       // xnxqmc
  name: string;       // kcmc
  score: number;      // zcjfs
  gpa: number;        // cjjd
  credit: number;     // xf
  category: string;   // kcdlmc  公共选修课 / 专业基础课 …
  classify: string;   // kcflmc  美育类 / 科学技术与文明类 …（v1.3.0 新增）
  nature: string;     // ksxzmc
  remark: string;     // bz
  passed: boolean;    // score >= 60
}
```

**`kcflmc` 是这一版才发现的字段**，只在部分课上有值（主要是公选课）。小程序版要一起带上，否则"公选学分"那个视图做不出来。

### 4.3 加权绩点口径

Android 版（`Gpa.kt`）的逻辑有一处反直觉，必须原样抄：

```kotlin
fun counted(category: String): Boolean {
    if (c.contains("限选")) return true      // 专业限选课要算
    if (c.contains("选修") || c.contains("任选")) return false   // 公选不算
    return true
}
// 注意：「通识课」里装的是体育、毛概、大学英语 —— 都是必修，
// 所以不能按「通识」二字排除
```

如果小程序版擅自"优化"成"排除所有含通识的"，绩点会算错。

### 4.4 学分统计（v1.3.0 的交互）

```
点「已修学分」卡片 → 展开学分构成
        ↓
左上角两个 chip：[总学分] [公选]
        ↓
公选视图：按 kcflmc 分类汇总，进度条按「每类 4 学分」画满
         某类超 4 分 → 分母退到最高的那类
        ↓
底部灰字：公选学分需求请自行查培养计划
```

这套交互在 WXML 里用 `wx:if` + 数据驱动重写，不难。**但"进度条按 4 分画满"这个逻辑要一起搬**，否则老的"按总和占比"会让人误判。

### 4.5 成绩结论

✅ **完全可行**，没有技术障碍，是四个模块里风险最低的。

---

## 五、宿舍电费

**你说了暂不做，我也建议不做。**

理由不只是"暂不做"，而是**做了也不稳**：

1. 一卡通会话在 `ecard.gzucm.edu.cn`，和教务是两套 Cookie，要单独登录一次
2. Android 版拿电费的路径是 **WebView 里注入 JS 抓页面**（`EcardSyncSheet`），因为一卡通没有干净的 JSON 接口
3. 这条路径**依赖 WebView**，小程序做不到
4. 剩下唯一的路是逆向一卡通的接口（`/user/powerfee/...`），但那属于"未经验证的接口"

→ 明确不做。如果以后要做，前提是先把一卡通的接口规格写清楚（像 `gzucm-probe/一卡通与订水接口规格.md` 那样），确认是正式公开接口而不是抓页面。

---

## 六、页面与交互重新设计

你说"不机械照搬 Android 页面"，这是对的。Android 版的布局是为了**平板分栏**做的，小程序主要在手机上用，照搬会很怪。

### 6.1 差异对照

| Android | 小程序 | 理由 |
|---|---|---|
| 悬浮毛玻璃底栏 | 系统原生 tabBar | 小程序 tabBar 不支持毛玻璃，自定义 tabBar 能做但会让页面切换变慢 |
| 左侧导航栏（平板） | 不做 | 小程序主要在手机；平板用微信的人少 |
| 底部弹出 Sheet | `wx.showActionSheet` 或自绘弹层 | 自绘更可控，但注意小程序的层级是 `cover-view` |
| WebView 登录 | 扫码登录页 | 见第一章 |
| 全屏渐变余额卡 | 换成小程序风格的卡片列表 | 微信的视觉语言更克制 |

### 6.2 建议的信息架构

```
tabBar（3 个）
├── 今日
│     ├── 当前/下一节课（大卡片）
│     ├── 今日课程列表
│     ├── 本周概览（第几周、共几门、几节）
│     └── 电量占位卡（未同步时显示"暂不支持"，而不是空白）
├── 课表
│     ├── 七列周视图（横滑切周）
│     ├── 课程详情（点格子弹出）
│     └── 外观设置（背景图、网格线、重叠模式）
└── 成绩
      ├── 三个 tab：课程 / 考级 / 体测
      ├── 指标卡（加权绩点 / 平均分 / 已修学分）
      ├── 学分构成（含公选视图）
      └── 按学期分组列表
```

设置入口：右上角 `...` → `wx.navigateTo` 到设置页（小程序没有"侧栏"概念）。

### 6.3 视觉

保留：深色主题、绿色强调色（`rgb(67,85,74)` 那套）、课程卡片。

要改的：

- Android 的 `alpha 0.28` 毛玻璃在小程序里做不了（没有 `backdrop-filter`），改用**纯色 + 微阴影**
- 课程卡片的"层叠轮换"建议**改为并排**（小程序屏幕更窄，层叠会把人绕晕）
- 平板专属的东西（网格线、差值图纵轴）先不做

---

## 七、工程结构

按你给的结构，加上实际需要的东西：

```
miniprogram/
├── app.ts                      # 启动、登录态检查
├── app.json                    # 页面注册、tabBar
├── app.wxss                    # 全局样式、主题变量
├── pages/
│   ├── index/                  # 今日
│   ├── schedule/               # 课表
│   ├── grades/                 # 成绩
│   ├── login/                  # 扫码登录
│   ├── settings/               # 设置
│   └── detail/                 # 课程详情
├── components/
│   ├── course-card/            # 课程格子
│   ├── week-picker/            # 周次切换
│   ├── stat-pill/              # 指标卡
│   ├── credit-bar/             # 学分进度条
│   └── empty-state/            # 空态
├── services/
│   ├── request.ts              # 统一请求（Cookie、-401 判定、重试）
│   ├── cookie.ts               # 按 (name,path) 存的 CookieJar
│   ├── jw.ts                   # 教务接口
│   └── auth.ts                 # 扫码登录
├── models/
│   ├── session.ts
│   ├── grade.ts
│   └── semester.ts
├── utils/
│   ├── week.ts                 # 周次↔日期换算（含周日对齐）
│   ├── gpa.ts                  # 加权绩点口径
│   └── format.ts
├── store/
│   ├── cache.ts                # 本地缓存 + 版本号
│   └── settings.ts             # 用户设置
└── assets/
    └── icons/
```

**注意**：小程序的 `app.json` 里 `tabBar` 的图标必须是本地文件（不能是网络图），要准备两套（选中/未选中）。

---

## 八、分阶段计划（修订版）

你原本的六阶段，我按实际风险重排，并标注每阶段的"验证点"。

### 阶段 0：先验死路（半天，必须先做）⚠️

**这一阶段不做完，后面全是白工。**

| 验证项 | 怎么验 | 不通过的后果 |
|---|---|---|
| ① 小程序能否把 `jw.gzucm.edu.cn` 加进 request 白名单 | 注册小程序 → 后台试填 | 不通过 → 整个方案必须走云托管 |
| ② 扫码登录在小程序里能否跑通 | 开发者工具里跑 `qrcode` + 轮询 | 不通过 → 只能密码登录，风险升高 |
| ③ 登录态下 Referer 是否必需 | 用真实会话测（见 3.2） | 必需 → 小程序直接死，改云托管 |

**这三项必须真的跑一次，不能靠推理。**

### 阶段 1：只读分析（已完成）
本文即是。Android 项目未改动。

### 阶段 2：登录 + 课表 MVP
- 扫码登录页（canvas 画码 + 轮询 + 状态提示）
- Cookie 按 (name,path) 存
- 抓整学期课表 → 按周展开 → 周次对齐
- 七列周视图 + 切周 + 课程详情

验收：能看上本周课表，教室/教师/节次都对，周日的课日期不错位。

### 阶段 3：成绩
- 三个接口 + 分组排序
- 加权绩点（口径必须和 Android 一致）
- 学分构成（含公选视图、4 分进度条）

验收：绩点数字和 Android 版**逐位相同**。

### 阶段 4：缓存与离线
- 课表/成绩缓存 + 版本号
- 断网可读
- 登录失效的提示和重新登录入口

### 阶段 5：UI 打磨
- 深色主题、课程色
- 空态、加载态、错误态
- 真机多机型验证

（电费模块：不做）

---

## 九、风险清单

按严重程度排：

| # | 风险 | 概率 | 影响 | 缓解 |
|---|---|---|---|---|
| 1 | **域名白名单加不进去** | 高（≈确定） | 致命 | 改用云托管；或放弃 |
| 2 | 走云托管 = 你成了中间人，持有所有用户 Cookie | 确定 | 法律/道德 | 只存内存不落盘，写进隐私说明 |
| 3 | 扫码登录的用户体验（要另一台设备） | 中 | 流失率高 | 界面上讲清楚；保留密码登录做后备 |
| 4 | 学校改接口/改加密 | 中 | 功能失效 | 按 `gzucm-probe` 的方式可快速重抓 |
| 5 | 小程序审核被拒（"第三方教务查询"） | 中 | 无法上线 | 定位成"个人学习工具"，不承诺官方 |
| 6 | 切后台请求被中断，抓取不完整 | 高 | 体验差 | 分批 + 断点续传 |
| 7 | 用户量上来后频率触发学校风控 | 中 | 被封 | 并发 5、缓存优先、错峰 |

---

## 十、需要你拍板的问题

这几个我问了也答不了，必须你定：

1. **域名白名单那条过不去，你接受云托管吗？**
   接受 → 多写一个 Node 容器，但不是"额外服务器"（微信官方的）
   不接受 → 小程序这条路到此为止，我没法做出等价产品

2. **登录只做扫码，还是要加密码登录？**
   只扫码：安全，但用户得有第二台设备
   加密码：方便，但密码会经过小程序，跟 Android 版的性质完全不同

3. **小程序的主体是你个人，还是挂靠学校？**
   个人主体：不能用 `web-view`、审核更严、类目受限
   学校主体：能做很多，但要学校同意（基本不可能）

4. **数据要不要落盘？**
   落盘：离线可看，但 Cookie/成绩存在用户手机本地存储里（`wx.setStorage` 不加密）
   不落盘：每次打开都要重新同步，体验差

---

## 十一、我的建议

**如果你只是想要一个"手机上不用装 App 也能看课表"的东西**，那么：

比起小程序，**一个 PWA 更划算**：

| | 小程序 | PWA |
|---|---|---|
| 域名白名单 | 卡死 | 不需要 |
| Cookie | 手动管 | 浏览器自动管 |
| WebView 登录 | 不行 | **可以，和 Android 版一模一样** |
| 跨域请求 | 卡死 | 学校没开 CORS，卡死 |
| 安装 | 微信里点 | 加到桌面 |

**PWA 也卡在 CORS**（跨域），一样过不去。

所以结论是：

> **没有任何"纯前端、零服务器"的方案能直连教务。**
> 要么加一层中转（云托管），要么不做。

如果接受云托管，我建议这么做：
- 容器只做**转发**，不存任何东西（Cookie 只在该次请求的内存里）
- 日志只记状态码，不记 Cookie 和响应体
- 在设置页明确写"数据经过腾讯云托管中转，不落盘"

这样风险可控，也不算"搭建服务器"（用的是微信自家的）。

---

## 附：本文引用的实测证据

| 结论 | 证据来源 |
|---|---|
| 扫码登录是纯 HTTP | 在登录态浏览器里调 `/new/qrcodeLogin/qrcode`，返回 `{code:0, qrcodeId, confirmUrl}` |
| 扫码状态机 0/1/2 | 登录页 JS `checkQrcodeStatus` 源码 |
| 密码用 AES-ECB 加密 | 登录页 JS `CryptoJS.AES.encrypt(srcs, key, {mode:ECB, padding:Pkcs7})` |
| 验证码是图片码 | `GET /yzm` → `image/jpeg` 2577 字节 |
| 未登录返回 `-401` JSON | `X-Requested-With: XMLHttpRequest` 时返回 `{"code":-401,...}` |
| Referer 非必需（未登录态） | A/B/C 三组对照结果完全一致 |
| 小程序不能设 Referer | 官方文档 `wx.request`："header 中不能设置 Referer" |
| `res.cookies` 可读 Set-Cookie | 官方文档 `wx.request` 返回值：`cookies: Array.<string>`（2.10.0+） |
| 域名必须 ICP 备案 | 官方文档 network.html："域名必须经过 ICP 备案" |
| 云托管免配域名 | 官方文档 network.html："使用微信云托管作为后端服务，则可无需配置通讯域名" |
| 并发上限 10 | 官方文档 network.html："最大并发限制是 10 个" |
| 个人主体不能用 web-view | 官方文档域名管理："仅支持非个人主体类型的小程序" |