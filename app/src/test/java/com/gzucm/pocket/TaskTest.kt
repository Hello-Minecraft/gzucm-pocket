package com.gzucm.pocket

import com.gzucm.pocket.core.Agenda
import com.gzucm.pocket.core.AgendaLogic
import com.gzucm.pocket.core.Net
import com.gzucm.pocket.core.Semester
import com.gzucm.pocket.core.TaskUrgency
import com.gzucm.pocket.core.taskAsOccurrence
import com.gzucm.pocket.core.taskDueLabel
import com.gzucm.pocket.core.taskUrgency
import kotlinx.serialization.builtins.ListSerializer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId

/**
 * 截止任务 / 待办的 core 层纯逻辑单测。
 *
 * 覆盖：任务不进课表展开、紧急度分档、逾期判定、截止文案、
 * 任务转首页 occurrence、序列化与老 JSON（无任务字段）兼容。
 */
class TaskTest {

    private val sem = Semester.of(
        code = "202601",
        name = "2026-2027 学年 第一学期",
        firstMonday = LocalDate.of(2026, 9, 7),
        totalWeeks = 19,
    )

    private val now = LocalDate.of(2026, 11, 17)
        .atTime(12, 0).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()

    private fun epochAt(date: LocalDate, hour: Int, minute: Int = 0): Long =
        date.atTime(hour, minute).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()

    private fun task(
        id: String,
        deadline: Long?,
        done: Long? = null,
        title: String = "任务",
    ) = Agenda(
        id = id,
        title = title,
        date = "2026-11-17",
        startMinute = 0,
        endMinute = 60,
        isTask = true,
        deadlineAt = deadline,
        doneAt = done,
    )

    // ── 任务不进课表 ────────────────────────────────────────────────

    @Test
    fun `任务不会被展开进课表网格`() {
        val ordinary = Agenda(
            id = "a", title = "上课", date = "2026-11-17",
            startMinute = 510, endMinute = 550,
        )
        val ddl = task("t", epochAt(now.let {
            java.time.Instant.ofEpochMilli(now).atZone(ZoneId.systemDefault()).toLocalDate()
        }.plusDays(3), 23, 59))

        val expanded = AgendaLogic.expand(sem, listOf(ordinary, ddl))

        assertEquals("只有普通日程进课表，任务不进", 1, expanded.size)
        assertEquals("a", expanded[0].sourceId)
    }

    // ── 紧急度分档 ──────────────────────────────────────────────────

    @Test
    fun `紧急度按逾期临近一般长期已完成分五档`() {
        val today = java.time.Instant.ofEpochMilli(now)
            .atZone(ZoneId.systemDefault()).toLocalDate()
        assertEquals(
            TaskUrgency.OVERDUE,
            taskUrgency(task("overdue", epochAt(today.minusDays(2), 12)), now),
        )
        assertEquals(
            TaskUrgency.SOON,
            taskUrgency(task("soon", now + 2 * 60 * 60_000L), now),
        )
        assertEquals(
            TaskUrgency.NORMAL,
            taskUrgency(task("normal", now + 3 * 24 * 60 * 60_000L), now),
        )
        assertEquals(
            TaskUrgency.NO_DEADLINE,
            taskUrgency(task("todo", null), now),
        )
        assertEquals(
            TaskUrgency.DONE,
            taskUrgency(task("done", epochAt(today.minusDays(5), 12), done = now - 1000), now),
        )
    }

    // ── 截止文案 ────────────────────────────────────────────────────

    @Test
    fun `截止文案区分逾期今天明天以后和长期`() {
        val today = java.time.Instant.ofEpochMilli(now)
            .atZone(ZoneId.systemDefault()).toLocalDate()
        assertEquals("长期待办", taskDueLabel(null, now))
        assertEquals("已逾期 3 天", taskDueLabel(epochAt(today.minusDays(3), 12), now))
        assertEquals("今天 23:59 截止", taskDueLabel(epochAt(today, 23, 59), now))
        assertEquals("明天 09:00 截止", taskDueLabel(epochAt(today.plusDays(1), 9), now))
        assertTrue(
            taskDueLabel(epochAt(today.plusDays(5), 15, 30), now).startsWith("11月22日"),
        )
    }

    // ── 任务转首页 occurrence ───────────────────────────────────────

    @Test
    fun `任务转成今天的occurrence且标记isTask`() {
        val today = java.time.Instant.ofEpochMilli(now)
            .atZone(ZoneId.systemDefault()).toLocalDate()
        val t = task("t1", epochAt(today.plusDays(2), 18), title = "交材料")

        val o = taskAsOccurrence(t, today, sem)

        assertTrue(o.isTask)
        assertEquals("t1", o.sourceId)
        assertEquals("交材料", o.title)
        assertEquals(today, o.date)
        assertEquals(t.deadlineAt, o.deadlineAt)
        assertEquals("task|t1", o.key)
    }

    // ── 序列化 / 老数据兼容 ─────────────────────────────────────────

    @Test
    fun `带任务字段的JSON能往返`() {
        val t = task("t", epochAt(LocalDate.of(2026, 11, 20), 23, 59), done = 12345L)
        val json = Net.json.encodeToString(ListSerializer(Agenda.serializer()), listOf(t))
        val back = Net.json.decodeFromString(ListSerializer(Agenda.serializer()), json)

        assertTrue(back[0].isTask)
        assertEquals(t.deadlineAt, back[0].deadlineAt)
        assertEquals(12345L, back[0].doneAt)
    }

    @Test
    fun `老JSON没有任务字段时默认按普通日程处理`() {
        val old = """[{"id":"old","title":"老日程","date":"2026-11-17",
            "startMinute":510,"endMinute":550,"place":"","note":"",
            "colorIndex":-1,"weekly":false,"unitStart":0,"unitEnd":0,
            "remindLeads":[0]}]"""
        val back = Net.json.decodeFromString(ListSerializer(Agenda.serializer()), old)

        assertFalse(back[0].isTask)
        assertNull(back[0].deadlineAt)
        assertNull(back[0].doneAt)
        assertEquals(listOf(0), back[0].remindLeads)
    }
}
