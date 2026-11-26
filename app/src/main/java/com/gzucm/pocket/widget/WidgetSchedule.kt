package com.gzucm.pocket.widget

import android.content.Context
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerParameters
import java.time.Duration
import java.time.LocalDateTime

/**
 * 部件刷新节奏的**纯函数**层：什么时候该刷、下一次排多远。
 *
 * ## 为什么需要它
 *
 * 部件没有「被看到」这个回调（Android 不给），`SCREEN_ON` 从 8.0 起也不能
 * 静态注册，前台服务代价太大。能做的只有**按时间刷**：在上课时段内每 15 分钟
 * 一次，其它时间不排（省电），第二天 0:05 由每日 worker 重新起链。
 *
 * 之前的实现在 16:02 还显示「正在上 就业指导」（那门课 15:30 就下课了），
 * 因为整个白天只有早上那一次渲染。下面这套就是为了让文字跟上时间。
 *
 * 所有判断都做成纯函数，可以脱离 WorkManager 直接单测 —— 排程类 bug
 * （比如周日晚上排了一个永远不跑的链）在真机上极难复现。
 */
object WidgetSchedule {

    /** 课程时段内每 15 分钟刷一次 */
    const val TickMinutes = 15L

    /** 窗口在第一节前、最后一节后各放宽多少分钟（课前准备 / 课间） */
    const val WindowPadMinutes = 10

    /** 课前几分钟先刷一次：让「下一节」提前变准，别等到上课那一分钟才跳 */
    const val PrewarmMinutes = 5

    /** 已经排好的 tick 落在多少分钟内就不重排（防抖） */
    const val DebounceMinutes = 12L

    /**
     * 今天「该刷」的时间窗口（当天第几分钟）。没课 / 快照为空 / 学期外 → null。
     *
     * 第一节前 10 分钟就开始刷：那时候桌面上显示的是「下一节 08:30」，
     * 提前进入节奏能让这句话一直是对的。
     */
    fun window(snapshot: WidgetSnapshotData?, now: LocalDateTime): IntRange? {
        val today = snapshot?.let { WidgetRender.coursesOn(it, now.toLocalDate()) }.orEmpty()
        if (today.isEmpty()) return null
        val first = today.minOf { it.startMinute }
        val last = today.maxOf { it.endMinute }
        // 时间缺失（脏数据）时不排 —— 否则会算出一个荒唐的延迟
        if (first == Int.MAX_VALUE || last == Int.MAX_VALUE) return null
        return (first - WindowPadMinutes)..(last + WindowPadMinutes)
    }

    /** 此刻是否落在上课时段（含课前 10 分钟和课后 10 分钟） */
    fun shouldTick(snapshot: WidgetSnapshotData?, now: LocalDateTime): Boolean {
        val w = window(snapshot, now) ?: return false
        return minuteOfDay(now) in w
    }

    /**
     * 下一次该在多久之后跑；null = 今天不用再排。
     *
     *   · 今天没课 / 快照为空 / 学期外        → null
     *   · now < 第一节 - 10 分钟              → 排到「第一节 - 5 分钟」
     *   · 窗口内（含课间）                    → 15 分钟
     *   · now > 最后一节 + 10 分钟            → null（交给 0:05 的每日 worker）
     */
    fun nextTickDelay(snapshot: WidgetSnapshotData?, now: LocalDateTime): Duration? {
        val w = window(snapshot, now) ?: return null
        val nowMinute = minuteOfDay(now)
        if (nowMinute > w.last) return null

        if (nowMinute < w.first) {
            // first - WindowPadMinutes + WindowPadMinutes - Prewarm = first - Prewarm
            val target = w.first + WindowPadMinutes - PrewarmMinutes
            return Duration.ofMinutes((target - nowMinute).toLong().coerceAtLeast(1L))
        }
        return Duration.ofMinutes(TickMinutes)
    }

    /**
     * 要不要重排（防抖）。
     *
     * 已经有一个计划落在**未来 12 分钟内**就不要再排：否则用户每切一次前台
     * 都会把链重置一遍，永远等不到那 15 分钟。
     *
     * 计划时间已经过去（<= now）时必须重排 —— 那说明链断了（进程被杀、
     * WorkManager 被清），再拦着就永远不会恢复。
     */
    fun shouldRearm(scheduledAtMs: Long, nowMs: Long): Boolean {
        if (scheduledAtMs <= nowMs) return true
        return scheduledAtMs - nowMs > DebounceMinutes * 60_000L
    }

    private fun minuteOfDay(now: LocalDateTime): Int = now.hour * 60 + now.minute
}

/**
 * 链式 tick 的排程器。
 *
 * 只用一个 unique work（`widget_tick`）+ REPLACE：任何时刻最多只有**一条**
 * 待跑的链，不会因为反复 arm 堆出一堆任务。
 */
object WidgetTick {

    private const val TAG = "WidgetTick"
    private const val WORK_NAME = "widget_tick"
    private const val PREFS = "gzucm_widget_tick"
    private const val KEY_NEXT_AT = "next_at_ms"

    /**
     * 按当前时间排下一次 tick。返回是否真的排了。
     *
     * 调用点（四处，缺一处就会出现「某天不刷新」）：
     *   1. [WidgetTickWorker] 跑完 → 经 [WidgetUpdate.refreshAll] 间接调用
     *   2. 每天 0:05 的 [WidgetRefreshWorker] → 同样经 refreshAll
     *   3. App 打开 / 切回前台 → [WidgetUpdate.refreshAll]
     *   4. 部件新增或系统重绘 → [BaseWidgetProvider.onUpdate]
     */
    fun arm(context: Context, snapshot: WidgetSnapshotData?, now: LocalDateTime): Boolean {
        val app = context.applicationContext
        val delay = WidgetSchedule.nextTickDelay(snapshot, now)
        val prefs = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val nowMs = System.currentTimeMillis()

        // 今天不用再刷：清掉时间戳并撤掉待跑的那条，避免明天之前空转
        if (delay == null) {
            prefs.edit().remove(KEY_NEXT_AT).apply()
            runCatching { WorkManager.getInstance(app).cancelUniqueWork(WORK_NAME) }
                .onFailure { android.util.Log.w(TAG, "撤销 tick 失败", it) }
            return false
        }

        if (!WidgetSchedule.shouldRearm(prefs.getLong(KEY_NEXT_AT, 0L), nowMs)) return false

        runCatching {
            val req = OneTimeWorkRequestBuilder<WidgetTickWorker>()
                .setInitialDelay(delay)
                .build()
            WorkManager.getInstance(app)
                .enqueueUniqueWork(WORK_NAME, ExistingWorkPolicy.REPLACE, req)
            prefs.edit().putLong(KEY_NEXT_AT, nowMs + delay.toMillis()).apply()
        }.onFailure { android.util.Log.w(TAG, "排 tick 失败", it) }
        return true
    }
}

/**
 * 一次 tick：刷所有部件，然后把自己排到下一次。
 *
 * WorkManager 在 Doze / 厂商省电下会把 15 分钟拖到 20~30 分钟，这是可接受的
 * 降级（文字最多晚半小时，不会错）。要更准只能换 AlarmManager，代价是耗电。
 */
class WidgetTickWorker(
    appContext: Context,
    params: WorkerParameters,
) : Worker(appContext, params) {

    override fun doWork(): Result {
        // refreshAll 内部会在渲染完之后调 WidgetTick.arm 排下一次，
        // 所以这里只管刷；刷失败也要让链继续，否则一次异常就永久停摆
        runCatching { WidgetUpdate.refreshAll(applicationContext) }
            .onFailure { android.util.Log.w("WidgetTick", "tick 刷新失败", it) }
        return Result.success()
    }
}
