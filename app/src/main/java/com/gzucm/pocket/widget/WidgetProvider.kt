package com.gzucm.pocket.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Typeface
import android.net.Uri
import android.text.Spannable
import android.text.SpannableString
import android.text.style.StyleSpan
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
 * ## 为什么是一个基类 + 六个子类
 *
 * 六个部件的内容完全不同，但**刷新、点击、PendingIntent、读快照**这套骨架
 * 一模一样。基类负责骨架，子类只实现 [bind]（把数据塞进 RemoteViews）。
 *
 * Manifest 里每个子类各一条 `<receiver>`，各自指向不同的
 * `appwidget-provider` 配置 —— 桌面选择器里就是六条独立的条目。
 *
 * ## 为什么不用 Glance
 *
 * 见 docs/WIDGET_RESEARCH.md §1：ColorOS 会冻结 Glance 的异步 SessionWorker，
 * 小米深色模式又要求静态资源适配。这个部件是纯同步的 RemoteViews。
 */
abstract class BaseWidgetProvider : AppWidgetProvider() {

    /** 这套部件的布局 */
    protected abstract val layoutId: Int

    /** 空间太紧张的不放刷新图标（2x1、2x2） */
    protected open val hasRefresh: Boolean get() = true

    /** 把快照渲染进 RemoteViews。实现里只做 set 调用，不要做 IO */
    protected abstract fun bind(
        views: RemoteViews,
        context: Context,
        snapshot: WidgetSnapshotData?,
        now: LocalDateTime,
    )

    /**
     * [bind] 之后、交给宿主之前调。只有需要 appWidgetId 的部件才覆盖 ——
     * 比如周视图绑 ListView 的 RemoteViewsService，并决定要不要滚到某一行。
     * 默认什么都不做，其余五个部件的代码不用改。
     */
    protected open fun afterBind(
        views: RemoteViews,
        context: Context,
        appWidgetId: Int,
        snapshot: WidgetSnapshotData?,
        now: LocalDateTime,
    ) {
    }

    /**
     * `updateAppWidget` 之后调。列表型部件在这里补
     * `notifyAppWidgetViewDataChanged` —— 宿主只有收到这次通知才会重新跑
     * factory 的 onDataSetChanged，否则列表会一直显示缓存住的旧数据。
     */
    protected open fun afterUpdate(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetId: Int,
    ) {
    }

    override fun onUpdate(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray,
    ) {
        // 快照只读一次，多个实例共用（用户可能同时摆了两个同样的部件）
        val snapshot = WidgetSnapshot.load(context)
        val now = LocalDateTime.now()
        appWidgetIds.forEach { id ->
            runCatching { push(context, appWidgetManager, id, snapshot, now) }
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
            push(
                context,
                appWidgetManager,
                appWidgetId,
                WidgetSnapshot.load(context),
                LocalDateTime.now(),
            )
        }.onFailure { android.util.Log.w(TAG, "尺寸变化重绘失败", it) }
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == ACTION_REFRESH) {
            val id = intent.getIntExtra(EXTRA_APP_WIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID)
            val mgr = AppWidgetManager.getInstance(context)
            if (id != AppWidgetManager.INVALID_APPWIDGET_ID && id > 0) {
                runCatching {
                    push(context, mgr, id, WidgetSnapshot.load(context), LocalDateTime.now())
                }.onFailure { android.util.Log.w(TAG, "手动刷新部件 $id 失败", it) }
            } else {
                onUpdate(context, mgr, mgr.getAppWidgetIds(ComponentName(context, javaClass)))
            }
            return
        }
        super.onReceive(context, intent)
    }

    /**
     * 更新一个实例：渲染 → 交给宿主 → 补后续动作。
     *
     * public 而不是 protected：`WidgetUpdate` 在 App 进程里批量刷新时
     * 要直接调它，不能只靠发广播（那样在 Robolectric / 后台限制下不可靠）。
     */
    fun push(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetId: Int,
        snapshot: WidgetSnapshotData?,
        now: LocalDateTime,
    ) {
        appWidgetManager.updateAppWidget(appWidgetId, build(context, appWidgetId, snapshot, now))
        runCatching { afterUpdate(context, appWidgetManager, appWidgetId) }
            .onFailure { android.util.Log.w(TAG, "更新后动作失败 $appWidgetId", it) }
    }

    /** 构造一个实例的 RemoteViews。只做 set 调用，副作用放到 [afterUpdate] */
    fun build(
        context: Context,
        appWidgetId: Int,
        snapshot: WidgetSnapshotData?,
        now: LocalDateTime,
    ): RemoteViews {
        val views = RemoteViews(context.packageName, layoutId)
        runCatching { bind(views, context, snapshot, now) }
            .onFailure { android.util.Log.w(TAG, "渲染 ${javaClass.simpleName} 失败", it) }
        runCatching { afterBind(views, context, appWidgetId, snapshot, now) }
            .onFailure { android.util.Log.w(TAG, "绑定 ${javaClass.simpleName} 的后续内容失败", it) }

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
            // data 唯一：否则几个部件共用一个 PendingIntent，FLAG_UPDATE_CURRENT
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

// ── 2x2 今日课程（上下两张卡）──────────────────────────────────────────

class TodayWidget : BaseWidgetProvider() {

    override val layoutId = R.layout.widget_today

    /** 2x2 实在太小，按产品要求连刷新图标都不放 */
    override val hasRefresh = false

    override fun bind(
        views: RemoteViews,
        context: Context,
        snapshot: WidgetSnapshotData?,
        now: LocalDateTime,
    ) {
        val data = WidgetRender.todayCards(snapshot, now)

        if (data.emptyText != null) {
            views.setViewVisibility(R.id.card1, View.GONE)
            views.setViewVisibility(R.id.card2, View.GONE)
            views.setViewVisibility(R.id.widget_empty, View.VISIBLE)
            views.setTextViewText(R.id.widget_empty, data.emptyText)
            return
        }

        views.setViewVisibility(R.id.widget_empty, View.GONE)
        bindCard(
            views = views,
            context = context,
            card = data.cards.getOrNull(0),
            cardId = R.id.card1,
            startId = R.id.card1_start,
            endId = R.id.card1_end,
            nameId = R.id.card1_name,
            roomId = null,
            teacherId = null,
        )
        bindCard(
            views = views,
            context = context,
            card = data.cards.getOrNull(1),
            cardId = R.id.card2,
            startId = R.id.card2_start,
            endId = R.id.card2_end,
            nameId = R.id.card2_name,
            roomId = null,
            teacherId = null,
        )
    }
}

// ── 4x2 今日课程（两张卡 + 地点 + 老师）────────────────────────────────

class TodayHorizontalWidget : BaseWidgetProvider() {

    override val layoutId = R.layout.widget_today_horizontal

    override fun bind(
        views: RemoteViews,
        context: Context,
        snapshot: WidgetSnapshotData?,
        now: LocalDateTime,
    ) {
        views.setTextViewText(R.id.widget_header, WidgetRender.header(snapshot, now.toLocalDate()))

        val data = WidgetRender.todayCards(snapshot, now)
        if (data.emptyText != null) {
            views.setViewVisibility(R.id.c1, View.GONE)
            views.setViewVisibility(R.id.c2, View.GONE)
            views.setViewVisibility(R.id.widget_empty, View.VISIBLE)
            views.setTextViewText(R.id.widget_empty, data.emptyText)
            return
        }

        views.setViewVisibility(R.id.widget_empty, View.GONE)
        bindCard(
            views = views,
            context = context,
            card = data.cards.getOrNull(0),
            cardId = R.id.c1,
            startId = R.id.c1_start,
            endId = R.id.c1_end,
            nameId = R.id.c1_name,
            roomId = R.id.c1_room,
            teacherId = R.id.c1_teacher,
        )
        bindCard(
            views = views,
            context = context,
            card = data.cards.getOrNull(1),
            cardId = R.id.c2,
            startId = R.id.c2_start,
            endId = R.id.c2_end,
            nameId = R.id.c2_name,
            roomId = R.id.c2_room,
            teacherId = R.id.c2_teacher,
        )
    }
}

/**
 * 把一张卡填上数据（2x2 和 4x2 共用）。
 *
 * [roomId] / [teacherId] 传 null 表示这个版式没有这两栏。值为空串时
 * **整栏隐藏**，不留一条空白 —— 老快照没有 teacher 字段，读出来就是空串。
 */
private fun bindCard(
    views: RemoteViews,
    context: Context,
    card: TodayCard?,
    cardId: Int,
    startId: Int,
    endId: Int,
    nameId: Int,
    roomId: Int?,
    teacherId: Int?,
) {
    if (card == null) {
        // 今天只剩一节课时，第二张卡整个收掉，不留空壳
        views.setViewVisibility(cardId, View.GONE)
        return
    }
    views.setViewVisibility(cardId, View.VISIBLE)
    views.setTextViewText(startId, "· ${card.startText}")
    views.setTextViewText(endId, card.endText)
    views.setTextViewText(nameId, card.name)

    if (roomId != null) setOptionalText(views, roomId, card.room)
    if (teacherId != null) setOptionalText(views, teacherId, card.teacher)

    val bg = if (card.ongoing) R.drawable.widget_block_now else R.drawable.widget_block
    views.setInt(cardId, "setBackgroundResource", bg)

    val main = ContextCompat.getColor(
        context,
        if (card.ongoing) R.color.widget_block_now_text else R.color.widget_block_text,
    )
    views.setTextColor(nameId, main)

    // 时间用次要色；正在上的卡是反色底，次要色也要跟着变白，否则灰字糊在绿底上看不清
    val accent = ContextCompat.getColor(
        context,
        if (card.ongoing) R.color.widget_block_now_text else R.color.widget_text_dim,
    )
    views.setTextColor(startId, accent)
    views.setTextColor(endId, accent)
    if (roomId != null) views.setTextColor(roomId, accent)
    if (teacherId != null) views.setTextColor(teacherId, accent)
}

/** 有内容就显示，空串整栏收掉 */
private fun setOptionalText(views: RemoteViews, viewId: Int, text: String) {
    if (text.isBlank()) {
        views.setViewVisibility(viewId, View.GONE)
    } else {
        views.setViewVisibility(viewId, View.VISIBLE)
        views.setTextViewText(viewId, text)
    }
}

/**
 * 课程块 / 列表行的三种状态配色。
 *
 * 色值全部来自 resources（values + values-night 各一套），这里不判断深浅色 ——
 * 小米桌面切深色不会回调，代码里判 `isSystemInDarkTheme()` 是无效的。
 *
 * [textIds] 随底色走的正文；[dimIds] 是次要信息（地点之类），
 * 普通状态下比正文淡一档。
 */
private fun applyBlockStyle(
    views: RemoteViews,
    context: Context,
    blockId: Int,
    state: TodayRowState,
    textIds: IntArray,
    dimIds: IntArray = IntArray(0),
) {
    val bg = when (state) {
        TodayRowState.ONGOING -> R.drawable.widget_block_now
        TodayRowState.PAST -> R.drawable.widget_block_past
        TodayRowState.UPCOMING -> R.drawable.widget_block
    }
    val textColor = when (state) {
        TodayRowState.ONGOING -> R.color.widget_block_now_text
        TodayRowState.PAST -> R.color.widget_block_past_text
        TodayRowState.UPCOMING -> R.color.widget_block_text
    }
    val dimColor = when (state) {
        TodayRowState.ONGOING -> R.color.widget_block_now_text
        TodayRowState.PAST -> R.color.widget_block_past_text
        TodayRowState.UPCOMING -> R.color.widget_text_dim
    }

    views.setInt(blockId, "setBackgroundResource", bg)
    val main = ContextCompat.getColor(context, textColor)
    textIds.forEach { views.setTextColor(it, main) }
    val dim = ContextCompat.getColor(context, dimColor)
    dimIds.forEach { views.setTextColor(it, dim) }
}

// ── 4x4 今日课程（节次列表）────────────────────────────────────────────

class TimelineWidget : BaseWidgetProvider() {

    override val layoutId = R.layout.widget_timeline

    override fun bind(
        views: RemoteViews,
        context: Context,
        snapshot: WidgetSnapshotData?,
        now: LocalDateTime,
    ) {
        views.setTextViewText(R.id.widget_header, WidgetRender.header(snapshot, now.toLocalDate()))

        val data = WidgetRender.todayList(snapshot, now, MAX_ROWS)
        if (data.emptyText != null) {
            ROW_IDS.forEach { views.setViewVisibility(it, View.GONE) }
            views.setViewVisibility(R.id.widget_empty, View.VISIBLE)
            views.setTextViewText(R.id.widget_empty, data.emptyText)
            return
        }

        views.setViewVisibility(R.id.widget_empty, View.GONE)
        ROW_IDS.forEachIndexed { i, rowId ->
            val row = data.rows.getOrNull(i)
            if (row == null) {
                views.setViewVisibility(rowId, View.GONE)
                return@forEachIndexed
            }
            views.setViewVisibility(rowId, View.VISIBLE)
            views.setTextViewText(UNIT_IDS[i], row.unitText)
            views.setTextViewText(TIME_IDS[i], row.timeText)
            views.setTextViewText(NAME_IDS[i], row.name)
            views.setTextViewText(ROOM_IDS[i], row.room)
            // 地点没填就整栏收掉，别留一截空白把课名挤窄
            views.setViewVisibility(ROOM_IDS[i], if (row.room.isBlank()) View.GONE else View.VISIBLE)
            applyBlockStyle(
                views = views,
                context = context,
                blockId = rowId,
                state = row.state,
                textIds = intArrayOf(UNIT_IDS[i], TIME_IDS[i], NAME_IDS[i]),
                dimIds = intArrayOf(ROOM_IDS[i]),
            )
        }
    }

    private companion object {
        const val MAX_ROWS = 7

        val ROW_IDS = intArrayOf(
            R.id.tl1, R.id.tl2, R.id.tl3, R.id.tl4,
            R.id.tl5, R.id.tl6, R.id.tl7,
        )
        val UNIT_IDS = intArrayOf(
            R.id.tl1_unit, R.id.tl2_unit, R.id.tl3_unit, R.id.tl4_unit,
            R.id.tl5_unit, R.id.tl6_unit, R.id.tl7_unit,
        )
        val TIME_IDS = intArrayOf(
            R.id.tl1_time, R.id.tl2_time, R.id.tl3_time, R.id.tl4_time,
            R.id.tl5_time, R.id.tl6_time, R.id.tl7_time,
        )
        val NAME_IDS = intArrayOf(
            R.id.tl1_name, R.id.tl2_name, R.id.tl3_name, R.id.tl4_name,
            R.id.tl5_name, R.id.tl6_name, R.id.tl7_name,
        )
        val ROOM_IDS = intArrayOf(
            R.id.tl1_room, R.id.tl2_room, R.id.tl3_room, R.id.tl4_room,
            R.id.tl5_room, R.id.tl6_room, R.id.tl7_room,
        )
    }
}

// ── 4x3 三天课表 ────────────────────────────────────────────────────────

class ThreeDaysWidget : BaseWidgetProvider() {

    override val layoutId = R.layout.widget_three_days

    override fun bind(
        views: RemoteViews,
        context: Context,
        snapshot: WidgetSnapshotData?,
        now: LocalDateTime,
    ) {
        views.setTextViewText(R.id.widget_header, WidgetRender.header(snapshot, now.toLocalDate()))

        val nowMinute = now.hour * 60 + now.minute
        val columns = WidgetRender.threeDays(snapshot, now, MAX_PER_DAY)

        columns.forEachIndexed { c, column ->
            views.setTextViewText(LABEL_IDS[c], column.label)
            views.setTextViewText(DATE_IDS[c], column.dateText)

            BLOCK_IDS[c].forEachIndexed { i, blockId ->
                val course = column.courses.getOrNull(i)
                when {
                    course != null -> {
                        views.setViewVisibility(blockId, View.VISIBLE)
                        views.setTextViewText(TIME_IDS[c][i], course.startText)
                        views.setTextViewText(NAME_IDS[c][i], course.name)
                        val state = when {
                            nowMinute < course.startMinute -> TodayRowState.UPCOMING
                            nowMinute < course.endMinute -> TodayRowState.ONGOING
                            else -> TodayRowState.PAST
                        }
                        applyBlockStyle(
                            views = views,
                            context = context,
                            blockId = blockId,
                            state = state,
                            textIds = intArrayOf(TIME_IDS[c][i], NAME_IDS[c][i]),
                        )
                    }

                    // 整天没课：第一格写「没课」，其余格收掉。
                    // 整列空白会让人以为部件没加载
                    i == 0 && column.courses.isEmpty() -> {
                        views.setViewVisibility(blockId, View.VISIBLE)
                        views.setInt(blockId, "setBackgroundResource", 0)
                        views.setTextViewText(TIME_IDS[c][i], "")
                        views.setTextViewText(NAME_IDS[c][i], "没课")
                        views.setTextColor(
                            NAME_IDS[c][i],
                            ContextCompat.getColor(context, R.color.widget_text_dim),
                        )
                    }

                    else -> views.setViewVisibility(blockId, View.GONE)
                }
            }
        }
    }

    private companion object {
        const val MAX_PER_DAY = 6

        val LABEL_IDS = intArrayOf(R.id.d1_label, R.id.d2_label, R.id.d3_label)
        val DATE_IDS = intArrayOf(R.id.d1_date, R.id.d2_date, R.id.d3_date)
        val TIME_IDS = arrayOf(
            intArrayOf(R.id.d1_t1, R.id.d1_t2, R.id.d1_t3, R.id.d1_t4, R.id.d1_t5, R.id.d1_t6),
            intArrayOf(R.id.d2_t1, R.id.d2_t2, R.id.d2_t3, R.id.d2_t4, R.id.d2_t5, R.id.d2_t6),
            intArrayOf(R.id.d3_t1, R.id.d3_t2, R.id.d3_t3, R.id.d3_t4, R.id.d3_t5, R.id.d3_t6),
        )
        val NAME_IDS = arrayOf(
            intArrayOf(R.id.d1_n1, R.id.d1_n2, R.id.d1_n3, R.id.d1_n4, R.id.d1_n5, R.id.d1_n6),
            intArrayOf(R.id.d2_n1, R.id.d2_n2, R.id.d2_n3, R.id.d2_n4, R.id.d2_n5, R.id.d2_n6),
            intArrayOf(R.id.d3_n1, R.id.d3_n2, R.id.d3_n3, R.id.d3_n4, R.id.d3_n5, R.id.d3_n6),
        )
        val BLOCK_IDS = arrayOf(
            intArrayOf(R.id.d1_b1, R.id.d1_b2, R.id.d1_b3, R.id.d1_b4, R.id.d1_b5, R.id.d1_b6),
            intArrayOf(R.id.d2_b1, R.id.d2_b2, R.id.d2_b3, R.id.d2_b4, R.id.d2_b5, R.id.d2_b6),
            intArrayOf(R.id.d3_b1, R.id.d3_b2, R.id.d3_b3, R.id.d3_b4, R.id.d3_b5, R.id.d3_b6),
        )
    }
}

// ── 4x3 周视图 ──────────────────────────────────────────────────────────

/**
 * 周视图：表头 + 日期行是普通 RemoteViews，12 节 × 7 天是 ListView。
 *
 * 正文为什么要 RemoteViewsService：见 [WidgetWeekService] 的注释
 * （ScrollView 不能程序化滚动，只有 ListView 的 setScrollPosition 能定位）。
 */
class WeekWidget : BaseWidgetProvider() {

    override val layoutId = R.layout.widget_week

    override fun bind(
        views: RemoteViews,
        context: Context,
        snapshot: WidgetSnapshotData?,
        now: LocalDateTime,
    ) {
        val grid = WidgetRender.weekGrid(snapshot, now)

        views.setTextViewText(R.id.widget_header, grid.header)

        // 星期表头 + 日期。今天那一列用主题色，日期加粗
        grid.weekdays.forEachIndexed { c, label ->
            views.setTextViewText(WEEKDAY_IDS[c], label)
            val isToday = c == grid.todayCol
            views.setTextColor(
                WEEKDAY_IDS[c],
                ContextCompat.getColor(
                    context,
                    if (isToday) R.color.widget_accent else R.color.widget_text_dim,
                ),
            )
            views.setTextColor(
                DATE_IDS[c],
                ContextCompat.getColor(
                    context,
                    if (isToday) R.color.widget_accent else R.color.widget_text_dim,
                ),
            )
            val date = grid.dates.getOrNull(c).orEmpty()
            views.setTextViewText(
                DATE_IDS[c],
                if (isToday) bold(date) else date,
            )
        }
    }

    // setRemoteAdapter(viewId, Intent) 在 API 35 起被标记为 deprecated，
    // 官方方向是 API 31+ 的 RemoteCollectionItems（一次把所有行塞进 RemoteViews）
    // 或 Compose 的 Glance。这两条都走不通：
    //   · minSdk 26，RemoteCollectionItems 用不了；
    //   · Glance 的异步 SessionWorker 会被 ColorOS 冻结（见 docs/WIDGET_RESEARCH.md）。
    // 所以继续用 Service 版，这是 26 上唯一能跑的列表方案。
    @Suppress("DEPRECATION")
    override fun afterBind(
        views: RemoteViews,
        context: Context,
        appWidgetId: Int,
        snapshot: WidgetSnapshotData?,
        now: LocalDateTime,
    ) {
        // 每个实例一个独立 factory：Intent 里带 appWidgetId，data 唯一
        // （不带 data 的话系统会把几个周视图合并到同一个 factory 上）
        val adapter = Intent(context, WidgetWeekService::class.java).apply {
            putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId)
            data = Uri.parse(toUri(Intent.URI_INTENT_SCHEME))
        }
        views.setRemoteAdapter(R.id.widget_week_list, adapter)
        views.setEmptyView(R.id.widget_week_list, R.id.widget_week_empty)

        // ── 打开就停在当前节次 ──────────────────────────────────────
        // setScrollPosition 必须在交给宿主之前调（它只是一条要 apply 的 action）。
        //
        // 去重很关键：不然用户正往下翻着看晚上的课，一次刷新就把视口拽回当前行。
        // 只有「当前行变了」（上一节下课了）才重新定位一次。
        val row = WidgetRender.currentRow(now)
        val prefs = prefs(context)
        val key = "week_last_row_$appWidgetId"
        if (prefs.getInt(key, -1) != row) {
            views.setScrollPosition(R.id.widget_week_list, row)
            prefs.edit().putInt(key, row).apply()
        }
    }

    // notifyAppWidgetViewDataChanged 的两个重载在 API 35 都标了 deprecated
    // （和 setRemoteAdapter 一起，整个「Service 型集合部件」都被划线了），
    // 但 26 上没有替代品，见 afterBind 上面的说明。
    @Suppress("DEPRECATION")
    override fun afterUpdate(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetId: Int,
    ) {
        // factory 里的快照是缓存住的（84 个格子不能每格读一次 SharedPreferences），
        // 只有这条通知能让它重跑 onDataSetChanged 拿到新快照；
        // 顺带让宿主在数据到位后重新应用一次上面那条滚动位置。
        appWidgetManager.notifyAppWidgetViewDataChanged(appWidgetId, R.id.widget_week_list)
    }

    override fun onDeleted(context: Context, appWidgetIds: IntArray) {
        // 部件删了就把「上次定位到的行」清掉，否则 prefs 里会一直堆没人用的 key
        val editor = prefs(context).edit()
        appWidgetIds.forEach { editor.remove("week_last_row_$it") }
        editor.apply()
    }

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(WEEK_PREFS, Context.MODE_PRIVATE)

    /** 日期加粗。RemoteViews 没有 setTypeface，用 StyleSpan 跟着文本一起过去 */
    private fun bold(text: String): CharSequence =
        SpannableString(text).apply {
            setSpan(StyleSpan(Typeface.BOLD), 0, length, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
        }

    private companion object {
        const val WEEK_PREFS = "gzucm_widget_week"

        val WEEKDAY_IDS = intArrayOf(
            R.id.wk_wd0, R.id.wk_wd1, R.id.wk_wd2, R.id.wk_wd3,
            R.id.wk_wd4, R.id.wk_wd5, R.id.wk_wd6,
        )
        val DATE_IDS = intArrayOf(
            R.id.wk_date0, R.id.wk_date1, R.id.wk_date2, R.id.wk_date3,
            R.id.wk_date4, R.id.wk_date5, R.id.wk_date6,
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

    /** 顺序无所谓，但必须是全部六个 —— 漏一个就会出现「某个部件永远不刷新」 */
    val providers: List<Class<out BaseWidgetProvider>> = listOf(
        CurrentCourseWidget::class.java,
        TodayWidget::class.java,
        TodayHorizontalWidget::class.java,
        TimelineWidget::class.java,
        ThreeDaysWidget::class.java,
        WeekWidget::class.java,
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
        ids.forEach { id -> provider.push(context, mgr, id, snapshot, now) }
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
