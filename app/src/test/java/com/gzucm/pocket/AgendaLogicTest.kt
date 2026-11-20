package com.gzucm.pocket

import com.gzucm.pocket.core.Agenda
import com.gzucm.pocket.core.AgendaLogic
import com.gzucm.pocket.core.Net
import com.gzucm.pocket.core.Semester
import kotlinx.serialization.builtins.ListSerializer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

/**
 * 自定义日程 core 层纯逻辑单测：学期范围、单次/每周展开、
 * week/weekday、排序、key 稳定性、序列化与老 JSON 兼容。
 *
 * 纯 JVM，不依赖 Android / Robolectric（core 层是纯 Kotlin + java.time）。
 * 失败消息按 JUnit4「message 在前」的签名书写，和 SemesterTest / SlotTimeTest 一致。
 */
class AgendaLogicTest {

    private val sem = Semester.of(
        code = "202601",
        name = "2026-2027 学年 第一学期",
        firstMonday = LocalDate.of(2026, 9, 7),
        totalWeeks = 19,
    )

    private fun agenda(
        id: String,
        date: String,
        start: Int = 8 * 60 + 30,
        end: Int = 9 * 60 + 10,
        weekly: Boolean = false,
        daily: Boolean = false,
        title: String = "测试日程",
        place: String = "",
        note: String = "",
        colorIndex: Int = -1,
        unitStart: Int = 0,
        unitEnd: Int = 0,
        remindLeads: List<Int> = emptyList(),
    ) = Agenda(
        id = id,
        title = title,
        date = date,
        startMinute = start,
        endMinute = end,
        place = place,
        note = note,
        colorIndex = colorIndex,
        weekly = weekly,
        daily = daily,
        unitStart = unitStart,
        unitEnd = unitEnd,
        remindLeads = remindLeads,
    )

    // ── 学期范围 ──────────────────────────────────────────

    @Test
    fun `firstDay 是第 1 周周一`() {
        assertEquals(
            "开学第一天应是校历里的 firstMonday",
            LocalDate.of(2026, 9, 7),
            AgendaLogic.firstDay(sem),
        )
    }

    @Test
    fun `lastDay 是本学期最后一周周日`() {
        val expected = sem.firstMonday.plusWeeks(sem.totalWeeks.toLong()).minusDays(1L)
        assertEquals(
            "学期最后一天 = firstMonday + totalWeeks*7 - 1（闭区间右端）",
            expected,
            AgendaLogic.lastDay(sem),
        )
        assertEquals("最后一天应是周日", 7, AgendaLogic.lastDay(sem).dayOfWeek.value)
    }

    @Test
    fun `inSemester 是闭区间`() {
        assertTrue("第一天应在范围内", AgendaLogic.inSemester(sem, AgendaLogic.firstDay(sem)))
        assertTrue("最后一天应在范围内", AgendaLogic.inSemester(sem, AgendaLogic.lastDay(sem)))
        assertTrue("学期中间某天应在范围内", AgendaLogic.inSemester(sem, LocalDate.of(2026, 10, 28)))
        assertFalse("开学前一天不在范围内", AgendaLogic.inSemester(sem, LocalDate.of(2026, 9, 6)))
        assertFalse("结课后一天不在范围内", AgendaLogic.inSemester(sem, AgendaLogic.lastDay(sem).plusDays(1L)))
    }

    // ── 单次 ──────────────────────────────────────────────

    @Test
    fun `单次日程在学期内展开成一条且字段完整`() {
        val rule = agenda(
            id = "a-single",
            date = "2026-10-28",
            start = 10 * 60 + 20,
            end = 11 * 60,
            title = "开会",
            place = "行政楼",
            note = "带材料",
            colorIndex = 3,
            unitStart = 3,
            unitEnd = 4,
            remindLeads = listOf(0, 15),
        )
        val got = AgendaLogic.expand(sem, listOf(rule))
        assertEquals("学期内的单次日程应展开成 1 条", 1, got.size)

        val o = got[0]
        assertEquals("sourceId", "a-single", o.sourceId)
        assertEquals("title", "开会", o.title)
        assertEquals("date", LocalDate.of(2026, 10, 28), o.date)
        assertEquals("week", sem.weekOf(o.date), o.week)
        assertEquals("weekday", o.date.dayOfWeek.value, o.weekday)
        assertEquals("startMinute", 10 * 60 + 20, o.startMinute)
        assertEquals("endMinute", 11 * 60, o.endMinute)
        assertEquals("place", "行政楼", o.place)
        assertEquals("note", "带材料", o.note)
        assertEquals("colorIndex", 3, o.colorIndex)
        assertEquals("unitStart", 3, o.unitStart)
        assertEquals("unitEnd", 4, o.unitEnd)
        assertFalse("weekly 应为 false", o.weekly)
        assertTrue("key 应非空", o.key.isNotBlank())
    }

    @Test
    fun `单次日程在学期外不展开`() {
        val before = agenda(id = "before", date = "2026-08-01")
        val after = agenda(id = "after", date = "2027-02-01")
        assertTrue(
            "寒暑假里的单次日程不应展开",
            AgendaLogic.expand(sem, listOf(before, after)).isEmpty(),
        )
    }

    // ── 每周重复 ──────────────────────────────────────────

    @Test
    fun `每周日程从锚点起每 7 天一条且都落在学期内`() {
        val anchor = LocalDate.of(2026, 9, 9) // 第 1 周周三
        val rule = agenda(id = "w", date = anchor.toString(), weekly = true, start = 600, end = 640)
        val got = AgendaLogic.expand(sem, listOf(rule))

        assertTrue("每周日程应展开出多条", got.size > 1)
        assertEquals("第一条应是锚点当天", anchor, got.first().date)
        assertTrue("全部实例应在学期内", got.all { AgendaLogic.inSemester(sem, it.date) })
        assertTrue("weekday 应与锚点一致", got.all { it.weekday == anchor.dayOfWeek.value })
        assertTrue("全部实例应标记为 weekly", got.all { it.weekly })
        assertFalse("最后一条不应晚于学期最后一天", got.last().date.isAfter(AgendaLogic.lastDay(sem)))
        for (i in 1 until got.size) {
            assertEquals("相邻实例应相隔 7 天", got[i - 1].date.plusDays(7L), got[i].date)
        }
    }

    @Test
    fun `每周日程锚点在开学前结果仍自洽`() {
        // 锚点在学期外时实现可以「顺延到第一个同 weekday」或「不展开」，
        // 本用例只断言结果本身自洽，两种实现都能通过（空结果时断言平凡成立）。
        val anchor = LocalDate.of(2026, 8, 26) // 周三，开学前
        val rule = agenda(id = "pre", date = anchor.toString(), weekly = true)
        val got = AgendaLogic.expand(sem, listOf(rule))

        assertTrue("结果全部落在学期内", got.all { AgendaLogic.inSemester(sem, it.date) })
        assertTrue("不早于学期第一天", got.all { !it.date.isBefore(AgendaLogic.firstDay(sem)) })
        assertTrue("weekday 与锚点一致", got.all { it.weekday == anchor.dayOfWeek.value })
        for (i in 1 until got.size) {
            assertEquals("相邻实例应相隔 7 天", got[i - 1].date.plusDays(7L), got[i].date)
        }
    }

    @Test
    fun `每周日程锚点在学期结束后结果仍自洽`() {
        val anchor = LocalDate.of(2027, 2, 3) // 周三，结课后
        val rule = agenda(id = "post", date = anchor.toString(), weekly = true)
        val got = AgendaLogic.expand(sem, listOf(rule))

        assertTrue("结果全部落在学期内", got.all { AgendaLogic.inSemester(sem, it.date) })
        assertTrue("weekday 与锚点一致", got.all { it.weekday == anchor.dayOfWeek.value })
        for (i in 1 until got.size) {
            assertEquals("相邻实例应相隔 7 天", got[i - 1].date.plusDays(7L), got[i].date)
        }
    }

    /**
     * 每天重复（2026-11-21 加的）。
     *
     * 致辉要的是「连着几天的见习轮转」不用一天建一条。展开逻辑和每周同构，
     * 但步长是 1 天 —— 很容易写成加 7 然后所有实例都落在同一个 weekday。
     */
    @Test
    fun `每天重复的相邻实例相隔一天`() {
        val rule = agenda(id = "daily", date = "2026-11-02", daily = true)
        val got = AgendaLogic.expand(sem, listOf(rule))

        assertTrue("应有展开结果", got.isNotEmpty())
        assertTrue("结果全部落在学期内", got.all { AgendaLogic.inSemester(sem, it.date) })
        for (i in 1 until got.size) {
            assertEquals("每天重复必须隔一天", got[i - 1].date.plusDays(1L), got[i].date)
        }
    }

    /** 锚点在开学前时一天天顺延，不能像每周那样加 7（会错位到别的 weekday） */
    @Test
    fun `每天重复锚点在学期前也能对齐`() {
        val rule = agenda(id = "pre-daily", date = "2026-08-01", daily = true)
        val got = AgendaLogic.expand(sem, listOf(rule))
        val first = AgendaLogic.firstDay(sem)
        assertEquals("第一天就该是学期第一天", first, got.first().date)
    }

    /** daily 和 weekly 同时为 true 时按「每天」算，别让老数据打架 */
    @Test
    fun `daily 优先于 weekly`() {
        val rule = agenda(id = "both", date = "2026-11-02", weekly = true).copy(daily = true)
        val got = AgendaLogic.expand(sem, listOf(rule))
        for (i in 1 until got.size) {
            assertEquals(got[i - 1].date.plusDays(1L), got[i].date)
        }
    }

    @Test
    fun `每条 occurrence 的 week 与 weekday 正确`() {
        val rule = agenda(id = "wk", date = "2026-09-07", weekly = true)
        val got = AgendaLogic.expand(sem, listOf(rule))
        assertTrue("应有展开结果", got.isNotEmpty())
        for (o in got) {
            assertEquals("week 应等于 semester.weekOf(date)", sem.weekOf(o.date), o.week)
            assertEquals("weekday 应等于 date.dayOfWeek.value", o.date.dayOfWeek.value, o.weekday)
        }
    }

    // ── 排序 / key ────────────────────────────────────────

    @Test
    fun `展开结果按日期和开始分钟排序`() {
        val rules = listOf(
            agenda(id = "c", date = "2026-11-02", start = 800),
            agenda(id = "a", date = "2026-09-08", start = 900),
            agenda(id = "b", date = "2026-09-08", start = 600),
            agenda(id = "d", date = "2026-10-01", start = 700),
        )
        val got = AgendaLogic.expand(sem, rules)
        for (i in 1 until got.size) {
            val a = got[i - 1]
            val b = got[i]
            assertTrue(
                "位置 $i 排序回退: ${a.date}/${a.startMinute} -> ${b.date}/${b.startMinute}",
                a.date < b.date || (a.date == b.date && a.startMinute <= b.startMinute),
            )
        }
    }

    @Test
    fun `key 唯一且同规则重跑稳定`() {
        val rules = listOf(
            agenda(id = "x", date = "2026-09-09", weekly = true),
            agenda(id = "y", date = "2026-09-09"),
            agenda(id = "z", date = "2026-09-16"),
        )
        val first = AgendaLogic.expand(sem, rules)
        assertEquals("key 应全部唯一", first.size, first.map { it.key }.toSet().size)

        val second = AgendaLogic.expand(sem, rules)
        assertEquals("同规则重跑结果应完全一致", first, second)
        assertEquals("同规则重跑 key 序列应一致", first.map { it.key }, second.map { it.key })
    }

    // ── 节次时间 ──────────────────────────────────────────

    @Test
    fun `minutesOfUnit 返回该节上下课分钟`() {
        assertEquals("第 1 节 08:30-09:10", 8 * 60 + 30 to 9 * 60 + 10, AgendaLogic.minutesOfUnit(1))
        assertEquals("第 5 节 13:10-13:50", 13 * 60 + 10 to 13 * 60 + 50, AgendaLogic.minutesOfUnit(5))
        assertEquals("第 14 节 20:40-21:20", 20 * 60 + 40 to 21 * 60 + 20, AgendaLogic.minutesOfUnit(14))
        val bad = AgendaLogic.minutesOfUnit(99)
        assertTrue("非法节次应返回合法区间", bad.first >= 0 && bad.first <= bad.second)
    }

    // ── 序列化 ────────────────────────────────────────────

    @Test
    fun `List 序列化往返后字段完全一致`() {
        val list = listOf(
            agenda(
                id = "s1", date = "2026-10-28", start = 600, end = 640,
                title = "开会", place = "行政楼", note = "带材料",
                colorIndex = 2, weekly = true, unitStart = 3, unitEnd = 4,
                remindLeads = listOf(0, 15),
            ),
            agenda(id = "s2", date = "2026-11-11", start = 780, end = 820, title = "夜跑"),
        )
        val ser = ListSerializer(Agenda.serializer())
        val raw = Net.json.encodeToString(ser, list)
        val back = Net.json.decodeFromString(ser, raw)

        assertEquals("往返后列表长度一致", list.size, back.size)
        assertEquals("往返后所有字段应完全一致", list, back)
    }

    @Test
    fun `缺少提醒字段的旧 JSON 能反序列化并补默认值`() {
        // 老缓存没有 remindLeads（v1.3.5 那个 remindMinutesBefore 也已经废掉），
        // 还夹带一个未来新增的未知字段：
        // 依赖 Net.json 的「缺字段用默认值 + ignoreUnknownKeys」。
        val old = """[{"id":"old1","title":"旧日程","date":"2026-10-28","startMinute":600,"endMinute":640,"futureField":"ignored"}]"""
        val back = Net.json.decodeFromString(ListSerializer(Agenda.serializer()), old)

        assertEquals("应解析出 1 条", 1, back.size)
        val a = back[0]
        assertEquals("id", "old1", a.id)
        assertEquals("title", "旧日程", a.title)
        assertEquals("date", "2026-10-28", a.date)
        assertEquals("startMinute", 600, a.startMinute)
        assertEquals("endMinute", 640, a.endMinute)
        assertEquals("缺 place 应补空串", "", a.place)
        assertEquals("缺 note 应补空串", "", a.note)
        assertEquals("缺 colorIndex 应补 -1", -1, a.colorIndex)
        assertEquals("缺 weekly 应补 false", false, a.weekly)
        assertEquals("缺 unitStart 应补 0", 0, a.unitStart)
        assertEquals("缺 unitEnd 应补 0", 0, a.unitEnd)
        assertEquals("缺 remindLeads 应补空列表（= 不提醒）", emptyList<Int>(), a.remindLeads)
    }

    // ── 新建日程的默认日期（致辉 2026-11-14 定） ──────────────────────

    @Test
    fun `新建默认日期-看本周取今天`() {
        val today = LocalDate.of(2026, 11, 14)

        assertEquals(
            "看的是本周，默认应落在今天",
            today,
            AgendaLogic.defaultDraftDate(sem, currentWeek = 10, viewingWeek = 10, today = today),
        )
    }

    @Test
    fun `新建默认日期-看别的周取那一周周一`() {
        val today = LocalDate.of(2026, 11, 14)

        assertEquals(
            "翻到第 12 周，默认应落在该周周一（9-07 + 11 周）",
            LocalDate.of(2026, 11, 23),
            AgendaLogic.defaultDraftDate(sem, currentWeek = 10, viewingWeek = 12, today = today),
        )
    }

    @Test
    fun `新建默认日期-翻回前面某周同样取该周周一`() {
        val today = LocalDate.of(2026, 11, 14)

        assertEquals(
            "第 2 周周一 = 9-14",
            LocalDate.of(2026, 9, 14),
            AgendaLogic.defaultDraftDate(sem, currentWeek = 10, viewingWeek = 2, today = today),
        )
    }

    @Test
    fun `新建默认日期-今天在假期里时夹回学期内`() {
        val winter = LocalDate.of(2027, 2, 1)

        // 假期里 currentWeek 会被夹到最后一两圈，viewingWeek 与它相等 → 用今天，
        // 但今天已经超出学期 → 必须夹回学期最后一天，否则表单一打开就是非法日期。
        assertEquals(
            "假期里看本周，应夹到学期最后一天",
            AgendaLogic.lastDay(sem),
            AgendaLogic.defaultDraftDate(
                sem,
                currentWeek = sem.totalWeeks,
                viewingWeek = sem.totalWeeks,
                today = winter,
            ),
        )
    }

    @Test
    fun `新建默认日期-越界的查看周先夹回合法周`() {
        val today = LocalDate.of(2026, 11, 14)

        assertEquals(
            "viewingWeek 超出总周数时应取最后一周周一",
            sem.dateOf(sem.totalWeeks, 1),
            AgendaLogic.defaultDraftDate(sem, currentWeek = 10, viewingWeek = 99, today = today),
        )
        assertEquals(
            "viewingWeek 为 0 时应取第 1 周周一",
            sem.dateOf(1, 1),
            AgendaLogic.defaultDraftDate(sem, currentWeek = 10, viewingWeek = 0, today = today),
        )
    }
}
