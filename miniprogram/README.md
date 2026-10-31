# 广中医口袋 · 微信小程序版

对 Android 版（v1.3.0）的功能移植。原生小程序 + TypeScript，无第三方运行时依赖。

> **这是一个不完整但有价值的移植。** 课表、成绩、扫码登录都能跑；
> 电费做不了（技术上做不到，不是没做）。上线前有一个必须解决的问题，
> 见下面「必须解决」。

---

## 先说三件最重要的事

### 1. 必须有云函数，否则正式环境跑不起来

学校的域名 `jw.gzucm.edu.cn` 加不进小程序的 request 白名单 —— 那不是你的域名，
没法备案、也没法放校验文件。所以 `wx.request` 直连只在勾了
「不校验合法域名」的开发者工具里能用。

正式环境走的是 `cloudfunctions/jwproxy` 云函数转发：

```
小程序 → wx.cloud.callFunction('jwproxy') → https://jw.gzucm.edu.cn
```

云函数出口不受小程序域名白名单限制，这是唯一能工作的方式。

**云函数只做转发，不落盘、不记日志、不持有会话。**
Cookie 每次由客户端作为参数传入，转发完随请求结束丢弃 —— 它是一根管子，不是中间人。

### 2. 只能扫码登录，不能输密码

Android 版是开 WebView 让用户在学校页面上输账密，**App 从不接触密码**，只拿 Cookie。
小程序没有 WebView（个人主体连 `web-view` 组件都不能用），做不到这一点。

密码登录技术上是能做的（登录页的密码用 `CryptoJS.AES.encrypt` 加密、
验证码是 `/yzm` 返回的 jpeg），但那意味着**密码会经过小程序的代码**。
这跟 Android 版的性质完全不同，所以没做。

`POST /new/qrcodeLogin/qrcode` + `/qrcodeStatus` 全在 HTTP 上，不需要 WebView。
代价是用户得有第二台设备扫码。

### 3. 电费做不了

一卡通没有干净的 JSON 接口，Android 版是在 WebView 里注入 JS 抓页面。
小程序没有 WebView，这条路断了。逆推一卡通的 `/user/powerfee/*` 属于
"未经验证的接口"，不做。

---

## 部署步骤

### 准备

1. 注册小程序（个人主体够用，但**不能配 `web-view` 业务域名**，本项目不需要）
2. 开通云开发（微信开发者工具里点「云开发」，选按量付费）

### 上传云函数

```bash
# 在微信开发者工具里右键 cloudfunctions/jwproxy → 上传并部署
```

云函数不需要额外 npm 依赖（只用 Node 内置的 `https`），
所以不需要 `npm install`，也不需要「云端安装依赖」那一步。

### 配置

1. 把 `project.config.json` 里的 `appid` 改成你的
2. 开发者工具 → 详情 → 本地设置 → 勾上「增强编译」（TypeScript 需要）
3. 上传代码，提交审核

审核时建议定位成「个人学习工具」，不要承诺"官方"或"替代教务系统"。

---

## 必须解决（上线前）

### 问题：云开发需要 `appid`，而 `appid` 绑在你的账号上

现在的代码用 `touristappid`（游客模式），云函数调不通。
你需要在开发者工具里登录自己的小程序账号，把 `appid` 换掉。

换 `appid` 之后云函数才能用，`wx.cloud.init()` 才能正常初始化。
这不算 bug，是微信的账号体系决定的。

### 还没有真机验证

我手上没有微信开发者工具，所以：

- **UI 是我用 HTML + 同一份 wxss 渲染截图核对的**（`tests/shots/` 里五张图）
- **二维码是真验证过的**：用 OpenCV 解码 7/7 通过，包括中文
- **业务逻辑有 48 项单元测试**
- **但整个小程序没有在微信里真机跑过**

第一次跑真机时最可能出问题的地方，按可能性排序：

1. `canvas type="2d"` 画二维码 —— 各机型 `pixelRatio` 处理可能不一致
2. `wx.cloud.callFunction` 的超时和并发（19 周批抓，每批 5 个）
3. 课表 `scroll-view` 的高度计算（用了固定 `ROW_H`）
4. `wx.chooseMedia` + `saveFile` 的路径权限

---

## 目录结构

```
miniprogram/
├── app.ts                  启动、云初始化、课表/成绩同步编排
├── app.json                页面注册、tabBar
├── app.wxss                全局主题变量（深色 + 灰绿）
├── pages/
│   ├── today/              今日：焦点卡 + 今日课程 + 本周概览
│   ├── schedule/           课表：七列周视图 + 切周 + 网格线
│   ├── grades/             成绩：三 tab + 学分构成（含公选）
│   ├── login/              扫码登录
│   ├── settings/           设置
│   └── detail/             课程/成绩详情
├── components/
│   ├── course-card/        课程块（颜色派生、按高度分级显示）
│   ├── credit-bar/         学分进度条
│   └── empty-state/        空态
├── services/
│   ├── request.ts          请求层（云函数/直连双通道、登录页识别）
│   ├── cookie.ts           Cookie 管理（按 name+path 存）
│   ├── jw.ts               教务接口（从 Jw.kt 移植）
│   └── auth.ts             扫码登录
├── models/types.d.ts       数据结构
├── utils/
│   ├── week.ts             周次↔日期（含教务周日对齐）
│   ├── gpa.ts              绩点与学分口径
│   ├── layout.ts           课程排布（重叠分列）
│   ├── format.ts           数字格式化（绕开 toFixed 浮点问题）
│   └── qrcode.ts           二维码（内联 qrcode-generator，MIT）
├── store/cache.ts          本地缓存 + 设置 + 数据版本号
├── cloudfunctions/jwproxy/ 转发云函数
├── check.py                wxml ↔ ts 一致性检查
├── verify.sh               一把跑完所有校验
└── tests/
    ├── run.js              48 项单元测试
    ├── verify_qr.py        二维码 OpenCV 解码验证
    ├── render_pages.py     用真实 wxss 渲染页面截图
    └── shots/              渲染产物
```

---

## 校验

```bash
bash verify.sh
```

跑四件事：

1. `tsc --noEmit` 类型检查（strict 模式，零错误）
2. `check.py` —— wxml 里引用的字段/事件方法是否在 ts 里存在
3. `tests/run.js` —— 48 项核心逻辑测试
4. `tests/verify_qr.py` —— 二维码真解码（需要 `pip install opencv-python-headless`）

第 2 步是必要的：`tsc` 管不到 wxml，字段名写错在真机上表现为**界面空白但不报错**，
方法名写错表现为**点了没反应**，都很难一眼看出来。

`check.py` 我做过注入测试：故意写错一个字段名、一个方法名、一个组件属性，
三个都被抓到了。

---

## 从 Android 版移植的关键逻辑

这几处都是 Android 版踩过坑修出来的，**改动会重新引入 bug**：

### 周次对齐（`utils/week.ts`）

教务的「第 N 周」是**周日~周六**，本项目的「第 N 周」是**周一~周日**。
直接用接口传的 `zc` 会让周日的课错位一整周。

正确做法是用每行的 `qsrq`（真实日期）反推周次。测试里有一条专门钉这个：

```js
// 教务第 8 周是 10-25(周日)~10-31(周六)
// 10-25 在周一制里属于第 7 周，不是第 8 周
assert.strictEqual(week.weekOf(SEM, parseDate('2026-10-25')), 7);
```

### 绩点口径（`utils/gpa.ts`）

学校评优/保研用「必修 + 限选」，任选不计。但：

- 「通识课」里装的是体育、毛概、大学英语 —— **都是必修**，不能按"通识"二字排除
- 「专业限选课」要算，不是所有带"选修"的都排除

### Cookie 按 (name, path) 存（`services/cookie.ts`）

一卡通在同一域名下有两个同名 `JSESSIONID`：`Path=/user` 和 `Path=/sso`。
只按 name 存会让后者覆盖前者，发请求带错 Session。

本项目虽然没做电费，但教务自己也可能有这个问题，
所以 `cookie.ts` 从一开始就按 path 分开存。

### 登录页识别（`services/request.ts`）

学校的会话过期**不返 401**：302 跳 CAS，最终 200 返回登录页 HTML。
不识别的话，上层会拿登录页去 `JSON.parse`，报「数据格式不对」——
用户看到莫名其妙的话，其实只是要重新登录。

判据：以 `<` 开头（排除 JSON，零误判）且含「统一身份认证」；
或 JSON 形式的 `-401` / 「尚未登录」。

### 学分条分母（`utils/gpa.ts`）

公选视角下，进度条分母 = `max(4, 各类最大值)`。

- 每类都没超 4 → 分母 4，满 4 分顶满，一眼看出哪类还差
- 有人某类修到 6 → 分母退到 6，4 分的条变 2/3，不会冲出格子

4 只当分母下限，**不用来判定"修够了没有"** —— 各年级培养计划不同，
所以卡片底部有一行灰字：「公选学分需求请自行查培养计划」。

---

## 与原 Android 版的差异

| 项 | Android | 小程序 | 原因 |
|---|---|---|---|
| 登录 | WebView 里输账密 / 扫码 | 只能扫码 | 没有 WebView |
| 电费 | 有（WebView 注入抓页面） | 无 | 同上 |
| 毛玻璃底栏 | 有 | 系统原生 tabBar | tabBar 不支持 backdrop-filter |
| 平板分栏 | 有 | 无 | 小程序主要在手机用 |
| 重叠课层叠轮换 | 有 ⇄ 按钮 | 只能并排 | 屏窄，层叠会更晕 |
| 上课提醒 | 有（通知） | 无 | 小程序不能发本地通知 |
| 检查更新 | 有（GitHub API） | 无 | 小程序由微信管更新 |

---

## 已知问题

1. **`appid` 是 `touristappid`** —— 云函数调不通，必须换（见「必须解决」）
2. **没上真机** —— 见上面「还没有真机验证」列的可能问题
3. **`ROW_H = 108rpx` 是写死的** —— 节次不齐（比如某天只有 3 节）时会留白，
   但不是 bug。想自适应得先量容器高度，复杂度高，先不做。
4. **课表不支持横滑切周** —— 用的是顶部周次条点击。小程序里
   `swiper` + `scroll-view` 嵌套容易手势冲突，点击更稳。

---

## 授权

二维码模块内联自 [qrcode-generator](https://github.com/kazuhikoarase/qrcode-generator)，
Copyright (c) 2009 Kazuhiko Arase，MIT License。原声明保留在 `utils/qrcode.ts` 开头。

其余代码与 Android 版同源，作者自制。