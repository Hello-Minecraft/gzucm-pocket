package com.gzucm.pocket

import com.gzucm.pocket.ui.schedule.SlotEndTimes
import com.gzucm.pocket.ui.schedule.SlotTimes
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 课表左表头的下课时间。
 *
 * 广中医每节课 40 分钟（08:30 上课、09:10 下课，课间 10 分钟）。
 * 之前代码注释写的是 45 分钟，是错的 —— 这个测试把 40 锁住。
 */
class SlotTimeTest {

    @Test
    fun `第一节 08点30 上课 09点10 下课`() {
        assertEquals("08:30", SlotTimes[1])
        assertEquals("09:10", SlotEndTimes[1])
    }

    @Test
    fun `第二节 09点20 到 10点整`() {
        assertEquals("09:20", SlotTimes[2])
        assertEquals("10:00", SlotEndTimes[2])
    }

    @Test
    fun `下午第一节 14点 到 14点40`() {
        assertEquals("14:00", SlotTimes[6])
        assertEquals("14:40", SlotEndTimes[6])
    }

    @Test
    fun `晚上最后一节 20点40 到 21点20`() {
        assertEquals("20:40", SlotTimes[14])
        assertEquals("21:20", SlotEndTimes[14])
    }

    @Test
    fun `每一节都恰好是40分钟`() {
        SlotTimes.forEach { (unit, start) ->
            val s = start.split(":")
            val startMin = s[0].toInt() * 60 + s[1].toInt()
            val e = SlotEndTimes.getValue(unit).split(":")
            val endMin = e[0].toInt() * 60 + e[1].toInt()
            assertEquals("第 $unit 节时长不对", 40, endMin - startMin)
        }
    }

    @Test
    fun `第5节 13点10 上课 13点50 下课`() {
        assertEquals("13:10", SlotTimes[5])
        assertEquals("13:50", SlotEndTimes[5])
    }

    @Test
    fun `第10节 17点20 上课 18点整 下课`() {
        assertEquals("17:20", SlotTimes[10])
        assertEquals("18:00", SlotEndTimes[10])
    }

    @Test
    fun `第11节 18点10 上课 18点50 下课`() {
        assertEquals("18:10", SlotTimes[11])
        assertEquals("18:50", SlotEndTimes[11])
    }

    @Test
    fun `节次是连续的1到14`() {
        // 曾经误以为跳号（缺 5/10/11），实际是连续编号
        assertEquals((1..14).toList(), SlotTimes.keys.sorted())
    }

    @Test
    fun `每个节次都有下课时间`() {
        assertEquals(SlotTimes.keys, SlotEndTimes.keys)
    }
}
