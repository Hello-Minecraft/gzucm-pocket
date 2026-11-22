package com.gzucm.pocket

import com.gzucm.pocket.core.Agenda
import com.gzucm.pocket.core.ClassPlan
import com.gzucm.pocket.core.ClassSubscription
import com.gzucm.pocket.core.ClassSyncState
import com.gzucm.pocket.core.ClassTaskDto
import com.gzucm.pocket.core.ClassTaskState
import com.gzucm.pocket.core.autoImportIds
import com.gzucm.pocket.core.classTaskStates
import com.gzucm.pocket.core.mergeClassSync
import com.gzucm.pocket.core.withDeclined
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

/**
 * 「只导入勾中的那几条」（2026-11-22）—— 这块最容易写错，钉死它。
 *
 * 核心风险：`mergeClassTasks` 判断「学委删过东西」的办法是「本地有这条、
 * 但这份清单里没有它了」。如果为了部分导入先把 tasks 过滤一遍再合并，
 * 那**没勾选的老条目会被全部误判成已删除**，首页挂一屏橙色警告。
 *
 * 所以 [mergeClassSync] 的 `onlyIds` 只跳过、不改判定：
 * 判定用的是完整的 `plan.tasks`。
 */
class ClassPlanSelectTest {

    private val today = LocalDate.of(2026, 11, 22)

    private companion object {
        const val KEY = "张三"
    }

    // ── 小工具 ────────────────────────────────────────────────────

    private fun dto(
        id: String,
        title: String = "作业 $id",
        updatedAt: Long = 100L,
    ) = ClassTaskDto(id = id, title = title, dueAt = 1_700_000_000_000L, updatedAt = updatedAt)

    private fun plan(vararg tasks: ClassTaskDto) = ClassPlan(
        key = KEY,
        maintainer = "2023014093",
        className = "一院23中医(铁涛)5",
        tasks = tasks.toList(),
        updatedAt = tasks.maxOfOrNull { it.updatedAt } ?: 0L,
    )

    private fun imported(
        remoteId: String,
        title: String = "作业 $remoteId",
        remote: Long = 100L,
        localEdit: Long = 0L,
        done: Long? = null,
        gone: Boolean = false,
    ) = Agenda(
        id = "cls:$KEY:$remoteId",
        title = title,
        date = "2026-11-22",
        startMinute = 1439,
        endMinute = 1439,
        isTask = true,
        deadlineAt = 1_700_000_000_000L,
        doneAt = done,
        origin = "class:$KEY",
        remoteUpdatedAt = remote,
        classRemoteGone = gone,
        localEditedAt = localEdit,
    )

    private fun sub(declined: List<String> = emptyList()) = ClassSubscription(
        id = "a1b2c3",
        key = KEY,
        importedUpdatedAt = 100L,
        notifiedUpdatedAt = 100L,
        claimedAt = 1_700_000_000_000L,
        declinedIds = declined,
    )

    // ── 部分导入 ──────────────────────────────────────────────────

    /** 三选二：只进两条，第三条根本不出现 */
    @Test
    fun `只导入勾选的两条`() {
        val p = plan(dto("a"), dto("b"), dto("c"))
        val (list, r) = mergeClassSync(emptyList(), p, today, onlyIds = setOf("a", "b"))

        assertEquals(2, list.size)
        assertEquals(2, r.added)
        assertEquals(setOf("作业 a", "作业 b"), list.map { it.title }.toSet())
        assertTrue(list.none { it.title == "作业 c" })
    }

    /**
     * **最关键的一条**：已经全量导入过三条，这次只勾两条。
     *
     * 学委一条都没删，所以 orphaned 必须是 0、第三条不能带「已删除」标记。
     * 如果实现里先把 tasks 过滤了再合并，这条就会红 —— 这正是要拦的回归。
     */
    @Test
    fun `部分导入不会把没勾的标成已删除`() {
        val existing = listOf(imported("a"), imported("b"), imported("c"))
        val p = plan(dto("a"), dto("b"), dto("c"))

        val (list, r) = mergeClassSync(existing, p, today, onlyIds = setOf("a", "b"))

        assertEquals(3, list.size)
        assertEquals(0, r.orphaned)
        assertTrue(list.none { it.classRemoteGone })
        assertEquals(0, r.added)
    }

    /** 勾选的集合是空的 → 什么都不动（不是「全都不导入就等于全删」） */
    @Test
    fun `勾选为空时原地不动`() {
        val existing = listOf(imported("a"), imported("b"))
        val p = plan(dto("a"), dto("b"))

        val (list, r) = mergeClassSync(existing, p, today, onlyIds = emptySet())

        assertEquals(2, list.size)
        assertEquals(0, r.added)
        assertEquals(0, r.updated)
        assertEquals(0, r.orphaned)
        assertFalse(list.any { it.classRemoteGone })
    }

    /** 学委真删了一条，**不管这次勾了什么**都要标出来 */
    @Test
    fun `学委真删的条目仍然会被标成已删除`() {
        val existing = listOf(imported("a"), imported("b"))
        val p = plan(dto("a")) // b 被学委删了

        val (list, r) = mergeClassSync(existing, p, today, onlyIds = setOf("a"))

        assertEquals(1, r.orphaned)
        assertTrue(list.first { it.title == "作业 b" }.classRemoteGone)
    }

    /** 勾了但学委改过：走覆盖，且不重复新增 */
    @Test
    fun `勾选的那条被学委改过时会覆盖`() {
        val existing = listOf(imported("a", title = "旧标题", remote = 100L))
        val p = plan(dto("a", title = "新标题", updatedAt = 200L))

        val (list, r) = mergeClassSync(existing, p, today, onlyIds = setOf("a"))

        assertEquals(1, r.updated)
        assertEquals(0, r.added)
        assertEquals("新标题", list.single().title)
    }

    // ── 明确拒绝的条目 ────────────────────────────────────────────

    /** autoImportIds：清单 3 条、拒绝 1 条 → 只剩 2 个 id */
    @Test
    fun `自动导入会跳过明确拒绝的条目`() {
        val p = plan(dto("a"), dto("b"), dto("c"))
        val ids = autoImportIds(p, sub(declined = listOf("b")))

        assertEquals(setOf("a", "c"), ids)
    }

    /** 没认领过（sub 为 null）→ 全都要 */
    @Test
    fun `没认领时自动导入包含全部条目`() {
        val p = plan(dto("a"), dto("b"))
        assertEquals(setOf("a", "b"), autoImportIds(p, null))
    }

    /** 拒绝名单是**覆盖**不是追加：这次重新勾上，就必须从名单里拿掉 */
    @Test
    fun `withDeclined 整份替换`() {
        val st = ClassSyncState(subs = listOf(sub(declined = listOf("a", "b", "c"))))
        val next = st.withDeclined("a1b2c3", listOf("c"))

        assertEquals(listOf("c"), next.subs.single().declinedIds)
    }

    /** 去掉重复 id，不至于存出 ["c","c"] */
    @Test
    fun `withDeclined 去重`() {
        val st = ClassSyncState(subs = listOf(sub()))
        val next = st.withDeclined("a1b2c3", listOf("c", "c", "a"))
        assertEquals(listOf("c", "a"), next.subs.single().declinedIds)
    }

    /** 订阅不存在时什么都不做（不会凭空造一条） */
    @Test
    fun `withDeclined 对不存在的清单无副作用`() {
        val st = ClassSyncState()
        assertEquals(st, st.withDeclined("nope", listOf("a")))
    }

    // ── 详情页每条的状态 ──────────────────────────────────────────

    /** 本地没有 → NEW */
    @Test
    fun `详情页状态 新增`() {
        val states = classTaskStates(plan(dto("a")), emptyList())
        assertEquals(ClassTaskState.NEW, states["a"])
    }

    /** 本地有且版本一致 → IMPORTED */
    @Test
    fun `详情页状态 已加入`() {
        val states = classTaskStates(plan(dto("a", updatedAt = 100L)), listOf(imported("a", remote = 100L)))
        assertEquals(ClassTaskState.IMPORTED, states["a"])
    }

    /** 学委改过、本地没动 → CHANGED */
    @Test
    fun `详情页状态 学委改过`() {
        val states = classTaskStates(plan(dto("a", updatedAt = 200L)), listOf(imported("a", remote = 100L)))
        assertEquals(ClassTaskState.CHANGED, states["a"])
    }

    /**
     * 两边都动过 → CONFLICT。
     *
     * 冲突标记是**合并那一步**写进 `classConflict` 的（`localEditedAt > remoteUpdatedAt`），
     * 详情页只负责把它读成 CONFLICT。单看时间戳判不出「谁更权威」——
     * 本地时钟和服务端时钟不是一个钟。
     */
    @Test
    fun `详情页状态 冲突`() {
        val p = plan(dto("a", updatedAt = 200L))
        val flagged = classTaskStates(
            p,
            listOf(imported("a", remote = 100L, localEdit = 300L).copy(classConflict = true)),
        )
        assertEquals(ClassTaskState.CONFLICT, flagged["a"])
    }

    /** 清单 80 条（上限）也能算，不会漏或重 */
    @Test
    fun `详情页状态 八十条边界`() {
        val tasks = (1..80).map { dto("t$it") }
        val states = classTaskStates(plan(*tasks.toTypedArray()), emptyList())

        assertEquals(80, states.size)
        assertTrue(states.values.all { it == ClassTaskState.NEW })
    }

    /** 空清单：没有状态、不崩 */
    @Test
    fun `详情页状态 空清单`() {
        val states = classTaskStates(plan(), emptyList())
        assertTrue(states.isEmpty())
        assertNull(states["a"])
    }
}
