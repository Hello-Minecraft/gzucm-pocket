package com.gzucm.pocket.ui.schedule

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.runtime.snapshotFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ChevronLeft
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.Wallpaper
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.gzucm.pocket.core.CourseSession
import com.gzucm.pocket.ui.components.CourseBackground
import com.gzucm.pocket.ui.components.LocalFloatingBarInset
import com.gzucm.pocket.core.Semester
import com.gzucm.pocket.ui.theme.courseColor
import java.time.LocalDate

private val WeekdayLabels = listOf("一", "二", "三", "四", "五", "六", "日")

/**
 * 广中医作息。每节 45 分钟，节间 5 分钟。
 * 节次编号是跳号的：没有 5、10、11（实测抓包确认），13 存在但常被忽略。
 */
val SlotTimes: Map<Int, String> = mapOf(
    1 to "08:30", 2 to "09:20", 3 to "10:20", 4 to "11:10",
    6 to "14:00", 7 to "14:50", 8 to "15:40", 9 to "16:30",
    12 to "19:00", 13 to "19:50", 14 to "20:40",
)

/** 全部可能的节次，用于计算跨节高度时补齐中间被跳过的号 */
private val AllUnits = listOf(1, 2, 3, 4, 6, 7, 8, 9, 12, 13, 14)

@Composable
fun ScheduleScreen(
    semester: Semester?,
    sessions: List<CourseSession>,
    currentWeek: Int,
    viewingWeek: Int,
    onWeekChange: (Int) -> Unit,
    onCourseClick: (CourseSession) -> Unit,
) {
    if (semester == null) {
        EmptyHint("正在获取学期信息…")
        return
    }

    val ctx = androidx.compose.ui.platform.LocalContext.current
    val store = remember { com.gzucm.pocket.core.SessionStore(ctx.applicationContext) }
    var bgPath by remember { mutableStateOf(store.courseBgPath) }
    var bgAlpha by remember { mutableStateOf(store.courseBgAlpha) }
    var showBgSheet by remember { mutableStateOf(false) }

    // 系统图片选择器，不需要存储权限
    val picker = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.PickVisualMedia()
    ) { uri ->
        if (uri != null) {
            val saved = runCatching {
                val dir = java.io.File(ctx.filesDir, "bg").apply { mkdirs() }
                dir.listFiles()?.forEach { it.delete() }
                val out = java.io.File(dir, "course_bg.jpg")
                ctx.contentResolver.openInputStream(uri)!!.use { ins ->
                    out.outputStream().use { os -> ins.copyTo(os) }
                }
                out.absolutePath
            }.getOrNull()
            if (saved != null) {
                bgPath = saved
                store.courseBgPath = saved
            }
        }
    }

    val totalWeeks = semester.totalWeeks.coerceAtLeast(1)

    /**
     * 行集合按**整学期**算，不按单周算。
     * 否则翻页时两边的行数不同，动画中途会跳一下；而且列宽也会跟着抖。
     */
    val slots = remember(sessions) {
        val used = sessions.flatMap { listOf(it.startUnit, it.endUnit) }.filter { it > 0 }
        if (used.isEmpty()) {
            AllUnits.take(9)
        } else {
            AllUnits.filter { it in used.min()..used.max() }
        }
    }
    // 一次分组，避免每页都全量 filter
    val byWeek = remember(sessions) { sessions.groupBy { it.week } }

    val pagerState = rememberPagerState(
        initialPage = (viewingWeek - 1).coerceIn(0, totalWeeks - 1),
    ) { totalWeeks }

    // 外部改周（点周次条）→ 平滑翻过去
    LaunchedEffect(viewingWeek) {
        val target = (viewingWeek - 1).coerceIn(0, totalWeeks - 1)
        if (pagerState.currentPage != target) pagerState.animateScrollToPage(target)
    }
    // 手势翻页落定 → 回写当前周
    LaunchedEffect(pagerState) {
        snapshotFlow { pagerState.settledPage }
            .distinctUntilChanged()
            .collect { p -> onWeekChange(p + 1) }
    }

    // 表头/日期跟着跟手位置走，滑动过程中就能看到目标周的日期
    val shownWeek = (pagerState.currentPage + 1).coerceIn(1, totalWeeks)

    Box(Modifier.fillMaxSize()) {
        // 自定义背景在最底层，课程块浮在上面
        CourseBackground(bgPath, bgAlpha)

        Column(
            Modifier
                .fillMaxSize()
                .statusBarsPadding(),
        ) {
        WeekHeader(
            semester = semester,
            currentWeek = currentWeek,
            viewingWeek = shownWeek,
            onWeekChange = onWeekChange,
            onOpenBg = { showBgSheet = true },
        )
        Spacer(Modifier.height(10.dp))
        WeekStrip(
            totalWeeks = totalWeeks,
            currentWeek = currentWeek,
            viewingWeek = shownWeek,
            onSelect = onWeekChange,
        )
        Spacer(Modifier.height(8.dp))
        WeekdayStrip(semester, shownWeek, currentWeek)

        Spacer(Modifier.height(8.dp))

        // 水平翻页：跟手拖动、松手按速度吸附、到边界回弹
        HorizontalPager(
            state = pagerState,
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
            key = { it },
        ) { page ->
            val week = page + 1
            WeekGrid(
                semester = semester,
                week = week,
                slots = slots,
                byWeekday = remember(byWeek[week]) {
                    byWeek[week].orEmpty()
                        .groupBy { it.weekday }
                },
                isTodayOf = { wd -> isTodayOf(semester, week, wd) },
                onCourseClick = onCourseClick,
            )
        }
        }
    }

    if (showBgSheet) {
        BackgroundSheet(
            hasBg = bgPath != null,
            alpha = bgAlpha,
            onPick = {
                picker.launch(
                    androidx.activity.result.PickVisualMediaRequest(
                        androidx.activity.result.contract.ActivityResultContracts.PickVisualMedia.ImageOnly
                    )
                )
            },
            onAlphaChange = {
                bgAlpha = it
                store.courseBgAlpha = it
            },
            onClear = {
                runCatching {
                    java.io.File(ctx.filesDir, "bg").listFiles()?.forEach { f -> f.delete() }
                }
                bgPath = null
                store.courseBgPath = null
            },
            onDismiss = { showBgSheet = false },
        )
    }
}

/** 单周网格。每个分页各自持有纵向滚动状态。 */
@Composable
private fun WeekGrid(
    semester: Semester,
    week: Int,
    slots: List<Int>,
    byWeekday: Map<Int, List<CourseSession>>,
    isTodayOf: (Int) -> Boolean,
    onCourseClick: (CourseSession) -> Unit,
) {
    // 纵向可滚动：13 行 × 58dp 在多数手机上放不下
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState()),
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 10.dp),
        ) {
            SlotColumn(slots)
            Row(Modifier.weight(1f)) {
                for (wd in 1..7) {
                    DayColumn(
                        modifier = Modifier.weight(1f),
                        weekday = wd,
                        slots = slots,
                        sessions = byWeekday[wd].orEmpty(),
                        isToday = isTodayOf(wd),
                        onCourseClick = onCourseClick,
                    )
                }
            }
        }
        Spacer(Modifier.height(LocalFloatingBarInset.current))
    }
}

@Composable
private fun SlotColumn(slots: List<Int>) {
    val scheme = MaterialTheme.colorScheme
    Column(Modifier.width(30.dp)) {
        slots.forEach { unit ->
            Column(
                Modifier
                    .height(cellHeight)
                    .fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Text(
                    "$unit",
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Medium,
                    color = scheme.onSurfaceVariant,
                )
                Text(
                    SlotTimes[unit] ?: "",
                    fontSize = 7.5.sp,
                    color = scheme.onSurfaceVariant.copy(alpha = 0.5f),
                )
            }
        }
    }
}

private val cellHeight = 58.dp

private fun isTodayOf(semester: Semester, week: Int, weekday: Int): Boolean {
    val today = LocalDate.now()
    return semester.weekOf(today) == week && today.dayOfWeek.value == weekday
}

@Composable
private fun WeekHeader(
    semester: Semester,
    currentWeek: Int,
    viewingWeek: Int,
    onWeekChange: (Int) -> Unit,
    onOpenBg: () -> Unit = {},
) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(start = 18.dp, end = 8.dp, top = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "第 $viewingWeek 周",
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.Medium,
                )
                if (viewingWeek == currentWeek) {
                    Spacer(Modifier.width(8.dp))
                    Box(
                        Modifier
                            .clip(RoundedCornerShape(6.dp))
                            .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.16f))
                            .padding(horizontal = 7.dp, vertical = 2.dp),
                    ) {
                        Text(
                            "本周",
                            fontSize = 10.5.sp,
                            fontWeight = FontWeight.Medium,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                }
            }
            Spacer(Modifier.height(3.dp))
            val days = weekRangeText(semester, viewingWeek)
            Text(
                days,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        IconButton(onClick = { onWeekChange(viewingWeek - 1) }, enabled = viewingWeek > 1) {
            Icon(
                Icons.Rounded.ChevronLeft,
                contentDescription = "上一周",
                tint = if (viewingWeek > 1) MaterialTheme.colorScheme.onSurface
                else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.3f),
            )
        }
        IconButton(
            onClick = { onWeekChange(viewingWeek + 1) },
            enabled = viewingWeek < semester.totalWeeks,
        ) {
            Icon(
                Icons.Rounded.ChevronRight,
                contentDescription = "下一周",
                tint = if (viewingWeek < semester.totalWeeks) MaterialTheme.colorScheme.onSurface
                else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.3f),
            )
        }
        // 背景设置入口，就放在课表页，不用去总设置里翻
        IconButton(onClick = onOpenBg) {
            Icon(
                Icons.Rounded.Wallpaper,
                contentDescription = "课表背景",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

private fun weekRangeText(semester: Semester, week: Int): String {
    val mon = semester.dateOf(week, 1)
    val sun = semester.dateOf(week, 7)
    return "${mon.monthValue}月${mon.dayOfMonth}日 — ${sun.monthValue}月${sun.dayOfMonth}日"
}

/**
 * 星期与日期行。
 *
 * 必须和下面的课程网格用**完全相同的水平布局**：
 * 同样的 padding + 同宽的节次列 + 同样的 weight(1f) 列宽。
 * 早先这里写成 `padding(horizontal = 44.dp)`，而网格是 `10.dp + 30.dp 节次列`，
 * 两边坐标系对不上，日期数字就明显偏在课程列中间之外。
 */
@Composable
private fun WeekdayStrip(semester: Semester, week: Int, currentWeek: Int) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 10.dp)) {
        // 与 SlotColumn 同宽，占位不画内容
        Box(Modifier.width(30.dp))
        Row(Modifier.weight(1f)) {
            for (wd in 1..7) {
                val date = semester.dateOf(week, wd)
                val isToday = semester.weekOf(LocalDate.now()) == week &&
                    LocalDate.now().dayOfWeek.value == wd
                Column(
                    // 与 DayColumn 完全同构：weight(1f) + 居中
                    Modifier
                        .weight(1f)
                        .padding(horizontal = 1.5.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(
                        WeekdayLabels[wd - 1],
                        fontSize = 10.5.sp,
                        color = if (isToday) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.onSurfaceVariant,
                        fontWeight = if (isToday) FontWeight.Medium else FontWeight.Normal,
                    )
                    Spacer(Modifier.height(1.dp))
                    Text(
                        "${date.dayOfMonth}",
                        fontSize = 11.sp,
                        color = if (isToday) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                        fontWeight = if (isToday) FontWeight.Medium else FontWeight.Normal,
                    )
                }
            }
        }
    }
}

@Composable
private fun DayColumn(
    modifier: Modifier,
    weekday: Int,
    slots: List<Int>,
    sessions: List<CourseSession>,
    isToday: Boolean,
    onCourseClick: (CourseSession) -> Unit,
) {
    val slotIndex = remember(slots) { slots.withIndex().associate { it.value to it.index } }

    Box(
        modifier
            .fillMaxHeight()
            .padding(horizontal = 1.5.dp),
    ) {
        // 今天整列一条连续的淡色带（逐格画会断成一截一截）
        if (isToday) {
            Box(
                Modifier
                    .fillMaxHeight()
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(8.dp))
                    .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.055f)),
            )
        }
        // 课程块：按 startUnit 的索引做绝对偏移，高度按跨节数
        sessions.forEach { s ->
            val startIdx = slotIndex[s.startUnit] ?: return@forEach
            val endIdx = slotIndex[s.endUnit] ?: startIdx
            val span = (endIdx - startIdx + 1).coerceAtLeast(1)
            CourseBlock(
                session = s,
                span = span,
                topOffset = cellHeight * startIdx,
                onClick = { onCourseClick(s) },
            )
        }
    }
}

@Composable
private fun BoxScope.CourseBlock(
    session: CourseSession,
    span: Int,
    topOffset: androidx.compose.ui.unit.Dp,
    onClick: () -> Unit,
) {
    val color = courseColor(session.name)
    Box(
        Modifier
            .align(Alignment.TopStart)
            .offset(y = topOffset)
            .fillMaxWidth()
            .height(cellHeight * span - 3.dp)
            .padding(vertical = 1.5.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(color.copy(alpha = 0.16f))
            .border(0.8.dp, color.copy(alpha = 0.40f), RoundedCornerShape(8.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 3.5.dp, vertical = 4.dp),
    ) {
        Column {
            Text(
                session.name,
                // 列宽只有 50dp 上下，9.5sp 才能放下 5 个汉字
                fontSize = 9.5.sp,
                fontWeight = FontWeight.Medium,
                color = color,
                maxLines = if (span >= 3) 4 else if (span == 2) 3 else 2,
                overflow = TextOverflow.Ellipsis,
                lineHeight = 12.sp,
            )
            if (span >= 2) {
                Spacer(Modifier.height(2.5.dp))
                Text(
                    session.room.ifBlank { "地点待定" },
                    fontSize = 8.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.85f),
                    maxLines = if (span >= 3) 3 else 2,
                    overflow = TextOverflow.Ellipsis,
                    lineHeight = 10.sp,
                )
            }
        }
    }
}

@Composable
private fun EmptyHint(text: String) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(text, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}