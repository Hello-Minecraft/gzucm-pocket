package com.gzucm.pocket

import android.app.AlarmManager
import android.app.NotificationManager
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.gzucm.pocket.core.Agenda
import com.gzucm.pocket.core.AgendaReminder
import com.gzucm.pocket.core.Semester
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.time.LocalDate

/**
 * 日程提醒真的交给了 AlarmManager（Robolectric，不联真机）。
 *
 * 纯函数的 plan() 已经单测过，这里补的是「plan → AlarmManager」这一段：
 * 排了几条、重复调用会不会翻倍、删掉规则后能不能清干净。
 * 这三件事出错的表现分别是「不响」「响两声」「改了还在响」，都不是 plan 能测出来的。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AgendaReminderScheduleTest {

    private val ctx: Context = ApplicationProvider.getApplicationContext()

    /** 以「本周」为第 8 周造一个学期，测试不会因为真实日期漂移而失败 */
    private val semester: Semester = run {
        val today = LocalDate.now()
        val monday = today.minusDays((today.dayOfWeek.value - 1).toLong())
        Semester.of("test202601", "测试学期", monday.minusWeeks(7), 19)
    }

    private val tomorrow: LocalDate = LocalDate.now().plusDays(1)

    private fun rule(id: String = "r1", remind: Int = 10) = Agenda(
        id = id,
        title = "复习内科学",
        date = tomorrow.toString(),
        startMinute = 9 * 60,
        endMinute = 10 * 60,
        place = "图书馆",
        remindLeads = if (remind < 0) emptyList() else listOf(remind),
    )

    private fun alarms() =
        shadowOf(ctx.getSystemService(AlarmManager::class.java)).scheduledAlarms

    @Test
    fun `排程会真的把闹钟交给 AlarmManager`() {
        AgendaReminder.scheduleAll(ctx, semester, listOf(rule()))

        val list = alarms()
        assertEquals("有提醒的日程应该排出 1 条闹钟", 1, list.size)
        assertEquals(
            "闹钟时刻 = 明早 09:00 减 10 分钟",
            AgendaReminder.epochAt(tomorrow, 9 * 60) - 10 * 60_000L,
            list[0].triggerAtMs,
        )
        assertTrue("排完要记下 code，否则下次取消不掉", AgendaReminder.loadCodes(ctx).isNotEmpty())
    }

    @Test
    fun `重复排程不会把闹钟排成两份`() {
        AgendaReminder.scheduleAll(ctx, semester, listOf(rule()))
        AgendaReminder.scheduleAll(ctx, semester, listOf(rule()))

        assertEquals("第二次排之前必须先把上一轮 cancel 干净", 1, alarms().size)
    }

    @Test
    fun `改成不提醒之后旧闹钟要被清掉`() {
        AgendaReminder.scheduleAll(ctx, semester, listOf(rule()))
        assertEquals(1, alarms().size)

        AgendaReminder.scheduleAll(ctx, semester, listOf(rule(remind = -1)))

        assertTrue("remind = -1 时应一条都不剩", alarms().isEmpty())
        assertTrue("code 记录也要清空", AgendaReminder.loadCodes(ctx).isEmpty())
    }

    @Test
    fun `一条日程选三个档位会排三个闹钟`() {
        val multi = Agenda(
            id = "r1",
            title = "交材料",
            // 用 3 天后而非明天：提前 1 天那条会落在明天，任何时刻跑测试都不会「已经过了」。
            // 原来写明天，凌晨跑没事，白天跑时「今天 09:00」已过 → 提前 1 天那条被过滤，
            // 从 3 个变 2 个（这个测试对当前时间敏感，2026-11-17 白天暴露）。
            date = LocalDate.now().plusDays(3).toString(),
            startMinute = 9 * 60,
            endMinute = 10 * 60,
            remindLeads = listOf(24 * 60, 30, 0),
        )
        AgendaReminder.scheduleAll(ctx, semester, listOf(multi))

        // 三档全部落在未来 → 三条都该排上
        assertEquals("三个档位三个闹钟", 3, alarms().size)
        assertEquals("三条都记进 code 表", 3, AgendaReminder.loadCodes(ctx).size)
    }

    @Test
    fun `没有校历时不会排出错误的闹钟`() {
        AgendaReminder.scheduleAll(ctx, null, listOf(rule()))

        assertTrue("算不出周次就不排，宁可不响也不要在错误的时刻响", alarms().isEmpty())
    }

    @Test
    fun `通知能发出来并且带标题`() {
        shadowOf(ctx as android.app.Application)
            .grantPermissions(android.Manifest.permission.POST_NOTIFICATIONS)

        AgendaReminder.notify(
            ctx,
            title = "复习内科学",
            place = "图书馆",
            note = "第三章",
            startMinute = 9 * 60,
            lead = 10,
            notifyId = 40_001,
        )

        val nm = ctx.getSystemService(NotificationManager::class.java)
        val posted = shadowOf(nm).allNotifications
        assertEquals("应该发出 1 条通知", 1, posted.size)
        assertEquals(
            "标题是「时间 + 名字」",
            "09:00 复习内科学",
            posted[0].extras.getCharSequence("android.title")?.toString(),
        )
        assertTrue(
            "正文要有地点和提前量",
            posted[0].extras.getCharSequence("android.text")?.toString()?.contains("图书馆") == true,
        )
    }
}
