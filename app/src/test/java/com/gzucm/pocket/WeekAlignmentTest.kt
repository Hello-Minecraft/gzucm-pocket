package com.gzucm.pocket

import com.gzucm.pocket.core.Semester
import com.gzucm.pocket.data.Jw
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDate

/**
 * 周次与日期对齐的守卫。
 *
 * 用户反馈「周日的课对不上日期」。根因是两套周制不同：
 *   · 教务：「第 N 周」= 周日 ~ 周六
 *   · App ：「第 N 周」= 周一 ~ 周日
 *
 * 所以传 `zc=8` 拿回来的是 10-25(周日)~10-31(周六)，
 * 如果照抄 zc 当 week，App 日历里第 8 周却是 10-26~11-01 ——
 * 「第 8 周周日」列上会挂着 10-25 的课，日期和周次互相矛盾。
 *
 * 修法是改用每行的 `qsrq`（真实日期）反推周次。
 */
class WeekAlignmentTest {

    /** 第 1 周周一 = 2026-09-07（对应教务第 1 周周日 09-06） */
    private val semester = Semester.of(
        code = "202601",
        name = "2026-2027-1",
        firstMonday = LocalDate.parse("2026-09-07"),
        totalWeeks = 20,
    )

    /**
     * 构造一条按周接口的记录。
     *
     * `xq`（星期）由日期推出来而不是手写 —— 手写很容易和 qsrq 自相矛盾，
     * 那样测试测的就不是代码而是我编的数据。
     * `zc` 传 null 时不写这个字段，用来验证「没有周次时退回接口参数」。
     */
    private fun weekJson(name: String, qsrq: String, zc: Int? = null): String {
        val xq = LocalDate.parse(qsrq).dayOfWeek.value
        val zcField = if (zc != null) """"zc":$zc,""" else ""
        return """{"code":0,"data":[{
            "kcmc":"$name","xq":$xq,"ps":"06","pe":"07",$zcField
            "qssj":"14:00:00","jssj":"15:30:00","teaxms":"王老师",
            "kcdm":"108061629","qsrq":"$qsrq","jxcdmc":"[三元里]综教楼601"
        }]}"""
    }

    @Test
    fun `教务第8周的周日课要落到App第7周`() {
        // 10-25 是周日；按周一制它属于第 7 周（10-19 ~ 10-25）
        val r = Jw.parseWeekly(weekJson("镜下应用局部解剖学", "2026-10-25", zc = 8), 8, semester)
        assertEquals(7, r.first().week)
        assertEquals("周日该落在第7周", 7, r.first().weekday)
    }

    @Test
    fun `教务第9周的周日课要落到App第8周`() {
        // 11-01 是周日；按周一制它属于第 8 周（10-26 ~ 11-01）
        val r = Jw.parseWeekly(weekJson("腔镜核心技能实训", "2026-11-01", zc = 9), 9, semester)
        assertEquals(8, r.first().week)
    }

    @Test
    fun `周一至周六的课周次不受影响`() {
        // 10-26 周一 ~ 10-31 周六 都属于 App 第 8 周
        listOf("2026-10-26", "2026-10-28", "2026-10-31").forEach { d ->
            val r = Jw.parseWeekly(weekJson("测试课", d, zc = 8), 8, semester)
            assertEquals("$d 应该是第 8 周", 8, r.first().week)
        }
    }

    @Test
    fun `周次与日期必须自洽`() {
        // 反过来用 week + weekday 还原日期，应该和 qsrq 一致
        listOf("2026-10-25", "2026-10-26", "2026-11-01", "2026-11-02").forEach { d ->
            val r = Jw.parseWeekly(weekJson("测试课", d, zc = 8), 8, semester).first()
            val back = semester.dateOf(r.week, r.weekday)
            assertEquals("$d 反推出的日期对不上", LocalDate.parse(d), back)
        }
    }

    @Test
    fun `没有 qsrq 时退回接口传的周次`() {
        val json = """{"code":0,"data":[{
            "kcmc":"测试课","xq":3,"ps":"01","pe":"02","zc":12,
            "qssj":"08:30:00","jssj":"10:00:00","kcdm":"001"
        }]}"""
        val r = Jw.parseWeekly(json, 12, semester)
        assertEquals(12, r.first().week)
    }

    @Test
    fun `日期超出学期范围时退回接口周次`() {
        // 学期前/后的日期不该被算成第 0 周或第 21 周
        // 不给 zc，验证走的是接口传进来的 fallbackWeek
        val r = Jw.parseWeekly(weekJson("测试课", "2027-05-01"), 10, semester)
        assertEquals(10, r.first().week)
    }
}
