package com.gzucm.pocket

import com.gzucm.pocket.core.Agenda
import com.gzucm.pocket.core.AgendaStore
import com.gzucm.pocket.core.SessionStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * 日程持久化的隔离性。
 *
 * 这一组不是在测「存进去能读出来」（那是顺带），重点是**教务侧的清理流程
 * 不能碰到用户手输的日程**：
 *   · 同步成功会整表覆盖 cachedSessions
 *   · 缓存版本升级会 remove(cache_sessions)
 *   · 切学段 / 退出登录会清课表与成绩
 * 日程如果和课表共用存储，上面任意一条都会把它抹掉。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AgendaStoreTest {

    private val ctx = RuntimeEnvironment.getApplication()

    private fun rule(id: String, title: String) = Agenda(
        id = id,
        title = title,
        date = "2026-10-26",
        startMinute = 8 * 60 + 30,
        endMinute = 9 * 60 + 10,
    )

    @Before
    fun clean() {
        AgendaStore(ctx).clear()
    }

    @Test
    fun `存进去能原样读出来`() {
        val store = AgendaStore(ctx)
        store.save(listOf(rule("a", "晨读"), rule("b", "例会")))
        val back = store.load()
        assertEquals("应有两条", 2, back.size)
        assertEquals("晨读", back[0].title)
        assertEquals(8 * 60 + 30, back[0].startMinute)
    }

    @Test
    fun `upsert 按 id 覆盖而不是追加`() {
        val store = AgendaStore(ctx)
        store.upsert(rule("a", "晨读"))
        store.upsert(rule("a", "晨读（改）"))
        val back = store.load()
        assertEquals("同 id 不该出现两条", 1, back.size)
        assertEquals("晨读（改）", back[0].title)
    }

    @Test
    fun `删除只删指定那条`() {
        val store = AgendaStore(ctx)
        store.save(listOf(rule("a", "晨读"), rule("b", "例会")))
        val left = store.delete("a")
        assertEquals("应剩一条", 1, left.size)
        assertEquals("b", left[0].id)
    }

    @Test
    fun `课表缓存被清空时日程仍在`() {
        val agenda = AgendaStore(ctx)
        agenda.save(listOf(rule("a", "晨读")))

        val session = SessionStore(ctx)
        session.cachedSessions = """[{"name":"某门课"}]"""
        session.cachedSemester = "{}"
        // 模拟三条清缓存路径（它们清的都是 cache_sessions，不该碰 agenda_rules）
        session.cachedSessions = null
        session.cachedSemester = null
        session.dropStaleSessionCache()

        assertEquals("日程不该被课表清理带走", 1, agenda.load().size)
    }

    @Test
    fun `缺字段的旧 JSON 能反序列化`() {
        val store = AgendaStore(ctx)
        val prefs = ctx.getSharedPreferences("gzucm_pocket_agenda", android.content.Context.MODE_PRIVATE)
        // 故意少给 colorIndex / weekly / unitStart / remindMinutesBefore，模拟旧版本写下的数据
        prefs.edit().putString(
            "agenda_rules",
            """[{"id":"x","title":"旧数据","date":"2026-10-26","startMinute":510,"endMinute":550}]""",
        ).apply()

        val back = store.load()
        assertEquals("应能读出", 1, back.size)
        assertEquals(-1, back[0].colorIndex)
        assertEquals(false, back[0].weekly)
        assertEquals(-1, back[0].remindMinutesBefore)
    }

    @Test
    fun `坏 JSON 不会崩，只是读成空`() {
        val store = AgendaStore(ctx)
        val prefs = ctx.getSharedPreferences("gzucm_pocket_agenda", android.content.Context.MODE_PRIVATE)
        prefs.edit().putString("agenda_rules", "{不是数组}").apply()
        assertTrue("坏数据应降级成空列表", store.load().isEmpty())
    }
}
