package com.gzucm.pocket

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.core.app.ApplicationProvider
import com.gzucm.pocket.core.CourseSession
import com.gzucm.pocket.core.Semester
import com.gzucm.pocket.ui.settings.WidgetGuideScreen
import com.gzucm.pocket.ui.theme.GzucmTheme
import com.gzucm.pocket.widget.WidgetSnapshot
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.LocalDate

/**
 * 「添加到桌面小组件」教程页的护栏。
 *
 * 这一页是纯 UI，但有两件事必须在 JVM 上钉住：
 *  1. 六个尺寸都能真的走完一遍 [com.gzucm.pocket.widget.WidgetRender] 并把版式画出来
 *     —— 权重错位之类的布局问题只在 measure 时会炸，编译期看不出来；
 *  2. 课表为空时给的是「先同步一次课表」，不是一片空白（新用户第一次进来就是空的）。
 *
 * 数据用用户自己的口径：课表快照 + 现在时刻，和真机完全一致。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h2600dp-xhdpi")
class WidgetGuideScreenTest {

    @get:Rule
    val rule = createComposeRule()

    private val today: LocalDate = LocalDate.now()

    /** 六个尺寸的「按钮文案 → 说明」。说明是给用户看的，不能被顺手删掉 */
    private val sizes = listOf(
        "2×1 一行" to "一行显示正在上 / 下一节",
        "2×2 今日" to "今天剩下的两节",
        "4×2 今日+地点" to "两节 + 地点老师",
        "4×3 今日课程" to "剩下的课，最多 7 门",
        "4×3 三日" to "今天 / 明天 / 后天",
        "4×3 周视图" to "整周，同一门课跨节连成一块",
    )

    private fun saveSnapshot() {
        val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
        val monday = today.minusDays((today.dayOfWeek.value - 1).toLong())
        val semester = Semester.of("2026-2027-1", "2026-2027 学年第一学期", monday, 20)
        val session = CourseSession(
            name = "中医诊断学",
            code = "B0101",
            teachers = "张伟",
            week = 1,
            weekday = today.dayOfWeek.value,
            startUnit = 1,
            endUnit = 2,
            startTime = "00:00",
            endTime = "24:00",
            room = "综教楼101",
            category = "必修",
        )
        WidgetSnapshot.save(ctx, semester, listOf(session))
    }

    @Test
    fun `六个尺寸都能渲染并各自带一句说明`() {
        saveSnapshot()
        rule.setContent {
            GzucmTheme { WidgetGuideScreen() }
        }

        // 四家机型的路径都要在（用户照着做才对得上）
        rule.onNodeWithText("小米 / Redmi（HyperOS、MIUI）").assertExists()
        rule.onNodeWithText("华为 / 荣耀（HarmonyOS、MagicOS）").assertExists()
        rule.onNodeWithText("OPPO / 一加（ColorOS）").assertExists()
        rule.onNodeWithText("vivo（OriginOS）").assertExists()

        sizes.forEach { (chip, caption) ->
            rule.onNodeWithText(chip).performScrollTo().performClick()
            // 说明在预览下面，滚动后一定存在；截图断言不了，这里只保证它没被删
            rule.onNodeWithText(caption).assertExists()
        }

        // 数据用的是用户自己的课表：切到 2x2 之后课名要真的画出来
        rule.onNodeWithText("2×2 今日").performScrollTo().performClick()
        rule.onNodeWithText("中医诊断学").assertExists()
    }

    @Test
    fun `课表为空时提示先去同步`() {
        // 明确清掉快照：不依赖测试执行顺序，空态就是「从没同步过」
        WidgetSnapshot.clear(ApplicationProvider.getApplicationContext())
        rule.setContent {
            GzucmTheme { WidgetGuideScreen() }
        }
        rule.onNodeWithText("先在课表页同步一次课表，这里就能看到你自己的课").assertExists()
    }
}
