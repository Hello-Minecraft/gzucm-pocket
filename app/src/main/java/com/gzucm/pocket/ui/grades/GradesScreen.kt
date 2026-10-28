package com.gzucm.pocket.ui.grades

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.gzucm.pocket.core.CourseGrade
import com.gzucm.pocket.ui.components.LocalFloatingBarInset
import com.gzucm.pocket.core.FitnessGrade
import com.gzucm.pocket.core.LevelGrade
import com.gzucm.pocket.ui.components.Fmt

private enum class Tab(val label: String) {
    Course("课程成绩"), Level("考级"), Fitness("体测")
}

@Composable
fun GradesScreen(
    grades: List<CourseGrade>,
    levels: List<LevelGrade>,
    fitness: List<FitnessGrade>,
    loading: Boolean,
    onRefresh: () -> Unit,
) {
    var tab by remember { mutableStateOf(Tab.Course) }

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
        }

        when {
            loading && grades.isEmpty() -> {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(Modifier.size(28.dp), strokeWidth = 2.5.dp)
                }
            }

            tab == Tab.Course -> CourseTab(grades)
            tab == Tab.Level -> LevelTab(levels)
            else -> FitnessTab(fitness)
        }
    }
}

@Composable
private fun CourseTab(grades: List<CourseGrade>) {
    if (grades.isEmpty()) {
        EmptyBox("暂无成绩")
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
        val passed = grades.filter { it.passed }
        val totalCredit = passed.sumOf { it.credit }
        val weightedGpa = if (totalCredit > 0) passed.sumOf { it.gpa * it.credit } / totalCredit else 0.0
        val avgScore = if (grades.isNotEmpty()) grades.sumOf { it.score } / grades.size else 0.0
        Triple(totalCredit, weightedGpa, avgScore)
    }
    val totalCredit = summary.first
    val weightedGpa = summary.second
    val avgScore = summary.third

    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(
            start = 18.dp, end = 18.dp, top = 2.dp,
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
                StatPill("加权绩点", Fmt.d2(weightedGpa), Modifier.weight(1f))
                StatPill("平均分", Fmt.trim1(avgScore), Modifier.weight(1f))
                StatPill("已修学分", Fmt.trim1(totalCredit), Modifier.weight(1f))
            }
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
    val gpa = remember(list) {
        val credit = list.filter { it.passed }.sumOf { it.credit }
        if (credit > 0) list.filter { it.passed }.sumOf { it.gpa * it.credit } / credit else 0.0
    }
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

@Composable
private fun StatPill(label: String, value: String, modifier: Modifier = Modifier) {
    val scheme = MaterialTheme.colorScheme
    Column(
        modifier
            .clip(RoundedCornerShape(14.dp))
            .background(scheme.surface)
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
        Text(label, fontSize = 10.5.sp, color = scheme.onSurfaceVariant)
    }
}

@Composable
private fun LevelTab(levels: List<LevelGrade>) {
    if (levels.isEmpty()) {
        EmptyBox("暂无考级成绩")
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
private fun FitnessTab(fitness: List<FitnessGrade>) {
    if (fitness.isEmpty()) {
        EmptyBox("暂无体测成绩")
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
                f.items.chunked(2).forEach { pair ->
                    Row(Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
                        pair.forEach { item ->
                            Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    item.label,
                                    fontSize = 11.5.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.weight(1f),
                                )
                                Text(
                                    item.value,
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Medium,
                                )
                                if (item.score.isNotBlank()) {
                                    Text(
                                        " (${item.score})",
                                        fontSize = 10.sp,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
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
private fun EmptyBox(text: String) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(text, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}