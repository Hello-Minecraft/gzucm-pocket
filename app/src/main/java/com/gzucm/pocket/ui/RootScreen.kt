package com.gzucm.pocket.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import com.gzucm.pocket.ui.adaptive.AppNavigationRail
import com.gzucm.pocket.ui.adaptive.NavRailItem
import com.gzucm.pocket.ui.adaptive.rememberLayout
import com.gzucm.pocket.ui.adaptive.usesNavigationRail
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material.icons.rounded.CalendarMonth
import androidx.compose.material.icons.rounded.Insights
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.WbSunny
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.gzucm.pocket.core.CourseSession
import com.gzucm.pocket.ui.components.FloatingBarBottomGap
import com.gzucm.pocket.ui.components.FloatingBarMinInset
import com.gzucm.pocket.ui.components.FloatingBarOverscroll
import com.gzucm.pocket.ui.components.FloatingBarTotalInset
import com.gzucm.pocket.ui.components.LocalFloatingBarInset
import com.gzucm.pocket.ui.components.glassStyle
import com.gzucm.pocket.ui.components.rememberHazeState
import com.gzucm.pocket.ui.grades.GradesScreen
import com.gzucm.pocket.ui.login.LoginScreen
import com.gzucm.pocket.ui.power.PowerScreen
import com.gzucm.pocket.ui.schedule.ScheduleScreen
import com.gzucm.pocket.ui.schedule.SessionSheet
import com.gzucm.pocket.ui.today.TodayScreen
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.hazeChild
import dev.chrisbanes.haze.hazeSource

internal enum class Tab(val label: String, val icon: ImageVector) {
    Today("今日", Icons.Rounded.WbSunny),
    Schedule("课表", Icons.Rounded.CalendarMonth),
    Grades("成绩", Icons.Rounded.Insights),
    Power("电费", Icons.Rounded.Bolt),
}

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
                content = content,
            )
        } else {
            PhoneScaffold(tab = tab, onSelect = onSelect, content = content)
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
    content: @Composable () -> Unit,
) {
    /*
     * 底部 inset 置 0：平板的导航条在左侧，内容区可以一直铺到屏幕底边。
     * 之前给底部 Tab 留的那一截在这里是纯浪费 —— 课表会因此少显示半节课。
     */
    CompositionLocalProvider(LocalFloatingBarInset provides 0.dp) {
        Row(Modifier.fillMaxSize()) {
            AppNavigationRail(
                items = Tab.entries.map { NavRailItem(it.label, it.icon) },
                selected = Tab.entries.indexOf(tab),
                onSelect = { onSelect(Tab.entries[it]) },
                bottomInset = 0.dp,
                bottomActions = {
                    /*
                     * 同步 / 设置从首页标题栏搬到这里。
                     *
                     * 首页的标题栏在宽屏下就只剩日期和红点位置，干净很多；
                     * 侧栏底部是拇指最容易够到的地方（平板握持在两侧）。
                     */
                    com.gzucm.pocket.ui.adaptive.RailActionButton(
                        icon = Icons.Rounded.Refresh,
                        label = "同步",
                        spinning = syncing,
                        onClick = onSyncClick,
                    )
                    com.gzucm.pocket.ui.adaptive.RailActionButton(
                        icon = Icons.Rounded.Settings,
                        label = "设置",
                        badge = hasUpdate,
                        onClick = onSettingsClick,
                    )
                },
            )
            Box(Modifier.weight(1f).fillMaxHeight()) {
                content()
            }
        }
    }
}

/** 手机 / 窄窗口：原实现，内容铺满 + 悬浮底栏。 */
@Composable
private fun PhoneScaffold(
    tab: Tab,
    onSelect: (Tab) -> Unit,
    content: @Composable () -> Unit,
) {
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
    val askNotify = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.RequestPermission()
    ) { granted -> notifyOk = granted }
    val prefs = remember { com.gzucm.pocket.core.SessionStore(ctx0.applicationContext) }
    // 默认启动页由用户在设置里选
    var tab by remember {
        mutableStateOf(Tab.entries.getOrElse(prefs.startTab) { Tab.Today })
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
    }

    var detail by remember { mutableStateOf<CourseSession?>(null) }
    var showSettings by remember { mutableStateOf(false) }
    var showEcardSync by remember { mutableStateOf(false) }
    var showRebindGuide by remember { mutableStateOf(false) }
    var showDonate by remember { mutableStateOf(false) }
    var showAbout by remember { mutableStateOf(false) }
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
            )

            else -> MainScaffold(
                tab = tab,
                onSelect = { tab = it },
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
                        Tab.Today -> TodayScreen(
                            semester = state.semester,
                            currentWeek = vm.currentWeek,
                            sessions = state.sessions,
                            power = state.power,
                            powerDaily = state.powerDaily,
                            powerCheckedAt = prefs.powerCheckedAt,
                            onCourseClick = { detail = it },
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
                        )

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
                        )

                        Tab.Grades -> {
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
                                needLogin = vm.loginExpired,
                                onOpenLogin = {
                                    showSettings = true
                                    focusRelogin = true
                                },
                            )
                        }

                        Tab.Power -> {
                            LaunchedEffect(Unit) { vm.loadPower() }
                            PowerScreen(
                                power = state.power,
                                daily = state.powerDaily,
                                loading = state.powerLoading,
                                error = state.powerError,
                                onSync = { showEcardSync = true },
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

    com.gzucm.pocket.ui.power.EcardSyncSheet(
        visible = showEcardSync,
        onDone = { info, daily, err ->
                showEcardSync = false
                if (info != null) {
                    vm.applyEcardSync(info, daily)
                } else {
                    // 记下尝试时间：失败后 10 分钟内不再自动弹，免得反复打断
                    prefs.lastPowerAttemptAt = System.currentTimeMillis()
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
    var lastToastSeq by remember { mutableIntStateOf(0) }
    LaunchedEffect(state.syncResultSeq) {
        if (state.syncResultSeq > lastToastSeq) {
            lastToastSeq = state.syncResultSeq
            state.syncResultText?.let { text ->
                android.widget.Toast.makeText(ctxToast, text, android.widget.Toast.LENGTH_SHORT).show()
            }
        }
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
                showSettings = false
            },
            paneWidth = 460.dp,
            paneTitle = when {
                showAbout -> "关于软件"
                showDonate -> "打赏作者"
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
                enabled = showAbout || showDonate,
            ) {
                showAbout = false
                showDonate = false
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
            com.gzucm.pocket.ui.settings.SettingsScreen(
                onSync = { vm.sync(); showSettings = false },
                onLogout = { vm.logout(); showSettings = false },
                onClose = { showSettings = false },
                onRelogin = {
                    focusRelogin = false
                    vm.logout()
                    vm.reopenLogin()
                    showSettings = false
                },
                lastSyncText = lastSync,
                semesterText = state.semester?.name ?: "—",
                focusRelogin = focusRelogin,
                hasUpdate = vm.hasUpdate,
                startTabIndex = prefs.startTab,
                onStartTabChange = {
                    prefs.startTab = it
                    // 选完立刻生效，不用等下次启动
                    tab = Tab.entries.getOrElse(it) { Tab.Today }
                },
                onDonate = { showDonate = true },
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
            )
        }
    }
}

/**
 * 悬浮底栏。
 *
 * - 不贴屏幕底边，左右留边、大圆角，浮在内容之上；
 * - 用 haze 做 backdrop blur，后方滚动内容会以磨砂形式透出来；
 * - 胶囊用 `navigationBarsPadding` 避开手势小白条，下方的留白让它自然浮起。
 */
@Composable
private fun FloatingBar(
    tab: Tab,
    onSelect: (Tab) -> Unit,
    modifier: Modifier = Modifier,
    hazeState: HazeState,
) {
    val scheme = MaterialTheme.colorScheme
    val style = glassStyle()

    Box(modifier = modifier.fillMaxWidth()) {
        Row(
            Modifier
                .fillMaxWidth()
                .windowInsetsPadding(WindowInsets.navigationBars)
                .padding(start = 22.dp, end = 22.dp, top = 6.dp, bottom = FloatingBarBottomGap)
                .clip(RoundedCornerShape(22.dp))
                // 先模糊后方内容，再叠一层极淡的色调保证文字对比度
                .hazeChild(state = hazeState, style = style)
                .background(scheme.surface.copy(alpha = 0.28f))
                .border(
                    width = 0.8.dp,
                    color = scheme.outlineVariant.copy(alpha = 0.5f),
                    shape = RoundedCornerShape(22.dp),
                )
                .padding(vertical = 7.dp),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Tab.entries.forEach { t ->
                val active = tab == t
                Column(
                    Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(14.dp))
                        .clickable { onSelect(t) }
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
                            .size(if (active) 3.5.dp else 0.dp)
                            .clip(CircleShape)
                            .background(if (active) scheme.primary else Color.Transparent),
                    )
                }
            }
        }
    }
}
