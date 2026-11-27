package com.gzucm.pocket.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import com.gzucm.pocket.core.Campus
import com.gzucm.pocket.ui.adaptive.AppNavigationRail
import com.gzucm.pocket.ui.adaptive.LocalContentLeadingInset
import com.gzucm.pocket.ui.adaptive.NavRailItem
import com.gzucm.pocket.ui.adaptive.rememberLayout
import com.gzucm.pocket.ui.adaptive.usesNavigationRail
import com.gzucm.pocket.ui.icons.AppIcons
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import com.gzucm.pocket.ui.components.G3CornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import android.widget.Toast
import androidx.compose.ui.platform.LocalContext
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.zIndex
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsPropertyKey
import androidx.compose.ui.semantics.SemanticsPropertyReceiver
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.gzucm.pocket.core.CourseSession
import com.gzucm.pocket.ui.components.FloatingBarDimAlpha
import com.gzucm.pocket.ui.components.FloatingBarIdleMs
import com.gzucm.pocket.ui.components.FloatingBarBottomGap
import com.gzucm.pocket.ui.components.FloatingBarMinInset
import com.gzucm.pocket.ui.components.FloatingBarOverscroll
import com.gzucm.pocket.ui.components.FloatingBarTintAlpha
import com.gzucm.pocket.ui.components.FloatingBarTotalInset
import com.gzucm.pocket.ui.components.LocalFloatingBarInset
import com.gzucm.pocket.ui.components.floatingBarGlassAlpha
import com.gzucm.pocket.ui.components.glassStyle
import com.gzucm.pocket.ui.components.rememberHazeState
import com.gzucm.pocket.ui.grades.GradesScreen
import com.gzucm.pocket.ui.login.LoginScreen
import com.gzucm.pocket.ui.power.PowerScreen
import com.gzucm.pocket.ui.schedule.ClassPlanDeletedDialog
import com.gzucm.pocket.ui.schedule.ClassPlanUpdateDialog
import com.gzucm.pocket.ui.schedule.ScheduleScreen
import com.gzucm.pocket.ui.schedule.SessionSheet
import com.gzucm.pocket.ui.theme.ThemeState
import com.gzucm.pocket.ui.today.TodayScreen
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.hazeChild
import dev.chrisbanes.haze.hazeSource
import kotlinx.coroutines.delay

internal enum class Tab(val label: String, val icon: ImageVector) {
    Today("今日", AppIcons.WbSunny),
    Schedule("课表", AppIcons.CalendarMonth),
    /*
     * 工作台取代了原来的「成绩」Tab（2026-11-21 致辉定）。
     *
     * 成绩没消失，只是从「一级 Tab」变成工作台里的第一张卡片：
     * 它是低频操作（一学期看几次），却占着一个每天都划过的位置。
     * 腾出来的位置放工作台 —— 查图书馆、办事大厅、财务这些才是高频。
     */
    Workbench("工作台", AppIcons.Apps),
    Power("电费", AppIcons.Bolt),
}

/**
 * 当前学段下可用的 Tab。
 *
 * **两个学段都是四个 Tab，电费不隐藏了**（致辉 2026-11-01 定）。
 *
 * 之前研究生学段把电费 Tab 去掉了，理由是「一卡通是独立登录，
 * 研究生登进来拿不到会话」。但那等于**这个功能对研究生直接消失** ——
 * 研究生一样要交电费，一样想看用了多少度。
 *
 * 现在的做法：Tab 保留，电费页自己判断有没有一卡通会话，
 * 没有就显示「登录一卡通」按钮，点开在 WebView 里单独登一次。
 * 这条路径和研究生系统无关，不需要登本科教务。
 *
 * 参数留着不删：以后要按学段隐藏别的 Tab 时还得用它，
 * 而且调用方已经传了，删了要改五处。
 */
@Suppress("UNUSED_PARAMETER")
internal fun tabsOf(campus: Campus): List<Tab> = Tab.entries

/**
 * 页面骨架：内容铺满全屏，悬浮底栏叠在它上面。
 *
 * 底栏高度是**实测**的（`onSizeChanged`），因为它除了自身内容还含手势导航条的
 * 内边距 —— 不同机器差 20~48dp，写死常量必然漏掉一截，最后一行内容就被压住。
 * 量出来的值加一段额外留白后通过 [LocalFloatingBarInset] 下发给各页做底部 padding。
 */
/**
 * 页面骨架，按可用宽度分成两套：
 *
 * 【手机 / 窄窗口】内容铺满，悬浮底栏叠在上面（原有实现，不动）
 * 【平板 / 宽窗口】左侧固定导航栏 + 右侧内容区
 *
 * 判断依据是 `BoxWithConstraints` 拿到的**实际可用宽度**，
 * 不是设备型号也不是屏幕物理尺寸 —— 平板开分屏后可能只剩 500dp，
 * 那时应当退回底部 Tab 布局，否则内容区会被挤成一条。
 */
@Composable
internal fun MainScaffold(
    tab: Tab,
    onSelect: (Tab) -> Unit,
    /** 宽屏侧栏底部「同步」按钮 */
    onSyncClick: () -> Unit = {},
    /** 宽屏侧栏底部「设置」按钮 */
    onSettingsClick: () -> Unit = {},
    /** 有新版本时，侧栏设置按钮右上角显示小红点 */
    hasUpdate: Boolean = false,
    syncing: Boolean = false,
    /** 当前学段。研究生没有电费 Tab，所以要传下去 */
    campus: Campus = Campus.UNDERGRAD,
    content: @Composable () -> Unit,
) {
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val layout = rememberLayout(maxWidth)

        if (layout.usesNavigationRail) {
            WideScaffold(
                tab = tab,
                onSelect = onSelect,
                onSyncClick = onSyncClick,
                onSettingsClick = onSettingsClick,
                hasUpdate = hasUpdate,
                syncing = syncing,
                campus = campus,
                content = content,
            )
        } else {
            PhoneScaffold(tab = tab, onSelect = onSelect, campus = campus, content = content)
        }
    }
}

/** 宽窗口：左侧导航栏 + 内容区。内容区不再需要底部留白，把 inset 归零。 */
@Composable
private fun WideScaffold(
    tab: Tab,
    onSelect: (Tab) -> Unit,
    onSyncClick: () -> Unit,
    onSettingsClick: () -> Unit,
    hasUpdate: Boolean,
    syncing: Boolean,
    campus: Campus,
    content: @Composable () -> Unit,
) {
    // 研究生学段没有电费 Tab（一卡通是另一套登录，拿不到会话）
    val tabs = tabsOf(campus)

    /*
     * 侧栏抽成 lambda，两套布局共用。
     *
     * 课表页要「内容铺满全屏 + 侧栏浮在上面」，其余页仍是「左右分栏」——
     * 两种结构的侧栏一模一样，复制一份迟早会漂移（少个角标、少个 autoHide）。
     */
    val rail: @Composable (Modifier) -> Unit = { railModifier ->
        AppNavigationRail(
            items = tabs.map { NavRailItem(it.label, it.icon) },
            selected = tabs.indexOf(tab),
            onSelect = { onSelect(tabs[it]) },
            /*
             * 课表页静置自动渐隐，其余页面永远全亮 —— 和手机端底栏同一条策略。
             *
             * 今日 / 成绩 / 电费三页的侧栏是主要入口，淡下去会让人以为导航没了；
             * 课表页是纯阅读场景，导航常年压在左边只是干扰。
             */
            autoHide = tab == Tab.Schedule,
            bottomInset = 0.dp,
            bottomActions = {
                /*
                 * 同步 / 设置从首页标题栏搬到这里。
                 *
                 * 首页的标题栏在宽屏下就只剩日期和红点位置，干净很多；
                 * 侧栏底部是拇指最容易够到的地方（平板握持在两侧）。
                 */
                com.gzucm.pocket.ui.adaptive.RailActionButton(
                    icon = AppIcons.Refresh,
                    label = "同步",
                    spinning = syncing,
                    onClick = onSyncClick,
                )
                com.gzucm.pocket.ui.adaptive.RailActionButton(
                    icon = AppIcons.Settings,
                    label = "设置",
                    badge = hasUpdate,
                    onClick = onSettingsClick,
                )
            },
            modifier = railModifier,
        )
    }

    /*
     * 底部 inset 置 0：平板的导航条在左侧，内容区可以一直铺到屏幕底边。
     * 之前给底部 Tab 留的那一截在这里是纯浪费 —— 课表会因此少显示半节课。
     */
    CompositionLocalProvider(
        LocalFloatingBarInset provides 0.dp,
        /*
         * 侧栏占了左边 76dp，页面自己要知道。
         *
         * 课表页拿它给自己**内容列**加左内边距（壁纸不受影响，仍然铺满全屏），
         * 这样侧栏渐隐之后露出来的是一整片壁纸，而不是一条纯色窗口底
         * （致辉 2026-11-21 报的「只隐藏了字和图标，侧栏底色没隐藏」）。
         */
        LocalContentLeadingInset provides com.gzucm.pocket.ui.adaptive.NavigationRailWidth,
    ) {
        if (tab == Tab.Schedule) {
            /*
             * 课表页：内容铺满全屏，侧栏浮在上面。
             *
             * ## 为什么这一页不能用左右分栏
             *
             * 分栏时壁纸只铺在右半边，侧栏底下是**纯色窗口底**。
             * 侧栏渐隐到 15% 之后，那 76dp 仍然是一块明显不同的竖条 ——
             * 用户看到的是「只有字和图标消失了，底色还在」。
             *
             * 改成「全屏铺内容 + 侧栏浮层」之后，壁纸自然铺到侧栏底下，
             * 淡出就是真的淡出。代价是侧栏会盖住最左边一列，所以课表页
             * 自己读了 [LocalContentLeadingInset] 把内容列右移。
             *
             * 其余三页没有壁纸、侧栏也不隐藏，保持左右分栏（结构更简单，
             * 也不会有内容被浮层压住的风险）。
             */
            Box(Modifier.fillMaxSize()) {
                content()
                // zIndex 必须给：Box 里后声明的在上层，而 content 是后声明的
                rail(Modifier.align(Alignment.CenterStart).zIndex(1f))
            }
        } else {
            Row(Modifier.fillMaxSize()) {
                rail(Modifier)
                Box(Modifier.weight(1f).fillMaxHeight()) {
                    content()
                }
            }
        }
    }
}

/** 手机 / 窄窗口：原实现，内容铺满 + 悬浮底栏。 */
@Composable
private fun PhoneScaffold(
    tab: Tab,
    onSelect: (Tab) -> Unit,
    campus: Campus,
    content: @Composable () -> Unit,
) {
    val tabs = tabsOf(campus)
    val hazeState = rememberHazeState()
    val density = androidx.compose.ui.platform.LocalDensity.current
    var barInset by remember { mutableStateOf(FloatingBarTotalInset) }

    CompositionLocalProvider(LocalFloatingBarInset provides barInset) {
        // 内容与底栏**叠在一起**，不是上下切开。
        // 这样滚动内容能一直铺到屏幕最底部，从悬浮底栏后面透出来。
        Box(Modifier.fillMaxSize()) {
            Box(
                Modifier
                    .fillMaxSize()
                    .hazeSource(hazeState),
            ) {
                content()
            }
            FloatingBar(
                tab = tab,
                onSelect = onSelect,
                campus = campus,
                /*
                 * 自动渐隐**只在课表页生效**（致辉 2026-11-18 补的硬约束）。
                 * 今日 / 成绩 / 电费三页的底栏永远全亮 —— 那几页底栏是主要入口，
                 * 淡下去会让人以为按钮没了。
                 */
                autoHide = tab == Tab.Schedule,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .onSizeChanged { size ->
                        val measured = with(density) { size.height.toDp() } + FloatingBarOverscroll
                        // 取大不取小：真机手势条/三键条高度差异大，
                        // 漏一截就会把最后一行课压在底栏下面。
                        val inset = maxOf(measured, FloatingBarMinInset)
                        if (inset > barInset) barInset = inset
                    },
                hazeState = hazeState,
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RootScreen(vm: AppViewModel = viewModel()) {
    val state = vm.state
    val ctx0 = androidx.compose.ui.platform.LocalContext.current
    // 通知权限：开着开关却发不出通知等于白开，所以开之前先查
    fun notifyGranted(): Boolean =
        android.os.Build.VERSION.SDK_INT < 33 ||
            androidx.core.content.ContextCompat.checkSelfPermission(
                ctx0, android.Manifest.permission.POST_NOTIFICATIONS,
            ) == android.content.pm.PackageManager.PERMISSION_GRANTED
    var notifyOk by remember { mutableStateOf(notifyGranted()) }
    // 精确闹钟授权（Android 12+）。没授权时提醒会退回非精确，可能被推迟很久
    var exactOk by remember { mutableStateOf(vm.canExactAlarm()) }
    val askNotify = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.RequestPermission()
    ) { granted -> notifyOk = granted }
    val prefs = remember { com.gzucm.pocket.core.SessionStore(ctx0.applicationContext) }
    // 默认启动页由用户在设置里选
    var tab by remember {
        mutableStateOf(
            /*
             * 恢复上次停留的 Tab。
             *
             * 研究生学段只有 3 个 Tab，而 prefs 里可能存着 3（电费）——
             * 那是本科时留下的。直接 getOrElse 会拿到 Power，
             * 但 Power 不在可用列表里，界面会显示一个空白页。
             * 所以先按学段过滤，取不到就回今日。
             */
            tabsOf(prefs.campus).getOrElse(prefs.startTab) { Tab.Today },
        )
    }
    /*
     * 从后台切回前台时补查更新。
     *
     * 原来只有「冷启动」查一次。手机上的 App 常常挂后台几天不退出，
     * 那几天就永远不会知道自己有新版本。
     * 这里监听 ON_RESUME，内部有「今天查过就跳过」的幂等判断，重复调用无副作用。
     */
    androidx.lifecycle.compose.LifecycleEventEffect(
        androidx.lifecycle.Lifecycle.Event.ON_RESUME
    ) {
        vm.checkUpdateIfStale()
        // 天气也是「回到前台就看一眼」：内部按 1 小时节流，多调无副作用
        vm.maybeRefreshWeather()
        /*
         * 班级日程是独立 Activity，它有自己的 ViewModel —— 那边改的清单
         * 只落在 SharedPreferences 里，这个 VM 不知道。回到前台重读一次，
         * 否则从那边回来首页还是旧清单（详情见 reloadClassPlans 的注释）。
         *
         * 日程放在前面：reloadClassPlans 会顺手重算首页那一行，里面含
         * 「学委删了 N 条，本地还留着」的计数，得先拿到新的 agenda 才算得准。
         */
        vm.reloadAgendas()
        vm.reloadClassPlans()
        // 用户可能刚去系统设置里开了通知 / 精确闹钟，回来要立刻反映，
        // 不然设置页「未授权」的红字一直挂着
        notifyOk = notifyGranted()
        val exactNow = vm.canExactAlarm()
        // 刚拿到精确闹钟授权：原来排的是「非精确」，要换成精确的重排一遍
        if (exactNow && !exactOk) vm.onExactAlarmGranted()
        exactOk = exactNow
    }

    var detail by remember { mutableStateOf<CourseSession?>(null) }
    /**
     * 正在看的考试详情（null = 没打开）。
     *
     * 三个面板（日程列表 / 日程编辑 / 考试详情）互斥打开 —— 手机端每个
     * AdaptiveSheet 都是一个 Dialog 窗口，同时叠两个就会出
     * 「窗口关不掉、整屏点不动」那种事。
     */
    var selectedExam by remember { mutableStateOf<com.gzucm.pocket.core.ExamItem?>(null) }

    /*
     * 日程面板状态放在 RootScreen 一层（不放在 ScheduleScreen 里）：
     * 今日页和课表页都能点日程，切 Tab 不该把编辑到一半的内容丢掉。
     */
    var showAgendaList by remember { mutableStateOf(false) }
    var showAgendaEdit by remember { mutableStateOf(false) }
    var editingAgenda by remember { mutableStateOf<com.gzucm.pocket.core.Agenda?>(null) }
    var draftAgendaDate by remember { mutableStateOf<java.time.LocalDate?>(null) }
    var draftAgendaMinute by remember { mutableStateOf<Int?>(null) }

    /*
     * 日程列表里「＋ 新建日程」的默认日期。
     *
     * 由 ScheduleScreen 按当前视图算好传上来：周视图 → 本周今天 / 别的周周一，
     * 日视图 → 当前翻到的那一天。打开列表那一刻定下来就够了 ——
     * 列表是全屏面板，开着的时候翻不了周。
     */
    var agendaListDate by remember { mutableStateOf<java.time.LocalDate?>(null) }

    /** 从一个展开实例找到它的原始规则，打开编辑 */
    /*
     * 课表里全部可点条目（日程 + 考试）。
     *
     * 详情面板的「重叠安排」切换条要靠它算同时段还有谁 ——
     * 跟课程详情里那条「重叠课程」是一个意思，只是这里混了两种来源。
     */
    val allEntries = vm.agendaOccurrences(state.semester) + vm.examOccurrences(state.semester)

    /** 当前正在看的那条（日程走 editingAgenda，考试走 selectedExam） */
    val editingEntry = editingAgenda?.let { a -> allEntries.firstOrNull { it.sourceId == a.id } }
    val selectedEntry = selectedExam?.let { e ->
        val key = com.gzucm.pocket.core.EXAM_PREFIX + vm.examId(e)
        allEntries.firstOrNull { it.sourceId == key }
    }

    fun overlapsOf(o: com.gzucm.pocket.core.AgendaOccurrence?): List<com.gzucm.pocket.core.AgendaOccurrence> {
        if (o == null) return emptyList()
        return allEntries.filter {
            it.date == o.date && it.startMinute < o.endMinute && o.startMinute < it.endMinute
        }
    }

    /** 详情面板里点另一条：按类型换面板，日程去编辑、考试去只读详情 */
    fun switchEntry(o: com.gzucm.pocket.core.AgendaOccurrence) {
        val prefix = com.gzucm.pocket.core.EXAM_PREFIX
        if (o.sourceId.startsWith(prefix)) {
            val id = o.sourceId.removePrefix(prefix)
            selectedExam = state.exams.firstOrNull { vm.examId(it) == id }
            showAgendaEdit = false
            showAgendaList = true
        } else {
            editingAgenda = state.agenda.firstOrNull { it.id == o.sourceId }
            draftAgendaDate = o.date
            draftAgendaMinute = o.startMinute
            showAgendaList = false
            showAgendaEdit = true
        }
    }

    fun openAgendaFromOccurrence(
        o: com.gzucm.pocket.core.AgendaOccurrence,
        rules: List<com.gzucm.pocket.core.Agenda>,
        exams: List<com.gzucm.pocket.core.ExamItem>,
    ) {
        /*
         * 考试和日程共用一种渲染条目，靠 sourceId 前缀分流：
         * 考试 → 只读详情；日程 → 可编辑表单。
         */
        if (o.sourceId.startsWith(com.gzucm.pocket.core.EXAM_PREFIX)) {
            val id = o.sourceId.removePrefix(com.gzucm.pocket.core.EXAM_PREFIX)
            val hit = exams.firstOrNull { vm.examId(it) == id }
            if (hit != null) {
                // 同一个面板内切到详情：不新开 Dialog，返回键才不会把 App 退到桌面
                selectedExam = hit
                showAgendaList = true
            }
            return
        }
        editingAgenda = rules.firstOrNull { it.id == o.sourceId }
        draftAgendaDate = o.date
        draftAgendaMinute = o.startMinute
        selectedExam = null
        showAgendaEdit = true
    }

    var showSettings by remember { mutableStateOf(false) }
    var showEcardSync by remember { mutableStateOf(false) }
    var showRebindGuide by remember { mutableStateOf(false) }
    var showDonate by remember { mutableStateOf(false) }
    var showAbout by remember { mutableStateOf(false) }
    // 「导出课表」子页面（复制分享串 / 导出 JSON 文件），和关于、打赏同一层级
    var showExport by remember { mutableStateOf(false) }
    // 「问题反馈」子页面（大字版本号 + 问卷入口），同一层级
    var showFeedback by remember { mutableStateOf(false) }
    // 「今日页显示」子页面（电费 / 天气 / 接下来 / 本周概览 开关 + 提前天数）
    var showTodaySectionsInSettings by remember { mutableStateOf(false) }
    // 「添加到桌面小组件」教程页（各机型步骤 + 尺寸预览），和关于页同一层级
    var showWidgetGuide by remember { mutableStateOf(false) }
    // 「天气提醒」二级页（两档开关 + 时间滚轮 + 立即预览），和小组件教程页同一层级
    var showWeatherAlertInSettings by remember { mutableStateOf(false) }
    /*
     * 天气地点。
     *
     * 单独存一份 state 而不是每次现读 SharedPreferences：后者不是可观察对象，
     * 在子页面里选完地点，设置页那一行的地点名不会跟着变。
     * 真值仍以 WeatherPlaceStore 为准，这里只是它的镜像。
     */
    val weatherPlaceStore = remember {
        com.gzucm.pocket.core.WeatherPlaceStore(ctx0.applicationContext)
    }
    var weatherPlace by remember { mutableStateOf(weatherPlaceStore.place) }
    // 「天气位置」子页面（4 个预设 + 使用当前位置）
    var showWeatherPlaceInSettings by remember { mutableStateOf(false) }
    /*
     * 这一页是从今日页天气卡点进来的，还是从设置里点进来的。
     *
     * 两者的「返回」落点不同（用户 2026-11-28 定的）：
     *   · 今日页 → 天气位置：选完 / 返回都应该回今日页，不能停在设置上；
     *   · 设置 → 天气位置：老行为，回设置页。
     * 只用一个来源标记，不新增面板层级（叠两层 Dialog 按 BACK 会掉回桌面）。
     */
    var weatherPlaceFromToday by remember { mutableStateOf(false) }
    /**
     * 今日页显示配置。
     *
     * 单独存一份状态而不是每次读 prefs：SharedPreferences 不是可观察对象，
     * 面板里一改，首页不会重组 —— 用户会以为开关没生效。
     *
     * 两处入口（设置子页面 / 首页长按）都改这一份，再回写磁盘。
     */
    var todaySections by remember { mutableStateOf(prefs.todaySections) }
    // 首页长按卡片弹出的那个（独立面板，不在设置里面）
    var showTodaySections by remember { mutableStateOf(false) }
    // 工作台：从成绩卡片进去的那一层，以及正在用内嵌网页打开的学校服务
    var showGradesFromWorkbench by remember { mutableStateOf(false) }
    /*
     * 「工作台默认打开成绩页」。
     *
     * 单独存一份 state：SharedPreferences 不是可观察对象，
     * 用户在成绩页拨了开关，页面上那个 Switch 不会自己跟着变。
     */
    var gradesDefault by remember { mutableStateOf(prefs.workbenchOpenGrades) }
    /*
     * 学校服务的打开次数。
     *
     * 存一份 state 而不是每次现读 prefs：SharedPreferences 不是可观察对象，
     * 点完一张卡片回来，「常用」区不会自己重排 —— 用户会以为计数没生效。
     */
    var campusCounts by remember { mutableStateOf(prefs.campusOpenCounts) }
    fun applyTodaySections(s: com.gzucm.pocket.core.TodaySections) {
        val old = todaySections
        todaySections = s
        prefs.todaySections = s
        /*
         * 天气从关到开时补一次请求（否则要等下次冷启动，
         * 用户刚打开开关却看不到卡片，会以为开关坏了）。
         * 关的方向不用管：ViewModel 那边的刷新入口自己会看这个开关。
         */
        vm.onTodaySectionsChanged(old, s)
    }
    // 从「登录已过期」点「去登录」进来时：设置页要自动滚到「重新登录」并抖动提示
    var focusRelogin by remember { mutableStateOf(false) }

    /**
     * 电费同步策略（2026-10-30 改）。
     *
     * 之前是「每天首次打开 App 挂一个 1dp 隐藏 WebView 静默同步」，但那条路
     * 一直失败：页面被判为不可见 → JS 定时器被系统节流 → 分页循环跑不完，
     * 经常只拿到余额、拿不到明细，用户最后还是得手动点一次。
     *
     * 现在只保留**弹窗**这一条：进电费页时，如果数据过期（超 2 小时）
     * 或是刚登录/刚手动同步过，就自动弹一次。弹窗看得见进度，也确实能成功。
     * 不打开电费页就不打扰 —— 首页电费卡会标明数据有多旧。
     */
    // 进电费页时按需自动弹窗
    LaunchedEffect(tab, state.phase) {
        if (tab != Tab.Power) return@LaunchedEffect
        if (state.phase != Phase.Ready) return@LaunchedEffect
        // 没有一卡通会话时弹窗也是跳 SSO，先不打扰
        if (!com.gzucm.pocket.core.Net.cookies.has("ecard.gzucm.edu.cn")) return@LaunchedEffect
        val need = vm.powerDirty || com.gzucm.pocket.core.PowerAlert.needsAutoSync(
            prefs.powerCheckedAt, prefs.lastPowerAttemptAt, System.currentTimeMillis(),
        )
        if (need && !showEcardSync) showEcardSync = true
    }

    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        when (state.phase) {
            Phase.NeedLogin -> LoginScreen(
                onLoggedIn = vm::onLoggedIn,
                onJwReady = vm::onJwReady,
                onGraduateLoggedIn = vm::onGraduateLoggedIn,
                /*
                 * 进来时默认选中学段。用存储里记住的那个 ——
                 * 研究生用户重新登录时不用每次手动切。
                 */
                /*
                 * 默认本科 + 只记「主动切过的」。
                 *
                 * 之前传的是 vm.campus（当前数据属于哪个学段），
                 * 结果本科生只要登过一次研究生账号，之后每次都停在研究生。
                 */
                initialCampus = vm.rememberedCampus,
                onCampusPicked = vm::rememberCampusPicked,
                /*
                 * 只有「点同步发现会话过期」这条路径会带说明进来；
                 * 冷启动未登录 / 主动退出登录时是 null，登录页跟以前一模一样。
                 */
                notice = vm.reloginNotice,
                /*
                 * 有缓存课表才给「稍后再登」：自动弹到登录页时，
                 * 用户至少得能退回去看已经同步好的课表。
                 */
                onSkip = if (vm.state.sessions.isNotEmpty()) vm::skipRelogin else null,
            )

            else -> MainScaffold(
                tab = tab,
                onSelect = { next ->
                    // 先记下「是不是真的换了 tab」，再改 tab —— 顺序反了就永远是 false
                    val switched = next != tab
                    tab = next
                    /*
                     * 切到工作台时，如果用户选了「默认打开成绩页」就直接落在成绩上。
                     *
                     * 只在真的从别的 tab 切过来时触发：已经在工作台时再点一次，
                     * 不该把用户从「← 工作台」退回来的首页又弹回成绩。
                     */
                    if (switched && next == Tab.Workbench && gradesDefault) {
                        showGradesFromWorkbench = true
                    }
                },
                campus = vm.campus,
                /*
                 * 宽屏侧栏底部的两个动作。
                 *
                 * 用 vm 的方法而不是直接改 tab —— 「同步」在这里的语义
                 * 跟首页标题栏那个刷新按钮一致：触发一次同步，不跳页。
                 * 「有新版本」小红点也一起传下去，原来在标题栏设置图标上的。
                 */
                onSyncClick = { vm.sync() },
                onSettingsClick = { showSettings = true },
                hasUpdate = vm.hasUpdate,
                /*
                 * 原来写的是 `syncing && sessions.isEmpty()` —— 那是首页
                 * 「首次加载占位」的判据，不是「同步进行中」。
                 * 已经有课表时 sessions 非空，条件永远 false，转圈永远不出现。
                 * 侧栏按钮只需要知道「现在是不是在同步」。
                 */
                syncing = vm.state.syncing,
            ) {
                /*
                 * 切 Tab 时保留各页状态。
                 *
                 * AnimatedContent 在切走时会把旧页彻底销毁，`rememberSaveable`
                 * 存的东西跟着没了 —— 表现是「看课表滑到第 12 节，切去成绩再回来，
                 * 又回到顶上了」。
                 *
                 * SaveableStateHolder 按 key 把状态寄存起来，切回来时原样恢复。
                 * key 用 Tab 本身，一个 Tab 一份。
                 */
                val stateHolder = rememberSaveableStateHolder()
                AnimatedContent(
                    targetState = tab,
                    transitionSpec = { fadeIn() togetherWith fadeOut() },
                    label = "tab",
                ) { t ->
                    stateHolder.SaveableStateProvider(t.name) {
                    when (t) {
                        Tab.Today -> {
                        TodayScreen(
                            semester = state.semester,
                            currentWeek = vm.currentWeek,
                            sessions = state.sessions,
                            power = state.power,
                            powerDaily = state.powerDaily,
                            powerCheckedAt = prefs.powerCheckedAt,
                            onCourseClick = { detail = it },
                            /*
                             * 任务单独一路传，不混进 agendaOccurrences：
                             * 它不受「显示明天」的日期过滤影响（截止前每天都显示），
                             * 也需要独立的 key + 淡出动画（混在一起会 slot 复用）。
                             */
                            agendaOccurrences = vm.agendaOccurrences(state.semester) +
                                vm.examOccurrences(state.semester),
                            taskCards = vm.todayTaskOccurrences(state.semester),
                            onAgendaClick = { o -> openAgendaFromOccurrence(o, state.agenda, state.exams) },
                            onTaskCheck = { o ->
                                // 已完成卡片仍留在首页最下方（变淡），点勾即取消完成
                                vm.setTaskDone(o.sourceId, done = o.doneAt == null)
                            },
                            sections = todaySections,
                            weather = state.weather,
                            weatherRefreshing = state.weatherRefreshing,
                            weatherError = state.weatherError,
                            weatherPlaceLabel = weatherPlace.label,
                            /*
                             * 今日页点地点 → 复用设置面板里的「天气位置」子页。
                             * 记下来源：返回 / 选完都直接回今日页，不停在设置上。
                             */
                            onOpenWeatherPlace = {
                                weatherPlaceFromToday = true
                                showWeatherPlaceInSettings = true
                                showSettings = true
                            },
                            onOpenSections = { showTodaySections = true },
                            onOpenPower = { tab = Tab.Power },
                            onOpenSettings = { showSettings = true },
                            onOpenLogin = {
                                showSettings = true
                                focusRelogin = true
                            },
                            onRefresh = { vm.sync() },
                            syncing = state.syncing,
                            progressDone = state.progressDone,
                            progressTotal = state.progressTotal,
                            hasUpdate = vm.hasUpdate,
                            notice = state.message,
                            /*
                             * 班级日程那行绑在「待办与日程」卡里，不单独成块
                             * （致辉 2026-11-22 定的：状态 + 入口合在一张卡里，
                             * 条目本身仍混在日程时间线上，靠角标区分）。
                             */
                            classPlan = vm.classPlanHint?.bar,
                            classPlanOrphaned = vm.classPlanHint?.orphaned ?: 0,
                            /*
                             * 「查看 / 认领」直接开独立 Activity（2026-11-23）。
                             *
                             * 原来是弹居中窗再转详情面板：列表几十行，往下翻和
                             * 「下拉关面板」是同一个手势，看着看着清单就没了。
                             * 整页之后滑动只是滚动。
                             */
                            onClassPlanAction = {
                                runCatching {
                                    ctx0.startActivity(
                                        com.gzucm.pocket.ui.schedule.ClassPlanActivity.intent(
                                            ctx0,
                                            com.gzucm.pocket.ui.schedule.ClassPlanActivity.MODE_CLAIM,
                                        ),
                                    )
                                }
                            },
                            onClassPlanResolveGone = { tab = Tab.Schedule },
                        )
                        ClassPlanSyncDialogs(vm)
                        }

                        Tab.Schedule -> ScheduleScreen(
                            semester = state.semester,
                            sessions = state.sessions,
                            currentWeek = vm.currentWeek,
                            viewingWeek = vm.viewingWeek,
                            onWeekChange = vm::goToWeek,
                            onCourseClick = { detail = it },
                            loadedWeeks = state.loadedWeeks,
                            syncing = state.syncing,
                            progressDone = state.progressDone,
                            progressTotal = state.progressTotal,
                            agendaRules = state.agenda,
                            agendaOccurrences = vm.agendaOccurrences(state.semester) +
                                vm.examOccurrences(state.semester),
                            onOpenAgendaList = { date ->
                                agendaListDate = date
                                showAgendaList = true
                                selectedExam = null
                            },
                            onAgendaClick = { o -> openAgendaFromOccurrence(o, state.agenda, state.exams) },
                            onAddAgenda = { date, minute ->
                                editingAgenda = null
                                draftAgendaDate = date
                                draftAgendaMinute = minute
                                showAgendaEdit = true
                            },
                        )

                        Tab.Workbench -> {
                            if (showGradesFromWorkbench) {
                                LaunchedEffect(Unit) { vm.loadGrades() }
                                GradesScreen(
                                    grades = state.grades,
                                    levels = state.levels,
                                    fitness = state.fitness,
                                    loading = state.gradesLoading,
                                    progressDone = state.gradesDone,
                                    progressTotal = state.gradesTotal,
                                    stage = state.gradesStage,
                                    updatedAt = prefs.gradesCheckedAt,
                                    onRefresh = { vm.loadGrades(force = true) },
                                    defaultOpenGrades = gradesDefault,
                                    onDefaultOpenChange = { on ->
                                        gradesDefault = on
                                        prefs.workbenchOpenGrades = on
                                    },
                                    needLogin = vm.loginExpired,
                                    onOpenLogin = {
                                        showSettings = true
                                        focusRelogin = true
                                    },
                                    onBack = { showGradesFromWorkbench = false },
                                )
                            } else {
                                com.gzucm.pocket.ui.workbench.WorkbenchScreen(
                                    studentName = state.studentCard?.name,
                                    gradeCount = state.grades.size,
                                    onOpenGrades = { showGradesFromWorkbench = true },
                                    onOpenApp = { app ->
                                        /*
                                         * 只有真正在 App 内打开的（WEB）才计入「常用」。
                                         *
                                         * 跳企业微信的那几个（实验室、宿管、路路通…）点一下只是
                                         * 把用户送出去，一次都没在 App 里看到内容 —— 算进去会把
                                         * 常用区占满，把图书馆、办事大厅这些真正常用的挤下去
                                         * （2026-11-21 实测就是这样）。
                                         * 打不开的（BROKEN）同理不记。
                                         */
                                        if (app.kind == com.gzucm.pocket.core.CampusAppKind.WEB) {
                                            prefs.recordCampusOpen(app.id)
                                            campusCounts = prefs.campusOpenCounts
                                        }
                                        when {
                                            // 只能在浏览器里跑（实验室平台）
                                            app.external -> com.gzucm.pocket.ui.workbench
                                                .openInBrowser(ctx0, app.url.orEmpty())
                                            /*
                                             * 能直连的：开一个全屏网页页。
                                             *
                                             * 以前是弹 ModalBottomSheet，但它和网页滚动抢
                                             * 同一个下滑手势，手指一划就把页面关了
                                             * （2026-11-21 致辉报的）。独立 Activity 没这问题。
                                             */
                                            app.kind == com.gzucm.pocket.core.CampusAppKind.WEB ->
                                                ctx0.startActivity(
                                                    com.gzucm.pocket.ui.workbench.CampusWebActivity
                                                        .intent(
                                                            ctx0,
                                                            app.url.orEmpty(),
                                                            app.name,
                                                            app.desktopMode,
                                                        ),
                                                )
                                            // 企业微信专属 / 已知坏的：Toast + 跳转，不在这里开
                                            else -> com.gzucm.pocket.ui.workbench.openOutsideApp(
                                                ctx0,
                                                app,
                                            )
                                        }
                                    },
                                    openCounts = campusCounts,
                                )
                            }
                        }

                        Tab.Power -> {
                            /*
                             * 两个都刷：
                             *   · refreshEcardSession — 用户可能刚从一卡通登完回来
                             *   · loadPower — 有缓存就直接显示，没有才去读
                             */
                            LaunchedEffect(Unit) {
                                vm.refreshEcardSession()
                                vm.loadPower()
                            }
                            PowerScreen(
                                power = state.power,
                                daily = state.powerDaily,
                                loading = state.powerLoading,
                                error = state.powerError,
                                onSync = { showEcardSync = true },
                                // 一卡通会话独立于课表会话：研究生登录后它是空的，
                                // 空态要显示「登录一卡通」而不是「读不到数据」
                                ecardLoggedIn = state.ecardLoggedIn,
                            onOpenRebindGuide = { showRebindGuide = true },
                            powerAlertOn = vm.powerAlertOn,
                            onPowerAlertChange = { on ->
                                vm.setPowerAlert(on)
                                if (on && !notifyGranted()) askNotify.launch(
                                    android.Manifest.permission.POST_NOTIFICATIONS
                                )
                            },
                            )
                        }
                    }
                    } // SaveableStateProvider
                }
            }
        }
    }

    // 打赏已并入设置面板作为子页面（见下方 AdaptiveSheet 里的 showDonate 分支），
    // 这里不再单独开一层面板 —— 两层叠着会让层级关系变乱。

    /*
     * 课程详情：手机用底部 Sheet，平板用右侧滑出面板。
     *
     * 判断依据是**实际可用宽度**（和课表、左导航用同一套断点），
     * 不是设备型号 —— 平板分屏后窄了，应该退回底部 Sheet。
     *
     * 用 BoxWithConstraints 包住：只有详情打开时才需要知道宽度，
     * 不该为了这件事给整个页面加一层约束。
     */
    /*
     * 课程详情。
     *
     * 容器由 SessionSheet 内部自己选（平板侧栏 / 手机底部 Sheet），
     * 这里不再手动分叉 —— 所有面板统一走 AdaptiveSheet，
     * 免得每个调用点各写一遍断点判断。
     */
    /*
     * 今日页显示（长按今日页卡片打开）。
     *
     * 和日程面板一样常驻组合树、靠 visible 控制 —— 用 if 包起来会让
     * AdaptiveSheet 的退场动画不播、子树被直接摘掉。
     */
    com.gzucm.pocket.ui.settings.TodaySectionsSheet(
        visible = showTodaySections,
        onDismiss = { showTodaySections = false },
        sections = todaySections,
        onChange = { applyTodaySections(it) },
    )

    detail?.let { s ->
        /*
         * 同一时段的其他课。
         *
         * 判据和课表分簇一致：同周、同星期、节次区间有交集。
         * 在详情页把它们列出来，用户才知道「这个时段一共压了几门」，
         * 而不是点开一门以为课表只有这一节。
         */
        val group = vm.state.sessions.filter { o ->
            o !== s && o.week == s.week && o.weekday == s.weekday &&
                o.startUnit <= s.endUnit && s.startUnit <= o.endUnit
        }
        SessionSheet(
            session = s,
            onDismiss = { detail = null },
            overlapGroup = group,
        )
    }

    /*
     * 自定义日程的两个面板。
     *
     * 常驻组合树、靠 visible 控制：包 if 会让 AdaptiveSheet 的进入动画
     * 不播、退场时整棵子树被直接摘掉（项目里踩过三次）。
     * 保存/删除只走 ViewModel，state.sessions 一个字都不动 ——
     * 提醒排程、同步、缓存失效都看不到日程。
     */
    /*
     * 学校服务的内嵌网页。
     *
     * 挂在这一层而不是工作台内部：它是全屏覆盖，切 Tab 时也该跟着关掉 ——
     * 挂在工作台里的话，用户切到课表再切回来，网页会自己「复活」。
     */
    com.gzucm.pocket.ui.schedule.AgendaListSheet(
        visible = showAgendaList,
        semester = state.semester,
        rules = state.agenda,
        exams = state.exams,
        defaultDraftDate = agendaListDate,
        examDetail = selectedExam,
        onExamDetailClose = { selectedExam = null },
        examOverlapEntries = overlapsOf(selectedEntry),
        examCurrentKey = selectedEntry?.key ?: "",
        onSwitchEntry = { switchEntry(it) },
        onExamClick = { e -> selectedExam = e },
        /*
         * 班级日程的入口、数据、云端回调都搬去 ClassPlanActivity 了
         * （那是它自己的 ViewModel 和整页界面）。
         * 这个面板只剩「我的日程」，不再需要那 11 个班级日程参数。
         */
        onAdd = { date, minute ->
            editingAgenda = null
            draftAgendaDate = date
            draftAgendaMinute = minute
            showAgendaEdit = true
        },
        onEdit = { rule ->
            editingAgenda = rule
            draftAgendaDate = null
            draftAgendaMinute = null
            showAgendaEdit = true
        },
        onTaskCheck = { rule ->
            // 已完成点一下 = 取消完成（toggle），未完成点一下 = 标记完成
            vm.setTaskDone(rule.id, done = rule.doneAt == null)
        },
        onDismiss = {
            showAgendaList = false
            selectedExam = null
        },
    )

    com.gzucm.pocket.ui.schedule.AgendaEditSheet(
        visible = showAgendaEdit,
        semester = state.semester,
        initial = editingAgenda,
        draftDate = draftAgendaDate,
        draftStartMinute = draftAgendaMinute,
        onSave = { item ->
            vm.saveAgenda(item)
            showAgendaEdit = false
            editingAgenda = null
        },
        overlapEntries = overlapsOf(editingEntry),
        currentEntryKey = editingEntry?.key ?: "",
        onSwitchEntry = { switchEntry(it) },
        onDelete = { id ->
            vm.deleteAgenda(id)
            showAgendaEdit = false
            editingAgenda = null
        },
        onDismiss = {
            showAgendaEdit = false
            editingAgenda = null
        },
    )

    com.gzucm.pocket.ui.power.EcardSyncSheet(
        visible = showEcardSync,
        onDone = { info, daily, err ->
                showEcardSync = false
                /*
                 * 无论成功还是失败都刷一次会话状态。
                 *
                 * 用户可能登录成功了但数据没读到（分页没跑完之类），
                 * 这时 info 是 null，但一卡通 Cookie 已经拿到 ——
                 * 不刷新的话电费页还会显示「还没登录一卡通」，
                 * 用户会以为白登了一次。
                 */
                vm.refreshEcardSession()
                if (info != null) {
                    vm.applyEcardSync(info, daily)
                } else {
                    // 记下尝试时间：失败后 10 分钟内不再自动弹，免得反复打断。
                    // 同时清掉 powerDirty，否则它会短路冷却判定（见 onPowerSyncFailed）
                    prefs.lastPowerAttemptAt = System.currentTimeMillis()
                    vm.onPowerSyncFailed()
                    if (err != null) vm.showPowerError(err)
                }
            },
        onDismiss = { showEcardSync = false },
    )

    com.gzucm.pocket.ui.power.RebindGuideSheet(
        onDismiss = { showRebindGuide = false },
        visible = showRebindGuide,
    )

    /*
     * 手动同步结束后弹一次轻提示。
     *
     * 用 Android 原生 Toast 而不是 Snackbar：Snackbar 要挂在 Scaffold 上，
     * 而这里整个页面是自绘的骨架（没有 Scaffold），为了一个提示重构布局不值得。
     * Toast 的定位也符合「操作完了给个回执」这个轻量语义。
     *
     * 按 syncResultSeq 判断是不是新的一条 —— 连续两次同样的失败文案，
     * 光看字符串会以为还是上次那条，Toast 就不弹第二次了。
     */
    val ctxToast = androidx.compose.ui.platform.LocalContext.current
    /*
     * rememberSaveable 而不是 remember：分屏、改字体、改语言会让 Activity 重建，
     * 而 syncResultSeq 在 ViewModel 里跨重建保留 —— 用 remember 的话重建后
     * lastToastSeq 回到 0，上一次的同步提示会再弹一遍。
     */
    var lastToastSeq by androidx.compose.runtime.saveable.rememberSaveable { mutableIntStateOf(0) }
    LaunchedEffect(state.syncResultSeq) {
        if (state.syncResultSeq > lastToastSeq) {
            lastToastSeq = state.syncResultSeq
            state.syncResultText?.let { text ->
                android.widget.Toast.makeText(ctxToast, text, android.widget.Toast.LENGTH_SHORT).show()
            }
        }
    }

    /*
     * 天气提醒「立即预览」的结果也走 Toast。
     *
     * 消息是个一次性事件（连点两次成功会是同一句话，光看字符串分辨不出新旧），
     * 所以 ViewModel 里用「取出即清空」的方式传递：弹完立刻 consume，
     * 下次再设为同一句话时仍然是 null → 文案 的变化，LaunchedEffect 会重新触发。
     */
    LaunchedEffect(vm.weatherPreviewMessage) {
        val msg = vm.weatherPreviewMessage ?: return@LaunchedEffect
        android.widget.Toast.makeText(ctxToast, msg, android.widget.Toast.LENGTH_SHORT).show()
        vm.consumeWeatherPreviewMessage()
    }

    /*
     * 设置面板**常驻组合树**，靠 visible 控制显隐。
     *
     * 之前写成 `if (showSettings) { AdaptiveSheet(...) }` ——
     * 面板第一次进组合时 visible 就已经是 true，AnimatedVisibility
     * 认为「初始状态就是可见」，进入动画根本不会播（退场也一样，
     * 整棵子树被直接摘掉）。表现就是面板「啪」地出现、没有滑出效果。
     *
     * 常驻之后 AnimatedVisibility 能观察到 false → true 的变化，
     * 才会真的从侧边滑进来。
     */
    run {
        val lastSync = state.lastSync.let { ts ->
            if (ts <= 0) "—"
            else java.text.SimpleDateFormat("MM-dd HH:mm", java.util.Locale.CHINA)
                .format(java.util.Date(ts))
        }
        /*
         * 设置用侧栏形态（平板）/ 底部 Sheet（手机）。
         *
         * 侧栏宽 460dp：设置项多、还有滑块和颜色选择器，
         * 380dp 会让「上课提醒」那几行折行。
         */
        com.gzucm.pocket.ui.adaptive.AdaptiveSheet(
            visible = showSettings,
            onDismiss = {
                focusRelogin = false
                // 注意：这里必须把两个标志都清掉。
                // 之前只清一个，导致「在关于页下拉关掉面板」后 showSettings 还是 true，
                // Compose 仍认为面板存在 → 留下一个透明遮罩盖在首页上，什么都点不动。
                showAbout = false
                showExport = false
                showTodaySectionsInSettings = false
                showWidgetGuide = false
                showWeatherAlertInSettings = false
                showWeatherPlaceInSettings = false
                weatherPlaceFromToday = false
                showSettings = false
            },
            paneWidth = 460.dp,
            paneTitle = when {
                showAbout -> "关于软件"
                showDonate -> "打赏作者"
                showExport -> "导出课表"
                showTodaySectionsInSettings -> "今日页显示"
                showWeatherPlaceInSettings -> "天气位置"
                showWidgetGuide -> "添加到桌面小组件"
                showWeatherAlertInSettings -> "天气提醒"
                else -> "设置"
            },
            // 设置页和关于页正文自带 verticalScroll，这里必须关掉
            scrollable = false,
            // 平板横屏导航栏在左边，面板也从左边出来
            fromLeft = true,
        ) {
            // 不可见时正文不渲染：面板常驻了，内容没必要跟着一直算
            if (!showSettings) return@AdaptiveSheet

            /*
             * 返回键分层：子页面 → 设置主页面 → 关闭面板。
             *
             * 之前没有这层处理，在「关于软件 / 打赏作者」里按返回会直接把
             * 整个设置面板关掉，用户以为退出了设置。跟系统设置的习惯相反。
             */
            androidx.activity.compose.BackHandler(
                enabled = showAbout || showDonate || showExport || showFeedback ||
                    showTodaySectionsInSettings || showWeatherPlaceInSettings || showWidgetGuide ||
                    showWeatherAlertInSettings,
            ) {
                /*
                 * 从今日页进来的天气位置页，返回要直接回今日页：
                 * 面板整层关掉，而不是退到设置主页。
                 */
                if (showWeatherPlaceInSettings && weatherPlaceFromToday) {
                    showWeatherPlaceInSettings = false
                    weatherPlaceFromToday = false
                    showSettings = false
                    return@BackHandler
                }
                showAbout = false
                showDonate = false
                showExport = false
                showFeedback = false
                showTodaySectionsInSettings = false
                showWeatherPlaceInSettings = false
                showWidgetGuide = false
                showWeatherAlertInSettings = false
            }
            if (showFeedback) {
                com.gzucm.pocket.ui.settings.FeedbackSheet(
                    visible = true,
                    onDismiss = { showFeedback = false },
                )
                return@AdaptiveSheet
            }
            if (showAbout) {
                com.gzucm.pocket.ui.settings.AboutScreen(
                    onBack = { showAbout = false },
                    onChecked = { vm.refreshUpdateBadge() },
                )
                return@AdaptiveSheet
            }
            /*
             * 打赏作为设置面板里的**子页面**，跟「关于软件」同一层级。
             *
             * 原来它是独立一层面板（root 里另开一个 DonateSheet），
             * 表现是：从设置点进打赏 → 又一个面板盖在上面 →
             * 标题变成「打赏作者」，但底下还压着设置那一层，
             * 返回时是两层动画叠着退回，层级关系看着很乱。
             *
             * 现在跟关于软件一致：同一块侧栏，只换正文和标题，
             * 关闭/返回都回到设置本身，层级是平的。
             */
            if (showDonate) {
                com.gzucm.pocket.ui.settings.DonateContent(
                    onBack = { showDonate = false },
                )
                return@AdaptiveSheet
            }
            /*
             * 导出课表子页面（致辉 2026-11-18 晚：太长就导出 json，发文件）。
             *
             * 和关于、打赏同一层级 —— 同一块面板换正文，返回回到设置。
             * 不另开一层 Dialog：那会让 BACK 先关 Dialog 再关面板，
             * 层级一乱就容易出现「按一下直接退到桌面」。
             */
            if (showExport) {
                val sem = state.semester
                val sessions = state.sessions
                /*
                 * 现算，不进 ViewModel：
                 *   · 只在子页面渲染时才做（450 条分组 + 排序，几毫秒）；
                 *   · key 里带 sessions，后台补齐周次后自动重算。
                 *
                 * includeContents = true **只给文件形态**：文件没有字数限制，
                 * 逐周上课内容全带上；分享串那份仍然空着（它要能进微信）。
                 */
                val exportDoc = androidx.compose.runtime.remember(showExport, sem, sessions) {
                    if (sem == null || sessions.isEmpty()) {
                        null
                    } else {
                        com.gzucm.pocket.core.buildTimetableExport(
                            semester = sem,
                            sessions = sessions,
                            loadedWeeks = state.loadedWeeks,
                            versionName = com.gzucm.pocket.BuildConfig.VERSION_NAME,
                            versionCode = com.gzucm.pocket.BuildConfig.VERSION_CODE,
                            includeContents = true,
                        )
                    }
                }
                // 分享串永远不带 contents：它存在的唯一理由是塞进微信一条消息
                val exportToken = androidx.compose.runtime.remember(exportDoc) {
                    exportDoc?.let {
                        com.gzucm.pocket.core.encodeTimetableToken(it.copy(contents = emptyList()))
                    }
                }
                com.gzucm.pocket.ui.settings.ExportContent(
                    courseCount = exportDoc?.courses?.size ?: 0,
                    weeksCovered = exportDoc?.coverage?.weeks?.size ?: 0,
                    totalWeeks = exportDoc?.semester?.totalWeeks ?: 0,
                    tokenChars = exportToken?.length ?: 0,
                    onBack = { showExport = false },
                    onCopyToken = {
                        if (exportToken == null || exportDoc == null) {
                            android.widget.Toast.makeText(
                                ctxToast,
                                "课表还没同步，先点「立即同步」",
                                android.widget.Toast.LENGTH_SHORT,
                            ).show()
                        } else {
                            val cm =
                                ctxToast.getSystemService(android.content.Context.CLIPBOARD_SERVICE)
                                    as android.content.ClipboardManager
                            cm.setPrimaryClip(
                                android.content.ClipData.newPlainText("广中医口袋课表", exportToken),
                            )
                            val head = "已复制 %d 字符 · %d 门课".format(
                                java.util.Locale.CHINA,
                                exportToken.length,
                                exportDoc.courses.size,
                            )
                            val warn = if (exportDoc.coverage.complete) {
                                ""
                            } else {
                                "；只含 ${exportDoc.coverage.weeks.size}/" +
                                    "${exportDoc.semester.totalWeeks} 周，建议先同步完整"
                            }
                            android.widget.Toast.makeText(
                                ctxToast,
                                head + warn,
                                if (warn.isEmpty()) android.widget.Toast.LENGTH_SHORT
                                else android.widget.Toast.LENGTH_LONG,
                            ).show()
                        }
                    },
                    onShareFile = {
                        if (exportDoc == null) {
                            android.widget.Toast.makeText(
                                ctxToast,
                                "课表还没同步，先点「立即同步」",
                                android.widget.Toast.LENGTH_SHORT,
                            ).show()
                        } else {
                            /*
                             * 写 cache/exports/ → prune 只留这一份 → 抛系统分享面板。
                             *
                             * 整段包 runCatching：机型上「分享面板没有接收方」
                             * 或 FileProvider 配置对不上都会抛，不能让 App 直接崩。
                             */
                            runCatching {
                                val file = com.gzucm.pocket.core.TimetableExportFile
                                    .write(ctxToast, exportDoc)
                                com.gzucm.pocket.core.TimetableExportFile
                                    .prune(ctxToast, keep = file)
                                ctxToast.startActivity(
                                    com.gzucm.pocket.core.TimetableExportFile
                                        .shareIntent(ctxToast, file),
                                )
                            }.onFailure { e ->
                                android.widget.Toast.makeText(
                                    ctxToast,
                                    "导出失败：${e.message}",
                                    android.widget.Toast.LENGTH_LONG,
                                ).show()
                            }
                        }
                    },
                )
                return@AdaptiveSheet
            }
            /*
             * 今日页显示（2026-11-20）。
             *
             * 和「导出课表」「关于」同一层级：同一块面板换正文、换标题，返回回到设置。
             * 不在这里另开一个 AdaptiveSheet —— 手机上一个面板就是一个 Dialog，
             * 两层叠起来按 BACK 会先关掉一层，层级一乱就出现「退回桌面」。
             */
            if (showTodaySectionsInSettings) {
                com.gzucm.pocket.ui.settings.TodaySectionsContent(
                    sections = todaySections,
                    onChange = { applyTodaySections(it) },
                    onBack = { showTodaySectionsInSettings = false },
                )
                return@AdaptiveSheet
            }
            /*
             * 天气位置（2026-11-23）。
             *
             * 同样是一个子页面，理由和「今日页显示」一样：手机上一个面板就是一个 Dialog。
             *
             * 选中之后：先落 state（让设置页那行的名字立刻变），再交给 ViewModel
             * 存盘 + 清缓存 + 绕过节流重拉；最后关掉这一页。
             */
            if (showWidgetGuide) {
                com.gzucm.pocket.ui.settings.WidgetGuideScreen(
                    onBack = { showWidgetGuide = false },
                )
                return@AdaptiveSheet
            }
            /*
             * 天气提醒（2026-11-26）。
             *
             * 从设置页那张卡改成二级页：原来两档开关 + 两个时间行 + 两个预览按钮
             * 加权限提示约 300dp，把「立即同步 / 退出登录」都挤出首屏。
             * 行为与原来完全一致（开关顺手要通知权限、预览走同一个 ViewModel 入口），
             * 只是正文换了一页。
             */
            if (showWeatherAlertInSettings) {
                com.gzucm.pocket.ui.settings.WeatherAlertScreen(
                    morningOn = vm.weatherMorningOn,
                    morningTime = vm.weatherMorningTime,
                    onMorningChange = { on ->
                        vm.setWeatherMorning(on)
                        // 开着却发不出通知 = 白开；和上课提醒一样，点开就顺手要权限
                        if (on && !notifyGranted()) askNotify.launch(
                            android.Manifest.permission.POST_NOTIFICATIONS
                        )
                    },
                    onMorningTimeChange = { vm.changeWeatherMorningTime(it) },
                    eveningOn = vm.weatherEveningOn,
                    eveningTime = vm.weatherEveningTime,
                    onEveningChange = { on ->
                        vm.setWeatherEvening(on)
                        if (on && !notifyGranted()) askNotify.launch(
                            android.Manifest.permission.POST_NOTIFICATIONS
                        )
                    },
                    onEveningTimeChange = { vm.changeWeatherEveningTime(it) },
                    notifyGranted = notifyOk,
                    onRequestNotify = {
                        askNotify.launch(android.Manifest.permission.POST_NOTIFICATIONS)
                    },
                    onPreviewMorning = { vm.previewWeatherAlert(morning = true) },
                    onPreviewEvening = { vm.previewWeatherAlert(morning = false) },
                    previewBusy = vm.weatherPreviewBusy,
                    onBack = { showWeatherAlertInSettings = false },
                )
                return@AdaptiveSheet
            }
            if (showWeatherPlaceInSettings) {
                /*
                 * 选完 / 返回的收尾：今日页进来的就把整个面板关掉（回今日页），
                 * 设置进来的只清子页标记（回设置页）。老路径行为不变。
                 */
                val closeWeatherPlace = {
                    showWeatherPlaceInSettings = false
                    if (weatherPlaceFromToday) {
                        weatherPlaceFromToday = false
                        showSettings = false
                    }
                }
                com.gzucm.pocket.ui.settings.WeatherPlaceContent(
                    current = weatherPlace,
                    onPick = { picked ->
                        weatherPlace = picked
                        vm.onWeatherPlaceChanged(picked)
                        closeWeatherPlace()
                    },
                    onBack = { closeWeatherPlace() },
                )
                return@AdaptiveSheet
            }
            com.gzucm.pocket.ui.settings.SettingsScreen(
                onSync = { vm.sync(); showSettings = false },
                onLogout = { vm.logout(); showSettings = false },
                onClose = { showSettings = false },
                onRelogin = {
                    focusRelogin = false
                    /*
                     * **只切 phase，绝对不能调 logout()**（2026-11-02 修的回归）。
                     *
                     * 「重新登录」和「退出登录」是两个语义：
                     *   · 重新登录 = 会话可能过期了，让用户再走一遍认证。
                     *     WebView 里的 CAS 会话（CASTGC）通常还活着，
                     *     打开 ssoLogin 会直接带 ticket 跳回来，**不用输密码**。
                     *   · 退出登录 = 换账号，必须把 cookie 清干净。
                     *
                     * 之前这里写的是 `vm.logout(); vm.reopenLogin()` ——
                     * logout 会清掉 WebView Cookie，于是「重新登录」
                     * 变成了「把登录信息删掉、手动重输账号密码」。
                     * AppViewModel.reopenLogin 的注释里写了不能共用清理逻辑，
                     * 但调用方还是漏了这一步。
                     */
                    vm.relogin()
                    showSettings = false
                },
                lastSyncText = lastSync,
                semesterText = state.semester?.name ?: "—",
                focusRelogin = focusRelogin,
                hasUpdate = vm.hasUpdate,
                /*
                 * 「导出课表数据」只是入口：点它进子页面，具体两条路
                 * （复制分享串 / 导出 JSON 文件）都在上面 showExport 分支里。
                 *
                 * 之前这里是「点了直接复制到剪贴板」，2026-11-18 晚致辉补了一句
                 * 「太长就导出为 json，发文件就可以」—— 于是入口改成先让他选。
                 */
                onOpenExport = { showExport = true },
                onOpenWidgetGuide = { showWidgetGuide = true },
                onOpenWeatherAlert = { showWeatherAlertInSettings = true },
                onOpenTodaySections = { showTodaySectionsInSettings = true },
                weatherPlaceLabel = weatherPlace.label,
                weatherAvailable = com.gzucm.pocket.core.Weather.enabled,
                onOpenWeatherPlace = {
                    // 设置 → 天气位置：老路径，返回回设置页
                    weatherPlaceFromToday = false
                    showWeatherPlaceInSettings = true
                },
                startTabIndex = prefs.startTab,
                onStartTabChange = {
                    prefs.startTab = it
                    // 选完立刻生效，不用等下次启动
                    tab = Tab.entries.getOrElse(it) { Tab.Today }
                },
                onDonate = { showDonate = true },
                onOpenFeedback = { showFeedback = true },
                onOpenAbout = { showAbout = true },
                classReminder = vm.classReminderOn,
                onClassReminderChange = { on ->
                    // 先落状态再要权限：用户点了开启就是他的意图
                    vm.setClassReminder(on)
                    if (on && !notifyGranted()) askNotify.launch(
                        android.Manifest.permission.POST_NOTIFICATIONS
                    )
                },
                leadFree = vm.classLeadFree,
                leadBreak = vm.classLeadBreak,
                onLeadChange = { f, b -> vm.setClassLead(f, b) },
                notifyGranted = notifyOk,
                onRequestNotify = {
                    askNotify.launch(android.Manifest.permission.POST_NOTIFICATIONS)
                },
                reminderEnhanced = vm.classReminderEnhanced,
                onReminderEnhancedChange = { vm.toggleReminderEnhanced(it) },
                exactAlarmGranted = exactOk,
                onRequestExactAlarm = {
                    // Android 12+ 才有这个设置页；回来时 ON_RESUME 会刷新 exactOk 并自动重排
                    if (android.os.Build.VERSION.SDK_INT >= 31) {
                        runCatching {
                            ctx0.startActivity(
                                android.content.Intent(
                                    android.provider.Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM,
                                    android.net.Uri.parse("package:${ctx0.packageName}"),
                                ).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK),
                            )
                        }
                    }
                },
                weatherMorning = vm.weatherMorningOn,
                onWeatherMorningChange = { on ->
                    vm.setWeatherMorning(on)
                    // 开着却发不出通知 = 白开；和上课提醒一样，点开就顺手要权限
                    if (on && !notifyGranted()) askNotify.launch(
                        android.Manifest.permission.POST_NOTIFICATIONS
                    )
                },
                weatherMorningTime = vm.weatherMorningTime,
                onWeatherMorningTimeChange = { vm.changeWeatherMorningTime(it) },
                weatherEvening = vm.weatherEveningOn,
                onWeatherEveningChange = { on ->
                    vm.setWeatherEvening(on)
                    if (on && !notifyGranted()) askNotify.launch(
                        android.Manifest.permission.POST_NOTIFICATIONS
                    )
                },
                weatherEveningTime = vm.weatherEveningTime,
                onWeatherEveningTimeChange = { vm.changeWeatherEveningTime(it) },
                onPreviewMorning = { vm.previewWeatherAlert(morning = true) },
                onPreviewEvening = { vm.previewWeatherAlert(morning = false) },
                previewBusy = vm.weatherPreviewBusy,
            )
        }
    }
}

/** 单测抓手：底栏当前整体 alpha（渐隐过程中在 0.15~1 之间过渡） */
internal val FloatingBarAlphaKey = SemanticsPropertyKey<Float>("FloatingBarAlpha")
internal var SemanticsPropertyReceiver.floatingBarAlpha by FloatingBarAlphaKey

/**
 * 悬浮底栏。
 *
 * - 不贴屏幕底边，左右留边、大圆角，浮在内容之上；
 * - 用 haze 做 backdrop blur，后方滚动内容会以磨砂形式透出来；
 * - 胶囊用 `navigationBarsPadding` 避开手势小白条，下方的留白让它自然浮起；
 * - 只在课表页静置 3 秒后渐隐到 15%（见 [autoHide]）。
 */
@Composable
private fun FloatingBar(
    tab: Tab,
    onSelect: (Tab) -> Unit,
    campus: Campus,
    /** 是否启用自动渐隐；只有课表页传 true */
    autoHide: Boolean = true,
    modifier: Modifier = Modifier,
    hazeState: HazeState,
) {
    val tabs = tabsOf(campus)
    val scheme = MaterialTheme.colorScheme
    val dark = ThemeState.isDark(isSystemInDarkTheme())
    val style = glassStyle(alpha = floatingBarGlassAlpha(dark))

    /*
     * wakeTick 每碰一次底栏 +1，计时 effect 跟着重启：
     * 立刻取消旧的延时、把 dimmed 拉回 false，再重新数 3 秒。
     * 切到非课表页时 autoHide 变化也会重启 effect —— 那时不排延时，
     * 底栏立刻全亮；切回课表页再重新数 3 秒。
     */
    var wakeTick by remember { mutableIntStateOf(0) }
    var dimmed by remember { mutableStateOf(false) }
    // 记下「按下那一刻是不是半透明」：半透明时的第一次点击只唤醒，不切页
    var dimmedAtDown by remember { mutableStateOf(false) }
    val currentDimmed by rememberUpdatedState(dimmed)
    val currentAutoHide by rememberUpdatedState(autoHide)

    LaunchedEffect(wakeTick, autoHide) {
        dimmed = false
        dimmedAtDown = false
        if (autoHide) {
            delay(FloatingBarIdleMs)
            dimmed = true
        }
    }
    val barAlpha by animateFloatAsState(
        targetValue = if (autoHide && dimmed) FloatingBarDimAlpha else 1f,
        animationSpec = tween(durationMillis = 260, easing = FastOutSlowInEasing),
        label = "floatingBarAlpha",
    )

    Box(modifier = modifier.fillMaxWidth()) {
        Row(
            Modifier
                .fillMaxWidth()
                .windowInsetsPadding(WindowInsets.navigationBars)
                /*
                 * 触摸热区放在整个胶囊上，不是某个 tab 上 —— 用户点图标之间的空隙、
                 * 点内边距也算「操作底栏」。awaitFirstDown 不 consume，
                 * 下面各个 tab 的 clickable 照常收到点击。
                 */
                .pointerInput(autoHide) {
                    awaitEachGesture {
                        awaitFirstDown(requireUnconsumed = false)
                        if (currentAutoHide) {
                            dimmedAtDown = currentDimmed
                            wakeTick++
                        }
                    }
                }
                .graphicsLayer { alpha = barAlpha }
                .semantics { floatingBarAlpha = barAlpha }
                .testTag("floating_bar")
                .padding(start = 22.dp, end = 22.dp, top = 6.dp, bottom = FloatingBarBottomGap)
                .clip(G3CornerShape(22.dp))
                // 先模糊后方内容，再叠一层色调：底色用 surfaceVariant 和页面 background 拉开一档
                .hazeChild(state = hazeState, style = style)
                .background(scheme.surfaceVariant.copy(alpha = FloatingBarTintAlpha))
                .border(
                    width = 0.8.dp,
                    color = scheme.outlineVariant.copy(alpha = 0.5f),
                    shape = G3CornerShape(22.dp),
                )
                .padding(horizontal = 10.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            tabs.forEach { t ->
                val active = tab == t
                Column(
                    Modifier
                        .weight(1f)
                        .testTag("tab_${t.name}")
                        .clip(G3CornerShape(16.dp))
                        .clickable {
                            /*
                             * 半透明时第一次点击只唤醒，不切页：
                             * 此时 alpha 0.15 的图标几乎看不清，点下去大概率是「想找底栏」
                             * 而不是真要切页，直接切走容易误触。
                             */
                            if (!dimmedAtDown) onSelect(t)
                            dimmedAtDown = false
                        }
                        .padding(vertical = 5.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Icon(
                        t.icon,
                        contentDescription = t.label,
                        modifier = Modifier.size(21.dp),
                        tint = if (active) scheme.primary
                        else scheme.onSurfaceVariant.copy(alpha = 0.6f),
                    )
                    Spacer(Modifier.height(2.dp))
                    Text(
                        t.label,
                        fontSize = 10.sp,
                        fontWeight = if (active) FontWeight.Medium else FontWeight.Normal,
                        color = if (active) scheme.primary
                        else scheme.onSurfaceVariant.copy(alpha = 0.6f),
                    )
                    Spacer(Modifier.height(2.dp))
                    Box(
                        Modifier
                            // 始终占 3.5dp：切换 tab 时每个格子高度一致，
                            // 否则选中项比未选中项高一个圆点的高度，底栏会跳一下
                            .size(3.5.dp)
                            .clip(CircleShape)
                            .background(if (active) scheme.primary else Color.Transparent),
                    )
                }
            }
        }
    }
}

/**
 * 班级日程的两个居中弹窗（「有更新」「已被删除」）。
 *
 * 只在首页渲染：在日程页、设置页同步出结果时不弹 —— 用户正忙着别的事，
 * 这时候弹「学委更新了作业」只会让他关掉，等于白白消耗掉当天那一次弹窗额度。
 * 攒着，回首页再问。
 *
 * 「认领」那个弹窗已经删掉（2026-11-23）：认领要选清单、输密钥、读四行说明，
 * 装不进一个居中窗，改成独立 Activity（ClassPlanActivity.MODE_CLAIM）。
 *
 * 为什么保留这两个：它们是一次性的一问一答（知道了 / 稍后），
 * 不存在「下滑误触」的问题，没必要为两句话再开一个页面。
 */
@Composable
private fun ClassPlanSyncDialogs(vm: AppViewModel) {
    val ctx = LocalContext.current

    // 导入结果用系统 Toast：一句话、几秒、不挡操作
    LaunchedEffect(vm.classPlanToast) {
        val msg = vm.classPlanToast ?: return@LaunchedEffect
        Toast.makeText(ctx, msg, Toast.LENGTH_SHORT).show()
        vm.clearClassPlanToast()
    }

    when (val p = vm.classPlanHint?.prompt) {
        is ClassPlanPrompt.Update -> ClassPlanUpdateDialog(
            key = p.key,
            added = p.added,
            updated = p.updated,
            /*
             * 不预览标题：弹窗这一刻手上只有 index（摘要），正文还没拉。
             * 为了三行标题多打一次请求不划算 —— 点「查看并导入」之后，
             * 日程列表里能看到完整内容，比在弹窗里读强。
             */
            previewTitles = emptyList(),
            // 进独立 Activity 的详情页；已认领状态下会直接落在那一份清单上
            onOpen = {
                // 先把这条提示收掉：Activity 回来时不该再看见同一个弹窗。
                // 不算「忽略」—— 用户是去看了，不是点了稍后。
                vm.dismissClassPlanPrompt(countAsIgnore = false)
                runCatching {
                    ctx.startActivity(
                        com.gzucm.pocket.ui.schedule.ClassPlanActivity.intent(
                            ctx,
                            com.gzucm.pocket.ui.schedule.ClassPlanActivity.MODE_CLAIM,
                        ),
                    )
                }
            },
            onLater = { vm.dismissClassPlanPrompt() },
        )

        // 清单被删不是「打扰」，不记忽略次数 —— 否则三次之后连真正的更新都不弹了
        is ClassPlanPrompt.Deleted -> ClassPlanDeletedDialog(
            key = p.key,
            onDismiss = { vm.dismissClassPlanPrompt(countAsIgnore = false) },
        )

        // 「认领」现在只有首页那一行 + 独立 Activity 一条路，这里不再弹窗
        else -> Unit
    }
}
