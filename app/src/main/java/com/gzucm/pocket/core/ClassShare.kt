package com.gzucm.pocket.core

import kotlinx.serialization.Serializable
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * 班级作业共享串。
 *
 * ## 为什么先是「分享串」而不是云端
 *
 * 需求是「学委建一份 DDL，全班能看到」。做云端要先解决三件事：谁来托管、
 * 怎么防别人删光、服务器谁维护。而这个项目此前一条后端都没有（见 [Feedback]
 * 那段「为什么不维护服务器」）。
 *
 * 所以第一版走**分享串**：学委在 App 里把截止任务打成一行字，发到班级群，
 * 同学粘进来就合并。零服务器、零成本、不涉及任何人的隐私。
 * 代价是学委加了新作业要重发一次 —— 但对一学期几十条 DDL 来说够用，
 * 等确认班里真有人天天看，再接云端（那时代码不用推翻，只是多一个「拉取」）。
 *
 * ## 编码跟课表分享串同一套
 *
 * `GZP3:` + raw deflate + Base64URL（去掉 `=`）。
 * 理由和课表那边完全一样（见 [encodeTimetableToken] 的注释）：
 * 微信能直接发、输入法不会吃掉字符、`+` `/` 不会在链接识别里出岔子。
 *
 * 前缀必须和 `GZP2` 不同：用户可能把两种串先后粘进同一个输入框，
 * 认错了就是「课表串解析失败」，还查不出为什么。
 */
const val CLASS_TOKEN_PREFIX = "GZP3:"

/** 一条班级作业。字段短名是为了压低分享串长度，反正只有这个文件读写它 */
@Serializable
data class ClassTaskDto(
    /** 学委那边的稳定 id。合并靠它认人，不能用标题当键（同名的 DDL 很常见） */
    val id: String,
    val title: String,
    /** 截止时间（epoch millis）。null = 长期待办 */
    val dueAt: Long? = null,
    val note: String = "",
    /** 这一条的修改时间。合并时只认更新的那份，避免旧串把新内容盖回去 */
    val updatedAt: Long,
)

/** 一个班级的一份 DDL 清单 */
@Serializable
data class ClassDoc(
    /** 班级名 / 学委名字。**同时是这串数据属于谁的唯一标识** */
    val name: String,
    val tasks: List<ClassTaskDto>,
    val v: Int = 1,
)

/** 导入结果，直接拿去拼一句人话给用户看 */
data class ClassImportResult(
    val added: Int,
    val updated: Int,
    val unchanged: Int,
    /** 本地已勾完成、这次远程改动没有把完成状态抹掉的条数 */
    val keptDone: Int,
) {
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
    }
}

// ── 编解码 ────────────────────────────────────────────────────────────

fun encodeClassToken(doc: ClassDoc): String {
    val json = Net.json.encodeToString(ClassDoc.serializer(), doc)
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
fun decodeClassToken(token: String): ClassDoc {
    val text = token.trim()
    require(text.isNotEmpty()) { "先复制同学发你的那串字，再来点导入" }
    require(text.startsWith(CLASS_TOKEN_PREFIX)) {
        if (text.startsWith(EXPORT_TOKEN_PREFIX)) {
            "这是课表的分享串，不是班级作业的"
        } else {
            "这不像班级作业的分享串，可能没复制全"
        }
    }

    val b64 = text.removePrefix(CLASS_TOKEN_PREFIX).trim()
    val packed = runCatching { java.util.Base64.getUrlDecoder().decode(b64) }
        .getOrElse { throw IllegalArgumentException("分享串被截断了，让对方重新发一次") }

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
    }.getOrElse { throw IllegalArgumentException("分享串解不开，让对方重新发一次") }

    val doc = runCatching { Net.json.decodeFromString(ClassDoc.serializer(), json) }
        .getOrElse { throw IllegalArgumentException("分享串格式对不上，可能来自更新的版本") }

    require(doc.tasks.isNotEmpty()) { "这串里没有作业，让对方重新生成一次" }
    return doc
}

// ── 导入 / 导出 ───────────────────────────────────────────────────────

/** 导入进来的任务 id 前缀。带班级名，避免两个班的同名作业互相覆盖 */
private fun taskId(className: String, remoteId: String) = "cls:$className:$remoteId"

/** `origin` 的取值：本地建的是空串 */
fun classOrigin(className: String) = "class:$className"

/** 这条任务是哪个班导进来的；本地建的回空串 */
fun Agenda.originClass(): String =
    if (origin.startsWith("class:")) origin.removePrefix("class:") else ""

/**
 * 把全班 DDL 合并进本地列表。
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
    doc: ClassDoc,
    today: LocalDate = LocalDate.now(),
): Pair<List<Agenda>, ClassImportResult> {
    val list = existing.toMutableList()
    val indexById = existing.withIndex().associate { (i, a) -> a.id to i }.toMutableMap()

    var added = 0
    var updated = 0
    var unchanged = 0
    var keptDone = 0

    doc.tasks.forEach { dto ->
        val incoming = dto.toAgenda(doc.name, today)
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
    return list to ClassImportResult(added, updated, unchanged, keptDone)
}

/**
 * 把本地截止任务打包成分享串要的内容。
 *
 * 只导**未完成**的任务：已勾掉的作业对同学没有意义，全带上只会让串变长、
 * 让同学那边多出一堆已经做完的东西。
 *
 * 本地建的任务没有 `remoteUpdatedAt`，用 [now] 兜底 —— 它第一次被分享出去时，
 * 就是这条任务在远程世界里的「出生时间」。
 */
fun buildClassDoc(
    className: String,
    all: List<Agenda>,
    now: Long = System.currentTimeMillis(),
): ClassDoc {
    val tasks = all
        .filter { it.isTask && it.doneAt == null }
        .map { a ->
            ClassTaskDto(
                // 二次分享时 id 已经带 `cls:班级:` 前缀，这里剥掉，不然会层层嵌套
                id = if (a.id.startsWith("cls:")) a.id.substringAfterLast(':') else a.id,
                title = a.title,
                dueAt = a.deadlineAt,
                note = a.note,
                updatedAt = if (a.remoteUpdatedAt > 0L) a.remoteUpdatedAt else now,
            )
        }
    return ClassDoc(name = className, tasks = tasks)
}

/**
 * 一条远程 DDL → 本地截止任务。
 *
 * 没填截止时间的按「长期待办」处理（`deadlineAt = null`）——
 * 和本地建的长期待办语义一致，首页会把它排在最后而不是标逾期。
 */
private fun ClassTaskDto.toAgenda(className: String, today: LocalDate): Agenda {
    val day = dueAt?.let {
        Instant.ofEpochMilli(it).atZone(ZoneId.systemDefault()).toLocalDate()
    } ?: today
    return Agenda(
        id = taskId(className, id),
        title = title,
        date = day.toString(),
        // 截止任务不占时段，这两个值只用来通过校验
        startMinute = 23 * 60 + 59,
        endMinute = 23 * 60 + 59,
        note = note,
        isTask = true,
        deadlineAt = dueAt,
        origin = classOrigin(className),
        remoteUpdatedAt = updatedAt,
    )
}
