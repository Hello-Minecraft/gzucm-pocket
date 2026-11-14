# 广中医口袋 · 踩坑与实现内幕

> 高频约定（发版流程、构建命令、设计改动先给方案）在 `MEMORY.md`，**这里只放「为什么这么写」和踩过的坑**。
> 项目根：`D:/App/_android/GZUCM-Pocket`。单模块 Kotlin + Compose，minSdk 26 / targetSdk 35，
> 无 Room、无 DataStore（只有 SharedPreferences + kotlinx.serialization）。

---

## 一、自定义日程（v1.3.5）

### 架构铁律：日程绝不进 `state.sessions`

教务侧有三条会清课表缓存的路径，日程混进去必丢：

1. `doSync` 成功后 `store.cachedSessions = 整份 JSON`（整表覆盖）
2. `bootstrap` 里 `dropStaleSessionCache()`（`sessions_data_version` 不匹配就 remove）
3. `clearScheduleCacheForCampusSwitch()` / `logout()`

所以日程走独立 `AgendaStore`（**独立 SP 文件 `gzucm_pocket_agenda`** + 独立 key + 类级锁），
`UiState.agenda` 与 `sessions` 并列，**合并只发生在 UI 层**（各自通道传参）。

副作用是好的：`ClassReminder` 只读 `state.sessions`，所以日程天然不进上课提醒，一行提醒代码都不用改。

### 分钟 ↔ 行映射 `AgendaGeometry`

每节 40 分钟 → **0.8 行**；节与节之间的空档（10 分钟课间 / 20 分钟大课间 / 80 分钟午休）
统一压缩到 **0.2 行**。早于第一节钳到 0，晚于末节钳到 `slots.size`。

`slots` 不是写死的 1..14：按**整学期所有课的 `min..max` 节次**算，没课时才 fallback 前 9 节。
日程自身不参与 `slots` 计算，否则一条晚间日程会把所有周的行数一起撑高。

### 视觉规则（全是实测调出来的，别凭感觉改）

| 规则 | 数字 | 为什么 |
|---|---|---|
| 与课程重叠时收窄 | 靠右 **62%** | 52% 时文字区只剩 19dp，「经典等级考」要折 3 行、时间还会被裁 |
| 早于第一节 | 同样收窄 | 26dp 卡片映射到 row 0，正好盖住第一行课名 |
| `compact`（26dp 单行） | **只由高度决定** `spanDp < 34f` | 曾写成 `overlapsCourse \|\| spanDp < 34f`，一个半小时的「见习报到」被压成 26dp，标题只剩「见…」 |
| 标题行数 | 按卡片实际高度算 | 写死 `maxLines = 2` 时 14:30–17:30 的高块只显示两行 |
| 起止时间 | **竖排两行** | 横排「14:30-17:30」要 44dp，窄列只有 22dp，会被裁成「4:30」 |
| 左侧竖色条 | **不要** | 3dp 条 + 4dp 间距白吃 7dp；虚线边框已经够区分了 |

### 重叠：沿用课程 `placeSessions` 那套，别自造

- 手机 <840dp → **层叠**（上层右缩 3dp、下层 alpha 0.14 只显示标题首字、zIndex 递增）
- 平板 ≥840dp → **并排** `wFrac = 1/n`
- 簇 id 用 `10_000 + weekday * 100 + ci`，与课程 `clusterId`（`(dayOffset shl 8) or localId`，<2000）错开，
  **共用同一个 `rotations: Map<String, Int>`**（key `"$week-$cid"`），不用新状态
- 簇内顺序按 rotation 轮转 `raw.drop(k) + raw.take(k)`，**最后一条是 onTop**
- ⇄ 按钮照抄课程：`zIndex(99f)` + `requiredSize(28.dp)` + 13dp 小方块，
  位置 `x = colWidth - 28dp`、`y = cellHeight * bottomRow - 28dp`

### 草稿缓存 `AgendaDraftCache`

面板被误关时把 `EditDraft` 留在内存（`private object`，只留最近一条，key = 日程 id 或 `"new"`）。

```kotlin
val cachedDraft = AgendaDraftCache.peek(draftKey)   // 不能 remember(draftKey)！
```

**踩过的坑**：写成 `remember(draftKey) { peek(...) }`，第一次进组合缓存是 null，remember 把 null 记一辈子，
重开永远恢复不了。直接读（两次字段比较，无开销）。保存 / 删除时 `clear(draftKey)`。

### 滚轮选择器

`LazyColumn` + `rememberSnapFlingBehavior` + `contentPadding` 上下各留半屏 + 中间高亮条（画在列表**下层**）。
**必须给固定高度**，否则在 `verticalScroll` 里会抛 infinity height 崩溃。
折叠式（点一行才展开）比常驻滚轮好：常驻会占半屏，且手指落在滚轮上想滚表单会误改日期（实测 11/14 被滑成 10/30）。

---

## 二、考试安排（v1.3.5）

`Jw.fetchExams` 早就写好了但**从没被调用**，v1.3.5 才接进 `doSync`。

### 真实字段（2026-11-14 抓到的样例，别再猜）

```
kcmc      课程名            ksrq   日期 "2026-11-14"
kssj      时间 "14:30:00--17:30:00"（两个连字符，秒位全 0）
kscdmc    考场 ← 注意！不是 cdmc / jxcdmc
zwh       座位（数字）
ksaplxmc  安排类型（综合考）  khxsmc 考核形式（笔试）
kslbmc    类别（综合考试）    jkteaxms 监考老师
xqmc      校区（三元里）      zc     周次
jcdm2     节次（06,07,08,09,10）
sjbh      试卷编号            kcbh   课程编号
xs        学分
```

**`kscdmc` 这条踩过**：原来猜成课表的 `cdmc`/`jxcdmc`，结果地点一直是空的。

### 渲染与缓存

- 转成 `AgendaOccurrence`：`sourceId` 前缀 `exam:`、`colorIndex = EXAM_COLOR_INDEX(-2)` → 主题 `error` 色
- 独立缓存 key `cache_exams`，**不参与** `sessions_data_version` 失效（改课表解析不该清考试）
- 抓取放在独立 `try` 里：失败不影响课表；**空结果不覆盖旧缓存**（否则一次网络抖动考试全消失）
- 切学段、退出登录时清 `cache_exams`（研究生没有对应接口，留着会串学段）

---

## 三、周次错位（2026-10-31，最隐蔽的 bug）

**教务第 N 周 = 周日~周六，App 第 N 周 = 周一~周日。** 差一天会伪装成「个别课读不到地点」。

修法三件套：① 用 `qsrq`（真实日期）反推 week；② `weekday == 7 → zc - 1`；③ 抓取范围 `1..totalWeeks + 1`。

**算法改了必须升 `sessions_data_version`**，否则老缓存还是错的，用户以为没修。

---

## 四、弹出面板 `AdaptiveSheet`

手机底部 Sheet / 平板右侧侧栏（380/460/400dp），设置页 `fromLeft = true`。

**四个必踩点**（每一个都真实发生过）：

1. 外层套 `if (show) { AdaptiveSheet(visible = true) }` → 进入动画不播
2. `BoxWithConstraints(fillMaxSize())` 常驻 → 不可见时仍占满父容器（电费卡被撑成空白）
3. `Popup` 承载 → 窗口只要存在就拦触摸，`visible = false` 时整屏点不动（用户报「卡死」）
4. **`confirmValueChange` 拦 `SheetValue.Hidden`** → M3 的 `hide()` 被永久拒绝，Dialog 关不掉。
   真机 `dumpsys window` 能看到 **3 个 MainActivity 窗口**（正常只有 1 个），空 Dialog 继续拦触摸。

**定稿写法**：`if (!visible) return` + `appear` 标记触发进入动画，不占位不拦触摸，退场无动画。

**要防误关，只能在数据层兜**（草稿缓存），绝不在手势 / 窗口层堵。
要半屏就给三档：`skipPartiallyExpanded = false`（半屏 ↔ 全屏 ↔ 关闭）。

**一个面板一个 Dialog**：详情别叠在列表上 —— Dialog 消费 BACK 后 Activity 又收到一次，**直接退回桌面**。
考试详情就是因此改成「同一个面板内切内容」（`AgendaListSheet(examDetail = ...)`）。

**Robolectric 截图**：手机端 `ModalBottomSheet` 是独立 Dialog 窗口，decorView 抓不到 → 全黑。
要拍表单必须用平板宽度（`qualifiers = "w900dp-h1000dp-xhdpi"`，≥840dp 走侧栏）。

---

## 五、登录 / 会话（v1.3.2）

**「重新登录」≠「退出登录」**：

- `reopenLogin` 只切 phase，**不碰会话与缓存**
- `logout` 才清 CookieManager + WebStorage + 各缓存

真 bug：`RootScreen` 写成 `vm.logout(); vm.reopenLogin()` → 重登要重输密码。已收口成 `vm.relogin()`。

**教训：只测 `reopenLogin()` 测不出调用方错，测试要覆盖入口。**

`logout` 的 `removeAllCookies` 是异步的，必须 `suspendCancellableCoroutine` 等回调。

### 会话过期不返 401

302 跳 CAS → 200 + 登录页 HTML。`Net.send()` 用 `looksLikeLoginPage()` 判断：
以 `<` 开头 + 含「统一身份认证」或（authserver 且 execution）→ 抛 Unauthorized。

---

## 六、课表重叠 / 平板自适应

- **手机 <840dp**：卡片叠放，上层右+下缩 3dp，右下角 ⇄ 轮换。透明度底层 0.78 / 上层 0.45，**文字不调淡**。
- **平板 ≥840dp**：一律并排 `wFrac = 1/n`。公式：起点 `c*inset`、宽 `1-inset`、重叠区 `1-2*inset`。
  **「完全重叠」必须进层叠分支**。
- 断点 `ui/adaptive/AdaptiveSize.kt`：600 / 840 / 1200，判据是 `BoxWithConstraints` 的 `maxWidth`。
- **宽屏不能有外层 `verticalScroll`**：嵌 `LazyColumn` 会抛 infinite maximum height 崩溃。

### `BoxWithConstraints` 里不能用 `matchParentSize`

父容器是 `BoxWithConstraints`（SubcomposeLayout）时，`matchParentSize` 的 parent data 不生效，
子级 `size()` 量成 **0×0** —— 卡片存在但一个像素都画不出来（真机日志能看到 `daycol agenda=1`，界面什么都没有）。
**用 `fillMaxSize()`**。

---

## 七、日视图 `DayScheduleView`

- Pager 以「**整学期第几天**」为索引（`pageOf(week, weekday) = (week-1)*7 + (weekday-1)`）
- `nowMinutes` 只在「看的那天就是今天」时传真实值，别的时候传 `-1`，否则翻到上周三那天的课也会被标成「正在上」
- 上课时间 **14sp** / 下课 **12sp**（曾经 20sp，比课名还抢眼）
- 「正在上」= 主色底 0.10 + 边框 + 色条 + 标签；已下课 alpha 0.55
- 手势回写必须用 `rememberUpdatedState` + `snapshotFlow { currentPage }`（**不是 `settledPage`**，否则标题与星期条错位）

---

## 八、检查更新

- `checkUpdateSilently()` 在 `bootstrap` **最前面**、`onLoggedIn` 补一次、`ON_RESUME` 查一次。
  曾经放在 launch 末尾，前面两个 `return@launch` 让「停在登录页就永远不查更新」。
- **兜底直链的 `v` 不能删**：tag `v1.3.2` → 附件 `gzucm-pocket-v1.3.2.apk`，
  `removePrefix("v")` 会 404。已抽成 `Updater.fallbackApkUrl()` 并测试锁住。

---

## 九、测试

- **失败先看异常类型**：`android.graphics.Color` 在 JVM not mocked，异常被 `assertTrue` 吞成「不达标」，
  加 `@RunWith(RobolectricTestRunner::class)` 解决。
- JUnit4 断言签名是 `assertEquals(message, expected, actual)`（**消息在前**）。
- `Color(r, g, b)` 通道范围是 0..1，不是 0..255。
- **测试写完要反向验证**：换回旧实现必须失败，否则这个测试没测到东西。
  并发测试第一版「写一次读一次」去掉锁照样过 —— `LinkedHashMap` 只有**结构性修改**才抛 CME。
- 测试 tmpdir 指向 `D:/App/_android/tmp`（`build.gradle.kts` 里配的），
  `conscrypt_openjdk_jni-*.dll` 和 `robolectric-nativeruntime*` 会堆在那里（每次 ~300MB），
  **跑完要清这里**，不只是 `%TEMP%`。

---

## 十、其它

- **电费会话在 WebView 里**，OkHttp 拿不到 → 只能 App 内弹窗同步（`EcardSyncSheet`）。
  换绑最后一步走微信支付，WebView 点不动 → 引导去小程序。
- 教务按周抓：`getCalendarWeekDatas` 传 `zc=周次` 才有 `sknrjj`、`qsrq`。
- 主题色只换 primary/secondary/tertiary 六槽，`seed = 0` 返回原配色。
- 打赏三档赞赏码静态图，**必须为自愿打赏、不解锁功能**。
- 图标按**外接圆**缩放而非 bbox；通知小图标用 `ic_notification`。
- 错误文案统一走 `core/UserMessage.kt`，翻中文并告诉下一步。
- **日程 / 考试相关的一切都不进 `ClassReminder`**。v2 要加日程提醒，得先抽公共 `AlarmPlanner`，
  不能直接改 `plan()` —— 它用 `requestCode(epochDay, startUnit)` + `used` 去重，
  日程没有真节次，伪造会跟真课程抢 code，把课程提醒吞掉。

---

## 十一、小程序 `miniprogram/`（已从主仓库移除）

登录只做扫码；正式环境必须走云函数 `jwproxy` 转发，只转发不落盘；电费不做。
待办：云开发环境 ID、云函数部署（超时 20s）、ICP 备案、名称「泥医口袋课表」。

> `miniprogram/` 在 `4201046` 那次提交里被整体提交进过公开仓库，`b36a8a7` 只是 `git rm --cached`，
> 历史 blob 仍在。里面有真实课表截图 —— 用户反馈「相册多出我的课表」就是有人下 ZIP 解压导致的。
