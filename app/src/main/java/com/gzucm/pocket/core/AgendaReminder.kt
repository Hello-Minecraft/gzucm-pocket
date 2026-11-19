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
import java.time.ZoneId

/**
 * 自建日程的提醒。
 *
 * **刻意不碰 [ClassReminder]**（docs/PITFALLS.md 里写过原因）：
 * 课程提醒的 requestCode 拿「节次」当核心参数、plan 里有「同一天同一节只排一条」的
 * 去重集合、续命 Worker 的启停还挂在课程开关上 —— 日程一天可以有好几条、也没有真节次，
 * 硬塞进去会把课程的闹钟挤掉。
 *
 * 隔离靠三样东西（任何一样单独都能防冲突，三样叠着）：
 *   1. PendingIntent 的目标组件不同（本文件里的 Receiver vs ClassReminderReceiver）
 *   2. requestCode 段不同（日程约 2000 万级，课程约 207 万级）
 *   3. 存 code 的 SharedPreferences 文件不同
 *
 * 排程骨架（7 天窗口 / 触发后续排 / 12h 兜底 / 开机重排）是照抄课程那套的 ——
 * 那套是踩出来的，不是设计出来的。
 */
object AgendaReminder {

    const val CHANNEL_ID = "agenda_remind"
    private const val NOTIFY_ID_BASE = 40_000

    /** 往后排几天。和课程提醒一致 */
    const val SCHEDULE_DAYS = 7

    /**
     * 迟到多久就不发了。
     *
     * 比课程宽松（课程是「超过提前量就丢」）：日程的提前量常常是 0（准时），
     * 按课程那套会把 5 分钟后才放出来的提醒也丢掉，等于白排。
     */
    private const val LATE_TOLERANCE_MS = 15 * 60_000L

    /** 已排闹钟的 requestCode 列表（取消时逐个对上，不靠重算） */
    private const val PREFS = "agenda_reminder"
    private const val KEY_CODES = "scheduled_codes"

    /**
     * 可选的提前量（分钟）。-1 = 不提醒，0 = 准时。
     *
     * 顺序就是表单里胶囊的顺序，加档位改这里一处即可。
     * 多选时 -1 只在「一个都没选」时显示为选中。
     */
    val LEADS = intArrayOf(-1, 0, 5, 10, 15, 30, 60, 24 * 60)

    /** 单个档位的文案（表单胶囊、编辑页标题都用它） */
    fun label(minutes: Int): String = when {
        minutes < 0 -> "不提醒"
        minutes == 0 -> "准时"
        minutes == 60 -> "提前 1 小时"
        minutes == 24 * 60 -> "提前 1 天"
        else -> "提前 $minutes 分钟"
    }

    /** 通知里显示的短文案（不带「提前」前缀） */
    private fun shortLabel(minutes: Int): String = when {
        minutes == 0 -> "准时"
        minutes == 60 -> "1 小时"
        minutes == 24 * 60 -> "1 天"
        else -> "$minutes 分钟"
    }

    /**
     * 多选时表单那一行显示的摘要。
     *
     * 用短文案拼（「1 天 · 30 分钟 · 准时」），不加「提前」前缀 ——
     * 6 个字的「提前」乘三四份，窄屏必然被省略号吃掉，反而看不全有哪几档。
     */
    fun summary(leads: List<Int>): String =
        if (leads.isEmpty()) "不提醒"
        else leads.filter { it >= 0 }.sorted().joinToString(" · ") { shortLabel(it) }

    // ── 时间与编号 ───────────────────────────────────────────────────

    internal fun epochAt(date: LocalDate, minuteOfDay: Int): Long =
        date.atStartOfDay()
            .plusMinutes(minuteOfDay.coerceIn(0, 24 * 60 - 1).toLong())
            .atZone(ZoneId.systemDefault())
            .toInstant()
            .toEpochMilli()

    /**
     * 稳定的 requestCode：日期 + 当天序号。
     *
     * `(epochDay%100000)*1000` 量级在 2000 万，课程那边最大约 207 万（`%200000*100`），
     * 两段不重叠；再加上 Receiver 组件不同，物理上不可能互相取消。
     * 一天 1000 条日程是绝对够用的上限。
     */
    internal fun requestCode(date: LocalDate, index: Int): Int =
        ((date.toEpochDay() % 100_000L) * 1000L + index.coerceIn(0, 999)).toInt()

    // ── 排程计划（纯函数，方便单测） ─────────────────────────────────

    internal data class Planned(
        val code: Int,
        val ruleId: String,
        val title: String,
        val place: String,
        val note: String,
        val startMinute: Int,
        val lead: Int,
        val whenAt: Long,
        /**
         * 通知 ID：**同一条日程的多个档位共用一个**。
         *
         * 这样「提前 1 天」响过没看，「提前 30 分钟」再响时是覆盖前一条，
         * 而不是在通知栏里堆一串同名提醒。闹钟还是各响各的（code 不同）。
         */
        val notifyId: Int,
    )

    /** 同一条日程（规则 + 日期）算出来的通知 ID 固定，跨档位共用 */
    private fun notifyIdOf(date: LocalDate, ruleId: String): Int =
        NOTIFY_ID_BASE + Math.floorMod((ruleId + "|" + date).hashCode(), 1_000_000)

    /**
     * 从广播里取通知 ID。
     *
     * 上一版排下的闹钟里没有 `notifyId` 这个 extra（装机后还没触发的那种），
     * 取不到就退回按 code 算一个 —— 保证总能发出通知，只是那条会占独立一格。
     */
    internal fun notifyIdFromIntent(intent: Intent, code: Int): Int {
        val id = intent.getIntExtra("notifyId", -1)
        return if (id > 0) id else NOTIFY_ID_BASE + Math.floorMod(code, 1_000_000)
    }

    /**
     * 算出未来 [days] 天里所有要排的提醒。
     *
     * 展开复用 [AgendaLogic.expand] —— 每周重复、学期范围、锚点顺延全都不用重写。
     * 没有校历时返回空：日程照常能看，但提醒依赖「第几周的第几天」，
     * 硬算只会算出错误的日期（和「课表上不画日程」是同一个口径）。
     */
    internal fun plan(
        rules: List<Agenda>,
        semester: Semester?,
        today: LocalDate,
        now: Long,
        days: Int = SCHEDULE_DAYS,
    ): List<Planned> {
        if (semester == null) return emptyList()
        // 截止任务 / 待办不排闹钟：它没有上课/日程那种「到点响」的语义
        val active = rules.filter { !it.isTask && it.remindLeads.isNotEmpty() }
        if (active.isEmpty()) return emptyList()

        val byId = active.associateBy { it.id }
        val lastDate = today.plusDays((days - 1).toLong())
        val out = ArrayList<Planned>()

        AgendaLogic.expand(semester, active)
            .filter { !it.date.isBefore(today) && !it.date.isAfter(lastDate) }
            .groupBy { it.date }
            .toSortedMap()
            .forEach { (date, dayItems) ->
                /*
                 * 当天序号就是 code 的后三位。**每个 (日程, 档位) 占一个序号** ——
                 * 一条日程选了 3 个档位就是 3 个 code，否则后一条会覆盖前一条的闹钟。
                 * 排序按「开始时间 → 标题 → key」，档位内部按 0/5/10… 由近到远，同一份数据每次算出来一致。
                 */
                var index = 0
                dayItems
                    .sortedWith(compareBy({ it.startMinute }, { it.title }, { it.key }))
                    .forEach { o ->
                        val rule = byId[o.sourceId] ?: return@forEach
                        val leads = rule.remindLeads
                            .filter { it >= 0 }
                            .distinct()
                            .sorted()
                        for (lead in leads) {
                            val whenAt = epochAt(date, o.startMinute) - lead * 60_000L
                            val code = requestCode(date, index++)
                            // 已经过了的（或 5 秒内就到的）不排，避免刚存下就弹一条
                            if (whenAt <= now + 5_000) continue
                            out += Planned(
                                code = code,
                                ruleId = rule.id,
                                title = rule.title,
                                place = rule.place,
                                note = rule.note,
                                startMinute = o.startMinute,
                                lead = lead,
                                whenAt = whenAt,
                                notifyId = notifyIdOf(date, rule.id),
                            )
                        }
                    }
            }
        return out
    }

    // ── 通知 ─────────────────────────────────────────────────────────

    fun ensureChannel(ctx: Context) {
        val mgr = ctx.getSystemService(NotificationManager::class.java) ?: return
        if (mgr.getNotificationChannel(CHANNEL_ID) != null) return
        mgr.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                "日程提醒",
                NotificationManager.IMPORTANCE_HIGH,
            ).apply {
                description = "自己加的日程到点提醒"
                enableVibration(true)
            },
        )
    }

    fun notify(
        ctx: Context,
        title: String,
        place: String,
        note: String,
        startMinute: Int,
        lead: Int,
        notifyId: Int,
    ) {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) return

        ensureChannel(ctx)

        /*
         * 折叠态只有「标题 + 一行正文」：标题给时间和名字，正文给地点和提前量。
         * 提前量为 0 时写「现在开始」，不写「提前 0 分钟」。
         */
        val head = listOf(AgendaLogic.hhmm(startMinute), title.trim())
            .filter { it.isNotBlank() }
            .joinToString(" ")
        val leadText = if (lead > 0) "提前 ${shortLabel(lead)}" else "现在开始"
        val sub = listOf(place.trim(), leadText)
            .filter { it.isNotBlank() }
            .joinToString(" · ")

        val builder = NotificationCompat.Builder(ctx, CHANNEL_ID)
            .setSmallIcon(com.gzucm.pocket.R.drawable.ic_notification)
            .setContentTitle(head)
            .setContentText(sub)
            .setStyle(
                NotificationCompat.BigTextStyle()
                    .bigText(listOf(sub, note.trim()).filter { it.isNotBlank() }.joinToString("\n")),
            )
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setAutoCancel(true)

        // 点通知打开 App（一期只做到这里；直达那条日程要改 Activity，见方案 §8）
        runCatching {
            ctx.packageManager.getLaunchIntentForPackage(ctx.packageName)?.let { launch ->
                builder.setContentIntent(
                    PendingIntent.getActivity(
                        ctx, 42, launch,
                        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                    ),
                )
            }
        }

        runCatching { NotificationManagerCompat.from(ctx).notify(notifyId, builder.build()) }
            .onFailure { android.util.Log.w("AgendaReminder", "发通知失败", it) }
    }

    // ── 闹钟调度 ─────────────────────────────────────────────────────

    private fun intentFor(ctx: Context): Intent = Intent(ctx, AgendaReminderReceiver::class.java)

    private fun pi(ctx: Context, p: Planned): PendingIntent {
        val intent = intentFor(ctx).apply {
            putExtra("code", p.code)
            putExtra("title", p.title)
            putExtra("place", p.place)
            putExtra("note", p.note)
            putExtra("startMinute", p.startMinute)
            putExtra("lead", p.lead)
            putExtra("whenAt", p.whenAt)
            putExtra("notifyId", p.notifyId)
        }
        return PendingIntent.getBroadcast(
            ctx, p.code, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private fun showIntent(ctx: Context): PendingIntent? {
        val launch = ctx.packageManager.getLaunchIntentForPackage(ctx.packageName) ?: return null
        return PendingIntent.getActivity(
            ctx, 41, launch,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    /**
     * 实际排一条。三档的选择逻辑和课程提醒完全一致（[ClassReminder.place] 的复制），
     * 抽公共层的事留到二期 —— 课程提醒刚修稳，这一版不碰它。
     */
    private fun place(
        am: AlarmManager,
        ctx: Context,
        whenAt: Long,
        p: PendingIntent,
        enhanced: Boolean,
    ): ClassReminder.Mode {
        if (!ClassReminder.canExact(ctx)) {
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, whenAt, p)
            return ClassReminder.Mode.INEXACT
        }
        if (enhanced) {
            am.setAlarmClock(AlarmManager.AlarmClockInfo(whenAt, showIntent(ctx)), p)
            return ClassReminder.Mode.ALARM_CLOCK
        }
        am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, whenAt, p)
        return ClassReminder.Mode.EXACT
    }

    /** 全量重排。规则条数少，每次 cancel 干净再排，不做增量 */
    fun scheduleAll(
        ctx: Context,
        semester: Semester?,
        rules: List<Agenda>,
        enhanced: Boolean = SessionStore(ctx).classReminderEnhanced,
    ) {
        val am = ctx.getSystemService(AlarmManager::class.java) ?: return
        cancelScheduled(ctx)

        val plans = plan(rules, semester, LocalDate.now(), System.currentTimeMillis())
        if (plans.isEmpty()) {
            // 一条提醒都没设：别留着 12h 周期任务空跑
            AgendaReminderRefreshWorker.cancel(ctx)
            return
        }

        ensureChannel(ctx)
        val placed = ArrayList<Int>(plans.size)
        var mode: ClassReminder.Mode? = null
        for (p in plans) {
            runCatching { place(am, ctx, p.whenAt, pi(ctx, p), enhanced) }
                .onSuccess {
                    mode = it
                    placed += p.code
                }
                .onFailure { android.util.Log.w("AgendaReminder", "排第 ${p.code} 条失败", it) }
        }
        saveCodes(ctx, placed)
        AgendaReminderRefreshWorker.ensure(ctx)
        android.util.Log.i(
            "AgendaReminder",
            "已排 ${placed.size}/${plans.size} 条日程提醒，方式 $mode",
        )
    }

    /**
     * 只读缓存重排一次。开机、改时间、闹钟触发后、每日兜底、App 启动都走这里。
     *
     * @return 是否真的去排了（没有设提醒的日程时返回 false）
     */
    fun rescheduleFromCache(ctx: Context): Boolean {
        val app = ctx.applicationContext
        val rules = AgendaStore(app).load()
        if (rules.none { it.remindLeads.isNotEmpty() }) {
            cancelScheduled(app)
            AgendaReminderRefreshWorker.cancel(app)
            return false
        }
        val store = SessionStore(app)
        val semester = store.cachedSemester?.let {
            runCatching { Net.json.decodeFromString(Semester.serializer(), it) }.getOrNull()
        }
        scheduleAll(app, semester, rules, store.classReminderEnhanced)
        return true
    }

    /**
     * 取消上一轮排下的全部闹钟。
     *
     * 按存下来的 code 逐个取消，不按「今天重算一遍再取消」—— 跨天重算 offset 就变了，
     * 会漏取消（课程提醒踩过）。PendingIntent 匹配只看 requestCode + 组件，extras 不参与。
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

    internal fun loadCodes(ctx: Context): List<Int> =
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
 * 到点发通知，然后**续排**（和课程提醒同一个套路：链条不能断在最后一条）。
 */
class AgendaReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val app = context.applicationContext
        val code = intent.getIntExtra("code", -1)
        val whenAt = intent.getLongExtra("whenAt", 0L)
        val title = intent.getStringExtra("title")

        val late = whenAt > 0 && System.currentTimeMillis() - whenAt > 15 * 60_000L
        if (title != null && !late) {
            AgendaReminder.notify(
                ctx = app,
                title = title,
                place = intent.getStringExtra("place") ?: "",
                note = intent.getStringExtra("note") ?: "",
                startMinute = intent.getIntExtra("startMinute", 0),
                lead = intent.getIntExtra("lead", 0),
                // 同一条日程的多个档位共用这个 ID：后一条覆盖前一条，不在通知栏堆一串
                notifyId = AgendaReminder.notifyIdFromIntent(intent, code),
            )
        } else if (late) {
            android.util.Log.w(
                "AgendaReminder",
                "提醒迟到 ${(System.currentTimeMillis() - whenAt) / 60_000} 分钟，已丢弃：$title",
            )
        }

        if (code >= 0) AgendaReminder.forgetCode(app, code)

        // 续排放后台线程：要读 SharedPreferences 和解析日程 JSON
        val pending = goAsync()
        Thread {
            try {
                runCatching { AgendaReminder.rescheduleFromCache(app) }
                    .onFailure { android.util.Log.w("AgendaReminder", "续排失败", it) }
            } finally {
                pending.finish()
            }
        }.start()
    }
}

/**
 * 每天续一次命。它不负责准点，只负责「用户一周不打开 App、中间也没有一条提醒触发，
 * 队列也不会排空」。
 *
 * 独立于课程的 [ReminderRefreshWorker]：那个的启停挂在课程提醒开关上，
 * 课程关掉、日程开着时它会被 cancel，日程就跟着断签了。
 */
class AgendaReminderRefreshWorker(
    ctx: Context,
    params: androidx.work.WorkerParameters,
) : androidx.work.CoroutineWorker(ctx, params) {

    override suspend fun doWork(): Result {
        runCatching { AgendaReminder.rescheduleFromCache(applicationContext) }
            .onFailure { android.util.Log.w("AgendaReminder", "每日续排失败", it) }
        return Result.success()
    }

    companion object {
        private const val WORK_NAME = "agenda_reminder_refresh"

        fun ensure(ctx: Context) {
            runCatching {
                val req = androidx.work.PeriodicWorkRequestBuilder<AgendaReminderRefreshWorker>(
                    12, java.util.concurrent.TimeUnit.HOURS,
                ).build()
                androidx.work.WorkManager.getInstance(ctx).enqueueUniquePeriodicWork(
                    WORK_NAME, androidx.work.ExistingPeriodicWorkPolicy.KEEP, req,
                )
            }.onFailure { android.util.Log.w("AgendaReminder", "排每日续命失败", it) }
        }

        fun cancel(ctx: Context) {
            runCatching { androidx.work.WorkManager.getInstance(ctx).cancelUniqueWork(WORK_NAME) }
        }
    }
}
