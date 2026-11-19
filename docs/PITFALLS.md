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

### 滚动容器里 `fillMaxHeight` 等于没写（v1.3.6 修）

`DayColumn` 的根原来是 `BoxWithConstraints(Modifier.fillMaxHeight())`，而它的上层是
`Column(verticalScroll)` → `Row`。滚动容器给下来的是**无限高度约束**，
`fillMaxHeight` 在无限约束下不生效，整列高度退化成「最高的课程块有多高」，
没有课的列直接是 0。

连环后果：

- 长按新建日程的热区是 `fillMaxSize`，跟着缩成一条缝甚至 0 ——
  **这个功能从 v1.3.5 上线起就没真正生效过**。界面上完全看不出来，因为课程块用
  `offset` 定位，画到父容器外面也不裁切。
- 「今天」那列的淡色带同样被压成一小截，颜色太淡一直没人注意。

修法：显式 `.height(cellHeight * slots.size)`。
回归测试 `AgendaLongPressTest` 先断言热区高度 ≥ 500dp，再真的长按一次 ——
把写法换回 `fillMaxHeight` 会直接失败。

教训：**判断一个触摸区域有没有生效，看代码不如断言它的高度**。
透明热区、靠 `offset` 画的元素，布局塌了肉眼是看不出来的。

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
- **别让测试依赖「当前时刻」**（2026-11-17 咬了一次）：`AgendaReminderScheduleTest` 拿「明天 09:00」
  配「提前 1 天」，凌晨跑时提前那条落在「今天 09:00」还没到 → 3 个闹钟；白天跑时已过 → 2 个，
  测试红。**凡是「相对今天」的排程断言，把锚点推到 3 天后**，让所有档位都落在未来。
- `LocalDate.now()` / 学期边界这类「相对时间」在测试里要显式固定或推远，不能在注释里假设「现在还没到」。

## 十二、截止任务 / 待办（v1.3.7 候选，2026-11-17）

任务 = `Agenda(isTask=true, deadlineAt, doneAt)`，和日程共用 store（致辉要「混在一个列表」）。
复用时踩的坑：

- **无 key 的 Column 会按位置复用组合**——首页任务列表勾选 A（`filter` 掉 A 后列表收缩），
  B 顶到 index 0 继承了 A 的 `TaskCheckCircle.checked=true`：显示成已勾选且点不动。
  修法：每项 `key(o.key)` + 已完成任务**不从数据里删**，用 `AnimatedVisibility(visible = doneAt==null)`
  淡出，撤销后原地恢复。日程页 `LazyColumn items(key={it.id})` 本来就有 key，不受影响。
- **勾选回调不能放进 `LaunchedEffect { delay(); onChecked() }`**——面板里勾完立刻关面板，
  组合移除取消协程，`onChecked` 永不执行：看到对钩和震动，任务却没完成。改成点击即时回调，
  动画交给卡片退出动画。
- **「显示明天」会误杀任务**：任务 occurrence 的 date 是今天，首页 `filter{date==明天}` 全过滤掉。
  修法：任务单独一路传参给首页，不混进 `agendaOccurrences`，不受日期过滤。
- **`buildDraft` 的 Elvis 会把「长期待办」变成 DDL**：`initial?.deadlineAt?.toString() ?: 默认值`，
  deadlineAt=null 时填了默认值，改个备注就凭空多出截止时间。用 `initial != null` 显式区分编辑/新建。
- **Dialog 提示条默认居中挡屏**：撤销条要用 `DialogWindowProvider` 拿到底层 window，
  `setGravity(Gravity.BOTTOM or FILL_HORIZONTAL)` + `setLayout(MATCH_PARENT, WRAP_CONTENT)`，才是底部一条。
- 已知边界（没做栈，工程权衡）：连续快速勾两条，撤销票据只留最后一条；
  但日程列表里已完成任务有实心勾可点取消，是第二个入口。

---

## 十三、周课表翻页卡顿（2026-11-17）

致辉报「release 也还有点小卡顿，往往是翻页第一下不跟手」。查下来三处叠加：

- **`beyondViewportPageCount` 默认 0 = 相邻页按需组合**。一周网格要组合 39 个节次文本
  + 二三十个课程块（每块两三个 Text），全压在手指开始拖的那一帧里 → 第一下像拖不动，
  后面几下反而顺。解法和取舍：**首帧画完再打开预排**（`withFrameNanos` 推两帧后置 1），
  既不让首屏多组合两页，翻页时相邻页又已经在树上。
- **`currentPage` 一变，整个 `ScheduleScreen` 重组**（表头、周条、日期行都要跟着变，
  这是刻意的：滑动中就该看到目标周的日期）。所以「周 → 星期」的分组、日程 filter、
  轮换 map 的 `filterKeys/mapKeys` 都不能写在页面内容里 —— 那些重跑正好压在手指还在动的帧上。
  全部提到页面层 `remember` 一次算完。
- **别用 lambda 传「今天」**：`isTodayOf = { wd -> ... }` 是每次重组的新实例，
  里面还逐列调 `LocalDate.now()`（一页 7 次）。改成 `todayWeek` / `todayWeekday` 两个 Int。
- **预排之后同一个 testTag 会在树上出现 3 份**：`onNodeWithTag` 直接
  「Expected exactly 1 node but found 3」。长按热区的 tag 必须带周次。

还有一条没动的大杠杆：`release` 目前 `isMinifyEnabled = false`（R8 关着），dex 大、
类加载也贵。要开得先补 kotlinx.serialization / DataStore 的 keep 规则，属于独立任务，别和 UI 改动一起上。

---

## 十四、包体：图标库换成 21 个本地图标（2026-11-17）

`material-icons-extended` 里光是 `Icons.Rounded.*` 就有 **2132 个类**（每个类里还编着
图标路径的构建代码），而全项目只用 21 个。图标没有反射用法（都是 `object` 的 getter），
所以可以直接搬成本地 `ImageVector`，再把依赖删掉。

- **别手抄 path**：手抄漏一位数就是笔画走形，而且肉眼很难发现。
  做法是读 `ImageVector` 的节点树（`VectorPath.pathData: List<PathNode>`），
  把节点还原成 SVG `d` 字符串，再用 `PathParser().parsePathString(d)` 装回去，
  顺便断言「解析回来节点数一致」。一次性工具（`IconDumpTest`）跑完即删。
- 结果：dex **46.0 MB → 23.4 MB**（3 个 dex 合并成 2 个），APK **12.94 MB → 8.74 MB（−32%）**。
- 以后加图标：见 `ui/icons/AppIcons.kt` 文件头 —— 从 Google 图标站抄 `pathData`
  贴一行，或临时把依赖加回来导出一次。
- 语义没变：`fill` 写死黑色，颜色仍由 `Icon(tint = ...)` 决定（`SrcIn` 滤镜），
  和原来 `Icons.Rounded` 完全一致。`material-icons-core` 仍会被 material3 传递依赖，
  剩 49 个图标类，可以不管。

---

## 十五、课表导出文件：FileProvider 与 Windows 单测（2026-11-18）

分享串能塞进微信（约 1300 字符），但为了这个长度砍掉了逐周上课内容。所以补一条文件通道：
写 `cacheDir/exports/` → `FileProvider` 换成 `content://` → 系统分享面板。三条容易踩的：

- **Android 7+ 不能 `file://` 分享**，会抛 `FileUriExposedException`，必须走 FileProvider。
  Manifest 里 `exported=false` + `grantUriPermissions=true`，`res/xml/file_paths.xml`
  只开 `<cache-path path="exports/">`（别开 `.`，等于把整个缓存目录交出去）。
- **`EXTRA_STREAM` 和 `ClipData` 两个都要写**。有些接收方（含旧版微信）只从 ClipData
  读授权，只给 `FLAG_GRANT_READ_URI_PERMISSION` 的话对方拿到的是读不了的 URI，
  表现为「发送失败」或发过去 0 字节。
- ⚠️ **`FileProvider.getUriForFile` 在 Windows 上必然抛异常**，不是配置错：
  androidx.core 1.13.1 的 `SimplePathStrategy.belongsToRoot` 拼的是 `root + "/"`，
  正斜杠写死；Windows 的 `File.getPath()` 是反斜杠，永远匹配不上，报
  「Failed to find configured root that contains …」。**Android 上的分隔符就是 `/`，真机没问题**。
  所以 Robolectric 单测不要去断言 `getUriForFile`，改成验两半：
  「文件落在 `cache/exports/`」+「`file_paths.xml` 声明了 `cache-path name=exports path=exports/`」。
  拼 Intent 的那半边抽成 `shareIntent(name, uri)` 单独测，别被这个平台缺陷挡住。

---

## 十六、课表分享串：合并键不能把教师算进去（2026-11-18 第二次踩）

导出的「课程模板」是按 (课名, 星期, 起止节, 教室, 教师…) **全等**才合并的。
见习轮转阶段每天换科室、每周换带教老师 → 一门「外科学(2)」拆成 30 条模板，
14 门课共 **151 条**，压完 5124 字符，微信发不出去（实测于致辉的手机导出）。

- **别按「条数」估体积**：同一份课表在普通学期 25 门 / 18 周只有 1300 字符，
  见习学期同样 14 门却是 5100 —— 差别全在「模板碎不碎」。
- 分享串改 **GZP2**：字典（课名/教室）+ 只按 (课名, 星期, 起止节) 分组 + 周次区间写法。
  151 条 → 42 组，**5124 → 1751 字符**；文件那边保持完整版（含教师/类别/配色/内容）。
- **最贵的一列是教师**：115 个带教老师几乎不重复，deflate 压不动，单这一列 ≈ 1400 字符。
  想留在串里就必然超 2000，所以串里不带 —— 界面上要写明「不含任课教师」，
  不然用户会以为导出漏了。
- 单测闸门要按**轮转数据**建：拿「25 门普通课」测长度是测不出问题的（那是 1300 字符的假安全）。

## 十七、浅色自定义背景的「灰蒙」（2026-11-18）

现象：浅色模式选了壁纸后，图上总像蒙了一层灰白，彩色全被冲淡；深色模式没这问题。

根因不是「alpha 调得不够高」，而是**透出来的底色**：`CourseBackground` 原来把图片按
`bgAlpha` 半透明画（默认 0.45），剩下 55% 露出页面底色 `#F6F7F9`，上面又铺了
`background@0.18` 的「压高光」层。合成后浅灰白占 **63%**、壁纸只剩 37%。
深色模式露出的是 `#0A0C0F`，看着像「变暗」而不是「灰蒙」，所以只有浅色会被报。

改法（`components/CourseBackground.kt` 的 `backdropPaint()`，纯函数可单测）：

- 浅色：图片**满绘**（alpha=1），滑块改为控制盖在上面的浅色遮罩 `(1-a)*0.6`。
  默认 0.45 时遮罩只有 33%；拉到 1.0 归零，壁纸 100% 显色。
- 深色：**原样保留**（图片 alpha=a + tint@0.18）。别顺手一起改 —— 课程块配色是按深色底调的。

配套：图片满绘后，压在背景上的文字就看不清了。这里试过两版，**两版都被否掉**：

1. 第一版给文字垫 0.62 的浅色圆角底板（`drawBehind` 画在文字外侧）。实机上是一排
   白方块贴在壁纸上，像补丁——致辉 2026-11-18 直接否掉。
2. 第二版改成「浅色主题一律用深色字 + 加粗」。也不对：壁纸明暗跟主题无关，
   浅色主题配深色壁纸时那样会变成黑字压黑底。

最终按设计文档 §5.2 做**按背景亮度切墨色**：
`rememberBackdropInk(blurredBg)` 采样模糊小图**顶部 45%** 的平均亮度，
`≥ 0.5` 用深色字（#14181C，主 92% / 次 66% / 弱 46%），否则用浅色字（白 96% / 76% / 56%），
经 `LocalBackdropInk` 下发，`backdropInkColor(ink, level)` 统一取色；壁纸上字重加粗一档。
只看上半部分是因为课表页这些文字全在屏幕上部，上下渐变的壁纸用全图平均会被下半部带偏。

另外两个测试坑（同一轮踩的）：
- 壁纸解码在 `Dispatchers.IO`，`Thread.sleep` 期间 Robolectric 的 Looper 是 PAUSED，
  回调执行不了。要「sleep 100ms + `ShadowLooper.idleMainLooper()` + `waitForIdle`」循环，
  否则截到的是 ink 还没判定的那一帧（表现为「代码明明改了，截图没变化」）。
- `makeTestWallpaper` 的深/亮两张图必须用不同文件名，否则后一张覆盖前一张，
  测出来的亮度永远是同一个值。

判断深浅走 `background.luminance() > 0.5f` 而不是 `ThemeState`：截图测试直接给
`GzucmTheme` 传 `darkTheme`，并不改 ThemeState，用后者会和实际配色对不上。

## 十八、G3 连续曲率圆角（2026-11-18）

按 `桌面/vibecoding/液态玻璃DESIGN.md` §2.3 复刻，实现在 `ui/components/G3Shape.kt`。
解析轮廓：`R=min(r,短边/2)`、`L=min(1.6R,短边/2)`、`a=max(0.02,(2v⁴−v²)/(1−v²))`、
`f(t)=t⁴/[a+(1−a)t²]`、`F(x,y)=f(x/L)+f(y/L)−1=0`。每个角采样 32 段拼 Path，
按尺寸 + 布局方向缓存（`createOutline` 会被 background / clip 反复调用）。

**坑一：局部坐标原点在过渡区外角，不是矩形角点。**
直接把 `(x, g3CornerY(x))` 当屏幕坐标拼 Path，曲线会朝矩形内侧弯，
Compose 用 NonZero 填充就把四角挖成**十字缺口**——看起来像「所有圆角都坏了」。
左上角的正确映射是 `(L−y, L−x)`，其余三角镜像。自检：`v=L/2` 时曲线应靠近角点，不是远离它。

**坑二：`clip(G3CornerShape)` 在 Robolectric 里点不动。**
Compose 对 `Outline.Generic` 的命中检测走 `ShapeContainingUtil.isInPath` →
`Path.op(Intersect)`。LEGACY 图形模式的 ShadowPath 不支持 op，交集恒为空 →
整个节点点击失效（`ExportContentTest` / `FloatingBarAutoHideTest` / `UiSmokeTest` 一起红）。
真机用 NATIVE 绘制，不受影响。修法：`app/src/test/resources/robolectric.properties`
全局 `graphicsMode=NATIVE`，不要只在截图测试上加注解。

**坑三：直角分支必须返回固定长度的采样数组。**
非对称圆角（上两角 24、下两角 0）会走到 `radius=0` 的角，
曾经提前返回 `FloatArray(1)`，而拼 Path 的循环按 `SAMPLES=32` 索引 → `ArrayIndexOutOfBounds`。

**边界**：Compose 只能输出 `Outline.Generic(Path)`，这是解析方程采样，
不是文档说的「解析覆盖率栅格化」，对外不要宣称严格数学 G3。
`CircleShape` 不动（正圆曲率恒定）。非对称圆角要显式给另一边 0：
`RoundedCornerShape` 缺省是 0，`G3CornerShape` 的缺省跟着 `topStart`。

---

## 十九、云母面板：无描边 + 阴影（2026-11-19）

`ui/components/MicaPanel.kt`。原来全项目**零阴影**，卡片靠 `surface` 贴 `background` +
一圈 0.6~1dp 描边分边界。深色下 `surface #12151A` 与 `background #0A0C0F` 只差 8/255，
`outlineVariant #1E242B` 更是几乎同色 —— 描边根本看不见，卡片是「糊」在背景上的。

**坑一：深色下只加阴影等于没改。**
黑色 elevation 叠在近黑底上肉眼不可见，反而让面板更平。深色必须
①面板底色提亮一档（`#171C23`）②顶边 1dp 内高光（白 7.5%）。立体感来自明度分层，不来自阴影。
浅色 `#FFFFFF` 贴 `#F6F7F9` 同理，但白卡上的阴影是有效的，所以浅色不加高光。

**坑二：自定义 Path 的阴影在 API 26/27 不渲染。**
minSdk 是 26，系统只给 rect / roundRect / oval 的 outline 算阴影。
折中：阴影层用同半径 `RoundedCornerShape` 画（全版本支持），本体用 `G3CornerShape` 画底。
同半径下两者差不到 1px，7dp 模糊后不可辨。`shadow(..., clip = false)` 不能省 ——
用圆角矩形裁内容的话，后面的 G3 轮廓就白做了。

**层级**：每张卡只有 `shadow`（graphicsLayer）+ `background(color/brush, g3)`（一次绘制）。
带 shape 的 `background` 已经按 G3 轮廓画底，面板内容都在内缩 padding 里，**不需要再 `clip`**
（那是第二层 graphicsLayer，白开销）。深色再多一次 `drawBehind` 画顶边高光，可忽略。

**坑三：`clip` 会把阴影一起裁掉，顺序错了一点点效果都没有。**
`Modifier.clip(shape)` 裁剪的是**后续所有绘制**。`GradesScreen` 的 `StatPill` 原来写成
`then(clickable)`（内含 clip）在前、`micaPanel` 在后，阴影整圈被裁掉，
表现是「代码改了但界面没变」。正确顺序：`micaPanel` → `clickable(clip)` → `border` → `padding`。

**坑四：相邻同色卡之间原来没有间距。**
设置页「操作卡」和「打赏卡」原本紧贴（同色 surface 无缝，看不出是两张）。
加阴影后两圈影会在缝里叠成一条黑线，必须补 16dp 间距。

**坑五：Robolectric 截图不渲染 elevation 阴影。**
`ScreenshotTest` 走 `view.draw(Canvas)` 软件绘制，阴影是 HWUI 合成阶段的事，一张都不会出。
截图只能验「布局没塌、深色提亮生效、描边确实没了」，**阴影强度必须真机看**。

**范围**：只改容器级（设置 7 张、关于 2 张、导出 1 张、成绩 4 处、电费 2 张、今日 3 张、
Sheet + 右侧 Pane）。列表行卡（成绩行、电费日行、日程行…）不加 —— 行距只有 3~9dp，
逐行带影会互相吃掉，滚动时还多一层合成开销。课程块 / 日程卡 / 各种 chip 不动。
日视图课程卡也走同一档（中间一度单独给过 4/6dp，致辉要求"全部对齐电量卡"后取消分档）。
`MicaLevel` 枚举还留着，但 `micaElevation` 已经不看它了 —— 以后想恢复分档只改那一个函数。

### 四轮真机修正（同一天，都记下来免得再走一遍）

**① 阴影偏重 → 10dp 降到 7dp。** 10dp 是 FAB 量级，压在 16dp 间距的卡片列表里，
下边缘那条影比卡片本身还抢眼。

**② 千万别给面板铺"斜向微渐变"。** 试过用 3% 的「左上亮 → 右下暗」渐变让阴影过渡柔和，
结果浅色大卡（今日页本周概览）底部积出一条明显灰带 —— 3% 的色差在渐变终点不是"3%"，
而是"卡片底下多了一条边"。致辉的原话是「概览面板下方的阴影高了几个像素」，
其实那条带跟阴影毫无关系。**立体感靠阴影 + 深色顶边高光，不要动底色。**
只有"品牌色渐变"（主色 → 面板色，两端同一底子）才不会积灰，比如余额卡/电量卡。

**③ 「下方多出一截」是 spot shadow 的投影，但它不该被关掉。** 系统 elevation 阴影是两束：
`ambient`（以轮廓为中心均匀扩散，无方向）+ `spot`（模拟屏幕上方光源，强度更高、向下偏）。
一度把 `spotColor` 置透明只留 ambient —— 结果整体太淡，被否。
**spot 是阴影强度的主要来源，别关。** 也不要上自绘：`BlurMaskFilter` 在 API 26/27
硬件加速下不保证生效，多层描边模拟会出洋葱圈。

**④ 阴影的"位置效应"是系统设计，不要修。** 致辉 2026-11-19 自己发现：同一张卡
滚到屏幕上方影浅、下方影长，滚动时还会变。原因是 **spot 光源挂在窗口上**（默认在窗口
上方中央），不是每张卡各打一盏灯 —— 整屏共享一盏。这是 Material 的纸片物理模型。
曾按"卡片高度"给高卡降过一档（`MicaLevel.Tall`），第四轮撤掉：高度只决定底边到光源的距离，
真正的主变量是卡片在光源坐标系里的位置，动态变化，静态分档治不了。

**⑤ 阴影溢出必须小于卡片间距。** 9dp 阴影向下溢出约 14dp，所以卡片间距统一到 16dp
（电费页原来 12dp，两张卡的影会接成一条连续灰带）。电费单列的「每日明细」行保持 12dp，
只给两张卡片各补 4dp —— 不能直接把 LazyColumn 的 spacedBy 调大，那会连带把明细行拉散。

**⑥ 最终形态：原生阴影 + 最少层级。** 致辉 2026-11-19 拍板「用最原生的状态，不要加太多层级，
保证渲染流畅」。现在 `micaPanel` 不传 `ambientColor` / `spotColor`（系统默认就是对的），
不分档，每张卡只有 `shadow` + `background(shape)` 两层，**没有单独的 `clip`**。
深色才多一次 `drawBehind` 画顶边高光。全部面板统一 7dp（浅）/ 3dp（深）。

**⑦ Sheet 容器不能用面板色，否则深色下和卡片同色、分组全消失。**
致辉 2026-11-19 报「深色设置页底色跟面板完全一样，看不出分组」。根因：
`AdaptiveSheet` 的 `containerColor` 和里面的卡片都取了 `micaPanelColor()`（#171C23），
浅色下还能靠 7dp 阴影分开，**深色下阴影是黑叠黑、只剩 3dp，等于没有**，整页塌成一块平板。

修法是给 Sheet 单独一档，形成三层：

| 层 | 深色 | lum | 浅色 |
|---|---|---|---|
| 页面 `background` | `#0A0C0F` | 0.0036 | `#F6F7F9` |
| Sheet / 右侧 Pane | **`#101318`**（`MicaSheetFill`） | 0.0064 | `#FFFFFF`（同 surface） |
| 卡片 | `#171C23`（`MicaDarkFill`） | 0.0113 | `#FFFFFF` |

**不要用主题的 `surface`(#12151A) 当 Sheet 色**：跟卡片只差 5~9 个色阶，仍然偏细；
`#101318` 拉开到 7~11，差一点点就是"看得见 / 看不见"的分界。
**也不要把卡片反过来提亮**：那会让设置页的卡片比今日页/成绩页的卡片亮一档，
同一种材质出现两个亮度，跨页面不一致。改 Sheet 只影响两处，卡片色全局统一。

浅色不做这个分层 —— 白 Sheet + 白卡片，7dp 阴影已经画出边界，再压暗反而发灰。

**截图测试要点**：`ScreenshotTest.settings`（`07_settings`）是直接渲染 `SettingsScreen`，
外壳走 `shot()` 的 `Surface`（= 主题 surface），**不是 AdaptiveSheet 的真实底色**。
验这个改动必须看 `settingsInSheet`（`15_settings_in_sheet`），它套了一层 `micaSheetColor()`。
两张图并排才看得出差别 —— 只看 `07_settings` 会误判成"已经分开了"。

---

## 二十、日视图三连修：磨砂拉伸 / 文字墨色 / 三档状态（2026-11-19）

### ① 横滑时磨砂被抻长 —— 两轮才修对

**第一轮（错）**：`frostSrcRect` 只把源矩形 `coerceIn` 进图像范围，`drawImage` 的目标永远是整格，
越界那侧源被压短、目标不变 → 横向放大。于是改成 `frostSample`，返回**源 + 目标**两个矩形，
按交集比例同时缩，越界部分不画。纯函数测下来全过（`srcW/dstW ≈ srcH/dstH`）——
**但真机上照样拉伸。**

**第二轮（对）**：换成 `BitmapShader` + `TileMode.CLAMP`。

```kotlin
matrix.setScale(scale, scale)
matrix.postTranslate(ox - relX, oy - relY)
shader.setLocalMatrix(matrix)
drawRect(brush = ShaderBrush(shader), alpha = alpha)
```

关键在于**矩阵里没有任何"目标尺寸"**。`drawImage(srcRect, dstRect)` 本质是两张独立的矩形，
宽高比只要有一边被取整或裁剪破坏就是拉伸；而仿射变换只有一个 `scale`，
由「位图尺寸 + 壁纸区尺寸」唯一确定，**跟着格子多宽多高、滑到哪都无关** —— 物理上不可能拉长。

越界部分交给 CLAMP 用边缘像素延拓，顺带解决了「半张卡没磨砂」。

**教训**：能用"一次固定变换"表达的绘制，不要拆成"算源 + 算目标"两个独立量。
可测函数也改成 `frostTransform`（返回 scale + dx/dy），测试直接断言
「不同格子尺寸 / 不同位置下 scale 不变」—— 这才是"不拉伸"的数学本质。

**周视图和日程卡也吃同一个 `frostedBackdrop`，所以一并修好了。**

**第三轮（同日）：换 shader 之后仍是"位置错"，不是"形状错"。**
致辉的原话：「前一天的模糊是有的，但是位置不对……背景中间的塔在正中间，
左边卡片滑入时理应不显示出底下的塔，但他的塔底色有，且位置是在动画完成时的位置。」
以及关键补充：「后一天的模糊就是实时的」（指右侧当前页一直是对的）。

**根因：`boundsInRoot()` 默认带裁剪。**

它的实现是 `findRootCoordinates().localBoundingBoxOf(this, clipBounds = true)` ——
**默认把矩形裁到所有父容器的裁剪区**。卡片被 Pager 拖出屏幕、只露出一半时：

```
卡片真实 x ∈ [-304, 486]   →   boundsInRoot() 返回 [0, 486]（left 被钳到 0）
```

采样点从 -304 变成 0，偏了整整 304px —— 卡片里贴的是**屏幕左边缘**那块壁纸，
而不是它正后方那块。所以看起来"磨砂有，但位置是动画完成时的位置"。

**这同时解释了「为什么只有前一天错、当前页永远对」**：卡片完整落在屏幕内时，
裁剪不改变任何数字。只有被拖出边界的那一页才会被钳。所以左边滑进来的必错，
右边当前页永远正确 —— 正是致辉观察到的现象。

修法：

```kotlin
private fun LayoutCoordinates.unclippedBoundsInRoot(): Rect =
    findRootCoordinates().localBoundingBoxOf(this, clipBounds = false)
```

**走过的弯路（写下来避免再犯）**：第一版把现象归因成「Pager 用图层平移表现中间态、
`boundsInRoot()` 不含那层平移」，于是加了一个 `scrollDx = -offsetFraction × 页宽`
的补偿，还通过 `CompositionLocal` 传 `FloatState` 以避免重组。方向完全错了：

- 那个补偿**多补了一次**，把本来正确的当前页也推歪 → 致辉实测「完全是乱飞的」；
- 真凶是**裁剪**，不是「布局坐标 vs 视觉坐标」。

**教训：`boundsInRoot()` 的名字看不出它带裁剪。** 要「元素真实占位」时一律用
`clipBounds = false`；要「屏幕上可见的部分」时才是默认行为。磨砂、投影、
位移跟随这类"我需要知道我在哪"的场景，全都属于前者。

**顺带修好**：周视图课程块、日程卡也吃同一个 `frostedBackdrop`，
它们滚动出视口时同样会被钳，现在一起对了。

复现用例：`ScreenshotTest.dayFrostSwipeBack`（向右拖一半、不抬手截图）与
`dayFrostSwipeForward`（反方向对照组）。**这种 bug 静止截图永远拍不出来**，
必须停在滑动中间态 —— 前几轮没拍到，就是因为只拍了落定后的画面。

### ② 低不透明度 + 极端壁纸时文字读不出来

`cellOpacity = 10%` 时卡片底色 ≈「磨砂壁纸 + 30% 雾面」，几乎就是壁纸本身：

| 场景 | 卡片实际底色 | 原字色 | 对比度 |
|---|---|---|---|
| 纯黑壁纸 + 浅色主题 | `#4C4C4C` | `#14181C` | **2.1:1** |
| 纯白壁纸 + 深色主题 | `#B9B9B9` | `#E9EDF0` | **1.7:1** |

WCAG 正文要求 4.5:1，这两个都只有三分之一；教师/教室行还额外乘了 0.75/0.8，
最先消失。

修法是 `rememberCardInk`：按**卡片实际底色**判定墨色 ——
`壁纸亮度 × (1-veil) + surface亮度 × veil`，不是壁纸原亮度。
直接复用 `rememberBackdropInk` 会系统性偏移，因为 veil 把亮度往主题方向拉了 30%。

两个关键点：

- 用**整图**平均（卡片铺满整屏），不是 `rememberBackdropInk` 的顶部 45%（那是给顶部文字用的）
- **课程色不换墨色**（它是识别码，同一天不同课靠颜色分块），只压明度：
  亮底 `lerp(color, Black, 0.35)`，暗底保持

`Unknown`（没壁纸 / 还没解码）时回退主题色，行为跟改版前一致。

### ③ 三档状态：原来只有两档

改前 `dim = if (past) 0.55f else 1f` —— 「正在上」和「未上」完全一样。
更糟的是「正在上」的底色是**写死的 `primary 10%`，滑块对它完全无效**，
和「整体受滑块控制」直接矛盾。

现在：

| 状态 | 底色系数 `stateDim` | 文字系数 `textDim` |
|---|---|---|
| 正在上 | 1.00 | 1.00 |
| 未上 | 0.75 | 0.85 |
| 已上过 | 0.45 | 0.55 |

- 底色 `alpha = 滑块 × 0.85 × stateDim`
- 「正在上」的主色底也乘滑块，系数 `OngoingTintRatio = 0.30`，
  保证滑块 40% 时仍是原来的 0.10（观感不变，但现在跟着滑块走）
- **文字不跟底色等比**：底色 0.45 已经够透，文字再乘 0.45 会让上午上过的课
  整张消失，回看时找不到。所以单独一套、收得慢一档

### ④ 三种背景状态、三种画法（同一天改了四版，这是最终形态）

日视图课程卡在「默认壁纸 / 自定义+模糊开 / 自定义+模糊关」下，阴影、衬底、描边各不相同：

| 场景 | 磨砂 | 阴影 | 衬底 | 描边 |
|---|---|---|---|---|
| 默认壁纸 | 无 | 有 | **有**（实心云母面板） | 无 |
| 自定义 + 模糊开 | 有 | 有 | 无 | 无 |
| 自定义 + 模糊关 | 无 | **无** | 无 | **有**（0.8dp 灰白） |

三条理由：

1. **默认壁纸要衬底**。没壁纸时 `CourseBackground` 在 `bmp ?: return` 那行直接返回，
   `bgBounds` 一直是 null，`frostedBackdrop` 什么都不画 —— 卡片只剩半透明课程色。
   这时必须补一层 `micaPanelColor()` 实心底（浅色纯白、深色 `#171C23`），
   才立得住「云母面板」的定位；只给阴影不衬底，它就是一块半透明色片，跟背景糊在一起。
   衬底要画在**磨砂之后、课程色之前**：两者互斥，不会互相盖死。
2. **模糊关掉时不能挂阴影，改描边**。底半透明 + elevation = 阴影从卡底下透出来，
   在卡片内侧显出一圈暗边（致辉报的「阴影穿帮」）。去掉阴影后轮廓全靠明度差，
   壁纸颜色接近时会「化」进背景，所以补 0.8dp 灰白边（深色白 22% / 浅色黑 10%）。
3. **模糊开着什么都不用补**。磨砂层画满不透明，阴影透不过来，轮廓也清楚。

判断依据用 `LocalHasCustomBackdrop`（显式标记），**不要用 `LocalWallpaperBounds != null`**：
换/删壁纸后 `onGloballyPositioned` 不再回调，旧值会留着；布局完成前它又是 null，两头都不准。

`frostOn` 要同时满足四个条件：有壁纸 + 用户没关格子磨砂 + 位图解码完 + 壁纸位置算出来了 ——
少任何一个 `frostedBackdrop` 都会静默不画，阴影和描边就得跟着换一套。

「正在上」的 1dp 主色描边优先级最高，它跟另两种描边互斥（`when` 的第一个分支）。

**通用规则**：给「底色可能半透明」的卡片加 elevation 阴影之前，先确认底下有不透明层。
半透明 + 阴影 = 阴影从卡片内部透出来，看起来像卡片脏了一圈。

滑块下限 `0.10 → 0.03`：`SessionStore.courseCellOpacity` 的两处 `coerceIn`
加 `BackgroundSheet` 的 `valueRange`，三处要一起改，漏一处就被夹回去。

---

## 二十一、今日标记 + 回到今日按钮（2026-11-19）

**今日描边**：日视图 `DayTab` 今天时画 `border(1.dp, 淡色, G3(8.dp))`，**不补 padding**；
周视图只框星期条那一格（1dp），**不框整列**（700dp 高的细长框会和课程块、长按热区互相压）。

**两次返工，都记下来：**

1. **第一版做成「环」**：`border(1.8dp) + padding(3dp)`，想让环和填充块之间露出间隙。
   结果 `padding` 把内容撑高了 6dp，**一行七格里今天那格明显高出一截**
   （致辉当天就截图指出「当天与前几天没对齐」）。
   `Modifier.border` 本身**不占布局空间**，撑大的是那个 padding —— 想加间隙就一定会改尺寸，
   除非改用 `drawBehind` 自己画（没必要）。
2. **第二版才定**：不要环，就要**淡淡的细框**（致辉原话「类似第 x 周那种上面的就可以」）。
   `1.dp` + `alpha 0.50`，跟顶部周条的选中框一个量级。

**描边颜色分两套**，因为底色不同：

| 状态 | 描边 | 说明 |
|---|---|---|
| 今天 · 未选中 | `primary` @ 50% | 透明底上一圈淡绿 |
| 今天 · 已选中 | `onPrimary` @ 55% | 绿块上一圈淡白，几乎看不出，不刺眼 |

选中态那圈刻意做得几乎看不见：**当前查看的那天本来就靠实心块表达**，
今天再叠一道强标记会打架。淡框的职责是「滑到别的天时还能找到今天在哪」。

周视图所有列统一 `padding(vertical = 4.dp)` —— 只有今天加的话，那格内容会被挤小、整行高度对不齐。

**回到今日**（`ui/components/TodayFab.kt`）：

- 彩色投影 `shadow(elevation, CircleShape, ambient/spot = primary.copy(...))` **要 API 28+**，
  minSdk 26 上退化成黑色阴影。按钮本身仍清楚，只是少了光晕。
- 底距 = `navigationBarsPadding + 98dp`（底栏 gap 14 + 胶囊内容高约 72 + 呼吸 12）。
  **不要用 `LocalFloatingBarInset`(170dp) 定位** —— 它是给滚动内容留的白，含 overscroll，
  拿来放按钮会飘到屏幕中间。
- `AnimatedVisibility(fade + scale)`，`visible = false` 时完全不占位。

**顺带修的真 bug**：日视图原来用外部传的 `currentWeek` 判「今天」。
跨 0 点、或 App 搁置几天后 currentWeek 还是旧值，会把错误的一天标成今天（「正在上」也标错）。
改成自己算 `semester.weekOf(today)`。

**测试注意**：`ScheduleScreen(semester, sessions, currentWeek, viewingWeek, ...)` ——
两个 Int 的顺序是 currentWeek 在前。传反了截图会拍成「今天那周」，按钮不出现。

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
