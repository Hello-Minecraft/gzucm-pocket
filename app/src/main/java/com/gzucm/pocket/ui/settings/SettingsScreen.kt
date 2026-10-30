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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Login
import androidx.compose.material.icons.rounded.Logout
import androidx.compose.material.icons.rounded.Refresh
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

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
    /** 从「登录已过期」点进来时置 true：自动滚到「重新登录」并抖动提示 */
    focusRelogin: Boolean = false,
    /** 有可用更新：「关于软件」那行右侧会显示提示 */
    hasUpdate: Boolean = false,
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
                .clip(RoundedCornerShape(14.dp))
                .background(scheme.surface),
        ) {
            InfoRow("当前学期", semesterText.ifBlank { "—" })
            InfoRow("上次同步", lastSyncText.ifBlank { "—" })
        }

        Spacer(Modifier.height(16.dp))

        // ── 默认启动页 ────────────────────────────────────────────
        Column(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(14.dp))
                .background(scheme.surface)
                .padding(14.dp),
        ) {
            Text("默认启动页", fontSize = 13.sp, color = scheme.onSurfaceVariant)
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                StartTabs.forEachIndexed { i, name ->
                    val on = i == startTabIndex
                    Box(
                        Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(10.dp))
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

        Spacer(Modifier.height(16.dp))

        // ── 主题色 ────────────────────────────────────────────────
        ThemeCard()

        Spacer(Modifier.height(16.dp))

        // ── 上课提醒 ──────────────────────────────────────────────
        Column(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(14.dp))
                .background(scheme.surface),
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
            }
        }

        Spacer(Modifier.height(16.dp))

        Column(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(14.dp))
                .background(scheme.surface),
        ) {
            ActionRow("立即同步", Icons.Rounded.Refresh, onSync)
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
                    .padding(horizontal = 4.dp)
                    .clip(RoundedCornerShape(10.dp))
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
                    )
                    .padding(horizontal = 0.dp),
            ) {
                ActionRow("重新登录", Icons.Rounded.Login, onRelogin)
            }
            ActionRow("退出登录", Icons.Rounded.Logout, onLogout, danger = true)
        }

        // ── 打赏 ──────────────────────────────────────────────────
        Column(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(14.dp))
                .background(scheme.surface),
        ) {
            ActionRow("觉得软件做的不错？", Icons.Rounded.Favorite, onDonate)
        }

        Spacer(Modifier.height(16.dp))

        // 关于软件（版本信息与更新日志都在里面）
        Column(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(14.dp))
                .background(scheme.surface),
        ) {
            ActionRow(
                "关于软件", Icons.Rounded.Info, onOpenAbout,
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
) {
    val scheme = MaterialTheme.colorScheme
    val tint = if (danger) scheme.error else scheme.onSurface
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 15.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null, modifier = Modifier.size(19.dp), tint = tint)
        Spacer(Modifier.width(12.dp))
        Text(label, fontSize = 13.5.sp, color = tint, modifier = Modifier.weight(1f))
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
            Icons.Rounded.ChevronRight,
            contentDescription = null,
            modifier = Modifier.size(17.dp),
            tint = scheme.onSurfaceVariant.copy(alpha = 0.5f),
        )
    }
}

/** 与底部 Tab 顺序一致 */
private val StartTabs = listOf("今日", "课表", "成绩", "电费")

/**
 * 主题色色卡。
 *
 * 排两行：第一行「默认 + 5 个预设」，第二行「4 个预设 + 自定义」。
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
            .clip(RoundedCornerShape(14.dp))
            .background(scheme.surface)
            .padding(14.dp),
    ) {
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

    if (showPicker) {
        com.gzucm.pocket.ui.settings.ColorPickerSheet(
            initial = if (isCustom) current else 0L,
            onPick = { picked ->
                apply(picked)
                showPicker = false
            },
            onDismiss = { showPicker = false },
        )
    }
}

private fun labelOf(seed: Long): String =
    if (seed == com.gzucm.pocket.ui.theme.ThemeColor.DEFAULT_SEED) "默认"
    else com.gzucm.pocket.ui.theme.ThemeColor.presets.firstOrNull { it.seed == seed }?.label ?: ""

/** 色卡上显示的颜色：跟随深浅主题调整，否则深色下莫兰迪色看着发黑 */
@Composable
private fun swatch(seed: Long): androidx.compose.ui.graphics.Color {
    val dark = androidx.compose.foundation.isSystemInDarkTheme()
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
