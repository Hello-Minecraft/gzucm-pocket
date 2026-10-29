package com.gzucm.pocket.ui.today

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.gzucm.pocket.core.CourseSession
import com.gzucm.pocket.ui.components.LocalFloatingBarInset
import com.gzucm.pocket.ui.components.Fmt
import com.gzucm.pocket.core.PowerDaily
import com.gzucm.pocket.core.PowerInfo
import com.gzucm.pocket.core.Semester
import com.gzucm.pocket.ui.theme.courseColor
import java.time.LocalDate
import java.time.LocalTime
import java.time.format.DateTimeFormatter

private val Cn = arrayOf("周一", "周二", "周三", "周四", "周五", "周六", "周日")

@Composable
fun TodayScreen(
    semester: Semester?,
    currentWeek: Int,
    sessions: List<CourseSession>,
    power: PowerInfo?,
    powerDaily: List<PowerDaily>,
    onCourseClick: (CourseSession) -> Unit,
    onOpenPower: () -> Unit,
    onOpenSettings: () -> Unit,
    onRefresh: () -> Unit,
    syncing: Boolean,
    progressDone: Int = 0,
    progressTotal: Int = 0,
    notice: String? = null,
) {
    val today = LocalDate.now()
    // 每分钟走一次，用来判断「今天的课是不是已经上完了」
    var nowMin by remember { mutableIntStateOf(LocalTime.now().let { it.hour * 60 + it.minute }) }
    LaunchedEffect(Unit) {
        while (true) {
            kotlinx.coroutines.delay(30_000)
            nowMin = LocalTime.now().let { it.hour * 60 + it.minute }
        }
    }
    // 这四处原本每次重组都重算（含 O(n) 遍历与多次 split），
    // 挂在 sessions/currentWeek 上缓存，页面不变就不重算。
    val todaySessions = remember(sessions, currentWeek, today) {
        sessions.filter { it.week == currentWeek && it.weekday == today.dayOfWeek.value }
            .sortedBy { it.startUnit }
    }
    val weekSessions = remember(sessions, currentWeek) {
        sessions.filter { it.week == currentWeek }
    }

    // 今天最后一节课结束之后，首页就该看明天的了
    val todayDone = todaySessions.isNotEmpty() &&
        nowMin > (todaySessions.maxOfOrNull { parseMinutes(it.endTime) } ?: 0)
    val tomorrowSessions = remember(sessions, semester, today) {
        val sem = semester ?: return@remember emptyList()
        val d = today.plusDays(1)
        val w = sem.weekOf(d)
        if (w < 1 || w > sem.totalWeeks) emptyList()
        else sessions.filter { it.week == w && it.weekday == d.dayOfWeek.value }
            .sortedBy { it.startUnit }
    }
    val showingTomorrow = todayDone && tomorrowSessions.isNotEmpty()

    Column(
        Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .statusBarsPadding()
            .padding(horizontal = 18.dp),
    ) {
        Spacer(Modifier.height(10.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "${today.monthValue} 月 ${today.dayOfMonth} 日",
                        style = MaterialTheme.typography.headlineMedium,
                        fontWeight = FontWeight.Medium,
                    )
                    Spacer(Modifier.width(8.dp))
                    Box(
                        Modifier
                            .clip(RoundedCornerShape(6.dp))
                            .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.14f))
                            .padding(horizontal = 7.dp, vertical = 2.dp),
                    ) {
                        Text(
                            "第 $currentWeek 周",
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Medium,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                }
                Spacer(Modifier.height(3.dp))
                Text(
                    "${Cn[today.dayOfWeek.value - 1]} · 今天 ${todaySessions.size} 节课",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (syncing) {
                androidx.compose.material3.CircularProgressIndicator(
                    modifier = Modifier.size(17.dp),
                    strokeWidth = 2.dp,
                    color = MaterialTheme.colorScheme.primary,
                )
                Spacer(Modifier.width(10.dp))
            } else {
                Box(
                    Modifier
                        .size(34.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.surface)
                        .clickable(onClick = onRefresh),
                    contentAlignment = Alignment.Center,
                ) {
                    androidx.compose.material3.Icon(
                        androidx.compose.material.icons.Icons.Rounded.Refresh,
                        contentDescription = "同步",
                        modifier = Modifier.size(17.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Spacer(Modifier.width(8.dp))
            }
            Box(
                Modifier
                    .size(34.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.surface)
                    .clickable(onClick = onOpenSettings),
                contentAlignment = Alignment.Center,
            ) {
                androidx.compose.material3.Icon(
                    androidx.compose.material.icons.Icons.Rounded.Settings,
                    contentDescription = "设置",
                    modifier = Modifier.size(17.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        Spacer(Modifier.height(16.dp))

        if (!notice.isNullOrBlank()) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(MaterialTheme.colorScheme.tertiary.copy(alpha = 0.13f))
                    .padding(horizontal = 13.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    notice,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.tertiary,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    "去登录",
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.clickable(onClick = onOpenSettings).padding(start = 10.dp),
                )
            }
            Spacer(Modifier.height(12.dp))
        }

        PowerCard(power = power, daily = powerDaily, onClick = onOpenPower)

        Spacer(Modifier.height(18.dp))

        Text(
            if (showingTomorrow) "明日课程" else "今日课程",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(10.dp))

        if (showingTomorrow) {
            Timeline(sessions = tomorrowSessions, onCourseClick = onCourseClick)
        } else if (todaySessions.isEmpty()) {
            Box(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(14.dp))
                    .background(MaterialTheme.colorScheme.surface)
                    .padding(vertical = if (syncing && sessions.isEmpty()) 26.dp else 30.dp),
                contentAlignment = Alignment.Center,
            ) {
                // 还在抓数据时说「今天没有课」是错的 —— 那是假话，用户会以为今天真没课
                if (syncing && sessions.isEmpty()) {
                    com.gzucm.pocket.ui.components.SyncPlaceholder(
                        done = progressDone,
                        total = progressTotal,
                    )
                } else {
                    Text(
                        "今天没有课",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        } else {
            Timeline(sessions = todaySessions, onCourseClick = onCourseClick)
        }

        Spacer(Modifier.height(20.dp))

        WeekOverview(
            sessions = weekSessions,
            currentWeek = currentWeek,
        )

        // 悬浮底栏占位，避免最后一张卡被压在底栏下面
        Spacer(Modifier.height(LocalFloatingBarInset.current))
    }
}

/** 本周概览：每天几节课的分布，填充界面下半部分 */
@Composable
private fun WeekOverview(sessions: List<CourseSession>, currentWeek: Int) {
    val scheme = MaterialTheme.colorScheme
    if (sessions.isEmpty()) return

    // 原本每天 count 一遍 = 7 次 O(n) 遍历，改成一次分组
    val perDay = remember(sessions) {
        val counts = IntArray(8)
        sessions.forEach { if (it.weekday in 1..7) counts[it.weekday]++ }
        (1..7).map { counts[it] }
    }
    val maxDay = remember(perDay) { perDay.maxOrNull() ?: 0 }
    val total = sessions.size
    val busiest = if (maxDay > 0) perDay.indexOf(maxDay) + 1 else 0
    val today = LocalDate.now().dayOfWeek.value

    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(scheme.surface)
            .padding(horizontal = 16.dp, vertical = 15.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "本周概览",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Medium,
            )
            Spacer(Modifier.weight(1f))
            Text(
                "共 $total 节" + if (busiest > 0) " · ${Cn[busiest - 1]}最忙" else "",
                fontSize = 11.sp,
                color = scheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.height(14.dp))
        Row(
            Modifier
                .fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(5.dp),
            verticalAlignment = Alignment.Bottom,
        ) {
            perDay.forEachIndexed { i, n ->
                val ratio = if (maxDay == 0) 0f else n.toFloat() / maxDay
                val isToday = (i + 1) == today
                Column(
                    Modifier.weight(1f),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(
                        if (n > 0) "$n" else "·",
                        fontSize = 9.sp,
                        color = if (isToday) scheme.primary
                        else scheme.onSurfaceVariant.copy(alpha = 0.75f),
                    )
                    Spacer(Modifier.height(4.dp))
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 3.dp)
                            .height((30 * ratio).dp.coerceAtLeast(if (n > 0) 4.dp else 2.dp))
                            .clip(RoundedCornerShape(topStart = 4.dp, topEnd = 4.dp))
                            .background(
                                when {
                                    n == 0 -> scheme.outlineVariant.copy(alpha = 0.4f)
                                    isToday -> scheme.primary
                                    else -> scheme.primary.copy(alpha = 0.3f)
                                },
                            ),
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(
                        Cn[i].takeLast(1),
                        fontSize = 10.sp,
                        color = if (isToday) scheme.primary
                        else scheme.onSurfaceVariant.copy(alpha = 0.6f),
                    )
                }
            }
        }

        // 下一节课提示
        // 原本每次重组都算一次（含 LocalTime.now 与多次字符串 split），缓存起来
        val next = remember(sessions, currentWeek) {
            nextSession(sessions, currentWeek)
        }
        if (next != null) {
            Spacer(Modifier.height(14.dp))
            Row(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(10.dp))
                    .background(scheme.primary.copy(alpha = 0.07f))
                    .padding(horizontal = 12.dp, vertical = 9.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "下次上课",
                    fontSize = 10.5.sp,
                    color = scheme.onSurfaceVariant,
                )
                Spacer(Modifier.width(9.dp))
                Text(
                    next.first,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Medium,
                    color = scheme.primary,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    next.second,
                    fontSize = 10.5.sp,
                    color = scheme.onSurfaceVariant,
                )
            }
        }
    }
}

/** 找下一节课：下一个有课的日子（含今天还没开始的） */
private fun nextSession(sessions: List<CourseSession>, week: Int): Pair<String, String>? {
    val now = LocalTime.now()
    val todayWd = LocalDate.now().dayOfWeek.value
    val nowMin = now.hour * 60 + now.minute

    // 今天还没开始的
    sessions.filter { it.weekday == todayWd }
        .sortedBy { it.startUnit }
        .firstOrNull { parseMinutes(it.startTime) > nowMin }
        ?.let { return it.name to "今天 ${it.startTime} · ${it.room.ifBlank { "地点待定" }}" }

    // 往后找 7 天
    for (offset in 1..7) {
        val wd = (todayWd - 1 + offset) % 7 + 1
        val list = sessions.filter { it.weekday == wd }.sortedBy { it.startUnit }
        if (list.isNotEmpty()) {
            val s = list.first()
            val label = if (offset == 1) "明天" else Cn[wd - 1]
            return s.name to "$label ${s.startTime} · ${s.room.ifBlank { "地点待定" }}"
        }
    }
    return null
}

@Composable
private fun Timeline(sessions: List<CourseSession>, onCourseClick: (CourseSession) -> Unit) {
    val now = LocalTime.now()
    val nowMinutes = now.hour * 60 + now.minute

    Column {
        sessions.forEachIndexed { i, s ->
            val color = courseColor(s.name)
            val endMin = parseMinutes(s.endTime)
            val startMin = parseMinutes(s.startTime)
            val isPast = nowMinutes > endMin
            val isOngoing = nowMinutes in startMin..endMin

            Row(Modifier.fillMaxWidth()) {
                // 时间轴
                Column(
                    Modifier.width(56.dp),
                    horizontalAlignment = Alignment.End,
                ) {
                    Text(
                        s.startTime,
                        fontSize = 12.5.sp,
                        fontWeight = if (isOngoing) FontWeight.Medium else FontWeight.Normal,
                        color = if (isOngoing) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        s.endTime,
                        fontSize = 10.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                    )
                }
                Spacer(Modifier.width(10.dp))
                // 轴点与连线
                Column(
                    Modifier.width(14.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Box(
                        Modifier
                            .padding(top = 4.dp)
                            .size(if (isOngoing) 9.dp else 7.dp)
                            .clip(CircleShape)
                            .background(
                                if (isPast) color.copy(alpha = 0.3f)
                                else color,
                            ),
                    )
                    if (i != sessions.lastIndex) {
                        Box(
                            Modifier
                                .width(1.4.dp)
                                .height(52.dp)
                                .background(
                                    MaterialTheme.colorScheme.outlineVariant,
                                ),
                        )
                    }
                }
                Spacer(Modifier.width(10.dp))
                // 卡片
                Box(
                    Modifier
                        .weight(1f)
                        .padding(bottom = if (i == sessions.lastIndex) 0.dp else 12.dp)
                        .clip(RoundedCornerShape(13.dp))
                        .background(
                            if (isOngoing) color.copy(alpha = 0.14f)
                            else MaterialTheme.colorScheme.surface,
                        )
                        .border(
                            width = if (isOngoing) 1.dp else 0.5.dp,
                            color = if (isOngoing) color.copy(alpha = 0.5f)
                            else MaterialTheme.colorScheme.outlineVariant,
                            shape = RoundedCornerShape(13.dp),
                        )
                        .clickable { onCourseClick(s) }
                        .padding(horizontal = 13.dp, vertical = 11.dp),
                ) {
                    Column {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                s.name,
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Medium,
                                color = if (isPast) MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f)
                                else MaterialTheme.colorScheme.onSurface,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f),
                            )
                            if (isOngoing) {
                                Box(
                                    Modifier
                                        .clip(RoundedCornerShape(5.dp))
                                        .background(color.copy(alpha = 0.2f))
                                        .padding(horizontal = 6.dp, vertical = 1.dp),
                                ) {
                                    Text(
                                        "正在上",
                                        fontSize = 9.5.sp,
                                        fontWeight = FontWeight.Medium,
                                        color = color,
                                    )
                                }
                            }
                        }
                        Spacer(Modifier.height(5.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                "${s.startUnit}-${s.endUnit} 节",
                                fontSize = 11.sp,
                                color = color,
                            )
                            if (s.room.isNotBlank()) {
                                Text(
                                    "  ·  ",
                                    fontSize = 11.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
                                )
                                Text(
                                    s.room,
                                    fontSize = 11.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

/** "08:30" → 510。不 split、不建 List，滚动时每行都要调它 */
private fun parseMinutes(hhmm: String): Int {
    val colon = hhmm.indexOf(':')
    if (colon <= 0) return Int.MAX_VALUE
    var h = 0
    for (i in 0 until colon) {
        val c = hhmm[i]
        if (c < '0' || c > '9') return Int.MAX_VALUE
        h = h * 10 + (c - '0')
    }
    var m = 0
    for (i in colon + 1 until hhmm.length) {
        val c = hhmm[i]
        if (c < '0' || c > '9') break
        m = m * 10 + (c - '0')
    }
    return h * 60 + m
}

@Composable
private fun PowerCard(power: PowerInfo?, daily: List<PowerDaily>, onClick: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    val accent = if (power == null) scheme.onSurfaceVariant else scheme.primary
    Box(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .background(
                Brush.linearGradient(
                    listOf(
                        scheme.surface,
                        scheme.surface.copy(alpha = 0.92f),
                    ),
                ),
            )
            .border(0.6.dp, scheme.outlineVariant, RoundedCornerShape(18.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 17.dp, vertical = 15.dp),
    ) {
        Column {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "宿舍电量",
                    style = MaterialTheme.typography.bodySmall,
                    color = scheme.onSurfaceVariant,
                )
                Spacer(Modifier.weight(1f))
                Text(
                    power?.roomLabel ?: "尚未同步",
                    fontSize = 10.5.sp,
                    color = scheme.onSurfaceVariant.copy(alpha = 0.7f),
                )
            }
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.Bottom) {
                Text(
                    if (power == null) "--" else formatBalance(power.balance),
                    fontSize = 30.sp,
                    fontWeight = FontWeight.Medium,
                    color = accent,
                    letterSpacing = (-1).sp,
                )
                Spacer(Modifier.width(5.dp))
                Text(
                    power?.unit ?: "度",
                    fontSize = 13.sp,
                    color = scheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 4.dp),
                )
                Spacer(Modifier.weight(1f))
                val avg = averageUsage(daily)
                if (avg > 0) {
                    Column(horizontalAlignment = Alignment.End) {
                        Text(
                            "日均 ${Fmt.d2(avg)} 度",
                            fontSize = 11.sp,
                            color = scheme.onSurfaceVariant,
                        )
                        if (power != null && power.balance > 0) {
                            val days = power.balance / avg
                            Text(
                                if (days < 1) "不足 1 天"
                                else "可用约 ${days.toInt()} 天",
                                fontSize = 10.5.sp,
                                color = if (days < 5) scheme.error
                                else scheme.primary.copy(alpha = 0.85f),
                            )
                        }
                    }
                }
            }
            if (daily.size >= 2) {
                Spacer(Modifier.height(12.dp))
                MiniSparkline(daily.takeLast(14), accent)
            }
        }
    }
}

private fun formatBalance(v: Double): String = Fmt.trim2(v)

private fun averageUsage(daily: List<PowerDaily>): Double {
    val recent = daily.takeLast(7).filter { it.usage > 0 }
    if (recent.isEmpty()) return 0.0
    return recent.sumOf { it.usage } / recent.size
}

@Composable
private fun MiniSparkline(points: List<PowerDaily>, color: Color) {
    val max = points.maxOf { it.usage }.coerceAtLeast(0.1)
    Row(
        Modifier
            .fillMaxWidth()
            .height(30.dp),
        horizontalArrangement = Arrangement.spacedBy(3.dp),
        verticalAlignment = Alignment.Bottom,
    ) {
        points.forEach { p ->
            val ratio = (p.usage / max).toFloat().coerceIn(0.06f, 1f)
            Box(
                Modifier
                    .weight(1f)
                    .height((30 * ratio).dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(color.copy(alpha = 0.25f + 0.5f * ratio)),
            )
        }
    }
}