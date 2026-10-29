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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
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
) {
    val scheme = MaterialTheme.colorScheme
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
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
        }

        Spacer(Modifier.height(16.dp))

        Column(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(14.dp))
                .background(scheme.surface),
        ) {
            ActionRow("立即同步", Icons.Rounded.Refresh, onSync)
            ActionRow("重新登录", Icons.Rounded.Login, onRelogin)
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
            ActionRow("关于软件", Icons.Rounded.Info, onOpenAbout)
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

@Composable
private fun ActionRow(
    label: String,
    icon: ImageVector,
    onClick: () -> Unit,
    danger: Boolean = false,
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
