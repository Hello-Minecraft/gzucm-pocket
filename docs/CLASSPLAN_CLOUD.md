# 班级日程云端版 · 接口契约

> 状态：**实现中**（2026-11-22 定稿）
> 上游设计：`core/ClassShare.kt` 注释里的「云端版」约定（className 主索引 / key 认领符 / updatedAt 版本判据）
> 本文是 App 与 FC 之间的唯一契约。改这里必须先改文档，两个端一起改。

## 1. 对象布局

```
classplan/v1/{classHash}/_index.json     全班清单摘要（可丢弃缓存）
classplan/v1/{classHash}/{planId}.json   一份清单正文（ClassPlan 原样序列化）
```

| 名称 | 算法 | 例 |
|---|---|---|
| `classHash` | `sha256(班级名).hexdigest()[:16]` | `3f9a1c7d2b4e5068` |
| `planId` | `sha256(学委名字).hexdigest()[:6]` | `a1b2c3` |

班级名用学籍里的原文（如 `一院23中医(铁涛)5`），**不做任何规范化**——两端必须完全一致，服务端不猜。

`_index.json` 缺失或解析失败时，服务端自行用 `ListObjects(prefix)` + 逐个 `HEAD` 重建，再回写。它只是加速缓存，丢了不影响正确性。

## 2. 接口

Base：现有 FC 域名，路径前缀 `/classplan`。全部 `POST`（GET 也能走，但统一 POST 省得处理 querystring 编码）。

公共请求头：

| 头 | 说明 |
|---|---|
| `X-App-Token` | 与天气云函数同一个 `APP_TOKEN`，防脚本乱刷 |
| `Content-Type` | `application/json` |

### 2.1 `POST /classplan/index`

```json
{"class":"一院23中医(铁涛)5"}
```

响应：

```json
{"ok":true,"updatedAt":1732350000000,
 "plans":[{"id":"a1b2c3","key":"张三","maintainer":"2023014093","count":12,"updatedAt":1732349000000}]}
```

`maintainer` 服务端只回学号（不回姓名），避免清单被同班之外的人认出「谁在维护」。

### 2.2 `POST /classplan/get`

```json
{"class":"...","id":"a1b2c3"}
```

响应：`{"ok":true,"plan":{ ...ClassPlan JSON... }}`
正文原样透传，服务端**不改写**任何字段。不存在返回 `{"ok":false,"error":"not_found"}`（HTTP 404）。

### 2.3 `POST /classplan/publish`

```json
{"class":"...","uploader":"2023014093","plan":{ ...ClassPlan JSON... }}
```

校验顺序（任何一条不过就 4xx，`error` 给英文码，`message` 给中文人话）：

| # | 规则 | 错误码 |
|---|---|---|
| 1 | `X-App-Token` 正确 | `bad_token` |
| 2 | `class` 非空且长度 ≤ 40 | `bad_class` |
| 3 | `plan.key` 非空且 ≤ 12 字 | `bad_key` |
| 4 | `plan.tasks` 1~80 条（一学期 15 门课各 5 次作业） | `bad_count` |
| 5 | 该 `uploader` 在本班已有清单数 < 2（本条是新增时才算） | `quota_user` |
| 6 | 本班清单总数 < 8 | `quota_class` |

成功后重建 `_index.json` 并返回 `{"ok":true,"id":"a1b2c3","updatedAt":...}`。

### 2.4 `POST /classplan/delete`

```json
{"class":"...","id":"a1b2c3","uploader":"2023014093"}
```

只允许删**自己发的**：读正文比对 `plan.maintainer == uploader`，不一致返回 `not_owner`。删除后重建 index。

## 3. 鉴权与已知风险（写清楚，别假装安全）

- `X-App-Token` 是**共享密钥**，只能防「不知道这个串的脚本」，挡不住逆向 APK 的人。
- `uploader`（学号）和 `class` 都是**客户端上报**的，服务端无法验证真伪——教务是 cookie 会话，FC 拿不到可信凭证。
- 所以第一版的安全模型是「**可追溯 + 有配额**」，不是「严格身份认证」：
  - 每条清单的对象元数据带 `uploader`，出问题能查到是谁传的
  - 每人 2 份、每班 8 份，压住灌水
  - 真要严格校验，二期让 App 把教务 cookie 传给 FC，由 FC 代调学籍接口验证（`StudentCard`），代价是服务端要处理用户 cookie，安全上更敏感，先不做。

## 4. Kotlin 数据定义（两端共用，直接落地）

```kotlin
// core/ClassSync.kt

/** index 里的一条摘要 */
@Serializable
data class ClassPlanBrief(
    val id: String,
    val key: String,
    val maintainer: String = "",
    val count: Int = 0,
    val updatedAt: Long = 0,
)

@Serializable
data class ClassPlanIndex(
    val v: Int = 1,
    val updatedAt: Long = 0,
    val plans: List<ClassPlanBrief> = emptyList(),
)

/** 已认领的一份清单 */
@Serializable
data class ClassSubscription(
    val id: String,
    val key: String,
    /** 本地已导入到的远端版本，用于判断「有没有新版」 */
    val importedUpdatedAt: Long = 0,
    /** 上次因为这份清单弹过窗的版本，避免同一版弹两次 */
    val notifiedUpdatedAt: Long = 0,
    val claimedAt: Long = 0,
)

/** 整个同步状态 */
@Serializable
data class ClassSyncState(
    val lastSyncAt: Long = 0,
    /** 上次「弹窗打扰」的时间。一天最多一次，靠它节流 */
    val lastPromptAt: Long = 0,
    /** 连续被忽略的次数，≥3 降级为横幅 */
    val ignored: Int = 0,
    val subs: List<ClassSubscription> = emptyList(),
)
```

`Agenda` 新增四个字段（全部有默认值，向后兼容）：

```kotlin
/** 来自班级清单且用户还没看过 */
val classUnread: Boolean = false,
/** 学委在清单里删掉了这条，本地保留中 */
val classRemoteGone: Boolean = false,
/** 学委改了、而本地也手动改过 → 冲突，等用户选 */
val classConflict: Boolean = false,
/** 用户最后一次手动编辑的时间；0 = 没动过 */
val localEditedAt: Long = 0,
```

## 5. 合并规则（改自 `mergeClassTasks`）

对 `plan.tasks` 每一条：

| 本地情况 | 远端 | 行为 |
|---|---|---|
| 没有 | 有 | 新增，`classUnread=true` |
| 有 | 更新更晚，且 `localEditedAt <= remoteUpdatedAt` | 覆盖内容，保留 `doneAt`，`classUnread=true` |
| 有 | 更新更晚，但 `localEditedAt > remoteUpdatedAt` | **不覆盖**，`classConflict=true` |
| 有 | 更新更早或相同 | 不动，`unchanged++` |
| 有 | 远端已无此条 | `classRemoteGone=true`，**不删** |

`orphaned` 统计口径改为「同一订阅下、远端已不存在的条数」，用于首页那行橙色提示。

## 6. 同步节流（三条，缺一条都会被烦死）

1. `now - lastSyncAt > 24h` 才自动同步（手动入口不受限，但 60 秒内不重复）
2. 同一份清单同一版本只弹一次：`brief.updatedAt > sub.notifiedUpdatedAt`
3. 一天最多一次弹窗：`now - lastPromptAt > 24h`；连续忽略 ≥3 次不再弹，改首页横幅

## 7. UI 文案（固定，避免各处措辞不一）

- 未认领：「本班有 N 份班级日程」
- 已认领无更新：「{key} 的清单 · {count} 条」「今天 08:12 同步」
- 有更新：「{key} 更新了{patch}」
- 首次说明（三句）：学委把作业清单传到这里，你的 App 每天自动看一眼 / 认领之后新作业会直接进你的日程，截止前会提醒你 / 随时可以取消认领，已导入的日程不会消失
- 发布确认（首次）：「上传后**本班所有同学**都会收到提醒。请确认这是全班都需要的作业，不要上传个人笔记。」
- 发布常驻小字：「发布后本班会收到提醒」
- 条目被删：标题左侧橙色竖线 + 右上「已删除」，详情顶部提示「学委在清单里删掉了这条，你本地还留着」
- 条目冲突：「学委也改过这条」，点开并排对比

## 8. 不要做的事

- 不要给 `classplan/` 前缀开公共读（桶已开阻止公共访问，保持）
- 不要在 App 里嵌 AK（读写全走 FC）
- 不要把 `maintainer` 显示成真实姓名以外的东西（同学要能认出找谁）
- 不要在弹窗里展开完整清单（只给前 3 条标题预览）
