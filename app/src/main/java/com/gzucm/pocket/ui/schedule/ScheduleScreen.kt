package com.gzucm.pocket.ui.schedule

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
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
import androidx.compose.foundation.layout.BoxWithConstraints
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
import androidx.compose.foundation.layout.requiredSize
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
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import androidx.compose.ui.unit.sp
import com.gzucm.pocket.core.CourseSession
import com.gzucm.pocket.ui.components.CourseBackground
import com.gzucm.pocket.ui.components.LocalFloatingBarInset
import com.gzucm.pocket.core.Semester
import com.gzucm.pocket.ui.theme.courseColor
import java.time.LocalDate

private val WeekdayLabels = listOf("一", "二", "三", "四", "五", "六", "日")

/**
 * 广中医作息。每节 40 分钟，课间 10 分钟。
 *
 * 节次编号是**连续**的 1–14，之前误以为跳号（没有 5、10、11）——
 * 那是「自己的课表里恰好没有这些节次的课」，不是系统没有。
 * 全校课表里 5 节 155 条、10 节 741 条、11 节 221 条（第 8 周实测）。
 *
 * 时间来自教务周课表页的 businessHours，并与课程接口的 qssj 交叉核对：
 * 第 5 节 13:10、第 10 节 17:20 两处吻合；
 * 10/11 节再用「40 分钟课 + 10 分钟课间」推链验证：
 * 16:30(9) → 17:20(10) → 18:10(11) → 19:00(12，已确认)。
 */
val SlotTimes: Map<Int, String> = mapOf(
    1 to "08:30", 2 to "09:20", 3 to "10:20", 4 to "11:10", 5 to "13:10",
    6 to "14:00", 7 to "14:50", 8 to "15:40", 9 to "16:30", 10 to "17:20",
    11 to "18:10", 12 to "19:00", 13 to "19:50", 14 to "20:40",
)

/**
 * 下课时间 = 上课时间 + 40 分钟。
 * 从 SlotTimes 推出来而不是手写一份，作息改了不会两边对不上。
 */
val SlotEndTimes: Map<Int, String> = SlotTimes.mapValues { (_, hhmm) ->
    val parts = hhmm.split(":")
    val h = parts.getOrNull(0)?.toIntOrNull()
    val m = parts.getOrNull(1)?.toIntOrNull()
    if (h == null || m == null) "" else {
        val total = h * 60 + m + 40
        "%02d:%02d".format((total / 60) % 24, total % 60)
    }
}

/** 全部可能的节次，用于计算跨节高度时补齐中间被跳过的号 */
private val AllUnits = (1..14).toList()

@Composable
fun ScheduleScreen(
    semester: Semester?,
    sessions: List<CourseSession>,
    currentWeek: Int,
    viewingWeek: Int,
    onWeekChange: (Int) -> Unit,
    onCourseClick: (CourseSession) -> Unit,
    /** null = 不做按周加载判断（全部已就绪）；RootScreen 会传真实集合 */
    loadedWeeks: Set<Int>? = null,
    syncing: Boolean = false,
    progressDone: Int = 0,
    progressTotal: Int = 0,
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
    /*
     * 冲突排布固定用「层叠」。
     *
     * 之前标题栏有个切换按钮（层叠 / 平铺分列），但分列在 52dp 的列宽下
     * 字只能竖排，实际没人会用 —— 按钮纯属干扰，已删。
     * 保留成常量而非删参数，是为了 placeSessions 的分支逻辑还在（测试用它）。
     */
    val overlapMode = true

    /*
     * 重叠课表的轮换状态。
     *
     * key 用「周 + 星期 + 簇编号」—— 簇编号是同一天内按开始节次排出来的，
     * 所以跨周不会串。值 = 轮了几次。
     * 只存在内存里：翻页回来还在，重启 App 就回到默认顺序，不值得落盘。
     */
    var rotations by remember { mutableStateOf<Map<String, Int>>(emptyMap()) }

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
                overlapMode = overlapMode,
                rotationSnapshot = rotations
                    .filterKeys { it.startsWith("$week-") }
                    .mapKeys { it.key.substringAfter("-").toIntOrNull() ?: -1 },
                onRotateCluster = { cid ->
                    val key = "$week-$cid"
                    rotations = rotations + (key to ((rotations[key] ?: 0) + 1))
                },
                onCourseClick = onCourseClick,
                isLoading = loadedWeeks != null && week !in loadedWeeks,
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
    overlapMode: Boolean = true,
    /** 这一周每个簇轮了几次（clusterId → 次数）。用快照而非 lambda，保证能触发重算 */
    rotationSnapshot: Map<Int, Int> = emptyMap(),
    onRotateCluster: (Int) -> Unit = {},
    onCourseClick: (CourseSession) -> Unit,
    isLoading: Boolean = false,
) {
    // 这一周还没拿到数据：别让用户对着一片空白以为课表坏了
    if (isLoading) {
        Column(
            Modifier
                .fillMaxSize()
                .padding(top = 40.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            com.gzucm.pocket.ui.components.SyncPlaceholder(done = 0, total = 0)
            Spacer(Modifier.height(10.dp))
            Text(
                "第 $week 周还在路上",
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
            )
        }
        return
    }

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
                        overlapMode = overlapMode,
                        rotationSnapshot = rotationSnapshot,
                        onRotateCluster = onRotateCluster,
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
                    lineHeight = 9.sp,
                    color = scheme.onSurfaceVariant.copy(alpha = 0.55f),
                )
                // 下课时间：比上课时间更淡，两行不至于抢注意力
                Text(
                    SlotEndTimes[unit] ?: "",
                    fontSize = 7.sp,
                    lineHeight = 8.sp,
                    color = scheme.onSurfaceVariant.copy(alpha = 0.35f),
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
                Spacer(Modifier.width(8.dp))
                if (viewingWeek == currentWeek) {
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
                } else {
                    // 翻到别的周了：给一条回得去的路，否则要一页页滑回来
                    Box(
                        Modifier
                            .clip(RoundedCornerShape(6.dp))
                            .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.16f))
                            .clickable(onClickLabel = "回到本周") { onWeekChange(currentWeek) }
                            .padding(horizontal = 8.dp, vertical = 2.dp),
                    ) {
                        Text(
                            "回到本周",
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
    overlapMode: Boolean = true,
    /** 这一周每个簇轮了几次（clusterId → 次数） */
    rotationSnapshot: Map<Int, Int> = emptyMap(),
    onRotateCluster: (Int) -> Unit = {},
    onCourseClick: (CourseSession) -> Unit,
) {
    val slotIndex = remember(slots) { slots.withIndex().associate { it.value to it.index } }
    // 时间冲突的排布：默认错位叠压，可选并排分列
    /*
     * 依赖里**不能放 lambda**。
     *
     * `rotationOf` 每次重组都是新实例，Compose 的 remember key 用它做比较
     * 可能判定「没变」而不重算 —— 点击后 rotations 变了，但 placed 还是旧的，
     * 表现就是「点了没反应」。
     *
     * 现在把整张 rotations 快照传进来当 key，值一变就必定重算。
     */
    val placed = remember(sessions, overlapMode, rotationSnapshot, weekday) {
        placeSessions(sessions, overlapMode, { cid -> rotationSnapshot[cid] ?: 0 }, weekday)
    }

    // 用 BoxWithConstraints 拿到这一列的实际宽度，才能算出每块该多宽、从哪开始
    BoxWithConstraints(
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
        // 课程块：纵向按 startUnit 偏移、高度按跨节数；横向按冲突分列切开
        placed.forEach { p ->
            val s = p.session
            val startIdx = slotIndex[s.startUnit] ?: return@forEach
            val endIdx = slotIndex[s.endUnit] ?: startIdx
            val span = (endIdx - startIdx + 1).coerceAtLeast(1)

            // xFrac / wFrac 由 placeSessions 算好，这里只做换算，避免重复推同一套比例
            val gap = if (p.cols > 1) 1.dp else 0.dp

            /*
             * 不做文字避让。
             *
             * 上层块的底色是不透明的，会把下面的字完全盖住 ——
             * 这样看起来就是「一门课 + 边上一角露出还有别的课」，
             * 视线不会被底下那行的字干扰。避让反而会把字挤得东缺一块西缺一块。
             */
            val coveredRight = 0.dp

            /*
             * **每层都写完整课名**，不缩成首字。
             *
             * 试过「只有最上层写」，结果下层只剩一个首字（「方」「毛」），
             * 反而更看不懂 —— 用户要的是「透过格子能看到下层」，不是「下层隐藏」。
             *
             * 现在：每层都写全名，靠半透明底色的深浅分出主次。
             * 上层字色不透明、底层字色淡一些，两者都能读，但主次分明。
             */
            val isTopOfCluster = p.cols <= 1 || p.col == p.cols - 1
            val showText = true

            /*
             * 每往上一层，右侧和下边各缩 [cardInset]（用户定 3dp）。
             *
             * 尺寸**跟着层级走，不跟着课程走** —— 轮换后某门课从底层升到顶层，
             * 它就会跟着缩小。这样「被上层挡住」永远只发生在下层，
             * 轮换出来的那门一定在最上面完整可见。
             */
            /*
             * 缩进量按层数区分：
             *   · 两层 → 3dp（一层厚度，克制）
             *   · 三层及以上 → 5dp（每层都要露出来，3dp 叠三层太挤，看不出有三层）
             */
            val cardInset = if (p.cols >= 3) 5.dp else 3.dp
            val shrink = cardInset * p.level
            CourseBlock(
                session = s,
                span = span,
                topOffset = cellHeight * startIdx,
                slotWidth = (maxWidth - shrink).coerceAtLeast(10.dp),
                xOffset = gap / 2,
                cols = p.cols,
                z = p.z,
                cardShrink = shrink,
                coveredRight = coveredRight,
                showText = showText,
                /*
                 * 顶部被上层压住多少。
                 *
                 * 只算「比本块 z 大、且从本块顶部或更早就开始」的那些块的底边，
                 * 取最大值 —— 文字从那条线下面开始写，一定落在可见区域。
                 */
                coverTopDp = run {
                    val coverEnd = placed.filter { q ->
                        q !== p && q.clusterId == p.clusterId && q.z > p.z &&
                            q.session.startUnit <= p.session.startUnit
                    }.maxOfOrNull { q ->
                        val qi = slotIndex[q.session.endUnit] ?: return@maxOfOrNull 0
                        qi - startIdx + 1
                    } ?: 0
                    cellHeight * coverEnd.coerceAtLeast(0)
                },
                onClick = { onCourseClick(s) },
            )
        }

        /*
         * 轮换按钮：**整组一个**，放在这组重叠课程的右下角。
         *
         * 原来是每块内部各挂一个，但只有最上层那块能点到 ——
         * 三课程重叠时下面两层根本点不着。现在按簇分组，一组只画一个。
         * 点它整组轮换：A-B-C → B-C-A → C-A-B → A-B-C（点三次回到原样）。
         */
        if (overlapMode) {
            placed.groupBy { it.clusterId }
                .filter { (_, group) -> group.size >= 2 }
                .forEach { (cid, group) ->
                    // 组的纵向范围：最早开始的课顶 → 最晚结束的课底
                    val firstIdx = group.minOf { slotIndex[it.session.startUnit] ?: 0 }
                    val lastIdx = group.maxOf {
                        slotIndex[it.session.endUnit] ?: (slotIndex[it.session.startUnit] ?: 0)
                    }
                    val spanAll = (lastIdx - firstIdx + 1).coerceAtLeast(1)
                    Box(
                        /*
                         * 顺序很关键，踩过坑：
                         *
                         * 1) zIndex(99) 必须在最前 —— 课程块用 z=level(0/1/2)，
                         *    没设 zIndex 的按钮会被它们盖住，既看不见也点不到。
                         * 2) **requiredSize 而不是 size** —— 父容器是 Box，
                         *    给的是「最多 maxWidth」的约束，size 在 offset 之后
                         *    会被撑成 Material 最小触摸目标（48dp），
                         *    结果按钮的实际点击区变成 144×144px，盖住旁边好几列。
                         * 3) clickable 放在 requiredSize 之后 —— 这样点击区
                         *    正好是 28dp，不多占。
                         */
                        Modifier
                            .align(Alignment.TopStart)
                            .zIndex(99f)
                            .offset(
                                x = (maxWidth - 28.dp).coerceAtLeast(0.dp),
                                y = cellHeight * firstIdx + cellHeight * spanAll - 28.dp,
                            )
                            .requiredSize(28.dp)
                            .clickable(onClickLabel = "切换重叠课程") { onRotateCluster(cid) },
                        contentAlignment = Alignment.BottomEnd,
                    ) {
                        Box(
                            Modifier
                                .size(13.dp)
                                .padding(end = 1.dp, bottom = 1.dp)
                                .clip(RoundedCornerShape(3.5.dp))
                                .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.96f))
                                .border(
                                    0.7.dp,
                                    MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                                    RoundedCornerShape(3.5.dp),
                                ),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                "⇄",
                                fontSize = 8.5.sp,
                                lineHeight = 9.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
        }
    }
}

/**
 * 一天的课排成几列。
 *
 * [cols] 是这一「重叠簇」一共分了几列，[col] 是它自己在第几列。
 * 只有 [overlap] 的课才拿到 cols > 1，其余仍是整列宽。
 */
internal data class Placed(
    val session: CourseSession,
    val col: Int,
    val cols: Int,
    /** 横向起点，占整列宽的比例（0..1）。错位叠压靠它 */
    val xFrac: Float = 0f,
    /** 横向宽度，占整列宽的比例（0..1） */
    val wFrac: Float = 1f,
    /** 层级：后叠的盖住前面的边角，越大越上 */
    val z: Float = 0f,
    /** 这一簇的标识（同一天同一簇的课共用一个值），轮换时用来定位 */
    val clusterId: Int = 0,
    /**
     * 卡片层级。0 = 最底层（满宽满高），每往上一层缩进一圈，
     * 下层就从右侧和下边露出来 —— 这就是「卡片叠卡片」的效果。
     */
    val level: Int = 0,
)

/**
 * 按时间重叠关系给一天里的课分列。
 *
 * 两节课只要有节次交集就必须分到不同列 —— 这是唯一能保证
 * 「两块不互相遮盖、文字不互相压」的做法。分列用贪心：
 * 按开始节次排序，依次放进第一个「已有的课都已在它开始前结束」的列。
 *
 * 先按交集把课分成若干「簇」（交集可传递：A 撞 B、B 撞 C，
 * 即使 A 不撞 C 也算一簇），再在簇内分列，这样一个簇有几列就都几列，
 * 不会出现同簇里某块宽、某块窄的错位。
 */
/**
 * 把课程色加工成「可阅读的文字色」——**深色提亮、浅色压暗**。
 *
 * 之前写死成「亮度至少 0.82」，深色主题下没问题，
 * 但浅色主题是浅底，提亮等于白字压白底 —— 整张课表几乎看不见字。
 *
 * 现在的规则：
 *   · 深色主题（浅字压深底）→ 亮度拉到 >= 0.82，饱和度压到 55%
 *   · 浅色主题（深字压浅底）→ 亮度压到 <= 0.42，饱和度提到 65%
 *
 * 两边都保留色相，所以仍然一眼认得出是同一门课。
 * 对比度都能到 4.5:1 以上（WCAG AA 正文标准）。
 */
internal fun textColorFor(c: Color, dark: Boolean): Color {
    val hsv = FloatArray(3)
    android.graphics.Color.colorToHSV(
        android.graphics.Color.rgb(
            (c.red * 255).toInt().coerceIn(0, 255),
            (c.green * 255).toInt().coerceIn(0, 255),
            (c.blue * 255).toInt().coerceIn(0, 255),
        ),
        hsv,
    )
    if (dark) {
        hsv[1] = (hsv[1] * 0.55f).coerceAtLeast(0.18f)
        hsv[2] = hsv[2].coerceAtLeast(0.82f)
    } else {
        // 浅底上要深字。饱和度略微提高，压暗后色相才不会糊成一片灰
        hsv[1] = (hsv[1] * 1.15f).coerceAtMost(1f).coerceAtLeast(0.42f)
        hsv[2] = hsv[2].coerceAtMost(0.42f)
    }
    return Color(android.graphics.Color.HSVToColor(hsv))
}

/** 错位时每块往右让出的比例，按这一簇总共几门课决定 */
internal fun insetOf(cols: Int): Float = if (cols <= 1) 0f else 0.10f

internal fun placeSessions(
    sessions: List<CourseSession>,
    /** true = 错位叠压；false = 并排分列 */
    overlap: Boolean = true,
    /** 某个簇轮了几次，用来决定谁在最上面 */
    rotationOf: (Int) -> Int = { 0 },
    /**
     * 这一天的编号（1..7）。
     *
     * 簇编号原本是按天独立从 0 开始的，于是周一的 0 号簇和周二的 0 号簇
     * 撞成同一个 id —— 点周一的按钮，所有「每天第一个重叠簇」会一起轮换。
     * 加星期偏移后每个簇全局唯一。
     */
    dayOffset: Int = 0,
): List<Placed> {
    if (sessions.size <= 1) return sessions.map { Placed(it, 0, 1) }

    // 开始节次相同的话，长课排前面：左边宽一点的是主课，符合阅读习惯
    val sorted = sessions.sortedWith(compareBy({ it.startUnit }, { -it.endUnit }))

    /*
     * 分簇：和**簇内任何一门**有交集就归入同一簇。
     *
     * 之前用「s.startUnit > clusterEnd 就开新簇」，clusterEnd 是簇里最晚结束的节次。
     * 这个判据在 1-3、2-5、4-6 上会出错：
     *   A(1-3) 开簇，clusterEnd=3；
     *   B(2-5) 与 3 相交 → 归入，clusterEnd=5；
     *   C(4-6) 与 5 相交 → 归入 —— 这一步是对的。
     * 但 A 与 C 本身不重叠，结果三块被当成必须分三列，
     * 而实际上 A 和 C 可以共用一列。所以下面分列时用贪心复用。
     */
    val clusters = mutableListOf<MutableList<CourseSession>>()
    for (s in sorted) {
        val target = clusters.firstOrNull { cl ->
            cl.any { it.startUnit <= s.endUnit && s.startUnit <= it.endUnit }
        }
        if (target != null) target.add(s) else clusters.add(mutableListOf(s))
    }

    val out = mutableListOf<Placed>()
    clusters.forEachIndexed { localId, cluster ->
        /*
         * 全局唯一的簇编号。
         *
         * 用「天序号左移 + 当天序号」而不是 `day*100 + local`：
         * 后者靠「一天不会有 100 个重叠簇」这个假设（虽然确实成立，
         * 14 节课塞不下 100 个簇），但既然是编码，就别留这种隐形前提。
         *
         * 左移 8 位 = 每天最多 256 个簇，且按位拼装无歧义：
         *   周一第 0 簇 = 0x0100，周二第 0 簇 = 0x0200，绝不相等
         *   周一第 0、1 簇 = 0x0100、0x0101，也不会相等
         */
        val clusterId = (dayOffset shl 8) or localId
        // 每列记住「最后一节上到第几节」，新来的课只要接在后面就能复用这一列
        val colEnds = mutableListOf<Int>()
        val myCol = IntArray(cluster.size)
        cluster.forEachIndexed { i, s ->
            var c = 0
            while (c < colEnds.size && colEnds[c] >= s.startUnit) c++
            if (c == colEnds.size) colEnds.add(s.endUnit) else colEnds[c] = s.endUnit
            myCol[i] = c
        }
        val cols = colEnds.size.coerceAtLeast(1)

        /*
         * 错位叠压。为什么要分两种策略：
         *
         * 一列只有 52dp（411dp 屏 / 7 天）。要让一块横排读出课名，至少要 33dp。
         * 两块叠压时，每块可读区 = 1 - 重叠 = 2*inset，取 inset=0.30 得 31dp，够用。
         * 三块就崩了：3 × 33 = 99dp 塞不进 52dp，无论怎么摆都读不出来。
         *
         * 所以：
         *   2 块 → 对称叠压，两块都完整露出文字区
         *   3 块及以上 → 阶梯式，**只有最上层给文字**，下面几层退化成一窄条色标，
         *              一眼能看出「这里还压着课」，点开单条详情看全名
         */
        /*
         * 叠压：每块都保持**整列宽减去一条错位**，只向右错开 10%。
         *
         * 用户要的效果是「几乎完全重合，只在边上露出下面那块的一角」，
         * 所以错位量很小。文字不避让 —— 靠「上层底色更实」把下方文字盖住，
         * 眼睛只看到最上面那门课的字，不会两行叠在一起。
         *
         * 顺序支持轮换：用户点块右下角的小按钮，把最上面那门挪到最后，
         * 依次露出下面的课。rotate 就是这一簇轮了几次。
         */
        if (!overlap) {
            // 并排分列：各占 1/cols，互不重叠
            cluster.forEachIndexed { i, s ->
                out += Placed(
                    session = s, col = i, cols = cols,
                    xFrac = i * (1f / cols), wFrac = 1f / cols,
                    z = 0f, clusterId = clusterId,
                )
            }
        } else {
            val inset = insetOf(cols)

            /*
             * 叠压时的横向排布，按「谁是长课」决定。
             *
             * 关键：**只有长度相近的课才值得并排错开**。
             *   · 长度相近 → 两块都要占大部分横向空间，只能错开叠
             *   · 短课嵌在长课里 → 短课只占垂直方向的一小段，
             *     横向没必要让位，直接盖在长课上面就行
             *
             * 反例（改之前的 bug）：周三「大学心理健康教育 6-10 节」+
             * 「马拉松运动医学 6-7 节」，两块都被右移，马拉松把长课盖掉了 90%，
             * 长课等于消失了。而实际上短课只占上半段，长课下半段本来就空着。
             */
            val lens = cluster.map { it.endUnit - it.startUnit }
            val maxLen = lens.max()
            val minLen = lens.min()

            /*
             * 什么时候用「卡片叠放」？
             *
             * ① 一大一小（长的把短的整段包住）→ 层叠，长的边缘露出来
             * ② 节次**完全相同** → 也层叠。这种情况下没有纵向错位空间，
             *    靠上层缩小一圈露出下层，才是唯一能看出「压着两门」的办法。
             *
             * 之前漏了 ②：两门 1-3 完全重叠时长度相等 → 判为「长度相近」，
             * 走了 10% 错位分支，两块几乎完全重合，看起来只有一门。
             */
            val sameSpan = cluster.all {
                it.startUnit == cluster[0].startUnit && it.endUnit == cluster[0].endUnit
            }
            val nested = sameSpan ||
                (maxLen > 0 && (maxLen - minLen) * 2 > maxLen)

            val rot = rotationOf(clusterId)
            val order = cluster.indices.map { (it + rot) % cluster.size }

            /*
             * 时间重叠的排法，只有两种：
             *
             * 【A】卡片叠放（有包含关系 / 完全重叠时用）
             *   最底层满宽满高，每往上一层右侧和下边各缩进一点，
             *   下层从这两条边露出来 —— 一眼看出「下面还压着课」，而且露出的
             *   正是下层的边缘，不会被误认为是一门完整的课。
             *   上层的文字区几乎不损失（只缩 6%），课名照常读。
             *
             * 【B】对称错位（长度相近、互相错开时用）
             *   两块几乎一样长，谁也不含谁，就各错 10% 叠着。
             */
            if (nested) {
                /*
                 * 谁在下面？长的在下面（它的边缘露出来更有信息量），
                 * 轮换时整体左移，谁都能被翻到最上面。
                 */
                val byLenDesc = cluster.indices.sortedByDescending { lens[it] }
                val rotN = rot % cluster.size
                val seq = List(cluster.size) { byLenDesc[(it + rotN) % cluster.size] }

                seq.forEachIndexed { level, idx ->
                    out += Placed(
                        session = cluster[idx],
                        col = level,
                        cols = cols,
                        xFrac = 0f,
                        // 宽度在 CourseBlock 里按 level 算，这里占位
                        wFrac = 1f,
                        z = level.toFloat(),
                        clusterId = clusterId,
                        // 第 0 层（最底）是满的，往上依次缩进
                        level = level,
                    )
                }
            } else {
                // 长度相近：对称错位叠压，每块 90% 宽、错开 10%
                order.forEachIndexed { visual, idx ->
                    val src = cluster[idx]
                    out += Placed(
                        session = src,
                        col = visual,
                        cols = cols,
                        xFrac = inset * visual,
                        wFrac = 1f - inset * visual,
                        z = visual.toFloat(),
                        clusterId = clusterId,
                    )
                }
            }
        }    }
    return out
}

@Composable
private fun BoxScope.CourseBlock(
    session: CourseSession,
    span: Int,
    topOffset: androidx.compose.ui.unit.Dp,
    slotWidth: androidx.compose.ui.unit.Dp,
    xOffset: androidx.compose.ui.unit.Dp,
    cols: Int,
    z: Float,
    /** 右侧被后叠的块压住多少，文字要让开 */
    coveredRight: androidx.compose.ui.unit.Dp,
    /** 三层以上时，下面几层只做色标不写字 */
    showText: Boolean = true,
    /** 顶部被上层盖住的像素高度；> 0 表示文字要从这条线下面开始写 */
    coverTopDp: androidx.compose.ui.unit.Dp = 0.dp,
    /** 卡片叠放时本层往下缩进多少（露出下层的下边缘） */
    cardShrink: androidx.compose.ui.unit.Dp = 0.dp,
    /** 有重叠且本块在最上层时，右下角显示轮换按钮；null = 不显示 */
    onRotate: (() -> Unit)? = null,
    onClick: () -> Unit,
) {
    val color = courseColor(session.name)
    // 叠压时的实心底色：用当前主题的 surface，保证任何背景图下都不透
    val surfaceBase = MaterialTheme.colorScheme.surface
    /*
     * 深浅主题要两套字色策略：深色提亮、浅色压暗。
     * 用 surface 的亮度判断比 isSystemInDarkTheme 可靠 ——
     * 主题可能被用户强制覆盖，但 surface 一定反映当前真实底色。
     */
    val isDark = surfaceBase.luminance() < 0.5f

    /*
     * 错位叠压：块本身保持接近整列宽，只把右侧一小条让给后一块；
     * 文字区再从右边让出被压的部分。
     *
     * 这样两门课**永远不会互相压字** —— 不是靠字号缩小硬挤，
     * 而是靠「文字只呆在没被盖住的那块矩形里」。
     */
    val overlapped = cols > 1
    // 还有更上层的块压着自己 —— 露出的那条边需要更亮才看得见
    val hasUpper = coverTopDp > 0.dp || cardShrink > 0.dp
    val hPad = if (overlapped) 2.dp else 3.5.dp
    val corner = if (overlapped) 6.dp else 8.dp

    /*
     * 缩放做动画过渡。
     *
     * 轮换时某一块会从「上层小卡片」变成「下层大卡片」，
     * 如果宽高直接跳变，看起来像是两块在闪。
     * 用 animateDpAsState 让宽高渐变，视觉上就是「它缩下去了 / 它长上来了」。
     *
     * 用 tween 220ms + FastOutSlowIn：短、跟手，不像弹簧那样晃。
     */
    val animWidth by animateDpAsState(
        targetValue = slotWidth,
        animationSpec = tween(durationMillis = 220, easing = FastOutSlowInEasing),
        label = "cardWidth",
    )
    val animShrink by animateDpAsState(
        targetValue = cardShrink,
        animationSpec = tween(durationMillis = 220, easing = FastOutSlowInEasing),
        label = "cardShrink",
    )
    /*
     * 层级**不做动画**。
     *
     * 试过给 zIndex 也加渐变，结果过渡期间两块的层级在中间值上打架，
     * 会看到它们互相穿透、闪烁。层级必须瞬间切换，
     * 只让尺寸渐变就够了 —— 视觉上已经是「缩下去/长上来」。
     */

    Box(
        Modifier
            .align(Alignment.TopStart)
            .offset(x = xOffset, y = topOffset)
            // 越靠右越上层：左侧那门课的文字区完整，右侧那门只压住它的边角
            .zIndex(z)
            .width(animWidth)
            // 高度也缩一圈：下层从下边露出来
            .height(cellHeight * span - 3.dp - animShrink)
            .padding(vertical = 1.5.dp)
            .clip(RoundedCornerShape(corner))
            /*
             * 叠压时格子**半透明**，能透出下面那块。
             *
             * 取舍：完全不透明 → 下面的课看不见，不知道叠了什么；
             *       太透明 → 两块的字糊在一起，都读不了。
             * 折中：底色 0.55、文字仍用不透明的高对比色。
             * 这样「下层那块」能隐约透出来（轮廓+色块），
             * 但最上面那门的字依然清晰 —— 字是看不清下层的唯一屏障。
             */
            .background(
                if (overlapped) {
                    /*
                     * 底色必须**浅**，因为文字用的也是课程色。
                     *
                     * 踩过的坑：为了让「透出下层」更明显，把底色提到 0.78，
                     * 结果橙色字压在橙色底上，几乎同色 —— 实机放大 4 倍看，
                     * 毛概那格的字要使劲眯眼才认得出。这是彻底做错了。
                     *
                     * 现在的分工：
                     *   · 底色浅（0.20）→ 字（同色系、实心）自然浮起来
                     *   · 「有几层」靠**边框**表达，不靠底色浓度
                     *   · 下层能透过上层看到，靠的是底色半透明本身
                     */
                    color.copy(alpha = if (hasUpper) 0.14f else 0.26f)
                } else {
                    color.copy(alpha = 0.16f)
                }
            )
            /*
             * 边框是「分几层」的主要信号 —— 底色浅了之后只能靠它。
             *
             * 上层的框亮且粗一点（1.2dp / 0.95），往下依次减弱（0.85 / 0.6），
             * 叠起来时能看到几条深浅不同的边，一眼数得出有几层。
             */
            .border(
                if (overlapped) (if (hasUpper) 0.8.dp else 1.2.dp) else 0.8.dp,
                color.copy(
                    alpha = when {
                        hasUpper -> 0.6f
                        overlapped -> 0.95f
                        else -> 0.40f
                    }
                ),
                RoundedCornerShape(corner),
            )
            .clickable(onClick = onClick)
            // 右侧让出被压住的宽度 + 一点呼吸
            .padding(
                start = hPad,
                end = hPad + coveredRight,
                top = if (overlapped) 2.5.dp else 4.dp,
                bottom = if (overlapped) 2.5.dp else 4.dp,
            ),
    ) {
        /*
         * 轮换按钮：右下角一个小方块。
         *
         * 放在这个 Box 里绝对定位到右下角，不占正文的行走 ——
         * 否则课名会少显示一行。点它只轮换，不打开详情。
         */
        if (onRotate != null) {
            /*
             * 触摸区 28dp，视觉方块 14dp。
             *
             * 之前视觉和触摸都是 13dp —— 远小于 Material 建议的 48dp，
             * 真机上几乎点不中，用户会以为按钮坏了。
             * 做法：外层 Box 撑到 28dp 只负责接点击，内层画可见的小方块。
             */
            Box(
                Modifier
                    .align(Alignment.BottomEnd)
                    .size(28.dp)
                    .clickable(onClickLabel = "切换重叠课程", onClick = onRotate),
                contentAlignment = Alignment.BottomEnd,
            ) {
                Box(
                    Modifier
                        .size(14.dp)
                        .padding(bottom = 3.dp, end = 3.dp)
                        .clip(RoundedCornerShape(4.dp))
                        .background(surfaceBase.copy(alpha = 0.96f))
                        .border(0.7.dp, color.copy(alpha = 0.75f), RoundedCornerShape(4.dp)),
                    contentAlignment = Alignment.Center,
                ) {
                    Text("⇄", fontSize = 9.sp, lineHeight = 10.sp, color = color)
                }
            }
        }

        if (!showText) {
            /*
             * 被压住的层：不画完整课名（放不下），但也不能是纯色块 ——
             * 那样看起来像个空档，还会被误认为是占位。
             * 给竖排的首字 + 一个点，能认出是哪门课，也知道点开有内容。
             */
            Text(
                session.name.take(1),
                fontSize = 8.sp,
                fontWeight = FontWeight.Medium,
                color = color.copy(alpha = 0.95f),
                maxLines = 1,
                overflow = TextOverflow.Clip,
            )
            return@Box
        }

        /*
         * 顶部被上层盖住时，文字要落在「没被遮挡的那段」。
         *
         * 用 top padding 精确下沉到遮挡区域的**下沿**，而不是一路沉到底 ——
         * 沉到底会和上面那门课离得太远，看起来像两门不相关的课。
         */
        Column(
            modifier = if (coverTopDp > 0.dp) {
                Modifier.fillMaxSize().padding(top = coverTopDp)
            } else {
                Modifier.fillMaxSize()
            },
        ) {
            Text(
                session.name,
                // 错位后仍有接近整列宽，字号不用缩太多
                fontSize = if (overlapped) 8.5.sp else 9.5.sp,
                fontWeight = FontWeight.Medium,
                /*
                 * 文字要**提亮**，不能用和底色同一个课程色。
                 *
                 * 深色主题下，课程色本身偏暗（橙 #B98A3E 之类），
                 * 压在同样偏暗的半透明底上，对比度只有 2:1 左右 ——
                 * 1:1 看是「能认但发褐」，放大 4 倍才勉强舒服，这不合格。
                 *
                 * 做法：保留课程色的色相，把亮度拉到 0.82、饱和度略降，
                 * 在深底上对比度能到 6:1 以上，主力阅读区就清晰了。
                 */
                color = textColorFor(color, isDark),
                // 叠压后横向仍接近整列宽（约 36dp），能横排 3 个字；
                // 竖向够长就多折几行，别动不动截断
                maxLines = when {
                    span >= 6 -> 20
                    span >= 3 -> 12
                    span == 2 -> 7
                    else -> 4
                },
                overflow = TextOverflow.Ellipsis,
                lineHeight = if (overlapped) 10.5.sp else 12.sp,
            )
            if (span >= 2 && !overlapped) {
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