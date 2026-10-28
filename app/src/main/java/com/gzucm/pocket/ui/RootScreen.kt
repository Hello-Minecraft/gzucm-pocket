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
    val prefs = remember { com.gzucm.pocket.core.SessionStore(ctx0.applicationContext) }
    // 默认启动页由用户在设置里选
    var tab by remember {
        mutableStateOf(Tab.entries.getOrElse(prefs.startTab) { Tab.Today })
    }
    var detail by remember { mutableStateOf<CourseSession?>(null) }
    var showSettings by remember { mutableStateOf(false) }
    var showEcardSync by remember { mutableStateOf(false) }
    var showDonate by remember { mutableStateOf(false) }
    var showAbout by remember { mutableStateOf(false) }

    /**
     * 电费同步策略（2026-10-28 改）：
     *
     * - **每天首次打开 App**：静默获取一次，**不弹窗**，用户无感。
     * - 之后当天不再自动同步，避免每次打开都跑一遍。
     * - 用户想手动更新：电费页点「更新」，才弹窗（那种情况用户本来就等着）。
     *
     * 静默同步用 1dp 的隐藏 WebView —— 数据只能从 WebView 里取，
     * 但没必要把界面亮给用户看。
     */
    val todayStr = remember {
        java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US)
            .format(java.util.Date())
    }
    var silentSyncKey by remember { mutableStateOf(0) }
    var silentDone by remember { mutableStateOf(false) }
    val needSilent = state.phase == Phase.Ready &&
        !silentDone &&
        com.gzucm.pocket.core.PowerAlert.shouldSilentSync(prefs.lastPowerSyncDate, todayStr)

    // 隐藏的同步载体：不可见、不接收点击，拿完数据就自己结束
    if (needSilent) {
        androidx.compose.foundation.layout.Box(Modifier.size(1.dp)) {
            com.gzucm.pocket.ui.power.PowerSilentSync(
                active = true,
                onDone = { info, daily ->
                    vm.applyEcardSync(info, daily)
                    prefs.lastPowerSyncDate = todayStr
                    silentDone = true
                },
                onFail = {
                    // 静默失败不打扰用户，但记下来避免同一天反复重试
                    prefs.lastPowerSyncDate = todayStr
                    silentDone = true
                },
            )
        }
    }

    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        when (state.phase) {
            Phase.NeedLogin -> LoginScreen(onLoggedIn = vm::onLoggedIn)

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
                            onCourseClick = { detail = it },
                            onOpenPower = { tab = Tab.Power },
                            onOpenSettings = { showSettings = true },
                            onRefresh = { vm.sync() },
                            syncing = state.syncing,
                            notice = state.message,
                        )

                        Tab.Schedule -> ScheduleScreen(
                            semester = state.semester,
                            sessions = state.sessions,
                            currentWeek = vm.currentWeek,
                            viewingWeek = vm.viewingWeek,
                            onWeekChange = vm::goToWeek,
                            onCourseClick = { detail = it },
                        )

                        Tab.Grades -> {
                            LaunchedEffect(Unit) { vm.loadGrades() }
                            GradesScreen(
                                grades = state.grades,
                                levels = state.levels,
                                fitness = state.fitness,
                                loading = state.gradesLoading,
                                onRefresh = { vm.loadGrades(force = true) },
                            )
                        }

                        Tab.Power -> {
                            LaunchedEffect(Unit) { vm.loadPower() }
                            PowerScreen(
                                power = state.power,
                                daily = state.powerDaily,
                                loading = state.powerLoading,
                                error = state.powerError,
                                onRefresh = { vm.loadPower(force = true) },
                                onSync = { showEcardSync = true },
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
                } else if (err != null) {
                    vm.showPowerError(err)
                }
            },
            onDismiss = { showEcardSync = false },
        )
    }

    if (showSettings) {
        val lastSync = state.lastSync.let { ts ->
            if (ts <= 0) "—"
            else java.text.SimpleDateFormat("MM-dd HH:mm", java.util.Locale.CHINA)
                .format(java.util.Date(ts))
        }
        androidx.compose.material3.ModalBottomSheet(
            onDismissRequest = {
                if (showAbout) showAbout = false else showSettings = false
            },
            containerColor = MaterialTheme.colorScheme.surface,
            shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp),
        ) {
            if (showAbout) {
                com.gzucm.pocket.ui.settings.AboutScreen(onBack = { showAbout = false })
                return@ModalBottomSheet
            }
            com.gzucm.pocket.ui.settings.SettingsScreen(
                onSync = { vm.sync(); showSettings = false },
                onLogout = { vm.logout(); showSettings = false },
                onClose = { showSettings = false },
                onRelogin = { vm.logout(); vm.reopenLogin(); showSettings = false },
                lastSyncText = lastSync,
                semesterText = state.semester?.name ?: "—",
                startTabIndex = prefs.startTab,
                onStartTabChange = {
                    prefs.startTab = it
                    // 选完立刻生效，不用等下次启动
                    tab = Tab.entries.getOrElse(it) { Tab.Today }
                },
                onDonate = { showDonate = true },
                onOpenAbout = { showAbout = true },
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
