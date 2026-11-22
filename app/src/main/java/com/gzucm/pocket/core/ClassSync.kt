package com.gzucm.pocket.core

import kotlinx.serialization.Serializable
import java.security.MessageDigest
import java.time.LocalDate

/*
 * 班级日程云端版：协议类型 + 同步逻辑的**纯函数**。
 *
 * 这个文件里没有 Context、没有 OkHttp、没有 Android 类型 —— 全部能在
 * 普通 JVM 单测里跑（见 ClassSyncTest）。网络和落盘分别在
 * ClassSyncApi / ClassSyncStore，UI 只消费这里的结果。
 *
 * 契约：docs/CLASSPLAN_CLOUD.md。下面这些字段名和顺序是两端共用的，
 * 改一个就得两边一起改，**不要顺手改名**。
 */

// ── 协议类型（契约 §4） ──────────────────────────────────────────────

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
    /**
     * 最近一次导入实际新增 / 更新了几条。
     *
     * 只为了首页那行能说「新增 2 条、改了 1 条」—— 同步时手上有
     * [ClassImportResult]，但界面渲染时早丢了，所以在这里留一份。
     * 纯展示用途，不参与任何判断。
     */
    val lastAdded: Int = 0,
    val lastUpdated: Int = 0,
    /**
     * 用户在「清单详情」里明确勾掉、不想要的条目（存 [ClassTaskDto.id]）。
     *
     * 为什么要记住它：如果只是「这一次没勾」，下次同步会重新算成「新增 N 条」
     * 再弹一次窗 —— 用户明确拒绝过的东西反复出现，比不给他选更烦。
     * 记住之后，这些条目**永远不会**被自动导入，也不会出现在「新增」计数里。
     *
     * 想反悔随时进清单详情重新勾，那里永远列全部条目，不受这个集合限制。
     *
     * 用 List 不用 Set：`@Serializable` 的 Set 在旧版 kotlinx 上序列化行为不稳，
     * 这里数据量小（最多几十个短 id），`in` 查找的线性代价可以忽略。
     */
    val declinedIds: List<String> = emptyList(),
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

/**
 * `POST /classplan/publish` 的成功响应。
 *
 * 只留 UI 真正用得上的两个字段：`id` 用来把本地列表对准远端，
 * `updatedAt` 用来填 [ClassSubscription.importedUpdatedAt]。
 */
@Serializable
data class ClassPublishAck(
    val id: String,
    val updatedAt: Long = 0,
)

// ── 标识算法（契约 §1） ──────────────────────────────────────────────

/**
 * 班级名 → 对象存储里的目录名。
 *
 * `sha256(班级名).hexdigest()[:16]`，**班级名不做任何规范化**
 * （不 trim、不去空格、不转全角半角）—— App 拿到的学籍原文是什么就哈希什么，
 * 服务端不猜。一旦这里开始「顺手规范化」，两端算出的目录就分叉，
 * 同一份清单会变成两份、谁也看不到谁。
 *
 * 取 16 位十六进制 = 64 bit，一个学校几千个班，碰撞概率可以忽略。
 */
fun classHash(className: String): String = sha256Hex(className).take(16)

/**
 * 学委名字（清单 key）→ 对象名。
 *
 * `sha256(key).hexdigest()[:6]`。只有 24 bit，**不指望它唯一** ——
 * 真正定位靠 `{classHash}/{planId}` 这一整条路径，同班重名会撞，
 * 撞了就是两个人共用一个对象（后发的覆盖先发的），契约接受这个概率
 * （同班两个学委同名本来就极罕见）。key 的原文仍存在正文里，能看出来是谁。
 */
fun planId(key: String): String = sha256Hex(key).take(6)

private fun sha256Hex(text: String): String {
    val digest = MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8))
    val out = StringBuilder(digest.size * 2)
    for (b in digest) {
        val v = b.toInt() and 0xFF
        out.append(HEX[v ushr 4]).append(HEX[v and 0x0F])
    }
    return out.toString()
}

private const val HEX = "0123456789abcdef"

// ── 节流（契约 §6） ──────────────────────────────────────────────────

/** 自动同步的最小间隔：24 小时 */
const val CLASS_SYNC_INTERVAL_MS: Long = 24L * 60L * 60L * 1000L

/** 手动入口（设置页 / 下拉刷新）的最小间隔：60 秒。防手抖连点打穿服务端 */
const val CLASS_MANUAL_MIN_INTERVAL_MS: Long = 60L * 1000L

/** 连续忽略到这个次数就不再弹窗，降级成首页横幅 */
const val CLASS_IGNORE_DOWNGRADE: Int = 3

/**
 * 该不该自动同步。
 *
 * 严格大于 24 小时才同步 —— 用的是 `>` 而不是 `>=`，因为「正好 24 小时」
 * 落在边界上时不应该刚打开 App 就又发一轮请求。
 * `lastSyncAt == 0`（从没同步过）时差值极大，天然返回 true。
 *
 * 注意：时钟回拨会得到负数，此时返回 false（不同步），不会误判成「该同步」。
 */
fun shouldSync(lastSyncAt: Long, now: Long): Boolean = now - lastSyncAt > CLASS_SYNC_INTERVAL_MS

/** 手动同步的最小间隔。手动入口不受 24 小时限制，只防连点 */
fun canManualSync(lastAttemptAt: Long, now: Long): Boolean =
    now - lastAttemptAt >= CLASS_MANUAL_MIN_INTERVAL_MS

/**
 * 这次该用哪种方式告诉用户「有新版」。
 *
 * - [NONE]：什么都不做（没有新版，或者这一版已经弹过）
 * - [BANNER]：首页那行静默提示，不打断
 * - [DIALOG]：弹窗
 */
enum class ClassPrompt { NONE, BANNER, DIALOG }

/**
 * 三条节流规则，缺一条都会被烦死（契约 §6）：
 *
 * 1. 同一份清单同一版本只弹一次 → `brief.updatedAt > sub.notifiedUpdatedAt`
 * 2. 一天最多一次弹窗 → `now - lastPromptAt > 24h`
 * 3. 连续忽略 ≥3 次 → 不再弹，改横幅
 *
 * 规则 2 不满足时返回 [ClassPrompt.BANNER] 而不是 [ClassPrompt.NONE]：
 * 弹窗额度今天用完了，但「有新版」这件事仍然值得在首页挂一行，
 * 只是不许再打断用户。
 *
 * @param sub 已认领的订阅；还没认领（只是想告诉他本班有几份清单）时传 null
 */
fun shouldPrompt(
    brief: ClassPlanBrief?,
    sub: ClassSubscription?,
    state: ClassSyncState,
    now: Long,
): ClassPrompt {
    if (brief == null) return ClassPrompt.NONE
    val notified = sub?.notifiedUpdatedAt ?: 0L
    // 规则 1：这一版已经通知过（或根本没有新版）→ 彻底安静
    if (brief.updatedAt <= notified) return ClassPrompt.NONE
    // 规则 3：一直不看的人，别再弹了
    if (state.ignored >= CLASS_IGNORE_DOWNGRADE) return ClassPrompt.BANNER
    // 规则 2：今天已经弹过一次
    if (now - state.lastPromptAt <= CLASS_SYNC_INTERVAL_MS) return ClassPrompt.BANNER
    return ClassPrompt.DIALOG
}

// ── 状态迁移（纯函数，返回新对象） ───────────────────────────────────

/** 找某一份清单的订阅；没有就是还没认领 */
fun ClassSyncState.subOf(id: String): ClassSubscription? = subs.firstOrNull { it.id == id }

/** 写入 / 覆盖一条订阅（按 id）。认领、导入完成后都走它 */
fun ClassSyncState.withSub(sub: ClassSubscription): ClassSyncState =
    copy(subs = subs.filterNot { it.id == sub.id } + sub)

/** 去掉一条订阅（取消认领）。已导入的日程不在这里处理 —— 它们归 AgendaStore */
fun ClassSyncState.withoutSub(id: String): ClassSyncState =
    copy(subs = subs.filterNot { it.id == id })

/** 同步成功（哪怕是空结果）后记一笔，24 小时节流靠它 */
fun ClassSyncState.syncedAt(now: Long): ClassSyncState = copy(lastSyncAt = now)

/** 弹窗已经给用户看了：记时间，并把连续忽略次数清零（他这次没有忽略） */
fun ClassSyncState.promptShown(now: Long): ClassSyncState =
    copy(lastPromptAt = now, ignored = 0)

/** 用户把弹窗划掉了 / 点了「以后再说」：连续忽略 +1 */
fun ClassSyncState.promptIgnored(): ClassSyncState = copy(ignored = ignored + 1)

/**
 * index 里比本地更新的那几份。
 *
 * 判据是 `brief.updatedAt > sub.importedUpdatedAt`：只要远端版本比
 * 「上次导入到的版本」新，就算有更新。还没认领的清单不在这里
 * （认领前用户连正文都没有，谈不上「更新」）。
 */
fun newVersions(index: ClassPlanIndex, state: ClassSyncState): List<ClassPlanBrief> =
    index.plans.filter { brief ->
        val sub = state.subOf(brief.id) ?: return@filter false
        brief.updatedAt > sub.importedUpdatedAt
    }

/**
 * 自动同步时真正要合并的条目。
 *
 * 就是「清单全部条目 − 用户明确拒绝过的」。没认领过（[sub] 为 null）时全都要 ——
 * 首次认领走的是用户勾选的路径，不走这个函数。
 *
 * 抽出来是因为它要能被单测：用户勾掉 3 条之后，下次同步的 added 计数
 * 必须把那 3 条排除，否则首页会一直喊「新增 3 条」而点进去什么都没变。
 */
fun autoImportIds(plan: ClassPlan, sub: ClassSubscription?): Set<String> {
    val declined = sub?.declinedIds.orEmpty().toHashSet()
    return plan.tasks.mapNotNullTo(LinkedHashSet()) { dto ->
        if (dto.id in declined) null else dto.id
    }
}

/**
 * 覆盖「这份清单里我不要哪些」。
 *
 * 详情页每次确认导入时整份替换，而不是追加：用户可能这次把上次勾掉的
 * 又勾回来了，追加的话旧记录会把它继续压在拒绝名单里。
 */
fun ClassSyncState.withDeclined(planId: String, ids: Collection<String>): ClassSyncState {
    val sub = subOf(planId) ?: return this
    return withSub(sub.copy(declinedIds = ids.distinct()))
}

// ── 合并 / 冲突处理 ──────────────────────────────────────────────────

/**
 * 云端自动同步的合并入口：和 [mergeClassTasks] 同一套规则，
 * 只是把「新增 / 覆盖要标未读」打开（契约 §5 的第一、二行）。
 *
 * 手动粘贴分享串不要用这个 —— 用户刚看完预览、亲手点了「导入」，
 * 再给他挂一串未读角标毫无意义。那条路继续调 [mergeClassTasks] 的默认参数。
 */
fun mergeClassSync(
    existing: List<Agenda>,
    plan: ClassPlan,
    today: LocalDate = LocalDate.now(),
    onlyIds: Set<String>? = null,
): Pair<List<Agenda>, ClassImportResult> =
    mergeClassTasks(existing, plan, today, markUnread = true, onlyIds = onlyIds)

/**
 * 冲突时用户选了「用学委的」。
 *
 * 内容整体换成远端版本，只保留 [Agenda.doneAt]（本地完成状态永远不被抹掉），
 * 同时清掉冲突标记、把 [Agenda.remoteUpdatedAt] 抬到远端版本 ——
 * 下次合并看到「版本相同」就走 `unchanged`，不会又判一次冲突。
 */
fun resolveConflictByRemote(
    local: Agenda,
    dto: ClassTaskDto,
    today: LocalDate = LocalDate.now(),
): Agenda {
    val key = local.originClass()
    val fresh = dto.toClassAgenda(key, today)
    return fresh.copy(
        // id 换成用本地 origin 推出来的那个，保证还是同一条，不会变成孤儿
        id = local.id,
        origin = local.origin,
        doneAt = local.doneAt,
        classUnread = false,
        classConflict = false,
        classRemoteGone = false,
        localEditedAt = 0L,
    )
}

/**
 * 冲突时用户选了「留我的」。
 *
 * 内容一个字不动，只把 [Agenda.remoteUpdatedAt] 记成远端那一版 ——
 * 等于告诉下一次合并「这一版我看过了，别再拿它跟我争」。
 */
fun resolveConflictByLocal(local: Agenda, remoteUpdatedAt: Long): Agenda =
    local.copy(
        remoteUpdatedAt = remoteUpdatedAt,
        classUnread = false,
        classConflict = false,
        classRemoteGone = false,
        localEditedAt = 0L,
    )

/**
 * 用户确认了「学委那边已经删掉这条」：就地转成普通本地条目。
 *
 * 清 `origin` 而不是直接删 —— 用户可能已经在这条上写了笔记、勾了完成，
 * 删掉是不可逆的。转成本地条目之后，它就不再受这份清单的合并影响。
 */
fun detachFromClass(local: Agenda): Agenda = local.copy(
    origin = "",
    remoteUpdatedAt = 0L,
    classUnread = false,
    classRemoteGone = false,
    classConflict = false,
    localEditedAt = 0L,
)
