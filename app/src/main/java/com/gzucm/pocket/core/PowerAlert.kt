package com.gzucm.pocket.core

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.gzucm.pocket.MainActivity
import com.gzucm.pocket.R
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * 电费不足提醒。
 *
 * 设计取舍：**后台不联网**。
 * 一卡通的会话在 WebView 里，后台 OkHttp 拿不到（这条已经验证过很多次了），
 * 所以巡检只读「上次同步时算好的可用天数」，不发起网络请求。
 * 好处是不会失败、不耗电、不打扰；代价是数据最多旧一天 ——
 * 每天打开 App 一次就足够新鲜，且 App 内已有自动同步。
 */
object PowerAlert {

    private const val CHANNEL_ID = "power_low"
    private const val WORK_NAME = "power_daily_check"
    private const val NOTIFY_ID = 1001

    /** 少于这个天数就提醒 */
    const val THRESHOLD_DAYS = 3f

    /** 数据超过这个时长就不提醒了，避免拿过期数据吓人 */
    private const val STALE_MS = 48L * 60 * 60 * 1000

    fun ensureChannel(ctx: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val mgr = ctx.getSystemService(NotificationManager::class.java) ?: return
        if (mgr.getNotificationChannel(CHANNEL_ID) != null) return
        mgr.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                "电费提醒",
                NotificationManager.IMPORTANCE_HIGH,
            ).apply {
                description = "宿舍电量不足时提醒"
                enableVibration(true)
            }
        )
    }

    /** 每天跑一次。用 KEEP 避免重复排期。 */
    fun schedule(ctx: Context) {
        val req = PeriodicWorkRequestBuilder<CheckWorker>(1, TimeUnit.DAYS)
            .setConstraints(
                Constraints.Builder()
                    .setRequiresBatteryNotLow(true)
                    .build()
            )
            .build()
        WorkManager.getInstance(ctx)
            .enqueueUniquePeriodicWork(WORK_NAME, ExistingPeriodicWorkPolicy.KEEP, req)
    }

    fun cancel(ctx: Context) {
        WorkManager.getInstance(ctx).cancelUniqueWork(WORK_NAME)
    }

    /**
     * 判断现在要不要提醒。抽成纯函数便于测试。
     *
     * @param daysLeft 可用天数，<=0 表示还没数据
     * @param checkedAt 上次同步时间
     * @param lastAlertDate 上次提醒日期（yyyy-MM-dd）
     * @param today 今天（yyyy-MM-dd）
     */
    fun shouldNotify(
        daysLeft: Float,
        checkedAt: Long,
        lastAlertDate: String?,
        today: String,
        now: Long = System.currentTimeMillis(),
        threshold: Float = THRESHOLD_DAYS,
    ): Boolean {
        if (daysLeft <= 0f) return false            // 没数据
        if (daysLeft >= threshold) return false      // 还够用
        if (checkedAt <= 0L) return false
        if (now - checkedAt > STALE_MS) return false // 数据太旧，别误报
        if (lastAlertDate == today) return false     // 今天已经提醒过
        return true
    }

    fun notify(ctx: Context, daysLeft: Float, balanceText: String) {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        ) {
            return
        }
        ensureChannel(ctx)

        val open = PendingIntent.getActivity(
            ctx, 0,
            Intent(ctx, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val days = when {
            daysLeft < 1f -> "不足 1 天"
            else -> "约 ${daysLeft.toInt()} 天"
        }

        val n = NotificationCompat.Builder(ctx, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle("宿舍电量快用完了")
            .setContentText("剩余 $balanceText，可用$days，记得充值")
            .setStyle(
                NotificationCompat.BigTextStyle()
                    .bigText("剩余 $balanceText，按最近用电速度可用$days。打开 App 可查看每日明细。")
            )
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setAutoCancel(true)
            .setContentIntent(open)
            .build()

        runCatching { NotificationManagerCompat.from(ctx).notify(NOTIFY_ID, n) }
    }

    /**
     * 是否该跑静默同步：每天只跑一次。
     * 抽成纯函数是为了能测——「每次打开都跑一遍」是最容易写错的地方。
     */
    /**
     * 进电费页时要不要自动弹窗同步。
     *
     * - 从没成功过 → 要
     * - 距上次成功超过 [staleMs]（默认 2 小时）→ 要
     * - 刚失败过（尝试时间晚于成功时间，且不到 [cooldownMs]）→ 不要，免得反复弹
     */
    fun needsAutoSync(
        lastCheckedAt: Long,
        lastAttemptAt: Long,
        now: Long = System.currentTimeMillis(),
        staleMs: Long = 2 * 60 * 60_000L,
        cooldownMs: Long = 10 * 60_000L,
    ): Boolean {
        if (lastAttemptAt > lastCheckedAt && now - lastAttemptAt < cooldownMs) return false
        return lastCheckedAt <= 0L || now - lastCheckedAt >= staleMs
    }

    fun today(): String =
        SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())

    /** 每日巡检 */
    class CheckWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
        override suspend fun doWork(): Result {
            val store = SessionStore(applicationContext)
            // 用户关掉了就别发
            if (!store.powerAlertEnabled) return Result.success()
            val days = store.powerDaysLeft
            val at = store.powerCheckedAt
            val today = today()

            // 余额只用来拼通知文案，解析失败就退回「-」
            val balance = store.cachedPower
                ?.let {
                    runCatching {
                        Net.json.decodeFromString(PowerInfo.serializer(), it).balance
                    }.getOrNull()
                }
                ?.let { com.gzucm.pocket.ui.components.Fmt.trim2(it) }
                ?: "-"

            return if (shouldNotify(days, at, store.powerAlertDate, today)) {
                notify(applicationContext, days, "$balance 度")
                store.powerAlertDate = today
                Result.success()
            } else {
                Result.success()
            }
        }
    }
}
