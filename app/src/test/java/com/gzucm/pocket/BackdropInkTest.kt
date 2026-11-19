package com.gzucm.pocket

import android.graphics.Bitmap
import androidx.compose.ui.graphics.asImageBitmap
import com.gzucm.pocket.ui.components.averageBackdropLuminance
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * 壁纸墨色判定的闸门（设计文档 §5.2）。
 *
 * 规则只有一条：亮背景用深色字、暗背景用浅色字。
 * 这里的「亮」取图片顶部 45% 的平均亮度，阈值 0.5。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class BackdropInkTest {

    private fun bitmapOf(color: Int): androidx.compose.ui.graphics.ImageBitmap {
        val bmp = Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888)
        bmp.eraseColor(color)
        return bmp.asImageBitmap()
    }

    @Test
    fun `纯白壁纸判定为亮`() {
        val l = averageBackdropLuminance(bitmapOf(android.graphics.Color.WHITE))
        assertTrue("白壁纸亮度应接近 1，实际 $l", l > 0.9f)
    }

    @Test
    fun `纯黑壁纸判定为暗`() {
        val l = averageBackdropLuminance(bitmapOf(android.graphics.Color.BLACK))
        assertTrue("黑壁纸亮度应接近 0，实际 $l", l < 0.05f)
    }

    @Test
    fun `只看上半部分 下半部再暗也不影响判定`() {
        val bmp = Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888)
        // 上半白、下半黑
        val canvas = android.graphics.Canvas(bmp)
        canvas.drawColor(android.graphics.Color.BLACK)
        canvas.drawRect(0f, 0f, 64f, 30f, android.graphics.Paint().apply {
            color = android.graphics.Color.WHITE
        })
        val l = averageBackdropLuminance(bmp.asImageBitmap())
        assertTrue("上半是白的就该判亮，实际 $l", l > 0.9f)
    }
}
