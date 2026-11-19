package com.gzucm.pocket

import androidx.compose.ui.geometry.Size
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.gzucm.pocket.ui.components.G3CornerShape
import com.gzucm.pocket.ui.components.cornerProfile
import com.gzucm.pocket.ui.components.g3CornerY
import com.gzucm.pocket.ui.components.g3F
import com.gzucm.pocket.ui.components.g3InverseF
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.math.sqrt

/**
 * G3 轮廓的数学契约（桌面设计文档 §2.3）。
 * 这里验的是解析公式本身；Path 只是它的采样输出。
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [34])
class G3ShapeTest {

    @Test
    fun `f 端点正确且单调`() {
        val a = cornerProfile(20f, 200f).a
        assertEquals(0f, g3F(0f, a), 0f)
        assertEquals(1f, g3F(1f, a), 0f)

        var prev = -1f
        for (i in 0..100) {
            val v = g3F(i / 100f, a)
            assertTrue("f 必须单调，t=${i / 100f}", v >= prev)
            prev = v
        }
    }

    @Test
    fun `f 在零点是四阶小量 前三阶导数为零`() {
        val a = cornerProfile(20f, 200f).a
        val h = 0.01f
        // f(t) ≈ t⁴/a ⇒ f(h)/h⁴ → 1/a。
        // 这同时说明 f′(0)=f″(0)=f‴(0)=0，比数值差分稳定（Float 差分会被抵消吃光）。
        assertEquals(1f / a, g3F(h, a) / (h * h * h * h), 0.02f)

        // 文档给的另一端条件：f′(1) = 2(1+a)
        val slope = (g3F(1f, a) - g3F(1f - h, a)) / h
        assertEquals(2f * (1f + a), slope, 0.05f)
    }

    @Test
    fun `反函数与正函数互为逆`() {
        val a = cornerProfile(12f, 108f).a
        for (t in listOf(0.05f, 0.2f, 0.5f, 0.8f, 0.97f)) {
            assertEquals(t, g3InverseF(g3F(t, a), a), 0.002f)
        }
    }

    @Test
    fun `轮廓两端落在直边上`() {
        val p = cornerProfile(18f, 180f)
        assertEquals(p.extent, g3CornerY(0f, p), 0.0001f)
        assertEquals(0f, g3CornerY(p.extent, p), 0.0001f)
    }

    @Test
    fun `对角内缩量与圆弧的四十五度点一致`() {
        val p = cornerProfile(18f, 180f)
        // v 是 f(t)=1/2 的解；x=y=vL 时内缩 (1-v)L = (1-1/√2)R
        val v = 1f - (1f - 1f / sqrt(2f)) * p.radius / p.extent
        assertEquals(0.5f, g3F(v, p.a), 0.002f)
        assertEquals(
            (1f - 1f / sqrt(2f)) * p.radius,
            p.extent - v * p.extent,
            0.002f,
        )
    }

    @Test
    fun `极端尺寸下 a 仍为正 不会切回圆弧`() {
        for (r in listOf(0f, 1f, 2f, 12f, 40f, 1000f)) {
            val p = cornerProfile(r, 100f)
            assertTrue("a 必须 ≥0.02，r=$r", p.a >= 0.02f)
            assertTrue("半径不得超过短边一半，r=$r", p.radius <= 50f)
            assertTrue("过渡范围不得超过短边一半，r=$r", p.extent <= 50f)
        }
    }

    @Test
    fun `Shape 输出的轮廓覆盖整个尺寸且不越界`() {
        val shape = G3CornerShape(12.dp)
        val out = shape.createOutline(
            Size(240f, 480f),
            LayoutDirection.Ltr,
            Density(1f),
        )
        val b = out.bounds
        assertEquals(0f, b.left, 0f)
        assertEquals(0f, b.top, 0f)
        assertEquals(240f, b.right, 0.01f)
        assertEquals(480f, b.bottom, 0.01f)
    }

    @Test
    fun `非对称圆角下直角两角不会越界崩溃`() {
        // AdaptiveSheet 就是这种：上面两个角 24、下面两个角 0。
        // 曾经因为直角分支只返回 1 个采样点、循环按 32 索引而崩。
        val shape = G3CornerShape(
            topStart = 24.dp, topEnd = 24.dp,
            bottomEnd = 0.dp, bottomStart = 0.dp,
        )
        val out = shape.createOutline(Size(320f, 480f), LayoutDirection.Ltr, Density(1f))
        assertEquals(320f, out.bounds.width, 0.01f)
        assertEquals(480f, out.bounds.height, 0.01f)
    }

    @Test
    fun `零尺寸不崩`() {
        val shape = G3CornerShape(12.dp)
        shape.createOutline(Size(0f, 0f), LayoutDirection.Ltr, Density(1f))
        shape.createOutline(Size(100f, 0f), LayoutDirection.Ltr, Density(1f))
        shape.createOutline(Size(0f, 100f), LayoutDirection.Ltr, Density(1f))
    }

    @Test
    fun `同尺寸重复取轮廓命中缓存 且 RTL 会换边`() {
        val shape = G3CornerShape(topStart = 20.dp, topEnd = 4.dp)
        val ltr = shape.createOutline(Size(100f, 100f), LayoutDirection.Ltr, Density(1f))
        val again = shape.createOutline(Size(100f, 100f), LayoutDirection.Ltr, Density(1f))
        assertTrue("缓存应返回同一个 Outline 实例", ltr === again)

        val rtl = shape.createOutline(Size(100f, 100f), LayoutDirection.Rtl, Density(1f))
        assertTrue("RTL  exchanged 左右角，轮廓必须重算", ltr !== rtl)
    }
}
