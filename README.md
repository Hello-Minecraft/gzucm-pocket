# 广中医口袋

广州中医药大学学生自用的 Android 客户端，把**教务系统**和**一卡通**的信息集中到一处：课表、成绩、宿舍电费。

> **非官方项目。** 与广州中医药大学无任何隶属关系，未获学校授权或认可。
> 数据直连学校系统，不经过任何第三方服务器。

## 功能

### 课表

- **按周抓取**：每节课都带「上课内容」「授课教师」「真实日期」，不是笼统的学期总表
  - 例如同一天两节中医各家学说，分别显示讲「王泰林」和「张锡纯」
- **水平翻页**：跟手滑动的翻页动画，可拖动、松手按速度吸附、边界回弹
- **自定义背景**：从相册选图当课表背景，不透明度可调
- 自动定位当前周，可左右切换查看任意周

### 成绩

- 课程成绩、等级成绩、体测成绩
- 列表滚动做了优化（排序分组缓存 + 整数运算格式化），不卡

### 电费

- 显示宿舍剩余电量、近 7 天日均、可用天数、用电趋势图、每日明细
- **每天首次打开 App 时后台静默更新**，不弹窗；想看最新的可以手动刷新
- **不足 3 天时推送提醒**（每天最多一次，不重复骚扰）

### 其他

- 默认启动页可选（今日 / 课表 / 成绩 / 电费）
- 深色模式跟随系统
- 「关于软件」里能看到历次更新日志

## 截图

<!-- 有截图后放这里 -->

## 技术要点

### 为什么电费必须用 WebView

一卡通（`ecard.gzucm.edu.cn`）的会话存在于 WebView 的 Cookie 里，用 OkHttp 直接调接口拿不到有效会话。所以电费是这么做的：

1. 用不可见（1dp）的 WebView 加载电费页
2. 在页面里**劫持 `XMLHttpRequest`**，截获页面自己发出的 `getBalance` / `getDailyDetails` 请求
3. 把响应通过 `addJavascriptInterface` 回传给 Kotlin 解析

这样参数、token、cookie 全都由页面自己生成，客户端不用猜签名规则。

### 课表按周查询

同一个接口 `POST /new/student/xsgrkb/getCalendarWeekDatas`，`zc`（周次）参数决定返回粒度：

| `zc` | 返回 | 差异 |
|---|---|---|
| 空 | 整学期，50 行 / 40 字段 | 有 `jxcdmc2`（逐周教室串），**没有上课内容** |
| 具体周次 | 该周，17 行 / 43 字段 | 多出 `sknrjj`（上课内容）、`qsrq`（真实日期） |

**行数完全一致**（同一周两边都是 17 条，逐条对得上），所以只需把 `zc` 换成周次，就能拿到更细的字段。学期总 19 周、233 课次，上课内容 / 真实日期 / 授课教师覆盖率均为 100%。

实现上并发 5 个请求逐周拉取；全部失败时自动退回学期总表，至少还有课表可看。

### 隐私

- 账号密码**只在学校的认证页面输入**，App 不接触、不保存、不上传
- 抓到的会话 Cookie 存在应用私有目录，退出登录即清空
- 无统计、无埋点、无广告、无第三方 SDK

## 构建

需要 JDK 17+ 和 Android SDK（compileSdk 35）。

```bash
git clone https://github.com/<your-name>/gzucm-pocket.git
cd gzucm-pocket
echo "sdk.dir=/path/to/Android/sdk" > local.properties
./gradlew assembleDebug
```

### 签名

仓库里**不含签名密钥**。构建时若根目录有 `keystore.properties` 就用它签名，没有则自动退回 debug 签名：

```properties
storeFile=app/your-key.jks
storePassword=****
keyAlias=****
keyPassword=****
```

## 测试

```bash
./gradlew testDebugUnitTest
```

110 项单元测试，覆盖：

| 测试 | 说明 |
|---|---|
| `WeeklyScheduleTest` | 按周课表解析，样本是真实响应脱敏后 |
| `EcardParseTest` | 电费解析（余额带单位、`dateTime` 字段、0 值陷阱） |
| `PowerAlertTest` | 电费提醒判定：何时该提醒、何时绝不能误报 |
| `ReleaseNotesTest` | 版本日志护卫：最新条目必须等于当前版本号 |
| `ParsingTest` / `SemesterTest` | 成绩解析、教学周历计算 |
| `LoginFlowTest` | 登录跳转状态机 |

## 致谢

`gzucm-probe/` 里的抓包脚本用于逆向接口字段，用的 Playwright + CDP。
仓库里不含任何真实抓取数据（课表、房号、Cookie 均已排除）。

## 许可

[MIT](LICENSE)

## 免责

- 本项目仅供学习交流与个人使用
- 请遵守学校的信息系统使用规定，不要高频请求
- 因使用本软件产生的任何后果由使用者自行承担
- 若学校方面有异议，会立即停止维护并删除仓库
