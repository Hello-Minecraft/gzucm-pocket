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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material3.Icon
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableIntStateOf
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
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.font.FontWeight
import kotlin.math.roundToInt
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
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
    onSync: () -> Unit = {},
    powerAlertOn: Boolean = true,
    onPowerAlertChange: (Boolean) -> Unit = {},
    onOpenRebindGuide: () -> Unit = {},
) {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    // 提到这里：之前挂在 LazyColumn 的 item 里，卡片滚出屏幕会被销毁，
    // 面板会突然消失，状态也跟着丢
    var showWhy by remember { mutableStateOf(false) }

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
        // 表头：手动更新入口。自动同步每天只跑一次，想看最新数据就点这里（会弹窗）
        item {
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "宿舍电量",
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.weight(1f))
                // 上次更新时间：小小字，压在「更新」左边
                val updatedText = remember(power) {
                    val ts = com.gzucm.pocket.core.SessionStore(
                        ctx.applicationContext
                    ).powerCheckedAt
                    if (ts <= 0) "" else {
                        val d = java.util.Date(ts)
                        val fmt = java.text.SimpleDateFormat("MM-dd HH:mm", java.util.Locale.CHINA)
                        "更新于 " + fmt.format(d)
                    }
                }
                if (updatedText.isNotEmpty()) {
                    Text(
                        updatedText,
                        fontSize = 9.5.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.55f),
                        modifier = Modifier.padding(end = 2.dp),
                    )
                    Spacer(Modifier.width(4.dp))
                }
                Row(
                    Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .clickable(onClick = onSync)
                        .padding(horizontal = 9.dp, vertical = 5.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        Icons.Rounded.Refresh,
                        contentDescription = null,
                        modifier = Modifier.size(14.dp),
                        tint = MaterialTheme.colorScheme.primary,
                    )
                    Spacer(Modifier.width(4.dp))
                    Text(
                        "更新",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Medium,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }
        }

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
                // 房号 + 「电费设置」提示
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
                        // 原来的「!」圆圈换成调节图标，跟「设置」这个语义更搭
                        Icon(
                            Icons.Rounded.Tune,
                            contentDescription = null,
                            modifier = Modifier.size(13.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.65f),
                        )
                        Spacer(Modifier.width(4.dp))
                        Text(
                            "电费设置",
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
                        alertOn = powerAlertOn,
                        onAlertChange = onPowerAlertChange,
                        // 先收起设置面板：两层 ModalBottomSheet 叠在一起会互相挡
                        onBuyInMiniProgram = { showWhy = false; onOpenRebindGuide() },
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

/**
 * 用电趋势折线图。
 *
 * 支持左右滑动选中某一天：竖线定位 + 该点高亮，
 * 日期与用量显示在图**上方**（图内空间太挤，字会压线）。
 */
@Composable
private fun UsageChart(points: List<PowerDaily>) {
    val scheme = MaterialTheme.colorScheme
    val line = scheme.primary
    val maxV = (points.maxOfOrNull { it.usage } ?: 0.1).coerceAtLeast(0.1)
    var sel by remember(points) { mutableIntStateOf(-1) }
    val picked = points.getOrNull(sel)

    Column {
        // 顶部读数：没选中时给操作提示，选中后显示日期 + 用量
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (picked != null) {
                Text(
                    picked.date,
                    fontSize = 11.5.sp,
                    fontWeight = FontWeight.Medium,
                    color = scheme.onSurface,
                )
                Text(
                    "${Fmt.d2(picked.usage)} 度",
                    fontSize = 12.5.sp,
                    fontWeight = FontWeight.Medium,
                    color = line,
                )
            } else {
                Text(
                    "左右滑动查看某一天的用量",
                    fontSize = 10.5.sp,
                    color = scheme.onSurfaceVariant.copy(alpha = 0.65f),
                )
                Text(
                    "峰值 ${Fmt.d2(maxV)} 度",
                    fontSize = 10.sp,
                    color = scheme.onSurfaceVariant.copy(alpha = 0.6f),
                )
            }
        }

        Spacer(Modifier.height(8.dp))

        Canvas(
            Modifier
                .fillMaxWidth()
                .height(110.dp)
                .pointerInput(points) {
                    // 拖动时按 x 落到最近的点；手指不用精确对准
                    fun pick(x: Float) {
                        if (size.width <= 0 || points.size < 2) return
                        val step = size.width / (points.size - 1).toFloat()
                        val i = (x / step).roundToInt().coerceIn(0, points.lastIndex)
                        sel = i
                    }
                    detectDragGestures(
                        onDragStart = { off -> pick(off.x) },
                        onDrag = { change, _ -> pick(change.position.x); change.consume() },
                    )
                }
                .pointerInput(points) {
                    detectTapGestures { off ->
                        if (size.width <= 0 || points.size < 2) return@detectTapGestures
                        val step = size.width / (points.size - 1).toFloat()
                        val i = (off.x / step).roundToInt().coerceIn(0, points.lastIndex)
                        // 再点同一处就取消选中
                        sel = if (sel == i) -1 else i
                    }
                },
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

            drawPath(fill, Brush.verticalGradient(listOf(line.copy(alpha = 0.22f), Color.Transparent)))
            drawPath(path, line, style = Stroke(width = 2.2f))

            if (picked != null) {
                // 选中：竖线 + 高亮点
                val x = stepX * sel
                val y = yOf(picked.usage.toFloat())
                drawLine(
                    color = line.copy(alpha = 0.45f),
                    start = Offset(x, 0f),
                    end = Offset(x, h),
                    strokeWidth = 1.4f,
                )
                drawCircle(color = line.copy(alpha = 0.18f), radius = 7.5f, center = Offset(x, y))
                drawCircle(color = line, radius = 3.6f, center = Offset(x, y))
            } else {
                // 未选中：保留最后一天的点，给个「线到这里为止」的锚
                val last = points.last()
                drawCircle(color = line, radius = 3.4f, center = Offset(w, yOf(last.usage.toFloat())))
            }
        }

        Spacer(Modifier.height(6.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(
                points.first().date.takeLast(5),
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