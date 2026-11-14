package com.gzucm.pocket

import com.gzucm.pocket.core.AgendaGeometry
import com.gzucm.pocket.core.SlotTimes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * 自定义日程网格几何映射单测：真实分钟 ↔ 浮点行号。
 *
 * 纯 JVM，不依赖 Android / Robolectric。
 * 断言重点：单调不减、节次上课时刻落在整数行、课间/午休落在两行之间、
 * 往返近似还原（±15 分钟）、空 slots 不崩、只依赖传入的 slots。
 */
class AgendaGeometryTest {

    /** 教务全量节次 1..14 */
    private val full: List<Int> = (1..14).toList()
    /** 按课裁剪后的子集（如 1..12），函数必须只依赖传入的 slots */
    private val subset: List<Int> = (1..12).toList()
    /** 非连续子集，进一步验证「不假设节次连续」 */
    private val sparse: List<Int> = listOf(1, 3, 5, 8, 12)

    /** 从 SlotTimes 直接算某节上课分钟，避免和被测实现共用 minutesOfUnit */
    private fun startMinute(unit: Int): Int {
        val parts = SlotTimes.getValue(unit).split(":")
        return parts[0].toInt() * 60 + parts[1].toInt()
    }

    private fun assertMonotonic(slots: List<Int>) {
        var prev = AgendaGeometry.minuteToRow(-100, slots)
        for (m in 0..1500) {
            val row = AgendaGeometry.minuteToRow(m, slots)
            assertTrue("minute=$m 行号回退: prev=$prev row=$row", row >= prev - 0.001f)
            prev = row
        }
    }

    private fun assertClassStartOnIntegerRow(slots: List<Int>) {
        for (i in slots.indices) {
            val row = AgendaGeometry.minuteToRow(startMinute(slots[i]), slots)
            assertEquals(
                "第 ${slots[i]} 节上课时刻应落在第 $i 行附近",
                i.toDouble(), row.toDouble(), 0.05,
            )
        }
    }

    private fun assertRoundTrip(slots: List<Int>, minutes: List<Int>, tolerance: Int = 15) {
        for (m in minutes) {
            val back = AgendaGeometry.rowToMinute(AgendaGeometry.minuteToRow(m, slots), slots)
            assertTrue(
                "rowToMinute(minuteToRow($m))=$back 与 $m 偏差应 <= $tolerance 分钟",
                abs(back - m) <= tolerance,
            )
        }
    }

    @Test
    fun `每节上课时刻落在对应整数行附近`() {
        assertClassStartOnIntegerRow(full)
    }

    @Test
    fun `minuteToRow 单调不减`() {
        assertMonotonic(full)
    }

    @Test
    fun `早于第一节上课钳到 0`() {
        val first = startMinute(full.first())
        assertEquals(
            "清晨早于第一节应钳到 0",
            0.0, AgendaGeometry.minuteToRow(first - 30, full).toDouble(), 0.01,
        )
        assertEquals(
            "第一节上课时刻应恰为 0",
            0.0, AgendaGeometry.minuteToRow(first, full).toDouble(), 0.01,
        )
    }

    @Test
    fun `晚于最后一节下课钳到 slots 附近`() {
        val row = AgendaGeometry.minuteToRow(23 * 60, full)
        assertTrue("深夜行号应为有限值", !row.isNaN())
        assertTrue(
            "深夜应钳到 slots.size(${full.size}) 附近，实际 $row",
            row >= full.size - 0.5f && row <= full.size + 0.5f,
        )
    }

    @Test
    fun `课间大课间和午休落在两行之间`() {
        // 10 分钟课间：第 1 节下课 09:10(550) ~ 第 2 节上课 09:20(560)，对应第 0/1 行之间
        val break10 = AgendaGeometry.minuteToRow(555, full)
        assertTrue("10 分钟课间应落在第 0、1 行之间，实际 $break10", break10 > 0f && break10 < 1f)

        // 20 分钟大课间：第 2 节下课 10:00(600) ~ 第 3 节上课 10:20(620)，对应第 1/2 行之间
        val break20 = AgendaGeometry.minuteToRow(610, full)
        assertTrue("20 分钟大课间应落在第 1、2 行之间，实际 $break20", break20 > 1f && break20 < 2f)

        // 80 分钟午休：第 4 节下课 11:50(710) ~ 第 5 节上课 13:10(790)，对应第 3/4 行之间
        val lunch = AgendaGeometry.minuteToRow(750, full)
        assertTrue("午休应落在第 3、4 行之间，实际 $lunch", lunch > 3f && lunch < 4f)
    }

    @Test
    fun `rowToMinute 能近似还原 minuteToRow`() {
        // 只取映射区间内的分钟；两端「钳到 0 / slots.size」本身不可逆，另测。
        val minutes = listOf(
            510, 530, 550,       // 第 1 节上课 / 课中 / 下课
            555,                 // 10 分钟课间
            610,                 // 20 分钟大课间
            750,                 // 午休
            800, 940, 1060,      // 各节上课 / 课中
            1240, 1280, 1285,    // 末节上课 / 下课 / 末节后的空档
        )
        assertRoundTrip(full, minutes)
    }

    @Test
    fun `空 slots 不崩且给出安全值`() {
        val row = AgendaGeometry.minuteToRow(600, emptyList())
        assertTrue("空 slots 的 minuteToRow 不应为 NaN", !row.isNaN())
        assertTrue("空 slots 的 minuteToRow 应 >= 0，实际 $row", row >= 0f)

        val minute = AgendaGeometry.rowToMinute(2.5f, emptyList())
        assertTrue("空 slots 的 rowToMinute 应落在 0..1439，实际 $minute", minute in 0..1439)
    }

    @Test
    fun `裁剪后的 slots 子集也能自洽映射`() {
        assertClassStartOnIntegerRow(subset)
        assertMonotonic(subset)
        assertRoundTrip(subset, listOf(510, 530, 555, 610, 750, 1140, 1150, 1185))
    }

    @Test
    fun `非连续 slots 子集同样只依赖传入列表`() {
        assertClassStartOnIntegerRow(sparse)
        assertMonotonic(sparse)
        assertRoundTrip(sparse, listOf(510, 530, 600, 800, 1000, 1140, 1150))
    }
}
