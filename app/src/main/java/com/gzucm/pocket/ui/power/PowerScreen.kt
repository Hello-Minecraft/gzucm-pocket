package com.gzucm.pocket.ui.power

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.border
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.Canvas
import com.gzucm.pocket.core.PowerDaily
import com.gzucm.pocket.ui.components.LocalFloatingBarInset
import com.gzucm.pocket.ui.components.Fmt
import com.gzucm.pocket.core.PowerInfo

@Composable
fun PowerScreen(
    power: PowerInfo?,
    daily: List<PowerDaily>,
    loading: Boolean,
    error: String? = null,
    onRefresh: () -> Unit,
    onSync: () -> Unit = {},
) {
    if (power == null) {
        Column(
            Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .padding(horizontal = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            if (loading) {
                CircularProgressIndicator(Modifier.size(28.dp), strokeWidth = 2.5.dp)
                Spacer(Modifier.height(14.dp))
                Text(
                    "正在读取电费…",
                    fontSize = 12.5.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                Text(
                    if (error != null) "电费没读出来" else "暂时读不到电费数据",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Medium,
                )
                if (error != null) {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        error,
                        fontSize = 11.5.sp,
                        lineHeight = 17.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                    )
                }
                Spacer(Modifier.height(20.dp))

                // 主入口：用 WebView 走一遍，cookie 由浏览器自己管，不会搬错
                Box(
                    Modifier
                        .clip(RoundedCornerShape(12.dp))
                        .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.15f))
                        .clickable(onClick = onSync)
                        .padding(horizontal = 20.dp, vertical = 10.dp),
                ) {
                    Text(
                        "同步校园卡",
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Medium,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
                Spacer(Modifier.height(10.dp))
                Text(
                    "会打开学校的一卡通页面，登录一次即可",
                    fontSize = 10.5.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                )
            }
        }
        return
    }

    val avg7 = daily.takeLast(7).filter { it.usage > 0 }.let { l ->
        if (l.isEmpty()) 0.0 else l.sumOf { it.usage } / l.size
    }

    LazyColumn(
        Modifier
            .fillMaxSize()
            .statusBarsPadding(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(
            start = 18.dp, end = 18.dp, top = 12.dp,
            bottom = LocalFloatingBarInset.current + 12.dp,
        ),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Column(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(20.dp))
                    .background(
                        Brush.linearGradient(
                            listOf(
                                MaterialTheme.colorScheme.primary.copy(alpha = 0.14f),
                                MaterialTheme.colorScheme.surface,
                            ),
                        ),
                    )
                    .padding(horizontal = 20.dp, vertical = 20.dp),
            ) {
                // 房号 + 「电费不准？」提示
                var showWhy by remember { mutableStateOf(false) }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        power.roomLabel,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.width(10.dp))
                    Row(
                        Modifier
                            .clip(RoundedCornerShape(6.dp))
                            .clickable { showWhy = true }
                            .padding(horizontal = 3.dp, vertical = 2.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        // 小圆圈感叹号
                        Box(
                            Modifier
                                .size(13.dp)
                                .clip(CircleShape)
                                .background(MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                "!",
                                fontSize = 9.sp,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.surface,
                            )
                        }
                        Spacer(Modifier.width(4.dp))
                        Text(
                            "电费不准？",
                            fontSize = 10.5.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textDecoration = TextDecoration.Underline,
                        )
                    }
                }
                if (showWhy) {
                    PowerWhySheet(
                        roomLabel = power.roomLabel,
                        onDismiss = { showWhy = false },
                    )
                }
                Spacer(Modifier.height(10.dp))
                Row(verticalAlignment = Alignment.Bottom) {
                    Text(
                        formatNum(power.balance),
                        fontSize = 44.sp,
                        fontWeight = FontWeight.Medium,
                        letterSpacing = (-1.5).sp,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(
                        power.unit,
                        fontSize = 15.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(bottom = 8.dp),
                    )
                }
                if (avg7 > 0) {
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "近 7 天日均 ${Fmt.d2(avg7)} 度 · " + run {
                            val days = power.balance / avg7
                            when {
                                days < 1 -> "不足 1 天，建议充值"
                                days > 999 -> "可用很久"
                                else -> "可用约 ${days.toInt()} 天"
                            }
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        if (daily.size >= 3) {
            item {
                Column(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(16.dp))
                        .background(MaterialTheme.colorScheme.surface)
                        .padding(horizontal = 16.dp, vertical = 15.dp),
                ) {
                    Text(
                        "用电趋势",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Medium,
                    )
                    Spacer(Modifier.height(2.dp))
                    Text(
                        "最近 ${minOf(daily.size, 30)} 天",
                        fontSize = 10.5.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(14.dp))
                    UsageChart(daily.takeLast(30))
                }
            }
        }

        item {
            Text(
                "每日明细",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Medium,
                modifier = Modifier.padding(top = 6.dp, bottom = 2.dp),
            )
        }

        items(daily.reversed(), key = { it.date }) { d ->
            Row(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(11.dp))
                    .background(MaterialTheme.colorScheme.surface)
                    .padding(horizontal = 14.dp, vertical = 11.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    d.date,
                    fontSize = 12.5.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.weight(1f))
                Text(
                    "用 ${Fmt.d2(d.usage)}",
                    fontSize = 12.5.sp,
                    fontWeight = FontWeight.Medium,
                )
                Spacer(Modifier.width(14.dp))
                Text(
                    "余 ${Fmt.d2(d.remain)}",
                    fontSize = 11.5.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun UsageChart(points: List<PowerDaily>) {
    val scheme = MaterialTheme.colorScheme
    val line = scheme.primary
    val maxV = (points.maxOfOrNull { it.usage } ?: 0.1).coerceAtLeast(0.1)

    Column {
        Canvas(
            Modifier
                .fillMaxWidth()
                .height(110.dp),
        ) {
            if (points.size < 2) return@Canvas
            val w = size.width
            val h = size.height
            val stepX = w / (points.size - 1).toFloat()

            fun yOf(v: Float) = h - (v / maxV.toFloat()) * (h * 0.86f) - h * 0.04f

            val path = Path()
            val fill = Path()
            points.forEachIndexed { i, p ->
                val x = stepX * i
                val y = yOf(p.usage.toFloat())
                if (i == 0) {
                    path.moveTo(x, y)
                    fill.moveTo(x, h)
                    fill.lineTo(x, y)
                } else {
                    path.lineTo(x, y)
                    fill.lineTo(x, y)
                }
                if (i == points.lastIndex) {
                    fill.lineTo(x, h)
                    fill.close()
                }
            }

            drawPath(
                fill,
                Brush.verticalGradient(
                    listOf(line.copy(alpha = 0.22f), Color.Transparent),
                ),
            )
            drawPath(path, line, style = Stroke(width = 2.2f))

            // 最后一个点加高亮
            val last = points.last()
            drawCircle(
                color = line,
                radius = 3.4f,
                center = Offset(w, yOf(last.usage.toFloat())),
            )
        }
        Spacer(Modifier.height(6.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(
                points.first().date.takeLast(5),
                fontSize = 9.5.sp,
                color = scheme.onSurfaceVariant.copy(alpha = 0.6f),
            )
            Text(
                "峰值 ${Fmt.d2(maxV)} 度",
                fontSize = 9.5.sp,
                color = scheme.onSurfaceVariant.copy(alpha = 0.6f),
            )
            Text(
                points.last().date.takeLast(5),
                fontSize = 9.5.sp,
                color = scheme.onSurfaceVariant.copy(alpha = 0.6f),
            )
        }
    }
}

private fun formatNum(v: Double): String = Fmt.trim2(v)