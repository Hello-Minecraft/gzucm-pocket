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
import com.gzucm.pocket.R
import kotlinx.coroutines.runBlocking
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId

/**
 * 每日天气提醒：早晨一条讲今天，晚上一条讲明天。
 *
 * ## 为什么不用 WorkManager
 *
 * 和上课提醒同一个理由：WorkManager 的最小周期是 15 分钟、且只保证「大概在那个时段」，
 * 用户设了 08:00 却在 08:40 才收到，「今天比昨天冷」的提醒就错过出门那一刻了。
 * 所以正式排程走 AlarmManager，每天响完自己续排下一天。
 *
 * ## 为什么数据要自己存
 *
 * 和风的 3d 预报只有今天/明天/后天，**没有昨天**。要算「今天比昨天冷几度」，
 * 只能在前一天成功拉到天气时把当天最高/最低温记下来（见 [WeatherSnapshot]）。
 * 昨天没记录（首次安装、那天没联网）就不比温差，只讲高温/低温/降雨三条。
 *
 * ## 关于两台闹钟
 *
 * 早晨和晚上是**两个独立闹钟**（各自开关、各自时间、各自 requestCode），
 * 关掉一个不影响另一个。文案生成是纯函数（[morningText] / [eveningText]），
 * 单测直接调，不需要真机、不需要网络。
 */
object WeatherAlert {

    const val CHANNEL_ID = "weather_alert"

    /** 默认早上 8 点：起床看一眼今天的温差 */
    const val DEFAULT_MORNING_TIME = "08:00"

    /** 默认晚上 10 点：睡前说明天要不要带伞 */
    const val DEFAULT_EVENING_TIME = "22:00"

    const val KIND_MORNING = "morning"
    const val KIND_EVENING = "evening"

    internal const val EXTRA_KIND = "weather_alert_kind"
    internal const val EXTRA_WHEN = "weather_alert_when"

    private const val REQ_MORNING = 9001
    private const val REQ_EVENING = 9002
    private const val NOTIFY_MORNING = 91_001
    private const val NOTIFY_EVENING = 91_002

    private const val TAG = "WeatherAlert"

    /**
     * 迟到多久就不发了。
     *
     * 国产 ROM 的省电合并会把闹钟推迟几个小时。08:00 的「今天比昨天冷」拖到下午才弹，
     * 只会让人以为 App 坏了；宁可这条不发，第二天的照排。
     */
    private const val LATE_LIMIT_MS = 3L * 60 * 60 * 1000

    // ── 文案（纯函数，单测直接调） ──────────────────────────────────

    /**
     * 温差主句。d = 今日最高温 − 昨日最高温。
     *
     * 分档写死是因为用户明确要这几句话：差个三五度只是「暖一点」，
     * 差到 5 度以上就是换衣服的量级，措辞必须不同。
     */
    fun diffText(d: Int): String = when {
        d <= -5 -> "今天比昨天冷 ${-d} 度，记得添件衣服"
        d < 0 -> "今天比昨天冷一点，差 ${-d} 度"
        d == 0 -> "今天和昨天差不多"
        d < 5 -> "今天比昨天暖一点，高 $d 度"
        else -> "今天比昨天热 $d 度，少穿一件"
    }

    /** 今天有没有降水（雨或雪都算）。和风的 textDay 是「小雨 / 阵雪」这种中文描述 */
    fun hasPrecip(text: String): Boolean = text.contains("雨") || text.contains("雪")

    /**
     * 早晨那一句。
     *
     * 规则：
     *   · 有昨天的数据 → 温差永远是主句；
     *   · 第二条从「雨 > 高温 > 低温」里挑一条（用户最需要先知道要不要带伞）；
     *   · 最多两句，中文逗号连接；
     *   · 昨天没数据 → 只从「雨 / 高温 / 低温」里按同样顺序取最多两条。
     *
     * @param yesterdayMax 昨天最高温；null 表示没记录（首次安装 / 那天没联网）
     */
    fun morningText(
        todayMax: Int,
        todayMin: Int,
        todayText: String,
        yesterdayMax: Int?,
    ): String {
        val wet = hasPrecip(todayText)
        val hot = todayMax >= 35
        val cold = todayMin <= 5

        if (yesterdayMax == null) {
            val parts = buildList {
                if (wet) add("今天有雨，记得带伞")
                if (hot) add("今天最高 $todayMax 度，注意防暑")
                if (cold) add("今天最低 $todayMin 度，注意保暖")
            }
            return parts.take(2).joinToString("，").ifEmpty {
                // 既不降温也不下雨、还没昨天数据：至少把今天大概多少度说清楚，
                // 否则开关开着却弹一条空通知
                "今天 $todayMin~$todayMax 度，${todayText.ifBlank { "天气不错" }}"
            }
        }

        val main = diffText(todayMax - yesterdayMax)
        // 温差已经占了主句，第二条最多再补一条，且优先说雨
        val tail = when {
            wet -> "还有雨记得带伞"
            hot -> "今天最高 $todayMax 度，注意防暑"
            cold -> "今天最低 $todayMin 度，注意保暖"
            else -> null
        }
        return if (tail == null) main else "$main，$tail"
    }

    /**
     * 晚上那一句。不下雨下雪返回 null —— 调用方据此**静默跳过**，不发通知。
     *
     * 雪单独说「有雪」：南方同学看到「有雨」不会想到要防滑。
     */
    fun eveningText(tomorrowText: String): String? = when {
        tomorrowText.contains("雪") -> "明天有雪，记得带伞"
        tomorrowText.contains("雨") -> "明天有雨，记得带伞"
        else -> null
    }

    // ── 时间 ────────────────────────────────────────────────────────

    /**
     * 下一次该响的时刻：今天这个点还没到就用今天，过了（或正好到）就用明天。
     *
     * 用 [LocalDateTime] 而不是硬编码 +08:00 —— 中国没有夏令时，
     * 但用户手动改时区后本地时间才是「他看到的 8 点」。
     */
    fun nextTrigger(hhmm: String, now: LocalDateTime = LocalDateTime.now()): LocalDateTime? {
        val t = runCatching { LocalTime.parse(hhmm.trim()) }.getOrNull() ?: return null
        val today = now.toLocalDate().atTime(t)
        return if (today.isAfter(now)) today else today.plusDays(1)
    }

    private fun millisOf(t: LocalDateTime): Long =
        t.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()

    // ── 排程 ────────────────────────────────────────────────────────

    /**
     * 按当前开关和时间重排两条闹钟。**先全撤再排**：
     * 用户把 08:00 改成 07:30 时，不撤的话旧的那条还在，会一天响两次。
     */
    fun schedule(ctx: Context) {
        val app = ctx.applicationContext
        val am = app.getSystemService(AlarmManager::class.java) ?: return
        cancel(app, am)

        // 没配天气服务就没什么可提醒的，别留一台永远取不到数据的闹钟
        if (!Weather.enabled) return

        val store = WeatherAlertStore(app)
        val now = LocalDateTime.now()
        ensureChannel(app)

        if (store.morningOn) {
            nextTrigger(store.morningTime, now)?.let {
                place(app, am, REQ_MORNING, KIND_MORNING, millisOf(it))
            }
        }
        if (store.eveningOn) {
            nextTrigger(store.eveningTime, now)?.let {
                place(app, am, REQ_EVENING, KIND_EVENING, millisOf(it))
            }
        }
    }

    /**
     * 实际排一条。精确闹钟权限被拒时退回非精确 —— 和上课提醒同一套判断
     * （[ClassReminder.canExact]），不另写一份。
     *
     * 这里**不用** setAlarmClock：那是系统闹钟级别的 API，会在状态栏常驻闹钟图标、
     * 锁屏显示「下一个闹钟」，为一条天气提醒付这个代价不值得。
     */
    private fun place(ctx: Context, am: AlarmManager, code: Int, kind: String, whenAt: Long) {
        val p = pendingIntent(ctx, code, kind, whenAt)
        val exact = ClassReminder.canExact(ctx)
        runCatching {
            if (exact) {
                am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, whenAt, p)
            } else {
                am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, whenAt, p)
            }
        }.onFailure { android.util.Log.w(TAG, "排 $kind 失败", it) }
    }

    private fun intentFor(ctx: Context): Intent = Intent(ctx, WeatherAlertReceiver::class.java)

    private fun pendingIntent(ctx: Context, code: Int, kind: String, whenAt: Long): PendingIntent {
        val intent = intentFor(ctx)
            .putExtra(EXTRA_KIND, kind)
            // 带上预定时刻：Receiver 靠它判断这条是不是已经被省电策略压了太久
            .putExtra(EXTRA_WHEN, whenAt)
        return PendingIntent.getBroadcast(
            ctx, code, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private fun cancel(ctx: Context, am: AlarmManager) {
        for (code in intArrayOf(REQ_MORNING, REQ_EVENING)) {
            // PendingIntent 的匹配只看 requestCode + 组件，extras 不参与，空 Intent 就能命中
            val p = PendingIntent.getBroadcast(
                ctx, code, intentFor(ctx),
                PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE,
            ) ?: continue
            runCatching {
                am.cancel(p)
                p.cancel()
            }
        }
    }

    /** 关掉开关时用：两条都撤 */
    fun cancelAll(ctx: Context) {
        val app = ctx.applicationContext
        app.getSystemService(AlarmManager::class.java)?.let { cancel(app, it) }
    }

    /**
     * 开机 / 改时间 / 冷启动后重排。只读本地缓存（开关和时间），不联网。
     */
    fun rescheduleFromStore(ctx: Context) {
        runCatching { schedule(ctx) }
            .onFailure { android.util.Log.w(TAG, "重排失败", it) }
    }

    // ── 通知 ────────────────────────────────────────────────────────

    fun ensureChannel(ctx: Context) {
        val mgr = ctx.getSystemService(NotificationManager::class.java) ?: return
        if (mgr.getNotificationChannel(CHANNEL_ID) != null) return
        mgr.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                "天气提醒",
                NotificationManager.IMPORTANCE_DEFAULT,
            ).apply {
                description = "每天早晨告诉你今天穿什么，晚上提醒明天要不要带伞"
            },
        )
    }

    private fun notifPermitted(ctx: Context): Boolean =
        Build.VERSION.SDK_INT < 33 ||
            ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED

    private fun notify(ctx: Context, title: String, text: String, id: Int) {
        if (!notifPermitted(ctx)) return
        ensureChannel(ctx)

        val open = runCatching {
            ctx.packageManager.getLaunchIntentForPackage(ctx.packageName)?.let { launch ->
                PendingIntent.getActivity(
                    ctx, id, launch,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                )
            }
        }.getOrNull()

        val n = NotificationCompat.Builder(ctx, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(text)
            // 折叠态只显示一行，两句话可能被截断 —— BigText 展开后是完整版
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setAutoCancel(true)
            .apply { if (open != null) setContentIntent(open) }
            .build()

        runCatching { NotificationManagerCompat.from(ctx).notify(id, n) }
            .onFailure { android.util.Log.w(TAG, "发通知失败", it) }
    }

    // ── 到点执行 ────────────────────────────────────────────────────

    /**
     * 闹钟触发后的全部逻辑：判断该不该发 → 拉一次天气 → 拼文案 → 发通知。
     *
     * 失败一律**静默**：没网、key 过期、接口抽风时，弹一句「天气获取失败」
     * 只会让用户以为 App 出故障 —— 这个提醒本来就不是必须的。
     */
    suspend fun fire(ctx: Context, kind: String, whenAt: Long) {
        val app = ctx.applicationContext
        val store = WeatherAlertStore(app)
        val today = LocalDate.now()
        val todayIso = today.toString()

        if (whenAt > 0 && System.currentTimeMillis() - whenAt > LATE_LIMIT_MS) {
            android.util.Log.i(TAG, "$kind 迟到太久，跳过")
            return
        }
        val enabled = if (kind == KIND_MORNING) store.morningOn else store.eveningOn
        if (!enabled) return

        // 同一天只发一次：改时间、手动重排都可能让同一台闹钟被排两遍
        val last = if (kind == KIND_MORNING) store.lastMorningDate else store.lastEveningDate
        if (last == todayIso) return

        if (!Weather.enabled) return

        when (val c = compose(app, kind, today)) {
            is Composed.Ok -> {
                notify(app, c.title, c.text, c.id)
                // 只有真的发出去了才记「今天发过」，否则当天重排后还能补发
                when (kind) {
                    KIND_MORNING -> store.lastMorningDate = todayIso
                    KIND_EVENING -> store.lastEveningDate = todayIso
                }
            }
            // 失败一律静默 —— 这个提醒本来就不是必须的
            Composed.NoWeather -> android.util.Log.i(TAG, "$kind 取天气失败，跳过：${Weather.lastError}")
            Composed.Nothing -> android.util.Log.i(TAG, "$kind 没有要发的内容，跳过")
        }
    }

    // ── 预览 ────────────────────────────────────────────────────────

    /** 预览失败的原因。[message] 直接给用户看（UI Toast 出去），所以是中文口语 */
    class PreviewError(message: String) : Exception(message)

    /**
     * 立即发一条预览通知。
     *
     * 和到点触发走**同一段** [compose] → [notify]，所以文案一个字都不差；
     * 但**不写**「今天已发过」的标记、**不碰闹钟** —— 预览完，明天的正常提醒照发。
     *
     * 和 [fire] 的区别只有失败处理：那边静默，这边把原因带回去让 UI 说出来。
     */
    suspend fun sendPreview(ctx: Context, morning: Boolean): Result<Unit> {
        val app = ctx.applicationContext
        val kind = if (morning) KIND_MORNING else KIND_EVENING

        if (!Weather.enabled) {
            return Result.failure(PreviewError("这个版本没配天气服务"))
        }
        // notify() 内部没权限就静默返回，预览必须提前拦下来，否则「点了没反应」
        if (!notifPermitted(app)) {
            return Result.failure(PreviewError("通知权限没开，允许通知后才能看到预览"))
        }

        return when (val c = compose(app, kind, LocalDate.now())) {
            is Composed.Ok -> {
                notify(app, c.title, c.text, c.id)
                Result.success(Unit)
            }
            Composed.NoWeather ->
                Result.failure(PreviewError(Weather.lastError ?: "天气没拿到，检查网络"))
            Composed.Nothing ->
                Result.failure(
                    PreviewError(
                        if (morning) "预报里没有今天的数据，稍后再试"
                        // 晚上这条本来就只有下雨下雪才发，没雨时「预览成功」会让人以为漏了通知
                        else "明天没有雨雪，这条到点不会发通知"
                    ),
                )
        }
    }

    // ── 内容组装（正式触发与预览共用） ──────────────────────────────

    /** 拼好的一条通知。两边共用同一份，文案才不会「预览一个样、真发另一个样」 */
    private sealed class Composed {
        data class Ok(val title: String, val text: String, val id: Int) : Composed()

        /** 天气取不到 */
        data object NoWeather : Composed()

        /** 按规则本来就不发：晚上明天没雨雪，或预报里缺那一天 */
        data object Nothing : Composed()
    }

    /**
     * 拉一次天气并拼出要发的内容。
     *
     * 顺手把结果写进缓存和 [WeatherSnapshot]（明天的温差靠它）—— 预览也写：
     * 这和「用户下拉刷新一次天气」的数据副作用等价，不是提醒状态。
     */
    private suspend fun compose(ctx: Context, kind: String, today: LocalDate): Composed {
        val info = Weather.fetch(WeatherPlaceStore(ctx).place) ?: return Composed.NoWeather
        Weather.save(ctx, SessionStore(ctx), info)

        val todayIso = today.toString()
        return when (kind) {
            KIND_MORNING -> {
                val d = info.days.firstOrNull { it.date == todayIso } ?: return Composed.Nothing
                val yesterday = WeatherSnapshot.of(ctx, today.minusDays(1))?.max
                Composed.Ok("今日天气", morningText(d.max, d.min, d.text, yesterday), NOTIFY_MORNING)
            }
            KIND_EVENING -> {
                val tomorrowIso = today.plusDays(1).toString()
                val d = info.days.firstOrNull { it.date == tomorrowIso } ?: return Composed.Nothing
                // 不下雨下雪就什么都不发（需求里明确要求静默）
                val text = eveningText(d.text) ?: return Composed.Nothing
                Composed.Ok("明日天气", text, NOTIFY_EVENING)
            }
            else -> Composed.Nothing
        }
    }
}

/**
 * 天气提醒的开关与时间。
 *
 * 单独一个 SharedPreferences 文件（`weather_alert`），不往 [SessionStore] 里塞 ——
 * 那里面已经是会话、电费、主题、课表一堆东西，这里是一组自洽的提醒配置。
 */
class WeatherAlertStore(context: Context) {

    private val sp = context.applicationContext
        .getSharedPreferences("weather_alert", Context.MODE_PRIVATE)

    /** 早晨天气提醒，默认开（需求：默认就给用户这条信息量最大的） */
    var morningOn: Boolean
        get() = sp.getBoolean(KEY_MORNING_ON, true)
        set(v) = sp.edit().putBoolean(KEY_MORNING_ON, v).apply()

    var morningTime: String
        get() = validTime(sp.getString(KEY_MORNING_TIME, null), WeatherAlert.DEFAULT_MORNING_TIME)
        set(v) = sp.edit().putString(KEY_MORNING_TIME, v).apply()

    /** 明日带伞提醒，默认开 */
    var eveningOn: Boolean
        get() = sp.getBoolean(KEY_EVENING_ON, true)
        set(v) = sp.edit().putBoolean(KEY_EVENING_ON, v).apply()

    var eveningTime: String
        get() = validTime(sp.getString(KEY_EVENING_TIME, null), WeatherAlert.DEFAULT_EVENING_TIME)
        set(v) = sp.edit().putString(KEY_EVENING_TIME, v).apply()

    /**
     * 上一次真的发出早晨 / 晚上提醒的日期（yyyy-MM-dd）。
     * 用来做「同一天不重复发」—— 闹钟重排、改时间都可能让同一天触发两次。
     */
    var lastMorningDate: String?
        get() = sp.getString(KEY_LAST_MORNING, null)
        set(v) = sp.edit().putString(KEY_LAST_MORNING, v).apply()

    var lastEveningDate: String?
        get() = sp.getString(KEY_LAST_EVENING, null)
        set(v) = sp.edit().putString(KEY_LAST_EVENING, v).apply()

    /**
     * 存进来的时间必须能解析，否则回默认值。
     *
     * 写坏的来源只有「旧版本格式 / 手改 prefs」，但一旦是坏值，
     * [WeatherAlert.nextTrigger] 会返回 null，那条提醒就**永远不排**而且没有任何提示。
     */
    private fun validTime(raw: String?, fallback: String): String {
        val t = raw?.let { runCatching { LocalTime.parse(it.trim()) }.getOrNull() }
            ?: return fallback
        return "%02d:%02d".format(t.hour, t.minute)
    }

    private companion object {
        const val KEY_MORNING_ON = "weather_morning_on"
        const val KEY_MORNING_TIME = "weather_morning_time"
        const val KEY_EVENING_ON = "weather_evening_on"
        const val KEY_EVENING_TIME = "weather_evening_time"
        const val KEY_LAST_MORNING = "weather_last_morning"
        const val KEY_LAST_EVENING = "weather_last_evening"
    }
}

/**
 * 到点收广播：拉天气、发通知，然后**无论成败都续排下一天**。
 *
 * 用 goAsync + 独立线程：取天气是网络请求，不能在广播的主线程上做。
 * 续排放 finally 里 —— 这次取天气失败也要接着排，否则一次断网就把这条链断掉。
 */
class WeatherAlertReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val app = context.applicationContext
        val kind = intent.getStringExtra(WeatherAlert.EXTRA_KIND) ?: return
        val whenAt = intent.getLongExtra(WeatherAlert.EXTRA_WHEN, 0L)

        val pending = goAsync()
        Thread {
            try {
                runCatching { runBlocking { WeatherAlert.fire(app, kind, whenAt) } }
                    .onFailure { android.util.Log.w("WeatherAlert", "$kind 执行失败", it) }
            } finally {
                runCatching { WeatherAlert.schedule(app) }
                    .onFailure { android.util.Log.w("WeatherAlert", "续排失败", it) }
                pending.finish()
            }
        }.start()
    }
}
