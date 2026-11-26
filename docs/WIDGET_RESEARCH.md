# 桌面课表小部件（App Widget）技术调研报告

> 适用工程：GZUCM-Pocket（Kotlin + Compose，minSdk 26 / targetSdk 35，`com.gzucm.pocket`）
> 调研时间：2026-11-26
> 范围：技术选型、更新机制、国产 ROM 适配、尺寸布局、风险。**本报告不含代码改动。**
> 证据等级约定：**A** = 官方文档原文；**B** = 官方文档摘要 + 社区/实测二次核实；**C** = 仅社区博客/用户反馈，需自行复测。文中 `[Sx]` 对应第 8 节来源。

---

## 1. 结论先行

### 1.1 选什么

**用传统 `RemoteViews` + XML，不要用 Glance。** 三套静态布局（2x2 / 4x2 / 4x4）在 `onAppWidgetOptionsChanged` 里按实际 dp 分档切换。

### 1.2 为什么（按权重排序）

1. **目标 ROM 里有明确的 Glance 反例。** ColorOS 的 `OplusHansManager` 会冻结 Glance 的异步 SessionWorker（Glance 的渲染是跑在 WorkManager 协程里的），已有同类课程表类 App 因此整体放弃 Glance、退回同步 `RemoteViews` + `goAsync()`，并把这个决策写进了测试用例 [S16]。这是 B 级证据（社区实测 + 与官方机制吻合），但足以否决"在 OPPO 存量用户占大头"的产品选型。
2. **小米的深色模式要求是"静态化"的，和 Glance 的动态取色模型冲突。** 小米官方明确：新版桌面切深色时不再回调 `onAppWidgetOptionsChanged`，宿主直接缓存上一次的 `RemoteViews` 重建，因此**只能靠 XML 里的 `values-night` / `drawable-night` 静态适配，不支持在 RemoteViews 里用代码切深色** [S8]。Glance 的颜色是在组合时解析的，宿主缓存 RemoteViews 后不会重新组合，"立刻跟随深色"这条在 HyperOS 上不可预期。
3. **小米的审核版小部件有 35MB 进程上限。** 走小米小部件中心必须跑在 `:widgetProvider` 独立进程，`dumpsys meminfo` ≤ 35MB，且禁止 fork [S9]。再往里塞 Compose Runtime（Glance）纯属给自己加预算压力；纯 `RemoteViews` + 一个 Room/文件读取，35MB 很宽松。
4. **这个需求本身不需要 Compose 的表达力。** 课表部件是"固定网格 + 文本 + 色块 + 点击"，`LinearLayout/GridLayout/TextView` 全覆盖；用 Glance 也一样要重写一套 Glance 组件（`Box/Column/Row/Text/Image/LazyColumn` 白名单），并不能复用现有 Compose 页面代码（Glance 不走 Compose UI 的 LayoutNode 与绘制管线）[S19]。所谓"复用 Compose"在这里是伪收益。
5. **视觉一致性靠设计规范保证，不靠框架。** 小部件本来就跑在 Launcher 进程、用系统字体渲染，和 App 内 Compose 永远不是同一套管线。选 Glance 也不会让两者更像。

### 1.3 代价（接受它）

| 代价 | 具体表现 | 缓解 |
|---|---|---|
| 布局要手写 XML | 三套 layout + 若干 selector/shape，约 300~500 行资源 | 抽 `WidgetUiModel` 单向映射，三套布局共用同一份数据类 |
| 没有 `@Composable` 状态管理 | 每次更新要自己拼 `RemoteViews` 的 set 调用 | 写一个 `bind(views, model)` 的纯函数，三套布局各一份 |
| 深色要手工维护 | `values-night` 两套颜色 | 见 4.3，优先用 `?attr` + 系统色，减少硬编码 |
| 圆角要自己保证 | 小米桌面不帮你裁圆角（见 4.4） | 背景 drawable 自带 `corners`，不依赖宿主 |

### 1.4 不建议现在就做的事

- **不要为"上小米小部件中心"重构独立进程。** 那是上架审核项，涉及架构拆分（Repository 进程隔离），收益只是"在小部件中心可搜到"。标准 AppWidget 在小米上照样能用，只是入口在"原生组件池"（长按桌面 → 添加小部件 → 全部/安卓原生），不在小米小部件中心 [S8]。
- **不要做 Glance 与 RemoteViews 双实现。** 两套渲染必然视觉漂移，维护成本翻倍，而收益是 0。

---

## 2. 技术选型对比

### 2.1 Glance 现状（2026-11）

| 项 | 值 | 来源 |
|---|---|---|
| 当前稳定版 | **1.2.0**（2026-08-26 发布） | [S1] |
| 上一个稳定版 | 1.1.1（2026-07 仍作为 stable 列出） | [S1] |
| 预发布 | 1.3.0-alpha02（2026-07-01） | [S1] |
| 1.2.0 关键变更 | 性能优化、bug 修复、弃用旧 Wear 库 | [S1] |
| minSdk | 1.2.0-beta01 起默认 **API 23**（项目 minSdk 26，无影响） | [S1] |
| 依赖 | `androidx.glance:glance-appwidget:1.2.0` + `glance-material3` | [S1] |
| 成熟度评价 | **API 已稳定，工程上可用**，但仍是"官方维护的次主流路径"，Google 自己的示例与文档主推 Glance，OEM 兼容性却是按 RemoteViews 校准的 | 推断（基于 [S1][S9][S16]） |

结论：Glance 在 2026 年**不是"实验品"**，版本号可以放心用；问题不在库本身，**在宿主**。

### 2.2 Glance 的能力边界

支持的可组合项（白名单，超出即不可用）：`Box`、`Column`、`Row`、`Spacer`、`Text`、`Button`、`Image`、`LazyColumn`、`Scaffold`，1.1.0 起加了 `LinearProgressIndicator`/`CircularProgressIndicator` [S2][S1]。

映射关系（决定了"能做什么"）：`Box→FrameLayout`、`Column/Row→LinearLayout`、`Text→TextView`、`Image→ImageView`、`LazyColumn→ListView + RemoteViewsService` [S2][S19]。

用不了的东西，本质上和 RemoteViews 一样：

- 无 `RecyclerView`、`ConstraintLayout`、自定义 View；
- 无 `shadow`/`elevation`/`blur`/渐变高级效果（只能背景色、`cornerRadius`、圆角图片）；
- 无动画、无 `animate*AsState` 的真实过渡（只是终值变化）；
- 点击只能是 `Action` → `PendingIntent`（`ActionParameters` 只支持基础类型，传自定义 Parcelable 运行期崩）[S16]；
- 字体只能用 `FontFamily` 资源，不能任意可变字体；
- 文本测量不可控，`maxLines`/省略行为受系统 `TextView` 限制。

**调试体验**：Android Studio Koala Feature Drop（2024.1.2）起支持 `@Preview` 预览 Glance 组件，但需要额外加 `glance-preview` + `glance-appwidget-preview`，且标注 `@OptIn(ExperimentalGlancePreviewApi::class)`，同时必须传 `widthDp/heightDp` [S3]。也就是"能预览，但要额外配置、API 仍是 Experimental"。XML 布局的预览则是 IDE 原生能力，且更接近 Launcher 真实 inflate 结果。

### 2.3 Glance 在国产 ROM 的已知问题

| 问题 | 表现 | 证据 |
|---|---|---|
| ColorOS 冻结异步更新 | Glance 的 `provideGlance` 跑在 WorkManager 协程 / `AppWidgetSession`，被 `OplusHansManager` 冻结后表现为"内容长时间不变、点开 App 才恢复" | B [S16] |
| 小米深色不跟随 | 宿主缓存 RemoteViews 直接重建，代码里切色无效 | A [S8] |
| 圆角靠宿主 | Glance 只能给 `Box` 加 `cornerRadius`，实际是否被裁取决于 Launcher。Pixel Launcher 会把卡片子 View 按自身轮廓裁一遍（掩盖问题），HyperOS 不裁 → 显示直角 | A/B [S15][S16] |
| 无 RemoteViews 后处理钩子 | `GlanceAppWidget.update()` 是 final，无法在交给系统前改 RemoteViews 根节点（例如补圆角 Mask） | C [S16] |
| 进程内存 | 组合 + Compose Runtime 常驻，小米 35MB 预算下更紧 | A（规范）+ 推断 |

### 2.4 RemoteViews 在 Android 12+ 的真实能力

| 能力 | API | 说明 | 来源 |
|---|---|---|---|
| `targetCellWidth` / `targetCellHeight` | 31 | 按 Launcher 网格单元格声明目标尺寸；定义后优先于 `minWidth/minHeight` | A [S4] |
| `maxResizeWidth` / `maxResizeHeight` | 31 | 用户可拉伸上限 | A [S4] |
| `previewLayout` | 31 | 用真实布局做选择器预览（替代静态 png） | A [S4] |
| 响应式布局 `RemoteViews(Map<SizeF, RemoteViews>)` | 31 | 一次下发多尺寸布局，系统按需挑 | A [S4] |
| 精确尺寸回调 | 31 | `OPTION_APPWIDGET_SIZES` 拿到 `List<SizeF>` 自行出图 | A [S4] |
| `setViewLayoutMargin(viewId, type, value, units)` | 31 | 运行时改 margin（`MARGIN_LEFT` 等） | A [S6] |
| **`setViewOutlinePreferredRadius(...)`** | 31 | ⚠️ 纠正一个常见误传：Android 12 **没有** `setCornerRadius`，圆角相关是 `setViewOutlinePreferredRadius` / `...Attr` / `...Dimen` | A [S6] |
| `setColorStateList` / `setColorStateListAttr` | 31 | 动态色，`Attr` 版本可按主题属性解析 | A [S6] |
| `setViewLayoutWidth/Height`、`setIntDimen/FloatDimen` | 31 | 运行时改尺寸 | A [S6] |
| 动态配色 | 31 | 根布局用 `Theme.DeviceDefault.DayNight` 或 `Theme.Material3.DynamicColors.DayNight`，子 View 用 `?attr/primary` 等；另可直接用 `@android:color/system_*`（API 31+） | A [S7] |
| `Chronometer` 倒计时 | 1 | 支持在 RemoteViews 中使用，**由宿主进程自己走秒，不需要 App 刷新** | A（RemoteViews 支持列表） |

关键现实差距（文档 vs 实际）：

- AOSP 要求 Launcher 支持 `OPTION_APPWIDGET_SIZES` 精确尺寸；**小米官方只配三档（2x2 / 4x2 / 4x4），并明确 `minHeight` 只用于算占几格，与最终显示高度无关**（最终高度 = `ceil(minHeight/cellHeight)*cellHeight - paddings`）[S9][S8]。所以"精确尺寸"路径在国内不可依赖。
- `targetCellWidth` 在 ColorOS 5×6 网格上算错格数，导致部件被"压扁"；实际项目（BeeCount）的修复方式是**直接删掉 `targetCellWidth/Height`，回退 `minWidth/minHeight`，并把 `minWidth` 从 250dp 提到 320dp** [S14]。这是 2026-05 的实测结论，非常值得抄。

### 2.5 结论

**RemoteViews。** 判据不是"Glance 不好"，而是：
- 我们的形态是固定三档尺寸 + 低刷新 + 强国产 ROM 覆盖 → RemoteViews 的确定性 > Glance 的开发效率；
- 唯一能说服人用 Glance 的场景是"内容复杂、需要频繁局部重绘、且用户以 Pixel/三星为主"，我们三条都不满足。

如果未来要做"可滚动的完整周课表 + 复杂交互"，再评估 Glance 的 `LazyColumn`（本质是 `ListView` + `RemoteViewsService`，这条 RemoteViews 也能做，只是样板代码更多）。

---

## 3. 更新机制建议

### 3.1 `updatePeriodMillis` 的真实行为

| 事实 | 说明 | 来源 |
|---|---|---|
| 最小 30 分钟 | 小于 30 分钟的请求被忽略，系统仍按 ≥30 分钟发 | A [S5] |
| `0` = 关闭周期更新 | 官方推荐的"自定义节奏"入口 | A [S5] |
| 不是精确调度 | 官方原文：不保证按此周期投递；Doze、App Standby、Launcher 调度、厂商省电都会拉长间隔 | A [S5] |
| 回调入口 | 系统发 `APPWIDGET_UPDATE` 广播 → `AppWidgetProvider.onUpdate()` → 主线程，**10 秒内必须返回**，否则 ANR | A [S5] |
| 小米的额外改动 | 只有"声明了 `miuiWidget=true` 并通过审核"的小部件才会**去掉系统定时刷新**，改成"用户滑到该屏才触发一次"（曝光刷新，需 `miuiWidgetRefresh=exposure` + `miuiWidgetRefreshMinInterval`，最短 10 秒）；未上架/未声明的小部件仍走原生机制 | A [S9][S16] |

> 注意区分：**"标准 AppWidget"在小米上不会被去掉定时刷新**。只有你主动适配小米规范（`miuiWidget=true`）才会切换刷新模型。这是一个"适配即失去原生能力"的陷阱。

### 3.2 WorkManager vs AlarmManager

| 维度 | WorkManager（Periodic） | AlarmManager（精确） |
|---|---|---|
| 精度 | 不保证时刻，只保证"大致在窗口内"；最小周期 15 分钟 | `setExactAndAllowWhileIdle` 可精确到分钟；Doze 下仍有 ≥15 分钟最小间隔（同一 App） |
| 国产 ROM 存活 | 受 App Standby 分桶 + 厂商省电双重限制；小米实测 15 分钟周期任务实际 2~6 小时（C 级用户反馈）[S17] | 同样被厂商省电层拦截；EMUI 有 60 分钟级强杀 [S17] |
| 是否需要权限 | 不需要 | Android 12+ 需要 `SCHEDULE_EXACT_ALARM`（或 `USE_EXACT_ALARM`，后者仅闹钟/日历类可声明，课程表**不属于**可声明类别，商店会拒） |
| 重启后 | `PersistedWork` 自动恢复 | 需要 `BOOT_COMPLETED` 重新注册 |
| 适合 | 日更、可延迟、失败重试 | 闹钟、用药提醒、开课前 5 分钟提醒 |
| 结论 | **课表日更用它** | **不要用**（既拿不到权限，也会被 ROM 杀） |

**关键认知**：`WorkManager` 不比 `updatePeriodMillis` "更硬"，两者都受 App Standby 与厂商省电约束 [S16]。差别只是 WorkManager 有约束/重试/持久化，`updatePeriodMillis` 由系统免费托管。**所以不要指望任何后台机制能"准点"**，要靠"渲染设计"让不准点也不难看。

### 3.3 推荐策略（分层，按可靠性排序）

| 层 | 触发 | 做什么 | 可靠性 |
|---|---|---|---|
| L1 | 用户点开 App（`MainActivity.onResume` / 数据变更后） | 立刻 `AppWidgetManager.updateAppWidget()` 全量刷新 | ★★★★★ |
| L2 | 部件内"刷新"图标（`PendingIntent` → BroadcastReceiver → `goAsync` + 重绘） | 同步重绘 + 更新"数据时间"角标 | ★★★★★ |
| L3 | `WorkManager` 每日一次（`PeriodicWorkRequest(24h, flex 1h)`，initialDelay 到次日 00:05） | 读本地缓存，重算"今日/明日课表" | ★★★☆（可能延迟数小时） |
| L4 | `updatePeriodMillis=1800000`（30 分钟）作为兜底 | 只在"下一节课"倒计时/状态需要变时有用；不联网 | ★★（Doze 下会停） |
| L5 | `BOOT_COMPLETED` / `MY_PACKAGE_REPLACED` / 时区变更 | 重建所有实例（`updateAll`） | ★★★★（注意 Android 8+ 隐式广播限制，这几个在豁免清单内或可用 `RECEIVE_BOOT_COMPLETED`） |

**建议组合：L1 + L2 + L3 + L5，`updatePeriodMillis` 设 0（关掉）。**

理由：
- 课表数据"一天变一次"，且变化时刻是 00:00；用户在 00:00 看部件的概率极低，L3 延迟到早上 6 点执行也无感；
- 周次切换（单双周）是真正的"易错点"，必须在渲染时按"当前时间"判断，而不是把"本周/下周"写死在数据里（见 3.5）；
- L4 的 30 分钟兜底会持续唤醒进程，对小米/华为用户是耗电投诉源，不如关掉。若小尺寸要显示"下一节课倒计时"，用 `Chronometer` 自走，不需要 L4。

> 若产品坚持要"过 0 点自动切到新一天"，可加一个 **一次性** `OneTimeWorkRequest`（`initialDelay` = 距次日 00:00 + 2min），每次刷新成功后重新排下一个。比周期任务更准，且只有一条任务在队列里（`enqueueUniqueWork(REPLACE)`）。

### 3.4 冷启动与 10 秒预算

系统发 `APPWIDGET_UPDATE` 时，如果进程没起，会**冷启动你的进程**，这段时间算在广播超时里 [S5][S19]。

实测经验（A 级文档 + 工程共识）：
- 广播接收器默认 10 秒上限（Android 14 起前台广播 10~20s、后台 60~120s，取决于 CPU 饥饿程度）[S19]；
- **冷启动也计时**，`goAsync()` 不豁免超时，必须及时 `PendingResult.finish()` [S19]；
- 因此：**绝对不要在 `onUpdate` 里联网、解析大 JSON、初始化 DI 全图、初始化 Compose**。

本项目的正确姿势：

1. App 侧写入时**预计算并落盘**一份"部件快照"（`DataStore`/小 JSON/单独 Room 表，字段只保留渲染需要的：今日课程列表、下一节课、周次、更新时间戳）。
2. `onUpdate` 里只做：读快照（同步、<50ms）→ 构造 3 个 `RemoteViews` → `updateAppWidget` → `finish()`。
3. 快照缺失（首次安装/清数据）→ 立刻显示"点击设置课表"的引导布局，**不要显示空白**（小米官方也要求"清数据时给默认视图" [S16]）。
4. 任何"需要计算"的逻辑放到 App 进程或 Worker，不放 `onUpdate`。

预估耗时：冷启动 300~800ms（中端机）+ 读文件 20ms + RemoteViews 构造 30ms，远在预算内；如果引入 Compose/Glance，冷启动会多 200~400ms（Compose Runtime 加载），这就是第 1 节选型的现实收益。

### 3.5 让"不刷新"不致命的渲染设计

这是本报告最重要的工程建议：**把"新鲜度"从刷新机制里挪到渲染逻辑里。**

| 手段 | 做法 | 效果 |
|---|---|---|
| 存全量、渲染时切片 | 快照里存"整学期课表 + 当前周次 + 学期起始日"，渲染时按 `now` 算今天/明天 | 即使快照是 3 天前的，只要周次对，显示仍正确 |
| 大尺寸显示"整周" | 4x4 画周一到周日 7 行，高亮"今天"这一列 | 内容天然稳定，只有高亮会错一天 |
| 小尺寸显示"下一节课" | 2x2 显示 `08:30 高等数学 / 教学楼的 302`，配 `Chronometer` 倒计时 | 需要分钟级新鲜度，但 `Chronometer` 由宿主走秒，不耗 App |
| 显式"数据时间" | 角标显示 `更新于 11-26 07:30` 或"离线"点 | 用户能理解，减少"坏了"的投诉 |
| 空态/异常态 | 无课 → "今天没课，好好休息"；读取失败 → "点我打开 App" | 避免空白 = 避免 1 星评价 |

---

## 4. 各家 ROM 适配现状

### 4.1 总表

| 厂商 / 系统 | 标准 AppWidget 支持度 | 需要注意的点 | 是否需额外适配 |
|---|---|---|---|
| **小米 HyperOS 1/2/3**（Android 14/15/16） | ✅ 支持（入口在"原生组件池"，不在小部件中心） | ① 上小米小部件中心需审核 + `:widgetProvider` 独立进程 ≤35MB + 禁 fork [S9]；② 适配后系统**去掉定时刷新**改曝光刷新 [S9]；③ 深色只能 XML 静态适配，代码切色无效 [S8]；④ 桌面**不裁圆角**，自绘 [S15]；⑤ 官方只认 2x2/4x2/4x4 三档，`minHeight` 只算格数 [S9]；⑥ 小部件中心收录后，原生池里同名部件被隐藏 [S8] | 不上的话：**否**。要上中心：是（架构级） |
| **华为 EMUI 15 / HarmonyOS 4.x** | ✅ 支持（APK + AppWidget 走 AOSP 行为） | ① 入口路径是"双指捏合 → 服务卡片 → 下滑到窗口小工具" [S12]；②"服务卡片/万能卡片"是鸿蒙 ArkTS 生态，**与 AppWidget 不互通**，APK 拿不到 [S16]；③"万象小组件"是主题引擎能力，不是给三方 APK 的 API [S16]；④ 深色无单应用开关，跟随 App 自身策略 [S16] | 否（仅需保证入口文案别写错） |
| **HarmonyOS NEXT（5/6/7）** | ❌ **不能跑 Android APK** | 彻底移除 AOSP 代码与 ART，只支持 `.hap`；第三方容器（卓易通/出境易）可虚拟化跑 APK，但**容器内的小部件行为不确定**（大概率不注册到桌面，需实测） [S11][S13] | 无法适配。只能单独做鸿蒙原生工程（ArkTS + Form Kit），属另一条产品线 |
| **荣耀 MagicOS 9/10** | ✅ 支持（原生路径） | ① 实测有"配置 Activity 被丢弃导致添加回滚"的问题，**建议不声明 `android:configure`**，改用 `reconfigurable` + App 内编辑 [S16]；② 深色规范禁纯黑 `#262626` [S16]；③ 锁屏小组件是独立推送通道，与桌面部件不是一回事 | 是（小项：去掉 configure、避纯黑） |
| **OPPO ColorOS 15/16** | ✅ 支持 | ① `targetCellWidth/Height` 在 5×6 网格算错格数 → 部件被压扁，实测修复是**删掉这两个属性 + `minWidth` 给到 320dp** [S14]；② Glance 异步 Session 会被 `OplusHansManager` 冻结 [S16]；③ 主题组件（物光引擎）是另一条需审核的通道，APK 不可达 [S16]；④ 熄屏即杀后台较激进 [S17] | 是（尺寸声明 + 避开 Glance） |
| **vivo OriginOS 5/6** | ✅ 支持（标准 AppWidget 可用） | ① "原子组件"需接 vivo SDK + 平台审核；**未接入时 `requestPinAppWidget` 完全无效**（静默失败）[S16]；② 单次 RemoteViews 的 Bitmap 总量上限 = 1.5× 屏幕内存（AOSP 硬校验，超了抛 `IllegalArgumentException`）[S16]；③ 挂件进程内存压力更紧 [S16]；④ 大图标模式/变形器会改变实际 cell dp，必须读 `options` 而非 `targetCell` [S16] | 是（pin 入口要有兜底引导；控制位图大小） |
| **三星 One UI 7/8** | ✅ 完全标准（参照组） | ① One UI 7 移除了 4x5/5x5 网格选项；② 三方部件不自动加标签；③ DeX 对 "widget-only 应用" 不支持，manifest 别只声明 widget [S16]；④ "未使用应用休眠"（默认开启）可能 3 天后冻结后台 [S17] | 基本否 |
| **Pixel / AOSP** | ✅ 最标准（参照组） | 会主动给卡片裁圆角 → 容易掩盖"你没画圆角"的问题，别在 Pixel 上验收圆角 [S15] | 否 |

### 4.2 三个必须点明的事实

**(1) 小米的"两套小部件"是两套东西。**
- 标准 AppWidget：装上就能用，入口是"添加小部件 → 原生/全部"，无审核、无独立进程、定时刷新照旧。
- 小米小部件：需邮件申请 + 设计/技术规范审核 + 上传小部件开放平台，通过后进"小部件中心"，同时**原生池里的同名部件被隐藏** [S8]。适配后刷新模型变成"曝光刷新"（默认不刷新，需声明 10s+ 间隔）[S9]。
- 结论：**先做标准 AppWidget**。等 MIUI/HyperOS 用户量证明需要曝光位，再走审核通道，代码侧主要是加 meta-data（可提前埋，不影响未上架行为）[S16]。

**(2) 华为必须区分"能装 APK"和"不能装 APK"。**
- EMUI / HarmonyOS 2~4.x：保留 AOSP 兼容层，APK 正常安装，AppWidget 走 AOSP 行为 [S11]。
- HarmonyOS NEXT（鸿蒙 5/6）：无 AOSP、无 ART，只认 `.hap` [S11][S13]。这不是"适配问题"，是"装机问题"——这部分用户**装不了这个 App**，小部件讨论无意义。
- 产品含义：如果华为用户在目标群体里占比高，需要单独评估"鸿蒙原生版"，而不是在小部件层面纠结。

**(3) ColorOS 的格数 bug 是"声明方式"问题，不是渲染问题。**
`targetCellWidth="4"` 在 5 列桌面上被算成错误格数 → 视觉压缩。修复是退回 `minWidth` 让 Launcher 自己算，并把 `minWidth` 提到 320dp 以覆盖 5 列 [S14]。这条对"要一份配置跑所有 ROM"的诉求是致命的：**宁可放弃 `targetCellWidth` 的精确性，也要保 `minWidth` 的兼容性**。

### 4.3 深色模式与动态配色

| 方案 | 适用 | 说明 |
|---|---|---|
| `values-night/colors.xml` + `drawable-night` | **首选，全 ROM 稳** | 小米明确要求只能静态适配 [S8]；宿主缓存 RemoteViews 也不会帮你重算 |
| 根布局 `android:theme="@android:style/Theme.DeviceDefault.DayNight"` + `?attr/colorBackground` 等 | API 31+ 动态色 | 官方推荐两种做法之一 [S7]；需确认 OEM 主题确实提供了这些 attr，国内 ROM 多数有兜底 |
| `@android:color/system_neutral1_*` / `system_accent1_*` | API 31+，仅 Android 原生调色板 | 需要 `values-v31` 单独放；在 OEM 深度定制上取到的是 OEM 调色板，不保证好看 |
| 代码 `setColorStateList` / `setTextColor` 切深色 | ❌ 不推荐 | 小米上无效（宿主缓存），且切换时机不可控 [S8] |

**结论：`values-night` 打底 + `values-v31` 用系统色做增强，绝不在代码里判断 `isSystemInDarkTheme()` 去切色。**

补充：`ColorProvider(day, night)`（Glance）在 API 31+ 由框架在渲染时解析，理论上能即时切换 [S18]，但**小米的"缓存 RemoteViews 重建"会绕过重新解析**，实测是否跟随取决于桌面版本 —— 这是"不确定"项，也是不选 Glance 的理由之一。

### 4.4 圆角与留白

| 场景 | 数值/做法 | 来源 |
|---|---|---|
| Android 12+ 系统圆角半径 | 用 `@android:dimen/system_app_widget_background_radius`（及 `..._inner_radius`） | A（系统 dimen） |
| 小米小部件规范 | 2K 屏（`values-sw411dp`）**14.48dp**；其他 **12.67dp**；预览图圆角统一 38px | A [S10][S8] |
| 小米桌面实际行为 | 不替三方部件裁圆角 → 背景 drawable 必须自带 `corners` | A/B [S15] |
| 内边距 | Android 12+ 系统会自动加外边距；小米按"格子 − paddings"计算，实际可用高度比 `minHeight` 小 | A [S9] |
| 建议 | 背景用 `shape` + `corners radius=16dp`（跟随系统 dimen），内边距 12~16dp，内容不要贴边（各家裁切不一致） | 推断 + [S10] |

### 4.5 字体缩放（易漏）

系统字体放大到 200% 时，`RemoteViews` 里的 `TextView` 会同步放大，固定高度的布局会截断。RemoteViews 侧没有 `autoSizeTextType` 的可靠支持（`TextView` 的 autosize 在 RemoteViews 中不保证生效）。建议：`maxLines` + `ellipsize="end"`，关键信息（时间/地点）单独一行，课程名可省略。

---

## 5. 尺寸与布局建议

### 5.1 能不能"一个部件自适应多尺寸"

**能做到"一个 provider + 多套布局"，做不到"一套布局自适应"。**

- Android 12+ 提供 `RemoteViews(Map<SizeF, RemoteViews>)` 响应式布局，一次下发多套，系统按当前尺寸挑 [S4]；
- 但国内 Launcher 对 `OPTION_APPWIDGET_SIZES` 支持参差（小米只给三档并明确按格数算）[S9][S16]；
- 实践结论：**用 `onAppWidgetOptionsChanged` 读 `OPTION_APPWIDGET_MIN_WIDTH/HEIGHT`（dp），按阈值分档选布局**，不要依赖 `SizeF` 映射表。

### 5.2 具体声明建议

| 档位 | `targetCellWidth/Height` | `minWidth/minHeight` | 说明 |
|---|---|---|---|
| 小 2x2 | 2 / 2 | 110 / 110 dp | 公式 `70n-30` |
| 中 4x2 | 4 / 2 | 250 / 110 dp（ColorOS 建议 320 / 110） | 5 列桌面要给 320dp |
| 大 4x4 | 4 / 4 | 250 / 250 dp | 小米规范给 300/250 |

**建议实际配置**：`resizeMode="horizontal|vertical"`，`minResizeWidth=110dp`、`minResizeHeight=110dp`，`maxResizeWidth/Height` 不设（让系统放开）。`targetCell*` 建议**按 [S14] 的经验先不加**，只留 `minWidth/minHeight` —— 小米/华为显示正常，ColorOS 不压缩。这是"牺牲精确性换兼容性"的取舍，先按此上线，后续按真机数据再调。

> 不确定项：小米对 `targetCellWidth` 的尊重程度没有官方明确说法（只说了三档尺寸与格数计算），标为待实测。

### 5.3 2x2 放不下课程名时，行业怎么做

没有权威规范，以下是课程表/日程类 App 的通行做法（**属观察推断，非文档结论**）：

1. **只显示"下一节课"**：`08:30` + 课程名（`maxLines=1`，省略）+ 地点，右上角小刷新点。信息密度最高，最实用。
2. **显示"今日 N 节 + 首/末节时间"**：`今天 4 节 · 08:30-16:50`，点开看详情。适合"只想确认今天忙不忙"。
3. **显示"当前/下一节 + 倒计时"**：`距下课 23:15`，用 `Chronometer` 自走。状态感最强，但需要处理"课间/放学后"的空态。
4. **纯图标 + 数字角标**：`📅 3`。最省地方，信息量最低，不推荐做主方案。

**推荐**：2x2 用方案 1（下一节课）+ 右上角日期；无课时切方案 2 的语义（"今天没课"）。4x2 用"今日课程横排 3~4 条"，4x4 用"整周 7 行 × 5 列网格 + 今天高亮"。

### 5.4 尺寸档位与内容映射

| 尺寸 | 内容 | 刷新需求 | 点击目标 |
|---|---|---|---|
| 2x2 | 下一节课（时间/名称/地点）+ 日期 | 中（跨课后要变） | 打开 App 今日视图 |
| 4x2 | 今日课程列表（最多 4 条）+ "第 N 周" | 低 | 同左 |
| 4x4 | 整周网格（7 天 × 节次）+ 今天高亮 + 周次 | 低（只有高亮变） | 同左 |

---

## 6. 风险清单（按严重度）

| # | 等级 | 风险 | 触发条件 | 影响 | 对策 | 证据 |
|---|---|---|---|---|---|---|
| 1 | **P0** | 后台被杀 → 部件长期停在旧数据 | 用户开启省电限制/自启动被禁；Doze；厂商 60 分钟强杀 | 用户看到昨天/上周的课，最严重的信任崩塌 | 见 3.3 分层 + 3.5 渲染设计；首次进入 App 引导"允许后台运行"（说明用途，不静默跳转） | A/B [S5][S17] |
| 2 | **P0** | 锁定屏幕后不刷新 / 隔夜不更新 | ColorOS "熄屏即杀"、EMUI `HwPFWService` 60 分钟、三星"未使用应用休眠" | 早八用户早上看到昨天 | 同上；把"日期"渲染成相对值（"今天"），至少文案不错 | B [S17] |
| 3 | **P1** | 小米深色模式不跟随 | 用户在深色下切换主题；部件是浅色底 | 白底黑字糊在深色桌面上 | 只用 `values-night` 静态资源，不在代码切色；上架前在 HyperOS 深色下验收 | A [S8] |
| 4 | **P1** | 圆角缺失/直角黑边 | HyperOS 不裁圆角；自绘背景没写 `corners` | 视觉廉价，用户截图吐槽 | 背景 drawable 自带圆角 + 跟随 `system_app_widget_background_radius` | A/B [S15][S10] |
| 5 | **P1** | ColorOS 上部件被压扁 | 声明了 `targetCellWidth=4` 且桌面为 5×6 网格 | 内容裁切、文字溢出 | 去掉 `targetCellWidth/Height`，`minWidth` 给 320dp | B [S14] |
| 6 | **P1** | Glance 方案在 ColorOS 上更新被冻结 | 若选 Glance | 点开 App 才恢复 | 不选 Glance（本报告结论） | B [S16] |
| 7 | **P1** | 点击无响应/延迟 | `PendingIntent` 用了隐式 Intent、缺 `FLAG_IMMUTABLE`、`requestCode` 冲突；ROM 后台限制拉起 Activity | 用户以为部件坏了 | 显式 Intent + `FLAG_IMMUTABLE` + 唯一 `requestCode`；点击后先本地乐观更新（改文案"正在打开…"），再 `startActivity` | A + 推断 |
| 8 | **P2** | 冷启动超时/首屏空白 | `onUpdate` 里做 IO/初始化 | ANR 或被系统杀，部件空白 | 预计算快照 + 只读小文件 + `goAsync` + 尽快 `finish()` | A [S5][S19] |
| 9 | **P2** | Compose 与小部件视觉不一致 | 两套渲染管线、字体/间距/圆角各自实现 | 用户觉得"不像一个 App" | 建立设计 token（色/圆角/间距）单一来源，两边都从 token 取值；接受"不可能完全一致" | A（机制）+ 推断 |
| 10 | **P2** | 位图超限崩溃 | 大尺寸用整张 Bitmap 背景；vivo 上限 1.5× 屏幕内存 | `IllegalArgumentException`，部件不显示 | 用 shape/纯色背景，不用大图；必须用图时按真实 dp 渲染并压缩 | B [S16] |
| 11 | **P2** | `requestPinAppWidget` 静默失败 | vivo 未接原子组件 SDK；小米不弹确认框 | "添加到桌面"按钮点了没反应 | 提供图文引导（长按桌面 → 添加小部件 → 找到本 App），不承诺一键添加 | B [S16] |
| 12 | **P2** | 字体放大 200% 截断 | 系统字号最大 | 课程名/地点被裁 | `maxLines` + `ellipsize`，关键行独立 | 推断 |
| 13 | **P3** | 多实例互相覆盖 | 用户加两个部件，`updateAll` 用同一份 RemoteViews | 正常（内容相同）但状态错乱 | 每个 `appWidgetId` 单独构造；`onDeleted` 清理 | A |
| 14 | **P3** | 升级后部件消失 | receiver 类名改名/移除；小米明确禁止改 | 用户升级后部件没了 | 类名冻结，加 `@Keep`；`onRestored` 处理 ID 迁移 | A [S9][S16] |
| 15 | **P3** | 鸿蒙 NEXT 用户装不了 | 系统限制 | 差评"华为装不上" | 商店描述注明支持范围；评估鸿蒙原生版 | A [S11][S13] |

### 6.1 三个"最容易翻车但最容易被忽略"的点

1. **把"今天"写进数据里**。快照存的是 `"今天 4 节课"` 这种字符串，一旦不刷新就永久错。必须存"整学期课表 + 周次"，渲染时算。
2. **在 Pixel 上验收圆角**。Pixel Launcher 会替你裁，HyperOS 不会。验收机必须是小米/OPPO 真机。
3. **`onUpdate` 里 `runBlocking` 读数据库**。Room 首次打开要建表/迁移，冷启动 2~3 秒，叠加广播超时 = 偶发 ANR。必须走"预写快照"。

---

## 7. 落地建议（不改代码，仅方案）

1. **阶段一（1~2 天）**：单个 provider + 三套 XML，`updatePeriodMillis=0`，L1 + L2 + L5 刷新，`values-night` 静态深色，自绘圆角。只在小米/OPPO/vivo 真机验收。
2. **阶段二**：加 `WorkManager` 日更（一次性 + 唯一任务链），加"数据时间"角标，加空态/引导态。
3. **阶段三（可选）**：若要小米小部件中心曝光位，再评估独立进程拆分 + 审核；代码侧提前埋 `miuiWidget*` meta-data 无害（未上架时走标准路径）[S16]。
4. **不建议**：Glance 迁移、`targetCellWidth` 精确布局、`AlarmManager` 精确定时、`android:configure` 配置页（荣耀实测会被丢弃）[S16]。

---

## 8. 参考来源

**官方文档（A）**

1. [S1] Glance 版本页 — https://developer.android.com/jetpack/androidx/releases/glance
2. [S2] 使用 Glance 构建界面（组件白名单/映射） — https://developer.android.com/develop/ui/compose/glance/build-ui
3. [S3] Android Studio Koala 功能更新（Glance 预览） — https://developer.android.google.cn/r/studio-ui/usb-check
4. [S4] 提供灵活的 widget 布局（targetCell / 响应式 / 精确布局） — https://developer.android.com/develop/ui/views/appwidgets/layouts
5. [S5] 创建高级 widget（updatePeriodMillis / 30 分钟 / 10 秒 / WorkManager） — https://developer.android.com/develop/ui/views/appwidgets/advanced
6. [S6] RemoteViews API 31 变更清单（setViewLayoutMargin / setViewOutlinePreferredRadius / setColorStateList） — https://developer.android.google.cn/sdk/api_diff/31/changes/android.widget.RemoteViews
7. [S7] 改进 widget（动态配色 / Theme.DeviceDefault.DayNight） — https://developer.android.com/develop/ui/views/appwidgets/enhance
8. [S8] 小米小部件适配常见问题 Q&A（圆角 12.67/14.48dp、深色静态、原生池隐藏、minHeight 只算格数） — https://dev.mi.com/xiaomihyperos/documentation/detail?pId=1591
9. [S9] 小米小部件技术规范与系统能力说明（独立进程 35M、去定时刷新、三档尺寸） — https://dev.mi.com/xiaomihyperos/documentation/detail?pId=1584
10. [S10] 小米 HyperOS 小部件设计规范 — https://dev.mi.com/xiaomihyperos/documentation/detail?pId=1664
11. [S11] 鸿蒙系统与 AOSP 的关系（华为开发者博客，NEXT 无 AOSP） — https://developer.huawei.com/consumer/cn/blog/topic/03220729367578049
12. [S12] 华为：使用服务卡片（入口路径，EMUI 15 / HarmonyOS 4.3） — https://consumer.huawei.com/ca/support/content/en-us16066408

**二手核实 / 实测（B/C，需自行复测）**

13. [S13] 鸿蒙操作系统 5（HarmonyOS NEXT 仅 HAP，卓易通为第三方容器） — https://zh.wikipedia.org/wiki/鸿蒙操作系统5
14. [S14] BeeCount PR #307：ColorOS 5×6 网格下移除 targetCellWidth/Height，minWidth 250→320dp（2026-05） — https://github.com/TNT-Likely/BeeCount/pull/307
15. [S15] capod issue #736：HyperOS 3.0 部件不画圆角，仅配置预览有圆角（2026-10） — https://github.com/d4rken-org/capod/issues/736
16. [S16] Sleepy `widget-vendor-specs` 厂商规范落盘（小米/华为/荣耀/OPPO/vivo/三星，含证据分级与实测） — https://github.com/lingion/sleepy/tree/main/docs/vendors/widget-vendor-specs
17. [S17] DontKillMyApp 各厂商后台存活实测汇总 — https://dontkillmyapp.com/
18. [S18] StackOverflow：Glance 深色模式取色（ColorProvider / API 31 起框架解析） — https://stackoverflow.com/questions/70378448
19. [S19] App Widget 更新性能：RemoteViews IPC 与 Glance 渲染（广播超时、Glance 成本模型） — https://wiki.androidperformance.com/part5-app/ch22-rendering-practice/19-app-widget-performance.html
20. [S20] 小米澎湃 OS 小组件不刷新的用户侧排查（后台冻结/桌面缓存，C 级） — https://www.toutiao.com/a7670106417885643308

**明确标注的不确定项**

- HarmonyOS NEXT 的第三方容器（卓易通）内 AppWidget 是否可用 —— 未找到可靠资料，需真机验证。
- 小米对 `targetCellWidth/Height` 的实际尊重程度 —— 官方只说明三档与格数算法，未明确 targetCell 行为。
- HyperOS 各版本"缓存 RemoteViews 重建"对 `ColorProvider` 的影响 —— 官方只说了静态适配，机制层面推断为"不会重新组合"。
- ColorOS `OplusHansManager` 冻结 Glance 的具体版本范围 —— 来源为同类项目实测记录，未覆盖全版本。
- vivo/OPPO 是否对第三方部件做圆角二次裁切 —— 无官方规范，需真机比对截图。
