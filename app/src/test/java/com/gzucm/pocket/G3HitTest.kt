package com.gzucm.pocket

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import com.gzucm.pocket.ui.components.G3CornerShape
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * 自定义 Shape 的命中检测回归。
 *
 * `clip(G3CornerShape)` 会让 Compose 用 Outline.Generic 做命中判断，
 * 内部走 Path.op(Intersect)。LEGACY 图形模式下这个操作不可用，
 * 表现是「整块点不动」——不是产品 bug，但必须靠全局 NATIVE 兜住
 * （见 test/resources/robolectric.properties）。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h874dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class G3HitTest {

    @get:Rule
    val rule = createComposeRule()

    private fun run(shape: androidx.compose.ui.graphics.Shape, clip: Boolean, tag: String): Int {
        var hit = 0
        rule.setContent {
            Box(Modifier.fillMaxSize()) {
                var m = Modifier
                    .padding(20.dp)
                    .size(200.dp)
                    .testTag(tag)
                if (clip) m = m.clip(shape)
                m = m.background(Color.Red).clickable { hit++ }
                Box(m)
            }
        }
        rule.onNodeWithTag(tag).performClick()
        rule.waitForIdle()
        return hit
    }

    @Test
    fun g3Clip() {
        assertEquals("G3 clip 后点不动", 1, run(G3CornerShape(24.dp), true, "a"))
    }

    @Test
    fun roundedClip() {
        assertEquals("圆弧 clip 后点不动", 1, run(RoundedCornerShape(24.dp), true, "b"))
    }

    @Test
    fun g3NoClip() {
        assertEquals("G3 不 clip 也点不动", 1, run(G3CornerShape(24.dp), false, "c"))
    }
}
