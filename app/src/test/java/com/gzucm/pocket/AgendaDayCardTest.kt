package com.gzucm.pocket

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import com.gzucm.pocket.core.AgendaOccurrence
import com.gzucm.pocket.core.EXAM_COLOR_INDEX
import com.gzucm.pocket.ui.schedule.AgendaDayCard
import com.gzucm.pocket.ui.theme.GzucmTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.LocalDate

/**
 * 今日页 / 日视图的卡片要把**座位号**显示出来（致辉 2026-11-14 要的）。
 *
 * 座位以前只存在 ExamItem 里，转成 AgendaOccurrence 时被丢掉了 ——
 * 卡片上只有「14:30 — 17:30 · 新课室楼301」，进场不知道坐哪。
 * 现在单独一个 seat 字段，和考试类型拼在同一行。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h874dp-xhdpi")
class AgendaDayCardTest {

    @get:Rule
    val rule = createComposeRule()

    private fun exam(seat: String, type: String) = AgendaOccurrence(
        key = "exam:1",
        sourceId = "exam:1",
        title = "中医经典知识Ⅱ级",
        date = LocalDate.of(2026, 11, 14),
        week = 10,
        weekday = 6,
        startMinute = 14 * 60 + 30,
        endMinute = 17 * 60 + 30,
        place = "新课室楼301",
        note = type,
        colorIndex = EXAM_COLOR_INDEX,
        unitStart = 0,
        unitEnd = 0,
        weekly = false,
        seat = seat,
    )

    @Test
    fun `考试卡片显示座位号`() {
        rule.setContent {
            GzucmTheme(darkTheme = true) {
                Surface(Modifier.fillMaxWidth()) {
                    AgendaDayCard(occurrence = exam("12", "闭卷"), onClick = {})
                }
            }
        }
        rule.waitForIdle()

        rule.onNodeWithText("闭卷 · 座位 12").assertExists()
    }

    @Test
    fun `没有座位号时不留一个孤零零的分隔点`() {
        rule.setContent {
            GzucmTheme(darkTheme = true) {
                Surface(Modifier.fillMaxWidth()) {
                    AgendaDayCard(occurrence = exam("", "开卷"), onClick = {})
                }
            }
        }
        rule.waitForIdle()

        // 只有类型、没有多余分隔符
        rule.onNodeWithText("开卷").assertExists()
    }
}
