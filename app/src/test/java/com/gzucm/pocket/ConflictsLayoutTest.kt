package com.gzucm.pocket

import com.gzucm.pocket.core.CourseSession
import com.gzucm.pocket.ui.schedule.placeSessions
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 课表重叠分列。
 *
 * 核心约束只有一条：**同一时段的两块绝不能落在同一列**。
 * 否则它们会画在同一个位置上，后画的盖住前画的，文字互相压成一团。
 */
class ConflictsLayoutTest {

    private fun c(name: String, start: Int, end: Int, wd: Int = 1) = CourseSession(
        name = name, code = "1", teachers = "", week = 8, weekday = wd,
        startUnit = start, endUnit = end,
        startTime = "", endTime = "", room = "", category = "理论教学",
    )

    @Test
    fun `没有冲突时全部占满整列`() {
        val r = placeSessions(listOf(c("A", 1, 2), c("B", 3, 4), c("C", 5, 6)))
        assertTrue(r.all { it.cols == 1 && it.col == 0 })
    }

    @Test
    fun `完全包含要分两列`() {
        // 长课 1-4，短课 2-3 完全落在里面
        val r = placeSessions(listOf(c("长", 1, 4), c("短", 2, 3)))
        assertEquals(2, r.map { it.cols }.max())
        assertEquals(2, r.map { it.col }.toSet().size)
    }

    @Test
    fun `首节相同要分两列`() {
        val r = placeSessions(listOf(c("长", 6, 10), c("短", 6, 7)))
        assertEquals(2, r.map { it.cols }.max())
    }

    @Test
    fun `部分重叠要分两列`() {
        // 3-5 与 4-6
        val r = placeSessions(listOf(c("A", 3, 5), c("B", 4, 6)))
        assertEquals(2, r.map { it.cols }.max())
    }

    @Test
    fun `两头各撞一门时两列就够`() {
        // 1-3、2-5、4-6：A 与 C 本身**不重叠**（3 结束、4 才开始），
        // 所以 A 和 C 可以共用一列，不该浪费成三列
        val r = placeSessions(listOf(c("A", 1, 3), c("B", 2, 5), c("C", 4, 6)))
        assertEquals(2, r.map { it.cols }.max())

        // 断言几何关系，不断言 col：轮换会重排 col，但「有没有真的压在一起」不该变
        val a = r.first { it.session.name == "A" }
        val cc = r.first { it.session.name == "C" }
        val w = maxOf(a.xFrac + a.wFrac, cc.xFrac + cc.wFrac) -
            minOf(a.xFrac, cc.xFrac)
        assertTrue("A 与 C 不重叠，且共用一列时应完全重合", w <= 1.001f)
    }

    @Test
    fun `三门互相交叠才分三列`() {
        // 1-3、2-5、3-6：第三节三门同时在，必须三列
        val r = placeSessions(listOf(c("A", 1, 3), c("B", 2, 5), c("C", 3, 6)))
        assertEquals(3, r.map { it.cols }.max())
        assertEquals(3, r.map { it.col }.toSet().size)
    }

    @Test
    fun `首尾相接不算冲突`() {
        // 1-2 和 3-4 中间空一节，不该分列
        val r = placeSessions(listOf(c("A", 1, 2), c("B", 3, 4)))
        assertTrue(r.all { it.cols == 1 })
    }

    @Test
    fun `任意两块有交集的都必须分在不同列`() {
        // 穷举一批组合，验证真正的硬约束
        val cases = listOf(
            listOf(c("A", 1, 4), c("B", 2, 3)),
            listOf(c("A", 1, 3), c("B", 3, 5)),
            listOf(c("A", 1, 2), c("B", 2, 3), c("C", 2, 4)),
            listOf(c("A", 1, 6), c("B", 2, 3), c("C", 4, 5)),
            listOf(c("A", 6, 10), c("B", 6, 7), c("C", 8, 9)),
        )
        for (case in cases) {
            val r = placeSessions(case)
            for (i in r.indices) {
                for (j in i + 1 until r.size) {
                    val a = r[i]; val b = r[j]
                    val overlap = a.session.startUnit <= b.session.endUnit &&
                        b.session.startUnit <= a.session.endUnit
                    if (overlap) {
                        assertTrue(
                            "「${a.session.name}」(${a.session.startUnit}-${a.session.endUnit}) 与 " +
                                "「${b.session.name}」(${b.session.startUnit}-${b.session.endUnit}) 重叠，却被排在同一列",
                            a.col != b.col,
                        )
                    }
                }
            }
        }
    }

    @Test
    fun `单个课程不报错`() {
        val r = placeSessions(listOf(c("A", 1, 2)))
        assertEquals(1, r.size)
        assertEquals(0, r[0].col)
        assertEquals(1, r[0].cols)
    }

    @Test
    fun `长度相近时错开 10%`() {
        // 3-5 与 4-6：长度相同，走「对称错位叠压」分支
        val r = placeSessions(listOf(c("A", 3, 5), c("B", 4, 6)))
        assertEquals(0.10f, r.first { it.col == 1 }.xFrac, 0.001f)
        assertEquals(0.0f, r.first { it.col == 0 }.xFrac, 0.001f)
    }

    @Test
    fun `嵌套时长课在下层且层级最低`() {
        // 1-4 套 2-3：长的在下、短的在上
        val r = placeSessions(listOf(c("长", 1, 4), c("短", 2, 3)))
        val long = r.first { it.session.name == "长" }
        val short = r.first { it.session.name == "短" }

        // 长课必须是最底层（level 0，满宽满高），否则下半段会被盖住
        assertEquals(0, long.level)
        // 短课在上层
        assertTrue("短课应在长课上面", short.level > long.level)
        assertTrue(short.z > long.z)
    }

    @Test
    fun `完全重叠时也走卡片叠放`() {
        // 两门节次完全相同 —— 没有纵向错位空间，必须靠层叠露边
        val r = placeSessions(listOf(c("甲", 2, 2), c("乙", 2, 2)))
        assertEquals("应分两层", 2, r.map { it.level }.toSet().size)
        // 一层是 level 0（大），一层是 level 1（小）
        assertTrue(r.any { it.level == 0 })
        assertTrue(r.any { it.level == 1 })
    }

    @Test
    fun `轮换一圈回到原点`() {
        val list = listOf(c("A", 1, 4), c("B", 2, 3), c("C", 2, 5))
        // 轮换前：col 0 是谁
        val base = placeSessions(list, true, { 0 })
        val top0 = base.first { it.col == base.map { p -> p.cols }.max() - 1 }

        // 轮 1 次：最上面换人
        val r1 = placeSessions(list, true, { 1 })
        val top1 = r1.first { it.col == r1.map { p -> p.cols }.max() - 1 }
        assertTrue("轮换后最上层应换成另一门课", top0.session.name != top1.session.name)

        // 轮 3 次（等于簇大小）应回到原样
        val r3 = placeSessions(list, true, { 3 })
        assertEquals(
            base.map { "${it.session.name}@${it.col}" }.sorted(),
            r3.map { "${it.session.name}@${it.col}" }.sorted(),
        )
    }

    @Test
    fun `轮换不改变重叠关系`() {
        val list = listOf(c("A", 1, 4), c("B", 2, 3))
        for (rot in 0..4) {
            val r = placeSessions(list, true, { rot })
            // 无论怎么轮，两块都在不同 col
            assertEquals(2, r.map { it.col }.toSet().size)
            // 且都在同一簇
            assertEquals(1, r.map { it.clusterId }.toSet().size)
        }
    }

    @Test
    fun `三门重叠点三次回到原样`() {
        val list = listOf(c("甲", 1, 3), c("乙", 1, 3), c("丙", 1, 3))
        fun topNames(rot: Int) = placeSessions(list, true, { rot })
            .sortedBy { it.level }
            .map { "${it.session.name}@L${it.level}" }

        val base = topNames(0)
        // 1-2-3 → 2-3-1：最底层升到最顶层
        assertEquals(
            listOf("乙@L0", "丙@L1", "甲@L2"),
            topNames(1),
        )
        assertEquals(listOf("丙@L0", "甲@L1", "乙@L2"), topNames(2))
        // 点三次回到原样
        assertEquals(base, topNames(3))
    }

    @Test
    fun `尺寸跟着层级走而不是跟着课程`() {
        val list = listOf(c("甲", 1, 3), c("乙", 1, 3))
        // rot=0：甲在 L0、乙在 L1
        val r0 = placeSessions(list, true, { 0 }).sortedBy { it.level }
        assertEquals("甲", r0[0].session.name)
        assertEquals(0, r0[0].level)

        // rot=1：乙升到 L0 当底层，甲退到 L1 —— 层级变了，尺寸自然跟着变
        val r1 = placeSessions(list, true, { 1 }).sortedBy { it.level }
        assertEquals("乙", r1[0].session.name)
        assertEquals(0, r1[0].level)
        assertEquals("甲", r1[1].session.name)
        assertEquals(1, r1[1].level)
    }

    @Test
    fun `不同星期的簇编号不能相撞`() {
        // 周一和周二各自有一个重叠簇，局部编号都是 0
        val mon = placeSessions(listOf(c("甲", 1, 3), c("乙", 1, 3)), true, { 0 }, dayOffset = 1)
        val tue = placeSessions(listOf(c("丙", 1, 3), c("丁", 1, 3)), true, { 0 }, dayOffset = 2)

        val monId = mon.map { it.clusterId }.toSet()
        val tueId = tue.map { it.clusterId }.toSet()

        assertTrue("周一的簇编号不应为空", monId.isNotEmpty())
        assertTrue("周二的簇编号不应为空", tueId.isNotEmpty())
        assertTrue(
            "周一的簇 $monId 与周二的簇 $tueId 撞了 —— 会导致点一个按钮两天一起轮换",
            monId.intersect(tueId).isEmpty(),
        )
    }

    @Test
    fun `同一天两个簇编号也不同`() {
        // 上午一组重叠、下午一组重叠
        val r = placeSessions(
            listOf(c("甲", 1, 3), c("乙", 1, 3), c("丙", 6, 8), c("丁", 6, 8)),
            true, { 0 }, dayOffset = 3,
        )
        assertEquals("同一天两簇应有 2 个不同编号", 2, r.map { it.clusterId }.toSet().size)
    }

    @Test
    fun `空列表不报错`() {
        assertTrue(placeSessions(emptyList()).isEmpty())
    }
}
