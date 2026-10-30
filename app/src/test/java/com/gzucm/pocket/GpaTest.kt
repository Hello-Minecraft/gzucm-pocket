package com.gzucm.pocket

import com.gzucm.pocket.core.CourseGrade
import com.gzucm.pocket.core.Gpa
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 加权绩点口径：必修 + 限选，任选（公共选修课）不计。
 *
 * 大类名列表取自真实数据（2026-10-30 抓的 83 门课），
 * 特别防「通识课」这个坑：里面装的是体育、毛概、大学英语，也都是必修。
 */
class GpaTest {

    private fun g(cat: String, jd: Double, xf: Double, score: Double = 85.0) = CourseGrade(
        term = "2025-2026-1", name = "x", score = score, gpa = jd,
        credit = xf, category = cat, nature = "", remark = "", passed = score >= 60,
    )

    @Test
    fun `公共选修课不计入`() {
        assertFalse(Gpa.counted("公共选修课"))
        assertFalse(Gpa.counted("任选课"))
    }

    @Test
    fun `专业限选课要计入`() {
        assertTrue(Gpa.counted("专业限选课"))
    }

    @Test
    fun `通识课要计入 里面其实是必修`() {
        assertTrue(Gpa.counted("通识课"))
    }

    @Test
    fun `必修类全都计入`() {
        listOf("公共必修课", "公共基础课", "专业基础课", "专业课", "实践课", "其它")
            .forEach { assertTrue("$it 应计入", Gpa.counted(it)) }
    }

    @Test
    fun `排除公选后加权绩点按学分加权`() {
        val list = listOf(
            g("专业基础课", 4.0, 5.0),   // 20
            g("专业限选课", 3.0, 2.0),   // 6
            g("公共选修课", 2.0, 3.0),   // 不计
        )
        val (gpa, credit) = Gpa.weighted(list)
        assertEquals(3.714, gpa, 0.001)   // 26 / 7
        assertEquals(7.0, credit, 0.001)
    }

    @Test
    fun `不及格的课不计入`() {
        val list = listOf(
            g("专业基础课", 4.0, 5.0, score = 90.0),
            g("专业基础课", 0.0, 3.0, score = 40.0),
        )
        val (gpa, _) = Gpa.weighted(list)
        assertEquals(4.0, gpa, 0.001)
    }
}
