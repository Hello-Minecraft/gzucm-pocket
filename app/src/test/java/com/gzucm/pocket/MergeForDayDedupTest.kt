package com.gzucm.pocket

import com.gzucm.pocket.core.CourseSession
import com.gzucm.pocket.core.ScheduleEntry
import com.gzucm.pocket.core.mergeForDay
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 2026-11-23 华为 TET-AN00（Android 12 / 1.4.4）日视图崩溃的回归测试。
 *
 * 用户点「切到日视图」直接闪退：
 * `IllegalArgumentException: Key "c|12|2|6|8|临床技能|... " was already used.`
 * 根因是教务返回了同周同天同节次、同课名同地点的两条记录，
 * mergeForDay 原样透传后，两条 ScheduleEntry.Course 的 stableKey 相同，
 * 喂给 LazyColumn 的 items(key) 就撞车。
 *
 * 这里的断言盯住两件事：重复行必须被合并成一条；真正的「同时段两门课」
 * 不能被误杀 —— 那是重叠课，课表要并排画两张卡。
 */
class MergeForDayDedupTest {

    private fun session(
        name: String,
        room: String = "技能中心（核医学楼六楼）",
        week: Int = 12,
        weekday: Int = 2,
        startUnit: Int = 6,
        endUnit: Int = 8,
    ) = CourseSession(
        name = name,
        code = "B123",
        teachers = "张三",
        week = week,
        weekday = weekday,
        startUnit = startUnit,
        endUnit = endUnit,
        startTime = "14:00",
        endTime = "16:20",
        room = room,
        category = "理论教学",
    )

    @Test
    fun `教务返回的重复课行只保留一条`() {
        // 两条完全一样：同周、同天、同节次、同课名、同地点
        val duplicated = listOf(
            session("临床技能"),
            session("临床技能"),
        )

        val entries = mergeForDay(duplicated, emptyList(), week = 12, weekday = 2)

        assertEquals("重复行必须合并成一条，否则 LazyColumn 的 key 会撞车", 1, entries.size)
        assertEquals(1, entries.map { it.stableKey }.distinct().size)
        assertEquals("临床技能", (entries.single() as ScheduleEntry.Course).session.name)
    }

    @Test
    fun `同时段但课名不同的课不能被去重误杀`() {
        // 同一时段的两门课是「重叠课」，日视图要并排展示两张卡
        val overlapping = listOf(
            session("临床技能"),
            session("中医诊断学"),
        )

        val entries = mergeForDay(overlapping, emptyList(), week = 12, weekday = 2)

        assertEquals("重叠课必须保留，去重只能干掉完全相同的记录", 2, entries.size)
        assertEquals(
            setOf("临床技能", "中医诊断学"),
            entries.map { (it as ScheduleEntry.Course).session.name }.toSet(),
        )
    }
}
