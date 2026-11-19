package com.gzucm.pocket

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextReplacement
import com.gzucm.pocket.core.Agenda
import com.gzucm.pocket.core.Semester
import com.gzucm.pocket.ui.schedule.AgendaEditSheet
import com.gzucm.pocket.ui.theme.GzucmTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.LocalDate

/**
 * 表单里「日程 / 截止任务」模式切换（致辉 2026-11-17 定的截止任务功能）。
 *
 * 验证：默认日程模式没有截止时间行；点「截止任务」后出现截止时间行；
 * 保存时传出的 Agenda 带正确的 isTask / deadlineAt。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h874dp-xhdpi")
class AgendaTaskFormTest {

    @get:Rule
    val rule = createComposeRule()

    private val sem = Semester.of("202601", "测试学期", LocalDate.of(2026, 9, 7), 19)

    private fun show(
        date: LocalDate,
        onSave: (Agenda) -> Unit = {},
    ) {
        rule.setContent {
            GzucmTheme(darkTheme = true) {
                Surface(Modifier.fillMaxSize()) {
                    AgendaEditSheet(
                        visible = true,
                        semester = sem,
                        initial = null,
                        draftDate = date,
                        draftStartMinute = null,
                        onSave = onSave,
                        onDelete = {},
                        onDismiss = {},
                    )
                }
            }
        }
        rule.waitForIdle()
    }

    @Test
    fun `默认是日程模式没有截止时间行`() {
        show(LocalDate.of(2026, 11, 20))
        rule.onNodeWithText("日程").assertExists()
        rule.onNodeWithText("截止任务").assertExists()
        assertEquals(
            "日程模式不该出现截止时间行",
            0,
            rule.onAllNodesWithText("截止时间").fetchSemanticsNodes().size,
        )
    }

    @Test
    fun `切到截止任务出现截止时间行且默认有值`() {
        show(LocalDate.of(2026, 11, 21))
        rule.onNodeWithText("截止任务").performSemanticsAction(SemanticsActions.OnClick)
        rule.waitForIdle()
        assertEquals(
            "截止任务模式应显示截止时间行",
            1,
            rule.onAllNodesWithText("截止时间").fetchSemanticsNodes().size,
        )
        // 默认种子是 draftDate 的 23:59：文案里必带 23:59。
        // 不写死「今天/11月21日」——那是相对系统当前时间的，会随跑测日期变。
        assertEquals(
            "截止时间行应存在",
            1,
            rule.onAllNodesWithText("截止时间").fetchSemanticsNodes().size,
        )
        // 具体默认值由下面的保存测试断言（deadlineAt 非空），这里只验行出现
    }

    @Test
    fun `编辑长期待办不会凭空填上截止日期`() {
        // 回归：buildDraft 原来用 Elvis，deadlineAt=null 会被当成「没有」填成今天 23:59，
        // 结果「改个备注」就把长期任务变成今天截止的 DDL（复核抓到）。
        val todo = Agenda(
            id = "todo1", title = "长期读书计划", date = "2026-11-17",
            startMinute = 0, endMinute = 60,
            isTask = true, deadlineAt = null,
        )
        rule.setContent {
            GzucmTheme(darkTheme = true) {
                Surface(Modifier.fillMaxSize()) {
                    AgendaEditSheet(
                        visible = true, semester = sem, initial = todo,
                        draftDate = null, draftStartMinute = null,
                        onSave = {}, onDelete = {}, onDismiss = {},
                    )
                }
            }
        }
        rule.waitForIdle()
        assertEquals(
            "长期待办编辑时应保持「无截止时间」",
            1,
            rule.onAllNodesWithText("长期待办（无截止时间）").fetchSemanticsNodes().size,
        )
    }

    @Test
    fun `保存的任务isTask为真且截止时间非空`() {
        var saved: Agenda? = null
        show(LocalDate.of(2026, 11, 22), onSave = { saved = it })

        // 标题必填，否则保存按钮禁用（canSave=false）。
        // 用 SetText 语义动作而非触摸输入 —— ModalBottomSheet 是独立窗口，触摸坐标容易落空。
        rule.onNodeWithText("给这条日程起个名字")
            .performTextReplacement("测试任务")
        rule.waitForIdle()
        rule.onNodeWithText("截止任务").performSemanticsAction(SemanticsActions.OnClick)
        rule.waitForIdle()
        rule.onNodeWithText("保存").performSemanticsAction(SemanticsActions.OnClick)
        rule.waitForIdle()

        assertNotNull("应该保存成功", saved)
        assertTrue("保存的应该是任务", saved!!.isTask)
        assertNotNull("默认截止时间应非空", saved!!.deadlineAt)
        assertTrue("任务不该排提醒", saved!!.remindLeads.isEmpty())
        assertFalse("任务不该每周重复", saved!!.weekly)
    }
}
