package com.gzucm.pocket.widget

import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.widget.FrameLayout
import androidx.test.core.app.ApplicationProvider
import com.gzucm.pocket.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.LocalDate
import java.time.LocalDateTime

/**
 * 4x3 时间轴部件的**真实高度**测量（Robolectric 里强制指定卡片高度）。
 *
 * ## 为什么要在 JVM 上量
 *
 * `android:minHeight="180dp"` 只是给宿主看的「我要几个格」，宿主真给多高
 * 外部管不着。7 行全是 `height=0dp + weight=1`，卡片一矮、行高就跟着塌，
 * 而右侧「地点 + 老师」是**两行固定字号**的文字块 —— 行高塌到它下面时
 * 文字会被 LinearLayout 裁掉，这类问题编译期、纯逻辑测试都看不出来。
 *
 * 这里把根布局按 EXACTLY 尺寸 measure/layout，读的是布局真实分配出来的
 * 像素高度，不是估算。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-xhdpi")
class WidgetTimelineHeightTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    private val monday: LocalDate = LocalDate.of(2026, 9, 7)

    /** 早上 7 点：今天 7 门课全都还没上，todayCourses 一条不滤 */
    private val now: LocalDateTime = monday.atTime(7, 0)

    private val rowIds = intArrayOf(
        R.id.tl1, R.id.tl2, R.id.tl3, R.id.tl4, R.id.tl5, R.id.tl6, R.id.tl7,
    )
    private val sideIds = intArrayOf(
        R.id.tl1_side, R.id.tl2_side, R.id.tl3_side, R.id.tl4_side,
        R.id.tl5_side, R.id.tl6_side, R.id.tl7_side,
    )

    private fun s(name: String, startUnit: Int, startTime: String, endTime: String) =
        SnapshotSession(
            week = 1,
            weekday = 1,
            startUnit = startUnit,
            endUnit = startUnit + 1,
            startTime = startTime,
            endTime = endTime,
            name = name,
            room = "综教楼101",
            teacher = "张伟",
        )

    /** 7 门课，时间从早到晚都不重叠；每门都有地点和老师（最坏情况，右侧两行都画） */
    private fun sevenCourses() = WidgetSnapshotData(
        semesterStart = monday.toString(),
        totalWeeks = 20,
        sessions = listOf(
            s("中医诊断学", 1, "08:30", "10:00"),
            s("中药学", 3, "10:20", "11:50"),
            s("方剂学", 5, "12:00", "13:30"),
            s("中医骨伤科学", 7, "13:40", "15:10"),
            s("外科学", 9, "15:20", "16:50"),
            s("针灸学", 11, "17:00", "18:30"),
            s("推拿学", 12, "19:00", "20:30"),
        ),
    )

    private fun dp(v: Int): Int =
        (v * context.resources.displayMetrics.density).toInt()

    private val density: Float get() = context.resources.displayMetrics.density
    private fun px2dp(px: Int) = px / density.toDouble()

    private fun layoutAt(root: View, heightDp: Int) {
        val w = dp(330)
        val h = dp(heightDp)
        root.measure(
            View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.EXACTLY),
        )
        root.layout(0, 0, w, h)
    }

    /** 7 行全显示时每行分到多少：布局本身的上限，和时间轴行数策略无关 */
    private fun measureAllSeven(heightDp: Int): List<Double> {
        val root = LayoutInflater.from(context)
            .inflate(R.layout.widget_timeline, FrameLayout(context), false)
        layoutAt(root, heightDp)
        return rowIds.map { px2dp(root.findViewById<View>(it).height) }
    }

    /**
     * 注册一个带格子高度的部件实例，让 Provider 的 maxRows 读到宿主给的值。
     *
     * 生产路径读的是 `getAppWidgetOptions(id).getInt(OPTION_APPWIDGET_MIN_HEIGHT)`，
     * 所以测试必须把 options 真的写进 ShadowAppWidgetManager，不能直接传行数 ——
     * 否则测的就不是「高度 → 行数」这条链路了。
     */
    @Suppress("DEPRECATION") // putWidgetInfo 是 Robolectric 的公开登记入口，没有替代品
    private fun widgetWithHeight(heightDp: Int): Int {
        val mgr = AppWidgetManager.getInstance(context)
        val id = 900 + heightDp
        val info = android.appwidget.AppWidgetProviderInfo().apply {
            provider = ComponentName(context, TimelineWidget::class.java)
        }
        org.robolectric.Shadows.shadowOf(mgr).putWidgetInfo(id, info)
        mgr.updateAppWidgetOptions(
            id,
            Bundle().apply {
                putInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT, heightDp)
            },
        )
        return id
    }

    private data class Shot(
        val cardDp: Int,
        /** 实际画出来的行高（dp） */
        val rows: List<Double>,
        /** 右侧两行文字块的自然高度（dp）；GONE 的记 0 */
        val sideNeed: List<Double>,
    ) {
        val visible: List<Int> get() = rows.indices.filter { rows[it] > 0.0 }
        val clipped: List<Int>
            get() = visible.filter { sideNeed[it] > rows[it] + 0.5 }
    }

    /** 走 Provider 的真实渲染路径（含按宿主高度算出的行数截断） */
    private fun measureWidget(heightDp: Int): Shot {
        val views = TimelineWidget().build(context, widgetWithHeight(heightDp), sevenCourses(), now)
        val root = views.apply(context, FrameLayout(context))
            ?: error("widget_timeline inflate 失败")
        layoutAt(root, heightDp)

        val rows = rowIds.map { px2dp(root.findViewById<View>(it).height) }
        val sideNeed = sideIds.map { id ->
            val side = root.findViewById<View>(id)
            if (side.visibility == View.GONE) {
                0.0
            } else {
                // 自然高度：看文字块**想**要多高，和行高差多少
                side.measure(
                    View.MeasureSpec.makeMeasureSpec(dp(200), View.MeasureSpec.AT_MOST),
                    View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
                )
                px2dp(side.measuredHeight)
            }
        }
        return Shot(heightDp, rows, sideNeed)
    }

    private fun fmt(list: List<Double>) = list.joinToString(" / ") { "%.1f".format(it) }

    @Test
    fun `180dp与267dp下量行高并检查右侧文字是否被裁`() {
        val seven180 = measureAllSeven(180)
        val seven267 = measureAllSeven(267)
        val w180 = measureWidget(180)
        val w267 = measureWidget(267)
        println("布局 7 行全开 180dp 行高[${fmt(seven180)}]")
        println("布局 7 行全开 267dp 行高[${fmt(seven267)}]")
        println("Provider 180dp 行高[${fmt(w180.rows)}] 右侧需要[${fmt(w180.sideNeed)}]")
        println("Provider 267dp 行高[${fmt(w267.rows)}] 右侧需要[${fmt(w267.sideNeed)}]")

        // 生产路径：180dp 下画的每一行都必须装得下「地点 + 老师」两行，且留出余量
        w180.visible.forEach { i ->
            assertTrue(
                "180dp 下第 ${i + 1} 行只有 ${"%.1f".format(w180.rows[i])}dp",
                w180.rows[i] >= 30.0,
            )
        }
        assertTrue(
            "180dp 下右侧两行文字被裁：行高[${fmt(w180.rows)}] 需要[${fmt(w180.sideNeed)}]",
            w180.clipped.isEmpty(),
        )
        // 用户设备 4x3 格 = 267dp：行高要舒服，不能贴着文字
        w267.visible.forEach { i ->
            assertTrue(
                "267dp 下第 ${i + 1} 行只有 ${"%.1f".format(w267.rows[i])}dp",
                w267.rows[i] >= 30.0,
            )
        }
        assertTrue(
            "267dp 下右侧两行文字被裁：行高[${fmt(w267.rows)}] 需要[${fmt(w267.sideNeed)}]",
            w267.clipped.isEmpty(),
        )
    }

    @Test
    fun `行数由宿主高度决定_180dp四行_267dp七行`() {
        assertEquals(4, WidgetRender.timelineRowsFor(180))
        assertEquals(7, WidgetRender.timelineRowsFor(267))

        val shot180 = measureWidget(180)
        // 180dp 只放得下 4 行：第 5 行起必须 GONE（高度 0），不能留半截空行
        assertEqualsNull(shot180.rows.drop(4))
        assertTrue(
            "180dp 应画 4 行，实际 ${shot180.visible.size} 行",
            shot180.visible.size == 4,
        )

        val shot267 = measureWidget(267)
        // 267dp 是小米 14 的 4x3 格：7 门课全都要显示，不能还停在 4 行
        assertTrue(
            "267dp 应画 7 行，实际 ${shot267.visible.size} 行",
            shot267.visible.size == 7,
        )
    }

    private fun assertEqualsNull(rest: List<Double>) {
        rest.forEach {
            assertTrue("超出已算行数的行还占着 ${"%.1f".format(it)}dp", it == 0.0)
        }
    }
}
