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
import com.gzucm.pocket.ui.components.G3CornerShape
import com.gzucm.pocket.ui.components.micaPanel
import com.gzucm.pocket.ui.components.micaShadow
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableFloatStateOf
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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.gzucm.pocket.core.AgendaOccurrence
import com.gzucm.pocket.core.CourseSession
import com.gzucm.pocket.core.ScheduleEntry
import com.gzucm.pocket.core.Semester
import com.gzucm.pocket.core.SessionPhase
import com.gzucm.pocket.core.SlotEndTimes
import com.gzucm.pocket.core.SlotTimes
import com.gzucm.pocket.core.mergeForDay
import com.gzucm.pocket.core.phaseOf
import com.gzucm.pocket.ui.components.LocalFloatingBarInset
import com.gzucm.pocket.ui.components.frostedBackdrop
import com.gzucm.pocket.ui.components.BackdropInk
import com.gzucm.pocket.ui.components.LocalBackdropInk
import com.gzucm.pocket.ui.components.TodayFab
import com.gzucm.pocket.ui.components.backdropInkColor
import com.gzucm.pocket.ui.components.cardTextColor
import com.gzucm.pocket.ui.components.rememberCardInk
import com.gzucm.pocket.ui.theme.courseColor
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import java.time.LocalTime
import java.time.LocalDate

/**
 * 「正在上」那层主色底的强度系数。
 *
 * 最终 alpha = 格子不透明度 × 0.85 × 本系数。
 * 取 0.30 是为了在滑块 40% 时刚好等于改版前写死的 0.10 —— 观感不变，
 * 但它现在会跟着滑块走（致辉 2026-11-19 要求「整体受滑块控制」）。
 */
private const val OngoingTintRatio = 0.30f


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
    /** 用户自建日程（已按学期展开）。独立通道，不合并进 [sessions] */
    agendaOccurrences: List<AgendaOccurrence> = emptyList(),
    onAgendaClick: (AgendaOccurrence) -> Unit = {},
    /** 空列表时提供的「添加日程」入口：(当天日期, 起始分钟或 null) */
    onAddAgenda: (LocalDate, Int?) -> Unit = { _, _ -> },
    /**
     * 滑动跨周时通知外部。
     *
     * 日视图自己算得出周次，但顶部「第 N 周」和周条归父组件管，
     * 不回调的话滑到下一周、顶部还写着旧周次。
     */
    onWeekChange: (Int) -> Unit = {},
    /**
     * 当前翻到的是哪一天。
     *
     * 日程的「新建」默认日期要用：用户在日视图里翻到 11/20 打开日程面板，
     * 新建应该落在 11/20，而不是跳回今天。日期由这里算（页索引 → 日期），
     * 父组件不用重复推一遍 Pager 的逻辑。
     */
    onDayChange: (LocalDate) -> Unit = {},
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
     * 「今天在第几周」自己算，不用外面传的 currentWeek。
     *
     * 两者平时相等，但跨 0 点、或者 App 搁置几天后 currentWeek 还是上次的值时，
     * 用外部值会把**错误的一天**标成今天（连带「正在上」也标错）。
     * semester.weekOf 是纯函数，自己算一遍最省心。
     */
    val todayWeek = semester.weekOf(today)

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

    /*
     * 当前页 → 日期，报给父组件。
     *
     * 同样要 rememberUpdatedState：key 里没有 onDayChange / firstMonday 的话，
     * 闭包捕获的是首次组合时的旧 lambda。firstMonday 变化说明换了学期，
     * 那本来就该重新起一次。
     */
    val latestOnDayChange by rememberUpdatedState(onDayChange)
    LaunchedEffect(pagerState, firstMonday) {
        snapshotFlow { pagerState.currentPage }
            .distinctUntilChanged()
            .collect { page -> latestOnDayChange(firstMonday.plusDays(page.toLong())) }
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

    Box(modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
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
            /*
             * 卡片墨色：按**卡片实际底色**判定（磨砂壁纸 + 30% 雾面），不是壁纸原亮度。
             * 纯黑壁纸上会取白字、纯白壁纸上会取黑字 —— 见 rememberCardInk 的说明。
             * 整页算一次就够，所有卡片共用（同屏底色一致）。
             */
            val cardInk = rememberCardInk(com.gzucm.pocket.ui.components.LocalBlurredWallpaper.current)

            HorizontalPager(
                state = pagerState,
                modifier = Modifier
                    .fillMaxSize()
                    .testTag("day-pager"),
                /*
                 * 前后各留 1 页 = 同时存在 3 页（当前 + 两侧）。
                 *
                 * 原来是 2（5 页同时组合 + 布局），2026-11-20 致辉在自己小米 14 上
                 * 都能明显感到卡顿 —— 每页是一个 LazyColumn 加十几张磨砂卡片，
                 * 5 页一起测就是 5 份活。降到 1 之后首帧和滑动都轻一截；
                 * 代价是极快甩动时相邻周可能白一帧，但预渲染仍然在，正常滑动看不到。
                 */
                beyondViewportPageCount = 1,
            ) { page ->
                val w = page / 7 + 1
                val weekday = page % 7 + 1
                val date = firstMonday.plusDays(page.toLong())
                /*
                 * 课程和日程按真实开始分钟混排。
                 *
                 * 用联合类型而不是把日程伪装成 CourseSession：后者会让
                 * 同名同节次撞 key 崩溃，也会把日程混进上课提醒排程。
                 */
                val entries = mergeForDay(
                    sessions = byDay[w to weekday].orEmpty(),
                    occurrences = agendaOccurrences,
                    week = w,
                    weekday = weekday,
                )
                val isTodayPage = w == todayWeek && todayWeekday == weekday

                if (entries.isEmpty()) {
                    EmptyDay(
                        isToday = isTodayPage,
                        date = date,
                        onAddAgenda = { onAddAgenda(date, null) },
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
                        items(entries, key = { it.stableKey }) { entry ->
                            when (entry) {
                                is ScheduleEntry.Course -> {
                                    val s = entry.session
                                    DayCourseCard(
                                        session = s,
                                        /*
                                         * 只有「正在看的这天就是今天」时才算现在时刻。
                                         * 别的时候传 -1：phaseOf 会判成 UPCOMING，
                                         * 翻到上周三时那天的课不会被标成「正在上」。
                                         */
                                        nowMinutes = if (isTodayPage) nowMinutes else -1,
                                        onClick = { onCourseClick(s) },
                                        ink = cardInk,
                                    )
                                }
                                is ScheduleEntry.Custom -> AgendaDayCard(
                                    occurrence = entry.occurrence,
                                    onClick = { onAgendaClick(entry.occurrence) },
                                )
                            }
                        }
                    }
                }
            }
        }
        /*
         * 回到今天。
         *
         * todayPage 用 currentWeek（今天所在的那一周）+ todayWeekday 算，
         * 不用外面传的 week —— 用户翻到别的周时，week 已经不是今天了，
         * 那样算出来的「今天」会跟着跑。
         */
        val todayPage = pageOf(todayWeek, todayWeekday)
        TodayFab(
            // todayWeek 可能落在学期外（假期），那时没有「今天」可回
            visible = shownPage != todayPage && todayWeek in 1..semester.totalWeeks,
            onClick = { scope.launch { pagerState.animateScrollToPage(todayPage) } },
            modifier = Modifier.align(Alignment.BottomEnd),
        )
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
    val ink = LocalBackdropInk.current
    val bg = when {
        selected -> scheme.primary
        else -> androidx.compose.ui.graphics.Color.Transparent
    }
    val fg = when {
        selected -> scheme.onPrimary
        isToday -> scheme.primary
        // 压壁纸时按壁纸亮度取深/浅墨色（文档 §5.2），不垫底板
        else -> backdropInkColor(ink, 1) ?: scheme.onSurfaceVariant
    }
    /*
     * 今天那一格的描边颜色。
     *
     * 两套配色，因为底色不同：
     *   · 今天没被选中（透明底）→ 淡主色
     *   · 今天正好选中（主色实心）→ 淡「onPrimary」，也就是绿块上一圈淡白线
     *
     * alpha 压到 0.5 左右 —— 致辉 2026-11-19 的原话是「类似第 x 周那种淡淡的就行」。
     * 用实心 primary 会变成一圈亮绿粗框，跟顶部周条的选中框不是一个语言。
     */
    val todayLine = if (selected) {
        scheme.onPrimary.copy(alpha = 0.55f)
    } else {
        scheme.primary.copy(alpha = 0.50f)
    }

    Column(
        modifier
            .clip(G3CornerShape(8.dp))
            .background(bg)
            /*
             * 描边必须画在格子**边界内**，且**不能补 padding**。
             *
             * 第一版为了让「环」和「填充块」分开，写成了 border + padding(3dp)，
             * 结果今天那格比相邻格高出 6dp —— 一行七格，一眼就看出来没对齐
             * （致辉 2026-11-19 报的）。border 本身不占布局空间，撑大的是那个 padding。
             *
             * 现在不加 padding，描边直接压在格子边缘，七格严格等高。
             */
            .then(
                if (isToday) Modifier.border(1.dp, todayLine, G3CornerShape(8.dp)) else Modifier,
            )
            .clickable(onClickLabel = "查看$label") { onClick() }
            .padding(vertical = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            label,
            fontSize = 11.sp,
            fontWeight = if (selected || isToday || ink != BackdropInk.Unknown) {
                FontWeight.Medium
            } else {
                FontWeight.Normal
            },
            color = fg,
        )
        Spacer(Modifier.height(2.dp))
        Text(
            date,
            fontSize = 9.5.sp,
            fontWeight = if (ink != BackdropInk.Unknown) FontWeight.Medium else FontWeight.Normal,
            color = if (selected) fg.copy(alpha = 0.85f)
            else if (ink != BackdropInk.Unknown) fg.copy(alpha = 0.8f)
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
    /** 卡片实际底色对应的墨色（见 rememberCardInk）。Unknown 时回退主题色 */
    ink: BackdropInk = BackdropInk.Unknown,
) {
    val scheme = MaterialTheme.colorScheme
    val color = courseColor(session.name)
    val phase = if (nowMinutes >= 0) phaseOf(session, nowMinutes) else SessionPhase.UPCOMING
    val ongoing = phase == SessionPhase.ONGOING
    val past = phase == SessionPhase.PAST

    /*
     * 三档状态系数（致辉 2026-11-19 定）：正在上最实、未上居中、已上过最透。
     *
     * 改之前只有两档（past 0.55 / 其余 1.0），「正在上」和「未上」完全一样，
     * 扫一眼分不出哪节已经上过了。
     */
    val stateDim = when {
        ongoing -> 1.00f
        past -> 0.45f
        else -> 0.75f
    }

    /*
     * 文字的淡出**不跟着底色等比走**：底色 0.45 已经够透，文字再乘 0.45
     * 会让上午上过的课整张消失，回看时找不到。所以收得慢一档。
     */
    val textDim = when {
        ongoing -> 1.00f
        past -> 0.55f
        else -> 0.85f
    }

    /*
     * 三种背景状态、三种画法（致辉 2026-11-19 定）。
     *
     * | 场景 | 磨砂 | 阴影 | 衬底 | 描边 |
     * |---|---|---|---|---|
     * | 默认壁纸 | 无 | **有** | **有**（实心云母面板） | 无 |
     * | 自定义 + 模糊开 | 有 | 有 | 无（磨砂本身不透明） | 无 |
     * | 自定义 + 模糊关 | 无 | **无** | 无 | **有**（0.8dp 灰白） |
     *
     * 三处判断的理由：
     *
     * · **默认壁纸要衬底，不能只靠阴影**。没壁纸时卡片是页面主体，应该长成实心云母
     *   面板（浅色纯白、深色 #171C23），压在白底/黑底上都立得住；只给阴影而不衬底，
     *   它就是一块半透明色片，跟背景糊在一起。
     * · **模糊关掉时不挂阴影、改描边**。底半透明 + elevation = 阴影从卡底下透出来，
     *   在卡片内侧显出一圈暗边（穿帮）；去掉阴影后卡片边缘只剩明度差撑着，
     *   壁纸颜色接近时会"化"进背景，所以补一圈 0.8dp 灰白边把轮廓勾出来。
     * · **模糊开着时什么都不用补**。磨砂层画满不透明，阴影不会透，轮廓也清楚。
     */
    val hasCustomBg = com.gzucm.pocket.ui.components.LocalHasCustomBackdrop.current
    val frostOn = hasCustomBg &&
        com.gzucm.pocket.ui.components.LocalCellFrostEnabled.current &&
        com.gzucm.pocket.ui.components.LocalBlurredWallpaper.current != null &&
        com.gzucm.pocket.ui.components.LocalWallpaperBounds.current != null

    val needsScrim = !hasCustomBg
    val needsShadow = !hasCustomBg || frostOn
    // 深色主题用白边、浅色主题用黑边，保证任何配色下都是"灰白"而不是隐形
    val edgeLine = if (scheme.surface.luminance() < 0.5f) {
        androidx.compose.ui.graphics.Color.White.copy(alpha = 0.22f)
    } else {
        androidx.compose.ui.graphics.Color.Black.copy(alpha = 0.10f)
    }

    Row(
        Modifier
            .fillMaxWidth()
            // 云母化（2026-11-19 致辉定）：轻一档阴影，一天十几张叠起来不会发灰
            .then(if (needsShadow) Modifier.micaShadow(12.dp) else Modifier)
            .clip(G3CornerShape(12.dp))
            /*
             * 磨砂底画满（理由同周视图：乘透明度会让背后清晰的壁纸透上来），
             * 用户调的那个浓度只作用在下一层的课程/中性色上。
             */
            .frostedBackdrop(
                bitmap = com.gzucm.pocket.ui.components.LocalBlurredWallpaper.current,
            )
            /*
             * 默认壁纸的不透明衬底。必须画在磨砂**之后**、课程色**之前**：
             * 磨砂先铺（有壁纸时才有），衬底再盖（只在没壁纸时），最后叠课程色。
             * 两者互斥，所以不会互相盖死。
             */
            .then(
                if (needsScrim) {
                    Modifier.background(com.gzucm.pocket.ui.components.micaPanelColor())
                } else {
                    Modifier
                },
            )
            .background(
                /*
                 * 三档底色全部受滑块控制（2026-11-19 改）。
                 *
                 * 「正在上」原来是一个写死的 0.10 主色底，滑块对它完全无效 ——
                 * 致辉要求「整体不透明度都受滑块控制」。现在主色底也乘滑块值，
                 * 系数 0.30 是为了在滑块 40% 时刚好落在原来那个 0.10（观感不变）。
                 */
                if (ongoing) {
                    scheme.primary.copy(
                        alpha = (com.gzucm.pocket.ui.components.LocalCellOpacity.current *
                            0.85f * OngoingTintRatio).coerceIn(0f, 1f),
                    )
                } else {
                    scheme.surfaceVariant.copy(
                        alpha = (com.gzucm.pocket.ui.components.LocalCellOpacity.current *
                            0.85f * stateDim).coerceIn(0f, 1f),
                    )
                },
            )
            /*
             * 描边只在需要的时候出现（2026-11-19 第三版）：
             *
             * · 「正在上」的 1dp 主色边 —— 状态信号，跟角标、加粗色条同一套语言，优先级最高
             * · 自定义壁纸 + 模糊关 —— 没有阴影了，靠这圈灰白边勾轮廓
             * · 其余情况不描边，边界交给阴影或磨砂
             */
            .then(
                when {
                    ongoing -> Modifier.border(
                        width = 1.dp,
                        color = scheme.primary.copy(alpha = 0.45f),
                        shape = G3CornerShape(12.dp),
                    )
                    hasCustomBg && !frostOn -> Modifier.border(
                        width = 0.8.dp,
                        color = edgeLine,
                        shape = G3CornerShape(12.dp),
                    )
                    else -> Modifier
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
                .clip(G3CornerShape(2.dp))
                .background(color.copy(alpha = stateDim)),
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
                    // 按卡片实际底色取墨色：纯黑壁纸用白字、纯白壁纸用黑字
                    color = cardTextColor(ink, 0, scheme.onSurface, textDim),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                if (ongoing) {
                    Spacer(Modifier.width(6.dp))
                    Box(
                        Modifier
                            .clip(G3CornerShape(5.dp))
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
                color = cardTextColor(ink, 2, scheme.onSurfaceVariant, 0.75f * textDim),
                modifier = Modifier.padding(top = 2.dp),
            )

            if (session.teachers.isNotBlank()) {
                Spacer(Modifier.height(3.dp))
                Text(
                    session.teachers,
                    fontSize = 12.sp,
                    color = cardTextColor(ink, 1, scheme.onSurfaceVariant, textDim),
                )
            }
            if (session.room.isNotBlank()) {
                Spacer(Modifier.height(1.dp))
                Text(
                    session.room,
                    fontSize = 12.sp,
                    color = cardTextColor(ink, 2, scheme.onSurfaceVariant, 0.8f * textDim),
                )
            }
        }

        Spacer(Modifier.width(10.dp))

        // ── 右侧：上下课时间竖排 ──────────────────────────
        TimeColumn(
            session = session,
            ongoing = ongoing,
            dim = textDim,
            color = color,
            ink = ink,
        )
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
    /** 卡片实际底色对应的墨色，用来压/提课程色的明度 */
    ink: BackdropInk = BackdropInk.Unknown,
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
                /*
                 * 平时用课程色：同一天的课颜色不同，扫一眼能分块。
                 * 但纯白壁纸 + 低不透明度时，浅色课程色会糊在浅底上 ——
                 * 亮底（BackdropInk.Dark）时压暗三成，暗底保持原色。
                 */
                else -> courseTimeColor(color, ink, dim)
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
            color = cardTextColor(ink, 2, scheme.onSurfaceVariant, 0.7f * dim),
            style = androidx.compose.ui.text.TextStyle(
                fontFeatureSettings = "tnum",
            ),
        )
    }
}

/**
 * 上课时间那串数字的颜色。
 *
 * 课程色是识别码（同一天的课颜色不同，扫一眼能分块），所以不换成墨色，
 * 只按底色调整明度：亮底压暗 35%，暗底保持 —— 浅色课程色（黄、浅绿）
 * 压在纯白壁纸的磨砂上时，原色对比度只有 1.5 左右，压暗后能到 4 以上。
 */
private fun courseTimeColor(
    base: androidx.compose.ui.graphics.Color,
    ink: BackdropInk,
    alphaMul: Float,
): androidx.compose.ui.graphics.Color {
    val c = if (ink == BackdropInk.Dark) {
        androidx.compose.ui.graphics.lerp(base, androidx.compose.ui.graphics.Color.Black, 0.35f)
    } else {
        base
    }
    return if (alphaMul >= 1f) c
    else c.copy(alpha = (c.alpha * alphaMul).coerceIn(0f, 1f))
}

/**
 * 那天没课时的占位。
 *
 * 本周的今天用轻松一点的文案（致辉选的「今天没课，想去哪玩去哪玩」），
 * 别的日子就正常说「没课」—— 用玩笑语气说一个还没到的日期会有点怪。
 */
@Composable
private fun EmptyDay(
    isToday: Boolean,
    date: java.time.LocalDate,
    onAddAgenda: () -> Unit,
) {
    Box(
        Modifier
            .fillMaxSize()
            .padding(bottom = LocalFloatingBarInset.current),
        contentAlignment = Alignment.Center,
    ) {
        val ink = LocalBackdropInk.current
        val onImage = ink != BackdropInk.Unknown
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                if (isToday) "今天没课，想去哪玩去哪玩" else "这天没课",
                fontSize = 13.sp,
                fontWeight = if (onImage) FontWeight.Medium else FontWeight.Normal,
                color = backdropInkColor(ink, 1)
                    ?: MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                "${date.monthValue} 月 ${date.dayOfMonth} 日",
                fontSize = 11.sp,
                fontWeight = if (onImage) FontWeight.Medium else FontWeight.Normal,
                color = backdropInkColor(ink, 2)
                    ?: MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
            )
            Spacer(Modifier.height(14.dp))
            Text(
                "+ 加个日程",
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier
                    .clip(G3CornerShape(10.dp))
                    .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.12f))
                    .clickable(onClickLabel = "添加日程", onClick = onAddAgenda)
                    .padding(horizontal = 14.dp, vertical = 7.dp),
            )
        }
    }
}