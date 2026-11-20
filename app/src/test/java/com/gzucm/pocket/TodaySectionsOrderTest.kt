package com.gzucm.pocket

import com.gzucm.pocket.core.TodayBlock
import com.gzucm.pocket.core.TodaySections
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 今日页五块的「增删 + 排序」。
 *
 * 2026-11-20 白天把配置从一个字段一个布尔改成了「一个有序列表」，
 * 晚上又改了一次，因为致辉发现 **「减掉项目再加回去没有回到原来的位置」**。
 *
 * 现在是「**全量顺序 + 隐藏集合**」：
 *   · [TodaySections.allOrder] 永远含五块，被减掉的也在里面；
 *   · [TodaySections.hiddenSet] 只记谁不显示，减掉不动顺序；
 *   · [TodaySections.order] 是两者的组合视图，界面读的是它。
 *
 * 这个文件守住四条最容易写错的口径：
 *   ① 默认顺序必须还原改动前那个页面（老用户升级不该看出差别）
 *   ② 减掉的块不显示，但它在全量顺序里的位置要留着
 *   ③ 加回来**回到原位**，不是接在末尾（第一版错在这）
 *   ④ 拖动只改看得见的那几个槽位，隐藏块的位置不能被挤掉
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
        assertEquals(
            "全量顺序里它还在第 1 位 —— 位置就是这么保住的",
            TodayBlock.POWER,
            s.allOrder[1],
        )
    }

    @Test
    fun `加回来回到原位而不是末尾`() {
        val s = TodaySections()
            .hide(TodayBlock.WEATHER)
            .show(TodayBlock.WEATHER)
        assertEquals(
            "减掉再加回来必须回到第一位，不能沉到最底下",
            TodaySections.DefaultOrder,
            s.order,
        )
        assertTrue(s.weather)
    }

    @Test
    fun `中间的块减掉再加回也回到原位`() {
        val s = TodaySections()
            .hide(TodayBlock.UPCOMING)
            .show(TodayBlock.UPCOMING)
        assertEquals(TodaySections.DefaultOrder, s.order)
    }

    /**
     * 连续减掉几块再逐个加回来。
     *
     * 第一版在这里必然错：记「减掉时的下标」会互相打架 ——
     * 减 B 记 1、再减 C 也记 1，加回来时 B 和 C 抢同一个位置。
     * 全量顺序不动就没有这个问题。
     */
    @Test
    fun `连续减掉多块再逐个加回顺序不乱`() {
        val s = TodaySections()
            .hide(TodayBlock.POWER)
            .hide(TodayBlock.UPCOMING)
            .hide(TodayBlock.WEEK_OVERVIEW)
        assertEquals(
            listOf(TodayBlock.WEATHER, TodayBlock.COURSE),
            s.order,
        )

        val back = s
            .show(TodayBlock.POWER)
            .show(TodayBlock.UPCOMING)
            .show(TodayBlock.WEEK_OVERVIEW)
        assertEquals(TodaySections.DefaultOrder, back.order)
    }

    /** 换个加回来的顺序，结果也得一样 —— 位置由 allOrder 决定，跟操作顺序无关 */
    @Test
    fun `按任意顺序加回来结果都一致`() {
        val hidden = TodaySections()
            .hide(TodayBlock.POWER)
            .hide(TodayBlock.UPCOMING)
        val reversed = hidden
            .show(TodayBlock.UPCOMING)
            .show(TodayBlock.POWER)
        assertEquals(TodaySections.DefaultOrder, reversed.order)
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

    /**
     * 拖动之后，被减掉的块在**全量顺序**里的位置不能被挤掉。
     *
     * 这是 [TodaySections.withOrder] 存在的理由：界面上看不见隐藏的块，
     * 如果拖动结果直接覆盖 allOrder，隐藏块会被从中间抹掉，
     * 下次加回来就又落到末尾了。
     */
    @Test
    fun `拖动不会挤掉隐藏块的位置`() {
        // 默认 [天气, 电费, 课程, 接下来, 概览]，减掉电费 → 看见 4 个
        val s = TodaySections().hide(TodayBlock.POWER)
        // 把天气拖到最后
        val moved = s.move(0, 3)
        assertEquals(
            listOf(
                TodayBlock.COURSE,
                TodayBlock.UPCOMING,
                TodayBlock.WEEK_OVERVIEW,
                TodayBlock.WEATHER,
            ),
            moved.order,
        )
        assertEquals(
            "电费仍然在第二位（课程和概览之间）",
            TodayBlock.POWER,
            moved.allOrder[1],
        )
        // 加回来 → 落在第二位，不是末尾
        assertEquals(
            listOf(
                TodayBlock.COURSE,
                TodayBlock.POWER,
                TodayBlock.UPCOMING,
                TodayBlock.WEEK_OVERVIEW,
                TodayBlock.WEATHER,
            ),
            moved.show(TodayBlock.POWER).order,
        )
    }

    @Test
    fun `越界拖动不抛异常也不改顺序`() {
        val s = TodaySections()
        assertEquals(s.order, s.move(0, 9).order)
        assertEquals(s.order, s.move(-1, 0).order)
        assertEquals(s.order, s.move(2, 2).order)
    }

    /**
     * 给 [TodaySections.withOrder] 的错误输入：数量对不上时必须**一个块都不丢**。
     *
     * 这条来自 2026-11-20 实机的一次事故：拖动落地时本地副本比上层慢一拍，
     * 拿 4 项去覆盖 5 项，多的那块既不在显示组也不在隐藏组，界面上凭空消失。
     */
    @Test
    fun `withOrder 数量对不上时不丢块`() {
        val s = TodaySections()
        val short = s.withOrder(listOf(TodayBlock.POWER, TodayBlock.COURSE))
        assertEquals("一个块都不能少", TodayBlock.entries.size, short.allOrder.size)
        assertTrue(short.allOrder.containsAll(TodayBlock.entries))

        val long = s.withOrder(TodayBlock.entries + TodayBlock.WEATHER)
        assertEquals(TodayBlock.entries.size, long.allOrder.size)
    }

    @Test
    fun `课程不能被减`() {
        val s = TodaySections().hide(TodayBlock.COURSE)
        assertTrue("课程卡必须一直在", s.order.contains(TodayBlock.COURSE))
        assertEquals(5, s.order.size)
        assertTrue("课程不会出现在隐藏组里", TodayBlock.COURSE !in s.hidden)
    }

    /**
     * 老配置迁移：`allOrder` 里缺块时，要按**默认顺序的相对位置**插回去，
     * 并且把 removable 的那些记进 hiddenSet（老版本没提到的 = 用户关掉的）。
     *
     * 这条来自一次真实的翻车：第一版把课程钉死在中间，判据是「前两块在上面」。
     * 用户减掉电费和本周概览之后，「接下来」从第三位升到第二位，
     * 于是从课程下面跳到了上面 —— 看着像 bug。
     */
    @Test
    fun `迁移时课程插回原本的相对位置`() {
        val onlyWeatherAndUpcoming = TodaySections(
            allOrder = listOf(TodayBlock.WEATHER, TodayBlock.UPCOMING),
        ).normalized()
        assertEquals(
            listOf(TodayBlock.WEATHER, TodayBlock.COURSE, TodayBlock.UPCOMING),
            onlyWeatherAndUpcoming.order,
        )
        assertEquals(
            "老配置里没提到的两块应该被标成隐藏，而不是显示回来",
            listOf(TodayBlock.POWER, TodayBlock.WEEK_OVERVIEW),
            onlyWeatherAndUpcoming.hidden,
        )

        val empty = TodaySections(allOrder = emptyList()).normalized()
        assertEquals(listOf(TodayBlock.COURSE), empty.order)

        val onlyWeek = TodaySections(allOrder = listOf(TodayBlock.WEEK_OVERVIEW)).normalized()
        assertEquals(listOf(TodayBlock.COURSE, TodayBlock.WEEK_OVERVIEW), onlyWeek.order)
    }

    @Test
    fun `toggle 就是 hide 和 show 的组合`() {
        val hidden = TodaySections().toggle(TodayBlock.UPCOMING)
        assertFalse(hidden.upcoming)
        val back = hidden.toggle(TodayBlock.UPCOMING)
        assertTrue(back.upcoming)
        assertEquals(
            "toggle 两次应该回到原样，而不是把它挪到末尾",
            TodaySections.DefaultOrder,
            back.order,
        )
    }
}
