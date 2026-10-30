package com.gzucm.pocket.core

import android.Manifest
import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

/**
 * 上课提醒。
 *
 * 提前量不是固定的：**课前 20 分钟**通常够穿衣走路，但如果那个时刻你正在上一节课
 * （比如上午 1-2 节 09:55 下课、3-4 节 10:10 上课），20 分钟前正好在上课中间，
 * 提醒没意义。所以这种情况下改成课前 **10 分钟**（课间）。
 *
 * 判定方式：看「T-20 分钟」这个瞬间是否落在别的课的 [开始, 结束) 区间里。
 */
object ClassReminder {

    const val CHANNEL_ID = "class_remind"
    private const val NOTIFY_ID_BASE = 20_000

    /** 默认：当前没课（早八、午二那种），提前 20 分钟 */
    const val DEFAULT_LEAD_FREE = 20
    /** 默认：当前还在上课（课间），提前 10 分钟 */
    const val DEFAULT_LEAD_BREAK = 10

    /**
     * 判断「T 时刻是否在别的课上」时，往前多看 1 分钟。
     *
     * 大课间有 20 分钟（10:00 下课、10:20 上课）。若按 T-20 整点判断，
     * 10:00 正好是下课时刻，会被判成「没课」，于是走 20 分钟档 ——
     * 结果刚下课就提醒，等于没给课间留时间。往前挪到 T-21 就落回上课区间，
     * 正确走「课间档」。
     */
    private const val PROBE_OFFSET_MIN = 21

    // ── 时间计算 ─────────────────────────────────────────────────────

    /** "08:00" → 当天该时刻的毫秒时间戳；解析失败返回 null */
    fun at(date: LocalDate, hhmm: String): Long? {
        val t = runCatching { LocalTime.parse(hhmm.trim()) }.getOrNull() ?: return null
        return date.atTime(t).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
    }

    /**
     * 算这节课的提醒时刻。
     *
     * @param daySessions 同一天的全部课程（用来判断 T-20 时是否还在上课）
     */
    fun remindAt(
        session: CourseSession,
        daySessions: List<CourseSession>,
        date: LocalDate,
        leadFree: Int = DEFAULT_LEAD_FREE,
        leadBreak: Int = DEFAULT_LEAD_BREAK,
    ): Long? {
        val start = at(date, session.startTime) ?: return null
        val probe = start - PROBE_OFFSET_MIN * 60_000L

        val stillInClass = daySessions.any { other ->
            if (other === session) return@any false
            val s = at(date, other.startTime) ?: return@any false
            val e = at(date, other.endTime) ?: return@any false
            probe >= s && probe < e
        }

        val lead = if (stillInClass) leadBreak else leadFree
        return start - lead * 60_000L
    }

    /** 这节该用哪一档（供通知文案显示「N 分钟后上课」） */
    fun leadFor(
        session: CourseSession,
        daySessions: List<CourseSession>,
        date: LocalDate,
        leadFree: Int = DEFAULT_LEAD_FREE,
        leadBreak: Int = DEFAULT_LEAD_BREAK,
    ): Int {
        val start = at(date, session.startTime) ?: return leadFree
        val probe = start - PROBE_OFFSET_MIN * 60_000L
        val stillInClass = daySessions.any { other ->
            if (other === session) return@any false
            val s = at(date, other.startTime) ?: return@any false
            val e = at(date, other.endTime) ?: return@any false
            probe >= s && probe < e
        }
        return if (stillInClass) leadBreak else leadFree
    }

    /** 副标题：地点·教师，空的自动略过 */
    fun subtitle(session: CourseSession): String =
        listOf(session.room.trim(), session.teachers.trim())
            .filter { it.isNotEmpty() }
            .joinToString("·")

    // ── 通知 ─────────────────────────────────────────────────────────

    fun ensureChannel(ctx: Context) {
        val mgr = ctx.getSystemService(NotificationManager::class.java) ?: return
        if (mgr.getNotificationChannel(CHANNEL_ID) != null) return
        mgr.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                "上课提醒",
                NotificationManager.IMPORTANCE_HIGH,
            ).apply {
                description = "课前提醒今天要上的课"
                enableVibration(true)
            },
        )
    }

    /**
     * 小米焦点通知 / 超级岛参数。
     *
     * 只有澎湃 OS3 以上才认这个字段；没权限的第三方应用发出去会被静默忽略，
     * 降级成普通通知，不会报错。所以这里只做「尽力而为」，不做权限判断。
     */
    private fun focusParam(session: CourseSession, lead: Int): String {
        fun esc(s: String) = s.replace("\\", "\\\\").replace("\"", "\\\"")
        val title = esc(session.name)
        val sub = esc(subtitle(session))
        // 结构照抄这台机器上系统应用（tsmclient）实际在用的 schema：
        // islandProperty / islandTimeout / imageTextInfoLeft|Right 才是 OS3 认的字段，
        // 文档里那套 title/content 在这版上不上岛。
        return """
        {"isShowNotification":true,"islandFirstFloat":true,"business":"course",
         "param_island":{
           "islandProperty":0,
           "islandTimeout":6,
           "expandedTime":3,
           "islandPriority":1,
           "bigIslandArea":{
             "imageTextInfoLeft":{"type":1,"picInfo":{"type":4,"pic":"miui.focus.pic_start"}},
             "imageTextInfoRight":{"type":2,"textInfo":{"title":"$title","content":"$sub"}}
           },
           "smallIslandArea":{
             "imageTextInfoLeft":{"type":1,"picInfo":{"type":4,"pic":"miui.focus.pic_start"}},
             "imageTextInfoRight":{"type":2,"textInfo":{"title":"$title"}}
           }
         },
         "aodTitle":"$title",
         "ticker":"$lead 分钟后 · $title"}
        """.trimIndent()
    }

    /** 是否是澎湃 OS3+（超级岛从这里开始） */
    private fun isHyperOs3(): Boolean {
        val v = runCatching {
            val c = Class.forName("android.os.SystemProperties")
            val m = c.getMethod("get", String::class.java)
            m.invoke(null, "ro.mi.os.version.name") as? String
        }.getOrNull() ?: return false
        return v.startsWith("OS3") || v.startsWith("OS4")
    }

    fun notify(ctx: Context, session: CourseSession, lead: Int) {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) return

        ensureChannel(ctx)

        val sub = subtitle(session)
        /**
         * 折叠态（通知栏默认样式）在 MIUI/HyperOS 上只显示「标题 + 一行正文」，
         * SubText 会被吞掉。所以把**地点·教师**放进正文，保证一瞥就能看到在哪上课；
         * 授课内容放 BigText，展开后才显示。
         */
        val builder = NotificationCompat.Builder(ctx, CHANNEL_ID)
            .setSmallIcon(com.gzucm.pocket.R.drawable.ic_notification)
            // 标题带上上课时间，一瞥就知道几点在哪
            .setContentTitle(
                listOf(session.startTime.trim(), session.name)
                    .filter { it.isNotBlank() }
                    .joinToString(" "),
            )
            .setContentText(sub)
            .setSubText("${lead} 分钟后上课")
            .setStyle(
                NotificationCompat.BigTextStyle()
                    .bigText(
                        listOf(sub, session.content)
                            .filter { it.isNotBlank() }
                            .joinToString("\n"),
                    ),
            )
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setAutoCancel(true)

        if (isHyperOs3()) {
            // 官方文档写的是 miui.focus.param；但这台 OS3 上系统应用（tsmclient）
            // 实际用的是 miui.focus.param.custom，两个都塞，谁认谁生效。
            val json = focusParam(session, lead)
            runCatching {
                builder.extras.putString("miui.focus.param", json)
                builder.extras.putString("miui.focus.param.custom", json)
            }
        }

        val id = NOTIFY_ID_BASE + (session.name + session.startTime + session.weekday).hashCode() % 9000
        runCatching { NotificationManagerCompat.from(ctx).notify(id, builder.build()) }
    }

    // ── 闹钟调度 ─────────────────────────────────────────────────────

    private fun pi(ctx: Context, session: CourseSession, date: LocalDate, lead: Int): PendingIntent {
        val intent = Intent(ctx, ClassReminderReceiver::class.java).apply {
            putExtra("name", session.name)
            putExtra("room", session.room)
            putExtra("teachers", session.teachers)
            putExtra("content", session.content)
            putExtra("startTime", session.startTime)
            putExtra("startUnit", session.startUnit)
            putExtra("endUnit", session.endUnit)
            putExtra("lead", lead)
        }
        val code = (session.weekday * 1000 + session.startUnit * 10 + date.dayOfMonth)
        return PendingIntent.getBroadcast(
            ctx, code, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    /**
     * 排未来几天（含今天）的提醒。
     *
     * 只用 AlarmManager 而不是 WorkManager：WorkManager 最小周期 15 分钟，
     * 且不保证准点，做不到「课前 10 分钟」。
     */
    fun scheduleAll(
        ctx: Context,
        semester: Semester,
        sessions: List<CourseSession>,
        enabled: Boolean,
        leadFree: Int = DEFAULT_LEAD_FREE,
        leadBreak: Int = DEFAULT_LEAD_BREAK,
    ) {
        val am = ctx.getSystemService(AlarmManager::class.java) ?: return
        cancelAll(ctx, sessions, semester)
        if (!enabled || sessions.isEmpty()) return

        ensureChannel(ctx)
        val today = LocalDate.now()
        val now = System.currentTimeMillis()
        var scheduled = 0

        for (offset in 0..2) {
            val date = today.plusDays(offset.toLong())
            val week = semester.weekOf(date)
            if (week < 1 || week > semester.totalWeeks) continue
            val weekday = date.dayOfWeek.value
            val daySessions = sessions.filter { it.week == week && it.weekday == weekday }
            if (daySessions.isEmpty()) continue

            for (s in daySessions) {
                val whenAt = remindAt(s, daySessions, date, leadFree, leadBreak) ?: continue
                if (whenAt <= now + 5_000) continue
                val lead = leadFor(s, daySessions, date, leadFree, leadBreak)
                val p = pi(ctx, s, date, lead)
                runCatching {
                    if (Build.VERSION.SDK_INT >= 31 && !am.canScheduleExactAlarms()) {
                        am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, whenAt, p)
                    } else {
                        am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, whenAt, p)
                    }
                }
                scheduled++
            }
        }
        android.util.Log.i("ClassReminder", "已排 $scheduled 条提醒")
    }

    fun cancelAll(ctx: Context, sessions: List<CourseSession>, semester: Semester) {
        val am = ctx.getSystemService(AlarmManager::class.java) ?: return
        val today = LocalDate.now()
        for (offset in 0..2) {
            val date = today.plusDays(offset.toLong())
            // PendingIntent 的匹配只看 requestCode，extras 不参与，所以这里 lead 传什么都命中
            sessions.forEach { runCatching { am.cancel(pi(ctx, it, date, 0)) } }
        }
    }
}

/** 到点发通知 */
class ClassReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val session = CourseSession(
            name = intent.getStringExtra("name") ?: return,
            code = "",
            teachers = intent.getStringExtra("teachers") ?: "",
            week = 0,
            weekday = 0,
            startUnit = intent.getIntExtra("startUnit", 1),
            endUnit = intent.getIntExtra("endUnit", 1),
            startTime = intent.getStringExtra("startTime") ?: "",
            endTime = "",
            room = intent.getStringExtra("room") ?: "",
            category = "",
            content = intent.getStringExtra("content") ?: "",
        )
        ClassReminder.notify(
            context, session,
            intent.getIntExtra("lead", ClassReminder.DEFAULT_LEAD_FREE),
        )
    }
}
