package com.gzucm.pocket.ui.settings

import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.rememberScrollState
import com.gzucm.pocket.ui.components.G3CornerShape
import com.gzucm.pocket.ui.components.micaPanel
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInParent
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.gzucm.pocket.ui.icons.AppIcons

@Composable
fun SettingsScreen(
    onSync: () -> Unit,
    onLogout: () -> Unit,
    onClose: () -> Unit,
    onRelogin: () -> Unit = {},
    lastSyncText: String,
    semesterText: String,
    startTabIndex: Int = 0,
    onStartTabChange: (Int) -> Unit = {},
    onDonate: () -> Unit = {},
    onOpenAbout: () -> Unit = {},
    classReminder: Boolean = true,
    onClassReminderChange: (Boolean) -> Unit = {},
    notifyGranted: Boolean = true,
    onRequestNotify: () -> Unit = {},
    leadFree: Int = 20,
    leadBreak: Int = 10,
    onLeadChange: (free: Int?, breakMin: Int?) -> Unit = { _, _ -> },
    /** 增强提醒方案（setAlarmClock） */
    reminderEnhanced: Boolean = false,
    onReminderEnhancedChange: (Boolean) -> Unit = {},
    /** 精确闹钟是否已授权（Android 12+；低版本恒为 true） */
    exactAlarmGranted: Boolean = true,
    onRequestExactAlarm: () -> Unit = {},
    /** 从「登录已过期」点进来时置 true：自动滚到「重新登录」并抖动提示 */
    focusRelogin: Boolean = false,
    /** 有可用更新：「关于软件」那行右侧会显示提示 */
    hasUpdate: Boolean = false,
    /** 打开「导出课表」子页面（复制分享串 / 导出 JSON 文件）。实际生成在 RootScreen */
    onOpenExport: () -> Unit = {},
    /** 打开「问题反馈」子页面（大字版本号 + 问卷入口）。 */
    onOpenFeedback: () -> Unit = {},
    /** 打开「今日页显示」子页面（电费 / 天气 / 接下来 / 本周概览 的开关 + 提前天数） */
    onOpenTodaySections: () -> Unit = {},
    /** 打开「添加到桌面小组件」教程页（各机型步骤 + 尺寸预览） */
    onOpenWidgetGuide: () -> Unit = {},
    /** 当前天气地点名，显示在「天气位置」右侧 */
    weatherPlaceLabel: String = com.gzucm.pocket.core.WeatherPlace.default.label,
    /** 这个构建有没有天气服务（没配 key 时隐藏「天气位置」，否则是一个点进去也没用的入口） */
    weatherAvailable: Boolean = com.gzucm.pocket.core.Weather.enabled,
    /** 打开「天气位置」子页面（预设地点 + 使用当前位置） */
    onOpenWeatherPlace: () -> Unit = {},
    /** 早晨天气提醒：每天的温差 / 高温 / 低温 / 有没有雨 */
    weatherMorning: Boolean = true,
    onWeatherMorningChange: (Boolean) -> Unit = {},
    weatherMorningTime: String = "08:00",
    onWeatherMorningTimeChange: (String) -> Unit = {},
    /** 明日带伞提醒：明天有雨雪才发，平时静默 */
    weatherEvening: Boolean = true,
    onWeatherEveningChange: (Boolean) -> Unit = {},
    weatherEveningTime: String = "22:00",
    onWeatherEveningTimeChange: (String) -> Unit = {},
    /**
     * 「立即预览」：马上发一条跟真实触发一模一样的通知，不用等到点。
     * 预览不写去重标记、不动闹钟，到点的提醒照常发。
     */
    onPreviewMorning: () -> Unit = {},
    onPreviewEvening: () -> Unit = {},
    /** 正在发预览的是哪一条："morning" / "evening"；null 表示空闲 */
    previewBusy: String? = null,
    /** 打开「天气提醒」二级页（两档开关 + 时间滚轮 + 立即预览） */
    onOpenWeatherAlert: () -> Unit = {},
) {
    val scheme = MaterialTheme.colorScheme
    val scrollState = rememberScrollState()

    // 「重新登录」那一行的位置：滚过去之后还要在它周围跳一下
    var reloginY by remember { mutableIntStateOf(0) }
    var pulsing by remember { mutableStateOf(focusRelogin) }
    val reloginIntoView = remember { BringIntoViewRequester() }
    val scope = rememberCoroutineScope()
    var reloginScrolled by remember(focusRelogin) { mutableStateOf(false) }

    LaunchedEffect(focusRelogin) {
        if (!focusRelogin) return@LaunchedEffect
        pulsing = true
        // 兜底再滚一次，**不做条件判断**。
        //
        // 布局回调那次通常滚不到底：面板展开动画还没结束，maxValue 只是当时的中间值。
        // 等 700ms 面板停稳，maxValue 才是最终值，这时候滚才是真到底。
        // 滚两次没有副作用，就是先滚一点再滚到底。
        kotlinx.coroutines.delay(700)
        runCatching { scrollState.scrollTo(scrollState.maxValue) }
        pulsing = false
        pulsing = true
        kotlinx.coroutines.delay(4_200)
        pulsing = false
    }

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(scrollState)
            .padding(horizontal = 18.dp),
    ) {
        Spacer(Modifier.height(12.dp))
        Text(
            "设置",
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Medium,
        )
        Spacer(Modifier.height(18.dp))

        Column(
            Modifier
                .fillMaxWidth()
                .micaPanel(14.dp),
        ) {
            InfoRow("当前学期", semesterText.ifBlank { "—" })
            InfoRow("上次同步", lastSyncText.ifBlank { "—" })
        }

        Spacer(Modifier.height(16.dp))

        // ── 页面：默认启动页 + 今日页显示 ──────────────────────────
        //
        // 2026-11-20 合并成一张卡（致辉提的）。原来「今日页显示」是单独一张
        // 只有一行入口的卡，两张卡说的都是「页面长什么样」，摆一起反而碎。
        // 拆分线之后接入口行，读起来就是「这块管页面」。
        Column(
            Modifier
                .fillMaxWidth()
                .micaPanel(14.dp),
        ) {
            Column(Modifier.padding(14.dp)) {
                Text("默认启动页", fontSize = 13.sp, color = scheme.onSurfaceVariant)
                Spacer(Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    StartTabs.forEachIndexed { i, name ->
                        val on = i == startTabIndex
                        Box(
                            Modifier
                                .weight(1f)
                                .clip(G3CornerShape(10.dp))
                                .background(
                                    if (on) scheme.primary.copy(alpha = 0.16f)
                                    else scheme.surfaceVariant.copy(alpha = 0.5f)
                                )
                                .clickable { onStartTabChange(i) }
                                .padding(vertical = 9.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                name,
                                fontSize = 12.5.sp,
                                fontWeight = if (on) FontWeight.Medium else FontWeight.Normal,
                                color = if (on) scheme.primary else scheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }

            Box(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 14.dp)
                    .height(0.7.dp)
                    .background(scheme.onSurfaceVariant.copy(alpha = 0.14f)),
            )

            // 「首页显示」这个名字一直有歧义（首页 = 今日？课表？），
            // 改成跟面板标题一致的「今日页显示」
            ActionRow(
                "今日页显示",
                AppIcons.Tune,
                onOpenTodaySections,
                hPadding = 14.dp,
            )

            /*
             * 天气位置。
             *
             * 和「今日页显示」同一张卡：两块都在回答「首页那张天气卡长什么样」。
             * 点开是子页面（不是就地弹菜单）—— 里面还有「使用当前位置」这个
             * 会等几秒的定位动作，塞在一个下拉里容易点错、也不好放失败提示。
             *
             * 没配天气服务时整行不出现：点进去选完地点、首页啥也没有，比不显示更糟。
             */
            if (weatherAvailable) {
                Box(
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 14.dp)
                        .height(0.7.dp)
                        .background(scheme.onSurfaceVariant.copy(alpha = 0.14f)),
                )
                WeatherPlaceRow(
                    value = weatherPlaceLabel,
                    onClick = onOpenWeatherPlace,
                )
            }
        }

        Spacer(Modifier.height(16.dp))

        // ── 主题色 ────────────────────────────────────────────────
        ThemeCard()

        Spacer(Modifier.height(16.dp))

        // ── 上课提醒 ──────────────────────────────────────────────
        Column(
            Modifier
                .fillMaxWidth()
                // 含三档开关 + 两条步进器，是设置页最高的一张 → 降一档
                .micaPanel(14.dp),
        ) {
            Row(
                Modifier
                    .fillMaxWidth()
                    // toggleable 单点处理；Switch 不接点击，避免同一次点击翻两次导致卡顿
                    .toggleable(
                        value = classReminder,
                        role = androidx.compose.ui.semantics.Role.Switch,
                        onValueChange = onClassReminderChange,
                    )
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text("上课提醒", fontSize = 13.5.sp)
                    Text(
                        "课前 20 分钟提醒；上一节还没下课时改为课间提前 10 分钟",
                        fontSize = 10.5.sp,
                        lineHeight = 15.sp,
                        color = scheme.onSurfaceVariant.copy(alpha = 0.8f),
                    )
                    // 开着却发不出通知 = 白开，直接摆一个可点的提示
                    if (classReminder && !notifyGranted) {
                        Spacer(Modifier.height(5.dp))
                        Text(
                            "通知权限未开启，提醒不会弹出 · 点这里开启",
                            fontSize = 10.5.sp,
                            color = scheme.error,
                            modifier = Modifier.clickable(onClick = onRequestNotify),
                        )
                    }
                }
                Spacer(Modifier.width(10.dp))
                androidx.compose.material3.Switch(
                    checked = classReminder,
                    onCheckedChange = null,
                )
            }

            // 两档提前量：没课时 / 课间。只在开启时显示，关掉就没意义了
            if (classReminder) {
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(1.dp)
                        .background(scheme.onSurfaceVariant.copy(alpha = 0.06f)),
                )
                LeadRow(
                    title = "当前没课时",
                    hint = "早八、下午第一节这种",
                    value = leadFree,
                    range = 5..40,
                    onChange = { onLeadChange(it, null) },
                )
                LeadRow(
                    title = "课间提醒",
                    hint = "上一节还没下课，留给课间",
                    value = leadBreak,
                    range = 5..30,
                    onChange = { onLeadChange(null, it) },
                )

                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(1.dp)
                        .background(scheme.onSurfaceVariant.copy(alpha = 0.06f)),
                )
                /*
                 * 增强提醒（setAlarmClock）。
                 *
                 * 做成可选项而不是默认：它会在状态栏常驻闹钟图标、锁屏显示「下一个闹钟」。
                 * 换来的是系统闹钟同级的优先级 —— ColorOS / HyperOS 的省电合并基本推迟不了。
                 * 「上午的课下午才响」的同学打开它。
                 */
                Row(
                    Modifier
                        .fillMaxWidth()
                        .toggleable(
                            value = reminderEnhanced,
                            role = androidx.compose.ui.semantics.Role.Switch,
                            onValueChange = onReminderEnhancedChange,
                        )
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text("增强提醒方案", fontSize = 13.5.sp)
                        Text(
                            "提醒总是迟到时打开。按系统闹钟的级别排，省电策略推迟不了；" +
                                "代价是状态栏会出现闹钟图标",
                            fontSize = 10.5.sp,
                            lineHeight = 15.sp,
                            color = scheme.onSurfaceVariant.copy(alpha = 0.8f),
                        )
                    }
                    Spacer(Modifier.width(10.dp))
                    androidx.compose.material3.Switch(
                        checked = reminderEnhanced,
                        onCheckedChange = null,
                    )
                }

                // 没有精确闹钟授权时，两种方案都只能退回「非精确」—— 必须明说
                if (!exactAlarmGranted) {
                    Text(
                        "「闹钟和提醒」权限未开启，提醒可能晚很久 · 点这里开启",
                        fontSize = 10.5.sp,
                        lineHeight = 15.sp,
                        color = scheme.error,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable(onClick = onRequestExactAlarm)
                            .padding(start = 16.dp, end = 16.dp, bottom = 12.dp),
                    )
                }
            }
        }

        /*
         * 天气提醒（2026-11-26 收成一行入口）。
         *
         * 原来这里直接铺一整张卡：两张开关 + 两个时间行 + 两个预览按钮 + 权限提示，
         * 叠起来约 300dp，「立即同步 / 退出登录」都被挤出首屏，用户嫌太占地方。
         * 现在只留一行「天气提醒」+ 副标题（当前两档时间和开关状态），
         * 具体配置点进去在 [WeatherAlertScreen] 里改。
         *
         * 仍然紧挨着「上课提醒」：两张卡都是「每天到点给你一条通知」，
         * 用户找提醒设置时只在同一个区域扫一遍就够了。没配天气服务时整行不出现。
         */
        if (weatherAvailable) {
            Spacer(Modifier.height(16.dp))
            val weatherAlertSubtitle = when {
                weatherMorning && weatherEvening ->
                    "每天 $weatherMorningTime 温差 · $weatherEveningTime 带伞"
                weatherMorning -> "每天 $weatherMorningTime 温差提醒"
                weatherEvening -> "每天 $weatherEveningTime 带伞提醒"
                else -> "已关闭"
            }
            Column(
                Modifier
                    .fillMaxWidth()
                    .micaPanel(14.dp),
            ) {
                ActionRow(
                    "天气提醒",
                    AppIcons.WbSunny,
                    onOpenWeatherAlert,
                    subtitle = weatherAlertSubtitle,
                    hPadding = 14.dp,
                )
            }
        }

        Spacer(Modifier.height(16.dp))

        Column(
            Modifier
                .fillMaxWidth()
                .micaPanel(14.dp),
        ) {
            ActionRow("立即同步", AppIcons.Refresh, onSync)
            Box(
                Modifier
                    .fillMaxWidth()
                    .bringIntoViewRequester(reloginIntoView)
                    .onGloballyPositioned { coords ->
                        reloginY = coords.positionInParent().y.toInt()
                        /**
                         * 直接滚到底，**不按坐标算**。
                         *
                         * 试过 positionInParent → 算出来的 y 不可靠（父节点不是滚动容器，
                         * 拿到的是相对某个内部容器的值），减完被 coerce 回 0，表现就是「没反应」。
                         * 「重新登录」本来就在设置页底部（下面只剩退出登录/打赏/关于），
                         * 滚到底它一定在视野里 —— 用最笨但一定对的办法。
                         */
                        if (focusRelogin && !reloginScrolled && scrollState.maxValue > 0) {
                            reloginScrolled = true
                            scope.launch {
                                runCatching { scrollState.scrollTo(scrollState.maxValue) }
                            }
                        }
                    }
                    /*
                     * ⚠️ 这里不能再加 padding(horizontal = 4.dp)（2026-11-18 修）。
                     *
                     * 原来为了让抖动高亮块「留白好看」，在 Box 上加了一圈 4dp 内边距，
                     * 结果把里面的 ActionRow 一起往里挤 —— 图标、文字、右边箭头
                     * 比上面「立即同步」和下面「退出登录」各缩进 4dp，一眼就能看出错位。
                     * 高亮块宁可齐着卡片宽度铺满，也不能牺牲行的对齐。
                     */
                    .clip(G3CornerShape(10.dp))
                    .background(
                        if (pulsing) {
                            val t = rememberInfiniteTransition(label = "relogin")
                            val a by t.animateFloat(
                                initialValue = 0.10f,
                                targetValue = 0.28f,
                                animationSpec = infiniteRepeatable(
                                    animation = tween(650),
                                    repeatMode = RepeatMode.Reverse,
                                ),
                                label = "pulse",
                            )
                            scheme.primary.copy(alpha = a)
                        } else Color.Transparent
                    ),
            ) {
                ActionRow("重新登录", AppIcons.Login, onRelogin)
            }
            ActionRow("退出登录", AppIcons.Logout, onLogout, danger = true)
            /*
             * 导出课表（致辉 2026-11-18 要网页导入，2026-11-18 晚补文件通道）。
             *
             * 位置按致辉 01:35 的要求挪到「退出登录」下面，去掉原来的红点 + 「文本 / 文件」角标 ——
             * 那是个低频入口，摆在显眼位置反而像广告。
             *
             * 点进来是子页面，不是直接复制 —— 两种形态各有用途：
             *   · 分享串约 1750 字符，能塞进微信一条消息，适合发同学；
             *   · JSON 文件带完整逐周内容，适合自己拿去喂网页。
             * 「太长发不出去就发文件」这句提示也在子页面里。
             */
            ActionRow("导出课表数据", AppIcons.GridView, onOpenExport)
        }

        /*
         * 添加到桌面小组件（教程）。
         *
         * 位置：紧跟「导出课表数据」那张操作卡之后、问题反馈之前。
         * 这两个入口是同一类 ——「把课表带到 App 外面去」（一个导文件、一个放桌面），
         * 摆在一起用户找的时候不用上下翻。
         *
         * 为什么需要一整页：六个部件都是标准安卓小部件，大部分 ROM 把它们埋在
         * 「全部应用 → 安卓小组件」最底部，用户按「添加卡片」的直觉根本翻不到。
         */
        Spacer(Modifier.height(16.dp))

        Column(
            Modifier
                .fillMaxWidth()
                .micaPanel(14.dp),
        ) {
            ActionRow(
                "添加到桌面小组件",
                AppIcons.GridView,
                onOpenWidgetGuide,
                subtitle = "各机型操作步骤 + 尺寸预览",
            )
        }

        /*
         * 云母化（2026-11-19）：这张卡原来**紧贴**上面的操作卡，中间没有间距 ——
         * 两张同色 surface 贴在一起，看起来像一张卡的下半部分。
         * 现在每张卡都带阴影，紧贴会让两圈阴影在缝里叠成一条黑线，
         * 所以补上跟别处一致的 16dp。
         */
        Spacer(Modifier.height(16.dp))

        // ── 问题反馈 ──────────────────────────────────────────────
        /*
         * 放在打赏上面（致辉 2026-11-19 定）。
         *
         * 为什么挨着打赏：这两张卡是他的「软性入口」——一个给钱、一个给意见，
         * 都靠用户主动点，不像同步/退出那样天天要用。摆在一起，
         * 用户想「说点什么」的时候能一起看到。
         */
        Column(
            Modifier
                .fillMaxWidth()
                .micaPanel(14.dp),
        ) {
            ActionRow("问题反馈", AppIcons.ChatBubble, onOpenFeedback)
        }

        Spacer(Modifier.height(16.dp))

        // ── 打赏 ──────────────────────────────────────────────────
        Column(
            Modifier
                .fillMaxWidth()
                .micaPanel(14.dp),
        ) {
            ActionRow("觉得软件做的不错？", AppIcons.Favorite, onDonate)
        }

        Spacer(Modifier.height(16.dp))

        // 关于软件（版本信息与更新日志都在里面）
        Column(
            Modifier
                .fillMaxWidth()
                .micaPanel(14.dp),
        ) {
            ActionRow(
                "关于软件", AppIcons.Info, onOpenAbout,
                badge = if (hasUpdate) "检测到新版本" else null,
            )
        }

        Spacer(Modifier.height(30.dp))
    }
}

@Composable
private fun InfoRow(label: String, value: String) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.weight(1f))
        Text(value, fontSize = 13.sp)
    }
}

/**
 * 提前量步进器。用 ±5 分钟而不是输入框：
 * 这里改的是十几分钟的体感值，滑动输入反而容易输错。
 */
@Composable
private fun LeadRow(
    title: String,
    hint: String,
    value: Int,
    range: IntRange,
    onChange: (Int) -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    Row(
        Modifier
            .fillMaxWidth()
            .padding(start = 16.dp, end = 10.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, fontSize = 12.5.sp)
            Text(hint, fontSize = 10.sp, color = scheme.onSurfaceVariant.copy(alpha = 0.7f))
        }
        IconButton(
            onClick = { onChange((value - 5).coerceAtLeast(range.first)) },
            enabled = value > range.first,
        ) {
            Text("−", fontSize = 16.sp, color = if (value > range.first) scheme.primary else scheme.onSurfaceVariant.copy(alpha = 0.3f))
        }
        Text(
            "$value 分钟",
            fontSize = 12.sp,
            fontWeight = FontWeight.Medium,
            modifier = Modifier.width(52.dp),
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
        )
        IconButton(
            onClick = { onChange((value + 5).coerceAtMost(range.last)) },
            enabled = value < range.last,
        ) {
            Text("+", fontSize = 16.sp, color = if (value < range.last) scheme.primary else scheme.onSurfaceVariant.copy(alpha = 0.3f))
        }
    }
}

@Composable
private fun ActionRow(
    label: String,
    icon: ImageVector,
    onClick: () -> Unit,
    danger: Boolean = false,
    /** 右侧的小提示（如「检测到新版本」），红点 + 主色字 */
    badge: String? = null,
    /** 第二行说明。只有需要解释「点进去能干什么」的入口才填 */
    subtitle: String? = null,
    /**
     * 水平内边距。默认 16dp。
     *
     * 放进「卡片自己带 14dp 内边距」的分组里时传 14dp —— 否则图标会比
     * 上面那行标题多缩进 2dp，单看卡不出来，两行摆一起就歪了。
     */
    hPadding: Dp = 16.dp,
) {
    val scheme = MaterialTheme.colorScheme
    val tint = if (danger) scheme.error else scheme.onSurface
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = hPadding, vertical = 15.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null, modifier = Modifier.size(19.dp), tint = tint)
        Spacer(Modifier.width(12.dp))
        if (subtitle == null) {
            Text(label, fontSize = 13.5.sp, color = tint, modifier = Modifier.weight(1f))
        } else {
            Column(Modifier.weight(1f)) {
                Text(label, fontSize = 13.5.sp, color = tint)
                Spacer(Modifier.height(2.dp))
                Text(
                    subtitle,
                    fontSize = 10.5.sp,
                    lineHeight = 15.sp,
                    color = scheme.onSurfaceVariant.copy(alpha = 0.8f),
                )
            }
        }
        if (badge != null) {
            // 小红点跟首页设置按钮上那颗呼应，一眼能串起来「就是那个更新」
            Box(
                Modifier
                    .size(5.dp)
                    .clip(CircleShape)
                    .background(scheme.error),
            )
            Spacer(Modifier.width(5.dp))
            Text(badge, fontSize = 10.5.sp, color = scheme.primary)
            Spacer(Modifier.width(6.dp))
        }
        Icon(
            AppIcons.ChevronRight,
            contentDescription = null,
            modifier = Modifier.size(17.dp),
            tint = scheme.onSurfaceVariant.copy(alpha = 0.5f),
        )
    }
}

/**
 * 「天气位置」那一行。
 *
 * 比普通 [ActionRow] 多两样东西：
 *   · 右侧的当前地点名（否则用户点进去才知道现在选的是哪个）；
 *   · 下面那句隐私说明 —— **不能省**。这一行背后是定位权限，
 *     用户有权在点进去之前就知道坐标去哪了。
 */
@Composable
private fun WeatherPlaceRow(
    value: String,
    onClick: () -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 13.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(AppIcons.Place, contentDescription = null, modifier = Modifier.size(19.dp))
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("天气位置", fontSize = 13.5.sp)
                Spacer(Modifier.width(10.dp))
                Text(
                    value,
                    fontSize = 11.5.sp,
                    color = scheme.primary,
                    maxLines = 1,
                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                    textAlign = androidx.compose.ui.text.style.TextAlign.End,
                    modifier = Modifier.weight(1f),
                )
            }
            Spacer(Modifier.height(2.dp))
            Text(
                "仅用于获取你所在位置的天气。坐标只保存在这台手机上，不会上传，我们服务器也不保存。",
                fontSize = 10.5.sp,
                lineHeight = 15.sp,
                color = scheme.onSurfaceVariant.copy(alpha = 0.8f),
            )
        }
        Spacer(Modifier.width(6.dp))
        Icon(
            AppIcons.ChevronRight,
            contentDescription = null,
            modifier = Modifier.size(17.dp),
            tint = scheme.onSurfaceVariant.copy(alpha = 0.5f),
        )
    }
}

/** 与底部 Tab 顺序一致 */
private val StartTabs = listOf("今日", "课表", "成绩", "电费")/**
 * 主题色色卡。
 *
 * 两行各 6 格：`默认 + 9 个预设 + 自定义` = 12 格，正好排满。
 * 点一下就立刻换色 —— 色卡本身也在被换色的界面里，所见即所得。
 */
@Composable
private fun ThemeCard() {
    val scheme = MaterialTheme.colorScheme
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val store = remember { com.gzucm.pocket.core.SessionStore(ctx.applicationContext) }
    var showPicker by remember { mutableStateOf(false) }

    val current = com.gzucm.pocket.ui.theme.ThemeState.seed
    val presetSeeds = com.gzucm.pocket.ui.theme.ThemeColor.presets.map { it.seed }
    // 既不是默认也不在预设里 → 说明是自定义色
    val isCustom = current != com.gzucm.pocket.ui.theme.ThemeColor.DEFAULT_SEED &&
        current !in presetSeeds

    fun apply(seed: Long) {
        com.gzucm.pocket.ui.theme.ThemeState.set(seed) { store.themeSeed = it }
    }

    // 0 = 默认，-1 = 自定义，其余是预设
    val slots = buildList<Long> {
        add(com.gzucm.pocket.ui.theme.ThemeColor.DEFAULT_SEED)
        addAll(presetSeeds)
        add(-1L)
    }

    Column(
        Modifier
            .fillMaxWidth()
            .micaPanel(14.dp)
            .padding(14.dp),
    ) {
        /*
         * ── 深色模式 ────────────────────────────────────────────
         *
         * 和主题色放同一张卡：都是「外观」类设置，用户找深浅色时
         * 一般会先想到主题色那一块，拆成两张卡反而要多扫一遍。
         */
        val darkMode = com.gzucm.pocket.ui.theme.ThemeState.darkMode
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("深色模式", fontSize = 13.sp, color = scheme.onSurfaceVariant)
            Spacer(Modifier.weight(1f))
            Text(darkMode.label, fontSize = 11.sp, color = scheme.primary)
        }
        Spacer(Modifier.height(9.dp))
        DarkModeRow(darkMode) { picked ->
            com.gzucm.pocket.ui.theme.ThemeState.setDarkMode(picked) { store.darkMode = it }
        }

        Spacer(Modifier.height(16.dp))
        Box(
            Modifier
                .fillMaxWidth()
                .height(0.7.dp)
                .background(scheme.onSurfaceVariant.copy(alpha = 0.14f)),
        )
        Spacer(Modifier.height(14.dp))

        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("主题色", fontSize = 13.sp, color = scheme.onSurfaceVariant)
            Spacer(Modifier.weight(1f))
            Text(
                when {
                    isCustom -> "自定义"
                    current == com.gzucm.pocket.ui.theme.ThemeColor.DEFAULT_SEED -> "默认"
                    else -> com.gzucm.pocket.ui.theme.ThemeColor.presets
                        .firstOrNull { it.seed == current }?.label ?: "默认"
                },
                fontSize = 11.sp,
                color = scheme.primary,
            )
        }
        Spacer(Modifier.height(12.dp))

        // 两行，每行 6 个槽位（不足的留空，保证两行对齐）
        slots.chunked(6).forEach { rowSeeds ->
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                rowSeeds.forEach { seed ->
                    if (seed == -1L) {
                        CustomSlot(
                            selected = isCustom,
                            color = if (isCustom) swatch(current) else scheme.primary,
                            onClick = { showPicker = true },
                        )
                    } else {
                        ThemeSlot(
                            selected = current == seed,
                            color = swatch(seed),
                            label = labelOf(seed),
                            onClick = { apply(seed) },
                        )
                    }
                }
                // 补齐第二行的空位
                repeat(6 - rowSeeds.size) { Spacer(Modifier.size(38.dp)) }
            }
            Spacer(Modifier.height(8.dp))
        }
    }

    // 常驻 + visible：包 if 会让进入/退场动画都不播
    com.gzucm.pocket.ui.settings.ColorPickerSheet(
        initial = if (isCustom) current else 0L,
        visible = showPicker,
        onPick = { picked ->
            apply(picked)
            showPicker = false
        },
        onDismiss = { showPicker = false },
    )
}

/**
 * 深色模式三选一：跟随系统 / 深色 / 浅色。
 *
 * 用等分胶囊而不是三行单选：这是「三选一」的开关，不是三个独立配置项 ——
 * 一排看全、点一下就切，比列表快。视觉跟登录页选学段一致。
 *
 * 底色用 onSurfaceVariant 的低透明，而不是 surfaceVariant：
 * 后者在深色主题下几乎和卡片同色，整条会「消失」。
 */
@Composable
private fun DarkModeRow(
    current: com.gzucm.pocket.ui.theme.ThemeState.DarkMode,
    onPick: (com.gzucm.pocket.ui.theme.ThemeState.DarkMode) -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    Row(
        Modifier
            .fillMaxWidth()
            .clip(G3CornerShape(10.dp))
            .background(scheme.onSurfaceVariant.copy(alpha = 0.09f))
            .padding(3.dp),
        horizontalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        com.gzucm.pocket.ui.theme.ThemeState.DarkMode.entries.forEach { mode ->
            val on = mode == current
            Box(
                Modifier
                    .weight(1f)
                    .clip(G3CornerShape(8.dp))
                    .background(
                        if (on) scheme.primary.copy(alpha = 0.16f)
                        else androidx.compose.ui.graphics.Color.Transparent
                    )
                    .clickable { onPick(mode) }
                    .padding(vertical = 8.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    mode.label,
                    fontSize = 12.5.sp,
                    fontWeight = if (on) FontWeight.Medium else FontWeight.Normal,
                    color = if (on) scheme.primary else scheme.onSurfaceVariant,
                )
            }
        }
    }
}

private fun labelOf(seed: Long): String =
    if (seed == com.gzucm.pocket.ui.theme.ThemeColor.DEFAULT_SEED) "默认"
    else com.gzucm.pocket.ui.theme.ThemeColor.presets.firstOrNull { it.seed == seed }?.label ?: ""

/** 色卡上显示的颜色：跟随深浅主题调整，否则深色下莫兰迪色看着发黑 */
@Composable
private fun swatch(seed: Long): androidx.compose.ui.graphics.Color {
    /*
     * 必须走 ThemeState，不能用 isSystemInDarkTheme()。
     *
     * 用户手动选了「浅色」而系统是深色时，色卡若按系统算，
     * 会取到深色版色值 —— 铺在浅色卡片上明显发黑，像是色卡坏了。
     */
    val dark = com.gzucm.pocket.ui.theme.ThemeState.isDark(
        androidx.compose.foundation.isSystemInDarkTheme()
    )
    return com.gzucm.pocket.ui.theme.ThemeColor.swatchColor(seed, dark)
}

/**
 * 一个色点。选中的状态是**外圈描边**（不是打勾）——
 * 色卡上本来就有颜色，再盖个勾反而看不清色。
 */
@Composable
private fun ThemeSlot(
    selected: Boolean,
    color: androidx.compose.ui.graphics.Color,
    label: String,
    onClick: () -> Unit,
) {
    Box(
        Modifier
            .size(38.dp)
            .clip(CircleShape)
            // 颜色没有文字，读屏只能靠这个标签
            .clickable(onClickLabel = label, onClick = onClick)
            .then(
                if (selected) Modifier.border(2.dp, MaterialTheme.colorScheme.primary, CircleShape)
                else Modifier
            ),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier
                .size(26.dp)
                .clip(CircleShape)
                .background(color)
                .border(
                    1.dp,
                    MaterialTheme.colorScheme.outline.copy(alpha = 0.35f),
                    CircleShape,
                )
        )
    }
}

/** 自定义槽位：没选过时是彩虹圆，选过就显示当前自定义色 */
@Composable
private fun CustomSlot(
    selected: Boolean,
    color: androidx.compose.ui.graphics.Color,
    onClick: () -> Unit,
) {
    Box(
        Modifier
            .size(38.dp)
            .clip(CircleShape)
            .clickable(onClick = onClick)
            .then(
                if (selected) Modifier.border(2.dp, MaterialTheme.colorScheme.primary, CircleShape)
                else Modifier
            ),
        contentAlignment = Alignment.Center,
    ) {
        com.gzucm.pocket.ui.settings.CustomSwatchDot(
            hasColor = selected,
            color = color,
            size = 26.dp,
        )
    }
}
