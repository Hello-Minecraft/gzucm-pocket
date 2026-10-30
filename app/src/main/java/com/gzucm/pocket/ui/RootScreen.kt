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
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
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
import androidx.compose.material.icons.rounded.WbSunny
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
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
@Composable
internal fun MainScaffold(
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

            else -> MainScaffold(tab = tab, onSelect = { tab = it }) {
                AnimatedContent(
                    targetState = tab,
                    transitionSpec = { fadeIn() togetherWith fadeOut() },
                    label = "tab",
                ) { t ->
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
                }
            }
        }
    }

    if (showDonate) {
        com.gzucm.pocket.ui.settings.DonateSheet(onDismiss = { showDonate = false })
    }

    detail?.let { s ->
        SessionSheet(session = s, onDismiss = { detail = null })
    }

    if (showEcardSync) {
        com.gzucm.pocket.ui.power.EcardSyncSheet(
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
    }

    if (showRebindGuide) {
        com.gzucm.pocket.ui.power.RebindGuideSheet(onDismiss = { showRebindGuide = false })
    }

    if (showSettings) {
        val lastSync = state.lastSync.let { ts ->
            if (ts <= 0) "—"
            else java.text.SimpleDateFormat("MM-dd HH:mm", java.util.Locale.CHINA)
                .format(java.util.Date(ts))
        }
        androidx.compose.material3.ModalBottomSheet(
            /**
             * 打开就全展开。
             *
             * 默认是「半展开」（约半屏高），设置页内容比半屏长得多，
             * 一进去只能看到上面几项，想找「重新登录」还得先把面板往上拖 —— 没人知道能拖。
             */
            sheetState = androidx.compose.material3.rememberModalBottomSheetState(
                skipPartiallyExpanded = true,
            ),
            onDismissRequest = {
                focusRelogin = false
                // 注意：这里必须把两个标志都清掉。
                // 之前只清一个，导致「在关于页下拉关掉面板」后 showSettings 还是 true，
                // Compose 仍认为面板存在 → 留下一个透明遮罩盖在首页上，什么都点不动。
                showAbout = false
                showSettings = false
            },
            containerColor = MaterialTheme.colorScheme.surface,
            shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp),
        ) {
            if (showAbout) {
                com.gzucm.pocket.ui.settings.AboutScreen(
                    onBack = { showAbout = false },
                    onChecked = { vm.refreshUpdateBadge() },
                )
                return@ModalBottomSheet
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
