package com.gzucm.pocket

import com.gzucm.pocket.core.ClassReminder
import com.gzucm.pocket.core.CourseSession
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

/**
 * 上课提醒的时间计算。
 *
 * 最容易错的是「什么时候该用 10 分钟」——判据是 T-20 分钟那一刻是否还在上课，
 * 而不是「两节课之间有没有间隔」。
 */
class ClassReminderTest {

    private val date = LocalDate.of(2026, 10, 29)

    private fun s(
        name: String,
        start: String,
        end: String,
        weekday: Int = 4,
        startUnit: Int = 1,
        endUnit: Int = 2,
        room: String = "示例楼101",
        teacher: String = "张老师",
    ) = CourseSession(
        name = name, code = "", teachers = teacher, week = 8, weekday = weekday,
        startUnit = startUnit, endUnit = endUnit, startTime = start, endTime = end,
        room = room, category = "",
    )

    private fun hhmm(millis: Long): String =
        LocalTime.ofInstant(java.time.Instant.ofEpochMilli(millis), ZoneId.systemDefault())
            .toString().take(5)

    @Test
    fun `当天第一节课提前20分钟`() {
        val a = s("中医基础理论", "08:00", "09:40")
        val at = ClassReminder.remindAt(a, listOf(a), date)!!
        assertEquals("07:40", hhmm(at))
    }

    @Test
    fun `上一节还没下课时改成课间提前10分钟`() {
        // 1-2 节 08:00-09:40，3-4 节 10:00-11:40。
        // T-20 = 09:40，正好是下课时刻，不算在课上 → 仍是 20 分钟。
        val a = s("中医基础理论", "08:00", "09:40", startUnit = 1, endUnit = 2)
        val b = s("中医诊断学", "10:00", "11:40", startUnit = 3, endUnit = 4)
        val at = ClassReminder.remindAt(b, listOf(a, b), date)!!
        assertEquals("09:40", hhmm(at))
    }

    @Test
    fun `课间只有10分钟时用10分钟提前量`() {
        // 上节 08:00-09:50，下节 10:00 开始。T-20 = 09:40 仍在课上 → 改 09:50
        val a = s("针灸学", "08:00", "09:50", startUnit = 1, endUnit = 2)
        val b = s("推拿学", "10:00", "11:40", startUnit = 3, endUnit = 4)
        val at = ClassReminder.remindAt(b, listOf(a, b), date)!!
        assertEquals("09:50", hhmm(at))
    }

    @Test
    fun `下午第一节课仍是20分钟`() {
        val morning = s("中医基础理论", "08:00", "11:40", startUnit = 1, endUnit = 4)
        val afternoon = s("内经", "14:00", "15:40", startUnit = 5, endUnit = 6)
        val at = ClassReminder.remindAt(afternoon, listOf(morning, afternoon), date)!!
        assertEquals("13:40", hhmm(at))
    }

    @Test
    fun `副标题用中点连接地点与教师`() {
        val a = s("伤寒论", "08:00", "09:40", room = "课室楼203", teacher = "李老师")
        assertEquals("课室楼203·李老师", ClassReminder.subtitle(a))
    }

    @Test
    fun `缺地点或教师时不留下多余的分隔符`() {
        val a = s("伤寒论", "08:00", "09:40", room = "", teacher = "李老师")
        assertEquals("李老师", ClassReminder.subtitle(a))
        val b = s("伤寒论", "08:00", "09:40", room = "课室楼203", teacher = "")
        assertEquals("课室楼203", ClassReminder.subtitle(b))
    }

    @Test
    fun `时间格式异常不崩只返回null`() {
        val bad = s("坏数据", "八点", "九点")
        assertEquals(null, ClassReminder.remindAt(bad, listOf(bad), date))
    }

    @Test
    fun `提醒时刻早于开课时间`() {
        val a = s("温病学", "08:00", "09:40")
        val at = ClassReminder.remindAt(a, listOf(a), date)!!
        val start = ClassReminder.at(date, "08:00")!!
        assertTrue(at < start)
        assertEquals(20L * 60_000L, start - at)
    }
}
