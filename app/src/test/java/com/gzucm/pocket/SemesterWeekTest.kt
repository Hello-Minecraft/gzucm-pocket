package com.gzucm.pocket

import com.gzucm.pocket.core.Campus
import com.gzucm.pocket.core.CourseSession
import com.gzucm.pocket.data.Yjs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

/**
 * 研究生学期第一周的推算。
 *
 * 背景（2026-11-01 致辉报的 bug）：
 *   新账号登录后「第 1 周」是空白的，第 2 周才有课。
 *   教务系统也这样显示没错，但**第一周起始日期应该是 9-07**，
 *   而 App 里当前周显示成了 9-14（错后一周）。
 *
 * 根因：`buildSemester` 拿「最早一节课的日期」往前推当第一周。
 *   研究生第 1 周常常没课，最早数据是第 2 周的 → 整体错后一周。
 *
 * 正解：每节课自带「日期 + 周次」，反过来算第一周周一。
 *   该周周一 = 日期 - (星期 - 1)
 *   第一周周一 = 该周周一 - (周次 - 1) * 7
 */
class SemesterWeekTest {

    private fun s(
        week: Int,
        weekday: Int,
        date: String,
        name: String = "某课",
    ) = CourseSession(
        name = name,
        code = "",
        teachers = "",
        week = week,
        weekday = weekday,
        startUnit = 1,
        endUnit = 2,
        startTime = "08:30",
        endTime = "09:10",
        room = "",
        category = "",
        mergedClasses = "",
        date = date,
        campus = Campus.GRADUATE,
    )

    // ── 核心 bug：第 1 周没课时不能错位 ─────────────────────

    @Test
    fun `第 1 周没课时仍能算出正确的第一周`() {
        /*
         * 致辉这个账号的真实情况：
         *   第 1 周（9-07 ~ 9-13）完全没课
         *   最早的数据是第 2 周（9-14 起）
         *
         * 旧逻辑拿 9-14 当第一周 → 全学期错后一周。
         * 新逻辑用「周次 2 + 日期 9-14」反推 → 9-07，正确。
         */
        val sessions = listOf(
            s(2, 1, "2026-09-14"),
            s(2, 2, "2026-09-15"),
            s(3, 1, "2026-09-21"),
        )
        assertEquals(
            "第 1 周无课时，必须靠周次+日期反推，不能拿最早日期当第一周",
            LocalDate.of(2026, 9, 7),
            Yjs.inferFirstMonday(sessions),
        )
    }

    @Test
    fun `用第 7 周的真实数据反推也是 9-07`() {
        // 2026-10-19 是周一，属于第 7 周 → 第 1 周周一 = 10-19 - 6 周 = 9-07
        val sessions = listOf(
            s(7, 1, "2026-10-19", "医学分子生物学基础"),
            s(7, 2, "2026-10-20", "英语（专业型硕士）"),
            s(7, 3, "2026-10-21", "自然辨证法概论"),
            s(7, 4, "2026-10-22", "科技论文配图设计与制作"),
            s(7, 5, "2026-10-23", "网络信息资源获取与管理"),
        )
        assertEquals(
            LocalDate.of(2026, 9, 7),
            Yjs.inferFirstMonday(sessions),
        )
    }

    @Test
    fun `大量真实数据投票，个别脏日期不影响结果`() {
        // 20 条正确 + 2 条日期写错（学校数据偶尔会错配）
        val good = (2..11).flatMap { w ->
            val monday = LocalDate.of(2026, 9, 7).plusWeeks((w - 1).toLong())
            (1..2).map { d -> s(w, d, monday.plusDays((d - 1).toLong()).toString()) }
        }
        val bad = listOf(
            s(5, 1, "2026-10-25"),   // 周日写成周一，算出来是 9-13
            s(9, 3, "2026-01-01"),   // 完全离谱
        )
        assertEquals(
            "多数样本一致时，个别脏数据应该被投票压下去",
            LocalDate.of(2026, 9, 7),
            Yjs.inferFirstMonday(good + bad),
        )
    }

    @Test
    fun `完全没有日期时退回今天，不崩`() {
        val sessions = listOf(
            s(3, 1, ""),
            s(4, 2, ""),
        )
        val r = Yjs.inferFirstMonday(sessions)
        assertEquals("没有日期只能退回今天", LocalDate.now(), r)
    }

    @Test
    fun `空列表也不崩`() {
        assertEquals(LocalDate.now(), Yjs.inferFirstMonday(emptyList()))
    }

    // ── 与应用侧换算的一致性 ───────────────────────────────

    @Test
    fun `反推出的第一周能正确算出当前周`() {
        /*
         * 用户的实际情况：9-07 是第一周周一，今天是 11-01（周日）。
         * 11-01 - 9-07 = 55 天，55/7 + 1 = 第 8 周。
         */
        val sessions = listOf(s(7, 1, "2026-10-19"))
        val firstMonday = Yjs.inferFirstMonday(sessions)
        val sem = com.gzucm.pocket.core.Semester.of(
            code = "48", name = "2026-2027秋学期",
            firstMonday = firstMonday, totalWeeks = 20,
        )

        assertEquals(
            "2026-11-01 应该是第 8 周",
            8,
            sem.weekOf(LocalDate.of(2026, 11, 1)),
        )
        assertEquals(
            "第 1 周周一必须是 9-07",
            LocalDate.of(2026, 9, 7),
            sem.dateOf(1, 1),
        )
        assertEquals(
            "第 7 周周一是 10-19",
            LocalDate.of(2026, 10, 19),
            sem.dateOf(7, 1),
        )
    }

    @Test
    fun `错用最早日期会让整个学期少一周`() {
        /*
         * 这条说明 bug 的真实表现。
         *
         * 旧逻辑拿「最早一节课的日期」9-14 当第一周周一：
         *   → 11-01 被算成**第 7 周**（正确应该是第 8 周）
         *   → 于是用户看到的「第 7 周」里装的是真正的第 7 周课，
         *     但页面顶部写「第 7 周」而数据其实对——不对，
         *     数据是按 week 字段筛的，week 是接口给的，没错。
         *   → 真正的错在**第 1 周**：9-07 那一周被挤掉了，
         *     页面根本翻不到「9-07 那一周」，第一屏就是 9-14。
         *
         * 用户的原话：「第一周是空白的，第二周开始有课。
         *   教务系统也是这样没错，但第一周起始日期其实是 9.7，
         *   当前是 9.14 了。」
         *   ——也就是说 App 里根本没有 9-07 这一周可看。
         */
        val wrong = com.gzucm.pocket.core.Semester.of(
            code = "48", name = "错",
            firstMonday = LocalDate.of(2026, 9, 14), totalWeeks = 20,
        )
        val right = com.gzucm.pocket.core.Semester.of(
            code = "48", name = "对",
            firstMonday = LocalDate.of(2026, 9, 7), totalWeeks = 20,
        )

        assertEquals("错用 9-14 → 11-01 算成第 7 周", 7, wrong.weekOf(LocalDate.of(2026, 11, 1)))
        assertEquals("正确应是第 8 周", 8, right.weekOf(LocalDate.of(2026, 11, 1)))

        // 关键差异：错的那份根本没有 9-07 这一周
        assertEquals(
            "错的第 1 周从 9-14 开始，用户看不到 9-07 那一周",
            LocalDate.of(2026, 9, 14),
            wrong.dateOf(1, 1),
        )
        assertEquals(
            "对的第 1 周才是 9-07",
            LocalDate.of(2026, 9, 7),
            right.dateOf(1, 1),
        )
        assertTrue("两者正好差 7 天", wrong.dateOf(1, 1).toEpochDay() - right.dateOf(1, 1).toEpochDay() == 7L)
    }

    // ── 端到端：走 buildSemester 而不是只调 inferFirstMonday ──────
    //
    // 教训：第一版测试只调 inferFirstMonday，把 buildSemester 里的调用
    // 退回旧逻辑，7 项测试依然全绿 —— 因为它们压根没走那条路。
    // 这两条专门测「真实入口」。

    private val fakeTerm = com.gzucm.pocket.data.YjsTerm(
        code = "48",
        name = "2026-2027秋学期",
        selected = true,
    )

    @Test
    fun `buildSemester 端到端：第1周无课也不许错位`() {
        // 真实数据：第 1 周 0 个格子，最早数据在第 2 周（2026-09-14 周一）
        val sessions = listOf(
            s(2, 1, "2026-09-14", "医学分子生物学基础"),
            s(2, 2, "2026-09-15", "英语（专业型硕士）"),
            s(6, 3, "2026-10-14", "自然辨证法概论"),
            s(7, 4, "2026-10-22", "科技论文配图设计与制作"),
        )
        val sem = Yjs.buildSemester(fakeTerm, sessions)

        assertEquals(
            "buildSemester 必须用日期+周次反推，不能拿最早日期当第一周",
            "2026-09-07",
            sem.firstMonday.toString(),
        )
        assertEquals(
            "2026-11-01 应是第 8 周",
            8,
            sem.weekOf(LocalDate.of(2026, 11, 1)),
        )
    }

    @Test
    fun `buildSemester 端到端：第1周有课时同样正确`() {
        // 对照组 —— 第一周就有课的情况，新旧算法结果应该一致
        val sessions = listOf(
            s(1, 2, "2026-09-08"),
            s(2, 1, "2026-09-14"),
            s(7, 4, "2026-10-22"),
        )
        val sem = Yjs.buildSemester(fakeTerm, sessions)
        assertEquals("2026-09-07", sem.firstMonday.toString())
    }
}