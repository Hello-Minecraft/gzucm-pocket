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
        return start - leadFor(session, daySessions, date, leadFree, leadBreak) * 60_000L
    }

    /**
     * 这节该用哪一档（供通知文案显示「N 分钟后上课」）。
     *
     * [remindAt] 也走这里 —— 原来两处各写了一份同样的判定，改一处忘一处就会出现
     * 「按 10 分钟排的闹钟、通知却写 20 分钟后上课」。
     */
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

    /**
     * @param code 闹钟的 requestCode。传了就直接当通知 ID 用（日期+节次，天然唯一）；
     *   不传（-1）退回按课名算的旧逻辑，兼容旧版本排下、升级后才触发的闹钟。
     */
    fun notify(ctx: Context, session: CourseSession, lead: Int, code: Int = -1) {
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

        // 点通知打开 App（原来没设，点了只是消失）
        runCatching {
            ctx.packageManager.getLaunchIntentForPackage(ctx.packageName)?.let { launch ->
                builder.setContentIntent(
                    PendingIntent.getActivity(
                        ctx, 2, launch,
                        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                    ),
                )
            }
        }

        /*
         * 通知 ID。原来是 `BASE + hash % 9000`：hashCode 可能是负数，取余也是负的，
         * 而且 Receiver 里 weekday 固定写 0，不同天同名同时刻的课会算出同一个 ID。
         */
        val id = if (code >= 0) {
            NOTIFY_ID_BASE + Math.floorMod(code, 1_000_000)
        } else {
            NOTIFY_ID_BASE + Math.floorMod((session.name + session.startTime).hashCode(), 9000)
        }
        runCatching { NotificationManagerCompat.from(ctx).notify(id, builder.build()) }
            .onFailure { android.util.Log.w("ClassReminder", "发通知失败", it) }
    }

    // ── 闹钟调度 ─────────────────────────────────────────────────────

    /**
     * 往后排几天。
     *
     * 原来只排 3 天，而且只在「打开 App / 开机 / 同步」时排 —— 用户 3 天没打开，
     * 或者 App 被系统强停（ColorOS 后台清理会把包打成 stopped，闹钟全部作废），
     * 提醒就断了。2026-11-06 在 OPPO 上实测：stopped=true，待触发提醒 0 条，
     * 最后一次排闹钟在 7 天前。
     *
     * 现在 7 天 + 每次触发后续排 + 每日兜底任务（[ReminderRefreshWorker]），三层叠着保。
     * 一周课再多也就三四十条，离系统 500 条的上限很远。
     */
    const val SCHEDULE_DAYS = 7

    /** 已排闹钟的 requestCode 列表存在这里，取消时逐个对上，不靠重算（见 [cancelScheduled]） */
    private const val PREFS = "class_reminder"
    private const val KEY_CODES = "scheduled_codes"

    /**
     * 稳定的 requestCode：日期 + 开始节次。
     *
     * 原来是 `weekday*1000 + startUnit*10 + dayOfMonth`，startUnit*10+day 会越过 10 的进位，
     * 比如「周一第 1 节 11 号」和「周一第 2 节 1 号」都是 1021。3 天窗口内恰好没撞上，
     * 但窗口拉到 7 天以后就不能再赌。改成 epochDay*100 + startUnit：
     * 一天最多 14 节，两位数够用，不同日期之间绝不重叠。
     */
    internal fun requestCode(date: LocalDate, startUnit: Int): Int =
        ((date.toEpochDay() % 200_000L) * 100L + startUnit.coerceIn(0, 99)).toInt()

    private fun intentFor(ctx: Context): Intent = Intent(ctx, ClassReminderReceiver::class.java)

    private fun pi(
        ctx: Context,
        code: Int,
        session: CourseSession,
        lead: Int,
        whenAt: Long,
    ): PendingIntent {
        val intent = intentFor(ctx).apply {
            putExtra("name", session.name)
            putExtra("room", session.room)
            putExtra("teachers", session.teachers)
            putExtra("content", session.content)
            putExtra("startTime", session.startTime)
            putExtra("startUnit", session.startUnit)
            putExtra("endUnit", session.endUnit)
            putExtra("lead", lead)
            putExtra("code", code)
            putExtra("whenAt", whenAt)
        }
        return PendingIntent.getBroadcast(
            ctx, code, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    /** 点通知 / 点状态栏闹钟图标时打开 App */
    private fun showIntent(ctx: Context): PendingIntent? {
        val launch = ctx.packageManager.getLaunchIntentForPackage(ctx.packageName) ?: return null
        return PendingIntent.getActivity(
            ctx, 1, launch,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    /** 这次排闹钟用了哪种方式（给设置页显示、给日志看） */
    enum class Mode { ALARM_CLOCK, EXACT, INEXACT }

    /**
     * 能不能排精确闹钟。
     *
     * Android 12+ 需要用户在「闹钟和提醒」里授权；没授权时只能退回非精确，
     * Doze 下可能被推迟到下一个维护窗口 —— 这就是「上午的课下午才提醒」的一种来源。
     */
    fun canExact(ctx: Context): Boolean {
        if (Build.VERSION.SDK_INT < 31) return true
        val am = ctx.getSystemService(AlarmManager::class.java) ?: return false
        return runCatching { am.canScheduleExactAlarms() }.getOrDefault(false)
    }

    /**
     * 实际排一条。
     *
     * 增强模式用 `setAlarmClock`：这是系统闹钟同级的 API，Doze 和国产 ROM 的省电合并
     * 基本都不敢推迟它（代价是状态栏会出现闹钟图标）。普通模式保持原来的
     * `setExactAndAllowWhileIdle`，它在原生系统上准，但 ColorOS / HyperOS 会合并推迟。
     */
    private fun place(
        am: AlarmManager,
        ctx: Context,
        whenAt: Long,
        p: PendingIntent,
        enhanced: Boolean,
    ): Mode {
        if (!canExact(ctx)) {
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, whenAt, p)
            return Mode.INEXACT
        }
        if (enhanced) {
            am.setAlarmClock(AlarmManager.AlarmClockInfo(whenAt, showIntent(ctx)), p)
            return Mode.ALARM_CLOCK
        }
        am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, whenAt, p)
        return Mode.EXACT
    }

    /** 一条待排的提醒（纯数据，方便单测） */
    internal data class Plan(
        val code: Int,
        val session: CourseSession,
        val lead: Int,
        val whenAt: Long,
    )

    /**
     * 算出未来 [days] 天里所有要排的提醒。纯函数，不碰系统服务。
     *
     * 抽出来是因为调度出错的表现（「漏了一节」「排到别的周」）只有拆开才测得到。
     */
    internal fun plan(
        semester: Semester,
        sessions: List<CourseSession>,
        today: LocalDate,
        now: Long,
        leadFree: Int = DEFAULT_LEAD_FREE,
        leadBreak: Int = DEFAULT_LEAD_BREAK,
        days: Int = SCHEDULE_DAYS,
    ): List<Plan> {
        val out = ArrayList<Plan>()
        val used = HashSet<Int>()
        for (offset in 0 until days) {
            val date = today.plusDays(offset.toLong())
            val week = semester.weekOf(date)
            if (week < 1 || week > semester.totalWeeks) continue
            val weekday = date.dayOfWeek.value
            val daySessions = sessions.filter { it.week == week && it.weekday == weekday }
            if (daySessions.isEmpty()) continue

            for (s in daySessions) {
                val whenAt = remindAt(s, daySessions, date, leadFree, leadBreak) ?: continue
                if (whenAt <= now + 5_000) continue
                val code = requestCode(date, s.startUnit)
                // 同一天同一节有两门课（重叠课表）时只排一条，避免后一条覆盖前一条的闹钟后
                // 又在取消列表里留两个相同的 code
                if (!used.add(code)) continue
                out += Plan(code, s, leadFor(s, daySessions, date, leadFree, leadBreak), whenAt)
            }
        }
        return out
    }

    /**
     * 排未来 [SCHEDULE_DAYS] 天（含今天）的提醒。
     *
     * 只用 AlarmManager 而不是 WorkManager：WorkManager 最小周期 15 分钟，
     * 且不保证准点，做不到「课前 10 分钟」。WorkManager 只负责每天续命一次。
     */
    fun scheduleAll(
        ctx: Context,
        semester: Semester,
        sessions: List<CourseSession>,
        enabled: Boolean,
        leadFree: Int = DEFAULT_LEAD_FREE,
        leadBreak: Int = DEFAULT_LEAD_BREAK,
        enhanced: Boolean = SessionStore(ctx).classReminderEnhanced,
    ) {
        val am = ctx.getSystemService(AlarmManager::class.java) ?: return
        cancelScheduled(ctx)
        if (!enabled || sessions.isEmpty()) {
            ReminderRefreshWorker.cancel(ctx)
            return
        }

        ensureChannel(ctx)
        val plans = plan(
            semester, sessions, LocalDate.now(), System.currentTimeMillis(),
            leadFree, leadBreak,
        )
        val placed = ArrayList<Int>(plans.size)
        var mode: Mode? = null
        for (p in plans) {
            val r = runCatching { place(am, ctx, p.whenAt, pi(ctx, p.code, p.session, p.lead, p.whenAt), enhanced) }
            r.onSuccess {
                mode = it
                placed += p.code
            }.onFailure {
                // 以前这里整个吞掉，排失败了用户和开发者都不知道
                android.util.Log.w("ClassReminder", "排第 ${p.code} 条失败", it)
            }
        }
        saveCodes(ctx, placed)
        ReminderRefreshWorker.ensure(ctx)
        android.util.Log.i("ClassReminder", "已排 ${placed.size}/${plans.size} 条提醒，方式 $mode")
    }

    /**
     * 只读缓存重排一次。开机、改时间、闹钟触发后、每日兜底都走这里。
     *
     * @return 是否真的去排了（缓存不全或开关关着时返回 false）
     */
    fun rescheduleFromCache(ctx: Context): Boolean {
        val app = ctx.applicationContext
        val store = SessionStore(app)
        if (!store.classReminderEnabled) {
            cancelScheduled(app)
            return false
        }
        val semJson = store.cachedSemester ?: return false
        val sesJson = store.cachedSessions ?: return false
        val semester = runCatching { Net.json.decodeFromString(Semester.serializer(), semJson) }
            .getOrNull() ?: return false
        val sessions = runCatching { Net.json.decodeFromString<List<CourseSession>>(sesJson) }
            .getOrNull() ?: return false
        scheduleAll(
            app, semester, sessions, true,
            store.classLeadFree, store.classLeadBreak, store.classReminderEnhanced,
        )
        return true
    }

    /**
     * 取消上一轮排下的全部闹钟。
     *
     * 按存下来的 code 逐个取消，而不是「按今天重算一遍再取消」——
     * 原来的做法跨天就对不上：昨天排的「后天那条」，今天重算时 offset 变了，就漏取消了。
     * PendingIntent 匹配只看 requestCode + Intent 的组件，extras 不参与，所以空 Intent 就能命中。
     */
    fun cancelScheduled(ctx: Context) {
        val am = ctx.getSystemService(AlarmManager::class.java) ?: return
        for (code in loadCodes(ctx)) {
            val p = PendingIntent.getBroadcast(
                ctx, code, intentFor(ctx),
                PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE,
            ) ?: continue
            runCatching {
                am.cancel(p)
                p.cancel()
            }
        }
        saveCodes(ctx, emptyList())
    }

    private fun prefs(ctx: Context) =
        ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun loadCodes(ctx: Context): List<Int> =
        prefs(ctx).getString(KEY_CODES, null)
            ?.split(',')
            ?.mapNotNull { it.trim().toIntOrNull() }
            .orEmpty()

    private fun saveCodes(ctx: Context, codes: List<Int>) {
        prefs(ctx).edit().putString(KEY_CODES, codes.joinToString(",")).apply()
    }

    /** 触发一条之后把它从记录里拿掉 */
    internal fun forgetCode(ctx: Context, code: Int) {
        saveCodes(ctx, loadCodes(ctx).filter { it != code })
    }
}

/**
 * 每天续一次命。
 *
 * 它**不负责准点**（WorkManager 本来就不准），只负责「哪怕用户一周不打开 App、
 * 中间也没有任何一条提醒触发，闹钟队列也不会排空」。
 *
 * 用 KEEP：已经排着就不动，不会每次打开 App 都把周期重置。
 * 不加电量约束：电量低的时候恰恰最容易被系统杀，续命更不能停。
 */
class ReminderRefreshWorker(
    ctx: Context,
    params: androidx.work.WorkerParameters,
) : androidx.work.CoroutineWorker(ctx, params) {

    override suspend fun doWork(): Result {
        runCatching { ClassReminder.rescheduleFromCache(applicationContext) }
            .onFailure { android.util.Log.w("ClassReminder", "每日续排失败", it) }
        return Result.success()
    }

    companion object {
        private const val WORK_NAME = "class_reminder_refresh"

        fun ensure(ctx: Context) {
            runCatching {
                val req = androidx.work.PeriodicWorkRequestBuilder<ReminderRefreshWorker>(
                    12, java.util.concurrent.TimeUnit.HOURS,
                ).build()
                androidx.work.WorkManager.getInstance(ctx).enqueueUniquePeriodicWork(
                    WORK_NAME, androidx.work.ExistingPeriodicWorkPolicy.KEEP, req,
                )
            }.onFailure { android.util.Log.w("ClassReminder", "排每日续命失败", it) }
        }

        fun cancel(ctx: Context) {
            runCatching { androidx.work.WorkManager.getInstance(ctx).cancelUniqueWork(WORK_NAME) }
        }
    }
}

/**
 * 到点发通知，然后**续排**。
 *
 * 续排是这次修复的关键之一：原来链条是一次性的，排下的那几条触发完就没了，
 * 只有用户再打开 App 才会补。现在每次触发都顺手把未来 7 天重排一遍。
 */
class ClassReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val app = context.applicationContext
        val code = intent.getIntExtra("code", -1)
        val whenAt = intent.getLongExtra("whenAt", 0L)
        val name = intent.getStringExtra("name")

        /*
         * 过时太久的不再发。
         *
         * 被省电策略压了几个小时才放出来的提醒（「上午的课下午才响」），
         * 弹出来只会误导 —— 课都上完了。超过开课时间就丢掉，只续排。
         */
        val lead = intent.getIntExtra("lead", ClassReminder.DEFAULT_LEAD_FREE)
        val late = whenAt > 0 && System.currentTimeMillis() - whenAt > lead * 60_000L

        if (name != null && !late) {
            val session = CourseSession(
                name = name,
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
            ClassReminder.notify(app, session, lead, code)
        } else if (late) {
            android.util.Log.w(
                "ClassReminder",
                "提醒迟到 ${(System.currentTimeMillis() - whenAt) / 60_000} 分钟，已丢弃：$name",
            )
        }

        if (code >= 0) ClassReminder.forgetCode(app, code)

        // 续排放到后台线程：要读 SharedPreferences 和解析课表 JSON，别占广播的主线程
        val pending = goAsync()
        Thread {
            try {
                runCatching { ClassReminder.rescheduleFromCache(app) }
                    .onFailure { android.util.Log.w("ClassReminder", "续排失败", it) }
            } finally {
                pending.finish()
            }
        }.start()
    }
}
