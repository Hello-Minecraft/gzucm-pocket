package com.gzucm.pocket

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.gzucm.pocket.ui.settings.ExportContent
import com.gzucm.pocket.ui.theme.GzucmTheme
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 导出子页面（2026-11-18）。
 *
 * 钉住三件事：
 *  1. 两条路都在 —— 只做复制的话，微信塞不下的那天就没退路了；
 *  2. 「微信发不出去？发文件就可以。」这句提示是致辉点名要的，不能被顺手删掉；
 *  3. 周次不完整要显式提示，别让人导出一份缺周的课表还以为全了。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h874dp-xhdpi")
class ExportContentTest {

    @get:Rule
    val rule = createComposeRule()

    private fun render(
        weeksCovered: Int = 18,
        tokenChars: Int = 1304,
        onCopy: () -> Unit = {},
        onShare: () -> Unit = {},
    ) {
        rule.setContent {
            GzucmTheme {
                ExportContent(
                    courseCount = 25,
                    weeksCovered = weeksCovered,
                    totalWeeks = 18,
                    tokenChars = tokenChars,
                    onBack = {},
                    onCopyToken = onCopy,
                    onShareFile = onShare,
                )
            }
        }
    }

    @Test
    fun `两条路和微信提示都在`() {
        render()

        rule.onNodeWithText("复制分享串").assertIsDisplayed()
        rule.onNodeWithText("导出 JSON 文件").assertIsDisplayed()
        rule.onNodeWithText("微信发不出去？发文件就可以。").assertIsDisplayed()
        // 字符数要露出来，他才知道「这条塞不塞得进微信」；「不含教师」也要说清
        rule.onNodeWithText("约 1,304 字符，粘进微信就能发（不含任课教师）").assertIsDisplayed()
        rule.onNodeWithText("25 门课 · 覆盖 18/18 周").assertIsDisplayed()
    }

    @Test
    fun `缺周要红字说明`() {
        render(weeksCovered = 8)

        rule.onNodeWithText("25 门课 · 覆盖 8/18 周（不完整，建议先「立即同步」）")
            .assertIsDisplayed()
    }

    @Test
    fun `点两行各自回调`() {
        var copied = false
        var shared = false
        render(onCopy = { copied = true }, onShare = { shared = true })

        rule.onNodeWithText("复制分享串").performClick()
        rule.onNodeWithText("导出 JSON 文件").performClick()

        assertTrue("复制分享串没回调", copied)
        assertTrue("导出文件没回调", shared)
    }
}
