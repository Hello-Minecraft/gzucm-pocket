package com.gzucm.pocket

import com.gzucm.pocket.data.Jw
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

/**
 * 本科和研究生的学期码不能混用。
 *
 * 背景（2026-11-01 致辉报「本科系统无法获取课表」）：
 *   登录研究生时，`doSyncGraduate` 里多写了一句
 *   `store.semesterCode = term.code` —— 把研究生的 "48" 写进了
 *   **本科专用的** `semesterCode` 字段。
 *
 *   之后切回本科，`doSync` 里：
 *     val code = store.semesterCode ?: Jw.guessSemesterCode()
 *     → 拿到 "48"（不是 null，所以兜底永远不触发）
 *     → Jw.fetchSemester("48") 请求 `/new/xlxx/data?xnxqdm=48` 必然失败
 *     → 再拿 "48" 算「上一个学期」也失败
 *     → 报「无法获取教学周历」，本科课表整个空的
 */
class SemesterCodeTest {

    // ── 本科格式 ────────────────────────────────────────────

    @Test
    fun `本科秋季学期码合法`() {
        assertTrue(Jw.isValidSemesterCode("202601"))
    }

    @Test
    fun `本科春季学期码合法`() {
        assertTrue(Jw.isValidSemesterCode("202602"))
    }

    @Test
    fun `研究生的短码不是合法本科码`() {
        // 这就是污染源：研究生 term.code = "48"
        assertFalse("研究生的 48 绝不能被当成本科学期码", Jw.isValidSemesterCode("48"))
        assertFalse(Jw.isValidSemesterCode("47"))
    }

    @Test
    fun `各种畸形值都拦住`() {
        for (bad in listOf("", "  ", "20260", "2026011", "abc601", "198901", "202603", "2026" + "00")) {
            assertFalse("「$bad」不该通过", Jw.isValidSemesterCode(bad))
        }
    }

    @Test
    fun `按日期猜的学期码本身必须合法`() {
        // 防止 guessSemesterCode 哪天改坏了，和校验规则自相矛盾
        for (m in 1..12) {
            val d = LocalDate.of(2026, m, 15)
            val code = Jw.guessSemesterCode(d)
            assertTrue(
                "2026-${m} 月猜出来的 $code 应该合法",
                Jw.isValidSemesterCode(code),
            )
        }
    }

    // ── 猜学期的边界 ────────────────────────────────────────

    @Test
    fun `八月起算新学年秋季学期`() {
        assertEquals("202601", Jw.guessSemesterCode(LocalDate.of(2026, 8, 1)))
        assertEquals("202601", Jw.guessSemesterCode(LocalDate.of(2026, 11, 1)))
        assertEquals("202601", Jw.guessSemesterCode(LocalDate.of(2026, 12, 31)))
    }

    @Test
    fun `八月前算上一学年春季学期`() {
        assertEquals("202502", Jw.guessSemesterCode(LocalDate.of(2026, 7, 31)))
        assertEquals("202502", Jw.guessSemesterCode(LocalDate.of(2026, 3, 1)))
        assertEquals("202502", Jw.guessSemesterCode(LocalDate.of(2026, 1, 1)))
    }

    // ── 上一个学期的换算 ────────────────────────────────────

    @Test
    fun `秋季的上一个学期是上一年春季`() {
        assertEquals("202502", Jw.previousSemesterCode("202601"))
    }

    @Test
    fun `春季的上一个学期是同一年秋季`() {
        assertEquals("202601", Jw.previousSemesterCode("202602"))
    }

    @Test
    fun `被污染的码算上一个学期会越算越离谱`() {
        /*
         * 这条不是断言「应该怎样」，而是记录 bug 有多严重：
         * "48".take(4).toIntOrNull() = 48 → 当成 48 年，
         * 得出 "4702" 这种完全不存在的学期码。
         */
        val next = Jw.previousSemesterCode("48")
        assertFalse(
            "「48」既不以 01 结尾，take(4) 也不是年份 —— 算出来必然是垃圾值（实测 $next）",
            Jw.isValidSemesterCode(next),
        )
    }

    // ── 真正覆盖 bug 的那一条 ──────────────────────────────
    //
    // 教训：前面 10 条都只测 isValidSemesterCode / guessSemesterCode
    // 这两个叶子函数，把 doSync 里的校验去掉后**全绿**。
    // 下面这条测 resolveSemesterCode —— 它是 doSync 真正调用的入口。

    @Test
    fun `缓存被研究生码污染时，resolve 必须回退到按日期猜`() {
        val nov = LocalDate.of(2026, 11, 1)

        // 中毒现场：store.semesterCode 是研究生的 "48"
        assertEquals(
            "绝不能拿 48 去请求本科校历",
            "202601",
            Jw.resolveSemesterCode("48", nov),
        )

        // 正常情况：缓存合法就沿用
        assertEquals("202601", Jw.resolveSemesterCode("202601", nov))
        assertEquals(
            "缓存写的是春季学期，也要原样沿用（用户可能在看历史学期）",
            "202602",
            Jw.resolveSemesterCode("202602", nov),
        )

        // 没有缓存
        assertEquals("202601", Jw.resolveSemesterCode(null, nov))
    }

    @Test
    fun `二月时缓存的脏值也要回退成春季学期`() {
        val feb = LocalDate.of(2026, 2, 20)
        assertEquals(
            "8 月前猜出来是上一年春季",
            "202502",
            Jw.resolveSemesterCode("48", feb),
        )
    }

    @Test
    fun `resolve 出来的值拿去请求校历一定是本科格式`() {
        // 不管输入多脏，输出必须符合本科学期码格式
        val dirty = listOf(null, "", "48", "47", "abc", "2026", "999999")
        for (d in dirty) {
            val code = Jw.resolveSemesterCode(d, LocalDate.of(2026, 11, 1))
            assertTrue("输入「$d」得到「$code」不合格式", Jw.isValidSemesterCode(code))
        }
    }
}