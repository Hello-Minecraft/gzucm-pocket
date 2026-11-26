package com.gzucm.pocket.widget

import com.gzucm.pocket.core.SlotEndTimes
import com.gzucm.pocket.core.SlotTimes
import com.gzucm.pocket.core.timeToMinutes
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.temporal.ChronoUnit

/**
 * 一节课，只带渲染要用的字段。
 *
 * 时间和节次两套坐标都留着：显示用时间，判断「正在上」优先用时间
 * （教务给的时间才是真的，节次表只是兜底）。
 */
data class WidgetCourse(
    val name: String,
    val room: String,
    val startTime: String,
    val endTime: String,
    val startUnit: Int,
    val endUnit: Int,
) {
    /** 显示用的上课时间。数据里没有就按节次表补 —— 和 App 内 `timesOf()` 同一口径 */
    val startText: String get() = startTime.ifBlank { SlotTimes[startUnit].orEmpty() }

    /** 显示用的下课时间 */
    val endText: String get() = endTime.ifBlank { SlotEndTimes[endUnit].orEmpty() }

    /** 当天第几分钟。解析不出来时给 MAX，效果是「比任何时刻都晚」 */
    val startMinute: Int get() = timeToMinutes(startText)

    val endMinute: Int get() = timeToMinutes(endText).let {
        // 时间缺失时 timeToMinutes 返回 MAX，会把这节课判成「永远正在上」。
        // 退回「上课 + 40 分钟」（广中医一节课的长度）。
        if (it == Int.MAX_VALUE) startMinute + 40 else it
    }
}

/** 4x2 横排：最多 max 块 + 「还有 N 节」 */
data class HorizontalToday(
    val courses: List<WidgetCourse>,
    val overflow: Int,
    val total: Int,
)

/** 时间轴的一行 */
data class TimelineRow(
    val unit: Int,
    /** 这一节的开始时间，如 "10:20" */
    val time: String,
    /** 覆盖这一节的课；空节为 null */
    val course: WidgetCourse?,
    /** 这节课是否从这一节开始（只有起始行显示课程名） */
    val isStart: Boolean,
    /** 此刻正在上 */
    val ongoing: Boolean,
)

/** 三天视图的一列 */
data class DayColumn(
    val label: String,
    val dateText: String,
    val courses: List<WidgetCourse>,
    val overflow: Int,
    val isToday: Boolean,
)

/**
 * 部件渲染的**纯函数**层：快照 + 时间 → 要显示的内容。
 *
 * 全部逻辑都在这里，Provider 只负责把结果塞进 RemoteViews。
 * 这样做的好处是「几点显示什么」可以脱离 Android 直接跑单测 ——
 * 部件最怕的 bug 是「周三早上八点显示成周二的课」，
 * 而这种 bug 只有把时间当参数传进来才测得出来。
 */
object WidgetRender {

    /** 一次都没同步过课表 */
    const val EmptyText = "打开 App 同步课表"

    /** 今天（含之后）没有课了 */
    const val NoMoreText = "今天没课了"

    private val WEEKDAY_NAMES = arrayOf("周一", "周二", "周三", "周四", "周五", "周六", "周日")

    /**
     * 这一天属于第几周。
     *
     * 口径和 [com.gzucm.pocket.core.Semester.weekOf] 完全一致（周一起算、不足一周按第 1 周），
     * 学期开始前 / 结束后返回 null —— 渲染时按「没课」处理。
     */
    fun weekOf(snapshot: WidgetSnapshotData, date: LocalDate): Int? {
        val start = runCatching { LocalDate.parse(snapshot.semesterStart) }.getOrNull() ?: return null
        if (snapshot.totalWeeks <= 0) return null
        val days = ChronoUnit.DAYS.between(start, date)
        if (days < 0) return null
        val week = (days / 7).toInt() + 1
        return if (week <= snapshot.totalWeeks) week else null
    }

    /**
     * 某一天的全部课程，按节次排序。
     *
     * 这里就是「存整学期、渲染时切片」的核心：快照里没有「今天」的概念，
     * 每次渲染都拿传入的日期现算周次和星期。
     */
    fun coursesOn(snapshot: WidgetSnapshotData, date: LocalDate): List<WidgetCourse> {
        val week = weekOf(snapshot, date) ?: return emptyList()
        val weekday = date.dayOfWeek.value
        return snapshot.sessions.asSequence()
            .filter { it.week == week && it.weekday == weekday && it.startUnit > 0 }
            .sortedWith(compareBy({ it.startUnit }, { it.endUnit }, { it.name }))
            .map {
                WidgetCourse(
                    name = it.name,
                    room = it.room,
                    startTime = it.startTime,
                    endTime = it.endTime,
                    startUnit = it.startUnit,
                    endUnit = it.endUnit,
                )
            }
            // 教务偶尔同一节课给两条（合班/分班各算一次），只留一条
            .distinctBy { "${it.name}|${it.startUnit}|${it.endUnit}|${it.startText}" }
            .toList()
    }

    /** 表头：「第 12 周 · 11月26日 周三」 */
    fun header(snapshot: WidgetSnapshotData?, date: LocalDate): String {
        val day = "${date.monthValue}月${date.dayOfMonth}日 ${WEEKDAY_NAMES[date.dayOfWeek.value - 1]}"
        val week = snapshot?.let { weekOf(it, date) } ?: return day
        return "第 $week 周 · $day"
    }

    /**
     * 空态该说哪句话。
     *
     * 区分「从没同步过」和「今天没课」很重要：前者要用户去 App 里操作，
     * 后者什么都不用做。混成一句会让新用户以为部件坏了。
     */
    fun emptyText(snapshot: WidgetSnapshotData?): String =
        if (snapshot == null || snapshot.sessions.isEmpty()) EmptyText else NoMoreText

    /**
     * 2x1 那一行字。
     *
     *   正在上课 → 「中医诊断学 · 至 09:40」
     *   还有下一节 → 「下一节 10:00 中药学」
     *   今天没了 / 今天本来就没课 / 学期外 → 「今天没课了」
     *   从没同步过 → 「打开 App 同步课表」
     */
    fun currentLine(snapshot: WidgetSnapshotData?, now: LocalDateTime): String {
        if (snapshot == null || snapshot.sessions.isEmpty()) return EmptyText
        val today = coursesOn(snapshot, now.toLocalDate())
        val nowMinute = now.hour * 60 + now.minute

        val ongoing = today.firstOrNull { nowMinute >= it.startMinute && nowMinute < it.endMinute }
        if (ongoing != null) return "${ongoing.name} · 至 ${ongoing.endText}"

        val next = today.firstOrNull { it.startMinute > nowMinute }
        if (next != null) return "下一节 ${next.startText} ${next.name}"

        return NoMoreText
    }

    /**
     * 今天**还没结束**的课，最多 [max] 节。
     *
     * 正在上的那节也算在内 —— 用户看 2x2 是想知道「今天还剩什么」，
     * 正在上的课当然算剩下的。
     */
    fun upcoming(
        snapshot: WidgetSnapshotData?,
        now: LocalDateTime,
        max: Int = 3,
    ): List<WidgetCourse> {
        if (snapshot == null) return emptyList()
        val nowMinute = now.hour * 60 + now.minute
        return coursesOn(snapshot, now.toLocalDate())
            .filter { it.endMinute > nowMinute }
            .take(max)
    }

    /**
     * 4x2 横排：今天的课全摆出来，超过 [max] 的折成「还有 N 节」。
     *
     * 和 [upcoming] 不同，这里**包含已经上完的课** —— 横排的语义是「今天的课表」，
     * 不是「还剩什么」。上完的课变灰（由调用方按时间判断），版面才不会跳。
     */
    fun todayHorizontal(
        snapshot: WidgetSnapshotData?,
        now: LocalDateTime,
        max: Int = 5,
    ): HorizontalToday {
        if (snapshot == null) return HorizontalToday(emptyList(), 0, 0)
        val today = coursesOn(snapshot, now.toLocalDate())
        return HorizontalToday(
            courses = today.take(max),
            overflow = (today.size - max).coerceAtLeast(0),
            total = today.size,
        )
    }

    /**
     * 4x4 时间轴：第 [firstUnit]..[lastUnit] 节各一行。
     *
     * 连续的大课（如 1-4 节）会在每一节都返回同一个 [WidgetCourse]，
     * 但只有 [TimelineRow.isStart] 为 true 的那一行显示课程名，
     * 其余行只画色块 —— 否则同一门课名会在 4 行里重复 4 遍。
     */
    fun timeline(
        snapshot: WidgetSnapshotData?,
        now: LocalDateTime,
        firstUnit: Int = 1,
        lastUnit: Int = 12,
    ): List<TimelineRow> {
        val today = snapshot?.let { coursesOn(it, now.toLocalDate()) }.orEmpty()
        val nowMinute = now.hour * 60 + now.minute
        return (firstUnit..lastUnit).map { unit ->
            val course = today.firstOrNull { it.startUnit <= unit && unit <= it.endUnit }
            TimelineRow(
                unit = unit,
                time = SlotTimes[unit].orEmpty(),
                course = course,
                isStart = course != null && course.startUnit == unit,
                ongoing = course != null && nowMinute >= course.startMinute && nowMinute < course.endMinute,
            )
        }
    }

    /**
     * 三天视图：今天 / 明天 / 后天各一列。
     *
     * 每列最多 [maxPerDay] 节，超出部分返回 [DayColumn.overflow]。
     * 跨天时周次会自然变化（周日 → 周一可能跨周），因为每一天都独立算
     * [coursesOn]，没有「本周」这种缓存。
     */
    fun threeDays(
        snapshot: WidgetSnapshotData?,
        now: LocalDateTime,
        maxPerDay: Int = 4,
    ): List<DayColumn> {
        val today = now.toLocalDate()
        val labels = arrayOf("今天", "明天", "后天")
        return (0..2).map { offset ->
            val date = today.plusDays(offset.toLong())
            val all = snapshot?.let { coursesOn(it, date) }.orEmpty()
            DayColumn(
                label = labels[offset],
                dateText = "${date.monthValue}月${date.dayOfMonth}日",
                courses = all.take(maxPerDay),
                overflow = (all.size - maxPerDay).coerceAtLeast(0),
                isToday = offset == 0,
            )
        }
    }
}
