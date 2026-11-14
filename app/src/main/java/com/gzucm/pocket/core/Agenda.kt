package com.gzucm.pocket.core

import kotlinx.serialization.Serializable
import java.time.LocalDate
import java.time.format.DateTimeParseException

/**
 * 考试条目的 `sourceId` 前缀。
 *
 * 考试和日程在渲染层是同一个类型 [AgendaOccurrence]，靠这个前缀分流点击行为：
 * 考试 → 只读详情；日程 → 编辑表单。也在 `agendaColor` 里用来决定配色。
 */
const val EXAM_PREFIX = "exam:"

/** 考试专用色号（[AgendaOccurrence.colorIndex]）。负数，和课程色板下标不冲突 */
const val EXAM_COLOR_INDEX = -2

/**
 * 一条用户自建日程（**规则**，不是展开后的实例）。
 *
 * 和 [CourseSession] 完全分开：教务课表会被同步整表覆盖、被缓存版本失效清空、
 * 被切学段/退出登录清掉，用户手输的数据不能跟着一起消失。
 * 所以这里单独存一份、单独一个 SharedPreferences key（见 AgendaStore）。
 *
 * 时间统一用「当天第几分钟」表达。表单里可以「按节次」或「按时间」输入，
 * 两者最后都换算成 [startMinute] / [endMinute]；[unitStart] / [unitEnd]
 * 只是记下当初选的是哪几节，用来在详情里显示「第 3-4 节」。
 *
 * 字段一律放最后 + 带默认值：老 JSON 里没有的字段反序列化时要能补上
 * （Net.json 配了 ignoreUnknownKeys / isLenient / coerceInputValues）。
 */
@Serializable
data class Agenda(
    val id: String,
    val title: String,
    /** ISO yyyy-MM-dd。单次 = 那一天；每周重复 = 锚点起始日 */
    val date: String,
    val startMinute: Int,
    val endMinute: Int,
    val place: String = "",
    val note: String = "",
    /** -1 = 自动取色；>=0 对应 CoursePalette 下标（颜色解析放在 ui 层，core 不碰 Compose） */
    val colorIndex: Int = -1,
    val weekly: Boolean = false,
    val unitStart: Int = 0,
    val unitEnd: Int = 0,
    /**
     * 提醒档位（分钟，0 = 准时）。空列表 = 不提醒。
     *
     * **可以多选**，同一条日程既「提前 1 天」又「提前 30 分钟」又「准时」——
     * 每个档位各排一个闹钟。之所以做成集合而不是单个 Int：
     * 考试/交材料这类事，前一天提醒一次、当天再提醒一次是真实需求。
     *
     * v1.3.5 里这里叫 `remindMinutesBefore: Int`（-1 = 不提醒）但没做 UI，
     * 线上不可能有值；旧 JSON 里那个 key 会被 Net.json 的 ignoreUnknownKeys 丢掉。
     */
    val remindLeads: List<Int> = emptyList(),
)

/** 一条日程在某一周某一天的实例（纯内存，不落盘） */
data class AgendaOccurrence(
    /** 稳定唯一：规则 id + 日期。日视图 LazyColumn 的 key 直接用它 */
    val key: String,
    val sourceId: String,
    val title: String,
    val date: LocalDate,
    val week: Int,
    val weekday: Int,
    val startMinute: Int,
    val endMinute: Int,
    val place: String,
    val note: String,
    val colorIndex: Int,
    val unitStart: Int,
    val unitEnd: Int,
    val weekly: Boolean,
    /**
     * 座位号。只有考试有（[ExamItem.seat]），自建日程一律空串。
     *
     * 单独一个字段而不是塞进 [note]：note 是「闭卷 / 考查」这类考试类型，
     * 也是用户自己填的备注，两者语义不同；而且卡片上时间/考场那行本来就快写满了，
     * 座位放第二行更容易被省略号吃掉。
     */
    val seat: String = "",
)

object AgendaLogic {

    /** 学期第一天 = 第 1 周周一 */
    fun firstDay(semester: Semester): LocalDate = semester.firstMonday

    /** 学期最后一天 = 第 totalWeeks 周周日。寒暑假不算 */
    fun lastDay(semester: Semester): LocalDate =
        semester.firstMonday.plusDays((semester.totalWeeks.coerceAtLeast(1) * 7L) - 1)

    /**
     * 致辉 2026-11-14 定的范围：**所有日程都框在本学期内，不管寒暑假**。
     * 表单的日期选择器和重复规则展开都以此为准 —— 这样不用处理
     * 「学期变了旧日程该怎么办」「跨学期重复怎么算周次」这类没完没了的边界。
     */
    fun inSemester(semester: Semester, date: LocalDate): Boolean =
        !date.isBefore(firstDay(semester)) && !date.isAfter(lastDay(semester))

    /**
     * 周视图里「新建日程」的默认日期（致辉 2026-11-14 定）。
     *
     * 看的是哪一周，默认值就落在哪一周：
     *   · 正在看本周 → 今天
     *   · 翻到别的周 → 那一周的周一
     *
     * 之前一律取 `LocalDate.now()`，翻到第 12 周点新建、日期还是今天，
     * 得先手动改一遍日期 —— 用户翻周本来就是为了在那一周加东西。
     *
     * 结果夹回学期内：表单只允许学期内日期，给一个必然报错的默认值
     * 等于让用户先改日期才能保存。
     */
    fun defaultDraftDate(
        semester: Semester,
        currentWeek: Int,
        viewingWeek: Int,
        today: LocalDate = LocalDate.now(),
    ): LocalDate {
        val lastWeek = semester.totalWeeks.coerceAtLeast(1)
        val week = viewingWeek.coerceIn(1, lastWeek)
        val anchor = if (week == currentWeek) today else semester.dateOf(week, 1)
        val from = firstDay(semester)
        val to = lastDay(semester)
        return when {
            anchor.isBefore(from) -> from
            anchor.isAfter(to) -> to
            else -> anchor
        }
    }

    /** 第 unit 节的上课 / 下课分钟。节次号非法时给一个安全值 */
    fun minutesOfUnit(unit: Int): Pair<Int, Int> {
        val s = SlotTimes[unit]?.let { timeToMinutes(it) } ?: return 8 * 60 + 30 to 9 * 60 + 10
        val e = SlotEndTimes[unit]?.let { timeToMinutes(it) } ?: (s + 40)
        return s to e
    }

    /** 把分钟格式化回 HH:mm，供表单和详情显示 */
    fun hhmm(minute: Int): String {
        val m = minute.coerceIn(0, 23 * 60 + 59)
        return "%02d:%02d".format(m / 60, m % 60)
    }

    /**
     * 按学期展开成一周一天一条的实例。
     *
     * - 单次：日期在学期内才有 1 条
     * - 每周：从锚点起每 7 天一条，直到学期最后一天；锚点在学期开始前就
     *   顺延到第一个落进学期的同 weekday；锚点在学期结束后 → 不展开
     * - 学期外的规则保留在管理列表里（灰显标注），但不进课表
     *
     * 结果按 (日期, 开始分钟) 排序，key 稳定唯一。
     */
    fun expand(semester: Semester, rules: List<Agenda>): List<AgendaOccurrence> {
        val out = ArrayList<AgendaOccurrence>(rules.size * 4)
        val from = firstDay(semester)
        val to = lastDay(semester)

        for (r in rules) {
            // 手写 try/catch 而不是 runCatching{}.getOrElse{}：
            // break/continue 在 lambda 里（哪怕 inline）都不允许。
            val anchor = try {
                LocalDate.parse(r.date)
            } catch (e: DateTimeParseException) {
                continue
            }
            val start = r.startMinute.coerceIn(0, 1439)
            val end = r.endMinute.coerceIn(start + 1, 1440)

            if (!r.weekly) {
                if (anchor.isBefore(from) || anchor.isAfter(to)) continue
                out += occurrence(r, anchor, start, end, semester)
                continue
            }

            var d = anchor
            // 锚点在开学前：顺延到第一个还落在学期内的同 weekday
            while (d.isBefore(from)) d = d.plusDays(7)
            while (!d.isAfter(to)) {
                out += occurrence(r, d, start, end, semester)
                d = d.plusDays(7)
            }
        }
        return out.sortedWith(compareBy({ it.date }, { it.startMinute }))
    }

    private fun occurrence(
        r: Agenda,
        date: LocalDate,
        start: Int,
        end: Int,
        semester: Semester,
    ) = AgendaOccurrence(
        key = r.id + "|" + date.toString(),
        sourceId = r.id,
        title = r.title,
        date = date,
        week = semester.weekOf(date),
        weekday = date.dayOfWeek.value,
        startMinute = start,
        endMinute = end,
        place = r.place,
        note = r.note,
        colorIndex = r.colorIndex,
        unitStart = r.unitStart,
        unitEnd = r.unitEnd,
        weekly = r.weekly,
    )
}

/**
 * 真实分钟 ↔ 网格行号的互转。
 *
 * 网格是**离散节次行**（每行 58dp 代表「一节 40 分钟 + 课间」），
 * 不是连续时间轴；而日程可以落在任意分钟（12:00 午休、21:30 夜跑）。
 * 这里是分段线性映射：
 *
 *   · 每节上课的 40 分钟 → 该行的 0.0..0.8
 *   · 该节下课到下一节上课（10 分钟课间 / 20 分钟大课间 / 80 分钟午休）→ 0.8..1.0
 *
 * 也就是说所有空档统一压成 0.2 行，不新增行、不改变 [ScheduleScreen] 的
 * `slots` 行集合 —— 否则一条晚间日程会把整学期的网格都撑高。
 * 代价是午休在视觉上被压缩（网格本来就没给午休留位置），可接受。
 *
 * slots 是网格实际画出来的节次列表（按整学期教务课裁剪，可能不是 1..14 全集），
 * 所以两个函数都只依赖传入的 slots，不假设节次连续。
 */
object AgendaGeometry {

    private const val CLASS_PART = 0.8f

    fun minuteToRow(minute: Int, slots: List<Int>): Float {
        if (slots.isEmpty()) return 0f
        val firstStart = AgendaLogic.minutesOfUnit(slots.first()).first
        if (minute <= firstStart) return 0f

        for (i in slots.indices) {
            val (s, e) = AgendaLogic.minutesOfUnit(slots[i])
            val nextStart = if (i + 1 < slots.size) {
                AgendaLogic.minutesOfUnit(slots[i + 1]).first
            } else {
                e + 10
            }
            val base = i.toFloat()
            when {
                minute < s -> return base
                minute <= e -> {
                    val len = (e - s).coerceAtLeast(1)
                    return base + CLASS_PART * (minute - s) / len
                }
                minute < nextStart -> {
                    val gap = (nextStart - e).coerceAtLeast(1)
                    return base + CLASS_PART + (1f - CLASS_PART) * (minute - e) / gap
                }
            }
        }
        return slots.size.toFloat()
    }

    /** 长按空白格时把 y 反推成分钟，预填新建表单 */
    fun rowToMinute(row: Float, slots: List<Int>): Int {
        if (slots.isEmpty()) return 8 * 60 + 30
        val i = row.toInt().coerceIn(0, slots.size - 1)
        val (s, e) = AgendaLogic.minutesOfUnit(slots[i])
        val frac = (row - i).coerceIn(0f, 1f)
        return if (frac <= CLASS_PART) {
            s + ((frac / CLASS_PART) * (e - s)).toInt()
        } else {
            val nextStart = if (i + 1 < slots.size) {
                AgendaLogic.minutesOfUnit(slots[i + 1]).first
            } else {
                e + 10
            }
            e + (((frac - CLASS_PART) / (1f - CLASS_PART)) * (nextStart - e)).toInt()
        }.coerceIn(0, 1439)
    }
}

/**
 * 日视图 / 今日页要按真实时间把课程和日程混排，又不能把日程伪装成
 * [CourseSession]（那会污染提醒排程和重叠分簇）。用一个联合类型在 UI 层区分。
 */
sealed interface ScheduleEntry {
    val startMinute: Int
    val endMinute: Int
    val stableKey: String

    data class Course(val session: CourseSession) : ScheduleEntry {
        override val startMinute: Int get() = startMinutesOf(session)
        override val endMinute: Int get() = endMinutesOf(session)
        override val stableKey: String get() =
            "c|${session.week}|${session.weekday}|${session.startUnit}|${session.endUnit}|${session.name}|${session.room}"
    }

    data class Custom(val occurrence: AgendaOccurrence) : ScheduleEntry {
        override val startMinute: Int get() = occurrence.startMinute
        override val endMinute: Int get() = occurrence.endMinute
        override val stableKey: String get() = "a|" + occurrence.key
    }
}

/** 把某一天的课程与日程合成一个按开始时间排序的列表 */
fun mergeForDay(
    sessions: List<CourseSession>,
    occurrences: List<AgendaOccurrence>,
    week: Int,
    weekday: Int,
): List<ScheduleEntry> {
    val out = ArrayList<ScheduleEntry>(sessions.size + occurrences.size)
    sessions.forEach { if (it.week == week && it.weekday == weekday) out += ScheduleEntry.Course(it) }
    occurrences.forEach { if (it.week == week && it.weekday == weekday) out += ScheduleEntry.Custom(it) }
    return out.sortedWith(compareBy({ it.startMinute }, { it.stableKey }))
}
