package com.gzucm.pocket

import com.gzucm.pocket.core.CourseSession
import com.gzucm.pocket.core.Semester
import com.gzucm.pocket.core.TimetableExport
import com.gzucm.pocket.core.TimetableExportJson
import com.gzucm.pocket.core.buildTimetableExport
import com.gzucm.pocket.core.decodeTimetableToken
import com.gzucm.pocket.core.encodeTimetableToken
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneOffset

/**
 * 课表结构化导出（2026-11-18，致辉要做网页导入）。
 *
 * 导出串是**对外契约**：改字段、改合并规则，用户网页就可能读错。这里锁住：
 *   · 同一门课跨周合并成一条模板，`weeks` 升序去重；教室/教学模式变了要拆开
 *   · 只收「有上课内容」的周 —— 没内容的周用 courses × weeks 推得出来，不该占字符
 *   · 作息表 14 节齐全（网页画网格直接用）
 *   · 只抓到部分周时 `coverage` 必须标出来
 *   · **分享串长度必须能过微信**：真实规模（25 门 × 18 周）压完要 < 1800 字符
 */
class TimetableExportTest {

    private val semester = Semester.of(
        code = "202601",
        name = "2026-2027 学年 第一学期",
        firstMonday = LocalDate.of(2026, 10, 26),
        totalWeeks = 18,
    )

    private fun session(
        name: String = "中医内科学",
        week: Int = 1,
        weekday: Int = 1,
        startUnit: Int = 1,
        endUnit: Int = 2,
        room: String = "新课室楼302",
        startTime: String = "08:30",
        endTime: String = "10:00",
        content: String = "",
        teachingMode: String = "",
    ) = CourseSession(
        name = name,
        code = "101227271",
        teachers = "李老师",
        week = week,
        weekday = weekday,
        startUnit = startUnit,
        endUnit = endUnit,
        startTime = startTime,
        endTime = endTime,
        room = room,
        category = "理论教学",
        content = content,
        teachingMode = teachingMode,
    )

    private fun build(
        sessions: List<CourseSession>,
        loadedWeeks: Set<Int> = (1..18).toSet(),
        includeContents: Boolean = false,
    ) = buildTimetableExport(
        semester = semester,
        sessions = sessions,
        loadedWeeks = loadedWeeks,
        versionName = "1.3.6",
        versionCode = 10306,
        now = OffsetDateTime.of(2026, 11, 18, 0, 30, 0, 0, ZoneOffset.ofHours(8)),
        includeContents = includeContents,
    )

    @Test
    fun `同一门课跨周合并成一条模板`() {
        val doc = build(listOf(session(week = 1), session(week = 2), session(week = 5)))

        assertEquals("三周应合并成一条课程", 1, doc.courses.size)
        assertEquals(listOf(1, 2, 5), doc.courses[0].weeks)
        assertEquals("没内容就不该有内容条目", 0, doc.contents.size)
    }

    @Test
    fun `教室变了要拆成两条模板`() {
        val doc = build(
            listOf(
                session(week = 1, room = "新课室楼302"),
                session(week = 2, room = "综教楼B202"),
            ),
        )
        assertEquals(2, doc.courses.size)
        assertEquals(listOf(1), doc.courses[0].weeks)
        assertEquals(listOf(2), doc.courses[1].weeks)
        assertTrue("两条模板不能共用一个 id", doc.courses[0].id != doc.courses[1].id)
    }

    @Test
    fun `教学模式不同也要拆开`() {
        val doc = build(
            listOf(
                session(week = 1, teachingMode = "PBL"),
                session(week = 2, teachingMode = ""),
            ),
        )
        assertEquals(2, doc.courses.size)
        assertEquals("PBL", doc.courses[0].teachingMode)
    }

    @Test
    fun `默认不带上课内容`() {
        val doc = build(listOf(session(week = 1, content = "咳嗽")))
        assertEquals("分享课表默认不带内容，能省一大截", 0, doc.contents.size)
    }

    @Test
    fun `显式开启后只有标了内容的周进 contents`() {
        val doc = build(
            listOf(
                session(week = 1, content = "咳嗽"),
                session(week = 2, content = ""),
                session(week = 3, content = "哮病"),
            ),
            includeContents = true,
        )
        assertEquals("两周有内容", 2, doc.contents.size)
        assertEquals(listOf("咳嗽", "哮病"), doc.contents.map { it.content })
        assertEquals("内容条目要指回模板", doc.courses[0].id, doc.contents[0].courseId)
        assertEquals(listOf(1, 3), doc.contents.map { it.week })
    }

    @Test
    fun `作息表十四节齐全且首尾时间正确`() {
        val doc = build(listOf(session()))
        assertEquals(14, doc.slots.size)
        assertEquals(1, doc.slots.first().unit)
        assertEquals("08:30", doc.slots.first().start)
        assertEquals("09:10", doc.slots.first().end)
        assertEquals(14, doc.slots.last().unit)
        assertEquals("20:40", doc.slots.last().start)
        assertEquals("21:20", doc.slots.last().end)
    }

    @Test
    fun `只抓到部分周时 coverage 标未完成`() {
        val doc = build(listOf(session(week = 1)), loadedWeeks = (1..8).toSet())
        assertFalse("只到第 8 周，不能标完整", doc.coverage.complete)
        assertEquals((1..8).toList(), doc.coverage.weeks)
    }

    @Test
    fun `全周都在时 coverage 标完整`() {
        val doc = build(listOf(session(week = 1)))
        assertTrue(doc.coverage.complete)
        assertEquals((1..18).toList(), doc.coverage.weeks)
    }

    @Test
    fun `课程按星期与节次排序且 id 稳定`() {
        val doc = build(
            listOf(
                session(name = "周三课", weekday = 3, startUnit = 1),
                session(name = "周一第二节", weekday = 1, startUnit = 3),
                session(name = "周一第一节", weekday = 1, startUnit = 1),
            ),
        )
        assertEquals(listOf("周一第一节", "周一第二节", "周三课"), doc.courses.map { it.name })
        assertEquals(listOf("c1", "c2", "c3"), doc.courses.map { it.id })
    }

    @Test
    fun `导出的 JSON 能被原样读回来`() {
        val doc = build(listOf(session(week = 1, content = "咳嗽"), session(week = 2)))
        val json = TimetableExportJson.encodeToString(TimetableExport.serializer(), doc)
        assertTrue("要带 kind 方便网页认文件", json.contains("gzucm-pocket-timetable"))
        assertTrue("要带 schemaVersion", json.contains("\"schemaVersion\": 1"))

        val back = TimetableExportJson.decodeFromString(TimetableExport.serializer(), json)
        assertEquals(doc.courses.size, back.courses.size)
        assertEquals(doc.contents.size, back.contents.size)
        assertEquals("2026-10-26", back.semester.firstMonday)
        assertEquals(doc.coverage.weeks, back.coverage.weeks)
    }

    @Test
    fun `空课表也能导出不炸`() {
        val doc = build(emptyList(), loadedWeeks = emptySet())
        assertEquals(0, doc.courses.size)
        assertEquals(0, doc.contents.size)
        assertEquals(14, doc.slots.size)
        assertFalse(doc.coverage.complete)
    }

    /**
     * 把课表铺成「每格」的集合：`课名|星期|起止节|教室|第几周`。
     *
     * 为什么要铺到周：串里丢掉教师之后，原本因为**老师不同**被拆开的两条课程
     * 会合并 —— 课程条数变少是正常的，但「某一周某一格在哪上课」不能少。
     */
    private fun cells(doc: com.gzucm.pocket.core.TimetableExport): Set<String> =
        doc.courses.flatMap { c ->
            c.weeks.map { w -> "${c.name}|${c.weekday}|${c.startUnit}-${c.endUnit}|${c.room}|$w" }
        }.toSet()

    @Test
    fun `分享串能解回原文档`() {
        val doc = build(
            listOf(
                session(week = 1, content = "咳嗽"),
                session(week = 2, room = "综教楼B202", weekday = 4, content = "哮病"),
            ),
        )
        val token = encodeTimetableToken(doc)
        assertTrue("要带版本前缀，网页端先校验", token.startsWith("GZP2:"))

        val back = decodeTimetableToken(token)
        assertEquals("每一格（周 × 课）都要还原", cells(doc), cells(back))
        assertEquals(doc.semester.totalWeeks, back.semester.totalWeeks)
        assertEquals(doc.semester.firstMonday, back.semester.firstMonday)
        assertEquals("作息表要跟着串走，网页画网格要用", doc.slots.map { "${it.start}-${it.end}" },
            back.slots.map { "${it.start}-${it.end}" })
        /*
         * 串里**不带**逐周上课内容 —— 这是 GZP1 时代就定下的取舍，
         * GZP2 继续：内容只在文件里。
         */
        assertEquals("串里不该有上课内容", 0, back.contents.size)
    }

    @Test
    fun `周次区间写法的往返`() {
        assertEquals("4", com.gzucm.pocket.core.weeksToCompact(listOf(4)))
        assertEquals("5-6", com.gzucm.pocket.core.weeksToCompact(listOf(5, 6)))
        assertEquals("1-18", com.gzucm.pocket.core.weeksToCompact((1..18).toList()))
        assertEquals("1,3,5-7", com.gzucm.pocket.core.weeksToCompact(listOf(1, 3, 5, 6, 7)))
        assertEquals("空列表写成空串", "", com.gzucm.pocket.core.weeksToCompact(emptyList()))

        assertEquals(listOf(1, 3, 5, 6, 7), com.gzucm.pocket.core.compactToWeeks("1,3,5-7"))
        assertEquals((1..18).toList(), com.gzucm.pocket.core.compactToWeeks("1-18"))
        assertEquals(emptyList<Int>(), com.gzucm.pocket.core.compactToWeeks(""))
    }

    @Test
    fun `真实规模的分享串必须能过微信单条上限`() {
        /*
         * 25 门/周 × 18 周，接近真实课表（每周 20~26 门）。
         * 微信单条文本上限各版本说法不一（2000~5400），按 2000 留余量卡 1800。
         * 这条是「不要偷偷把字段加回去、把分享串撑爆」的闸门。
         */
        val sessions = buildList {
            for (w in 1..18) {
                repeat(25) { i ->
                    add(
                        session(
                            name = "中医内科学（第${i + 1}门）",
                            week = w,
                            weekday = i % 7 + 1,
                            startUnit = (i / 7) * 2 + 1,
                            endUnit = (i / 7) * 2 + 2,
                            room = "新课室楼30${i % 9}",
                            content = if (w % 4 == 0) "第${w}章" else "",
                        ),
                    )
                }
            }
        }
        val token = encodeTimetableToken(build(sessions))
        assertTrue(
            "分享串 ${token.length} 字符，微信可能发不出去（上限卡 1800）",
            token.length < 1800,
        )
    }

    /**
     * 真正把 GZP1 撑爆的那种数据（2026-11-18 致辉实测 5124 字符）。
     *
     * 见习轮转：同一门课一周一个科室、一周一位带教老师，**教室和教师每周都不同**。
     * GZP1 按「课名+时段+教室+教师」合并，这种数据一周一条模板 —— 14 门课炸成 151 条。
     * GZP2 只按「课名+时段」分组，教室退化成叶子，才压得下去。
     */
    @Test
    fun `见习轮转（每周换科室）也不能把串撑爆`() {
        val rooms = listOf(
            "一附院乳腺科", "一附院介入血管外科", "一附院泌尿外科", "一附院甲状腺疝外科",
            "一附院皮肤科", "一附院结直肠外科", "一附院肛肠科", "一附院胃肠外科",
            "综教楼607/609骨科实训室", "综教楼820（智慧课室）", "一附院胆胰外科", "一附院胸外科",
        )
        val sessions = buildList {
            for (w in 1..20) {
                // 一周 8 个大节，块块都有课 —— 和见习周一样排满
                repeat(8) { i ->
                    add(
                        session(
                            name = "外科学(2)",
                            week = w,
                            weekday = i % 4 + 1,
                            startUnit = if (i < 4) 1 else 7,
                            endUnit = if (i < 4) 2 else 9,
                            // 每周整个科室，和真实数据一样每周都不一样
                            room = rooms[w % rooms.size] + "第${w}周",
                        ),
                    )
                }
            }
        }
        val doc = build(sessions, loadedWeeks = (1..20).toSet())
        assertTrue("轮转应该拆成很多条模板（这是被压的源头）", doc.courses.size > 100)

        val token = encodeTimetableToken(doc)
        assertTrue(
            "轮转课的分享串 ${token.length} 字符，微信发不出去（上限卡 1900）",
            token.length < 1900,
        )

        // 压完还得能还原：周次、教室一个都不能少
        val back = decodeTimetableToken(token)
        assertEquals(
            "解码后每一格（周 × 课 × 教室）都要和原来一致",
            cells(doc),
            cells(back),
        )
    }
}
