package com.gzucm.pocket

import com.gzucm.pocket.data.Jw
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 按周课表解析（`zc` 传具体周次）。
 *
 * 样本是**真机抓到的第 8 周响应**（gzucm-probe/data/week_8.json 摘了 3 条），
 * 不是编的。关键点：总表没有 `sknrjj`（上课内容）和 `qsrq`（真实日期），
 * 按周查才有。
 */
class WeeklyScheduleTest {

    private fun sample(): String =
        javaClass.classLoader!!.getResourceAsStream("week8_sample.json")!!
            .bufferedReader(Charsets.UTF_8).readText()

    @Test
    fun `能解析出三条课`() {
        val list = Jw.parseWeekly(sample(), fallbackWeek = 8)
        assertEquals(3, list.size)
        // 按 星期 → 起始节 排序
        assertTrue(list.zipWithNext().all { (a, b) ->
            a.weekday < b.weekday || (a.weekday == b.weekday && a.startUnit <= b.startUnit)
        })
    }

    @Test
    fun `上课内容被正确读出`() {
        val list = Jw.parseWeekly(sample(), fallbackWeek = 8)
        // 样本首条：中医人文与社会实践课(4)，内容「临床医学人文实践3」（老师名已脱敏）
        val s = list.first { it.name.contains("中医人文与社会实践课") }
        assertEquals("临床医学人文实践3", s.content)
        assertEquals("张老师", s.teachers)  // 样本已脱敏
    }

    @Test
    fun `真实日期被读出`() {
        val list = Jw.parseWeekly(sample(), fallbackWeek = 8)
        list.forEach {
            assertTrue("日期应为 yyyy-MM-dd，实际 ${it.date}", Regex("""\d{4}-\d{2}-\d{2}""").matches(it.date))
        }
        assertEquals("2026-10-31", list.first { it.name.contains("中医人文") }.date)
    }

    @Test
    fun `周次取自 zc 字段`() {
        val list = Jw.parseWeekly(sample(), fallbackWeek = 8)
        assertTrue("应有周次 8", list.all { it.week == 8 })
    }

    @Test
    fun `节次与时间都正确`() {
        val list = Jw.parseWeekly(sample(), fallbackWeek = 8)
        val s = list.first { it.name.contains("中医人文") }
        assertEquals(2, s.startUnit)
        assertEquals(3, s.endUnit)
        assertEquals("09:20", s.startTime)
        assertEquals("11:00", s.endTime)
    }

    @Test
    fun `空响应不会崩`() {
        assertTrue(Jw.parseWeekly("""{"code":0,"data":[]}""", 8).isEmpty())
        assertTrue(Jw.parseWeekly("""{"code":-401,"message":"尚未登录"}""", 8).isEmpty())
        assertTrue(Jw.parseWeekly("not json", 8).isEmpty())
    }

    @Test
    fun `缺字段的行会被跳过而不是崩`() {
        val bad = """{"code":0,"data":[{"kcmc":"","xq":"1"},{"xq":"1"},{"kcmc":"正常课","xq":"9"}]}"""
        assertTrue(Jw.parseWeekly(bad, 8).isEmpty())
    }

    @Test
    fun `单周多周次时会逐周展开`() {
        val json = """
            {"code":0,"data":[{"kcmc":"测试课","xq":"3","ps":"01","pe":"02",
             "zc":"7,8","qssj":"08:30:00","jssj":"10:00:00","sknrjj":"内容A"}]}
        """.trimIndent()
        val list = Jw.parseWeekly(json, 8)
        assertEquals(2, list.size)
        assertEquals(listOf(7, 8), list.map { it.week }.sorted())
        assertTrue(list.all { it.content == "内容A" })
    }
}
