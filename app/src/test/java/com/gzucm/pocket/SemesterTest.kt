package com.gzucm.pocket

import com.gzucm.pocket.core.Semester
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

/** 纯逻辑单测：学期周次换算、校历解析规则。不依赖 Android。 */
class SemesterTest {

    private val sem = Semester.of(
        code = "202601",
        name = "2026-2027 学年 第一学期",
        firstMonday = LocalDate.of(2026, 9, 7),
        totalWeeks = 19,
    )

    @Test
    fun `开学第一天是第 1 周周一`() {
        assertEquals(1, sem.weekOf(LocalDate.of(2026, 9, 7)))
    }

    @Test
    fun `第 1 周周日仍属第 1 周`() {
        assertEquals(1, sem.weekOf(LocalDate.of(2026, 9, 13)))
    }

    @Test
    fun `第 2 周周一`() {
        assertEquals(2, sem.weekOf(LocalDate.of(2026, 9, 14)))
    }

    @Test
    fun `开学前返回 0`() {
        assertEquals(0, sem.weekOf(LocalDate.of(2026, 9, 1)))
    }

    @Test
    fun `10 月 28 日落在第 8 周`() {
        // 9/7 起算：9/7 + 7*7 = 10/26 是第 8 周周一，10/28 是周三
        assertEquals(8, sem.weekOf(LocalDate.of(2026, 10, 28)))
    }

    @Test
    fun `周次与星期能换回日期`() {
        assertEquals(LocalDate.of(2026, 9, 7), sem.dateOf(1, 1))
        assertEquals(LocalDate.of(2026, 9, 13), sem.dateOf(1, 7))
        assertEquals(LocalDate.of(2026, 10, 26), sem.dateOf(8, 1))
        assertEquals(LocalDate.of(2026, 10, 28), sem.dateOf(8, 3))
    }

    @Test
    fun `序列化后能还原`() {
        val iso = sem.firstMondayIso
        assertEquals("2026-09-07", iso)
        assertEquals(LocalDate.of(2026, 9, 7), LocalDate.parse(iso))
    }

    @Test
    fun `正反换算自洽`() {
        for (w in 1..19) {
            for (d in 1..7) {
                val date = sem.dateOf(w, d)
                assertEquals("week=$w day=$d", w, sem.weekOf(date))
                assertEquals(d, date.dayOfWeek.value)
            }
        }
    }
}