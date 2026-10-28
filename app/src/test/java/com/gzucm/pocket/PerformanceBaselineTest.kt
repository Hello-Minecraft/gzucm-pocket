package com.gzucm.pocket

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import com.gzucm.pocket.core.CourseGrade
import com.gzucm.pocket.ui.grades.GradesScreen
import com.gzucm.pocket.ui.theme.GzucmTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.system.measureTimeMillis

/**
 * 性能基线：测量成绩页在「大量课程」下的组合耗时。
 *
 * 用 300 门课（真实四年制医学生大致量级）压测，记录首次组合与多次重组的耗时。
 * 优化前后各跑一次，用数字说话。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-xhdpi")
class PerformanceBaselineTest {

    @get:Rule
    val rule = createComposeRule()

    private fun makeGrades(n: Int): List<CourseGrade> = (1..n).map { i ->
        CourseGrade(
            term = "202${i % 5}-202${i % 5 + 1}-${if (i % 2 == 0) 1 else 2}",
            name = "课程名称第 ${i} 号",
            score = 60.0 + (i % 40),
            gpa = 1.0 + (i % 40) / 10.0,
            credit = 1.0 + (i % 5),
            category = listOf("专业课", "基础课", "实践课", "通识课")[i % 4],
            nature = "考试",
            remark = "",
            passed = (i % 37) != 0,
        )
    }

    @Test
    fun `成绩页 300 门课的渲染耗时`() {
        val data = makeGrades(300)
        var composeMs = 0L
        var idleMs = 0L

        composeMs = measureTimeMillis {
            rule.setContent {
                GzucmTheme(darkTheme = true) {
                    Surface(Modifier.fillMaxSize()) {
                        GradesScreen(
                            grades = data,
                            levels = emptyList(),
                            fitness = emptyList(),
                            loading = false,
                            onRefresh = {},
                        )
                    }
                }
            }
        }
        idleMs = measureTimeMillis { rule.waitForIdle() }

        println("PERF grades=300 compose=${composeMs}ms idle=${idleMs}ms")
    }

    @Test
    fun `成绩页 83 门课（真实量级）的渲染耗时`() {
        val data = makeGrades(83)
        var composeMs = 0L
        composeMs = measureTimeMillis {
            rule.setContent {
                GzucmTheme(darkTheme = true) {
                    Surface(Modifier.fillMaxSize()) {
                        GradesScreen(
                            grades = data,
                            levels = emptyList(),
                            fitness = emptyList(),
                            loading = false,
                            onRefresh = {},
                        )
                    }
                }
            }
        }
        val idle = measureTimeMillis { rule.waitForIdle() }
        println("PERF grades=83 compose=${composeMs}ms idle=${idle}ms")
    }

    /** 纯格式化开销对比：这正是滚动时每帧重复执行的部分 */
    @Test
    fun `格式化 10000 次的开销对比`() {
        val values = (1..10000).map { 60.0 + (it % 40) + (it % 7) / 10.0 }

        val old = measureTimeMillis {
            var sink = 0
            values.forEach { v ->
                sink += "%.1f".format(v).length
                sink += "%.2f".format(v).length
            }
            check(sink > 0)
        }

        val optimized = measureTimeMillis {
            var sink = 0
            values.forEach { v ->
                sink += com.gzucm.pocket.ui.components.Fmt.d1(v).length
                sink += com.gzucm.pocket.ui.components.Fmt.d2(v).length
            }
            check(sink > 0)
        }

        println("PERF format 20k calls: StringFormat=${old}ms Fmt=${optimized}ms")
    }
}