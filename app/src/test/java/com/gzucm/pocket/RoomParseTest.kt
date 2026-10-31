package com.gzucm.pocket

import com.gzucm.pocket.data.Jw
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 上课地点的解析守卫。
 *
 * 用户反馈「个别课程读不到上课地点，教务系统有地点但软件读不到」。
 * 查下来 App 只读 `jxcdmc` 一个字段，且 `str()` 不 trim ——
 * 教务返回全角空格时会被判成空、整行「地点」都不显示。
 *
 * 样本来自 app/src/test/resources/week8_sample.json 的真实结构。
 */
class RoomParseTest {

    private fun week(
        name: String = "测试课",
        jxcdmc: String? = null,
        jxcdmc2: String? = null,
        cdmc: String? = null,
    ): String {
        val fields = buildString {
            append("\"kcmc\":\"$name\"")
            append(",\"xq\":7,\"ps\":\"01\",\"pe\":\"02\",\"zc\":8")
            append(",\"qssj\":\"08:30:00\",\"jssj\":\"10:00:00\"")
            append(",\"teaxms\":\"张老师\",\"kcdm\":\"001\",\"bgcolor\":\"#5FB878\"")
            if (jxcdmc != null) append(",\"jxcdmc\":\"$jxcdmc\"")
            if (jxcdmc2 != null) append(",\"jxcdmc2\":\"$jxcdmc2\"")
            if (cdmc != null) append(",\"cdmc\":\"$cdmc\"")
        }
        return """{"code":0,"data":[{${fields}}]}"""
    }

    @Test
    fun `jxcdmc 有值时直接用它`() {
        val r = Jw.parseWeekly(week(jxcdmc = "[三元里]综教楼601"), 8)
        assertEquals("[三元里]综教楼601", r.first().room)
    }

    @Test
    fun `jxcdmc 是空字符串时不该编造地点`() {
        val r = Jw.parseWeekly(week(jxcdmc = ""), 8)
        assertEquals("", r.first().room)
    }

    @Test
    fun `全角空格要当成空`() {
        // 教务网页上会渲染成一个方框，肉眼像是有值
        val r = Jw.parseWeekly(week(jxcdmc = "　"), 8)
        assertEquals("空场地不该带出全角空格", "", r.first().room)
    }

    @Test
    fun `jxcdmc 为空时回退到逐周教室 jxcdmc2`() {
        val r = Jw.parseWeekly(week(jxcdmc = "", jxcdmc2 = "实训楼A-8"), 8)
        assertEquals("实训楼A", r.first().room)
    }

    @Test
    fun `逐周教室取匹配当前周的那一段`() {
        val r = Jw.parseWeekly(week(jxcdmc = "", jxcdmc2 = "实训楼A-3,实训楼B-8,实训楼C-12"), 8)
        assertEquals("实训楼B", r.first().room)
    }

    @Test
    fun `都没有时回退到 cdmc`() {
        val r = Jw.parseWeekly(week(jxcdmc = "", cdmc = "备用场地名"), 8)
        assertEquals("备用场地名", r.first().room)
    }

    @Test
    fun `jxcdmc 有值时不采用逐周教室`() {
        // 主字段权威，别被逐周串覆盖
        val r = Jw.parseWeekly(week(jxcdmc = "主场地", jxcdmc2 = "别的场地-8"), 8)
        assertEquals("主场地", r.first().room)
    }
}
