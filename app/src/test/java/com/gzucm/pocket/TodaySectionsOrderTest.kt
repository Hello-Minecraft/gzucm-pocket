package com.gzucm.pocket

import com.gzucm.pocket.core.TodayBlock
import com.gzucm.pocket.core.TodaySections
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 今日页四块的「增删 + 排序」。
 *
 * 2026-11-20 把配置从一个字段一个布尔改成了**一个有序列表**：
 * 在 `order` 里 = 显示且顺序如此，不在 = 被减掉了。
 * 这个文件守住三条最容易写错的口径：
 *   ① 默认顺序必须还原改动前那个页面（老用户升级不该看出差别）
 *   ② 减掉的块要从顺序里拿掉，不能只是「标记为隐藏」还占着位置
 *   ③ 加回来是接在**末尾**，不是插回原位 —— 界面上用户看到的就是这样
 */
class TodaySectionsOrderTest {

    @Test
    fun `默认顺序还原改动前那个页面`() {
        assertEquals(
            listOf(
                TodayBlock.WEATHER,
                TodayBlock.POWER,
                TodayBlock.COURSE,
                TodayBlock.UPCOMING,
                TodayBlock.WEEK_OVERVIEW,
            ),
            TodaySections().order,
        )
    }

    @Test
    fun `减掉的块从顺序里移除并落到 hidden`() {
        val s = TodaySections().hide(TodayBlock.POWER)
        assertFalse("减掉之后 power 应该是关的", s.power)
        assertEquals(
            listOf(
                TodayBlock.WEATHER,
                TodayBlock.COURSE,
                TodayBlock.UPCOMING,
                TodayBlock.WEEK_OVERVIEW,
            ),
            s.order,
        )
        assertEquals(listOf(TodayBlock.POWER), s.hidden)
    }

    @Test
    fun `加回来接在末尾`() {
        val s = TodaySections()
            .hide(TodayBlock.WEATHER)
            .show(TodayBlock.WEATHER)
        assertEquals(
            "加回来应该排在最后，用户才能看到它回到哪",
            TodayBlock.WEATHER,
            s.order.last(),
        )
        assertTrue(s.weather)
    }

    @Test
    fun `四块都减掉是合法状态`() {
        val s = TodaySections.DefaultOrder.fold(TodaySections()) { acc, b -> acc.hide(b) }
        assertEquals("今日页只剩课程也是允许的", listOf(TodayBlock.COURSE), s.order)
        assertEquals(4, s.hidden.size)
    }

    @Test
    fun `拖动换位`() {
        val s = TodaySections().move(0, 2)
        assertEquals(
            listOf(
                TodayBlock.POWER,
                TodayBlock.COURSE,
                TodayBlock.WEATHER,
                TodayBlock.UPCOMING,
                TodayBlock.WEEK_OVERVIEW,
            ),
            s.order,
        )
    }

    @Test
    fun `越界拖动不抛异常也不改顺序`() {
        val s = TodaySections()
        assertEquals(s.order, s.move(0, 9).order)
        assertEquals(s.order, s.move(-1, 0).order)
        assertEquals(s.order, s.move(2, 2).order)
    }

    @Test
    fun `课程不能被减`() {
        val s = TodaySections().hide(TodayBlock.COURSE)
        assertTrue("课程卡必须一直在", s.order.contains(TodayBlock.COURSE))
        assertEquals(5, s.order.size)
        assertTrue("课程不会出现在隐藏组里", TodayBlock.COURSE !in s.hidden)
    }

    /**
     * 老配置迁移：`order` 里没有课程卡时，要按**默认顺序的相对位置**插回去。
     *
     * 这条来自一次真实的翻车：第一版把课程钉死在中间，判据是「前两块在上面」。
     * 用户减掉电费和本周概览之后，「接下来」从第三位升到第二位，
     * 于是从课程下面跳到了上面 —— 看着像 bug。
     */
    @Test
    fun `迁移时课程插回原本的相对位置`() {
        val onlyWeatherAndUpcoming = TodaySections(
            order = listOf(TodayBlock.WEATHER, TodayBlock.UPCOMING),
        ).normalized()
        assertEquals(
            listOf(TodayBlock.WEATHER, TodayBlock.COURSE, TodayBlock.UPCOMING),
            onlyWeatherAndUpcoming.order,
        )

        val empty = TodaySections(order = emptyList()).normalized()
        assertEquals(listOf(TodayBlock.COURSE), empty.order)

        val onlyWeek = TodaySections(order = listOf(TodayBlock.WEEK_OVERVIEW)).normalized()
        assertEquals(listOf(TodayBlock.COURSE, TodayBlock.WEEK_OVERVIEW), onlyWeek.order)
    }

    @Test
    fun `toggle 就是 hide 和 show 的组合`() {
        val hidden = TodaySections().toggle(TodayBlock.UPCOMING)
        assertFalse(hidden.upcoming)
        val back = hidden.toggle(TodayBlock.UPCOMING)
        assertTrue(back.upcoming)
        assertEquals(TodayBlock.UPCOMING, back.order.last())
    }
}
