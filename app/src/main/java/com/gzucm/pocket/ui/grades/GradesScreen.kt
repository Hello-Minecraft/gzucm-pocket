package com.gzucm.pocket.ui.grades

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width

import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.Canvas
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import kotlin.math.roundToInt
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.gzucm.pocket.core.CourseGrade
import com.gzucm.pocket.ui.components.LocalFloatingBarInset
import com.gzucm.pocket.core.FitnessGrade
import com.gzucm.pocket.core.LevelGrade
import com.gzucm.pocket.ui.components.Fmt

internal enum class Tab(val label: String) {
    Course("课程成绩"), Level("考级"), Fitness("体测")
}

@Composable
fun GradesScreen(
    grades: List<CourseGrade>,
    levels: List<LevelGrade>,
    fitness: List<FitnessGrade>,
    loading: Boolean,
    progressDone: Int = 0,
    progressTotal: Int = 0,
    stage: String? = null,
    /** 上次成功抓取的时间戳，0 表示还没成功过 */
    updatedAt: Long = 0L,
    onRefresh: () -> Unit,
    /** 登录已失效：成绩空着不是「没有成绩」，是看不了 */
    needLogin: Boolean = false,
    onOpenLogin: () -> Unit = {},
    /**
     * 初始标签页索引：0=课程成绩 1=考级 2=体测。
     *
     * 用 Int 而不是 Tab：Tab 是 private enum，公开函数的参数不能用它
     * （编译报 "Function 'public' exposes its 'internal' parameter type"）。
     * 给截图测试切到体测页用，正常运行保持默认 0。
     */
    initialTabIndex: Int = 0,
) {
    var tab by remember { mutableStateOf(Tab.entries.getOrElse(initialTabIndex) { Tab.Course }) }

    Column(
        Modifier
            .fillMaxSize()
            .statusBarsPadding(),
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 18.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Tab.entries.forEach { t ->
                val active = tab == t
                Box(
                    Modifier
                        .clip(RoundedCornerShape(9.dp))
                        .background(
                            if (active) MaterialTheme.colorScheme.primary.copy(alpha = 0.16f)
                            else Color.Transparent,
                        )
                        .border(
                            0.6.dp,
                            if (active) MaterialTheme.colorScheme.primary.copy(alpha = 0.4f)
                            else MaterialTheme.colorScheme.outlineVariant,
                            RoundedCornerShape(9.dp),
                        )
                        .clickable { tab = t }
                        .padding(horizontal = 13.dp, vertical = 7.dp),
                ) {
                    Text(
                        t.label,
                        fontSize = 12.5.sp,
                        fontWeight = if (active) FontWeight.Medium else FontWeight.Normal,
                        color = if (active) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            Spacer(Modifier.weight(1f))

            if (updatedAt > 0) {
                Text(
                    java.text.SimpleDateFormat("MM-dd HH:mm", java.util.Locale.CHINA)
                        .format(java.util.Date(updatedAt)),
                    fontSize = 9.5.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.55f),
                )
                Spacer(Modifier.width(4.dp))
            }
            Row(
                Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .clickable(enabled = !loading) { onRefresh() }
                    .padding(horizontal = 7.dp, vertical = 5.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (loading) {
                    CircularProgressIndicator(Modifier.size(11.dp), strokeWidth = 1.5.dp)
                    Spacer(Modifier.width(4.dp))
                }
                Text(
                    if (loading) "更新中" else "更新",
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.Medium,
                )
            }
        }

        when {
            loading && grades.isEmpty() -> {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        CircularProgressIndicator(Modifier.size(28.dp), strokeWidth = 2.5.dp)
                        if (progressTotal > 0) {
                            Spacer(Modifier.height(14.dp))
                            Text(
                                "正在获取 $progressDone/$progressTotal",
                                fontSize = 11.5.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            if (!stage.isNullOrBlank()) {
                                Spacer(Modifier.height(3.dp))
                                Text(
                                    stage,
                                    fontSize = 10.5.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                                )
                            }
                            // 与课表一致的细进度条
                            Spacer(Modifier.height(10.dp))
                            Box(
                                Modifier
                                    .width(120.dp)
                                    .height(3.dp)
                                    .clip(RoundedCornerShape(2.dp))
                                    .background(MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.15f)),
                            ) {
                                Box(
                                    Modifier
                                        .fillMaxWidth(progressDone.toFloat() / progressTotal)
                                        .height(3.dp)
                                        .clip(RoundedCornerShape(2.dp))
                                        .background(MaterialTheme.colorScheme.primary),
                                )
                            }
                        }
                    }
                }
            }

            tab == Tab.Course -> CourseTab(grades, needLogin, onOpenLogin)
            tab == Tab.Level -> LevelTab(levels, needLogin, onOpenLogin)
            else -> FitnessTab(fitness, needLogin, onOpenLogin)
        }
    }
}

@Composable
private fun CourseTab(
    grades: List<CourseGrade>,
    needLogin: Boolean = false,
    onOpenLogin: () -> Unit = {},
) {
    if (grades.isEmpty()) {
        EmptyBox("暂无成绩", needLogin, onOpenLogin)
        return
    }
    // 这三处原本每次重组都重算：分组排序、三个统计量。
    // 用 remember 挂在 grades 上，列表不变就不重算。
    val byTerm = remember(grades) {
        grades.groupBy { it.term }.toList().sortedByDescending { it.first }
    }
    // 排序结果单独缓存，避免 LazyColumn 每次重组重新排序
    val sortedByTerm = remember(grades) {
        byTerm.associate { (term, list) -> term to list.sortedByDescending { it.score } }
    }
    val summary = remember(grades) {
        // 绩点按「必修 + 限选」算（任选不计）；
        // 但「已修学分」显示全部及格学分，那是实打实修完的，不该被口径裁掉
        val (gpa, _) = com.gzucm.pocket.core.Gpa.weighted(grades)
        val passedCredit = grades.filter { it.passed }.sumOf { it.credit }
        val avgScore = if (grades.isNotEmpty()) grades.sumOf { it.score } / grades.size else 0.0
        Triple(passedCredit, gpa, avgScore)
    }
    // 哪张图展开着：0=无 1=绩点折线 2=成绩柱状
    var expanded by remember { mutableStateOf(0) }
    val totalCredit = summary.first
    val weightedGpa = summary.second
    val avgScore = summary.third

    BoxWithConstraints(Modifier.fillMaxSize()) {
    val wide = maxWidth >= 840.dp
    val hPad = if (wide) 32.dp else 18.dp

    /*
     * 宽屏：左列 40% 放指标卡 + 展开的图表 + 学分构成，右列单独滚动成绩列表。
     *
     * 这样右列可以长条滚下去翻各学期的课，左列那些「总览性质」的内容
     * 一直留在视野里 —— 翻到 2024 学期时还能看见加权绩点是多少。
     *
     * 两列各自 LazyColumn 独立滚动。外层套 verticalScroll 再镶 LazyColumn
     * 会因无限高度约束直接崩，所以分栏时不能有外层滚动。
     */
    if (wide) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(24.dp),
        ) {
            LazyColumn(
                Modifier.weight(0.4f),
                verticalArrangement = Arrangement.spacedBy(3.dp),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(
                    top = 2.dp, bottom = LocalFloatingBarInset.current + 12.dp,
                ),
            ) {
                item(key = "summary") {
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .padding(bottom = 14.dp),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        StatPill(
                            "加权绩点", Fmt.d2(weightedGpa), Modifier.weight(1f),
                            onClick = { expanded = if (expanded == 1) 0 else 1 },
                            selected = expanded == 1,
                        )
                        StatPill(
                            "平均分", Fmt.trim1(avgScore), Modifier.weight(1f),
                            onClick = { expanded = if (expanded == 2) 0 else 2 },
                            selected = expanded == 2,
                        )
                    }
                }
                item(key = "credit") {
                    StatPill("已修学分", Fmt.trim1(totalCredit), Modifier.fillMaxWidth())
                }
                // 展开的图表放在指标卡下方、学分构成上方
                if (expanded == 1) {
                    item(key = "chart_gpa") { GpaTrendCard(grades) }
                }
                if (expanded == 2) {
                    item(key = "chart_score") { ScoreBarCard(grades) }
                }
                item(key = "breakdown") {
                    Spacer(Modifier.height(10.dp))
                    CategoryBreakdown(grades)
                }
            }

            LazyColumn(
                Modifier.weight(0.6f),
                verticalArrangement = Arrangement.spacedBy(3.dp),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(
                    top = 2.dp, bottom = LocalFloatingBarInset.current + 12.dp,
                ),
            ) {
                byTerm.forEach { (term, list) ->
                    item(key = "h_$term") {
                        TermHeader(term, list)
                    }
                    items(
                        items = sortedByTerm[term].orEmpty(),
                        key = { g -> "$term|${g.name}|${g.credit}" },
                    ) { g ->
                        GradeRow(g)
                    }
                    item(key = "s_$term") { Spacer(Modifier.height(14.dp)) }
                }
            }
        }
    } else {
        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(
                start = hPad, end = hPad, top = 2.dp,
                bottom = LocalFloatingBarInset.current + 12.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(3.dp),
        ) {
            item(key = "summary") {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(bottom = 14.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    StatPill(
                        "加权绩点", Fmt.d2(weightedGpa), Modifier.weight(1f),
                        onClick = { expanded = if (expanded == 1) 0 else 1 },
                        selected = expanded == 1,
                    )
                    StatPill(
                        "平均分", Fmt.trim1(avgScore), Modifier.weight(1f),
                        onClick = { expanded = if (expanded == 2) 0 else 2 },
                        selected = expanded == 2,
                    )
                    // 已修学分暂不做展开
                    StatPill("已修学分", Fmt.trim1(totalCredit), Modifier.weight(1f))
                }

                if (expanded == 1) GpaTrendCard(grades)
                if (expanded == 2) ScoreBarCard(grades)
            }
            byTerm.forEach { (term, list) ->
                item(key = "h_$term") {
                    TermHeader(term, list)
                }
                items(
                    items = sortedByTerm[term].orEmpty(),
                    // 稳定的 key 让 LazyColumn 能复用 item，滚动时不重建
                    key = { g -> "$term|${g.name}|${g.credit}" },
                ) { g ->
                    GradeRow(g)
                }
                item(key = "s_$term") { Spacer(Modifier.height(14.dp)) }
            }

            item(key = "breakdown") {
                CategoryBreakdown(grades)
            }
        }
    }
    } // BoxWithConstraints
}

/** 按课程大类汇总，顺便把页面底部填满 */
@Composable
private fun CategoryBreakdown(grades: List<CourseGrade>) {
    val scheme = MaterialTheme.colorScheme
    val grouped = remember(grades) {
        grades.groupBy { it.category.ifBlank { "其他" } }
            .mapValues { (_, v) -> v.sumOf { it.credit } }
            .toList()
            .sortedByDescending { it.second }
    }
    if (grouped.isEmpty()) return
    val totalCredit = remember(grouped) { grouped.sumOf { it.second }.coerceAtLeast(0.1) }

    Column(
        Modifier
            .fillMaxWidth()
            .padding(top = 6.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(scheme.surface)
            .padding(horizontal = 16.dp, vertical = 15.dp),
    ) {
        Text(
            "学分构成",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Medium,
        )
        Spacer(Modifier.height(12.dp))
        grouped.forEach { (cat, credit) ->
            val ratio = (credit / totalCredit).toFloat()
            Column(Modifier.padding(vertical = 5.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(cat, fontSize = 12.sp, color = scheme.onSurfaceVariant)
                    Spacer(Modifier.weight(1f))
                    Text(
                        formatCredit(credit),
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Medium,
                    )
                }
                Spacer(Modifier.height(5.dp))
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(4.dp)
                        .clip(RoundedCornerShape(2.dp))
                        .background(scheme.outlineVariant.copy(alpha = 0.5f)),
                ) {
                    Box(
                        Modifier
                            .fillMaxWidth(ratio)
                            .height(4.dp)
                            .clip(RoundedCornerShape(2.dp))
                            .background(scheme.primary.copy(alpha = 0.75f)),
                    )
                }
            }
        }
    }
}

@Composable
private fun TermHeader(term: String, list: List<CourseGrade>) {
    val gpa = remember(list) { com.gzucm.pocket.core.Gpa.weighted(list).first }
    Row(
        Modifier
            .fillMaxWidth()
            .padding(top = 10.dp, bottom = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            term,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Medium,
        )
        Spacer(Modifier.width(8.dp))
        Text(
            "${list.size} 门 · 绩点 ${Fmt.d2(gpa)}",
            fontSize = 11.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun GradeRow(g: CourseGrade) {
    val scheme = MaterialTheme.colorScheme
    // 格式化结果缓存：滚动时 LazyColumn 复用 item，不重复拼字符串
    val scoreText = remember(g.score) { Fmt.trim1(g.score) }
    val creditText = remember(g.credit) { Fmt.trim1(g.credit) }
    val gpaText = remember(g.gpa) { Fmt.d1(g.gpa) }
    val color = remember(g.score) {
        scoreColor(g.score, scheme.primary, scheme.error, scheme.onSurfaceVariant)
    }

    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(11.dp))
            .background(scheme.surface)
            .padding(horizontal = 13.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                g.name,
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(3.dp))
            Row {
                if (g.category.isNotBlank()) {
                    Text(
                        g.category,
                        fontSize = 10.5.sp,
                        color = scheme.onSurfaceVariant.copy(alpha = 0.75f),
                    )
                }
                if (g.credit > 0) {
                    Text(
                        "  ·  $creditText 学分",
                        fontSize = 10.5.sp,
                        color = scheme.onSurfaceVariant.copy(alpha = 0.75f),
                    )
                }
                if (g.gpa > 0) {
                    Text(
                        "  ·  绩点 $gpaText",
                        fontSize = 10.5.sp,
                        color = scheme.onSurfaceVariant.copy(alpha = 0.75f),
                    )
                }
            }
        }
        Text(
            scoreText,
            fontSize = 19.sp,
            fontWeight = FontWeight.Medium,
            color = color,
        )
    }
}

private fun formatCredit(v: Double): String = Fmt.trim1(v)

private fun formatScore(v: Double): String = Fmt.trim1(v)

private fun scoreColor(v: Double, primary: Color, error: Color, muted: Color): Color = when {
    v >= 90 -> primary
    v >= 60 -> Color(0xFF7FA8FF)
    else -> error
}


/**
 * 各学期加权绩点折线图。
 *
 * 量程固定上限 5、下限取「最低绩点 − 1」（不低于 0）：
 * 全都画在 0–4 的话，2.8–3.3 的起伏会被压成一条近乎水平的线，看不出趋势。
 * 按个人数据裁剪纵轴，变化才明显。
 *
 * 支持左右滑动选中某个学期，日期与数值显示在图上方（同电费折线图）。
 */
@Composable
private fun GpaTrendCard(grades: List<CourseGrade>) {
    val scheme = MaterialTheme.colorScheme
    val series = remember(grades) {
        grades.filter { it.passed }
            .groupBy { it.term }
            .toSortedMap()
            .map { (term, list) -> term to com.gzucm.pocket.core.Gpa.weighted(list).first }
    }
    var sel by remember(series) { mutableIntStateOf(-1) }
    val picked = series.getOrNull(sel)

    Column(
        Modifier
            .fillMaxWidth()
            .padding(bottom = 14.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(scheme.surface)
            .padding(horizontal = 12.dp, vertical = 14.dp),
    ) {
        // 顶部读数
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (picked != null) {
                Text(picked.first, fontSize = 11.sp, fontWeight = FontWeight.Medium)
                Text(
                    Fmt.d2(picked.second),
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium,
                    color = scheme.primary,
                )
            } else {
                Text(
                    "各学期加权绩点",
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Medium,
                )
                Text(
                    "左右滑动查看",
                    fontSize = 9.5.sp,
                    color = scheme.onSurfaceVariant.copy(alpha = 0.6f),
                )
            }
        }
        Spacer(Modifier.height(10.dp))

        if (series.size < 2) {
            Text(
                "只有一个学期的数据，画不出趋势",
                fontSize = 11.sp,
                color = scheme.onSurfaceVariant,
            )
            return@Column
        }

        // 量程跟着个人数据走：上限 = 最高 + 0.5，下限 = 最低 − 0.5（不低于 0）
        // 固定 0–5 或 0–4 都会把 2.8–3.3 这种起伏压平，看不出趋势
        val minGpa = series.minOf { it.second }
        val maxGpa = series.maxOf { it.second }
        val bottom = (minGpa - 0.5).coerceAtLeast(0.0)
        var top = maxGpa + 0.5
        // 全部学期一样高时给个最小跨度，避免除零把线画到画布外
        if (top - bottom < 0.2) top = bottom + 0.2

        Canvas(
            Modifier
                .fillMaxWidth()
                .height(150.dp)
                .pointerInput(series) {
                    fun pick(x: Float, w: Int) {
                        if (series.size < 2 || w <= 0) return
                        val step = w / (series.size - 1).toFloat()
                        sel = (x / step).roundToInt().coerceIn(0, series.lastIndex)
                    }
                    detectHorizontalDragGestures(
                        onDragStart = { off -> pick(off.x, size.width) },
                        onHorizontalDrag = { ch, _ -> pick(ch.position.x, size.width); ch.consume() },
                    )
                }
                .pointerInput(series) {
                    detectTapGestures { off ->
                        if (series.size < 2) return@detectTapGestures
                        val step = size.width / (series.size - 1).toFloat()
                        val i = (off.x / step).roundToInt().coerceIn(0, series.lastIndex)
                        sel = if (sel == i) -1 else i
                    }
                },
        ) {
            val leftPad = 8f
            val rightPad = 10f
            val topPad = 10f
            val bottomPad = 8f
            val w = size.width - leftPad - rightPad
            val h = size.height - topPad - bottomPad
            val span = (top - bottom).toFloat()

            fun yOf(v: Double) = topPad + h - ((v - bottom).toFloat() / span * h)
            val stepX = w / (series.size - 1)

            // 4 条横向参考线
            for (i in 0..3) {
                val v = bottom + span * i / 3
                val y = yOf(v)
                drawLine(
                    color = scheme.onSurfaceVariant.copy(alpha = 0.12f),
                    start = Offset(leftPad, y), end = Offset(leftPad + w, y),
                    strokeWidth = 1f,
                )
            }

            val path = Path()
            series.forEachIndexed { i, (_, v) ->
                val x = leftPad + stepX * i
                val y = yOf(v)
                if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
            }
            drawPath(path, scheme.primary, style = Stroke(width = 2f))

            series.forEachIndexed { i, (_, v) ->
                drawCircle(scheme.primary, radius = 2.8f, center = Offset(leftPad + stepX * i, yOf(v)))
            }

            if (picked != null) {
                val x = leftPad + stepX * sel
                val y = yOf(picked.second)
                drawLine(
                    color = scheme.primary.copy(alpha = 0.45f),
                    start = Offset(x, topPad), end = Offset(x, topPad + h),
                    strokeWidth = 1.2f,
                )
                drawCircle(scheme.primary.copy(alpha = 0.18f), radius = 7f, center = Offset(x, y))
                drawCircle(scheme.primary, radius = 3.4f, center = Offset(x, y))
            }
        }

        Spacer(Modifier.height(6.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            series.forEach { (term, _) ->
                Text(
                    term.removePrefix("20").replace("-20", "-"),
                    fontSize = 8.sp,
                    color = scheme.onSurfaceVariant.copy(alpha = 0.6f),
                )
            }
        }
    }
}

/**
 * 每门课成绩的柱状图，从高到低排。
 *
 * 80 多门课挤一屏，柱子本身承担「分布」的职责，不标课程名；
 * 左右滑动选中某根柱子，课程名与分数显示在图上方。
 */
@Composable
private fun ScoreBarCard(grades: List<CourseGrade>) {
    val scheme = MaterialTheme.colorScheme
    val sorted = remember(grades) { grades.sortedByDescending { it.score } }
    var sel by remember(sorted) { mutableIntStateOf(-1) }
    val picked = sorted.getOrNull(sel)

    Column(
        Modifier
            .fillMaxWidth()
            .padding(bottom = 14.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(scheme.surface)
            .padding(horizontal = 12.dp, vertical = 14.dp),
    ) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (picked != null) {
                Text(
                    picked.name,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    Fmt.trim1(picked.score),
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium,
                    color = scheme.primary,
                )
            } else {
                Text("每门课成绩 · 从高到低", fontSize = 12.sp, fontWeight = FontWeight.Medium)
                Text(
                    "左右滑动查看",
                    fontSize = 9.5.sp,
                    color = scheme.onSurfaceVariant.copy(alpha = 0.6f),
                )
            }
        }
        Spacer(Modifier.height(10.dp))

        Canvas(
            Modifier
                .fillMaxWidth()
                .height(120.dp)
                .pointerInput(sorted) {
                    fun pick(x: Float, w: Int) {
                        if (sorted.isEmpty() || w <= 0) return
                        val slot = w.toFloat() / sorted.size
                        sel = (x / slot).toInt().coerceIn(0, sorted.lastIndex)
                    }
                    detectHorizontalDragGestures(
                        onDragStart = { off -> pick(off.x, size.width) },
                        onHorizontalDrag = { ch, _ -> pick(ch.position.x, size.width); ch.consume() },
                    )
                }
                .pointerInput(sorted) {
                    detectTapGestures { off ->
                        if (sorted.isEmpty()) return@detectTapGestures
                        val slot = size.width.toFloat() / sorted.size
                        val i = (off.x / slot).toInt().coerceIn(0, sorted.lastIndex)
                        sel = if (sel == i) -1 else i
                    }
                },
        ) {
            if (sorted.isEmpty()) return@Canvas
            val n = sorted.size
            val gap = 1.2f
            val barW = ((size.width - gap * (n - 1)) / n).coerceAtLeast(1f)
            val h = size.height

            sorted.forEachIndexed { i, g ->
                val ratio = (g.score / 100.0).coerceIn(0.0, 1.0).toFloat()
                val bh = h * ratio
                val x = i * (barW + gap)
                val alpha = if (sel == i) 1f else 0.35f + 0.45f * ratio
                drawRoundRect(
                    color = (if (g.score < 60) scheme.error else scheme.primary).copy(alpha = alpha),
                    topLeft = Offset(x, h - bh),
                    size = Size(barW, bh),
                    cornerRadius = CornerRadius(barW / 3f, barW / 3f),
                )
            }
            // 60 分线
            drawLine(
                color = scheme.onSurfaceVariant.copy(alpha = 0.22f),
                start = Offset(0f, h * 0.4f), end = Offset(size.width, h * 0.4f),
                strokeWidth = 1f,
            )
        }

        Spacer(Modifier.height(8.dp))
        val top1 = sorted.firstOrNull()
        val low1 = sorted.lastOrNull()
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(
                "最高 ${top1?.let { Fmt.trim1(it.score) } ?: "—"}",
                fontSize = 9.5.sp,
                color = scheme.onSurfaceVariant.copy(alpha = 0.7f),
            )
            Text(
                "最低 ${low1?.let { Fmt.trim1(it.score) } ?: "—"}",
                fontSize = 9.5.sp,
                color = scheme.onSurfaceVariant.copy(alpha = 0.7f),
            )
        }
    }
}

/**
 * 统计卡片。
 *
 * 可点的那两个（加权绩点 / 平均分）加了一圈描边 + 点击涟漪，
 * 否则跟「已修学分」长得一样，没人知道能点。
 * 描边用 Material3 的 outline 语义色，选中时换成主色并加深。
 */
@Composable
private fun StatPill(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    selected: Boolean = false,
) {
    val scheme = MaterialTheme.colorScheme
    val clickable = if (onClick != null) {
        Modifier
            .clip(RoundedCornerShape(14.dp))
            .clickable(onClick = onClick)
    } else Modifier

    Column(
        modifier
            .then(clickable)
            .clip(RoundedCornerShape(14.dp))
            .background(if (selected) scheme.primary.copy(alpha = 0.10f) else scheme.surface)
            .border(
                width = if (onClick != null) 0.9.dp else 0.dp,
                color = when {
                    onClick == null -> Color.Transparent
                    selected -> scheme.primary.copy(alpha = 0.55f)
                    else -> scheme.outlineVariant
                },
                shape = RoundedCornerShape(14.dp),
            )
            .padding(vertical = 13.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            value,
            fontSize = 21.sp,
            fontWeight = FontWeight.Medium,
            color = scheme.primary,
        )
        Spacer(Modifier.height(3.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(label, fontSize = 10.5.sp, color = scheme.onSurfaceVariant)
            // 可点的小三角，进一步暗示能展开
            if (onClick != null) {
                Spacer(Modifier.width(2.dp))
                Text(
                    if (selected) "▴" else "▾",
                    fontSize = 8.sp,
                    color = scheme.onSurfaceVariant.copy(alpha = 0.7f),
                )
            }
        }
    }
}

@Composable
private fun LevelTab(
    levels: List<LevelGrade>,
    needLogin: Boolean = false,
    onOpenLogin: () -> Unit = {},
) {
    if (levels.isEmpty()) {
        EmptyBox("暂无考级成绩", needLogin, onOpenLogin)
        return
    }
    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(
            start = 18.dp, end = 18.dp, top = 2.dp,
            bottom = LocalFloatingBarInset.current + 12.dp,
        ),
        verticalArrangement = Arrangement.spacedBy(9.dp),
    ) {
        items(levels.reversed(), key = { it.name + it.term + it.date }) { l ->
            Column(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(13.dp))
                    .background(MaterialTheme.colorScheme.surface)
                    .padding(horizontal = 14.dp, vertical = 13.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        l.name,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Medium,
                        modifier = Modifier.weight(1f),
                    )
                    Text(
                        if (l.total > 0) formatScore(l.total) else l.level.ifBlank { "-" },
                        fontSize = 22.sp,
                        fontWeight = FontWeight.Medium,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
                Spacer(Modifier.height(4.dp))
                Text(
                    listOf(l.term, l.date, l.level).filter { it.isNotBlank() }.joinToString("  ·  "),
                    fontSize = 10.5.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (l.parts.isNotEmpty()) {
                    Spacer(Modifier.height(9.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                        l.parts.forEach { (k, v) ->
                            Column {
                                Text(
                                    k,
                                    fontSize = 9.5.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                                )
                                Text(v, fontSize = 12.5.sp, color = MaterialTheme.colorScheme.onSurface)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun FitnessTab(
    fitness: List<FitnessGrade>,
    needLogin: Boolean = false,
    onOpenLogin: () -> Unit = {},
) {
    if (fitness.isEmpty()) {
        EmptyBox("暂无体测成绩", needLogin, onOpenLogin)
        return
    }
    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(
            start = 18.dp, end = 18.dp, top = 2.dp,
            bottom = LocalFloatingBarInset.current + 12.dp,
        ),
        verticalArrangement = Arrangement.spacedBy(9.dp),
    ) {
        items(fitness.reversed(), key = { it.term + it.total }) { f ->
            Column(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(13.dp))
                    .background(MaterialTheme.colorScheme.surface)
                    .padding(horizontal = 14.dp, vertical = 13.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        f.term.ifBlank { "体测" },
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Medium,
                        modifier = Modifier.weight(1f),
                    )
                    if (f.total.isNotBlank()) {
                        Text(
                            f.total,
                            fontSize = 21.sp,
                            fontWeight = FontWeight.Medium,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                    if (f.level.isNotBlank()) {
                        Spacer(Modifier.width(7.dp))
                        Box(
                            Modifier
                                .clip(RoundedCornerShape(6.dp))
                                .background(levelColor(f.level).copy(alpha = 0.18f))
                                .padding(horizontal = 7.dp, vertical = 2.dp),
                        ) {
                            Text(
                                f.level,
                                fontSize = 10.5.sp,
                                fontWeight = FontWeight.Medium,
                                color = levelColor(f.level),
                            )
                        }
                    }
                }
                Spacer(Modifier.height(10.dp))
                /*
                 * 体测项两列排布。
                 *
                 * 关键点：**项目名不要用 weight(1f) 撑满**。
                 *
                 * 之前写成 `Text(label, Modifier.weight(1f))` + 值 ——
                 * 标签被推到最左、值被推到最右。单看一项没问题，
                 * 但两列并排时，左项的值正好顶在右项的项目名上：
                 *     「身高        172体重        62.3」
                 * 手机和平板都这样，平板因为列更宽还更明显。
                 *
                 * 现在让标签和值紧挨着成组（中间只隔 6dp），
                 * 右列靠内边距拉开距离。视觉上一项是「一对数据」，
                 * 而不是被拉到两端的两个孤立文字。
                 */
                f.items.chunked(2).forEach { pair ->
                    Row(Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
                        pair.forEachIndexed { col, item ->
                            Row(
                                Modifier
                                    .weight(1f)
                                    // 右列左侧留 20dp，两列之间才有明显分隔
                                    .padding(start = if (col == 0) 0.dp else 20.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(
                                    item.label,
                                    fontSize = 11.5.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                Spacer(Modifier.width(6.dp))
                                Text(
                                    item.value,
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Medium,
                                    maxLines = 1,
                                )
                                if (item.score.isNotBlank()) {
                                    Text(
                                        " (${item.score})",
                                        fontSize = 10.sp,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                                        maxLines = 1,
                                    )
                                }
                            }
                        }
                        if (pair.size == 1) Spacer(Modifier.weight(1f))
                    }
                }
            }
        }
    }
}

private fun levelColor(level: String): Color = when {
    level.contains("优秀") -> Color(0xFF4ADE9B)
    level.contains("良好") -> Color(0xFF7FA8FF)
    level.contains("及格") -> Color(0xFFF5B454)
    level.contains("不及格") -> Color(0xFFFF8A72)
    else -> Color(0xFF9AA3AE)
}

@Composable
private fun EmptyBox(
    text: String,
    needLogin: Boolean = false,
    onOpenLogin: () -> Unit = {},
) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(text, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (needLogin) {
                Spacer(Modifier.height(12.dp))
                Text(
                    "登录状态掉了，去登录",
                    fontSize = 12.5.sp,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier
                        .clip(RoundedCornerShape(9.dp))
                        .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.12f))
                        .clickable(onClick = onOpenLogin)
                        .padding(horizontal = 16.dp, vertical = 7.dp),
                )
            }
        }
    }
}