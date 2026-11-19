package com.gzucm.pocket

import android.graphics.Bitmap
import android.graphics.Color
import com.gzucm.pocket.ui.components.frostTransform
import com.gzucm.pocket.ui.components.boxBlur
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 课表格子的磨砂底。
 *
 * 两件事容易出错，而且错得很隐蔽：
 * 1. **坐标变换**（[frostTransform]）写错时，磨砂块整体偏移几像素，
 *    肉眼只觉得「有点脏」，看不出是错的 —— 必须靠数学断言锁住。
 * 2. **模糊算法**（[boxBlur]）滑窗边界处理错时，四周会发黑。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class FrostedBackdropTest {

    // ── 坐标变换 ──────────────────────────────────────────

    @Test
    fun `图与区域等比时按二倍缩放`() {
        // 100×100 的图铺到 200×200 → 放大 2 倍，不裁切，位图原点对齐壁纸原点
        val tf = frostTransform(
            bmpW = 100, bmpH = 100, wallW = 200f, wallH = 200f,
            relX = 0f, relY = 0f,
        )!!
        assertEquals(2f, tf.scale, 0.0001f)
        assertEquals(0f, tf.dx, 0.0001f)
        assertEquals(0f, tf.dy, 0.0001f)
    }

    @Test
    fun `宽图铺进窄区域时左右被居中裁掉`() {
        // 100×50 的图（2:1）铺进 200×200：scale = max(2, 4) = 4
        // 画出来 400×200，左右各溢出 100 → 位图原点在屏幕 x=-100
        val tf = frostTransform(
            bmpW = 100, bmpH = 50, wallW = 200f, wallH = 200f,
            relX = 0f, relY = 0f,
        )!!
        assertEquals(4f, tf.scale, 0.0001f)
        assertEquals(-100f, tf.dx, 0.0001f)
        assertEquals(0f, tf.dy, 0.0001f)
    }

    /**
     * **不拉伸的根本保证**：缩放系数只由「位图尺寸 + 壁纸区尺寸」决定，
     * 跟格子自己多宽多高、滑到哪，一点关系都没有。
     *
     * 2026-11-19 致辉报「横滑时磨砂被横向抻长」——旧的 drawImage 方案要自己算
     * 源矩形和目标矩形，两者宽高比一有偏差就放大。换成 shader 之后，矩阵里
     * 只有一个 scale，物理上不可能被拉长。这条测试就是钉住这一点。
     */
    @Test
    fun `缩放系数与格子尺寸和位置完全无关`() {
        val cases = listOf(
            // relX, 格子宽, 格子高
            Triple(0f, 100f, 100f),
            Triple(50f, 100f, 100f),
            Triple(-200f, 100f, 100f),   // 左半滑出屏幕
            Triple(900f, 100f, 100f),    // 几乎完全在屏幕外
            Triple(0f, 1080f, 2000f),    // 满屏大卡
            Triple(0f, 40f, 40f),        // 迷你卡
        )
        cases.forEach { (relX, _, _) ->
            val tf = frostTransform(
                bmpW = 135, bmpH = 240, wallW = 1080f, wallH = 2400f,
                relX = relX, relY = 0f,
            )!!
            assertEquals(
                "relX=$relX 时 scale 变了 —— 说明有格子尺寸混进了计算，必然拉伸",
                10f, tf.scale, 0.0001f,
            )
        }
    }

    /** 越界只体现为平移（dx 变负），缩放不动。 */
    @Test
    fun `滑出边界只改平移不改缩放`() {
        val inside = frostTransform(
            bmpW = 100, bmpH = 100, wallW = 200f, wallH = 200f,
            relX = 0f, relY = 0f,
        )!!
        val outside = frostTransform(
            bmpW = 100, bmpH = 100, wallW = 200f, wallH = 200f,
            relX = 150f, relY = 0f,
        )!!
        assertEquals("缩放必须一致", inside.scale, outside.scale, 0.0001f)
        // 格子右移 150，位图原点在格子局部坐标里就左移 150
        assertEquals(inside.dx - 150f, outside.dx, 0.0001f)
    }

    @Test
    fun `空图或零尺寸返回 null`() {
        assertEquals(null, frostTransform(0, 0, 200f, 200f, 0f, 0f))
        assertEquals(null, frostTransform(100, 100, 0f, 200f, 0f, 0f))
    }

    // ── 模糊 ──────────────────────────────────────────────

    @Test
    fun `纯色图模糊后还是同一个颜色`() {
        val bmp = Bitmap.createBitmap(32, 32, Bitmap.Config.ARGB_8888)
        bmp.eraseColor(Color.rgb(30, 120, 200))
        val out = boxBlur(bmp, radius = 5)

        val c = out.getPixel(16, 16)
        // 允许 ±2 的整数除法误差
        assertTrue("红通道偏差过大", kotlin.math.abs(Color.red(c) - 30) <= 2)
        assertTrue("绿通道偏差过大", kotlin.math.abs(Color.green(c) - 120) <= 2)
        assertTrue("蓝通道偏差过大", kotlin.math.abs(Color.blue(c) - 200) <= 2)
    }

    @Test
    fun `黑白块模糊后交界处变成灰`() {
        val bmp = Bitmap.createBitmap(32, 32, Bitmap.Config.ARGB_8888)
        for (y in 0 until 32) {
            for (x in 0 until 32) {
                bmp.setPixel(x, y, if (x < 16) Color.BLACK else Color.WHITE)
            }
        }
        val out = boxBlur(bmp, radius = 6)

        // 交界附近既不是纯黑也不是纯白
        val mid = out.getPixel(16, 16)
        val v = Color.red(mid)
        assertTrue("交界处应该是灰的，实际 $v", v in 60..200)
    }

    @Test
    fun `边缘不发黑`() {
        // 滑窗越界若按 0 处理，四周会变暗 —— 这是盒式模糊最常见的 bug
        val bmp = Bitmap.createBitmap(24, 24, Bitmap.Config.ARGB_8888)
        bmp.eraseColor(Color.WHITE)
        val out = boxBlur(bmp, radius = 6)

        listOf(0 to 0, 23 to 0, 0 to 23, 23 to 23).forEach { (x, y) ->
            val c = out.getPixel(x, y)
            assertTrue("角点 ($x,$y) 不该变暗，实际=${Color.red(c)}", Color.red(c) >= 250)
        }
    }
}
