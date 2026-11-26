package com.gzucm.pocket.widget

import android.content.Context
import android.os.Parcel
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.RemoteViews
import androidx.test.core.app.ApplicationProvider
import com.gzucm.pocket.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.LocalDate
import java.time.LocalDateTime

/**
 * 部件 RemoteViews 的护栏测试。
 *
 * ## 这个测试存在的唯一原因
 *
 * 4x3 三天部件在真机上反复提示「加载时出现问题」，而这类失败**不会**在
 * 编译期、也不会在纯逻辑单测里暴露：它发生在宿主 apply RemoteViews 的那一刻 ——
 * 布局 inflate 不了、某个 id 不存在、或者某个 setInt 的方法名不是
 * @RemotableViewMethod。没有设备时唯一的验证手段就是把 apply 在 JVM 上跑一遍。
 *
 * 覆盖 6 个部件 × 5 种数据（没同步过 / 空快照 / 今天有课 / 今天没课 /
 * 跨节连堂课），任何一条路径抛异常都算失败。以后改布局先跑它。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-xhdpi")
class WidgetApplyTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    private val monday: LocalDate = LocalDate.of(2026, 9, 7)

    /** 周一的 10:40：中医诊断学正在上，跨节连堂课也在 */
    private val now: LocalDateTime = monday.atTime(10, 40)

    private val providers: List<BaseWidgetProvider> = listOf(
        CurrentCourseWidget(),
        TodayWidget(),
        TodayHorizontalWidget(),
        TimelineWidget(),
        ThreeDaysWidget(),
        WeekWidget(),
    )

    private fun s(
        name: String,
        weekday: Int,
        startUnit: Int,
        endUnit: Int,
        startTime: String,
        endTime: String,
        week: Int = 1,
        room: String = "综教楼101",
        teacher: String = "张伟",
    ) = SnapshotSession(
        week = week,
        weekday = weekday,
        startUnit = startUnit,
        endUnit = endUnit,
        startTime = startTime,
        endTime = endTime,
        name = name,
        room = room,
        teacher = teacher,
    )

    private fun snapshot(vararg sessions: SnapshotSession) = WidgetSnapshotData(
        semesterStart = monday.toString(),
        totalWeeks = 20,
        sessions = sessions.toList(),
    )

    /** 五种数据：每一种都可能走到某个部件里没被走过的那条分支 */
    private fun datasets(): List<Pair<String, WidgetSnapshotData?>> = listOf(
        "没同步过" to null,
        "空快照" to snapshot(),
        "今天有课" to snapshot(
            s("中药学", weekday = 1, startUnit = 1, endUnit = 2, startTime = "08:30", endTime = "10:00"),
            s("中医诊断学", weekday = 1, startUnit = 3, endUnit = 4, startTime = "10:20", endTime = "11:50"),
        ),
        // sessions 非空但今天（周一）一条都没有 → 走「今天没课了」那条路径
        "今天没课" to snapshot(
            s("方剂学", weekday = 2, startUnit = 1, endUnit = 2, startTime = "08:30", endTime = "10:00"),
        ),
        // 跨 3 节 + 同一天再一节：周视图的 first/middle/last 全部走到
        "跨节连堂课" to snapshot(
            s("外科学(2)", weekday = 1, startUnit = 1, endUnit = 3, startTime = "08:30", endTime = "11:00"),
            s("中医骨伤科学", weekday = 1, startUnit = 4, endUnit = 4, startTime = "11:10", endTime = "11:50"),
            s("就业指导", weekday = 3, startUnit = 5, endUnit = 6, startTime = "13:10", endTime = "14:40"),
        ),
    )

    @Test
    fun `六个部件在五种数据下都能inflate并apply`() {
        datasets().forEach { (label, snapshot) ->
            providers.forEach { provider ->
                val name = provider.javaClass.simpleName
                val views = provider.build(context, 1, snapshot, now)
                // apply 会真的 inflate 布局并逐条执行 action：id 不存在、方法名
                // 不是 @RemotableViewMethod、布局里混进了裸 View，都在这里炸
                val root = views.apply(context, FrameLayout(context))
                assertNotNull("$name / $label 没有 inflate 出根视图", root)
                assertAllViewsRemotableView(root, name, label)
            }
        }
    }

    @Test
    fun `三日部件不允许出现ScrollView`() {
        // 上一版的「加载时出现问题」就是 69 个视图 + ScrollView 的深嵌套。
        // 这条断言把「别再放回去」写死在这里。
        val views = ThreeDaysWidget().build(context, 1, datasets()[2].second, now)
        val root = views.apply(context, FrameLayout(context))
        val scrollViews = ArrayList<View>()
        walk(root) { if (it is android.widget.ScrollView) scrollViews += it }
        assertEquals("三日部件布局里不能有 ScrollView", 0, scrollViews.size)
    }

    @Test
    fun `RemoteViews可以parcel往返且不超过binder限制`() {
        datasets().forEach { (label, snapshot) ->
            providers.forEach { provider ->
                val name = provider.javaClass.simpleName
                val views = provider.build(context, 1, snapshot, now)

                val parcel = Parcel.obtain()
                try {
                    views.writeToParcel(parcel, 0)
                    val size = parcel.dataSize()
                    assertTrue(
                        "$name / $label 的 RemoteViews 有 $size 字节，超过 binder 的 1MB 上限",
                        size < (1 shl 20),
                    )

                    parcel.setDataPosition(0)
                    val restored = RemoteViews.CREATOR.createFromParcel(parcel)
                    assertNotNull("$name / $label 反序列化失败", restored)
                    // 解出来的 action 也要能用 —— 只 write 不 read 的话，
                    // 序列化里漏字段的 bug 照样发现不了
                    assertNotNull(restored.apply(context, FrameLayout(context)))
                } finally {
                    parcel.recycle()
                }
            }
        }
    }

    /**
     * 这条是护栏的「自检」：故意调一个不存在的方法名，apply 必须报错。
     *
     * 为什么值得写：如果 Robolectric 的 apply 把所有失败都吞掉，上面那三个
     * 测试就是永远绿的假护栏 —— 真机上照样「加载时出现问题」，而 CI 一片绿。
     * 这条红了就说明护栏失效，要去换验证手段（比如真机跑一遍 6 个部件）。
     */
    @Test
    fun `护栏自检_非法方法名必须让apply失败`() {
        val views = RemoteViews(context.packageName, R.layout.widget_current)
        views.setInt(R.id.widget_root, "setThisMethodDoesNotExist", 1)
        val thrown = runCatching { views.apply(context, FrameLayout(context)) }.exceptionOrNull()
        assertNotNull("apply 没有对非法方法名报错，说明这个护栏测试抓不到真问题", thrown)
    }

    /**
     * RemoteViews 的 inflate 只认一部分类。
     *
     * 白名单是 AOSP `LayoutInflater` 里 `@RemoteView` 注解覆盖的那批
     * android.widget 类；自定义 View / ViewStub / 裸 View 在这里就会失败。
     */
    private fun assertAllViewsRemotableView(root: View, widget: String, label: String) {
        walk(root) { v ->
            val cls = v.javaClass.name
            assertTrue(
                "$widget / $label 里出现了 RemoteViews 不支持的视图：$cls",
                cls in REMOTE_VIEW_CLASSES,
            )
        }
    }

    private fun walk(view: View, visit: (View) -> Unit) {
        visit(view)
        if (view is ViewGroup) {
            for (i in 0 until view.childCount) walk(view.getChildAt(i), visit)
        }
    }

    private companion object {
        val REMOTE_VIEW_CLASSES = setOf(
            "android.widget.FrameLayout",
            "android.widget.LinearLayout",
            "android.widget.RelativeLayout",
            "android.widget.GridLayout",
            "android.widget.TextView",
            "android.widget.ImageView",
            "android.widget.ImageButton",
            "android.widget.Button",
            "android.widget.ListView",
            "android.widget.GridView",
            "android.widget.ScrollView",
            "android.widget.ProgressBar",
            "android.widget.AnalogClock",
            "android.widget.ViewFlipper",
            "android.widget.ViewAnimator",
            "android.widget.AdapterViewFlipper",
            "android.widget.StackView",
        )
    }
}
