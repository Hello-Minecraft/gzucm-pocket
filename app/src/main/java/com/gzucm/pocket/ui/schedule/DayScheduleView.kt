package com.gzucm.pocket.ui.schedule

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.ui.graphics.luminance
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.gzucm.pocket.core.CourseSession
import com.gzucm.pocket.core.Semester
import com.gzucm.pocket.core.SlotEndTimes
import com.gzucm.pocket.core.SlotTimes
import com.gzucm.pocket.core.phaseOf
import com.gzucm.pocket.core.SessionPhase
import com.gzucm.pocket.ui.components.LocalFloatingBarInset
import com.gzucm.pocket.ui.components.frostedBackdrop
import com.gzucm.pocket.ui.theme.courseColor
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import java.time.LocalTime
import java.time.LocalDate

/**
 * 日视图：一次只看一天，卡片堆叠。
 *
 * 为什么加这个视图（致辉 2026-11-01 定）：
 *   周视图一屏塞七天，每块只有一个课名，教师和教室要眯着眼看。
 *   有同学反馈「日视图更适合看课」—— 一天就几门课，能写全信息。
 *
 * 布局分两块，和截图（教务系统的星期课表）一致：
 *   上：星期条（日一二三四五六 + 日期），滑块切换，当前天高亮
 *   下：当天的课，一张卡一门，高度自适应内容
 *
 * **周的切换不在这里做**：顶部「第 N 周」那一条仍然归 ScheduleScreen 管，
 * 两处都改周会打架。这里只负责「这一周里的哪一天」。
 */
@Composable
internal fun DayScheduleView(
    semester: Semester,
    week: Int,
    currentWeek: Int,
    sessions: List<CourseSession>,
    onCourseClick: (CourseSession) -> Unit,
    /**
     * 滑动跨周时通知外部。
     *
     * 日视图自己算得出周次，但顶部「第 N 周」和周条归父组件管，
     * 不回调的话滑到下一周、顶部还写着旧周次。
     */
    onWeekChange: (Int) -> Unit = {},
    /** 初始停在哪一天（1=周一 … 7=周日）。默认今天；不是本周就落周一 */
    initialDay: Int = 1,
    modifier: Modifier = Modifier,
) {
    /*
     * 「现在」每 30 秒走一次，整个日视图只有这一份（不放进 Pager 每页各建一个）。
     * 停在页面上到点要能自动切到下一节「正在上」，跨 0 点「今天」也要跟着变。
     */
    var today by remember { mutableStateOf(LocalDate.now()) }
    var nowMinutes by remember { mutableIntStateOf(LocalTime.now().let { it.hour * 60 + it.minute }) }
    LaunchedEffect(Unit) {
        while (true) {
            kotlinx.coroutines.delay(30_000)
            nowMinutes = LocalTime.now().let { it.hour * 60 + it.minute }
            today = LocalDate.now()
        }
    }
    val todayWeekday = today.dayOfWeek.value

    /*
     * Pager 以「整学期的第几天」为索引，而不是「这周的第几天」。
     *
     * 为什么这么改（致辉 2026-11-01 报的）：
     *   之前 Pager 固定 7 页，翻到周日再往左滑只能回弹 ——
     *   滑不出下一周的周一，得先回去点周条。
     *
     * 现在页数 = totalWeeks * 7，页 i 就是学期第 i 天：
     *   周日（第 6 天）往左滑 → 第 7 天 = 下一周周一 ✅
     *   周一（第 7k 天）往右滑 → 第 7k-1 天 = 上一周周日 ✅
     * 周次不再由外部驱动，而是从页索引反算出来，两边永远一致。
     */
    val totalDays = (semester.totalWeeks.coerceAtLeast(1)) * 7
    val firstMonday = semester.firstMonday

    // 初始页：视图打开时应该落在「当前查看的那一周的某一天」
    fun pageOf(week: Int, weekday: Int): Int {
        val raw = (week - 1) * 7 + (weekday - 1)
        return raw.coerceIn(0, totalDays - 1)
    }

    val startPage = remember(week, currentWeek, totalDays) {
        val day = if (week == currentWeek) todayWeekday else initialDay
        pageOf(week, day)
    }

    val pagerState = rememberPagerState(initialPage = startPage) { totalDays }
    val scope = rememberCoroutineScope()

    /*
     * 外部改周（点周条）→ 平滑翻过去。
     *
     * 只在「当前页不在目标周内」时才动，否则用户刚滑到周三、
     * 父组件因为别的原因重组，会被硬拽回周一。
     */
    /*
     * 防回环：滑动 → onWeekChange → 父组件 week 变 → 这里再滚回去。
     *
     * 判据是「当前页不在目标周」才动。滑动时当前页已经在目标周内，
     * 所以这个 effect 不会做任何事，不会把手势打断。
     *
     * 再加 isScrollInProgress 判断：手指还按着时一律不抢。
     */
    LaunchedEffect(week) {
        val targetWeekOfPage = pagerState.currentPage / 7 + 1
        if (targetWeekOfPage != week && !pagerState.isScrollInProgress) {
            val day = if (week == currentWeek) todayWeekday else initialDay
            pagerState.scrollToPage(pageOf(week, day))
        }
    }

    /*
     * 滑到哪一周就回写哪一周。
     *
     * 用 currentPage 而不是 settledPage：settled 只在完全停稳时更新，
     * 实测出现「星期条已经回到第 8 周、标题还写第 9 周」的错位
     * （致辉 2026-11-01 实机发现）—— 因为 settled 还没跟上。
     *
     * currentPage 在跨过页边界时就变，标题和周条能立刻跟上手指。
     * 用 distinctUntilChanged 去重，不会因为半像素抖动重复回调。
     */
    /*
     * ⚠️ 这里必须用 rememberUpdatedState，不能直接读 week / onWeekChange。
     *
     * `LaunchedEffect(pagerState)` 的 key 里没有 week，所以 block 只在
     * pagerState 变化时启动一次，里面捕获的 week 永远停在**首次启动时的值**。
     *
     * 实机表现（致辉 2026-11-01）：
     *   第 8 周 → 左滑到第 9 周：w=9 != week(8) → 回调 ✅
     *   第 9 周 → 右滑回第 8 周：w=8 == week(8) → 判定没变，**不回调** ❌
     * 于是星期条回到了第 8 周，标题却停在「第 9 周」。
     *
     * rememberUpdatedState 让闭包每次都读到最新值，两个方向都能正常回调。
     */
    val latestWeek by rememberUpdatedState(week)
    val latestOnWeekChange by rememberUpdatedState(onWeekChange)

    LaunchedEffect(pagerState) {
        snapshotFlow { pagerState.currentPage }
            .distinctUntilChanged()
            .collect { page ->
                val w = (page / 7 + 1).coerceIn(1, semester.totalWeeks.coerceAtLeast(1))
                if (w != latestWeek) latestOnWeekChange(w)
            }
    }

    // 全部课按「周-星期」预分组
    val byDay = remember(sessions) {
        sessions.groupBy { it.week to it.weekday }
    }

    /*
     * 星期条跟着 Pager 走：滑到下一周时，星期条上的日期也要跟着变。
     *
     * 用 currentPage 而不是外部传的 week —— 手指滑到一半时
     * week 还没更新，用 currentPage 才能让日期立刻跟上。
     */
    val shownPage by remember { derivedStateOf { pagerState.currentPage } }
    val shownWeek = shownPage / 7 + 1
    val shownWeekday = shownPage % 7 + 1

    Column(modifier.fillMaxSize()) {
        // ── 星期条 ──────────────────────────────────────────
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 10.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            for (wd in 1..7) {
                val date = semester.dateOf(shownWeek, wd)
                val selected = shownWeekday == wd
                val isToday = shownWeek == currentWeek && todayWeekday == wd
                DayTab(
                    label = WeekdayLabels[wd - 1],
                    // 日期只用「月-日」，不带年份，格子窄放不下
                    date = "%02d-%02d".format(date.monthValue, date.dayOfMonth),
                    selected = selected,
                    isToday = isToday,
                    modifier = Modifier.weight(1f),
                    onClick = {
                        // 点星期条 = 直接跳到「同一周的那一天」，不跨周
                        val target = pageOf(shownWeek, wd)
                        scope.launch { pagerState.animateScrollToPage(target) }
                    },
                )
            }
        }

        // ── 当天的课 ────────────────────────────────────────
        HorizontalPager(
            state = pagerState,
            modifier = Modifier.fillMaxSize(),
            // 多留两页：相邻周的页面也能立刻渲染，跨周滑动不白屏
            beyondViewportPageCount = 2,
        ) { page ->
            val w = page / 7 + 1
            val weekday = page % 7 + 1
            val date = firstMonday.plusDays(page.toLong())
            val list = byDay[w to weekday].orEmpty().sortedBy { it.startUnit }

            if (list.isEmpty()) {
                EmptyDay(
                    isToday = w == currentWeek && todayWeekday == weekday,
                    date = date,
                )
            } else {
                LazyColumn(
                    Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(
                        start = 14.dp, end = 14.dp, top = 4.dp,
                        bottom = LocalFloatingBarInset.current + 16.dp,
                    ),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(list, key = { "${it.name}|${it.startUnit}|${it.endUnit}" }) { s ->
                        DayCourseCard(
                            session = s,
                            /*
                             * 只有「正在看的这天就是今天」时才算现在时刻。
                             *
                             * 别的时候传 -1：phaseOf 会判成 UPCOMING，
                             * 于是别的日期不会出现「正在上」—— 否则用户
                             * 翻到上周三，那天的课也会被标成正在上。
                             */
                            nowMinutes = if (w == currentWeek && todayWeekday == weekday) {
                                nowMinutes
                            } else {
                                -1
                            },
                            onClick = { onCourseClick(s) },
                        )
                    }
                }
            }
        }
    }
}

/** 星期条上的一格：星期 + 日期，选中时整块填充主色 */
@Composable
private fun DayTab(
    label: String,
    date: String,
    selected: Boolean,
    isToday: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    val bg = when {
        selected -> scheme.primary
        else -> androidx.compose.ui.graphics.Color.Transparent
    }
    val fg = when {
        selected -> scheme.onPrimary
        isToday -> scheme.primary
        else -> scheme.onSurfaceVariant
    }
    Column(
        modifier
            .clip(RoundedCornerShape(8.dp))
            .background(bg)
            .clickable(onClickLabel = "查看$label") { onClick() }
            .padding(vertical = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            label,
            fontSize = 11.sp,
            fontWeight = if (selected || isToday) FontWeight.Medium else FontWeight.Normal,
            color = fg,
        )
        Spacer(Modifier.height(2.dp))
        Text(
            date,
            fontSize = 9.5.sp,
            color = if (selected) fg.copy(alpha = 0.85f)
            else fg.copy(alpha = 0.65f),
        )
    }
}

/**
 * 一张课卡。高度由内容决定（不按节数算），这是日视图相对周视图的核心优势 ——
 * 课名长了能换行，教师多了能写完，不会像格子那样被截断。
 *
 * ## 布局演进（致辉 2026-11-02 定）
 *
 * 参考了一张课表 App 的截图：时间放在**右侧竖排**，上课时间大、下课时间小。
 * 这样左侧留给课名，一眼扫下去全是课名，不用被时间挤得只剩半行。
 *
 * **和参考图的区别**：参考图强调**下课时间**（橙色大字在下面），
 * 这里强调**上课时间**（大字在上面用主色）。理由：
 * 学生看课表最关心的是「几点开始」，迟到是从开头丢的；
 * 下课时间只是「大概什么时候能走」，次要。
 *
 * ## 三种状态
 *
 * | 状态 | 视觉 |
 * |---|---|
 * | 正在上 | 卡片底色带主色、左侧色条加粗发光、右上角「正在上」标签、时间用主色 |
 * | 还没开始 | 正常显示 |
 * | 已下课 | 整体降透明度，一眼能跳过 |
 */
@Composable
private fun DayCourseCard(
    session: CourseSession,
    /** 当天第几分钟。传 -1 表示「不是今天」，不做状态判断 */
    nowMinutes: Int,
    onClick: () -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    val color = courseColor(session.name)
    val phase = if (nowMinutes >= 0) phaseOf(session, nowMinutes) else SessionPhase.UPCOMING
    val ongoing = phase == SessionPhase.ONGOING
    val past = phase == SessionPhase.PAST

    /*
     * 已下课的整卡降透明度（0.55），但「正在上」不受影响。
     *
     * 不降到更低：用户可能想回看上午上过什么，降太狠就看不见了。
     */
    val dim = if (past) 0.55f else 1f

    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            /*
             * 磨砂底画满（理由同周视图：乘透明度会让背后清晰的壁纸透上来），
             * 用户调的那个浓度只作用在下一层的课程/中性色上。
             */
            .frostedBackdrop(
                bitmap = com.gzucm.pocket.ui.components.LocalBlurredWallpaper.current,
            )
            .background(
                if (ongoing) scheme.primary.copy(alpha = 0.10f)
                else scheme.surfaceVariant.copy(
                    alpha = (com.gzucm.pocket.ui.components.LocalCellOpacity.current * 0.85f * dim)
                        .coerceIn(0.05f, 1f),
                ),
            )
            .then(
                if (ongoing) {
                    // 正在上：主色细边框，和角落标签、加粗色条一起做「当前」的信号
                    Modifier.border(
                        width = 1.dp,
                        color = scheme.primary.copy(alpha = 0.45f),
                        shape = RoundedCornerShape(12.dp),
                    )
                } else {
                    /*
                     * 普通卡片：一圈很细的灰白描边（2026-11-03 致辉要的）。
                     *
                     * 为什么需要：卡片底色是磨砂 + 半透明课程色，叠在壁纸上时
                     * 边缘完全靠明度差撑着 —— 壁纸那块颜色接近时，卡片就「化」进背景了。
                     * 描边给一条硬边界，等于替眼睛把轮廓画出来，可读性立刻上来。
                     *
                     * 只描 0.8dp：再粗就成卡片框了，会跟「正在上」的主色边框抢注意力。
                     * 深浅色分别用白/黑，保证任何主题下都是「灰白」而不是隐形。
                     */
                    val line = if (scheme.surface.luminance() < 0.5f) {
                        androidx.compose.ui.graphics.Color.White.copy(alpha = 0.22f)
                    } else {
                        androidx.compose.ui.graphics.Color.Black.copy(alpha = 0.10f)
                    }
                    Modifier.border(
                        width = 0.8.dp,
                        color = line,
                        shape = RoundedCornerShape(12.dp),
                    )
                },
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.Top,
    ) {
        // 左侧色条：和课程块同一个颜色。正在上时加粗，作为第二重信号
        Box(
            Modifier
                .width(if (ongoing) 4.dp else 3.dp)
                .height(if (ongoing) 44.dp else 38.dp)
                .clip(RoundedCornerShape(2.dp))
                .background(color.copy(alpha = dim)),
        )
        Spacer(Modifier.width(12.dp))

        // ── 中间：课名 / 教师 / 教室 ──────────────────────
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    session.name,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Medium,
                    lineHeight = 20.sp,
                    color = scheme.onSurface.copy(alpha = dim),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                if (ongoing) {
                    Spacer(Modifier.width(6.dp))
                    Box(
                        Modifier
                            .clip(RoundedCornerShape(5.dp))
                            .background(scheme.primary.copy(alpha = 0.2f))
                            .padding(horizontal = 6.dp, vertical = 1.dp),
                    ) {
                        Text(
                            "正在上",
                            fontSize = 9.5.sp,
                            fontWeight = FontWeight.Medium,
                            color = scheme.primary,
                        )
                    }
                }
            }

            // 节次单独一行小字，时间和节次分开显示（时间在右侧）
            Text(
                if (session.startUnit == session.endUnit) "第 ${session.startUnit} 节"
                else "第 ${session.startUnit}-${session.endUnit} 节",
                fontSize = 10.5.sp,
                color = scheme.onSurfaceVariant.copy(alpha = 0.75f * dim),
                modifier = Modifier.padding(top = 2.dp),
            )

            if (session.teachers.isNotBlank()) {
                Spacer(Modifier.height(3.dp))
                Text(
                    session.teachers,
                    fontSize = 12.sp,
                    color = scheme.onSurfaceVariant.copy(alpha = dim),
                )
            }
            if (session.room.isNotBlank()) {
                Spacer(Modifier.height(1.dp))
                Text(
                    session.room,
                    fontSize = 12.sp,
                    color = scheme.onSurfaceVariant.copy(alpha = 0.8f * dim),
                )
            }
        }

        Spacer(Modifier.width(10.dp))

        // ── 右侧：上下课时间竖排 ──────────────────────────
        TimeColumn(session = session, ongoing = ongoing, dim = dim, color = color)
    }
}

/**
 * 右侧的竖排时间：上课时间在上（大字号、强调色），下课时间在下（小字号、次要色）。
 *
 * **强调上课时间和参考图不一样**，原因写在 [DayCourseCard] 的注释里：
 * 学生最关心「几点开始」，迟到是从开头丢的。
 */
@Composable
private fun TimeColumn(
    session: CourseSession,
    ongoing: Boolean,
    dim: Float,
    color: androidx.compose.ui.graphics.Color,
) {
    val scheme = MaterialTheme.colorScheme
    val start = session.startTime.ifBlank { SlotTimes[session.startUnit].orEmpty() }
    val end = session.endTime.ifBlank { SlotEndTimes[session.endUnit].orEmpty() }
    if (start.isBlank() && end.isBlank()) return

    Column(horizontalAlignment = Alignment.End) {
        /*
         * 上课时间：强调色 + 等宽数字，竖排时数位才对得齐。
         *
         * 字号只比下课时间大一档（12 → 14sp）。最早写的是 20sp，
         * 视觉上比课名还抢眼，整张卡被右侧两个数字撑得头重脚轻（致辉 2026-11-02 反馈）。
         * 层次感靠「颜色 + 字重」拿，不靠字号硬拉。
         */
        Text(
            start.ifBlank { "—" },
            fontSize = 14.sp,
            fontWeight = FontWeight.SemiBold,
            lineHeight = 17.sp,
            color = when {
                ongoing -> scheme.primary
                // 平时用课程色：同一天的课颜色不同，扫一眼能分块
                else -> color.copy(alpha = dim)
            },
            style = androidx.compose.ui.text.TextStyle(
                fontFeatureSettings = "tnum",
            ),
        )
        Spacer(Modifier.height(1.dp))
        // 下课时间：小一号、次要色，只作参考
        Text(
            end.ifBlank { "—" },
            fontSize = 12.sp,
            lineHeight = 14.sp,
            color = scheme.onSurfaceVariant.copy(alpha = 0.7f * dim),
            style = androidx.compose.ui.text.TextStyle(
                fontFeatureSettings = "tnum",
            ),
        )
    }
}

/**
 * 那天没课时的占位。
 *
 * 本周的今天用轻松一点的文案（致辉选的「今天没课，想去哪玩去哪玩」），
 * 别的日子就正常说「没课」—— 用玩笑语气说一个还没到的日期会有点怪。
 */
@Composable
private fun EmptyDay(isToday: Boolean, date: java.time.LocalDate) {
    Box(
        Modifier
            .fillMaxSize()
            .padding(bottom = LocalFloatingBarInset.current),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                if (isToday) "今天没课，想去哪玩去哪玩" else "这天没课",
                fontSize = 13.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                "${date.monthValue} 月 ${date.dayOfMonth} 日",
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
            )
        }
    }
}