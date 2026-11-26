package com.gzucm.pocket.ui.settings

import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.gzucm.pocket.ui.components.G3CornerShape
import com.gzucm.pocket.ui.components.micaPanel
import com.gzucm.pocket.ui.icons.AppIcons

/**
 * 「天气提醒」二级页（2026-11-26）。
 *
 * ## 为什么单独一页
 *
 * 原来它是一整张卡直接铺在设置页「上课提醒」下面：两张开关 + 两个时间行 +
 * 两个预览按钮 + 权限提示，叠起来约 300dp，一屏塞不下，把下面「立即同步 / 退出登录」
 * 都挤到了折叠线外。收成一行入口后，设置页只留「天气提醒」+ 一句副标题，
 * 具体怎么配都在这一页里。
 *
 * ## 结构
 *
 * 和「添加到桌面小组件」教程页同一套：顶部「← 设置」返回、大标题、一句说明，
 * 正文是原来那张卡，整页可滚。
 */
@Composable
fun WeatherAlertScreen(
    morningOn: Boolean,
    morningTime: String,
    onMorningChange: (Boolean) -> Unit,
    onMorningTimeChange: (String) -> Unit,
    eveningOn: Boolean,
    eveningTime: String,
    onEveningChange: (Boolean) -> Unit,
    onEveningTimeChange: (String) -> Unit,
    notifyGranted: Boolean,
    onRequestNotify: () -> Unit,
    onPreviewMorning: () -> Unit,
    onPreviewEvening: () -> Unit,
    previewBusy: String?,
    onBack: () -> Unit,
) {
    val scheme = MaterialTheme.colorScheme

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 18.dp),
    ) {
        Spacer(Modifier.height(10.dp))

        // 返回（和关于页 / 小组件教程页同一套写法，返回键也是回到设置）
        Row(
            Modifier
                .clip(G3CornerShape(10.dp))
                .clickable(onClick = onBack)
                .padding(vertical = 6.dp, horizontal = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                AppIcons.ArrowBack,
                contentDescription = "返回",
                modifier = Modifier.size(18.dp),
                tint = scheme.onSurfaceVariant,
            )
            Spacer(Modifier.width(6.dp))
            Text("设置", fontSize = 13.sp, color = scheme.onSurfaceVariant)
        }

        Spacer(Modifier.height(10.dp))
        Text(
            "天气提醒",
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Medium,
        )
        Spacer(Modifier.height(6.dp))
        Text(
            "每天到点给你一条通知，两条可以分别开关、各自改时间。",
            fontSize = 12.sp,
            lineHeight = 18.sp,
            color = scheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(18.dp))

        WeatherAlertCard(
            morningOn = morningOn,
            morningTime = morningTime,
            onMorningChange = onMorningChange,
            onMorningTimeChange = onMorningTimeChange,
            eveningOn = eveningOn,
            eveningTime = eveningTime,
            onEveningChange = onEveningChange,
            onEveningTimeChange = onEveningTimeChange,
            notifyGranted = notifyGranted,
            onRequestNotify = onRequestNotify,
            onPreviewMorning = onPreviewMorning,
            onPreviewEvening = onPreviewEvening,
            previewBusy = previewBusy,
        )

        Spacer(Modifier.height(30.dp))
    }
}

/**
 * 天气提醒卡片：两行独立开关，各自可改时间。
 *
 * 时间选择做成**行下内联展开**（复用 AgendaSheet 里的滚轮），不弹 Dialog：
 * 这个设置页本身就在一个 AdaptiveSheet 里，再叠一层 Dialog 按返回键会先关掉一层，
 * 层级一乱就出现「退回桌面」。
 */
@Composable
private fun WeatherAlertCard(
    morningOn: Boolean,
    morningTime: String,
    onMorningChange: (Boolean) -> Unit,
    onMorningTimeChange: (String) -> Unit,
    eveningOn: Boolean,
    eveningTime: String,
    onEveningChange: (Boolean) -> Unit,
    onEveningTimeChange: (String) -> Unit,
    notifyGranted: Boolean,
    onRequestNotify: () -> Unit,
    onPreviewMorning: () -> Unit,
    onPreviewEvening: () -> Unit,
    previewBusy: String?,
) {
    val scheme = MaterialTheme.colorScheme
    // null / "morning" / "evening"。同时只展开一个：两个 132dp 的滚轮上下叠着没法用
    var open by remember { mutableStateOf<String?>(null) }

    // 关掉开关时收起滚轮，否则留着展开的面板会让人以为时间还在生效
    LaunchedEffect(morningOn, eveningOn) {
        if (!morningOn && open == "morning") open = null
        if (!eveningOn && open == "evening") open = null
    }

    Column(
        Modifier
            .fillMaxWidth()
            .micaPanel(14.dp),
    ) {
        WeatherAlertSwitchRow(
            title = "天气提醒",
            hint = "每天 $morningTime 告诉你今天的温差和天气",
            checked = morningOn,
            onChange = onMorningChange,
        )
        if (morningOn) {
            TimeRow(
                value = morningTime,
                expanded = open == "morning",
                onClick = { open = if (open == "morning") null else "morning" },
            )
            if (open == "morning") {
                HourMinuteWheel(morningTime, onMorningTimeChange)
            }
        }
        PreviewButton(
            busy = previewBusy == "morning",
            enabled = previewBusy == null,
            onClick = onPreviewMorning,
        )

        Box(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .height(0.7.dp)
                .background(scheme.onSurfaceVariant.copy(alpha = 0.12f)),
        )

        WeatherAlertSwitchRow(
            title = "明日带伞",
            hint = "每天 $eveningTime 提醒你明天要不要带伞",
            checked = eveningOn,
            onChange = onEveningChange,
        )
        if (eveningOn) {
            TimeRow(
                value = eveningTime,
                expanded = open == "evening",
                onClick = { open = if (open == "evening") null else "evening" },
            )
            if (open == "evening") {
                HourMinuteWheel(eveningTime, onEveningTimeChange)
            }
        }
        PreviewButton(
            busy = previewBusy == "evening",
            enabled = previewBusy == null,
            onClick = onPreviewEvening,
        )

        // 两张提醒都是靠通知送达的：没权限就明说，给一个能点的出口
        if ((morningOn || eveningOn) && !notifyGranted) {
            Text(
                "通知权限未开启，提醒不会弹出 · 点这里开启",
                fontSize = 10.5.sp,
                color = scheme.error,
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(onClick = onRequestNotify)
                    .padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 12.dp),
            )
        }
    }
}

/** 一行开关，样式照「上课提醒」那行，只是标题/副标题不同 */
@Composable
private fun WeatherAlertSwitchRow(
    title: String,
    hint: String,
    checked: Boolean,
    onChange: (Boolean) -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    Row(
        Modifier
            .fillMaxWidth()
            // toggleable 单点处理；Switch 不接点击，避免一次点击翻两次
            .toggleable(
                value = checked,
                role = androidx.compose.ui.semantics.Role.Switch,
                onValueChange = onChange,
            )
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, fontSize = 13.5.sp)
            Text(
                hint,
                fontSize = 10.5.sp,
                lineHeight = 15.sp,
                color = scheme.onSurfaceVariant.copy(alpha = 0.8f),
            )
        }
        Spacer(Modifier.width(10.dp))
        androidx.compose.material3.Switch(checked = checked, onCheckedChange = null)
    }
}

/**
 * 「立即预览」：马上发一条通知，不用等到点。
 *
 * 做成行尾的小号文字按钮，不是大按钮 —— 它是个验证手段，不是每天要点的操作；
 * 摆在卡片里也不该抢走开关和时间的注意力。
 *
 * @param busy 这一条正在发（显示「发送中…」并禁点，防止连点打两次天气接口）
 * @param enabled 另一条在发时置 false：两条同时发只会撞上天气接口的频控
 */
@Composable
private fun PreviewButton(busy: Boolean, enabled: Boolean, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(start = 16.dp, end = 8.dp, bottom = 4.dp),
        horizontalArrangement = Arrangement.End,
    ) {
        TextButton(
            onClick = onClick,
            enabled = enabled && !busy,
            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 2.dp),
        ) {
            Text(if (busy) "发送中…" else "立即预览", fontSize = 12.5.sp)
        }
    }
}

/** 「提醒时间  08:00 ▼」，点一下展开/收起滚轮 */
@Composable
private fun TimeRow(value: String, expanded: Boolean, onClick: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    Row(
        Modifier
            .fillMaxWidth()
            .clip(G3CornerShape(10.dp))
            .clickable(onClick = onClick)
            .padding(start = 16.dp, end = 16.dp, top = 9.dp, bottom = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("提醒时间", fontSize = 12.sp, color = scheme.onSurfaceVariant)
        Spacer(Modifier.weight(1f))
        Text(value, fontSize = 13.sp, fontWeight = FontWeight.Medium, color = scheme.primary)
        Spacer(Modifier.width(6.dp))
        Text(
            "▼",
            fontSize = 8.sp,
            color = scheme.onSurfaceVariant.copy(alpha = 0.7f),
            modifier = Modifier.graphicsLayer {
                rotationZ = if (expanded) 180f else 0f
            },
        )
    }
}

/**
 * 小时 + 分钟两列滚轮。
 *
 * 复用 `ui/schedule/AgendaSheet.kt` 里那份 [WheelColumn] —— 它已经处理了
 * 「固定高度 + 上下半屏 contentPadding」这两个硬约束，重写一份只会踩同样的坑。
 */
@Composable
private fun HourMinuteWheel(value: String, onChange: (String) -> Unit) {
    val scheme = MaterialTheme.colorScheme
    val minutes = minutesOf(value)
    Row(
        Modifier
            .fillMaxWidth()
            .padding(start = 16.dp, end = 16.dp, bottom = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        com.gzucm.pocket.ui.schedule.WheelColumn(
            count = 24,
            initialIndex = minutes / 60,
            label = { "%02d".format(it) },
            onSelect = { h ->
                val text = "%02d:%02d".format(h, minutes % 60)
                // 展开时会先回掉一次当前值，值没变就别重排闹钟
                if (text != value) onChange(text)
            },
            modifier = Modifier.weight(1f),
        )
        Text(
            ":",
            fontSize = 16.sp,
            color = scheme.onSurfaceVariant,
            modifier = Modifier.align(Alignment.CenterVertically),
        )
        com.gzucm.pocket.ui.schedule.WheelColumn(
            count = 60,
            initialIndex = minutes % 60,
            label = { "%02d".format(it) },
            onSelect = { m ->
                val text = "%02d:%02d".format(minutes / 60, m)
                if (text != value) onChange(text)
            },
            modifier = Modifier.weight(1f),
        )
    }
}

/** "08:00" → 一天里的第几分钟；解析不了按 8 点算（[WeatherAlertStore] 已保证存的是合法值） */
private fun minutesOf(hhmm: String): Int {
    val t = runCatching { java.time.LocalTime.parse(hhmm.trim()) }.getOrNull()
        ?: java.time.LocalTime.of(8, 0)
    return t.hour * 60 + t.minute
}
