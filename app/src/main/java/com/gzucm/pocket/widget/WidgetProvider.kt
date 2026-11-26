package com.gzucm.pocket.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.view.View
import android.widget.RemoteViews
import androidx.core.content.ContextCompat
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerParameters
import com.gzucm.pocket.MainActivity
import com.gzucm.pocket.R
import java.time.Duration
import java.time.LocalDateTime
import java.util.concurrent.TimeUnit

private const val TAG = "WidgetProvider"

/** 点部件上的刷新图标时发的广播 */
private const val ACTION_REFRESH = "com.gzucm.pocket.widget.ACTION_REFRESH"
private const val EXTRA_APP_WIDGET_ID = "appWidgetId"

/** PendingIntent 统一用 IMMUTABLE（Android 12+ 强制要求，不给可变性） */
private const val PI_FLAGS = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE

/**
 * 所有课表部件的公共基类。
 *
 * ## 为什么是一个基类 + 五个子类
 *
 * 五个部件的内容完全不同，但**刷新、点击、PendingIntent、读快照**这套骨架
 * 一模一样。基类负责骨架，子类只实现 [bind]（把数据塞进 RemoteViews）。
 *
 * Manifest 里每个子类各一条 `<receiver>`，各自指向不同的
 * `appwidget-provider` 配置 —— 桌面选择器里就是五条独立的条目。
 *
 * ## 为什么不用 Glance
 *
 * 见 docs/WIDGET_RESEARCH.md §1：ColorOS 会冻结 Glance 的异步 SessionWorker，
 * 小米深色模式又要求静态资源适配。这个部件是纯同步的 RemoteViews。
 */
abstract class BaseWidgetProvider : AppWidgetProvider() {

    /** 这套部件的布局 */
    protected abstract val layoutId: Int

    /** 2x1 空间太紧张，不放刷新图标 */
    protected open val hasRefresh: Boolean get() = true

    /** 把快照渲染进 RemoteViews。实现里只做 set 调用，不要做 IO */
    protected abstract fun bind(
        views: RemoteViews,
        context: Context,
        snapshot: WidgetSnapshotData?,
        now: LocalDateTime,
    )

    override fun onUpdate(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray,
    ) {
        // 快照只读一次，多个实例共用（用户可能同时摆了两个同样的部件）
        val snapshot = WidgetSnapshot.load(context)
        val now = LocalDateTime.now()
        appWidgetIds.forEach { id ->
            runCatching { appWidgetManager.updateAppWidget(id, build(context, id, snapshot, now)) }
                .onFailure { android.util.Log.w(TAG, "更新部件 $id 失败", it) }
        }
    }

    override fun onAppWidgetOptionsChanged(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetId: Int,
        newOptions: android.os.Bundle,
    ) {
        // 只按 minWidth/minHeight 声明尺寸，内容会随实际给的格子拉伸；
        // 宿主改了尺寸就重画一次，别让它停在旧内容上
        runCatching {
            appWidgetManager.updateAppWidget(
                appWidgetId,
                build(context, appWidgetId, WidgetSnapshot.load(context), LocalDateTime.now()),
            )
        }.onFailure { android.util.Log.w(TAG, "尺寸变化重绘失败", it) }
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == ACTION_REFRESH) {
            val id = intent.getIntExtra(EXTRA_APP_WIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID)
            val mgr = AppWidgetManager.getInstance(context)
            if (id != AppWidgetManager.INVALID_APPWIDGET_ID && id > 0) {
                runCatching {
                    mgr.updateAppWidget(id, build(context, id, WidgetSnapshot.load(context), LocalDateTime.now()))
                }.onFailure { android.util.Log.w(TAG, "手动刷新部件 $id 失败", it) }
            } else {
                onUpdate(context, mgr, mgr.getAppWidgetIds(ComponentName(context, javaClass)))
            }
            return
        }
        super.onReceive(context, intent)
    }

    /**
     * 构造一个实例的 RemoteViews。
     *
     * public 而不是 protected：`WidgetUpdate` 在 App 进程里批量刷新时
     * 要直接调它，不能只靠发广播（那样在 Robolectric / 后台限制下不可靠）。
     */
    fun build(
        context: Context,
        appWidgetId: Int,
        snapshot: WidgetSnapshotData?,
        now: LocalDateTime,
    ): RemoteViews {
        val views = RemoteViews(context.packageName, layoutId)
        runCatching { bind(views, context, snapshot, now) }
            .onFailure { android.util.Log.w(TAG, "渲染 ${javaClass.simpleName} 失败", it) }

        // 点整块 → 打开 App。显式 Intent + IMMUTABLE + 每个实例独立的 requestCode
        views.setOnClickPendingIntent(R.id.widget_root, openApp(context, appWidgetId))
        if (hasRefresh) {
            views.setOnClickPendingIntent(R.id.widget_refresh, refresh(context, appWidgetId))
        }
        return views
    }

    private fun openApp(context: Context, appWidgetId: Int): PendingIntent {
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or
                Intent.FLAG_ACTIVITY_CLEAR_TOP or
                Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        return PendingIntent.getActivity(context, 1000 + appWidgetId, intent, PI_FLAGS)
    }

    private fun refresh(context: Context, appWidgetId: Int): PendingIntent {
        val intent = Intent(context, javaClass).apply {
            action = ACTION_REFRESH
            // data 唯一：否则五个部件共用一个 PendingIntent，FLAG_UPDATE_CURRENT
            // 会把 extra 覆盖成最后那个 id，点谁都是刷同一个
            data = Uri.parse("gzucm://widget/refresh/$appWidgetId")
            putExtra(EXTRA_APP_WIDGET_ID, appWidgetId)
        }
        return PendingIntent.getBroadcast(context, 2000 + appWidgetId, intent, PI_FLAGS)
    }
}

// ── 2x1 当前课程 ────────────────────────────────────────────────────────

class CurrentCourseWidget : BaseWidgetProvider() {

    override val layoutId = R.layout.widget_current

    override val hasRefresh = false

    override fun bind(
        views: RemoteViews,
        context: Context,
        snapshot: WidgetSnapshotData?,
        now: LocalDateTime,
    ) {
        views.setTextViewText(R.id.widget_line, WidgetRender.currentLine(snapshot, now))
    }
}

// ── 2x2 今日课程 ────────────────────────────────────────────────────────

class TodayWidget : BaseWidgetProvider() {

    override val layoutId = R.layout.widget_today

    override fun bind(
        views: RemoteViews,
        context: Context,
        snapshot: WidgetSnapshotData?,
        now: LocalDateTime,
    ) {
        views.setTextViewText(R.id.widget_header, WidgetRender.header(snapshot, now.toLocalDate()))

        val courses = WidgetRender.upcoming(snapshot, now, MAX_ROWS)
        val rows = intArrayOf(R.id.row1, R.id.row2, R.id.row3)
        val times = intArrayOf(R.id.row1_time, R.id.row2_time, R.id.row3_time)
        val names = intArrayOf(R.id.row1_name, R.id.row2_name, R.id.row3_name)

        rows.forEachIndexed { i, rowId ->
            val course = courses.getOrNull(i)
            views.setViewVisibility(rowId, if (course == null) View.GONE else View.VISIBLE)
            if (course != null) {
                views.setTextViewText(times[i], course.startText)
                views.setTextViewText(names[i], course.name)
            }
        }

        if (courses.isEmpty()) {
            views.setViewVisibility(R.id.widget_rows, View.GONE)
            views.setViewVisibility(R.id.widget_empty, View.VISIBLE)
            views.setTextViewText(R.id.widget_empty, WidgetRender.emptyText(snapshot))
        } else {
            views.setViewVisibility(R.id.widget_rows, View.VISIBLE)
            views.setViewVisibility(R.id.widget_empty, View.GONE)
        }
    }

    private companion object {
        const val MAX_ROWS = 3
    }
}

// ── 4x2 今日横排 ────────────────────────────────────────────────────────

class TodayHorizontalWidget : BaseWidgetProvider() {

    override val layoutId = R.layout.widget_today_horizontal

    override fun bind(
        views: RemoteViews,
        context: Context,
        snapshot: WidgetSnapshotData?,
        now: LocalDateTime,
    ) {
        views.setTextViewText(R.id.widget_header, WidgetRender.header(snapshot, now.toLocalDate()))

        val data = WidgetRender.todayHorizontal(snapshot, now, MAX_BLOCKS)
        val nowMinute = now.hour * 60 + now.minute

        // 超过 5 节时留一格写「还有 N 节」，比硬塞 5 个然后戛然而止清楚
        val slots = if (data.overflow > 0) data.courses.take(MAX_BLOCKS - 1) else data.courses

        val blocks = intArrayOf(R.id.hb1, R.id.hb2, R.id.hb3, R.id.hb4, R.id.hb5)
        val names = intArrayOf(R.id.hb1_name, R.id.hb2_name, R.id.hb3_name, R.id.hb4_name, R.id.hb5_name)
        val times = intArrayOf(R.id.hb1_time, R.id.hb2_time, R.id.hb3_time, R.id.hb4_time, R.id.hb5_time)

        blocks.forEachIndexed { i, blockId ->
            val course = slots.getOrNull(i)
            val isOverflowSlot = course == null && data.overflow > 0 && i == slots.size
            views.setViewVisibility(blockId, if (course == null && !isOverflowSlot) View.GONE else View.VISIBLE)

            when {
                course != null -> {
                    views.setTextViewText(names[i], course.name)
                    views.setTextViewText(times[i], course.startText)
                    val ongoing = nowMinute >= course.startMinute && nowMinute < course.endMinute
                    val past = nowMinute >= course.endMinute
                    applyBlockStyle(views, context, blockId, names[i], times[i], ongoing, past)
                }

                isOverflowSlot -> {
                    views.setTextViewText(names[i], "还有 ${data.overflow} 节")
                    views.setTextViewText(times[i], "")
                    applyBlockStyle(views, context, blockId, names[i], times[i], ongoing = false, past = true)
                }
            }
        }

        if (slots.isEmpty() && data.overflow == 0) {
            views.setViewVisibility(R.id.widget_blocks, View.GONE)
            views.setViewVisibility(R.id.widget_empty, View.VISIBLE)
            views.setTextViewText(R.id.widget_empty, WidgetRender.emptyText(snapshot))
        } else {
            views.setViewVisibility(R.id.widget_blocks, View.VISIBLE)
            views.setViewVisibility(R.id.widget_empty, View.GONE)
        }
    }

    private companion object {
        const val MAX_BLOCKS = 5
    }
}

/**
 * 给课程块上色。
 *
 * 三种状态：正在上（反色强调）/ 已上完（压暗）/ 还没上（正常）。
 * 色值全部来自 resources（values + values-night 各一套），这里不判断深浅色 ——
 * 小米桌面切深色不会回调，代码里判 `isSystemInDarkTheme()` 是无效的。
 */
internal fun applyBlockStyle(
    views: RemoteViews,
    context: Context,
    blockId: Int,
    nameId: Int,
    timeId: Int,
    ongoing: Boolean,
    past: Boolean,
) {
    val (bg, text) = when {
        ongoing -> R.drawable.widget_block_now to R.color.widget_block_now_text
        past -> R.drawable.widget_block_past to R.color.widget_block_past_text
        else -> R.drawable.widget_block to R.color.widget_block_text
    }
    views.setInt(blockId, "setBackgroundResource", bg)
    val color = ContextCompat.getColor(context, text)
    views.setTextColor(nameId, color)
    views.setTextColor(timeId, color)
}

// ── 4x4 今日时间轴 ──────────────────────────────────────────────────────

class TimelineWidget : BaseWidgetProvider() {

    override val layoutId = R.layout.widget_timeline

    override fun bind(
        views: RemoteViews,
        context: Context,
        snapshot: WidgetSnapshotData?,
        now: LocalDateTime,
    ) {
        views.setTextViewText(R.id.widget_header, WidgetRender.header(snapshot, now.toLocalDate()))

        val rows = WidgetRender.timeline(snapshot, now, 1, UNIT_IDS.size)
        val nowMinute = now.hour * 60 + now.minute

        rows.forEachIndexed { i, row ->
            views.setTextViewText(UNIT_IDS[i], row.unit.toString())
            views.setTextViewText(TIME_IDS[i], row.time)

            val course = row.course
            if (course == null) {
                views.setTextViewText(BLOCK_IDS[i], "")
                views.setInt(BLOCK_IDS[i], "setBackgroundResource", 0)
                views.setTextColor(BLOCK_IDS[i], ContextCompat.getColor(context, R.color.widget_block_text))
            } else {
                // 连续的大课只在起始行写课程名，其余行留空 —— 同一个名字重复 4 遍
                // 不但难看，还会把 12 行的空间吃光
                views.setTextViewText(BLOCK_IDS[i], if (row.isStart) course.name else "")
                applyBlockStyle(
                    views = views,
                    context = context,
                    blockId = BLOCK_IDS[i],
                    nameId = BLOCK_IDS[i],
                    timeId = BLOCK_IDS[i],
                    ongoing = row.ongoing,
                    past = !row.ongoing && nowMinute >= course.endMinute,
                )
            }
        }
    }

    private companion object {
        val UNIT_IDS = intArrayOf(
            R.id.tl1_unit, R.id.tl2_unit, R.id.tl3_unit, R.id.tl4_unit,
            R.id.tl5_unit, R.id.tl6_unit, R.id.tl7_unit, R.id.tl8_unit,
            R.id.tl9_unit, R.id.tl10_unit, R.id.tl11_unit, R.id.tl12_unit,
        )
        val BLOCK_IDS = intArrayOf(
            R.id.tl1_block, R.id.tl2_block, R.id.tl3_block, R.id.tl4_block,
            R.id.tl5_block, R.id.tl6_block, R.id.tl7_block, R.id.tl8_block,
            R.id.tl9_block, R.id.tl10_block, R.id.tl11_block, R.id.tl12_block,
        )
        val TIME_IDS = intArrayOf(
            R.id.tl1_time, R.id.tl2_time, R.id.tl3_time, R.id.tl4_time,
            R.id.tl5_time, R.id.tl6_time, R.id.tl7_time, R.id.tl8_time,
            R.id.tl9_time, R.id.tl10_time, R.id.tl11_time, R.id.tl12_time,
        )
    }
}

// ── 4x4 三天课表 ────────────────────────────────────────────────────────

class ThreeDaysWidget : BaseWidgetProvider() {

    override val layoutId = R.layout.widget_three_days

    override fun bind(
        views: RemoteViews,
        context: Context,
        snapshot: WidgetSnapshotData?,
        now: LocalDateTime,
    ) {
        views.setTextViewText(R.id.widget_header, WidgetRender.header(snapshot, now.toLocalDate()))

        val columns = WidgetRender.threeDays(snapshot, now, MAX_PER_DAY)
        columns.forEachIndexed { c, column ->
            views.setTextViewText(LABEL_IDS[c], column.label)
            views.setTextViewText(DATE_IDS[c], column.dateText)

            ITEM_IDS[c].forEachIndexed { i, itemId ->
                val course = column.courses.getOrNull(i)
                if (course == null) {
                    // 第一格写「没课」，其余留空：整列空白会让人以为部件没加载
                    views.setTextViewText(itemId, if (i == 0 && column.courses.isEmpty()) "没课" else "")
                } else {
                    views.setTextViewText(itemId, "${course.startText} ${course.name}")
                }
            }

            views.setTextViewText(
                MORE_IDS[c],
                if (column.overflow > 0) "还有 ${column.overflow} 节" else "",
            )
        }
    }

    private companion object {
        const val MAX_PER_DAY = 4

        val LABEL_IDS = intArrayOf(R.id.d1_label, R.id.d2_label, R.id.d3_label)
        val DATE_IDS = intArrayOf(R.id.d1_date, R.id.d2_date, R.id.d3_date)
        val MORE_IDS = intArrayOf(R.id.d1_more, R.id.d2_more, R.id.d3_more)
        val ITEM_IDS = arrayOf(
            intArrayOf(R.id.d1_i1, R.id.d1_i2, R.id.d1_i3, R.id.d1_i4),
            intArrayOf(R.id.d2_i1, R.id.d2_i2, R.id.d2_i3, R.id.d2_i4),
            intArrayOf(R.id.d3_i1, R.id.d3_i2, R.id.d3_i3, R.id.d3_i4),
        )
    }
}

// ── 批量刷新 ────────────────────────────────────────────────────────────

/**
 * 在 App 进程里刷新所有部件实例。
 *
 * 为什么不用发广播：广播是异步的，还要过一遍系统队列；App 打开时用户就在看着，
 * 直接 `updateAppWidget` 是同步生效的。而且广播在部分 ROM 上会被延迟/合并。
 */
object WidgetUpdate {

    /** 顺序无所谓，但必须是全部五个 —— 漏一个就会出现「某个部件永远不刷新」 */
    val providers: List<Class<out BaseWidgetProvider>> = listOf(
        CurrentCourseWidget::class.java,
        TodayWidget::class.java,
        TodayHorizontalWidget::class.java,
        TimelineWidget::class.java,
        ThreeDaysWidget::class.java,
    )

    fun refreshAll(context: Context) {
        val app = context.applicationContext
        val snapshot = WidgetSnapshot.load(app)
        val now = LocalDateTime.now()
        providers.forEach { cls ->
            runCatching { refreshProvider(app, cls, snapshot, now) }
                .onFailure { android.util.Log.w(TAG, "刷新 ${cls.simpleName} 失败", it) }
        }
    }

    /** 只刷一类部件（比如点某个部件上的刷新图标） */
    fun refresh(context: Context, cls: Class<out BaseWidgetProvider>) {
        val app = context.applicationContext
        runCatching { refreshProvider(app, cls, WidgetSnapshot.load(app), LocalDateTime.now()) }
            .onFailure { android.util.Log.w(TAG, "刷新 ${cls.simpleName} 失败", it) }
    }

    private fun refreshProvider(
        context: Context,
        cls: Class<out BaseWidgetProvider>,
        snapshot: WidgetSnapshotData?,
        now: LocalDateTime,
    ) {
        val mgr = AppWidgetManager.getInstance(context)
        val ids = mgr.getAppWidgetIds(ComponentName(context, cls))
        if (ids.isEmpty()) return
        val provider = cls.getDeclaredConstructor().newInstance()
        ids.forEach { id -> mgr.updateAppWidget(id, provider.build(context, id, snapshot, now)) }
    }
}

/**
 * 每天 0 点刷一次。
 *
 * 为什么还是要有它：周次、星期、当前节次都是**渲染时**算的，但界面上的文字
 * 需要一次重绘才会变。用户过了 0 点不打开 App、桌面也不重建部件的话，
 * 2x1 那行字会停在昨天 —— 这个 Worker 就是兜底。
 *
 * WorkManager 不保证准点（Doze / 厂商省电都会推迟），所以所有文案都写成
 * 相对语义（「今天」「下一节」），晚几个小时也不会变成错的。
 */
class WidgetRefreshWorker(
    appContext: Context,
    params: WorkerParameters,
) : Worker(appContext, params) {

    override fun doWork(): Result {
        runCatching { WidgetUpdate.refreshAll(applicationContext) }
            .onFailure { android.util.Log.w(TAG, "每日刷新失败", it) }
        return Result.success()
    }

    companion object {
        private const val WORK_NAME = "widget_daily_refresh"

        fun ensure(ctx: Context) {
            runCatching {
                val now = LocalDateTime.now()
                val next = now.toLocalDate().plusDays(1).atTime(0, 5)
                val delayMs = Duration.between(now, next).toMillis().coerceAtLeast(0L)
                val req = PeriodicWorkRequestBuilder<WidgetRefreshWorker>(24, TimeUnit.HOURS)
                    .setInitialDelay(delayMs, TimeUnit.MILLISECONDS)
                    .build()
                WorkManager.getInstance(ctx)
                    .enqueueUniquePeriodicWork(WORK_NAME, ExistingPeriodicWorkPolicy.KEEP, req)
            }.onFailure { android.util.Log.w(TAG, "排每日刷新失败", it) }
        }
    }
}
