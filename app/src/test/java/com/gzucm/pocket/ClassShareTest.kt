package com.gzucm.pocket

import com.gzucm.pocket.core.Agenda
import com.gzucm.pocket.core.ClassDoc
import com.gzucm.pocket.core.ClassTaskDto
import com.gzucm.pocket.core.buildClassDoc
import com.gzucm.pocket.core.decodeClassToken
import com.gzucm.pocket.core.encodeClassToken
import com.gzucm.pocket.core.mergeClassTasks
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

/**
 * 班级作业的分享串与导入合并。
 *
 * 这里守四条口径，每条都对应一个真实会踩的坑：
 *   ① 编解码必须往返一致（丢字段 = 同学那边少了作业）
 *   ② 同名作业不能互相覆盖（靠 id 认人，不靠标题）
 *   ③ 旧串不能盖掉新内容（靠 updatedAt 比大小）
 *   ④ **本地勾掉的作业不能被远程改动打回未完成**（否则全班进度倒退）
 */
class ClassShareTest {

    private val today = LocalDate.of(2026, 11, 20)

    private fun task(
        id: String,
        title: String,
        due: Long? = null,
        note: String = "",
        updatedAt: Long = 1000L,
    ) = ClassTaskDto(id = id, title = title, dueAt = due, note = note, updatedAt = updatedAt)

    @Test
    fun `分享串编解码往返一致`() {
        val doc = ClassDoc(
            name = "张三",
            tasks = listOf(
                task("a", "实验报告", due = 1_700_000_000_000L, note = "手写"),
                task("b", "见习小结", due = null),
            ),
        )
        val back = decodeClassToken(encodeClassToken(doc))
        assertEquals("张三", back.name)
        assertEquals(2, back.tasks.size)
        assertEquals("实验报告", back.tasks[0].title)
        assertEquals(1_700_000_000_000L, back.tasks[0].dueAt)
        assertNull("长期待办的时间必须还是 null", back.tasks[1].dueAt)
    }

    @Test
    fun `导入后变成未完成的截止任务`() {
        val doc = ClassDoc("张三", listOf(task("a", "实验报告", due = 1_700_000_000_000L)))
        val (list, result) = mergeClassTasks(emptyList(), doc, today)

        assertEquals(1, result.added)
        val item = list.single()
        assertTrue("必须是截止任务，否则今日页和逾期标红都不认", item.isTask)
        assertNull("导入的不该是已完成", item.doneAt)
        assertEquals("class:张三", item.origin)
        assertEquals("cls:张三:a", item.id)
    }

    @Test
    fun `同名作业按 id 区分不会合并成一条`() {
        val doc = ClassDoc(
            "张三",
            listOf(task("w1", "实验报告"), task("w2", "实验报告")),
        )
        val (list, result) = mergeClassTasks(emptyList(), doc, today)
        assertEquals("两条同名的都得在", 2, result.added)
        assertEquals(2, list.count { it.title == "实验报告" })
    }

    @Test
    fun `新的更新能覆盖标题和截止时间`() {
        val first = ClassDoc("张三", listOf(task("a", "实验报告", due = 1000L, updatedAt = 100)))
        val (afterFirst, _) = mergeClassTasks(emptyList(), first, today)

        val second = ClassDoc("张三", listOf(task("a", "实验报告（改）", due = 2000L, updatedAt = 200)))
        val (afterSecond, result) = mergeClassTasks(afterFirst, second, today)

        assertEquals(1, result.updated)
        val item = afterSecond.single()
        assertEquals("实验报告（改）", item.title)
        assertEquals(2000L, item.deadlineAt)
    }

    @Test
    fun `旧串不能把新内容盖回去`() {
        val newer = ClassDoc("张三", listOf(task("a", "新标题", updatedAt = 500)))
        val (afterNew, _) = mergeClassTasks(emptyList(), newer, today)

        val older = ClassDoc("张三", listOf(task("a", "旧标题", updatedAt = 100)))
        val (afterOld, result) = mergeClassTasks(afterNew, older, today)

        assertEquals(0, result.updated)
        assertEquals(1, result.unchanged)
        assertEquals("新标题", afterOld.single().title)
    }

    /**
     * 最要紧的一条：学委改了个错别字，不能把同学已勾掉的作业打回未完成。
     * 那等于让全班的进度倒退，而且用户会以为勾选没保存。
     */
    @Test
    fun `本地勾掉的作业在导入后仍然完成`() {
        val first = ClassDoc("张三", listOf(task("a", "实验报告", updatedAt = 100)))
        val (imported, _) = mergeClassTasks(emptyList(), first, today)
        val doneAt = 9_999L
        val markedDone = imported.map { it.copy(doneAt = doneAt) }

        val revised = ClassDoc("张三", listOf(task("a", "实验报告（补充要求）", updatedAt = 200)))
        val (afterMerge, result) = mergeClassTasks(markedDone, revised, today)

        val item = afterMerge.single()
        assertEquals("标题该更新", "实验报告（补充要求）", item.title)
        assertEquals("完成状态必须保住", doneAt, item.doneAt)
        assertEquals(1, result.keptDone)
    }

    @Test
    fun `再次分享时 id 不会层层嵌套`() {
        val doc = ClassDoc("张三", listOf(task("a", "实验报告", updatedAt = 100)))
        val (imported, _) = mergeClassTasks(emptyList(), doc, today)

        val reshared = buildClassDoc("张三", imported, now = 500)
        assertEquals("a", reshared.tasks.single().id)
    }

    @Test
    fun `已完成的作业不会再被分享出去`() {
        val doc = ClassDoc("张三", listOf(task("a", "做完的", updatedAt = 100)))
        val (imported, _) = mergeClassTasks(emptyList(), doc, today)
        val withDone = imported.map { it.copy(doneAt = 1L) }

        val reshared = buildClassDoc("张三", withDone, now = 500)
        assertEquals("只带未完成的", 0, reshared.tasks.size)
    }

    @Test
    fun `粘了课表串会明确说串错了`() {
        val e = runCatching { decodeClassToken("GZP2:abcdef") }.exceptionOrNull()
        assertTrue(e is IllegalArgumentException)
        assertTrue("文案要指出是课表串", e!!.message!!.contains("课表"))
    }

    @Test
    fun `空串和不完整的串都给出人话提示`() {
        assertTrue(runCatching { decodeClassToken("  ") }.exceptionOrNull()!!.message!!.contains("复制"))
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
        val doc = ClassDoc("张三", listOf(task("a", "实验报告", updatedAt = 100)))
        val (list, result) = mergeClassTasks(listOf(local), doc, today)

        assertEquals(1, result.added)
        assertEquals(2, list.size)
        assertTrue(list.any { it.id == "local-1" && it.title == "自己去医院" })
    }
}
