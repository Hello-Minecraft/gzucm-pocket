package com.gzucm.pocket.core

import java.time.LocalDate

/**
 * 今日页显示配置。
 *
 * 致辉 2026-11-20 定：「今日页可管理显示内容」——
 * 电费、天气、未来日程可关，本周概览也可关；
 * **今日课程不可关**（那是首页存在的理由）。
 *
 * 提前天数单独一项：他说「日程（加提前显示的时间的可调）」，
 * 范围 1~14 天，默认 7。
 */
data class TodaySections(
    val power: Boolean = true,
    val weather: Boolean = true,
    val upcoming: Boolean = true,
    val weekOverview: Boolean = true,
    val upcomingDays: Int = DefaultDays,
) {
    companion object {
        const val MinDays = 1
        const val MaxDays = 14
        const val DefaultDays = 7
    }
}

object TodayLogic {

    /** 「接下来」最多摆几条。再多就不是「提醒」而是第二张日程表了 */
    const val MaxUpcoming = 5

    /**
     * 未来 N 天的日程 / 考试。
     *
     * 三条口径：
     * 1. **不含今天** —— 今天的内容已经在上面那一段里了，重复一遍会让人以为撞车；
     * 2. 含第 N 天当天（`<= today + N`），「七天内」就是连头带尾都在；
     * 3. 按日期、时间、标题排序，保证同一天里早的在前。
     *
     * 入参是 `agendaOccurrences + examOccurrences` 的合并结果，
     * 两种来源在这里不做区分 —— 对用户来说都是「那天有事」。
     */
    fun upcoming(
        all: List<AgendaOccurrence>,
        today: LocalDate,
        days: Int,
    ): List<AgendaOccurrence> {
        val span = days.coerceIn(TodaySections.MinDays, TodaySections.MaxDays).toLong()
        val until = today.plusDays(span)
        return all
            .filter { it.date.isAfter(today) && !it.date.isAfter(until) }
            .sortedWith(compareBy({ it.date }, { it.startMinute }, { it.title }))
    }
}
