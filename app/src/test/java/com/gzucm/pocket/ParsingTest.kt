package com.gzucm.pocket

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

/**
 * 解析逻辑单测。用的都是 gzucm-probe 里抓到的**真实接口片段**，
 * 不是编的假数据 —— 这样校验的是真实契约。
 */
class ParsingTest {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    // ── 课表 jxcdmc2 展开 ────────────────────────────────────────────────

    private val tokenRe = Regex("^(.*)-(\\d{1,2})$")

    /** 复刻 Jw.fetchSessions 里的展开逻辑 */
    private fun expand(c2: String, mainRoom: String, zc: String): List<Pair<Int, String>> {
        if (c2.isNotBlank()) {
            return c2.split(",").mapNotNull { tok ->
                val t = tok.trim()
                if (t.isEmpty()) return@mapNotNull null
                val m = tokenRe.find(t) ?: return@mapNotNull null
                val week = m.groupValues[2].toIntOrNull() ?: return@mapNotNull null
                val room = m.groupValues[1].trim().ifBlank { mainRoom }
                week to room
            }
        }
        return zc.split(",").mapNotNull { w ->
            val week = w.trim().toIntOrNull() ?: return@mapNotNull null
            week to mainRoom
        }
    }

    @Test
    fun `正常逐周教室串`() {
        // 真实值：综教楼804（智慧课室）-8,综教楼804（智慧课室）-9
        val r = expand("综教楼804（智慧课室）-8,综教楼804（智慧课室）-9", "", "")
        assertEquals(2, r.size)
        assertEquals(8 to "综教楼804（智慧课室）", r[0])
        assertEquals(9 to "综教楼804（智慧课室）", r[1])
    }

    @Test
    fun `教室名为空时回退主教室`() {
        // 真实坑：只有周次，没有教室名
        val r = expand("-12,-13", "新课室楼302", "")
        assertEquals(2, r.size)
        assertEquals(12 to "新课室楼302", r[0])
        assertEquals(13 to "新课室楼302", r[1])
    }

    @Test
    fun `见习轮转多科室各自不同周`() {
        val raw = "一附院泌尿外科-14,一附院肛肠科-12,一附院甲状腺疝外科-10"
        val r = expand(raw, "", "")
        assertEquals(3, r.size)
        assertEquals(14 to "一附院泌尿外科", r[0])
        assertEquals(10 to "一附院甲状腺疝外科", r[2])
    }

    @Test
    fun `无逐周教室时回退 zc 周次`() {
        val r = expand("", "综教楼309", "1,14,2,8")
        assertEquals(4, r.size)
        assertEquals(listOf(1, 14, 2, 8), r.map { it.first })
        assertTrue(r.all { it.second == "综教楼309" })
    }

    @Test
    fun `两位数周次能正确解析`() {
        val r = expand("综教楼603/605外科缝合打结实训室-15,综教楼607/609骨科实训室-16", "", "")
        assertEquals(15 to "综教楼603/605外科缝合打结实训室", r[0])
        assertEquals(16 to "综教楼607/609骨科实训室", r[1])
    }

    // ── 校历解析 ────────────────────────────────────────────────────────

    @Test
    fun `从校历响应推出第一周周一`() {
        // 真实结构：外层数组是分组，每组第一行表头，之后每周一行
        val raw = """
        {"code":0,"data":[[
          [{"type":"head","value":"周次"},
           {"type":"xq","value":"日","fullText":"星期日"},
           {"type":"xq","value":"一","fullText":"星期一"}],
          [{"type":"zc","value":"1","fullText":"第1周"},
           {"type":"day","fullText":"2026-09-06","month":"2026-09"},
           {"type":"day","fullText":"2026-09-07","month":"2026-09"}],
          [{"type":"zc","value":"2","fullText":"第2周"},
           {"type":"day","fullText":"2026-09-13","month":"2026-09"},
           {"type":"day","fullText":"2026-09-14","month":"2026-09"}]
        ]]}""".trimIndent()

        val root = json.parseToJsonElement(raw).jsonObject
        var firstSunday: String? = null
        var maxWeek = 0
        for (group in root["data"]!!.jsonArray) {
            for (rowEl in group.jsonArray) {
                val row = rowEl.jsonArray
                val head = row[0].jsonObject
                if (head.str("type") != "zc") continue
                val week = head.str("value").toIntOrNull() ?: 0
                if (week > maxWeek) maxWeek = week
                if (week == 1) {
                    firstSunday = row.drop(1)
                        .map { it.jsonObject }
                        .firstOrNull { it.str("type") == "day" }
                        ?.str("fullText")
                }
            }
        }
        assertNotNull(firstSunday)
        assertEquals("2026-09-06", firstSunday)
        val firstMonday = LocalDate.parse(firstSunday).plusDays(1)
        assertEquals(LocalDate.of(2026, 9, 7), firstMonday)
        assertEquals(2, maxWeek)
    }

    // ── 电费解析 ────────────────────────────────────────────────────────

    @Test
    fun `电费余额响应解析`() {
        val raw = """{"ret":true,"msg":"查询成功","obj":{"roomNum":"5770",
            "schoolAreaNo":"2","buildingNo":"5754","powerBalance":"11.78","du":"度"}}"""
        val root = json.parseToJsonElement(raw).jsonObject
        assertEquals("true", root.str("ret"))
        val obj = root["obj"]!!.jsonObject
        assertEquals(11.78, obj.str("powerBalance").toDouble(), 0.001)
        assertEquals("度", obj.str("du"))
    }

    @Test
    fun `电费明细 HTML 能解析出逐日用量`() {
        // 真实片段（两个 li）
        val html = """
        <li class="mui-table-view-cell"> <a href="javascript:;">
        <div class="mui-row customize_row"><div class="mui-col-sm-8 mui-col-xs-8 accountName">日用电量(度)</div>
        <div class="mui-col-sm-4 mui-col-xs-4 text-right total_income">0.18</div></div>
        <div class="mui-row customize_row"><div class="mui-col-sm-8 mui-col-xs-8 accountName">剩余电量(度)</div>
        <div class="mui-col-sm-4 mui-col-xs-4 text-right total_expend">73.86</div></div>
        <div class="mui-row customize_row"><div class="mui-col-sm-8 mui-col-xs-8 accountName">总用电量(度)</div>
        <div class="mui-col-sm-4 mui-col-xs-4 text-right total_expend">28.80</div></div>
        <div class="mui-row customize_row1"><div class="mui-col-sm-12 mui-col-xs-12 text-left accountTime">2026-10-01 </div></div>
        </a> </li>
        <li class="mui-table-view-cell"> <a href="javascript:;">
        <div class="mui-row customize_row"><div class="mui-col-sm-8 mui-col-xs-8 accountName">日用电量(度)</div>
        <div class="mui-col-sm-4 mui-col-xs-4 text-right total_income">1.08</div></div>
        <div class="mui-row customize_row"><div class="mui-col-sm-8 mui-col-xs-8 accountName">剩余电量(度)</div>
        <div class="mui-col-sm-4 mui-col-xs-4 text-right total_expend">72.78</div></div>
        <div class="mui-row customize_row"><div class="mui-col-sm-8 mui-col-xs-8 accountName">总用电量(度)</div>
        <div class="mui-col-sm-4 mui-col-xs-4 text-right total_expend">29.88</div></div>
        <div class="mui-row customize_row1"><div class="mui-col-sm-12 mui-col-xs-12 text-left accountTime">2026-10-02 </div></div>
        </a> </li>
        """.trimIndent()

        val blockRe = Regex("""<li class="mui-table-view-cell">(.*?)</li>""", RegexOption.DOT_MATCHES_ALL)
        val dateRe = Regex("""accountTime">\s*(\d{4}-\d{2}-\d{2})""")
        val kvRe = Regex("""accountName">([^<]+)</div>\s*<div[^>]*>\s*([\d.]+)""")

        val rows = blockRe.findAll(html).mapNotNull { b ->
            val body = b.groupValues[1]
            val date = dateRe.find(body)?.groupValues?.get(1) ?: return@mapNotNull null
            var usage = 0.0
            var remain = 0.0
            var total = 0.0
            for (kv in kvRe.findAll(body)) {
                val label = kv.groupValues[1]
                val v = kv.groupValues[2].toDoubleOrNull() ?: continue
                when {
                    label.contains("日用电") -> usage = v
                    label.contains("剩余电量") -> remain = v
                    label.contains("总用电") -> total = v
                }
            }
            Triple(date, usage, remain) to total
        }.toList()

        assertEquals(2, rows.size)
        assertEquals("2026-10-01", rows[0].first.first)
        assertEquals(0.18, rows[0].first.second, 0.001)
        assertEquals(73.86, rows[0].first.third, 0.001)
        assertEquals("2026-10-02", rows[1].first.first)
        assertEquals(1.08, rows[1].first.second, 0.001)
    }

    // ── 成绩解析 ────────────────────────────────────────────────────────

    @Test
    fun `成绩行解析`() {
        val raw = """{"data":"","total":1,"rows":[{
            "kcmc":"临床预备课(1)","kcdm":"0446","zcjfs":87.00,"cjjd":3.70,
            "xf":2.0,"xnxqmc":"2025-2026-2","kcdlmc":"实践课","bz":"成绩导入"}]}"""
        val root = json.parseToJsonElement(raw).jsonObject
        val rows = root["rows"]!!.jsonArray
        assertEquals(1, rows.size)
        val o = rows[0].jsonObject
        assertEquals("临床预备课(1)", o.str("kcmc"))
        assertEquals(87.0, o.str("zcjfs").toDouble(), 0.001)
        assertEquals(3.70, o.str("cjjd").toDouble(), 0.001)
        assertEquals(2.0, o.str("xf").toDouble(), 0.001)
        assertEquals("2025-2026-2", o.str("xnxqmc"))
    }

    @Test
    fun `数字型字段也能取成字符串`() {
        val o = json.parseToJsonElement("""{"xq":3,"ps":"06","zcj":502.0}""").jsonObject
        assertEquals("3", o.str("xq"))
        assertEquals("06", o.str("ps"))
        assertEquals("502.0", o.str("zcj"))
    }

    // ── 作息表 ──────────────────────────────────────────────────────────

    @Test
    fun `节次时间表跳过不存在的 5 与 10 11`() {
        val slots = com.gzucm.pocket.ui.schedule.SlotTimes
        assertEquals("08:30", slots[1])
        assertEquals("10:20", slots[3])
        assertEquals("14:00", slots[6])
        assertEquals("16:30", slots[9])
        assertEquals("19:50", slots[13])
        assertEquals("20:40", slots[14])
        // 广中医实际不存在 5、10、11 节
        assertEquals(null, slots[5])
        assertEquals(null, slots[10])
        assertEquals(null, slots[11])
        // 共 11 个有效节次
        assertEquals(11, slots.size)
    }
}

private fun kotlinx.serialization.json.JsonObject.str(key: String): String {
    val el = this[key] ?: return ""
    return runCatching { el.jsonPrimitive.content }.getOrDefault("")
}