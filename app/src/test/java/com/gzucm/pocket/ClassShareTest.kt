package com.gzucm.pocket

import com.gzucm.pocket.core.Agenda
import com.gzucm.pocket.core.ClassPlan
import com.gzucm.pocket.core.ClassTaskDto
import com.gzucm.pocket.core.decodeClassToken
import com.gzucm.pocket.core.encodeClassToken
import com.gzucm.pocket.core.mergeClassTasks
import com.gzucm.pocket.core.toClassTask
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

/**
 * 班级日程：分享串编解码 + 导入合并。
 *
 * 守四条口径，每条都对应一个真实会踩的坑：
 *   ① 编解码往返一致（丢字段 = 同学那边少了作业）
 *   ② 同名作业不能互相覆盖（靠 id 认人，不靠标题）
 *   ③ 旧串不能盖掉新内容（靠 updatedAt 比大小）
 *   ④ **本地勾掉的作业不能被远程改动打回未完成**（否则全班进度倒退）
 */
class ClassShareTest {

    private val today = LocalDate.of(2026, 11, 20)

    private fun task(
        id: String,
        title: String,
        course: String = "",
        due: Long? = null,
        note: String = "",
        updatedAt: Long = 1000L,
    ) = ClassTaskDto(id = id, title = title, course = course, dueAt = due, note = note, updatedAt = updatedAt)

    private fun plan(vararg tasks: ClassTaskDto) = ClassPlan(key = "张三", tasks = tasks.toList())

    @Test
    fun `分享串编解码往返一致`() {
        val p = ClassPlan(
            key = "张三",
            tasks = listOf(
                task("a", "期末论文", course = "中医内科", due = 1_700_000_000_000L, note = "3000 字"),
                task("b", "见习小结", due = null),
            ),
        )
        val back = decodeClassToken(encodeClassToken(p))
        assertEquals("张三", back.key)
        assertEquals(2, back.tasks.size)
        assertEquals("期末论文", back.tasks[0].title)
        assertEquals("中医内科", back.tasks[0].course)
        assertEquals(1_700_000_000_000L, back.tasks[0].dueAt)
        assertNull("长期待办的时间必须还是 null", back.tasks[1].dueAt)
        assertEquals("3000 字", back.tasks[0].note)
    }

    @Test
    fun `导入后变成未完成的截止任务`() {
        val p = plan(task("a", "期末论文", due = 1_700_000_000_000L))
        val (list, result) = mergeClassTasks(emptyList(), p, today)

        assertEquals(1, result.added)
        val item = list.single()
        assertTrue("必须是截止任务，否则今日页和逾期标红都不认", item.isTask)
        assertNull("导入的不该是已完成", item.doneAt)
        assertEquals("class:张三", item.origin)
        assertEquals("cls:张三:a", item.id)
    }

    @Test
    fun `同名作业按 id 区分不会合并成一条`() {
        val p = plan(task("w1", "实验报告"), task("w2", "实验报告"))
        val (list, result) = mergeClassTasks(emptyList(), p, today)
        assertEquals("两条同名的都得在", 2, result.added)
        assertEquals(2, list.count { it.title == "实验报告" })
    }

    @Test
    fun `新的更新能覆盖标题和截止时间`() {
        val first = plan(task("a", "期末论文", due = 1000L, updatedAt = 100))
        val (afterFirst, _) = mergeClassTasks(emptyList(), first, today)

        val second = plan(task("a", "期末论文（延期）", due = 2000L, updatedAt = 200))
        val (afterSecond, result) = mergeClassTasks(afterFirst, second, today)

        assertEquals(1, result.updated)
        val item = afterSecond.single()
        assertEquals("期末论文（延期）", item.title)
        assertEquals(2000L, item.deadlineAt)
    }

    @Test
    fun `旧串不能把新内容盖回去`() {
        val newer = plan(task("a", "新标题", updatedAt = 500))
        val (afterNew, _) = mergeClassTasks(emptyList(), newer, today)

        val older = plan(task("a", "旧标题", updatedAt = 100))
        val (afterOld, result) = mergeClassTasks(afterNew, older, today)

        assertEquals(0, result.updated)
        assertEquals(1, result.unchanged)
        assertEquals("新标题", afterOld.single().title)
    }

    /**
     * 最要紧的一条：学委改了个错别字，不能把同学已勾掉的作业打回未完成。
     * 那等于让全班进度倒退，而且用户会以为勾选没保存。
     */
    @Test
    fun `本地勾掉的作业在导入后仍然完成`() {
        val first = plan(task("a", "期末论文", updatedAt = 100))
        val (imported, _) = mergeClassTasks(emptyList(), first, today)
        val doneAt = 9_999L
        val markedDone = imported.map { it.copy(doneAt = doneAt) }

        val revised = plan(task("a", "期末论文（补充要求）", updatedAt = 200))
        val (afterMerge, result) = mergeClassTasks(markedDone, revised, today)

        val item = afterMerge.single()
        assertEquals("标题该更新", "期末论文（补充要求）", item.title)
        assertEquals("完成状态必须保住", doneAt, item.doneAt)
        assertEquals(1, result.keptDone)
    }

    @Test
    fun `粘了课表串会明确说串错了`() {
        val e = runCatching { decodeClassToken("GZP2:abcdef") }.exceptionOrNull()
        assertTrue(e is IllegalArgumentException)
        assertTrue("文案要指出是课表串", e!!.message!!.contains("课表"))
    }

    @Test
    fun `空串和不完整的串都给出人话提示`() {
        assertTrue(
            runCatching { decodeClassToken("  ") }.exceptionOrNull()!!
                .message!!.contains("复制"),
        )
        assertTrue(
            runCatching { decodeClassToken("GZP3:xxxx") }.exceptionOrNull()!!
                .message!!.let { it.contains("截断") || it.contains("解不开") || it.contains("格式") },
        )
    }

    @Test
    fun `合并不会动本地自己建的日程`() {
        val local = Agenda(
            id = "local-1",
            title = "自己去医院",
            date = "2026-11-21",
            startMinute = 540,
            endMinute = 600,
        )
        val p = plan(task("a", "期末论文", updatedAt = 100))
        val (list, result) = mergeClassTasks(listOf(local), p, today)

        assertEquals(1, result.added)
        assertEquals(2, list.size)
        assertTrue(list.any { it.id == "local-1" && it.title == "自己去医院" })
    }

    /** 「从我的日程导入」：把已有的截止任务搬进清单，id 原样保留 */
    @Test
    fun `从日程搬进清单保留 id 和时间`() {
        val a = Agenda(
            id = "agenda-abc",
            title = "针灸见习小结",
            date = "2026-11-25",
            startMinute = 1439,
            endMinute = 1439,
            note = "800 字",
            isTask = true,
            deadlineAt = 1_700_000_000_000L,
        )
        val dto = a.toClassTask(now = 12345L)
        assertEquals("agenda-abc", dto.id)
        assertEquals("针灸见习小结", dto.title)
        assertEquals(1_700_000_000_000L, dto.dueAt)
        assertEquals("800 字", dto.note)
        assertEquals(12345L, dto.updatedAt)
    }

    /** 空清单不允许分享出去 —— 同学收到一串「0 条」只会困惑 */
    @Test
    fun `空清单的串会被拒绝`() {
        val empty = ClassPlan(key = "张三", tasks = emptyList())
        val e = runCatching { decodeClassToken(encodeClassToken(empty)) }.exceptionOrNull()
        assertTrue(e is IllegalArgumentException)
        assertTrue(e!!.message!!.contains("没有作业"))
    }
}
