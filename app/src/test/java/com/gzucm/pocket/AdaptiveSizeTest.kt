package com.gzucm.pocket

import androidx.compose.ui.unit.dp
import com.gzucm.pocket.ui.adaptive.AppLayout
import com.gzucm.pocket.ui.adaptive.allowsMultiColumn
import com.gzucm.pocket.ui.adaptive.resolveLayout
import com.gzucm.pocket.ui.adaptive.usesNavigationRail
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 自适应断点守卫。
 *
 * 阈值一旦被改动（比如有人觉得 840 太保守改成 800），
 * 小米平板 6 Pro 横屏（872dp 可用宽）和小新 Pad 这类机器
 * 会掉到 Compact 档，左导航栏突然消失、退回底部 Tab。
 * 这些边界值必须钉住。
 */
class AdaptiveSizeTest {

    @Test
    fun `断点边界按官方 WindowSizeClass 划分`() {
        // < 600 → Phone
        assertEquals(AppLayout.Phone, resolveLayout(0.dp))
        assertEquals(AppLayout.Phone, resolveLayout(411.dp))
        assertEquals(AppLayout.Phone, resolveLayout(599.dp))

        // 600 ~ 839 → Compact
        assertEquals(AppLayout.Compact, resolveLayout(600.dp))
        assertEquals(AppLayout.Compact, resolveLayout(768.dp))
        assertEquals(AppLayout.Compact, resolveLayout(839.dp))

        // 840 ~ 1199 → Tablet
        assertEquals(AppLayout.Tablet, resolveLayout(840.dp))
        assertEquals(AppLayout.Tablet, resolveLayout(872.dp)) // 小米平板 6 Pro 横屏
        assertEquals(AppLayout.Tablet, resolveLayout(1199.dp))

        // >= 1200 → Large
        assertEquals(AppLayout.Large, resolveLayout(1200.dp))
        assertEquals(AppLayout.Large, resolveLayout(1600.dp))
    }

    @Test
    fun `只有平板及以上才用左侧导航`() {
        assertFalse("手机不该用左导航", AppLayout.Phone.usesNavigationRail)
        assertFalse("600~839 仍是底部 Tab", AppLayout.Compact.usesNavigationRail)
        assertTrue("平板该用左导航", AppLayout.Tablet.usesNavigationRail)
        assertTrue("大屏该用左导航", AppLayout.Large.usesNavigationRail)
    }

    @Test
    fun `分屏变窄后要能退回手机布局`() {
        // 平板分屏：原本 872dp 的窗口被切成两半
        assertEquals(AppLayout.Tablet, resolveLayout(872.dp))
        assertEquals(AppLayout.Phone, resolveLayout(436.dp))
        assertFalse(
            "分屏后应该退回底部 Tab，而不是硬撑左导航把内容挤成一条",
            resolveLayout(436.dp).usesNavigationRail,
        )
    }

    @Test
    fun `手机不允许双列但平板可以`() {
        assertFalse(AppLayout.Phone.allowsMultiColumn)
        assertTrue(AppLayout.Compact.allowsMultiColumn)
        assertTrue(AppLayout.Tablet.allowsMultiColumn)
    }
}
