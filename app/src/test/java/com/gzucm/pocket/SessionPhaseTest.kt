package com.gzucm.pocket

import com.gzucm.pocket.core.CourseSession
import com.gzucm.pocket.core.SessionPhase
import com.gzucm.pocket.core.phaseOf
import com.gzucm.pocket.core.timeToMinutes
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 「正在上」的判定。
 *
 * 致辉 2026-11-02 要求：日视图也要有今日视图那种「正在上」提示。
 *
 * 关键是要**和今日页用同一套判定**。之前今日页写的是
 * `nowMinutes in startMin..endMin`（含结束那一刻），
 * 如果日视图另写一份写成 `now < end`，同一个时刻两个页面会说不一样的话。
 * 所以判定抽到 core/Campus.kt 的 [phaseOf]，两边都调它。
 */
class SessionPhaseTest {

    private fun s(start: String, end: String, startUnit: Int = 1, endUnit: Int = 2) =
        CourseSession(
            name = "测试课",
            code = "",
            teachers = "张三",
            week = 8,
            weekday = 1,
            startUnit = startUnit,
            endUnit = endUnit,
            startTime = start,
            endTime = end,
            room = "A101",
            category = "",
        )

    private fun hm(h: Int, m: Int) = h * 60 + m

    // ── 时间解析 ──────────────────────────────────────────

    @Test
    fun `正常时间能解析`() {
        assertEquals(hm(8, 30), timeToMinutes("08:30"))
        assertEquals(hm(0, 0), timeToMinutes("00:00"))
        assertEquals(hm(21, 20), timeToMinutes("21:20"))
        // 不带前导零也要能认
        assertEquals(hm(8, 5), timeToMinutes("8:5"))
    }

    @Test
    fun `异常时间返回MAX不炸`() {
        assertEquals(Int.MAX_VALUE, timeToMinutes(""))
        assertEquals(Int.MAX_VALUE, timeToMinutes("ab:cd"))
        assertEquals(Int.MAX_VALUE, timeToMinutes("0830"))
        assertEquals(Int.MAX_VALUE, timeToMinutes(":30"))
    }

    // ── 三种状态 ──────────────────────────────────────────

    @Test
    fun `开始之前是还没开始`() {
        val c = s("14:00", "15:40")
        assertEquals(SessionPhase.UPCOMING, phaseOf(c, hm(13, 59)))
        assertEquals(SessionPhase.UPCOMING, phaseOf(c, hm(13, 0)))
        assertEquals(SessionPhase.UPCOMING, phaseOf(c, hm(8, 0)))
    }

    @Test
    fun `开始那一刻算正在上`() {
        val c = s("14:00", "15:40")
        assertEquals(
            "刚打铃就是正在上，不能等一分钟",
            SessionPhase.ONGOING,
            phaseOf(c, hm(14, 0)),
        )
    }

    @Test
    fun `课中间是正在上`() {
        val c = s("14:00", "15:40")
        assertEquals(SessionPhase.ONGOING, phaseOf(c, hm(14, 1)))
        assertEquals(SessionPhase.ONGOING, phaseOf(c, hm(15, 0)))
        assertEquals(SessionPhase.ONGOING, phaseOf(c, hm(15, 39)))
    }

    @Test
    fun `结束那一刻算已下课`() {
        val c = s("14:00", "15:40")
        assertEquals(
            "15:40 已经下课了，不能再显示正在上",
            SessionPhase.PAST,
            phaseOf(c, hm(15, 40)),
        )
        assertEquals(SessionPhase.PAST, phaseOf(c, hm(16, 0)))
    }

    @Test
    fun `传-1表示不是今天一律当没开始`() {
        // 日视图翻到别的日期时传 -1，卡片就不该出现「正在上」
        val c = s("14:00", "15:40")
        assertEquals(SessionPhase.UPCOMING, phaseOf(c, -1))
    }

    // ── 数据缺时间时退回节次表 ────────────────────────────

    @Test
    fun `没有startTime时用节次表推算`() {
        // 第 1 节 = 08:30，第 2 节 = 09:20
        val c = s("", "", startUnit = 1, endUnit = 2)
        assertEquals(
            "空时间不能判成正在上",
            SessionPhase.UPCOMING,
            phaseOf(c, hm(8, 0)),
        )
        assertEquals(
            "08:30 应该是正在上（节次表推算）",
            SessionPhase.ONGOING,
            phaseOf(c, hm(8, 30)),
        )
    }

    @Test
    fun `节次也没有时不会误判成正在上`() {
        // 节次越界 → 时间拿不到 → MAX_VALUE → 永远 UPCOMING
        val c = s("", "", startUnit = 99, endUnit = 99)
        assertEquals(SessionPhase.UPCOMING, phaseOf(c, hm(12, 0)))
    }
}
