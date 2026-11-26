package com.gzucm.pocket.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Color
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
        // 刚放到桌面上时也要进入 15 分钟节奏，否则要等用户开一次 App 才开始刷
        runCatching { WidgetTick.arm(context.applicationContext, snapshot, now) }
            .onFailure { android.util.Log.w(TAG, "排下一次 tick 失败", it) }
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

// ── 课程色查表 ──────────────────────────────────────────────────────────
//
// 所有「选哪个颜色 / 哪个圆角」的决策都只看 colorIndex，这里用数组下标完成。
// **不要**拼 "widget_k$n" 字符串去 Resources.getIdentifier：资源名在 release
// 包里会被 R8 收缩，运行时拿到 0，整块底色直接变透明 —— 这类 bug 只在
// release APK 上出现，debug 完全正常。

/** [WidgetRender.colorIndexFor] 的取值范围；下标越界时按环形取，绝不抛异常 */
private const val K_COUNT = 8

private fun kIndex(raw: Int): Int = ((raw % K_COUNT) + K_COUNT) % K_COUNT

private val DOT_BG = intArrayOf(
    R.drawable.widget_dot1, R.drawable.widget_dot2, R.drawable.widget_dot3, R.drawable.widget_dot4,
    R.drawable.widget_dot5, R.drawable.widget_dot6, R.drawable.widget_dot7, R.drawable.widget_dot8,
)

/** 2x2 卡（43dp 高，a 档 22dp 圆角） */
private val KA_BG = intArrayOf(
    R.drawable.widget_ka1, R.drawable.widget_ka2, R.drawable.widget_ka3, R.drawable.widget_ka4,
    R.drawable.widget_ka5, R.drawable.widget_ka6, R.drawable.widget_ka7, R.drawable.widget_ka8,
)
private val KA_NOW_BG = intArrayOf(
    R.drawable.widget_ka1_now, R.drawable.widget_ka2_now, R.drawable.widget_ka3_now, R.drawable.widget_ka4_now,
    R.drawable.widget_ka5_now, R.drawable.widget_ka6_now, R.drawable.widget_ka7_now, R.drawable.widget_ka8_now,
)

/** 4x2 卡（34dp 高，b 档 16dp 圆角） */
private val KB_BG = intArrayOf(
    R.drawable.widget_kb1, R.drawable.widget_kb2, R.drawable.widget_kb3, R.drawable.widget_kb4,
    R.drawable.widget_kb5, R.drawable.widget_kb6, R.drawable.widget_kb7, R.drawable.widget_kb8,
)
private val KB_NOW_BG = intArrayOf(
    R.drawable.widget_kb1_now, R.drawable.widget_kb2_now, R.drawable.widget_kb3_now, R.drawable.widget_kb4_now,
    R.drawable.widget_kb5_now, R.drawable.widget_kb6_now, R.drawable.widget_kb7_now, R.drawable.widget_kb8_now,
)

/** 4x4 行 / 三日块（26dp 高，c 档 13dp 圆角） */
private val KC_BG = intArrayOf(
    R.drawable.widget_kc1, R.drawable.widget_kc2, R.drawable.widget_kc3, R.drawable.widget_kc4,
    R.drawable.widget_kc5, R.drawable.widget_kc6, R.drawable.widget_kc7, R.drawable.widget_kc8,
)
private val KC_NOW_BG = intArrayOf(
    R.drawable.widget_kc1_now, R.drawable.widget_kc2_now, R.drawable.widget_kc3_now, R.drawable.widget_kc4_now,
    R.drawable.widget_kc5_now, R.drawable.widget_kc6_now, R.drawable.widget_kc7_now, R.drawable.widget_kc8_now,
)

/** 块内文字（同色系的深/亮色），只用在「正在上」和课色块上 */
private val K_TEXT = intArrayOf(
    R.color.widget_k1_text, R.color.widget_k2_text, R.color.widget_k3_text, R.color.widget_k4_text,
    R.color.widget_k5_text, R.color.widget_k6_text, R.color.widget_k7_text, R.color.widget_k8_text,
)

/** 加粗。RemoteViews 没有 setTypeface，用 StyleSpan 跟着文本一起过去 */
private fun bold(text: String): CharSequence =
    SpannableString(text).apply {
        setSpan(StyleSpan(Typeface.BOLD), 0, length, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
    }

/** 有内容就显示，空串整栏收掉 */
private fun setOptionalText(views: RemoteViews, viewId: Int, text: String, color: Int) {
    if (text.isBlank()) {
        views.setViewVisibility(viewId, View.GONE)
    } else {
        views.setViewVisibility(viewId, View.VISIBLE)
        views.setTextViewText(viewId, text)
        views.setTextColor(viewId, color)
    }
}

/**
 * 一张 2x2 / 4x2 的课程卡（两个版式共用）。
 *
 * [roomId] / [teacherId] 传 null = 这个版式没有这两栏；值为空串时整栏隐藏
 * （老快照没有 teacher 字段，读出来就是空串）。
 *
 * 正在上的卡：块底换成该课的 26% 变体、课名加粗、正文改用块内文字色 ——
 * **不用实心主题色**，否则一眼看过去分不出是哪门课。
 */
private fun bindTodayCard(
    views: RemoteViews,
    context: Context,
    card: TodayCard?,
    cardId: Int,
    dotId: Int,
    timeId: Int,
    nameId: Int,
    roomId: Int?,
    teacherId: Int?,
    normalBg: IntArray,
    nowBg: IntArray,
) {
    if (card == null) {
        // 今天只剩一节课时，第二张卡收成 INVISIBLE 而不是 GONE：
        // GONE 会让第一张卡撑满整块，看起来「这一天特别长」
        views.setViewVisibility(cardId, View.INVISIBLE)
        return
    }
    val i = kIndex(card.colorIndex)
    views.setViewVisibility(cardId, View.VISIBLE)
    views.setInt(cardId, "setBackgroundResource", if (card.ongoing) nowBg[i] else normalBg[i])
    views.setInt(dotId, "setBackgroundResource", DOT_BG[i])

    views.setTextViewText(timeId, card.startText)
    views.setTextViewText(nameId, if (card.ongoing) bold(card.name) else card.name)

    val strong = ContextCompat.getColor(
        context,
        if (card.ongoing) K_TEXT[i] else R.color.widget_text,
    )
    val dim = ContextCompat.getColor(
        context,
        if (card.ongoing) K_TEXT[i] else R.color.widget_text_dim,
    )
    val weak = ContextCompat.getColor(
        context,
        if (card.ongoing) K_TEXT[i] else R.color.widget_text_weak,
    )
    views.setTextColor(nameId, strong)
    views.setTextColor(timeId, dim)

    if (roomId != null) setOptionalText(views, roomId, card.room, dim)
    if (teacherId != null) setOptionalText(views, teacherId, card.teacher, weak)
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
        val info = WidgetRender.currentInfo(snapshot, now)
        if (info.emptyText != null) {
            views.setViewVisibility(R.id.widget_current_row, View.GONE)
            views.setViewVisibility(R.id.widget_empty, View.VISIBLE)
            views.setTextViewText(R.id.widget_empty, info.emptyText)
            return
        }

        views.setViewVisibility(R.id.widget_current_row, View.VISIBLE)
        views.setViewVisibility(R.id.widget_empty, View.GONE)
        views.setInt(
            R.id.widget_current_dot,
            "setBackgroundResource",
            DOT_BG[kIndex(info.colorIndex)],
        )
        views.setTextViewText(R.id.widget_current_time, info.time)
        views.setTextViewText(R.id.widget_current_name, info.name)
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
        bindTodayCard(
            views = views,
            context = context,
            card = data.cards.getOrNull(0),
            cardId = R.id.card1,
            dotId = R.id.card1_dot,
            timeId = R.id.card1_time,
            nameId = R.id.card1_name,
            roomId = null,
            teacherId = null,
            normalBg = KA_BG,
            nowBg = KA_NOW_BG,
        )
        bindTodayCard(
            views = views,
            context = context,
            card = data.cards.getOrNull(1),
            cardId = R.id.card2,
            dotId = R.id.card2_dot,
            timeId = R.id.card2_time,
            nameId = R.id.card2_name,
            roomId = null,
            teacherId = null,
            normalBg = KA_BG,
            nowBg = KA_NOW_BG,
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
        bindTodayCard(
            views = views,
            context = context,
            card = data.cards.getOrNull(0),
            cardId = R.id.c1,
            dotId = R.id.c1_dot,
            timeId = R.id.c1_time,
            nameId = R.id.c1_name,
            roomId = R.id.c1_room,
            teacherId = R.id.c1_teacher,
            normalBg = KB_BG,
            nowBg = KB_NOW_BG,
        )
        bindTodayCard(
            views = views,
            context = context,
            card = data.cards.getOrNull(1),
            cardId = R.id.c2,
            dotId = R.id.c2_dot,
            timeId = R.id.c2_time,
            nameId = R.id.c2_name,
            roomId = R.id.c2_room,
            teacherId = R.id.c2_teacher,
            normalBg = KB_BG,
            nowBg = KB_NOW_BG,
        )
    }
}

// ── 4x4 今日课程（课程条目）────────────────────────────────────────────

/**
 * 一门课一行，最多 7 门；已上完的课不占位。
 *
 * 行高靠 weight 分配：前 n 行 VISIBLE，接着到 [WidgetRender.widgetRowsToAllot]
 * 行 INVISIBLE（不画但**仍参与分配**），其余 GONE（不参与）。这样 1~4 门课时
 * 卡片只占四分之一到一半高度，不会被一门课撑成一整块。
 */
class TimelineWidget : BaseWidgetProvider() {

    override val layoutId = R.layout.widget_timeline

    override fun bind(
        views: RemoteViews,
        context: Context,
        snapshot: WidgetSnapshotData?,
        now: LocalDateTime,
    ) {
        views.setTextViewText(R.id.widget_header, WidgetRender.header(snapshot, now.toLocalDate()))

        val data = WidgetRender.todayCourses(snapshot, now, MAX_ROWS)
        if (data.emptyText != null) {
            ROW_IDS.forEach { views.setViewVisibility(it, View.GONE) }
            views.setViewVisibility(R.id.widget_empty, View.VISIBLE)
            views.setTextViewText(R.id.widget_empty, data.emptyText)
            return
        }
        views.setViewVisibility(R.id.widget_empty, View.GONE)

        val text = ContextCompat.getColor(context, R.color.widget_text)
        val dim = ContextCompat.getColor(context, R.color.widget_text_dim)
        val weak = ContextCompat.getColor(context, R.color.widget_text_weak)
        val allot = WidgetRender.widgetRowsToAllot(data.rows.size)

        ROW_IDS.forEachIndexed { i, rowId ->
            val row = data.rows.getOrNull(i)
            if (row == null) {
                // 关键：INVISIBLE 仍占 weight，GONE 不占。
                // 「少于 4 门按 4 门排」完全靠这一行实现
                views.setViewVisibility(rowId, if (i < allot) View.INVISIBLE else View.GONE)
                return@forEachIndexed
            }

            views.setViewVisibility(rowId, View.VISIBLE)
            val colorIndex = kIndex(row.colorIndex)
            val ongoing = row.state == TodayRowState.ONGOING
            views.setInt(
                rowId,
                "setBackgroundResource",
                if (ongoing) KA_NOW_BG[colorIndex] else KA_BG[colorIndex],
            )
            views.setInt(DOT_IDS[i], "setBackgroundResource", DOT_BG[colorIndex])

            views.setTextViewText(UNIT_IDS[i], row.unitText)
            views.setTextViewText(TIME_IDS[i], row.timeText)
            views.setTextViewText(NAME_IDS[i], if (ongoing) bold(row.name) else row.name)
            // 地点没填就整栏收掉，别留一截空白把课名挤窄
            views.setViewVisibility(ROOM_IDS[i], if (row.room.isBlank()) View.GONE else View.VISIBLE)
            views.setTextViewText(ROOM_IDS[i], row.room)

            if (ongoing) {
                // 正在上：块底换 26% 变体、课名加粗、文字用块内文字色
                val ink = ContextCompat.getColor(context, K_TEXT[colorIndex])
                views.setTextColor(UNIT_IDS[i], ink)
                views.setTextColor(TIME_IDS[i], ink)
                views.setTextColor(NAME_IDS[i], ink)
                views.setTextColor(ROOM_IDS[i], ink)
            } else {
                views.setTextColor(UNIT_IDS[i], weak)
                views.setTextColor(TIME_IDS[i], dim)
                views.setTextColor(NAME_IDS[i], text)
                views.setTextColor(ROOM_IDS[i], weak)
            }
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
        val DOT_IDS = intArrayOf(
            R.id.tl1_dot, R.id.tl2_dot, R.id.tl3_dot, R.id.tl4_dot,
            R.id.tl5_dot, R.id.tl6_dot, R.id.tl7_dot,
        )
    }
}

// ── 4x3 三天课表 ────────────────────────────────────────────────────────

/**
 * 今天 / 明天 / 后天三列，每列最多 4 块。
 *
 * 超出的第 4 块换成「还有 N 节」（不显示第 4 门课）—— 和时间轴的做法一致，
 * 用户能知道「这天还有课」，也不会误以为课表只到这儿。
 */
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
        val accent = ContextCompat.getColor(context, R.color.widget_accent)
        val text = ContextCompat.getColor(context, R.color.widget_text)
        val dim = ContextCompat.getColor(context, R.color.widget_text_dim)
        val weak = ContextCompat.getColor(context, R.color.widget_text_weak)
        val columns = WidgetRender.threeDays(snapshot, now, MAX_PER_DAY)

        columns.forEachIndexed { c, column ->
            // 今天用强调色 + 加粗：三列只有 70dp 宽，靠颜色比靠位置更快认出「我在哪」
            views.setTextViewText(LABEL_IDS[c], if (column.isToday) bold(column.label) else column.label)
            views.setTextColor(LABEL_IDS[c], if (column.isToday) accent else text)
            views.setTextViewText(DATE_IDS[c], column.dateText)
            views.setTextColor(DATE_IDS[c], if (column.isToday) accent else dim)

            BLOCK_IDS[c].forEachIndexed { i, blockId ->
                val course = if (column.overflow > 0 && i == MAX_PER_DAY - 1) {
                    null
                } else {
                    column.courses.getOrNull(i)
                }
                val overflowRow = course == null &&
                    column.overflow > 0 &&
                    i == MAX_PER_DAY - 1

                when {
                    course != null -> {
                        views.setViewVisibility(blockId, View.VISIBLE)
                        val colorIndex = kIndex(WidgetRender.colorIndexFor(course.name))
                        val state = when {
                            nowMinute < course.startMinute -> TodayRowState.UPCOMING
                            nowMinute < course.endMinute -> TodayRowState.ONGOING
                            else -> TodayRowState.PAST
                        }
                        views.setInt(
                            blockId,
                            "setBackgroundResource",
                            if (state == TodayRowState.ONGOING) {
                                KC_NOW_BG[colorIndex]
                            } else {
                                KC_BG[colorIndex]
                            },
                        )
                        views.setTextViewText(TIME_IDS[c][i], course.startText)
                        views.setTextViewText(NAME_IDS[c][i], course.name)
                        when (state) {
                            // 三天视图里「已经上完」只在今天那一列出现，
                            // 压成弱色即可，不必整块挖空
                            TodayRowState.PAST -> {
                                views.setTextColor(TIME_IDS[c][i], weak)
                                views.setTextColor(NAME_IDS[c][i], weak)
                            }

                            TodayRowState.ONGOING -> {
                                val ink = ContextCompat.getColor(context, K_TEXT[colorIndex])
                                views.setTextColor(TIME_IDS[c][i], ink)
                                views.setTextColor(NAME_IDS[c][i], ink)
                                views.setTextViewText(NAME_IDS[c][i], bold(course.name))
                            }

                            TodayRowState.UPCOMING -> {
                                views.setTextColor(TIME_IDS[c][i], dim)
                                views.setTextColor(NAME_IDS[c][i], text)
                            }
                        }
                    }

                    // 整天没课：第一格写「没课」，其余格收掉。
                    // 整列空白会让人以为部件没加载
                    i == 0 && column.courses.isEmpty() -> {
                        views.setViewVisibility(blockId, View.VISIBLE)
                        // 这里以前传 setBackgroundResource(0)，部分 ROM 的宿主
                        // 不接受 0 这个资源 id。改成显式透明色更稳。
                        views.setInt(blockId, "setBackgroundColor", Color.TRANSPARENT)
                        views.setTextViewText(TIME_IDS[c][i], "")
                        views.setTextViewText(NAME_IDS[c][i], "没课")
                        views.setTextColor(NAME_IDS[c][i], weak)
                    }

                    overflowRow -> {
                        views.setViewVisibility(blockId, View.VISIBLE)
                        views.setInt(blockId, "setBackgroundColor", Color.TRANSPARENT)
                        views.setTextViewText(TIME_IDS[c][i], "")
                        views.setTextViewText(NAME_IDS[c][i], "还有 ${column.overflow} 节")
                        views.setTextColor(NAME_IDS[c][i], weak)
                    }

                    else -> views.setViewVisibility(blockId, View.GONE)
                }
            }
        }
    }

    private companion object {
        /** 和 widget_three_days.xml 里每列的块数一致；改布局必须同步改这里 */
        const val MAX_PER_DAY = 4

        val LABEL_IDS = intArrayOf(R.id.d1_label, R.id.d2_label, R.id.d3_label)
        val DATE_IDS = intArrayOf(R.id.d1_date, R.id.d2_date, R.id.d3_date)
        val TIME_IDS = arrayOf(
            intArrayOf(R.id.d1_t1, R.id.d1_t2, R.id.d1_t3, R.id.d1_t4),
            intArrayOf(R.id.d2_t1, R.id.d2_t2, R.id.d2_t3, R.id.d2_t4),
            intArrayOf(R.id.d3_t1, R.id.d3_t2, R.id.d3_t3, R.id.d3_t4),
        )
        val NAME_IDS = arrayOf(
            intArrayOf(R.id.d1_n1, R.id.d1_n2, R.id.d1_n3, R.id.d1_n4),
            intArrayOf(R.id.d2_n1, R.id.d2_n2, R.id.d2_n3, R.id.d2_n4),
            intArrayOf(R.id.d3_n1, R.id.d3_n2, R.id.d3_n3, R.id.d3_n4),
        )
        val BLOCK_IDS = arrayOf(
            intArrayOf(R.id.d1_b1, R.id.d1_b2, R.id.d1_b3, R.id.d1_b4),
            intArrayOf(R.id.d2_b1, R.id.d2_b2, R.id.d2_b3, R.id.d2_b4),
            intArrayOf(R.id.d3_b1, R.id.d3_b2, R.id.d3_b3, R.id.d3_b4),
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

        // 星期表头 + 日期。今天那一列用主题色，两行都加粗
        grid.weekdays.forEachIndexed { c, label ->
            val isToday = c == grid.todayCol
            val color = ContextCompat.getColor(
                context,
                if (isToday) R.color.widget_accent else R.color.widget_text_dim,
            )
            views.setTextViewText(WEEKDAY_IDS[c], if (isToday) bold(label) else label)
            views.setTextColor(WEEKDAY_IDS[c], color)

            val date = grid.dates.getOrNull(c).orEmpty()
            views.setTextViewText(DATE_IDS[c], if (isToday) bold(date) else date)
            views.setTextColor(DATE_IDS[c], color)
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
        // App 打开 / 同步完成 / tick 跑完都会走到这里：顺手把下一次 tick 排上。
        // arm 内部有防抖，这里多调几次不会把链重置。
        runCatching { WidgetTick.arm(app, snapshot, now) }
            .onFailure { android.util.Log.w(TAG, "排下一次 tick 失败", it) }
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
 *
 * 它同时是 15 分钟链的**起点**：`refreshAll()` 末尾会调 `WidgetTick.arm`，
 * 今天有课时就把第一条 tick 排到「第一节前 5 分钟」；没课则什么也不排，
 * 安静等到第二天 0:05。
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
