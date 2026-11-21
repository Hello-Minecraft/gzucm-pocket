package com.gzucm.pocket

import com.gzucm.pocket.core.Agenda
import com.gzucm.pocket.core.CLASS_IGNORE_DOWNGRADE
import com.gzucm.pocket.core.ClassPlan
import com.gzucm.pocket.core.ClassPlanBrief
import com.gzucm.pocket.core.ClassPlanIndex
import com.gzucm.pocket.core.ClassPrompt
import com.gzucm.pocket.core.ClassSubscription
import com.gzucm.pocket.core.ClassSyncState
import com.gzucm.pocket.core.ClassTaskDto
import com.gzucm.pocket.core.canManualSync
import com.gzucm.pocket.core.classHash
import com.gzucm.pocket.core.detachFromClass
import com.gzucm.pocket.core.mergeClassSync
import com.gzucm.pocket.core.mergeClassTasks
import com.gzucm.pocket.core.newVersions
import com.gzucm.pocket.core.planId
import com.gzucm.pocket.core.promptIgnored
import com.gzucm.pocket.core.promptShown
import com.gzucm.pocket.core.resolveConflictByLocal
import com.gzucm.pocket.core.resolveConflictByRemote
import com.gzucm.pocket.core.shouldPrompt
import com.gzucm.pocket.core.shouldSync
import com.gzucm.pocket.core.syncedAt
import com.gzucm.pocket.core.withSub
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

/**
 * 班级日程云端版：合并规则（契约 §5）、同步节流（契约 §6）、标识算法（契约 §1）。
 *
 * 全部是纯 JVM 测试 —— ClassSync.kt 里没有 Android 类型，这里不需要 Robolectric。
 *
 * 固定 `now` 而不是 `System.currentTimeMillis()`：节流全是 24 小时边界，
 * 用真实时钟的话「跑在午夜附近」和「跑在别的时候」行为会不一样。
 */
class ClassSyncTest {

    private val today = LocalDate.of(2026, 11, 20)
    private val now = 1_800_000_000_000L

    private companion object {
        const val H = 60L * 60L * 1000L
        const val MIN = 60L * 1000L
        const val CLASS_NAME = "一院23中医(铁涛)5"
        const val KEY = "张三"
    }

    // ── 小工具 ────────────────────────────────────────────────────

    private fun dto(
        id: String,
        title: String = "期末论文",
        updatedAt: Long = 100L,
    ) = ClassTaskDto(id = id, title = title, dueAt = 1_700_000_000_000L, updatedAt = updatedAt)

    private fun plan(vararg tasks: ClassTaskDto) = ClassPlan(
        key = KEY,
        maintainer = "2023014093",
        className = CLASS_NAME,
        tasks = tasks.toList(),
        updatedAt = tasks.maxOfOrNull { it.updatedAt } ?: 0L,
    )

    /** 一条「已经导入过」的本地条目（origin / id 和 mergeClassSync 产出的完全一致） */
    private fun imported(
        remoteId: String = "a",
        title: String = "期末论文",
        remote: Long = 100L,
        localEdit: Long = 0L,
        done: Long? = null,
        gone: Boolean = false,
    ) = Agenda(
        id = "cls:$KEY:$remoteId",
        title = title,
        date = "2026-11-20",
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

    private fun brief(updatedAt: Long, id: String = "a1b2c3") =
        ClassPlanBrief(id = id, key = KEY, maintainer = "2023014093", count = 3, updatedAt = updatedAt)

    private fun sub(imported: Long = 100L, notified: Long = 0L) = ClassSubscription(
        id = "a1b2c3",
        key = KEY,
        importedUpdatedAt = imported,
        notifiedUpdatedAt = notified,
        claimedAt = 1_700_000_000_000L,
    )

    // ── 合并：六种分支 ────────────────────────────────────────────

    /** ① 新增：本地没有、远端有（顺带覆盖「只有 1 条」的边界） */
    @Test
    fun `新增一条会被标成未读`() {
        val (list, r) = mergeClassSync(emptyList(), plan(dto("a")))

        assertEquals(1, r.added)
        val a = list.single()
        assertEquals("cls:$KEY:a", a.id)
        assertEquals("class:$KEY", a.origin)
        assertTrue("新增的要标未读", a.classUnread)
        assertFalse(a.classConflict)
        assertFalse(a.classRemoteGone)
        assertEquals("本地没动过", 0L, a.localEditedAt)
    }

    /** ② 覆盖：远端更新更晚，且本地没动过 → 内容以远端为准，完成状态保留 */
    @Test
    fun `远端更新会覆盖内容但保留完成状态`() {
        val existing = listOf(imported(remote = 100L, done = 9_999L))
        val (list, r) = mergeClassSync(existing, plan(dto("a", "期末论文（延期）", 200L)))

        assertEquals(0, r.added)
        assertEquals(1, r.updated)
        assertEquals(1, r.keptDone)
        assertEquals(0, r.conflicts)

        val a = list.single()
        assertEquals("期末论文（延期）", a.title)
        assertEquals("勾掉的作业不能被远程改回未完成", 9_999L, a.doneAt)
        assertEquals(200L, a.remoteUpdatedAt)
        assertTrue(a.classUnread)
        assertFalse(a.classConflict)
    }

    /** ③ 冲突：远端更新更晚，但本地也手动改过 → 一个字都不覆盖 */
    @Test
    fun `本地也改过时只标冲突不覆盖内容`() {
        val existing = listOf(imported(remote = 100L, title = "我改过的标题", localEdit = 300L))
        val (list, r) = mergeClassSync(existing, plan(dto("a", "学委改的标题", 200L)))

        assertEquals("冲突不算覆盖", 0, r.updated)
        assertEquals(0, r.added)
        assertEquals(1, r.conflicts)
        assertTrue("要把冲突数报给 UI", r.summary().contains("学委也改过"))

        val a = list.single()
        assertEquals("本地内容不能被盖掉", "我改过的标题", a.title)
        assertTrue(a.classConflict)
        assertEquals("本地编辑时间要留着，方便并排对比", 300L, a.localEditedAt)
        assertEquals("远端版本没采用，remoteUpdatedAt 不动", 100L, a.remoteUpdatedAt)
    }

    /** ④ 未变：远端版本更旧或相同 → 内容不动 */
    @Test
    fun `远端更旧或同版本时不改动`() {
        val existing = listOf(imported(remote = 200L, title = "新标题"))

        val (afterOld, rOld) = mergeClassSync(existing, plan(dto("a", "旧标题", 100L)))
        assertEquals(1, rOld.unchanged)
        assertEquals(0, rOld.updated)
        assertEquals("新标题", afterOld.single().title)

        // 同版本也算未变：每次打开 App 都重新「更新」一遍会白刷未读角标
        val (_, rSame) = mergeClassSync(existing, plan(dto("a", "同版本", 200L)))
        assertEquals(1, rSame.unchanged)
        assertEquals(0, rSame.updated)
    }

    /** ⑤ 远端已删：只标 classRemoteGone，不删本地数据 */
    @Test
    fun `远端删掉的条目只标已删除不删除`() {
        val existing = listOf(imported(remoteId = "a"), imported(remoteId = "b", title = "被删的"))
        val (list, r) = mergeClassSync(existing, plan(dto("a")))

        assertEquals("不能自动删用户的数据", 2, list.size)
        assertEquals(1, r.orphaned)
        assertFalse(list.first { it.id.endsWith(":a") }.classRemoteGone)
        assertTrue(list.first { it.id.endsWith(":b") }.classRemoteGone)
        assertTrue(r.summary().contains("已经删掉"))
    }

    /** ⑤b 学委删了又加回来 → 橙色竖线要消失 */
    @Test
    fun `远端重新出现的条目会清掉已删除标记`() {
        val existing = listOf(imported(gone = true))
        val (list, r) = mergeClassSync(existing, plan(dto("a")))

        assertEquals(1, r.unchanged)
        assertFalse(list.single().classRemoteGone)
    }

    /** ⑥ 已完成保留：远程改了标题，本地勾选状态不能被打回 */
    @Test
    fun `已完成的条目在远程改动后仍然完成`() {
        val existing = listOf(imported(remote = 100L, done = 4_242L))
        val (list, r) = mergeClassSync(existing, plan(dto("a", "期末论文（补充要求）", 200L)))

        val a = list.single()
        assertEquals("期末论文（补充要求）", a.title)
        assertEquals(4_242L, a.doneAt)
        assertEquals(1, r.keptDone)
        assertEquals(1, r.updated)
    }

    // ── 合并：边界 ────────────────────────────────────────────────

    /** 空清单：本地条目全部变成「远端已删」，一条都不许消失 */
    @Test
    fun `空清单只标记不删除本地条目`() {
        val existing = listOf(imported(remoteId = "a"))
        val (list, r) = mergeClassSync(existing, plan())

        assertEquals(1, list.size)
        assertEquals(0, r.added)
        assertEquals(1, r.orphaned)
        assertTrue(list.single().classRemoteGone)
    }

    /** 50 条（契约上限）：id 不重复、全部入库 */
    @Test
    fun `五十条上限都能合并且 id 不重复`() {
        val tasks = (1..50).map { dto("t$it", "作业 $it") }
        val (list, r) = mergeClassSync(emptyList(), plan(*tasks.toTypedArray()))

        assertEquals(50, r.added)
        assertEquals(50, list.size)
        assertEquals("id 不能撞", 50, list.map { it.id }.toSet().size)
        assertEquals(50, list.count { it.classUnread })
    }

    /** 老路径（粘贴分享串）保持原样：不标未读 */
    @Test
    fun `手动导入默认不标未读`() {
        val (list, _) = mergeClassTasks(emptyList(), plan(dto("a")), today)
        assertFalse(list.single().classUnread)
    }

    // ── 冲突善后 ──────────────────────────────────────────────────

    @Test
    fun `冲突选远端时内容换成学委版并保留完成状态`() {
        val local = imported(title = "我改过的标题", remote = 100L, localEdit = 300L, done = 7_777L)
            .copy(classConflict = true)

        val a = resolveConflictByRemote(local, dto("a", "学委改的标题", 200L))

        assertEquals("学委改的标题", a.title)
        assertEquals("完成状态不能丢", 7_777L, a.doneAt)
        assertEquals("id 不变，否则会变成孤儿", local.id, a.id)
        assertEquals(200L, a.remoteUpdatedAt)
        assertFalse(a.classConflict)
        assertEquals(0L, a.localEditedAt)
    }

    @Test
    fun `冲突选本地时保留内容且不会再次判冲突`() {
        val local = imported(remote = 100L, title = "我改过的标题", localEdit = 300L)
            .copy(classConflict = true)
        val kept = resolveConflictByLocal(local, remoteUpdatedAt = 200L)

        assertEquals("我改过的标题", kept.title)
        assertEquals(200L, kept.remoteUpdatedAt)
        assertFalse(kept.classConflict)

        // 再合一次同一个远端版本：走 unchanged，不该再弹冲突
        val (list, r) = mergeClassSync(listOf(kept), plan(dto("a", "学委改的标题", 200L)))
        assertEquals(1, r.unchanged)
        assertEquals(0, r.conflicts)
        assertEquals("我改过的标题", list.single().title)
    }

    @Test
    fun `脱离清单后变成本地条目`() {
        val a = detachFromClass(imported(gone = true))
        assertEquals("", a.origin)
        assertEquals(0L, a.remoteUpdatedAt)
        assertFalse(a.classRemoteGone)
    }

    // ── 节流：规则 1（24 小时） ───────────────────────────────────

    @Test
    fun `自动同步的二十四小时边界`() {
        assertFalse("23h59m 不该同步", shouldSync(now - 23 * H - 59 * MIN, now))
        assertTrue("24h01m 该同步", shouldSync(now - 24 * H - MIN, now))
        assertFalse("正好 24h 落在边界上，不重复打一次", shouldSync(now - 24 * H, now))
        assertTrue("从没同步过（0）该同步", shouldSync(0L, now))
        assertFalse("时钟回拨不该被判成该同步", shouldSync(now + 5 * H, now))
    }

    @Test
    fun `手动同步只防六十秒连点`() {
        assertFalse(canManualSync(now - 30_000L, now))
        assertTrue(canManualSync(now - 61_000L, now))
        assertTrue("手动入口不受 24 小时限制", canManualSync(now - 60_000L, now))
    }

    // ── 节流：规则 2（同版本只弹一次） ────────────────────────────

    @Test
    fun `同一版本只弹一次`() {
        val state = ClassSyncState()
        val s = sub(imported = 100L, notified = 200L)

        assertEquals("同一版已经弹过", ClassPrompt.NONE, shouldPrompt(brief(200L), s, state, now))
        assertEquals("比通知过的版本还旧", ClassPrompt.NONE, shouldPrompt(brief(150L), s, state, now))
        assertFalse(shouldPrompt(brief(200L), s, state, now) == ClassPrompt.DIALOG)

        assertEquals("新版才弹", ClassPrompt.DIALOG, shouldPrompt(brief(201L), s, state, now))
    }

    @Test
    fun `没有摘要时不打扰`() {
        assertEquals(ClassPrompt.NONE, shouldPrompt(null, sub(), ClassSyncState(), now))
    }

    // ── 节流：规则 3（一天一次 + 忽略降级） ──────────────────────

    @Test
    fun `一天最多弹一次窗`() {
        val s = sub(notified = 0L)

        assertEquals(
            "25 小时前弹过，今天可以再弹",
            ClassPrompt.DIALOG,
            shouldPrompt(brief(300L), s, ClassSyncState(lastPromptAt = now - 25 * H), now),
        )
        assertEquals(
            "1 小时前弹过，今天不能再弹（降到横幅）",
            ClassPrompt.BANNER,
            shouldPrompt(brief(300L), s, ClassSyncState(lastPromptAt = now - 1 * H), now),
        )
        assertEquals(
            "正好 24 小时仍然算「今天弹过」，和 shouldSync 同口径",
            ClassPrompt.BANNER,
            shouldPrompt(brief(300L), s, ClassSyncState(lastPromptAt = now - 24 * H), now),
        )
    }

    @Test
    fun `连续忽略三次降级为横幅`() {
        val s = sub(notified = 0L)

        assertEquals(
            "忽略 2 次还算正常，可以再弹一次",
            ClassPrompt.DIALOG,
            shouldPrompt(brief(300L), s, ClassSyncState(ignored = CLASS_IGNORE_DOWNGRADE - 1), now),
        )
        assertEquals(
            "忽略 3 次就只剩横幅",
            ClassPrompt.BANNER,
            shouldPrompt(brief(300L), s, ClassSyncState(ignored = CLASS_IGNORE_DOWNGRADE), now),
        )
        assertEquals(
            "忽略 10 次也别弹",
            ClassPrompt.BANNER,
            shouldPrompt(brief(300L), s, ClassSyncState(ignored = 10), now),
        )
    }

    @Test
    fun `弹窗和忽略会正确记账`() {
        val shown = ClassSyncState(ignored = 2).promptShown(now)
        assertEquals(now, shown.lastPromptAt)
        assertEquals("看了就不算忽略，计数清零", 0, shown.ignored)

        val ignored = ClassSyncState(ignored = 2).promptIgnored()
        assertEquals(3, ignored.ignored)

        assertEquals(now, ClassSyncState().syncedAt(now).lastSyncAt)
    }

    // ── 订阅与「有没有新版」 ─────────────────────────────────────

    @Test
    fun `订阅按 id 覆盖不重复`() {
        val state = ClassSyncState().withSub(sub(imported = 100L)).withSub(sub(imported = 200L))
        assertEquals(1, state.subs.size)
        assertEquals(200L, state.subs.single().importedUpdatedAt)
    }

    @Test
    fun `只把比本地导入版本新的清单算成有更新`() {
        val index = ClassPlanIndex(
            plans = listOf(brief(300L, "a1b2c3"), brief(100L, "d4e5f6"), brief(900L, "999999")),
        )
        val state = ClassSyncState(
            subs = listOf(
                sub(imported = 200L, notified = 0L).copy(id = "a1b2c3"),
                sub(imported = 100L, notified = 0L).copy(id = "d4e5f6"),
                // 999999 没订阅（没认领）→ 不算「更新」
            ),
        )
        val newer = newVersions(index, state)
        assertEquals(listOf("a1b2c3"), newer.map { it.id })
    }

    // ── 标识算法 ──────────────────────────────────────────────────

    @Test
    fun `classHash 和 planId 确定且长度固定`() {
        val h1 = classHash(CLASS_NAME)
        val h2 = classHash(CLASS_NAME)
        assertEquals("同一输入两次必须一致", h1, h2)
        assertEquals(16, h1.length)
        assertTrue("必须是纯小写十六进制：$h1", Regex("^[0-9a-f]{16}$").matches(h1))

        val p1 = planId(KEY)
        assertEquals("同一输入两次必须一致", p1, planId(KEY))
        assertEquals(6, p1.length)
        assertTrue("必须是纯小写十六进制：$p1", Regex("^[0-9a-f]{6}$").matches(p1))

        /*
         * 钉死具体值：sha256("一院23中医(铁涛)5") 的前 16 位。
         * 这一条用来抓「有人顺手 trim / 转全角」—— 一旦规范化，值和这个就对不上，
         * 两端算出的目录会分叉（契约 §1 明确禁止规范化）。
         * 文档里那个 3f9a1c7d2b4e5068 只是示意，不是真实哈希。
         */
        assertEquals("7670dce43736f6bd", h1)
        assertEquals("1d841b", p1)
    }

    @Test
    fun `不同班级名和不同密钥不会撞在一起`() {
        assertNotEquals(classHash(CLASS_NAME), classHash("$CLASS_NAME "))
        assertNotEquals(classHash(CLASS_NAME), classHash("一院23中医（铁涛）5"))
        assertNotEquals(planId("张三"), planId("李四"))
    }
}
