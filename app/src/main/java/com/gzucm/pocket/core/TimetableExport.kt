package com.gzucm.pocket.core

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter

/**
 * 课表结构化导出（schemaVersion 1）。
 *
 * ## 为什么要有它
 *
 * 致辉要做一个网页导入课表（2026-11-18）。App 内部的数据不适合直接给外部用：
 * `CourseSession` 是**按周展开**的（18 周 × 25 门 = 450 条，同一门课重复 18 次），
 * 而且字段里混着教务的原始值。所以这里做一层「导出视图」：
 *
 * - [courses] = 按周次合并后的课程模板（同一门课同一时段，18 周只占一条，`weeks` 里列周次）
 * - [contents] = 逐周上课内容，**只收录标了内容的周** —— 其余字段都能从模板和
 *   `firstMonday` 推出来，铺开整张明细表只会让分享串长到微信发不出去
 * - [slots] = 1~14 节的作息表，网页画网格不用自己猜「第 3 节几点」
 * - [coverage] = 数据完整性（App 会先抓本周给首屏、其余后台补，可能只有部分周）
 *
 * ## 口径（网页端必须知道）
 *
 * - `weekday`：**1 = 周一 … 7 = 周日**。教务接口是周日开头，同步时已经转换，
 *   外部直接用，不要再减一。
 * - `startUnit` / `endUnit`：1~14 的连续节次编号。
 * - 时间一律 `HH:mm` 字符串（本地时区），不做时区换算。
 * - 日程、考试**不在 v1 范围内**（致辉 2026-11-18 拍板只导课程）。
 *   以后要加就升 `schemaVersion`，不要原地塞字段。
 */
@Serializable
data class TimetableExport(
    val schemaVersion: Int,
    /**
     * 固定标识，方便网页端一眼认出「这是广中医口袋导的文件」，
     * 别拿别的 JSON 直接喂进去。
     */
    val kind: String,
    /** 导出时刻，带时区偏移，如 `2026-11-18T00:30:00+08:00` */
    val exportedAt: String,
    val app: ExportApp,
    val semester: ExportSemester,
    val coverage: ExportCoverage,
    val slots: List<ExportSlot>,
    val courses: List<ExportCourse>,
    /**
     * 逐周上课内容。**默认不导出**（致辉 2026-11-18：分享串要能发微信）。
     * 空列表在紧凑 JSON 里会被整段省掉，一个字符都不占。
     */
    val contents: List<ExportContent> = emptyList(),
) {
    companion object {
        const val SCHEMA_VERSION = 1
        const val KIND = "gzucm-pocket-timetable"
    }
}

@Serializable
data class ExportApp(
    val name: String = "广中医口袋",
    val versionName: String,
    val versionCode: Int,
)

@Serializable
data class ExportSemester(
    val code: String,
    val name: String,
    /** 第 1 周周一，ISO `yyyy-MM-dd`。第 N 周第 W 天 = firstMonday + (N-1)*7 + (W-1) 天 */
    val firstMonday: String,
    val totalWeeks: Int,
)

/**
 * 数据完整性。
 *
 * App 首屏只抓本周，其余周在后台逐周补（见 AppViewModel 的逐周加载）。用户在补完
 * 之前导出，文件里就会缺周且**看不出来** —— 所以把这件事写进文件本身，
 * 网页端可以据此提示「数据只到第 8 周」。
 */
@Serializable
data class ExportCoverage(
    val complete: Boolean,
    /** 文件实际覆盖到的周次（升序） */
    val weeks: List<Int>,
)

@Serializable
data class ExportSlot(
    val unit: Int,
    val start: String,
    val end: String,
)

@Serializable
data class ExportCourse(
    /** 模板 id，`c1` 起。明细用它回指 */
    val id: String,
    val name: String,
    val weekday: Int,
    val startUnit: Int,
    val endUnit: Int,
    /** 这门课出现的周次，升序去重 */
    val weeks: List<Int>,
    /*
     * 下面这些都可能有空值，统一给空串默认：紧凑 JSON 配了 encodeDefaults = false，
     * 空值直接不写进文件 —— 一条课省 20 来个字符，二三十门就是几百个。
     * 网页端读不到就当空串处理。
     */
    val code: String = "",
    val teachers: String = "",
    val startTime: String = "",
    val endTime: String = "",
    val room: String = "",
    val category: String = "",
    /** 教学模式（PBL / 讲授…），可能为空 */
    val teachingMode: String = "",
    /** 学校配色，如 `#5FB878`，可能为空 */
    val schoolColor: String = "",
)

/**
 * 逐周上课内容。
 *
 * **不再逐周铺开整张明细表**（2026-11-18 改）：450 条明细里有三样东西是冗余的 ——
 * 星期/节次/教室在模板里、日期由 `semester.firstMonday` 推得、而 `content` 大多数周是空的。
 * 全铺出来光分享串就 3500 字符，微信发不出去；只留有内容的周之后只剩几十条。
 *
 * 网页端要「某周某门课上什么」，就按 `courseId + week` 在这张表里查，查不到就是没标内容。
 */
@Serializable
data class ExportContent(
    /** 指回 [ExportCourse.id] */
    val courseId: String,
    val week: Int,
    val content: String,
)

/** 导出用的 JSON 配置：缩进两格，人眼能看，网页端 `JSON.parse` 也照吃 */
val TimetableExportJson: Json = Json {
    prettyPrint = true
    prettyPrintIndent = "  "
    encodeDefaults = true
}

/**
 * 分享串前缀。
 *
 * 微信单条文本有字数上限（约 2000），完整 JSON 发不出去，所以对外分享用
 * 「紧凑 JSON → raw deflate → Base64URL」的一行串。
 *
 * **GZP2 是 2026-11-18 换的编码**：GZP1 是把 [TimetableExport] 原样压一遍，
 * 遇到见习轮转（同一门课每周换科室/带教老师）会炸成 151 条模板 → 5100 字符，
 * 根本发不出去。GZP2 改成「名字/教室字典 + 按 (课名, 星期, 起止节) 分组」，
 * 同一条课的不同周只在叶子里留一行，实测同一份数据 5124 → 1719 字符。
 *
 * 前缀同时是版本号，网页端先看前缀再决定用哪套解码。
 */
const val EXPORT_TOKEN_PREFIX = "GZP2:"

/**
 * 生成可分享的一行串。
 *
 * 编码分三层，每层都在解决上一版的具体问题：
 *
 * 1. **字典化**：课名只有 14 个、教室 29 个，却被 151 条模板各写一遍。
 *    提到 `names` / `rooms` 两个数组里，行内只留下标。
 * 2. **分组**：GZP1 的「模板」粒度是 (课名, 星期, 节次, 教室, 教师…) 全等才合并，
 *    见习轮转一周一个科室就一周一条。GZP2 只按 (课名, 星期, 起止节) 分组，
 *    教室/周次退化成叶子 `["4", 3]`（第 4 周 → 教室 3 号）。
 * 3. **周次写区间**：`[4]` → `"4"`，`[5,6]` → `"5-6"`，`[1,3,5]` → `"1,3,5"`。
 *
 * 为什么是 raw deflate 而不是 gzip：少 18 字节头尾，浏览器
 * `new DecompressionStream('deflate-raw')` 直接可用，不需要额外库。
 * Base64 用 URL_SAFE + 去掉 `=`：避免 `+` `/` 在某些输入法/链接识别里出岔子。
 */
fun encodeTimetableToken(doc: TimetableExport): String {
    val json = buildCompactJson(doc).toString()
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

    val b64 = java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(out.toByteArray())
    return EXPORT_TOKEN_PREFIX + b64
}

/**
 * 紧凑 JSON 的形状（GZP2）：
 *
 * ```json
 * {
 *   "sem": ["2026-09-07", 20],          // 第 1 周周一、总周数
 *   "cov": "1-20",                      // 实际覆盖到的周（缺周时网页能提示）
 *   "slots": ["08:30-09:10", ...],      // 1~14 节作息
 *   "names": ["外科学(2)", ...],         // 课名字典
 *   "rooms": ["综教楼820（智慧课室）", ...], // 教室字典
 *   "courses": [ [名字下标, 星期, 起节, 止节, [["4", 教室下标], ["5-6", 教室下标]]], ... ]
 * }
 * ```
 *
 * 刻意**不带**教师/类别/教学方式/配色/课程代码：教师有 115 个各不相同，
 * 光这一列就要 1400 多字符，带上必然发不出微信（实测 1719 → 3180）。
 * 要这些字段请用「导出 JSON 文件」，那边是完整版。
 */
private fun buildCompactJson(doc: TimetableExport): JsonObject {
    val names = ArrayList<String>()
    val nameIdx = HashMap<String, Int>()
    val rooms = ArrayList<String>()
    val roomIdx = HashMap<String, Int>()

    fun nameOf(s: String): Int = nameIdx.getOrPut(s) { names.add(s); names.size - 1 }
    fun roomOf(s: String): Int = roomIdx.getOrPut(s) { rooms.add(s); rooms.size - 1 }

    /*
     * 分组键不含教室 —— 这是 GZP2 和 GZP1 的根本差别。
     * 见习轮转一周一个科室，含教室分组就是一周一条（实测 151 条 → 5124 字符）。
     */
    val groups = LinkedHashMap<String, MutableList<JsonArray>>()
    doc.courses.forEach { c ->
        // 先登记名字，后面 key 里直接用下标；names 的顺序 = 首次出现顺序（doc 已排序，稳定）
        val n = nameOf(c.name)
        val key = "$n\u0001${c.weekday}\u0001${c.startUnit}\u0001${c.endUnit}"
        groups.getOrPut(key) { mutableListOf() }.add(
            buildJsonArray {
                add(weeksToCompact(c.weeks))
                add(roomOf(c.room))
            },
        )
    }

    return buildJsonObject {
        /*
         * 不写 `v` / `kind`：前缀 GZP2 已经表明格式和版本，kind 只是给**文件**用的
         * 识别标记。这两个键在串里要占三十来个字符，不值当。
         */
        put("sem", buildJsonArray {
            add(doc.semester.firstMonday)
            add(doc.semester.totalWeeks)
        })
        put("cov", weeksToCompact(doc.coverage.weeks))
        put("slots", buildJsonArray { doc.slots.forEach { add("${it.start}-${it.end}") } })
        put("names", buildJsonArray { names.forEach { add(it) } })
        put("rooms", buildJsonArray { rooms.forEach { add(it) } })
        put("courses", buildJsonArray {
            groups.forEach { (key, cells) ->
                val parts = key.split('\u0001')
                add(buildJsonArray {
                    add(parts[0].toInt())
                    add(parts[1].toInt())
                    add(parts[2].toInt())
                    add(parts[3].toInt())
                    add(JsonArray(cells))
                })
            }
        })
    }
}

/**
 * 周次列表 → 区间写法。`[4]`→`"4"`，`[5,6]`→`"5-6"`，`[1,3,5]`→`"1,3,5"`。
 *
 * 为什么值得单独写：18 周全是 `1,2,3,…,18` 时，区间写法 5 个字符搞定，
 * 逗号写法则要 50 个 —— 二三十门课就是上千字符。
 */
internal fun weeksToCompact(weeks: List<Int>): String {
    if (weeks.isEmpty()) return ""
    val sorted = weeks.distinct().sorted()
    val sb = StringBuilder()
    var i = 0
    while (i < sorted.size) {
        var j = i
        while (j + 1 < sorted.size && sorted[j + 1] == sorted[j] + 1) j++
        if (sb.isNotEmpty()) sb.append(',')
        sb.append(sorted[i])
        if (j > i) sb.append('-').append(sorted[j])
        i = j + 1
    }
    return sb.toString()
}

/** [weeksToCompact] 的逆运算。空串 → 空列表 */
internal fun compactToWeeks(s: String): List<Int> {
    if (s.isBlank()) return emptyList()
    return s.split(',').flatMap { part ->
        val dash = part.indexOf('-')
        if (dash < 0) {
            listOfNotNull(part.toIntOrNull())
        } else {
            val a = part.substring(0, dash).toIntOrNull()
            val b = part.substring(dash + 1).toIntOrNull()
            if (a == null || b == null) emptyList() else (a..b).toList()
        }
    }
}

/**
 * 把分享串解回 [TimetableExport]（文件版形状），网页端的 JS 展开逻辑以它为准。
 *
 * 用途有两个：
 * 1. 单测里证明「编出来的串能还原成同一份课表」—— 格式的唯一验伪手段；
 * 2. 以后做「从分享串导入」时，解码与展开已经在这儿了。
 *
 * 注意：串里本来就没有教师/类别/教学方式/配色/课程代码，
 * 展开后这些字段是空串 —— 不是 bug，是编码时的取舍。
 */
fun decodeTimetableToken(token: String): TimetableExport {
    require(token.startsWith(EXPORT_TOKEN_PREFIX)) { "不是广中医口袋的课表分享串" }
    val b64 = token.removePrefix(EXPORT_TOKEN_PREFIX)
    val packed = java.util.Base64.getUrlDecoder().decode(b64)

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

    val root = Json.parseToJsonElement(out.toByteArray().toString(Charsets.UTF_8))
        .jsonObject
    return expandCompact(root)
}

/** [buildCompactJson] 的逆运算：字典查回字符串，叶子拆回 [ExportCourse] 列表 */
private fun expandCompact(root: JsonObject): TimetableExport {
    val names = root.getValue("names").jsonArray.map { it.jsonPrimitive.content }
    val rooms = root.getValue("rooms").jsonArray.map { it.jsonPrimitive.content }
    val slots = root.getValue("slots").jsonArray.map { it.jsonPrimitive.content }
    val sem = root.getValue("sem").jsonArray
    val totalWeeks = sem[1].jsonPrimitive.int

    /*
     * 一个分组里的叶子可能指向不同教室（见习换科室），而文件版的一条课程
     * 只有一个教室 —— 所以要按教室再拆一次，把同教室的周次合并。
     */
    val courses = ArrayList<ExportCourse>()
    root.getValue("courses").jsonArray.forEach { el ->
        val row = el.jsonArray
        val name = names[row[0].jsonPrimitive.int]
        val weekday = row[1].jsonPrimitive.int
        val startUnit = row[2].jsonPrimitive.int
        val endUnit = row[3].jsonPrimitive.int
        val byRoom = LinkedHashMap<String, MutableList<Int>>()
        row[4].jsonArray.forEach { cellEl ->
            val cell = cellEl.jsonArray
            val weeks = compactToWeeks(cell[0].jsonPrimitive.content)
            val room = rooms[cell[1].jsonPrimitive.int]
            byRoom.getOrPut(room) { mutableListOf() }.addAll(weeks)
        }
        byRoom.forEach { (room, weeks) ->
            courses.add(
                ExportCourse(
                    id = "c${courses.size + 1}",
                    name = name,
                    weekday = weekday,
                    startUnit = startUnit,
                    endUnit = endUnit,
                    weeks = weeks.distinct().sorted(),
                    room = room,
                ),
            )
        }
    }

    val covered = compactToWeeks(root["cov"]?.jsonPrimitive?.content.orEmpty())
    return TimetableExport(
        schemaVersion = TimetableExport.SCHEMA_VERSION,
        kind = root["kind"]?.jsonPrimitive?.content ?: TimetableExport.KIND,        // 串里不存导出时刻与版本号：它们对渲染没用，占字符
        exportedAt = "",
        app = ExportApp(versionName = "", versionCode = 0),
        semester = ExportSemester(
            code = "",
            name = "",
            firstMonday = sem[0].jsonPrimitive.content,
            totalWeeks = totalWeeks,
        ),
        coverage = ExportCoverage(
            complete = covered.size >= totalWeeks,
            weeks = covered,
        ),
        slots = slots.mapIndexed { i, s ->
            val dash = s.indexOf('-')
            ExportSlot(
                unit = i + 1,
                start = if (dash < 0) s else s.substring(0, dash),
                end = if (dash < 0) "" else s.substring(dash + 1),
            )
        },
        courses = courses,
    )
}

/** 分组键：这些字段全一样才合并成一条模板 */
private data class CourseKey(
    val name: String,
    val code: String,
    val teachers: String,
    val weekday: Int,
    val startUnit: Int,
    val endUnit: Int,
    val startTime: String,
    val endTime: String,
    val room: String,
    val category: String,
    val teachingMode: String,
    val schoolColor: String,
)

/** 一条上课记录 → 它的模板键。取模板和拼明细两条路都走这里，避免两处写歪 */
private fun courseKeyOf(s: CourseSession) = CourseKey(
    name = s.name,
    code = s.code,
    teachers = s.teachers,
    weekday = s.weekday,
    startUnit = s.startUnit,
    endUnit = s.endUnit,
    startTime = s.startTime,
    endTime = s.endTime,
    room = s.room,
    category = s.category,
    teachingMode = s.teachingMode,
    schoolColor = s.schoolColor,
)

/**
 * 把 App 内部的按周数据整理成导出文档。纯函数，不碰 Android，可单测。
 *
 * @param loadedWeeks 已加载的周次（来自 UiState）。空集合时不判完整性 —— 见 [ExportCoverage]
 * @param now 注入时间，测试里固定
 */
fun buildTimetableExport(
    semester: Semester,
    sessions: List<CourseSession>,
    loadedWeeks: Set<Int> = emptySet(),
    versionName: String,
    versionCode: Int,
    now: OffsetDateTime = OffsetDateTime.now(),
    /**
     * 是否带逐周上课内容。
     *
     * 默认 false：分享的是**课表**，别人要看的是「哪天几点在哪上课」；
     * 上课内容属于个人学习记录，而且它是分享串里唯一按周增长的部分
     * （教务有内容的周可能有几十上百条），带上就很难塞进微信一条消息。
     * 想带上就传 true，格式本身支持。
     */
    includeContents: Boolean = false,
): TimetableExport {
    val groups = LinkedHashMap<CourseKey, MutableList<CourseSession>>()
    sessions.forEach { s ->
        groups.getOrPut(courseKeyOf(s)) { mutableListOf() }.add(s)
    }

    /*
     * 排序后再发 id：这样同一份数据每次导出的 id 都一样，
     * 网页端把文件存进 localStorage 之后对比新旧版本不会全变。
     */
    val sorted = groups.entries.sortedWith(
        compareBy({ it.key.weekday }, { it.key.startUnit }, { it.key.name }, { it.key.room }),
    )

    val courses = sorted.mapIndexed { index, (key, list) ->
        ExportCourse(
            id = "c${index + 1}",
            name = key.name,
            code = key.code,
            teachers = key.teachers,
            weekday = key.weekday,
            startUnit = key.startUnit,
            endUnit = key.endUnit,
            startTime = key.startTime,
            endTime = key.endTime,
            room = key.room,
            category = key.category,
            teachingMode = key.teachingMode,
            schoolColor = key.schoolColor,
            weeks = list.map { it.week }.distinct().sorted(),
        )
    }

    val idOf = sorted.withIndex().associate { (i, entry) -> entry.key to "c${i + 1}" }
    /*
     * 只留「标了内容」的周。
     *
     * 没内容的周不看也全是空的，铺出来只是给分享串加长；网页想遍历所有实例，
     * 用 courses × weeks 就能展开。排序是为了同一份数据每次导出的字节都稳定。
     */
    val contents = if (!includeContents) {
        emptyList()
    } else {
        sessions
            .filter { it.content.isNotBlank() }
            .sortedWith(compareBy({ it.week }, { it.weekday }, { it.startUnit }, { it.name }))
            .map { s ->
                ExportContent(
                    courseId = idOf.getValue(courseKeyOf(s)),
                    week = s.week,
                    content = s.content,
                )
            }
    }

    // 实际覆盖的周次：优先信 loadedWeeks，没有就按数据里出现过的周算
    val weeks = (if (loadedWeeks.isNotEmpty()) loadedWeeks else sessions.map { it.week }.toSet())
        .filter { it in 1..semester.totalWeeks }
        .sorted()

    return TimetableExport(
        schemaVersion = TimetableExport.SCHEMA_VERSION,
        kind = TimetableExport.KIND,
        // 秒级就够：纳秒会让「同一份数据导两次」看起来不一样，对比时添乱
        exportedAt = now.withNano(0).format(DateTimeFormatter.ISO_OFFSET_DATE_TIME),
        app = ExportApp(versionName = versionName, versionCode = versionCode),
        semester = ExportSemester(
            code = semester.code,
            name = semester.name,
            firstMonday = semester.firstMondayIso,
            totalWeeks = semester.totalWeeks,
        ),
        coverage = ExportCoverage(
            complete = weeks.size >= semester.totalWeeks,
            weeks = weeks,
        ),
        slots = SlotTimes.keys.sorted().map { unit ->
            ExportSlot(
                unit = unit,
                start = SlotTimes.getValue(unit),
                end = SlotEndTimes[unit].orEmpty(),
            )
        },
        courses = courses,
        contents = contents,
    )
}
