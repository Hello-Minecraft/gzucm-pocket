# 日程提醒设计方案

> 状态：**已实现**（2026-11-15 定稿，结论见 §12）
> 需求：给自建日程加提醒；设置入口放在「新建 / 编辑日程」面板里。

## 0. 结论先行

- **不动 `ClassReminder` 一行**。新建 `core/AgendaReminder.kt`，用自己的 Receiver、自己的 SharedPreferences 文件、自己的 requestCode 段。
  课程提醒是全 App 最脆的一环（v1.3.4 刚把「排 3 天 / 断签」修好），这一版不重构它。
- 排程骨架（7 天窗口 + 触发后续排 + 12h WorkManager 兜底 + 开机/改时间重排）**照抄**课程那套，因为那套是踩出来的，不是设计出来的。
- 复用 `Agenda.remindMinutesBefore`（模型里早就有这个字段，v1.3.5 只是没做 UI）。
- 复用 `AgendaLogic.expand()` 展开每周重复 → 提醒排程天然处理「每周」和「学期外」。
- 表单里加一行「提醒」，点开是一排胶囊选项（不提醒 / 准时 / 5 / 10 / 15 / 30 / 60 分钟），样式直接抄同文件里的 `OverlapSwitcher`。

## 1. 需求边界

**做：**

| 项 | 说明 |
|---|---|
| 每条日程独立设置 | 存 `Agenda.remindMinutesBefore`，-1 = 不提醒 |
| 单次 / 每周重复都生效 | 每周的每个实例各排一条 |
| 提前量 | **多选**：不提醒 / 准时(0) / 5 / 10 / 15 / 30 / 60 分钟 / 提前 1 天，可同时选多个 |
| 通知点击 | 打开 App（一期）；直达那条日程（二期，见 §8） |
| 改 / 删日程 | 立即重排，旧闹钟必须清干净 |

**不做（这一版）：**

- 全局「日程提醒」总开关（没设提醒的日程本来就不响，开关是多余的）
- 自定义提醒铃声 / 震动模式（跟频道默认 HIGH）
- 通知里的「稍后提醒」按钮（二期候选）
- 按发生实例单独设置（模型是「规则级」，单天例外要动数据结构）
- 考试提醒（考试是教务推的，`AgendaOccurrence.sourceId` 以 `exam:` 开头，天然可以以后接，但这一版不排）

## 2. 现有零件盘点（能不能直接复用）

| 现有代码 | 位置 | 复用方式 |
|---|---|---|
| `Agenda.remindMinutesBefore` | `core/Agenda.kt:48` | **已有字段**，-1 默认。不用改模型，不用数据迁移 |
| `AgendaLogic.expand(semester, rules)` | `core/Agenda.kt:110` | **直接调**。拿到未来实例（含每周重复展开、学期外过滤） |
| `AgendaStore.load()` | `core/AgendaStore.kt:23` | **直接调**。`rescheduleFromCache` 用它读规则 |
| `SessionStore.cachedSemester` + `Net.json` | `core/Net.kt` | **照抄** `ClassReminder.rescheduleFromCache` 的解析方式（`AgendaReminder` 也需要学期） |
| `ClassReminder.Mode` | `core/ClassReminder.kt:306` | 公开枚举，可直接引用（`ALARM_CLOCK / EXACT / INEXACT`），日志口径一致 |
| `ClassReminder.canExact(ctx)` | `:314` | **可复用**（public），判断 Android 12+ 精确闹钟授权 |
| `ClassReminder.place()` | `:327` | ⚠️ private，18 行。一期**复制**，二期抽 `AlarmSupport`（见 §9） |
| `ClassReminder.cancelScheduled()` + `loadCodes/saveCodes` | `:467-493` | ⚠️ 模式复制，但**必须换独立 prefs 文件**，否则两边互相清 |
| `ReminderRefreshWorker` | `:510` | ⚠️ 想复用要改它的生命周期（它的 cancel 挂在课程开关上）。一期**新建一个 worker** |
| `BootReceiver.HANDLED` | `core/BootReceiver.kt:46` | **加一行调用**即可，action 白名单不用动 |
| `AgendaLogic.minutesOfUnit(unit)` | `core/Agenda.kt:88` | 按节次填的日程也能算准开始分钟（`startMinute` 已经是准的，其实用不上） |
| `AgendaSheet` 的 `FieldRow` / `WheelColumn` / 胶囊样式 | `ui/schedule/AgendaSheet.kt:1423 / 1586` | **直接复用**：`FieldRow` 做折叠行，胶囊抄 `OverlapSwitcher` |
| `notifyGranted()` | `ui/RootScreen.kt:262` | 是 RootScreen 的局部函数 → 给表单加一个 `notifyGranted: Boolean` 参数传进去 |
| `ClassReminderTest` | `test/ClassReminderTest.kt` | 纯函数测试的写法照抄 |

**不能复用的（各写各的）：**

- 通知内容：课程是「几点 课名 / 教室·教师 / 授课内容」，日程是「几点 日程名 / 地点 / 备注」。
- 频道：`class_remind` 已占，日程必须新开 `agenda_remind`（Android 频道建了就不能改重要性，名字和重要性一次定对）。
- 焦点通知（HyperOS 超级岛）参数 `focusParam` 是 private，一期不做；二期抽公共再给日程用。

## 3. 为什么不改 `ClassReminder`

`docs/PITFALLS.md` 已经写了结论，这里复述原因：

1. `ClassReminder.plan()` 的 requestCode 是 `(epochDay % 200000) * 100 + startUnit`，**节次**是核心参数。日程没有真节次，硬塞一个会跟课程抢 code。
2. `plan()` 里有 `used` 去重集合，语义是「同一天同一节只排一条」。日程一天可以有好几条，语义冲突。
3. `ReminderRefreshWorker` 的 ensure / cancel 挂在**课程提醒开关**上。日程提醒开着、课程提醒关着时，worker 会被 cancel 掉，日程跟着断签。
4. 课程提醒刚在 v1.3.4 修完（7 天窗口、触发后续排、12h 兜底、改时间重排），现在动它风险收益比最差。

**隔离靠三样东西**（任何一样单独都能防冲突，三样叠着）：

| 隔离项 | 课程 | 日程 |
|---|---|---|
| PendingIntent 目标组件 | `ClassReminderReceiver` | `AgendaReminderReceiver`（新） |
| requestCode 段 | `(epochDay%200000)*100 + 节次` ≈ 207 万级 | `(epochDay%100000)*1000 + 当天序号` ≈ 2070 万级 |
| 存 code 的 prefs | `class_reminder` | `agenda_reminder`（新文件） |

> PendingIntent 匹配看 `requestCode + Intent（组件/action/data）`，组件不同就**不可能**互相取消 —— 这是最硬的那道保险。code 分段只是为了让日志好看、排查时不串。

## 4. 排程设计

### 4.1 数据流

```
AgendaEditSheet 保存
  └─ AppViewModel.saveAgenda(item)
       ├─ agendaStore.upsert(item)          // 现有
       ├─ state.agenda 更新                  // 现有
       └─ AgendaReminder.scheduleAll(app, semester, state.agenda)   // 新增，IO 线程
```

删除同理。**每次全量重排**（cancel 全部 → 重新 plan）：日程最多几十条，重排是毫秒级；增量更新要处理「改了时间 / 改了提前量 / 改了日期 / 从每周改成单次」，逻辑分支比全量多得多。

### 4.2 触发重排的 5 个入口

| # | 时机 | 入口 |
|---|---|---|
| 1 | 保存 / 删除日程 | `AppViewModel.saveAgenda / deleteAgenda` |
| 2 | App 启动 | `AppViewModel.init` 里（bootstrap 读回日程之后） |
| 3 | 开机 / 覆盖安装 / 改时间 / 改时区 / 精确闹钟授权变化 | `BootReceiver` 里加一行 |
| 4 | 每条提醒触发之后 | `AgendaReminderReceiver` 里续排 |
| 5 | 每天兜底 | `AgendaReminderRefreshWorker`（12h 周期） |

### 4.3 窗口与过滤

- 窗口 **7 天**（和课程一致）：一周内每条规则最多 1 个实例，几十条日程 → 几条到十几条闹钟，离系统上限（500）很远。
- 只排 `whenAt > now + 5 秒` 的（照抄课程）。
- 只排学期内的（`AgendaLogic.expand` 已经过滤，这里不用再判）。

### 4.4 requestCode / 通知 ID

```kotlin
// 当天第几条（0 起）。同一天内不同日程绝不同 code，
// 同一条规则的不同天由 epochDay 段区分。
fun requestCode(date: LocalDate, index: Int): Int =
    ((date.toEpochDay() % 100_000L) * 1000L + index.coerceIn(0, 999)).toInt()
```

- 取消时**按存下来的 code 列表逐个取消**，不按「今天重算一遍」—— 这是课程那边踩过的坑（跨天重算 offset 变了，漏取消）。
- 通知 ID：`40_000 + floorMod(code, 1_000_000)`（课程是 20_000 起），互不覆盖。

### 4.5 触发时的行为

```
AlarmManager 到点 → AgendaReminderReceiver.onReceive
  ├─ 迟到检查：now - whenAt > 15 分钟 → 丢弃（只 log，不发）
  ├─ AgendaReminder.notify(...)           // 通知
  ├─ forgetCode(code)                     // 从已排列表里拿掉
  └─ goAsync + Thread { rescheduleFromCache }   // 续排
```

> 迟到阈值 15 分钟比课程的「超过提前量就丢」宽松一点：日程提前量常常是 0（准时），按课程的算法会把 5 分钟后的提醒也丢掉。

## 5. 通知设计

新频道 `agenda_remind`，「日程提醒」，`IMPORTANCE_HIGH` + 震动（课程频道同款）。

```
标题：14:30 复习内科学          ← 时间 + 标题（跟课程提醒一致，一瞥知道几点）
正文：图书馆 · 提前 10 分钟      ← 地点 + 提前量
大文本：备注（有才显示）
点按：打开 App（一期）
```

- 图标 `ic_notification`（和课程共用）。
- `setCategory(CATEGORY_REMINDER)`、`setAutoCancel(true)`、`PRIORITY_HIGH`。
- 提前量为 0 时正文只写地点，不写「提前 0 分钟」。

## 6. 表单 UI（放在新建 / 编辑面板）

### 位置

```
标题
日期
开始 / 结束
──── 提醒 ────          ← 新增，紧跟时间（语义上属于「什么时候」）
地点
备注
每周重复
颜色
```

### 三个方案

| 方案 | 长什么样 | 优点 | 缺点 |
|---|---|---|---|
| **A. 折叠行 + 胶囊选项（推荐）** | 一行「提醒　提前 10 分钟 ▾」，点开下面铺一排胶囊：`不提醒 准时 5 10 15 30 60 分钟` | 不点开时只占一行；7 个离散值一眼看全；样式直接抄 `OverlapSwitcher` | 展开后表单会变高约 40dp |
| B. 折叠行 + 滚轮 | 和日期/时间完全一致的交互 | 交互统一 | 7 个值用滚轮翻太慢，反而费劲 |
| C. 常驻一排胶囊 | 不折叠，一直显示 | 少一次点击 | 表单已经 700+ dp，再加一排更挤 |

选 A，理由是它跟「颜色」那排的视觉语言一致（同是离散选项），而滚轮是给连续量用的。

### 细节

- 值显示：`不提醒` / `准时` / `提前 N 分钟`。
- 通知权限没开时，选项下面一行小字：「通知权限未开启，提醒不会弹出」（和设置页同一个文案，点了去授权）。
- 编辑已有日程：读 `initial.remindMinutesBefore`；新建：读全局默认（见 §10 待定 1）。
- 草稿缓存 `EditDraft` 加一个 `remind: Int` 字段，误关重开跟着恢复。

### 列表侧

`AgendaRow`（我的日程列表）右侧加一个小铃铛 `Icons.Rounded.NotificationsActive`（13dp），有提醒才显示 —— 不然用户不知道哪条会响。

## 7. 边界情况清单

| 情况 | 行为 |
|---|---|
| 提醒时刻已经过去（如给今天上午的日程设提醒） | `plan()` 跳过，不排；保存不报错（表单校验不管这个） |
| 日程 5 分钟后开始、提前量 10 分钟 | 提醒时刻在过去 → 不排。用户想要就改提前量 |
| 改日程时间 / 提前量 / 日期 / 每周开关 | 全量重排，旧 code 全部 cancel，不会有「旧时间还响一次」 |
| 删日程 | 全量重排，同一条的闹钟全部消失 |
| 同一时刻两条日程 | 两条通知，code 由当天序号区分 |
| 每周重复跨到学期外 | `expand()` 不产出，自然不排 |
| 没同步过校历（无 semester） | 不排，log 一条；能画日程但不排提醒（和「课表上不显示」口径一致） |
| 切换学段 / 登出 | 日程本身保留 → 重排时按新学期算；学期外的实例自动不排 |
| App 被 ColorOS 打成 stopped | 同课程：闹钟全废，靠下次打开 / 12h worker 恢复（worker 也会被系统冻，这是系统级问题，不新增解法） |
| 通知权限关着 | 闹钟照排但通知发不出 —— 表单里给提示，和设置页一致 |

## 8. 点通知直达那条日程（二期）

一期只打开 App（`getLaunchIntentForPackage`，和课程提醒一样）。

二期的两条路：

| 做法 | 改动 | 代价 |
|---|---|---|
| A. Activity 收 extra | `MainActivity` 读 `intent.getStringExtra("agenda_id")` + `onNewIntent`；`RootScreen` 消费一次 → 打开编辑面板 | 要处理「冷启动 / 已在前台 / 二次点击」三种状态，约 40 行；`launchMode` 可能得改 `singleTop` |
| B. 通知里带 `contentIntent` 打开一个中间页 | 不减复杂度 | 不如 A |

建议 A，但**放二期**：先把「会响」跑稳，再优化「点了去哪」。

## 9. 二期可选的公共层抽象

现在 `ClassReminder` 和 `AgendaReminder` 会重复三段：

1. `place()`（18 行，三档：`setAlarmClock` / `setExactAndAllowWhileIdle` / `setAndAllowWhileIdle`）
2. `cancelScheduled` + `loadCodes/saveCodes`（30 行）
3. `focusParam`（HyperOS 焦点通知 JSON，25 行，现在 private）

等日程提醒发一版、确认稳定之后，抽 `core/AlarmSupport.kt`：

```kotlin
internal object AlarmSupport {
    fun canExact(ctx: Context): Boolean
    fun place(ctx: Context, whenAt: Long, pi: PendingIntent, enhanced: Boolean): ClassReminder.Mode
    fun loadCodes(ctx: Context, prefsName: String): List<Int>
    fun saveCodes(ctx: Context, prefsName: String, codes: List<Int>)
    fun cancelAll(ctx: Context, prefsName: String, intentFor: (Context) -> Intent)
}
```

`ClassReminder` 改成调它，行为不变；`AgendaReminder` 也调它。**这一版不做**，理由是课程提醒刚修好，改它要重新验证 7 天窗口 + 续排 + 开机重排三条链路，收益只是少 50 行重复代码。

## 10. 测试计划

| 测试 | 类型 | 覆盖 |
|---|---|---|
| `AgendaReminderTest` | 纯 JVM | `plan()`：单次 / 每周展开 / 提前量 0 与 15 分钟 / 过滤已过时刻 / 同一天多条不同 code / 学期外不排 / 跨周 |
| `requestCode` 唯一性 | 纯 JVM | 同一天 100 条不撞；不同天不撞；与课程 code 段不重叠（断言 agenda ≥ 2000 万、class ≤ 2000 万） |
| `AgendaReminderNotifyTest` | Robolectric | 权限没有时不崩、文案拼接（地点为空 / 提前量 0） |
| `AgendaEditSheet` 表单 | Robolectric | 选「提前 15 分钟」→ 保存出的 Agenda `remindMinutesBefore == 15`；「不提醒」→ -1 |
| 回归 | 现有 464 条 | `ClassReminderTest` 一条都不能动 |

## 11. 工时

| 步 | 内容 | 估时 |
|---|---|---|
| 1 | `core/AgendaReminder.kt`（plan / schedule / cancel / notify / receiver / worker） | 3~4 h |
| 2 | `AgendaReminderTest` + requestCode 测试 | 1.5 h |
| 3 | `AppViewModel` 挂钩（保存/删除/启动）+ `BootReceiver` 一行 | 0.5 h |
| 4 | 表单 UI（折叠行 + 胶囊 + 权限提示）+ `EditDraft` 字段 + 保存 | 2 h |
| 5 | 列表铃铛 | 0.5 h |
| 6 | 真机验证（改时间、每周重复、删日程、关机重启） | 1 h |
| | **合计** | **约 1 天** |

## 12. 拍板结论（2026-11-15）

> **补记（同日晚）**：胶囊改成了**多选** —— 同一条日程可以既「提前 1 天」又「提前 30 分钟」又「准时」，
> 每个档位各排一个闹钟。数据从 `remindMinutesBefore: Int` 改成 `remindLeads: List<Int>`。
> 多个档位**共用一个通知 ID**（后一条覆盖前一条），否则通知栏会堆一串同名提醒；
> 闹钟本身各用各的 requestCode。v1.3.5 那个字段从上线起就没 UI、不可能有值，旧 JSON 的键被忽略即可，不用迁移。


| 问题 | 结论 |
|---|---|
| 新建默认 | **准时**（到点响一次）。日程是用户主动加的事，加的时候就是想被提醒 |
| 档位 | 不提醒 / 准时 / 5 / 10 / 15 / 30 / 60 分钟 / **提前 1 天**（8 个胶囊，**多选**） |
| 点通知 | **一期只打开 App**，直达那条日程留到二期（要改 Activity + onNewIntent） |
| 列表铃铛 | **显示**（`Icons.Rounded.NotificationsActive`，13dp，有提醒才画） |
| 公共层 `AlarmSupport` | **这一版不抽**。课程提醒刚修稳，等日程提醒发一版确认稳定再说 |

实现时补的两个细节：

- 表单里的通知权限提示自己查、自己要（`rememberLauncherForActivityResult`），不依赖 RootScreen 传参
- `AgendaReminderRefreshWorker` 单独一个 12h 任务，不复用课程的 —— 课程的 worker 会在
  「课程提醒关掉」时被 cancel，日程跟着断签
