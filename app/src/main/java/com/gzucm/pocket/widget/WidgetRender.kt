package com.gzucm.pocket.widget

import com.gzucm.pocket.core.SlotEndTimes
import com.gzucm.pocket.core.SlotTimes
import com.gzucm.pocket.core.timeToMinutes
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.temporal.ChronoUnit

/**
 * 周视图一行（一节课）的内容高度。**必须和 widget_week_row.xml 里的
 * `wk_row_body` 的 24dp 一致**（那一行没有别的装饰，行高就等于内容高）。
 */
const val WeekRowHeightDp = 24

/**
 * 周视图固定 12 节。
 *
 * **不能按当天有几节课来定行数**：ListView 的 setScrollPosition 收的是行号，
 * 只有「第 n 行就是第 n 节」这个前提成立，定位才算得准。没课的节次也要占位。
 */
const val WeekUnitCount = 12

/**
 * 周视图的配色数量，对应 @color/widget_k1..k8 与 @drawable/widget_wk1..8_*。
 *
 * 8 组色同时用于 2x2 / 4x2 / 4x4 / 三日 / 周视图，同一门课在哪个部件里都同色。
 */
const val WeekColorCount = 8

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
    /** 任课老师，可能是「张伟,李娜」。老快照没有这个字段时是空串 */
    val teacher: String = "",
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

    /**
     * 4x2 的老师列只有 34dp，两个老师的逗号串一定显示不下。
     * 取第一个 —— 用户在桌面上要的是「这门课谁上的」这个线索，不是完整名单。
     */
    val teacherText: String
        get() = teacher.split(',', '，', '、', ';', '；')
            .firstOrNull { it.isNotBlank() }
            ?.trim()
            .orEmpty()

    /** 节次标签：「1-2」/「3」 */
    val unitText: String
        get() = if (endUnit > startUnit) "$startUnit-$endUnit" else "$startUnit"
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
 * 2x2 / 4x2 的一张卡片。
 *
 * 两张卡片的版式一样，差别只在 4x2 多两栏（地点、老师），所以数据层不区分尺寸，
 * 由 Provider 决定填几个字段。
 *
 * [colorIndex] 是这门课的配色下标（0..7），Provider 拿它查 drawable / 颜色表。
 * 默认值 0 只是为了老测试能少改一行；新逻辑必须显式填。
 */
data class TodayCard(
    val startText: String,
    val endText: String,
    val name: String,
    val room: String,
    val teacher: String,
    /** true = 正在上：块底换成该课的 26% 变体 + 课名加粗 */
    val ongoing: Boolean,
    val colorIndex: Int = 0,
)

/**
 * 2x2 / 4x2 的卡片数据。
 *
 * [emptyText] 非空 = 两张卡合并成一句话（「今天没课了」/「打开 App 同步课表」），
 * 这时 [cards] 一定为空 —— 两个状态互斥，Provider 不用再判一次。
 */
data class TodayCards(
    val cards: List<TodayCard>,
    val emptyText: String?,
)

/** 一节次列表行的状态。样式由 Provider 按它选背景/文字色 */
enum class TodayRowState { UPCOMING, ONGOING, PAST }

/** 4x4 节次列表的一行 */
data class TodayRow(
    val unitText: String,
    val timeText: String,
    val name: String,
    val room: String,
    val state: TodayRowState,
    /** 这门课的配色下标（0..7）。「还有 N 节」那一行没有课，给 0 即可 */
    val colorIndex: Int = 0,
)

/** 4x4 节次列表的数据。[overflow] > 0 时最后一行是「还有 N 节」 */
data class TodayList(
    val rows: List<TodayRow>,
    val overflow: Int,
    val emptyText: String?,
)

/**
 * 2x1 一行的内容（圆点 + 课名 + 尾巴）。
 *
 * [emptyText] 非空 = 今天没课 / 没同步过，Provider 把整行收掉只留那句提示。
 */
data class CurrentInfo(
    val name: String,
    /** 正在上：「至 09:40」；下一节：「10:20 开始」 */
    val tail: String,
    val colorIndex: Int,
    val emptyText: String?,
)

/**
 * 周视图格子在「同一门课里」的位置。
 *
 * 只有这四个角色才能拼出正确的连块：跨 3 节的课是 first + middle + last，
 * 单节是 single。缺口（不同课之间 2dp）和贴合（同门课之间 0dp）全靠
 * 每种角色各自的上下 inset 实现，见 widget_wk{n}_{role} 的 drawable。
 */
enum class WeekCellRole { SINGLE, FIRST, MIDDLE, LAST }

/**
 * 周视图的一个格子。
 *
 * 跨节的课（第 1-2 节连上）在数据层就是两个格子：
 * 起始格 [isStart] = true、[span] = 2、带课名和时间；后续格 [covered] = true、
 * 只有颜色没有字。视觉上两格同色相接 = 一个高块，靠 [role] 决定各自的圆角和 inset。
 *
 * 为什么不在运行时把起始格的 layout_height 改成两倍：RemoteViews 的
 * `setInt(..., "setMinimumHeight", ...)` 不是 @RemotableViewMethod，宿主要抛
 * ActionException；能改高度的 `setViewLayoutHeight` 要 API 31，本项目 minSdk 26。
 */
data class WeekCell(
    val unit: Int,
    /** 0..6，0 = 周日（和表头一致） */
    val col: Int,
    val name: String?,
    val time: String?,
    /** -1 = 这格没课 */
    val colorIndex: Int,
    val isStart: Boolean,
    val covered: Boolean,
    /** 只有起始格有意义：这门课跨几节 */
    val span: Int,
    /** 在连块里的位置。没课时无意义，给 SINGLE */
    val role: WeekCellRole = WeekCellRole.SINGLE,
) {
    /** 块高度 = 跨的节数 × 行高。仅起始格有意义 */
    val heightDp: Int get() = if (isStart) span * WeekRowHeightDp else 0
}

/** 周视图网格。[cells] 行优先：`(unit-1) * 7 + col` */
data class WeekGrid(
    val header: String,
    val weekdays: List<String>,
    /** 表头第二行的日期数字 */
    val dates: List<String>,
    /** 今天在第几列，-1 = 今天不在这一周（学期外） */
    val todayCol: Int,
    val units: List<Int>,
    val cells: List<WeekCell>,
    val emptyText: String?,
) {
    fun cell(unit: Int, col: Int): WeekCell = cells[(unit - 1) * 7 + col]
}

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

    /** 周视图表头用的单字，顺序是**周日在前** */
    private val WEEKDAY_CHARS = arrayOf("日", "一", "二", "三", "四", "五", "六")

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
                    teacher = it.teacher,
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
     * 2x1 的一行：圆点 + 课名 + 尾巴。
     *
     * 和 [currentLine] 是同一套判断，只是拆成结构化字段 —— v3 版式里课名要用
     * 主文字色、尾巴用次色，还得在左边放一个课程色圆点，一整串字符串做不到。
     * [currentLine] 留着是因为它被单测和别处引用，两边口径必须一致。
     *
     *   正在上 → name=课名 / tail=「至 09:40」
     *   下一节 → name=课名 / tail=「10:20 开始」
     *   没课   → [emptyText] 非空，Provider 只显示那一句
     */
    fun currentInfo(snapshot: WidgetSnapshotData?, now: LocalDateTime): CurrentInfo {
        if (snapshot == null || snapshot.sessions.isEmpty()) {
            return CurrentInfo("", "", 0, EmptyText)
        }
        val today = coursesOn(snapshot, now.toLocalDate())
        val nowMinute = now.hour * 60 + now.minute

        val ongoing = today.firstOrNull { nowMinute >= it.startMinute && nowMinute < it.endMinute }
        if (ongoing != null) {
            return CurrentInfo(
                name = ongoing.name,
                tail = "至 ${ongoing.endText}",
                colorIndex = colorIndexFor(ongoing.name),
                emptyText = null,
            )
        }

        val next = today.firstOrNull { it.startMinute > nowMinute }
        if (next != null) {
            return CurrentInfo(
                name = next.name,
                tail = "${next.startText} 开始",
                colorIndex = colorIndexFor(next.name),
                emptyText = null,
            )
        }

        return CurrentInfo("", "", 0, NoMoreText)
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

    // ── 2x2 / 4x2 两张卡片 ──────────────────────────────────────────

    /**
     * 两张卡片的内容。
     *
     * 三种情况（对应三个真机场景）：
     *   · 正在上课 → 上卡就是这节课（主题色底），下卡是再下一节
     *   · 课间 / 早上第一节课前 → 上卡是下一节，下卡是再下一节，都用普通色
     *   · 今天没课了 / 从没同步过 → [TodayCards.emptyText] 非空，两卡合并成一句话
     *
     * 为什么课间不用主题色：用户看到的「正在上」必须真的是在上课，
     * 课间高亮会让人以为已经上课了。
     */
    fun todayCards(snapshot: WidgetSnapshotData?, now: LocalDateTime): TodayCards {
        val today = snapshot?.let { coursesOn(it, now.toLocalDate()) }.orEmpty()
        val nowMinute = now.hour * 60 + now.minute

        val ongoing = today.firstOrNull { nowMinute >= it.startMinute && nowMinute < it.endMinute }
        val cards = if (ongoing != null) {
            val next = today.firstOrNull { it !== ongoing && it.startMinute > nowMinute }
            listOfNotNull(ongoing.toCard(ongoing = true), next?.toCard(ongoing = false))
        } else {
            today.filter { it.endMinute > nowMinute }
                .take(2)
                .map { it.toCard(ongoing = false) }
        }
        return TodayCards(cards, if (cards.isEmpty()) emptyText(snapshot) else null)
    }

    private fun WidgetCourse.toCard(ongoing: Boolean) = TodayCard(
        startText = startText,
        endText = endText,
        name = name,
        room = room,
        teacher = teacherText,
        ongoing = ongoing,
        colorIndex = colorIndexFor(name),
    )

    // ── 4x4 节次列表 ────────────────────────────────────────────────

    /**
     * 今天的课按节次排成一列，最多 [max] 行。
     *
     * 超过 [max] 时**最后一行换成「还有 N 节」**，而不是硬截断 ——
     * 一眼能看到「今天还有课」，不至于以为课表就到这儿了。
     */
    fun todayList(snapshot: WidgetSnapshotData?, now: LocalDateTime, max: Int = 7): TodayList {
        val today = snapshot?.let { coursesOn(it, now.toLocalDate()) }.orEmpty()
        if (today.isEmpty()) return TodayList(emptyList(), 0, emptyText(snapshot))

        val nowMinute = now.hour * 60 + now.minute
        fun row(c: WidgetCourse) = TodayRow(
            unitText = c.unitText,
            timeText = c.startText,
            name = c.name,
            room = c.room,
            state = when {
                nowMinute < c.startMinute -> TodayRowState.UPCOMING
                nowMinute < c.endMinute -> TodayRowState.ONGOING
                else -> TodayRowState.PAST
            },
            colorIndex = colorIndexFor(c.name),
        )

        val overflow = (today.size - max).coerceAtLeast(0)
        if (overflow == 0) return TodayList(today.map(::row), 0, null)

        val shown = today.take(max - 1).map(::row) +
            TodayRow("", "", "还有 $overflow 节", "", TodayRowState.PAST)
        return TodayList(shown, overflow, null)
    }

    // ── 4x3 周视图 ──────────────────────────────────────────────────

    /**
     * 课程名 → 配色下标，同一门课永远同一个颜色（跨周、跨刷新都一致）。
     *
     * 用 String.hashCode 而不是 Kotlin 的 `hashCode()` 随机种子：它是有定义的
     * 稳定算法（s[0]*31^(n-1) + ...），换设备、换进程、隔一周算出来都一样。
     */
    fun colorIndexFor(name: String): Int =
        ((name.hashCode() % WeekColorCount) + WeekColorCount) % WeekColorCount

    /** 周视图表头：「第 12 周 · 今日课程已结束」 */
    fun weekHeader(snapshot: WidgetSnapshotData?, now: LocalDateTime): String {
        if (snapshot == null || snapshot.sessions.isEmpty()) return EmptyText
        val week = weekOf(snapshot, now.toLocalDate())
        val prefix = if (week != null) "第 $week 周" else "课表"
        val today = coursesOn(snapshot, now.toLocalDate())
        val nowMinute = now.hour * 60 + now.minute
        val ongoing = today.firstOrNull { nowMinute >= it.startMinute && nowMinute < it.endMinute }
        val status = when {
            ongoing != null -> "正在上 ${ongoing.name}"
            else -> today.firstOrNull { it.startMinute > nowMinute }
                ?.let { "下一节 ${it.startText}" }
                ?: "今日课程已结束"
        }
        return "$prefix · $status"
    }

    /**
     * 课时表里「此刻」对应的**行号**（0 起，等于节次 - 1）。
     *
     * 定位用，不判断有没有课：课间和早上第一节课前都要停在「接下来那一节」，
     * 而不是停在上一节已经上完的课。规则：
     *   · 正在某一节 → 这一节
     *   · 课间（10:00–10:20）→ 下一节
     *   · 早于第一节（8:30 前）→ 第 1 节
     *   · 晚上最后一节之后 → 第 12 节（不越界，ListView 不会滚到空白处）
     *
     * 口径和 App 内一样，时间来自 [SlotTimes] / [SlotEndTimes]。
     */
    fun currentRow(now: LocalDateTime, units: Int = WeekUnitCount): Int {
        val nowMinute = now.hour * 60 + now.minute
        for (unit in 1..units) {
            val end = timeToMinutes(SlotEndTimes[unit].orEmpty())
            // 这一节还没下课 → 就是它（含「还没开始」）
            if (nowMinute < end) return unit - 1
        }
        return (units - 1).coerceAtLeast(0)
    }

    /**
     * 7 天 × [units] 节的网格。
     *
     * 列是**周日在前**（日 一 二 三 四 五 六），和小米自带课表一致；
     * 课程数据里 weekday 是 1=周一，所以列 0 对应 weekday 7。
     *
     * 跨节的课在 [WeekCell] 里展开成多个格子：起始格带字，后续格同色留空。
     */
    fun weekGrid(snapshot: WidgetSnapshotData?, now: LocalDateTime, units: Int = WeekUnitCount): WeekGrid {
        val today = now.toLocalDate()
        val todayCol = today.dayOfWeek.value % 7
        val sunday = today.minusDays(todayCol.toLong())
        val dates = (0..6).map { sunday.plusDays(it.toLong()) }

        // 每一天：unit → (这门课, 是否起始节)
        val perDay: List<Map<Int, Pair<WidgetCourse, Boolean>>> = dates.map { date ->
            snapshot?.let { coursesOn(it, date) }.orEmpty()
                .flatMap { c -> (c.startUnit..c.endUnit).map { u -> u to (c to (u == c.startUnit)) } }
                .toMap()
        }

        val cells = ArrayList<WeekCell>(units * 7)
        for (unit in 1..units) {
            for (col in 0..6) {
                val hit = perDay[col][unit]
                when {
                    hit == null -> cells += WeekCell(unit, col, null, null, -1, false, false, 0)

                    hit.second -> {
                        val c = hit.first
                        cells += WeekCell(
                            unit = unit,
                            col = col,
                            name = c.name,
                            time = c.startText,
                            colorIndex = colorIndexFor(c.name),
                            isStart = true,
                            covered = false,
                            span = (c.endUnit - c.startUnit + 1).coerceAtLeast(1),
                            role = roleOf(unit, c),
                        )
                    }

                    else -> {
                        val c = hit.first
                        cells += WeekCell(
                            unit = unit,
                            col = col,
                            name = null,
                            time = null,
                            colorIndex = colorIndexFor(c.name),
                            isStart = false,
                            covered = true,
                            span = 0,
                            role = roleOf(unit, c),
                        )
                    }
                }
            }
        }

        val inSemester = snapshot != null && weekOf(snapshot, today) != null
        return WeekGrid(
            header = weekHeader(snapshot, now),
            weekdays = WEEKDAY_CHARS.toList(),
            dates = dates.map { it.dayOfMonth.toString() },
            todayCol = if (inSemester) todayCol else -1,
            units = (1..units).toList(),
            cells = cells,
            emptyText = if (snapshot == null || snapshot.sessions.isEmpty()) EmptyText else null,
        )
    }

    /**
     * 第 [unit] 节落在某门课的哪个位置。
     *
     * 口径以 [WidgetCourse.startUnit] / [endUnit] 为准，不看 `isStart` ——
     * 后者是「这一格要不要显示课名」，和「这一格上下要不要圆角」是两件事。
     */
    private fun roleOf(unit: Int, c: WidgetCourse): WeekCellRole = when {
        unit == c.startUnit && c.endUnit <= c.startUnit -> WeekCellRole.SINGLE
        unit == c.startUnit -> WeekCellRole.FIRST
        unit == c.endUnit -> WeekCellRole.LAST
        else -> WeekCellRole.MIDDLE
    }
}
