package com.gzucm.pocket

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import com.gzucm.pocket.core.Semester
import com.gzucm.pocket.ui.schedule.AgendaEditSheet
import com.gzucm.pocket.ui.theme.GzucmTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.LocalDate

/**
 * 新建 / 编辑面板里的「提醒」这一行（致辉 2026-11-15 定：设置入口放在表单里）。
 *
 * 只验证 UI 到草稿这一段：默认值、点胶囊能不能真的改到值。
 * 「存下来的值会不会变成闹钟」由 AgendaReminderTest / AgendaReminderScheduleTest 负责。
 *
 * 注意：表单很长，「提醒」在首屏之外 —— 点击前必须先 performScrollTo()，
 * 否则触摸事件落在窗口外，点了等于没点（第一次写这个测试就栽在这）。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h874dp-xhdpi")
class AgendaEditReminderTest {

    @get:Rule
    val rule = createComposeRule()

    private val sem = Semester.of("202601", "测试学期", LocalDate.of(2026, 9, 7), 19)

    /**
     * 每个用例传不同的日期：草稿缓存是按「新建|日期」做 key 的进程级单例，
     * 两个用例共用一天的话，后跑的那个会恢复前一个留下的草稿（第一次就栽在这）。
     */
    private fun show(date: LocalDate) {
        rule.setContent {
            GzucmTheme(darkTheme = true) {
                Surface(Modifier.fillMaxSize()) {
                    AgendaEditSheet(
                        visible = true,
                        semester = sem,
                        initial = null,
                        draftDate = date,
                        draftStartMinute = null,
                        onSave = {},
                        onDelete = {},
                        onDismiss = {},
                    )
                }
            }
        }
        rule.waitForIdle()
    }

    private fun clickRemindRow() {
        // 直接调语义动作，不做真实触摸注入：表单是 ModalBottomSheet（独立窗口），
        // 触摸坐标换算容易落空；这里要验的是「点了会不会改状态」，不是命中测试。
        rule.onNodeWithText("提醒").performSemanticsAction(SemanticsActions.OnClick)
        rule.waitForIdle()
    }

    @Test
    fun `新建默认准时`() {
        show(LocalDate.of(2026, 11, 16))
        // 折叠状态下只剩行上的值，胶囊还没展开
        rule.onNodeWithText("提醒").assertExists()
        assertEquals(
            "新建默认应该是「准时」",
            1,
            rule.onAllNodesWithText("准时").fetchSemanticsNodes().size,
        )
    }

    @Test
    fun `多选之后行上按由近到远显示`() {
        show(LocalDate.of(2026, 11, 17))

        clickRemindRow()
        // 默认只有「准时」，再加一档「提前 1 天」—— 多选，不是替换
        rule.onNodeWithText("提前 1 天").performSemanticsAction(SemanticsActions.OnClick)
        rule.waitForIdle()

        clickRemindRow()
        assertEquals(
            "收起后行上应显示「准时 · 1 天」（由近到远）",
            1,
            rule.onAllNodesWithText("准时 · 1 天").fetchSemanticsNodes().size,
        )
    }

    @Test
    fun `点不提醒会清空所有档位`() {
        show(LocalDate.of(2026, 11, 18))

        clickRemindRow()
        rule.onNodeWithText("不提醒").performSemanticsAction(SemanticsActions.OnClick)
        rule.waitForIdle()

        clickRemindRow()
        assertEquals(
            "清空后行上应该只剩「不提醒」",
            1,
            rule.onAllNodesWithText("不提醒").fetchSemanticsNodes().size,
        )
    }
}
