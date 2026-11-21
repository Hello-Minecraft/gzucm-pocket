package com.gzucm.pocket

import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.width
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import com.gzucm.pocket.ui.components.micaSideShadow
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * 侧栏投影的**方向**像素级回归（2026-11-21）。
 *
 * ## 为什么专门写一个像素测试
 *
 * 致辉报过两次「双层阴影」，第一次我只把 alpha 从 0.50 降到 0.34，没修好 ——
 * 因为问题在渐变方向：`colors = [Black, Transparent]` 配 `startX=-w, endX=0`
 * 画出来是「远离面板最黑、贴着面板反而透明」，于是面板左边出现
 * 「一条暗边 → 变亮 → 白面板」的三段灰度，看起来像两层阴影。
 *
 * 这种错法在数值上完全合法（只影响观感），所以只能靠像素断言钉住：
 * **紧贴面板那一列必须比渐变外端更暗**。方向再画反，这条会立刻红。
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-xhdpi")
class SideShadowTest {

    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    private fun gray(c: Int): Int {
        val r = (c shr 16) and 0xFF
        val g = (c shr 8) and 0xFF
        val b = c and 0xFF
        return (r + g + b) / 3
    }

    @Test
    fun `侧栏投影贴着面板处最暗向外渐隐`() {
        rule.setContent {
            Row(
                Modifier
                    .fillMaxSize()
                    // 中灰底：投影叠上去的明暗差才量得出来
                    .background(Color(0xFF888888)),
            ) {
                Spacer(Modifier.weight(1f))
                Box(
                    Modifier
                        .width(200.dp)
                        .fillMaxHeight()
                        .micaSideShadow(width = 18.dp, alpha = 0.5f)
                        .background(Color.White),
                )
            }
        }
        rule.waitForIdle()

        val view = rule.activity.window.decorView
        val w = if (view.width > 0) view.width else 822
        val h = if (view.height > 0) view.height else 1782
        if (view.width == 0 || view.height == 0) {
            view.measure(
                android.view.View.MeasureSpec.makeMeasureSpec(w, android.view.View.MeasureSpec.EXACTLY),
                android.view.View.MeasureSpec.makeMeasureSpec(h, android.view.View.MeasureSpec.EXACTLY),
            )
            view.layout(0, 0, w, h)
        }
        val bmp = android.graphics.Bitmap.createBitmap(w, h, android.graphics.Bitmap.Config.ARGB_8888)
        view.draw(android.graphics.Canvas(bmp))

        val density = rule.activity.resources.displayMetrics.density
        val panelLeft = bmp.width - (200 * density).toInt()
        val shadowPx = (18 * density).toInt()
        val y = bmp.height / 2

        val near = gray(bmp.getPixel(panelLeft - 2, y))
        val far = gray(bmp.getPixel(panelLeft - shadowPx + 4, y))
        val outside = gray(bmp.getPixel((panelLeft - shadowPx - 8).coerceAtLeast(0), y))

        assertTrue(
            "紧贴面板那一列应比渐变外端更暗（near=$near, far=$far）—— 相等或反了就是方向画反",
            near < far - 8,
        )
        assertTrue(
            "渐变外端应接得上遮罩/背景（far=$far, outside=$outside），不能凭空多一条边",
            kotlin.math.abs(far - outside) <= 8,
        )

        bmp.recycle()
    }
}
