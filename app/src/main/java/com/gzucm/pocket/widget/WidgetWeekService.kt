package com.gzucm.pocket.widget

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.widget.RemoteViews
import android.widget.RemoteViewsService
import androidx.core.content.ContextCompat
import com.gzucm.pocket.R
import com.gzucm.pocket.core.SlotTimes
import java.time.LocalDateTime

/**
 * 周视图的列表数据源。
 *
 * ## 为什么必须走 RemoteViewsService
 *
 * 周视图的正文是「12 节」的竖直列表，而 RemoteViews 不能 addView，也不能
 * 程序化滚动（`setScrollY` 不是 @RemotableViewMethod，宿主 apply 时抛
 * ActionException）。ListView + RemoteViewsService 是唯一同时满足
 * 「内容动态」和「能滚到第 N 行」（`RemoteViews.setScrollPosition`）的方案。
 *
 * ## 一个实例 = 一个部件
 *
 * `setRemoteAdapter` 传的 Intent 里带 EXTRA_APPWIDGET_ID 和唯一 data，
 * 宿主据此为每个部件实例维护独立的 factory。
 */
class WidgetWeekService : RemoteViewsService() {

    override fun onGetViewFactory(intent: Intent): RemoteViewsFactory =
        WeekRowFactory(applicationContext)
}

/**
 * 一行 = 一节（跨 7 天）。
 *
 * ## 快照什么时候读
 *
 * **只在 [onDataSetChanged] 里读一次**，之后 [getCount] / [getViewAt]（12 行，
 * 每行 7 格 = 84 次调用）全用内存里的 [grid]。SharedPreferences + JSON 解析
 * 放在 getViewAt 里会变成 84 次磁盘读 —— 部件刷新就会被拖到 ANR 边缘。
 *
 * onDataSetChanged 由宿主在「首次绑定」和「收到 notifyAppWidgetViewDataChanged」
 * 时调用，所以 WeekWidget 每次 updateAppWidget 后都会补一次通知，
 * 否则这里会一直用旧的快照。
 */
private class WeekRowFactory(private val context: Context) : RemoteViewsService.RemoteViewsFactory {

    /** 渲染要用的网格，null = 还没加载过 */
    @Volatile
    private var grid: WeekGrid? = null

    /** 当前节次的行号（0 起），和 [grid] 同一时刻算出来 */
    @Volatile
    private var currentRow = 0

    /**
     * factory 在每个 binder 线程上单线程回调，这两个钩子都在里面跑，不用加锁。
     *
     * onCreate 也 reload 一次（规范上首次绑定会紧跟 onDataSetChanged，理论上
     * 只需要后者）：个别 ROM 会先 getCount 再调 onDataSetChanged，那时
     * grid 还是 null，列表会先白一下。多读一次快照换掉这个闪白，值得。
     */
    override fun onCreate() = reload()

    override fun onDataSetChanged() = reload()

    override fun onDestroy() {
        grid = null
    }

    private fun reload() {
        val now = LocalDateTime.now()
        grid = WidgetRender.weekGrid(WidgetSnapshot.load(context), now)
        currentRow = WidgetRender.currentRow(now)
    }

    /** 恒为 12：没课的节次也占一行，行号才等于节次，定位才算得准 */
    override fun getCount(): Int = WeekUnitCount

    override fun getViewAt(position: Int): RemoteViews {
        val row = RemoteViews(context.packageName, R.layout.widget_week_row)

        // 越界（宿主偶尔会在数据刷新中途多要一行）→ 返回一条空白行，别抛异常
        if (position !in 0 until WeekUnitCount) return row

        val unit = position + 1
        val snapshot = grid

        // 左列：节次号 + 上课时间，两行
        val time = SlotTimes[unit].orEmpty()
        row.setTextViewText(R.id.wk_row_unit, if (time.isBlank()) "$unit" else "$unit\n$time")

        // 右边 7 格：日 一 二 三 四 五 六（和表头一致，周日在前）
        for (col in 0..6) {
            val cellId = CELL_IDS[col]
            val cell = snapshot?.cell(unit, col)
            when {
                // 空节：显式清掉背景和文字。ListView 复用 view，
                // 不主动清就会带着上一行/上一次刷新的颜色
                cell == null || cell.colorIndex < 0 -> {
                    row.setTextViewText(cellId, "")
                    row.setInt(cellId, "setBackgroundResource", 0)
                }

                cell.isStart -> {
                    row.setTextViewText(cellId, listOfNotNull(cell.time, cell.name).joinToString("\n"))
                    row.setInt(cellId, "setBackgroundResource", CELL_BG[cell.colorIndex])
                    row.setTextColor(
                        cellId,
                        ContextCompat.getColor(context, CELL_TEXT[cell.colorIndex]),
                    )
                }

                // 跨节课的后续节：同色、无字，和起始格连成一个高块
                else -> {
                    row.setTextViewText(cellId, "")
                    row.setInt(cellId, "setBackgroundResource", CELL_BG[cell.colorIndex])
                }
            }
        }

        // ── 当前节次的指示 ──────────────────────────────────────────
        //
        // 精度只做到「一节课」，不做分钟级：分钟级要把线画在行的 0%~100% 之间，
        // 那是绝对定位，ListView 给不了（每行是一个独立 RemoteViews，
        // 拿不到整行像素高度的百分比），只能换回 ScrollView —— 而 ScrollView
        // 又没法程序化定位。两害相权取整节精度：用户要的是「我现在在哪一节」。
        val isNow = position == currentRow
        row.setInt(
            R.id.wk_row_line,
            "setBackgroundColor",
            if (isNow) ContextCompat.getColor(context, R.color.widget_accent) else Color.TRANSPARENT,
        )
        // 整行微微提亮 + 节次号跟着变主题色，让「当前」在两三米外也看得出
        row.setInt(
            R.id.wk_row_body,
            "setBackgroundColor",
            if (isNow) ContextCompat.getColor(context, R.color.widget_week_row_now) else Color.TRANSPARENT,
        )
        row.setTextColor(
            R.id.wk_row_unit,
            ContextCompat.getColor(
                context,
                if (isNow) R.color.widget_accent else R.color.widget_text_dim,
            ),
        )

        return row
    }

    override fun getLoadingView(): RemoteViews? = null

    override fun getViewTypeCount(): Int = 1

    override fun hasStableIds(): Boolean = true

    override fun getItemId(position: Int): Long = position.toLong()

    private companion object {
        val CELL_IDS = intArrayOf(
            R.id.wk_row_c0, R.id.wk_row_c1, R.id.wk_row_c2, R.id.wk_row_c3,
            R.id.wk_row_c4, R.id.wk_row_c5, R.id.wk_row_c6,
        )
        val CELL_BG = intArrayOf(
            R.drawable.widget_week_1, R.drawable.widget_week_2,
            R.drawable.widget_week_3, R.drawable.widget_week_4,
            R.drawable.widget_week_5, R.drawable.widget_week_6,
            R.drawable.widget_week_7, R.drawable.widget_week_8,
        )
        val CELL_TEXT = intArrayOf(
            R.color.widget_c1_text, R.color.widget_c2_text,
            R.color.widget_c3_text, R.color.widget_c4_text,
            R.color.widget_c5_text, R.color.widget_c6_text,
            R.color.widget_c7_text, R.color.widget_c8_text,
        )
    }
}
