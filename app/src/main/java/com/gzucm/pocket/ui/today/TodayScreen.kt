package com.gzucm.pocket.ui.today

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import com.gzucm.pocket.ui.components.G3CornerShape
import com.gzucm.pocket.ui.components.isDarkSurface
import com.gzucm.pocket.ui.components.micaPanel
import com.gzucm.pocket.ui.components.micaPanelColor
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.key
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.gzucm.pocket.core.AgendaOccurrence
import com.gzucm.pocket.core.CourseSession
import com.gzucm.pocket.core.PowerDaily
import com.gzucm.pocket.core.PowerInfo
import com.gzucm.pocket.core.Semester
import com.gzucm.pocket.core.TodayLogic
import com.gzucm.pocket.core.TodayBlock
import com.gzucm.pocket.core.TodaySections
import com.gzucm.pocket.core.Weather
import com.gzucm.pocket.ui.components.Fmt
import com.gzucm.pocket.ui.components.LocalFloatingBarInset
import com.gzucm.pocket.ui.icons.AppIcons
import com.gzucm.pocket.ui.schedule.ClassPlanBarState
import com.gzucm.pocket.ui.schedule.classPlanPatchText
import com.gzucm.pocket.ui.schedule.classPlanSyncTime
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
    /** 电费上次成功同步的时间；0 表示从没成功过。首页据此提示数据新旧 */
    powerCheckedAt: Long = 0L,
    onCourseClick: (CourseSession) -> Unit,
    onOpenPower: () -> Unit,
    onOpenSettings: () -> Unit,
    onRefresh: () -> Unit,
    syncing: Boolean,
    progressDone: Int = 0,
    progressTotal: Int = 0,
    /** 有新版本时，设置按钮右上角显示小红点 */
    hasUpdate: Boolean = false,
    notice: String? = null,
    /**
     * notice 里「去登录」的动作。
     *
     * 跟右上角设置按钮分开：那个只是打开设置，这个要额外把「重新登录」滚出来高亮。
     * 默认回落到 [onOpenSettings]，老调用方不用改。
     */
    onOpenLogin: () -> Unit = onOpenSettings,
    /**
     * 用户自建日程（已按学期展开）。
     *
     * 放在参数表最后：现有截图测试是按位置传参的（`TodayScreen(semester, 8, …)`），
     * 插在中间会整体错位。新字段一律追加在末尾也是项目一贯做法。
     * 只在本页追加显示，不参与「下次上课」「本周概览」的原有计算。
     */
    agendaOccurrences: List<AgendaOccurrence> = emptyList(),
    onAgendaClick: (AgendaOccurrence) -> Unit = {},
    /**
     * 截止任务 / 待办（已按紧急度排好，含已完成的沉底项）。
     * 单独一路传，不受「显示明天」的日期过滤影响 —— 任务在截止前每天都显示。
     */
    taskCards: List<AgendaOccurrence> = emptyList(),
    /** 勾选截止任务 / 待办（已完成）。任务卡在首页和日程页都有勾选圈 */
    onTaskCheck: (AgendaOccurrence) -> Unit = {},
    /*
     * 以下都是 2026-11-20 新增（天气 + 接下来 + 显示管理），
     * 一律追加在末尾：截图测试里有按位置传参的调用，插在中间会整体错位。
     */
    /** 今日页显示哪些块（电费 / 天气 / 接下来 / 本周概览）+ 提前天数 */
    sections: TodaySections = TodaySections(),
    weather: Weather.Info? = null,
    weatherRefreshing: Boolean = false,
    weatherError: String? = null,
    /** 长按任意卡片、或从设置进来：打开「今日页显示」面板 */
    onOpenSections: () -> Unit = {},
    /**
     * 「班级日程」那一行的状态；**null = 不显示这一行**。
     *
     * 它**不是**一个新的 TodayBlock —— 致辉明确要求绑在「待办与日程」里面，
     * 所以这里只往下传，最终画在 TasksSection 那张卡的顶部。
     *
     * 默认 null 是刻意的：接上 core/ClassSync 之前，各调用点保持原样，
     * 今日页一个像素都不变（截图测试也不会动）。集成时把 ClassSyncState
     * 映射成 [ClassPlanBarState] 传进来即可。
     */
    classPlan: ClassPlanBarState? = null,
    /** 学委删掉、本地还留着的条数；>0 时行下面叠一行橙色提示 */
    classPlanOrphaned: Int = 0,
    /** 点「去认领」/「查看」 */
    onClassPlanAction: () -> Unit = {},
    /** 点橙色提示里的「处理」 */
    onClassPlanResolveGone: () -> Unit = {},
) {
    // today 也挂在同一个 tick 上：停在首页跨过 0 点后要切到新的一天，
    // 下面 remember(…, today) 那几处才会重算
    var today by remember { mutableStateOf(LocalDate.now()) }
    // 每 30 秒走一次，用来判断「今天的课是不是已经上完了」
    var nowMin by remember { mutableIntStateOf(LocalTime.now().let { it.hour * 60 + it.minute }) }
    LaunchedEffect(Unit) {
        while (true) {
            kotlinx.coroutines.delay(30_000)
            nowMin = LocalTime.now().let { it.hour * 60 + it.minute }
            today = LocalDate.now()
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

    /*
     * 未来 N 天的日程 / 考试（不含今天）。
     *
     * 数据源就是上面那一条合并列表，不额外问 ViewModel 要 ——
     * 它已经包含日程和考试两种来源，筛选和排序都是纯函数（TodayLogic.upcoming），
     * 单测里能直接验「第 N 天算不算在内」这种边界。
     */
    val upcoming = remember(agendaOccurrences, today, sections.upcomingDays) {
        TodayLogic.upcoming(agendaOccurrences, today, sections.upcomingDays)
    }

    /*
     * 今日页的日程：只跟着「显示今天还是明天」切换，不改 [todayDone] 的判定
     * （那是按最后一节课算的，把日程混进去会让首页提前翻到明天）。
     */
    val shownAgenda = remember(agendaOccurrences, today, showingTomorrow) {
        val d = if (showingTomorrow) today.plusDays(1) else today
        agendaOccurrences
            .filter { it.date == d }
            // 入参是「日程 + 考试」两段拼接的，各自有序、合起来不一定有序：
            // 同一天里必须再按开考/开始时间排一次，否则上午的考试会落到晚上的日程下面
            .sortedWith(compareBy({ it.startMinute }, { it.title }))
    }

    /*
     * 「待办与日程」整块画不画。
     *
     * 班级日程那一行也算内容：它自己就是入口，只因为它一条都没有就把入口藏起来，
     * 用户永远发现不了这个功能。没有任何待办但这一行在的话，块里就只剩这一行。
     */
    val hasTaskSection = taskCards.isNotEmpty() || shownAgenda.isNotEmpty() || classPlan != null

    /*
     * 按可用宽度分两套排版。
     *
     * 【宽屏】整页**不滚动**，顶部标题栏跨全宽，下面两列各自独立滚动。
     *   左列 = 电费卡 + 本周概览，右列 = 今日课程。
     *   （课程放右边是致辉定的，权重 1 : 1.35 保持不动。）
     *
     * 【窄屏】沿用原来的单列 + 整页 verticalScroll，视觉零变化。
     *
     * 为什么宽屏不能共用外层 verticalScroll：
     * 外层有 verticalScroll 时，里面的 LazyColumn 会被判定为
     * 「infinity maximum height constraints」直接抛异常崩溃。
     * 两套容器分开写是唯一干净的解法。
     */
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val wide = maxWidth >= 840.dp
        val hPad = if (wide) 32.dp else 18.dp

        if (wide) {
            Column(
                Modifier
                    .fillMaxSize()
                    .statusBarsPadding()
                    .padding(horizontal = hPad),
            ) {
                Spacer(Modifier.height(10.dp))

                TodayTitleBar(
                    today = today,
                    currentWeek = currentWeek,
                    count = todaySessions.size,
                    syncing = syncing,
                    hasUpdate = hasUpdate,
                    onRefresh = onRefresh,
                    onOpenSettings = onOpenSettings,
                    // 宽屏：同步/设置已搬到左侧导航栏底部
                    hideActions = true,
                )

                Spacer(Modifier.height(16.dp))

                if (!notice.isNullOrBlank()) {
                    NoticeBar(notice = notice, onOpenLogin = onOpenLogin)
                    Spacer(Modifier.height(12.dp))
                }

                Row(
                    Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    horizontalArrangement = Arrangement.spacedBy(28.dp),
                ) {
                    /*
                     * 左列不滚动，靠 weight 撑满剩余高度。
                     *
                     * 内容只有两张卡，正常情况下装得下；真装不下时 Column
                     * 会溢出而不是崩 —— 比塞一层滚动让内外互相抢手势好。
                     */
                    Column(
                        Modifier
                            .weight(1f)
                            .fillMaxHeight()
                            /*
                             * 左列自己可滚（2026-11-20 加天气卡之后）。
                             *
                             * 宽屏整页不滚动是有意为之，但左列内容从两张卡变成三张，
                             * 平板横屏高度不足时会溢出。这里加的是**本列**的滚动，
                             * 外层没有嵌套滚动容器，不会出现「内外抢手势」那种事。
                             */
                            .verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(18.dp),
                    ) {
                        /*
                         * 左栏按用户排的顺序渲染（2026-11-20 加的排序）。
                         *
                         * 「接下来」不在这里 —— 它跟课程一起在右栏，属于「今天之后」那一列；
                         * 平板的两栏布局本身是固定的，排序只在栏内生效。
                         */
                        sections.order
                            .filter {
                                it != TodayBlock.UPCOMING &&
                                    it != TodayBlock.COURSE &&
                                    it != TodayBlock.TASKS
                            }
                            .forEach { block ->
                                TodayBlockCard(
                                    block = block,
                                    weather = weather,
                                    weatherRefreshing = weatherRefreshing,
                                    weatherError = weatherError,
                                    power = power,
                                    powerDaily = powerDaily,
                                    powerCheckedAt = powerCheckedAt,
                                    upcoming = upcoming,
                                    upcomingDays = sections.upcomingDays,
                                    weekSessions = weekSessions,
                                    currentWeek = currentWeek,
                                    onOpenPower = onOpenPower,
                                    onAgendaClick = onAgendaClick,
                                    onOpenSections = onOpenSections,
                                )
                            }
                    }
                    LazyColumn(
                        Modifier
                            .weight(1.35f)
                            .fillMaxHeight(),
                        contentPadding = androidx.compose.foundation.layout.PaddingValues(
                            bottom = LocalFloatingBarInset.current,
                        ),
                    ) {
                        item {
                            CourseSection(
                                title = if (showingTomorrow) "明日课程" else "今日课程",
                                sessions = if (showingTomorrow) tomorrowSessions else todaySessions,
                                syncing = syncing,
                                allEmpty = sessions.isEmpty(),
                                progressDone = progressDone,
                                progressTotal = progressTotal,
                                onCourseClick = onCourseClick,
                            )
                        }
                        /*
                         * 待办与日程在宽屏仍然固定跟在课程后面：
                         * 两栏布局本身是固定的，目前只在栏内按顺序排
                         * （2026-11-22 致辉指出这样排序等于没用，待定改法）。
                         */
                        if (hasTaskSection) {
                            item {
                                Spacer(Modifier.height(18.dp))
                                TasksSection(
                                    taskCards = taskCards,
                                    agenda = shownAgenda,
                                    onAgendaClick = onAgendaClick,
                                    onTaskCheck = onTaskCheck,
                                    classPlan = classPlan,
                                    classPlanOrphaned = classPlanOrphaned,
                                    onClassPlanAction = onClassPlanAction,
                                    onClassPlanResolveGone = onClassPlanResolveGone,
                                )
                            }
                        }
                        if (sections.upcoming) {
                            item {
                                Spacer(Modifier.height(18.dp))
                                UpcomingCard(
                                    items = upcoming,
                                    days = sections.upcomingDays,
                                    onAgendaClick = onAgendaClick,
                                    onLongPress = onOpenSections,
                                )
                            }
                        }
                    }
                }
            }
        } else {
            Column(
                Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .statusBarsPadding()
                    .padding(horizontal = hPad),
            ) {
                Spacer(Modifier.height(10.dp))

                TodayTitleBar(
                    today = today,
                    currentWeek = currentWeek,
                    count = todaySessions.size,
                    syncing = syncing,
                    hasUpdate = hasUpdate,
                    onRefresh = onRefresh,
                    onOpenSettings = onOpenSettings,
                )

                Spacer(Modifier.height(16.dp))

                if (!notice.isNullOrBlank()) {
                    NoticeBar(notice = notice, onOpenLogin = onOpenLogin)
                    Spacer(Modifier.height(12.dp))
                }

                /*
                 * 渲染顺序 = sections.order（2026-11-20 起的拖动排序）。
                 *
                 * 课程卡也在这个列表里 —— 能拖、不能减。第一版把它钉死在中间，
                 * 结果是「减掉两块之后，剩下那块会自己跳到课程上面」，
                 * 看着像 bug。现在顺序是唯一的真相。
                 *
                 * 课程卡就地展开而不是塞进 TodayBlockCard：它要的参数
                 * （课表、日程、进度、勾选…）比另外四块加起来还多，
                 * 传进去只会让那个函数变成一个参数搬运工。
                 */
                /*
                 * 「待办与日程」今天没内容就整块跳过（连那 18dp 间距也不留）——
                 * 空着的一块比不画更让人困惑。班级日程那一行在参数里算作有内容。
                 */
                sections.order.forEach { block ->
                    if (block == TodayBlock.TASKS && !hasTaskSection) return@forEach
                    when (block) {
                        TodayBlock.COURSE -> CourseSection(
                            title = if (showingTomorrow) "明日课程" else "今日课程",
                            sessions = if (showingTomorrow) tomorrowSessions else todaySessions,
                            syncing = syncing,
                            allEmpty = sessions.isEmpty(),
                            progressDone = progressDone,
                            progressTotal = progressTotal,
                            onCourseClick = onCourseClick,
                        )
                        /*
                         * 待办与日程用 today 的 agenda，不跟「明日课程」走：
                         * 明天的课表可以预览，但今天的作业不会因为点了「明日」就不用交了。
                         */
                        TodayBlock.TASKS -> TasksSection(
                            taskCards = taskCards,
                            agenda = shownAgenda,
                            onAgendaClick = onAgendaClick,
                            onTaskCheck = onTaskCheck,
                            classPlan = classPlan,
                            classPlanOrphaned = classPlanOrphaned,
                            onClassPlanAction = onClassPlanAction,
                            onClassPlanResolveGone = onClassPlanResolveGone,
                        )
                        else -> TodayBlockCard(
                            block = block,
                            weather = weather,
                            weatherRefreshing = weatherRefreshing,
                            weatherError = weatherError,
                            power = power,
                            powerDaily = powerDaily,
                            powerCheckedAt = powerCheckedAt,
                            upcoming = upcoming,
                            upcomingDays = sections.upcomingDays,
                            weekSessions = weekSessions,
                            currentWeek = currentWeek,
                            onOpenPower = onOpenPower,
                            onAgendaClick = onAgendaClick,
                            onOpenSections = onOpenSections,
                        )
                    }
                    Spacer(Modifier.height(18.dp))
                }

                // 悬浮底栏占位，避免最后一张卡被压在底栏下面
                Spacer(Modifier.height(LocalFloatingBarInset.current))
            }
        }
    }
}

/**
 * 顶部标题栏：日期 + 周次徽标 + 同步 / 设置。
 *
 * 抽出来是因为双列和单列都要用同一套标题栏 ——
 * 复制两份的话，以后加一个按钮必然改一处漏一处。
 */
@Composable
private fun TodayTitleBar(
    today: LocalDate,
    currentWeek: Int,
    count: Int,
    syncing: Boolean,
    hasUpdate: Boolean,
    onRefresh: () -> Unit,
    onOpenSettings: () -> Unit,
    /**
     * 隐藏右侧的同步 / 设置按钮。
     *
     * 宽屏下这两个按钮搬到了左侧导航栏底部 —— 留着会同时出现两处入口，
     * 用户不知道该点哪个，而且标题栏右侧会被占掉一块。
     */
    hideActions: Boolean = false,
) {
    val scheme = MaterialTheme.colorScheme
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
                        .clip(G3CornerShape(6.dp))
                        .background(scheme.primary.copy(alpha = 0.14f))
                        .padding(horizontal = 7.dp, vertical = 2.dp),
                ) {
                    Text(
                        "第 $currentWeek 周",
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Medium,
                        color = scheme.primary,
                    )
                }
            }
            Spacer(Modifier.height(3.dp))
            Text(
                "${Cn[today.dayOfWeek.value - 1]} · 今天 $count 节课",
                style = MaterialTheme.typography.bodySmall,
                color = scheme.onSurfaceVariant,
            )
        }
        if (hideActions) return@Row
        if (syncing) {
            CircularProgressIndicator(
                modifier = Modifier.size(17.dp),
                strokeWidth = 2.dp,
                color = scheme.primary,
            )
            Spacer(Modifier.width(10.dp))
        } else {
            Box(
                Modifier
                    .size(34.dp)
                    .clip(CircleShape)
                    .background(scheme.surface)
                    .clickable(onClick = onRefresh),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    AppIcons.Refresh,
                    contentDescription = "同步",
                    modifier = Modifier.size(17.dp),
                    tint = scheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.width(8.dp))
        }
        Box(
            Modifier
                .size(34.dp)
                .clip(CircleShape)
                .background(scheme.surface)
                .clickable(onClick = onOpenSettings),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                AppIcons.Settings,
                contentDescription = "设置",
                modifier = Modifier.size(17.dp),
                tint = scheme.onSurfaceVariant,
            )
            // 有新版本：右上角一颗小红点，白描边保证在深色主题也看得见
            if (hasUpdate) {
                Box(
                    Modifier
                        .align(Alignment.TopEnd)
                        .padding(top = 6.dp, end = 6.dp)
                        .size(7.dp)
                        .clip(CircleShape)
                        .background(scheme.error),
                )
            }
        }
    }
}

/** 登录过期之类的提示条，带「去登录」动作。 */
@Composable
private fun NoticeBar(notice: String, onOpenLogin: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    Row(
        Modifier
            .fillMaxWidth()
            .clip(G3CornerShape(12.dp))
            .background(scheme.tertiary.copy(alpha = 0.13f))
            .padding(horizontal = 13.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            notice,
            style = MaterialTheme.typography.bodySmall,
            color = scheme.tertiary,
            modifier = Modifier.weight(1f),
        )
        Text(
            "去登录",
            style = MaterialTheme.typography.bodySmall,
            fontWeight = FontWeight.Medium,
            color = scheme.primary,
            modifier = Modifier.clickable(onClick = onOpenLogin).padding(start = 10.dp),
        )
    }
}

/**
 * 一节课的列表区：小标题 + 时间轴，或空态。
 *
 * [allEmpty] 用来区分「真的今天没课」和「课表还没抓完」——
 * 同步中且一条数据都没有时说「今天没有课」是在骗人。
 *
 * 2026-11-22 起这里**只画课表**：待办与日程已经独立成 [TasksSection]，
 * 进排序列表了（见 [com.gzucm.pocket.core.TodayBlock.TASKS]）。
 */
@Composable
private fun CourseSection(
    title: String,
    sessions: List<CourseSession>,
    syncing: Boolean,
    allEmpty: Boolean,
    progressDone: Int,
    progressTotal: Int,
    onCourseClick: (CourseSession) -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    Text(
        title,
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.Medium,
        color = scheme.onSurfaceVariant,
    )
    Spacer(Modifier.height(10.dp))

    /*
     * 课程时间轴原样保留 —— 它的「正在上 / 已下课」判定和竖线连线
     * 都绑在 CourseSession 上，重做成混排会牵动一堆视觉细节。
     */
    if (sessions.isEmpty()) {
        Box(
            Modifier
                .fillMaxWidth()
                .micaPanel(14.dp)
                .padding(vertical = if (syncing && allEmpty) 26.dp else 30.dp),
            contentAlignment = Alignment.Center,
        ) {
            if (syncing && allEmpty) {
                com.gzucm.pocket.ui.components.SyncPlaceholder(
                    done = progressDone,
                    total = progressTotal,
                )
            } else {
                Text(
                    // 只说没课 —— 日程挪到单独的块去了，别在这里替它下结论
                    "今天没有课",
                    style = MaterialTheme.typography.bodyMedium,
                    color = scheme.onSurfaceVariant,
                )
            }
        }
    } else {
        Timeline(sessions = sessions, onCourseClick = onCourseClick)
    }
}

/**
 * 「待办与日程」：今天的截止任务 / 待办 + 今天的日程。
 *
 * ## 为什么从课程块里拆出来（2026-11-22，致辉提的）
 *
 * 它原来画在 [CourseSection] 末尾，跟着课程块一起移动，也不在排序列表里。
 * 致辉问「排序少了个待办与日程，现在算在哪」——答案就是「算在课程里」，
 * 但这说不通：它有自己的标题、自己的内容，用户完全可能想把它拖到最上面
 * （今天要交的作业比课表更要紧），也可能想暂时收起。
 *
 * 拆出来之后它是一个独立块，能拖、能减，位置由 [com.gzucm.pocket.core.TodaySections.order] 决定。
 *
 * ## 空的时候整块不画
 *
 * 调用方会先判断有没有内容；空着还占一块只会让人以为加载失败。
 */
@Composable
private fun TasksSection(
    /** 截止任务卡（含已完成沉底项），单独渲染，不受日期过滤 */
    taskCards: List<AgendaOccurrence>,
    /** 今天的日程 / 考试 */
    agenda: List<AgendaOccurrence>,
    onAgendaClick: (AgendaOccurrence) -> Unit,
    onTaskCheck: (AgendaOccurrence) -> Unit,
    /**
     * 班级日程那一行；null = 不画。
     *
     * 带默认值：老调用点（含测试）不用改，行为跟加这一行之前完全一样。
     */
    classPlan: ClassPlanBarState? = null,
    classPlanOrphaned: Int = 0,
    onClassPlanAction: () -> Unit = {},
    onClassPlanResolveGone: () -> Unit = {},
) {
    val scheme = MaterialTheme.colorScheme
    /*
     * 标题按内容写：有截止任务 / 待办就叫「待办与日程」（任务排在前面），
     * 否则有考试叫「日程与考试」，都没有才是「我的日程」。
     */
    val hasPendingTask = taskCards.any { it.doneAt == null }
    val hasItems = taskCards.isNotEmpty() || agenda.isNotEmpty()

    /*
     * 第一行永远是班级日程。
     *
     * 致辉 2026-11-22 定：不做成新的卡片、不做成按钮，就绑在「待办与日程」这一块里 ——
     * 它和下面的待办是同一件事（都是「今天要交什么」），分开放反而要用户自己拼。
     */
    if (classPlan != null) {
        ClassPlanBar(
            state = classPlan,
            orphaned = classPlanOrphaned,
            onAction = onClassPlanAction,
            onResolveGone = onClassPlanResolveGone,
        )
        Spacer(Modifier.height(10.dp))
    }

    // 底下一条待办都没有时，标题也就不写了 —— 否则只剩一个光秃秃的分类名
    if (hasItems) {
        Text(
            when {
                hasPendingTask -> "待办与日程"
                agenda.any { it.colorIndex == com.gzucm.pocket.core.EXAM_COLOR_INDEX } -> "日程与考试"
                else -> "我的日程"
            },
            fontSize = 12.sp,
            color = scheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(6.dp))
    }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        /*
         * 顺序：未完成任务 → 日程 → 已完成任务（变淡沉底）。
         *
         * 完成后**不立刻消失**（致辉 2026-11-17 拍板）：卡片降到半透明留在最下方，
         * 第二天才不见（过滤在 ViewModel 的 todayTaskOccurrences 里）。
         * 想改回来直接再点一下勾 —— 比一闪而过的撤销条好用。
         * key 仍然保留：已完成项还在列表里、位置会变，没 key 会串状态。
         */
        taskCards.filter { it.doneAt == null }.forEach { o ->
            key("task|" + o.key) {
                com.gzucm.pocket.ui.schedule.AgendaDayCard(
                    occurrence = o,
                    onClick = { onAgendaClick(o) },
                    onTaskCheck = { onTaskCheck(o) },
                )
            }
        }
        agenda.forEach { o ->
            key("agenda|" + o.key) {
                com.gzucm.pocket.ui.schedule.AgendaDayCard(
                    occurrence = o,
                    onClick = { onAgendaClick(o) },
                )
            }
        }
        taskCards.filter { it.doneAt != null }.forEach { o ->
            key("done|" + o.key) {
                com.gzucm.pocket.ui.schedule.AgendaDayCard(
                    occurrence = o,
                    onClick = { onAgendaClick(o) },
                    onTaskCheck = { onTaskCheck(o) },
                )
            }
        }
    }
}

/** 橙色提示：浅色下压深一档，11sp 的小字才不会发飘 */
private val WarnOrangeLight = Color(0xFFB2620C)
private val WarnOrangeDark = Color(0xFFF2AC66)

/**
 * 「班级日程」那一行：绑在「待办与日程」顶部的一条信息条。
 *
 * ## 为什么不是按钮
 *
 * 它要能表达四种状态（没有 / 未认领 / 已认领 / 有更新），按钮只能表达一种。
 * 所以是「图标 + 两行字 + 右侧动作」的信息条，只有右侧动作可点（整行也可点，
 * 便于命中）；底色用 `surfaceVariant` 的 40%，跟下面的白色日程卡区分开，
 * 底部一条 1dp 细线把它和待办列表切开 —— 它们是同一块里的两段，不是两张卡。
 *
 * ## 文案
 *
 * 严格照 `docs/CLASSPLAN_CLOUD.md` 第 7 节：
 * 「还没有班级日程 / 学委上传后会自动出现在这里」
 * 「本班有 N 份班级日程 / 张三 12 条 · 王五 5 条」
 * 「{key}的清单 · {count} 条 / 今天 08:12 同步」
 * 「{key}更新了：新增 2 条、改了 1 条 / 今天 08:12」
 * 不要在这里换说法，别处（弹窗）也用的是同一套措辞。
 */
@Composable
private fun ClassPlanBar(
    state: ClassPlanBarState,
    orphaned: Int,
    onAction: () -> Unit,
    onResolveGone: () -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    val warn = if (isDarkSurface()) WarnOrangeDark else WarnOrangeLight

    val (title, sub) = when (state) {
        ClassPlanBarState.Empty ->
            Pair("还没有班级日程", "学委上传后会自动出现在这里")

        // 只列前 3 份：一行装不下第 4 个名字，兜底交给省略号
        is ClassPlanBarState.Unclaimed -> Pair(
            "本班有 ${state.plans.size} 份班级日程",
            state.plans.take(3).joinToString(" · ") { "${it.key} ${it.count} 条" },
        )

        is ClassPlanBarState.Claimed -> Pair(
            "${state.key.ifBlank { "学委" }}的清单 · ${state.count} 条",
            "${classPlanSyncTime(state.syncedAt)} 同步",
        )

        // 「更新了：新增 2 条、改了 1 条」——顿号是首页那一行的口径，弹窗里用逗号
        is ClassPlanBarState.Updated -> Pair(
            "${state.key.ifBlank { "学委" }}更新了：" +
                classPlanPatchText(state.added, state.updated, "、"),
            classPlanSyncTime(state.syncedAt),
        )
    }

    val actionLabel = when (state) {
        is ClassPlanBarState.Unclaimed -> "去认领"
        is ClassPlanBarState.Updated -> "查看"
        else -> null
    }

    Column(
        Modifier
            .fillMaxWidth()
            .clip(G3CornerShape(12.dp))
            .background(scheme.surfaceVariant.copy(alpha = 0.4f)),
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .heightIn(min = 44.dp)
                // 有动作时整行可点：右侧那两个字的命中区太小，手指容易点空
                .then(if (actionLabel != null) Modifier.clickable(onClick = onAction) else Modifier)
                .padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                AppIcons.EventNote,
                contentDescription = null,
                modifier = Modifier.size(16.dp),
                tint = scheme.primary.copy(alpha = 0.9f),
            )
            Spacer(Modifier.width(9.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    title,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium,
                    color = scheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    sub,
                    fontSize = 11.sp,
                    color = scheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (actionLabel != null) {
                Spacer(Modifier.width(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (state is ClassPlanBarState.Updated) {
                        Box(
                            Modifier
                                .size(6.dp)
                                .clip(CircleShape)
                                .background(scheme.error),
                        )
                        Spacer(Modifier.width(5.dp))
                    }
                    Text(
                        actionLabel,
                        fontSize = 12.5.sp,
                        fontWeight = FontWeight.Medium,
                        color = scheme.primary,
                    )
                }
            }
        }

        /*
         * 学委删掉的条目：本地还留着（见契约第 5 节 mergeClassTasks 的口径）。
         * 橙色、一行小字、带「处理」入口 —— 红点太吵，这些条目还没丢，只是需要用户拍板。
         */
        if (orphaned > 0) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(start = 12.dp, end = 12.dp)
                    .padding(bottom = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "学委删了 $orphaned 条，你本地还留着",
                    fontSize = 11.sp,
                    color = warn,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                Text(
                    " · 处理",
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Medium,
                    color = warn,
                    modifier = Modifier
                        .clip(G3CornerShape(6.dp))
                        .clickable(onClick = onResolveGone)
                        .padding(horizontal = 2.dp, vertical = 2.dp),
                )
            }
        }

        // 和下面待办列表的分界线。画在条内、贴着底边，圆角会把它两端收进去
        Box(
            Modifier
                .fillMaxWidth()
                .height(1.dp)
                .background(scheme.outlineVariant.copy(alpha = 0.6f)),
        )
    }
}

/** 本周概览：每天几节课的分布，填充界面下半部分 */
@Composable
private fun WeekOverview(
    sessions: List<CourseSession>,
    currentWeek: Int,
    onLongPress: () -> Unit = {},
) {
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
            // 阴影用系统原生 elevation，不再按高度分档（见 MicaPanel.kt）
            .micaPanel(16.dp)
            // 这张卡本身没有点击动作，长按只是「打开今日页显示管理」的快捷入口
            .cardLongPress(onLongPress)
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
                            .clip(G3CornerShape(topStart = 4.dp, topEnd = 4.dp, bottomEnd = 0.dp, bottomStart = 0.dp))
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
            /*
             * 两行布局，不是一行三个元素。
             *
             * 原来写成「下次上课 | 课名(weight=1f) | 时间地点」一行排，
             * 课名又没设 maxLines —— 空间不够时就把「中医儿科学」折成
             * 「中医儿科」+「学」两行，看着像写错了。
             * 平板双列把右列压窄到 330dp 后这个问题特别明显。
             *
             * 改成课名一行、时间地点一行，两边都不挤，而且手机端也更好读。
             */
            Column(
                Modifier
                    .fillMaxWidth()
                    .clip(G3CornerShape(10.dp))
                    .background(scheme.primary.copy(alpha = 0.07f))
                    .padding(horizontal = 12.dp, vertical = 9.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
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
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                }
                Spacer(Modifier.height(3.dp))
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
                        .clip(G3CornerShape(13.dp))
                        .background(
                            if (isOngoing) color.copy(alpha = 0.14f)
                            else MaterialTheme.colorScheme.surface,
                        )
                        .border(
                            width = if (isOngoing) 1.dp else 0.5.dp,
                            color = if (isOngoing) color.copy(alpha = 0.5f)
                            else MaterialTheme.colorScheme.outlineVariant,
                            shape = G3CornerShape(13.dp),
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
                                        .clip(G3CornerShape(5.dp))
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

/**
 * 宿舍电量卡。
 *
 * **没有数据就整块不渲染**，不画一个「--」的空卡。
 *
 * 原因有两个：
 *   · 研究生学段根本拿不到电费（一卡通是独立登录，暂不支持），
 *     留个空卡会让人以为「点一下就有了」，其实是永远没有
 *   · 本科首次同步电费失败时同样不该占位 —— 电费页有入口，
 *     这里空着反而更干净
 */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun PowerCard(
    power: PowerInfo?,
    daily: List<PowerDaily>,
    checkedAt: Long,
    onClick: () -> Unit,
    /** 长按卡片 = 打开「今日页显示」管理（致辉 2026-11-20 定的快捷入口） */
    onLongPress: () -> Unit = {},
) {
    if (power == null) return
    val scheme = MaterialTheme.colorScheme
    val accent = if (power == null) scheme.onSurfaceVariant else scheme.primary
    Box(
        Modifier
            .fillMaxWidth()
            /*
             * 去掉了原来那圈 0.6dp 的 outlineVariant 描边（2026-11-19 云母化）。
             *
             * 渐变不再手写 —— [micaPanel] 的默认底就是这道 3% 斜向微渐变，
             * 这张卡是它的样板（2026-11-19 第二轮：所有面板对齐到这里）。
             */
            .micaPanel(18.dp)
            .combinedClickable(onClick = onClick, onLongClick = onLongPress)
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
                // 电费只能进电费页同步（会话在 WebView 里），所以这里要说清数据有多旧，
                // 否则用户会以为 12 小时前的余额是实时的
                val staleText = run {
                    if (power == null) return@run null
                    val age = System.currentTimeMillis() - checkedAt
                    if (checkedAt <= 0 || age < 12 * 60 * 60_000L) null
                    else {
                        val h = age / (60 * 60_000L)
                        if (h < 1) "刚刚" else "${h}小时前 · 点此更新"
                    }
                }
                Text(
                    staleText ?: power?.roomLabel ?: "尚未同步",
                    fontSize = 10.5.sp,
                    color = if (staleText != null) scheme.primary.copy(alpha = 0.85f)
                    else scheme.onSurfaceVariant.copy(alpha = 0.7f),
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
                    .clip(G3CornerShape(2.dp))
                    .background(color.copy(alpha = 0.25f + 0.5f * ratio)),
            )
        }
    }
}

/**
 * 一块内容的统一入口：把 [TodayBlock] 映射到具体那张卡。
 *
 * 为什么要这一层：今日页现在由 `sections.order` 驱动渲染，
 * 窄屏的「前两块 / 后两块」和宽屏左栏都用同一套映射。
 * 如果每处各写一遍 `when`，以后加第五块就得改好几处，
 * 漏一处就是「排了序之后某块凭空消失」。
 */
@Composable
private fun TodayBlockCard(
    block: TodayBlock,
    weather: Weather.Info?,
    weatherRefreshing: Boolean,
    weatherError: String?,
    power: PowerInfo?,
    powerDaily: List<PowerDaily>,
    powerCheckedAt: Long,
    upcoming: List<AgendaOccurrence>,
    upcomingDays: Int,
    weekSessions: List<CourseSession>,
    currentWeek: Int,
    onOpenPower: () -> Unit,
    onAgendaClick: (AgendaOccurrence) -> Unit,
    onOpenSections: () -> Unit,
) {
    when (block) {
        TodayBlock.WEATHER -> {
            /*
             * 有数据就画；没数据但功能可用时也画（卡片自己显示「正在取 / 取不到」）——
             * 首次请求就失败时卡片凭空消失，看着像开关没生效。
             */
            if (Weather.enabled || weather != null) {
                WeatherCard(
                    info = weather,
                    refreshing = weatherRefreshing,
                    error = weatherError,
                    onLongPress = onOpenSections,
                )
            }
        }

        TodayBlock.POWER -> PowerCard(
            power = power,
            daily = powerDaily,
            checkedAt = powerCheckedAt,
            onClick = onOpenPower,
            onLongPress = onOpenSections,
        )

        TodayBlock.UPCOMING -> UpcomingCard(
            items = upcoming,
            days = upcomingDays,
            onAgendaClick = onAgendaClick,
            onLongPress = onOpenSections,
        )

        TodayBlock.WEEK_OVERVIEW -> WeekOverview(
            sessions = weekSessions,
            currentWeek = currentWeek,
            onLongPress = onOpenSections,
        )

        // 课程卡由调用方就地展开（参数太多），这里不该被走到
        // 课程和待办都是整段自己渲染的，不走这个通用卡片
    TodayBlock.COURSE, TodayBlock.TASKS -> Unit
    }
}
