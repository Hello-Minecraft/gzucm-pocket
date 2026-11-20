package com.gzucm.pocket.core

import android.content.Context
import kotlinx.serialization.Serializable
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * 班级日程：学委（或课代表）维护的一份 DDL 清单。
 *
 * ## 它和「自己的日程」是两样东西
 *
 * 第一版做成「把你所有截止任务打包发出去」—— 致辉当场指出这样不对：
 * 学委要的是**一份能改的清单**，新作业来了加一条、作业延期了改日期、
 * 取消了删一条。打包自己的日程只能发快照，改一条就得重发一次全班皆错。
 *
 * 所以清单独立存在（[ClassPlanStore]），和 `AgendaStore` 一个字都不共享：
 *   · 学委维护的是它，分享发出去的也是它；
 *   · 同学导入后，它变成同学自己日程里的**截止任务**（今日页、逾期标红、
 *     勾选完成全都复用，不需要新造展示）。
 *
 * ## 「密钥」为什么是学委名字
 *
 * 同学要拿到这份清单，得知道填什么。填班级全名（「一院23中医(铁涛)5」）太拗口，
 * 而且换辅导员、分方向之后会变。用**学委的名字**最稳：全班都知道是谁，
 * 而且这个名字天然是「一个人」的标识 —— 以后接云端时，
 * 用户只要填一个学委名字，服务端就能查「这个人有没有活跃清单，要不要拉」，
 * 不需要用户先选年级再选班级。
 */
const val CLASS_TOKEN_PREFIX = "GZP3:"

/** 一条班级作业。字段短名是为了压低分享串长度，只有这个文件读写它 */
@Serializable
data class ClassTaskDto(
    /** 维护者这边的稳定 id。合并靠它认人，不能用标题当键（同名的 DDL 很常见） */
    val id: String,
    val title: String,
    /**
     * 科目，**选填**。
     *
     * 大多数情况直接写在标题里更省事（「中医内科学期末论文」），
     * 这个字段留给「标题不想带科目、又想按科目看」的人。
     */
    val course: String = "",
    /** 截止时间（epoch millis）。DDL 用；null = 长期待办 */
    val dueAt: Long? = null,
    /**
     * 日程形态：起始日期 `yyyy-MM-dd`。**空串 = 这条是 DDL/待办，不是日程**。
     *
     * 2026-11-21 加的。原来清单只认「截止时间」，从日程导入时只能硬塞一个
     * 结束时刻当 DDL —— 致辉指出这是错的：见习、上课这种本来就是「某天某时段」，
     * 转成截止任务会丢掉时长、地点，还会在今日页变成一个「今天要完成的事」。
     *
     * 现在两种形态并存：DDL（[dueAt] 有值）和日程（[day] 非空）。
     * **提醒不跟着清单走**：提醒是每个人的偏好，学委不该替全班决定
     * 什么时候响（致辉 2026-11-21 明确说的）。
     */
    val day: String = "",
    /** 日程形态的开始分钟（当天第几分钟）。只在 [day] 非空时有意义 */
    val startMinute: Int = 0,
    /** 日程形态的结束分钟 */
    val endMinute: Int = 0,
    /** 日程形态的地点 */
    val place: String = "",
    /** 详细要求（选填）。作业要求长的时候就写这里 */
    val note: String = "",
    /** 这一条的修改时间。合并时只认更新的那份，避免旧串把新内容盖回去 */
    val updatedAt: Long,
) {
    /** 这条是「某天某时段」的日程，还是「有个截止时间」的任务 */
    val isEvent: Boolean get() = day.isNotBlank()
}

/** 一个学委维护的整份清单 */
@Serializable
data class ClassPlan(
    /**
     * 密钥：**手动填**，全班共识的那个名字（通常是学委）。
     *
     * 注意它和 [maintainer] 是两回事：密钥是「同学凭它认领这份清单」的标识，
     * 维护人是「实际在编辑的人」。班长帮学委代维护很常见 ——
     * 那时密钥该填学委的名字（全班都知道找谁要作业），维护人却是班长自己。
     *
     * 只用来解锁和区分同一班的几份清单，**不参与查找**（查找靠 [className]）。
     */
    val key: String,
    /**
     * 维护人：**自动填**，当前登录账号的名字。
     *
     * 本地版取学籍里的姓名；接上云端换成学号更稳（重名不串）。
     * 同学导入时会看到「这是 X 维护的清单」，出问题知道找谁。
     */
    val maintainer: String = "",
    /**
     * 行政班，如「一院23中医(铁涛)5」。
     *
     * **这是云端版真正的主索引**（2026-11-20 致辉定的）：
     * 同学登录后 App 拿学籍里的班级直接查云端，有清单才弹窗，
     * 不需要同学输任何东西。密钥只负责「解锁哪一份」。
     *
     * 一个班可以有多份：学委一份全科、各科课代表各一份。
     * 本地版（分享串）里这个字段只用于显示「这是 X 班的清单」，
     * 传不出去也不影响导入 —— 老串里没有它就是空串。
     */
    val className: String = "",
    val tasks: List<ClassTaskDto> = emptyList(),
    /**
     * 整份清单的更新时间。
     *
     * 现在只有本地用得上（「上次改于 X」），
     * 接云端之后它是「要不要提示同学有新版」的判据。
     */
    val updatedAt: Long = 0L,
)

/** 导入结果，直接拿去拼一句人话给用户看 */
data class ClassImportResult(
    val added: Int,
    val updated: Int,
    val unchanged: Int,
    /** 本地已勾完成、这次远程改动没有把完成状态抹掉的条数 */
    val keptDone: Int,
    /**
     * 本地还留着、但学委这份清单里已经没有的条数（多半是学委删了）。
     *
     * **只报告，不自动删**：用户可能已经在那条上写了笔记、
     * 或者学委删错了还会加回来。真要清理让他自己在日程里删。
     */
    val orphaned: Int = 0,
) {
    /** 这次有没有真的产生变化（决定「确认导入」按钮是不是灰的） */
    val hasChanges: Boolean get() = added > 0 || updated > 0

    fun summary(): String = buildString {
        if (added > 0) append("新增 $added 条")
        if (updated > 0) {
            if (isNotEmpty()) append("，")
            append("更新 $updated 条")
        }
        if (keptDone > 0) {
            if (isNotEmpty()) append("，")
            append("$keptDone 条已完成的保持不变")
        }
        if (isEmpty()) append("没有新内容，都是已有的")
        if (orphaned > 0) {
            append("。另外有 $orphaned 条学委那边已经删掉了，还留在你这里")
        }
    }
}

// ── 清单存储 ──────────────────────────────────────────────────────────

/**
 * 班级清单的独立持久化。
 *
 * 为什么不放进 `AgendaStore`：清单是「我发给别人的东西」，日程是「我自己的事」。
 * 混在一起会出现「我把自己加的那条作业删了，结果全班的清单也少一条」——
 * 这两件事的责任人不同，存储也必须分开。
 */
class ClassPlanStore(context: Context) {

    private val sp = context.applicationContext
        .getSharedPreferences("gzucm_pocket_class_plan", Context.MODE_PRIVATE)

    fun load(): ClassPlan = synchronized(LOCK) {
        val raw = sp.getString(KEY_PLAN, null) ?: return@synchronized ClassPlan(key = "")
        runCatching { Net.json.decodeFromString(ClassPlan.serializer(), raw) }
            .getOrDefault(ClassPlan(key = ""))
    }

    fun save(plan: ClassPlan) = synchronized(LOCK) {
        val encoded = Net.json.encodeToString(ClassPlan.serializer(), plan)
        sp.edit().putString(KEY_PLAN, encoded).apply()
    }

    fun clear() = synchronized(LOCK) { sp.edit().remove(KEY_PLAN).apply() }

    private companion object {
        const val KEY_PLAN = "class_plan"
        val LOCK = Any()
    }
}

// ── 编解码 ────────────────────────────────────────────────────────────

fun encodeClassToken(plan: ClassPlan): String {
    val json = Net.json.encodeToString(ClassPlan.serializer(), plan)
    val raw = json.toByteArray(Charsets.UTF_8)

    val deflater = java.util.zip.Deflater(java.util.zip.Deflater.BEST_COMPRESSION, true)
    deflater.setInput(raw)
    deflater.finish()
    val out = java.io.ByteArrayOutputStream(raw.size / 2 + 64)
    val buf = ByteArray(8 * 1024)
    while (!deflater.finished()) {
        out.write(buf, 0, deflater.deflate(buf))
    }
    deflater.end()

    val b64 = java.util.Base64.getUrlEncoder().withoutPadding()
        .encodeToString(out.toByteArray())
    return CLASS_TOKEN_PREFIX + b64
}

/**
 * 解析分享串。
 *
 * 失败一律抛 `IllegalArgumentException`，文案直接给用户看 ——
 * 这里最常见的错是「粘了课表串」和「粘了一半」，两种都要说清楚是哪种。
 */
fun decodeClassToken(token: String): ClassPlan {
    val text = token.trim()
    require(text.isNotEmpty()) { "先复制学委发你的那串字，再来点导入" }
    require(text.startsWith(CLASS_TOKEN_PREFIX)) {
        if (text.startsWith(EXPORT_TOKEN_PREFIX)) {
            "这是课表的分享串，不是班级日程的"
        } else {
            "这不像班级日程的分享串，可能没复制全"
        }
    }

    val b64 = text.removePrefix(CLASS_TOKEN_PREFIX).trim()
    val packed = runCatching { java.util.Base64.getUrlDecoder().decode(b64) }
        .getOrElse { throw IllegalArgumentException("分享串被截断了，让学委重新发一次") }

    val json = runCatching {
        val inflater = java.util.zip.Inflater(true)
        inflater.setInput(packed)
        val out = java.io.ByteArrayOutputStream(packed.size * 4 + 64)
        val buf = ByteArray(8 * 1024)
        while (!inflater.finished()) {
            val n = inflater.inflate(buf)
            if (n == 0 && inflater.needsInput()) break
            out.write(buf, 0, n)
        }
        inflater.end()
        out.toByteArray().toString(Charsets.UTF_8)
    }.getOrElse { throw IllegalArgumentException("分享串解不开，让学委重新发一次") }

    val plan = runCatching { Net.json.decodeFromString(ClassPlan.serializer(), json) }
        .getOrElse { throw IllegalArgumentException("分享串格式对不上，可能来自更新的版本") }

    require(plan.tasks.isNotEmpty()) { "这串里没有作业，让学委重新生成一次" }
    return plan
}

// ── 导入 / 转换 ───────────────────────────────────────────────────────

/** 导入进来的任务 id 前缀。带密钥，避免两个学委的清单互相覆盖 */
private fun taskId(key: String, remoteId: String) = "cls:$key:$remoteId"

/** `origin` 的取值：本地建的是空串 */
fun classOrigin(key: String) = "class:$key"

/** 这条任务是哪个学委的清单导进来的；本地建的回空串 */
fun Agenda.originClass(): String =
    if (origin.startsWith("class:")) origin.removePrefix("class:") else ""

/**
 * 把一份班级清单合并进本地日程。
 *
 * 三条规则，缺一条都会出问题：
 *
 * 1. **认 id 不认标题**。同名的 DDL（「实验报告」每周一份）太常见，
 *    拿标题当键会把两条合成一条。
 * 2. **只认更新的那份**。学委连发两次串时，先导的旧串不能把新内容盖回去。
 * 3. **本地完成状态永远保留**。学委改了个错别字，不能把你已经勾掉的作业
 *    重新变回未完成 —— 那等于让全班的进度倒退。
 */
fun mergeClassTasks(
    existing: List<Agenda>,
    plan: ClassPlan,
    today: LocalDate = LocalDate.now(),
): Pair<List<Agenda>, ClassImportResult> {
    val list = existing.toMutableList()
    val indexById = existing.withIndex().associate { (i, a) -> a.id to i }.toMutableMap()

    var added = 0
    var updated = 0
    var unchanged = 0
    var keptDone = 0

    plan.tasks.forEach { dto ->
        val incoming = dto.toAgenda(plan.key, today)
        val at = indexById[incoming.id]
        if (at == null) {
            list += incoming
            indexById[incoming.id] = list.lastIndex
            added++
            return@forEach
        }
        val old = list[at]
        if (dto.updatedAt > old.remoteUpdatedAt) {
            /* 完成状态保留；其余字段以远程为准 */
            list[at] = incoming.copy(doneAt = old.doneAt)
            if (old.doneAt != null) keptDone++
            updated++
        } else {
            unchanged++
        }
    }

    /*
     * 本地还留着、这份清单里已经没有的 —— 学委删过东西。
     * 只统计**同一个 key** 导进来的条目，别把另一个学委的清单算成「被删了」。
     */
    val incomingIds = plan.tasks.mapTo(HashSet()) { taskId(plan.key, it.id) }
    val orphaned = existing.count {
        it.origin == classOrigin(plan.key) && it.id !in incomingIds
    }

    return list to ClassImportResult(added, updated, unchanged, keptDone, orphaned)
}

/**
 * 一条班级作业 → 本地日程。
 *
 * **两种形态分开走**（2026-11-21）：
 *   · 日程（[ClassTaskDto.day] 非空）→ 普通日程，保留时长和地点；
 *   · DDL → 截止任务，没填时间的按长期待办。
 *
 * 提醒一律留空：清单只是「有什么作业」，什么时候响是每个人自己的事。
 * 学委设了「提前一天」不代表全班都想要 —— 硬塞过去，同学还得逐条关掉。
 */
private fun ClassTaskDto.toAgenda(key: String, today: LocalDate): Agenda {
    val localId = taskId(key, this.id)
    if (isEvent) {
        val start = startMinute.coerceIn(0, 1439)
        return Agenda(
            id = localId,
            title = title,
            date = day,
            startMinute = start,
            endMinute = endMinute.coerceIn(start + 1, 1440),
            place = place,
            note = note,
            isTask = false,
            // 导入的日程一律单次：学委那边是单次，重复规则不该凭空生成
            daily = false,
            weekly = false,
            origin = classOrigin(key),
            remoteUpdatedAt = updatedAt,
        )
    }
    val date = dueAt?.let {
        Instant.ofEpochMilli(it).atZone(ZoneId.systemDefault()).toLocalDate()
    } ?: today
    return Agenda(
        id = localId,
        title = title,
        date = date.toString(),
        // 截止任务不占时段，这两个值只用来通过校验
        startMinute = 23 * 60 + 59,
        endMinute = 23 * 60 + 59,
        note = note,
        isTask = true,
        deadlineAt = dueAt,
        origin = classOrigin(key),
        remoteUpdatedAt = updatedAt,
    )
}

/**
 * 一条本地日程 → 班级清单里的条目。
 *
 * 「从我的日程导入」用：把已经建好的条目搬进清单，不用重新敲一遍。
 * id 原样保留（本地 id 是 UUID，不会和别的条目撞）。
 *
 * **普通日程保持日程形态**（带日期和起止时间、地点），
 * 不硬转成截止任务 —— 见 [ClassTaskDto.day] 的说明。
 * 提醒不在清单里存（[ClassTaskDto] 压根没有这个字段）。
 */
fun Agenda.toClassTask(now: Long = System.currentTimeMillis()): ClassTaskDto =
    if (!isTask) {
        ClassTaskDto(
            id = id,
            title = title,
            course = "",
            day = date,
            startMinute = startMinute,
            endMinute = endMinute,
            place = place,
            note = note,
            updatedAt = now,
        )
    } else {
        ClassTaskDto(
            id = id,
            title = title,
            course = "",
            dueAt = deadlineAt,
            note = note,
            updatedAt = now,
        )
    }
