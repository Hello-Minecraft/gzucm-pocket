package com.gzucm.pocket

import android.graphics.Bitmap
import android.graphics.Color
import com.gzucm.pocket.ui.components.frostSrcRect
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
 * 1. **采样坐标**（[frostSrcRect]）写错时，磨砂块整体偏移几像素，
 *    肉眼只觉得「有点脏」，看不出是错的 —— 必须靠数学断言锁住。
 * 2. **模糊算法**（[boxBlur]）滑窗边界处理错时，四周会发黑。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class FrostedBackdropTest {

    // ── 采样坐标 ──────────────────────────────────────────

    @Test
    fun `图与区域等比时按二倍缩放`() {
        // 100×100 的图铺到 200×200 区域 → 放大 2 倍，无裁切
        val r = frostSrcRect(
            bmpW = 100, bmpH = 100, wallW = 200f, wallH = 200f,
            relX = 0f, relY = 0f, cellW = 100f, cellH = 100f,
        )
        assertEquals(0, r.left)
        assertEquals(0, r.top)
        assertEquals(50, r.width)
        assertEquals(50, r.height)
    }

    @Test
    fun `格子落在右下角时取图的右下角`() {
        val r = frostSrcRect(
            bmpW = 100, bmpH = 100, wallW = 200f, wallH = 200f,
            relX = 100f, relY = 100f, cellW = 100f, cellH = 100f,
        )
        assertEquals(50, r.left)
        assertEquals(50, r.top)
        assertEquals(50, r.width)
        assertEquals(50, r.height)
    }

    @Test
    fun `宽图铺进窄区域时左右被居中裁掉`() {
        // 100×50 的图（2:1）铺进 200×200：scale = max(2, 4) = 4
        // 画出来是 400×200，左右各溢出 100 → 原图 x=0 对应屏幕 x=-100
        // 格子左上角在屏幕 0 → 原图 (0+100)/4 = 25
        val r = frostSrcRect(
            bmpW = 100, bmpH = 50, wallW = 200f, wallH = 200f,
            relX = 0f, relY = 0f, cellW = 100f, cellH = 100f,
        )
        assertEquals(25, r.left)
        assertEquals(0, r.top)
        assertEquals(25, r.width)
        assertEquals(25, r.height)
    }

    @Test
    fun `超出边缘时钳在图像范围内`() {
        // 格子跑到壁纸区外面（叠压缩进可能算出负值/越界），不能崩、不能取到图外
        val r = frostSrcRect(
            bmpW = 100, bmpH = 100, wallW = 200f, wallH = 200f,
            relX = 900f, relY = 900f, cellW = 50f, cellH = 50f,
        )
        assertTrue("left 不能越过右边界", r.left + r.width <= 100)
        assertTrue("top 不能越过下边界", r.top + r.height <= 100)
        assertTrue("宽高至少 1 像素", r.width >= 1 && r.height >= 1)
    }

    @Test
    fun `负数偏移不会取到负坐标`() {
        val r = frostSrcRect(
            bmpW = 100, bmpH = 100, wallW = 200f, wallH = 200f,
            relX = -50f, relY = -50f, cellW = 40f, cellH = 40f,
        )
        assertEquals(0, r.left)
        assertEquals(0, r.top)
    }

    @Test
    fun `空图不会除零`() {
        val r = frostSrcRect(0, 0, 200f, 200f, 0f, 0f, 10f, 10f)
        assertTrue(r.width >= 1 && r.height >= 1)
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
