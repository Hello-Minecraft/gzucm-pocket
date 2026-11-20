package com.gzucm.pocket.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.gzucm.pocket.core.TodaySections
import com.gzucm.pocket.ui.adaptive.AdaptiveSheet
import com.gzucm.pocket.ui.components.G3CornerShape
import com.gzucm.pocket.ui.components.micaPanel
import com.gzucm.pocket.ui.icons.AppIcons

/**
 * 「今日页显示」面板。
 *
 * 两个入口共用同一个正文（[TodaySectionsContent]）：
 *   · 设置 → 今日页显示（作为设置面板的子页面，和「导出课表数据」同一层）
 *   · 今日页长按任意卡片（作为独立面板弹出）
 *
 * 为什么不做成「只能从设置进」：调这几个开关的时候人就在首页，
 * 跳出去、再跳回来才能看到效果，改一次就不想改第二次了。
 *
 * 为什么不做拖拽排序：致辉 2026-11-20 选方案时也否掉了（编辑模式工作量大，
 * 而今日页现在没有排序需求）。真要排序时再加，别提前做。
 */
@Composable
fun TodaySectionsSheet(
    visible: Boolean,
    onDismiss: () -> Unit,
    sections: TodaySections,
    onChange: (TodaySections) -> Unit,
) {
    AdaptiveSheet(
        visible = visible,
        onDismiss = onDismiss,
        paneTitle = "今日页显示",
    ) {
        TodaySectionsContent(
            sections = sections,
            onChange = onChange,
            showBack = false,
        )
    }
}

@Composable
fun TodaySectionsContent(
    sections: TodaySections,
    onChange: (TodaySections) -> Unit,
    /** 设置面板里作为子页面时显示「返回设置」；独立面板里不需要 */
    showBack: Boolean = true,
    onBack: () -> Unit = {},
    /**
     * 这个构建里有没有天气服务（见 [com.gzucm.pocket.core.Weather.enabled]）。
     *
     * 没有 key 时这一行要显式置灰并说明原因 —— 否则用户打开开关、首页毫无变化，
     * 只会认为「开关坏了」。
     */
    weatherAvailable: Boolean = com.gzucm.pocket.core.Weather.enabled,
) {
    val scheme = MaterialTheme.colorScheme

    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp)
            .padding(bottom = 26.dp),
    ) {
        if (showBack) {
            Row(
                Modifier
                    .padding(bottom = 8.dp)
                    .clip(G3CornerShape(10.dp))
                    .clickable(onClick = onBack)
                    .padding(vertical = 6.dp, horizontal = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    AppIcons.ArrowBack,
                    contentDescription = null,
                    modifier = Modifier.size(17.dp),
                    tint = scheme.onSurfaceVariant,
                )
                Spacer(Modifier.width(5.dp))
                Text("返回设置", fontSize = 12.5.sp, color = scheme.onSurfaceVariant)
            }
        }

        Text(
            "今日页显示",
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Medium,
        )
        Spacer(Modifier.height(3.dp))
        Text(
            "关掉的块不会删除任何数据，只是今日页不显示。长按今日页任意卡片也能打开这里。",
            fontSize = 12.5.sp,
            lineHeight = 18.sp,
            color = scheme.onSurfaceVariant,
        )

        Spacer(Modifier.height(16.dp))

        Column(Modifier.micaPanel(14.dp)) {
            SectionSwitch(
                title = "宿舍电量",
                subtitle = "余额、日均用量、还能用几天",
                checked = sections.power,
                onChecked = { onChange(sections.copy(power = it)) },
            )
            SectionSwitch(
                title = "天气",
                subtitle = if (weatherAvailable) {
                    // 明确写出「关掉就不再请求」：这是用户能感知到的省额度行为
                    "现在多少度、未来三天预报；关掉后不再请求天气服务"
                } else {
                    "这个版本没有配置天气服务"
                },
                checked = sections.weather && weatherAvailable,
                enabled = weatherAvailable,
                onChecked = { onChange(sections.copy(weather = it)) },
            )
            SectionSwitch(
                title = "接下来",
                subtitle = "未来几天还有哪些日程和考试",
                checked = sections.upcoming,
                onChecked = { onChange(sections.copy(upcoming = it)) },
            )
            SectionSwitch(
                title = "本周概览",
                subtitle = "这周每天几节课，哪天最忙",
                checked = sections.weekOverview,
                onChecked = { onChange(sections.copy(weekOverview = it)) },
                last = true,
            )
        }

        Spacer(Modifier.height(14.dp))

        // ── 提前天数 ──────────────────────────────────────────────
        Column(
            Modifier
                .fillMaxWidth()
                .micaPanel(14.dp)
                .padding(horizontal = 16.dp, vertical = 13.dp),
        ) {
            /*
             * 标签在左、步进器在右 —— 和上面那组开关的「文字左、控件右」同一条竖线，
             * 第一版把步进器也摆在左下方，右侧空出一大片，看着像没排完。
             */
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text("「接下来」显示范围", fontSize = 13.5.sp, color = scheme.onSurface)
                    Spacer(Modifier.height(2.dp))
                    Text(
                        "往后 ${sections.upcomingDays} 天内的日程和考试（不含今天）",
                        fontSize = 11.5.sp,
                        color = scheme.onSurfaceVariant,
                    )
                }
                Spacer(Modifier.width(12.dp))
                StepButton(
                    text = "−",
                    enabled = sections.upcomingDays > TodaySections.MinDays,
                ) { onChange(sections.copy(upcomingDays = sections.upcomingDays - 1)) }
                Text(
                    "${sections.upcomingDays}",
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Medium,
                    color = scheme.primary,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                    modifier = Modifier.width(34.dp),
                )
                StepButton(
                    text = "+",
                    enabled = sections.upcomingDays < TodaySections.MaxDays,
                ) { onChange(sections.copy(upcomingDays = sections.upcomingDays + 1)) }
            }
        }

        Spacer(Modifier.height(14.dp))

        Text(
            "今日课程不能关 —— 首页就是给今天用的。只关天气、只留电费都可以，但至少得有一块能看的东西。",
            fontSize = 11.5.sp,
            lineHeight = 17.sp,
            color = scheme.onSurfaceVariant.copy(alpha = 0.85f),
        )
    }
}

/** 一行开关。整行可点（不只是那个小滑块），Switch 自己不吃点击 */
@Composable
private fun SectionSwitch(
    title: String,
    subtitle: String,
    checked: Boolean,
    onChecked: (Boolean) -> Unit,
    last: Boolean = false,
    /** 功能不可用时置灰：开关点不动，也不能被误认为「开了但没效果」 */
    enabled: Boolean = true,
) {
    val scheme = MaterialTheme.colorScheme
    Row(
        Modifier
            .fillMaxWidth()
            .alpha(if (enabled) 1f else 0.45f)
            .toggleable(
                value = checked,
                enabled = enabled,
                role = Role.Switch,
                onValueChange = onChecked,
            )
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, fontSize = 13.5.sp, color = scheme.onSurface)
            Spacer(Modifier.height(2.dp))
            Text(subtitle, fontSize = 11.5.sp, color = scheme.onSurfaceVariant)
        }
        Spacer(Modifier.width(12.dp))
        Switch(checked = checked, onCheckedChange = null, enabled = enabled)
    }
    if (!last) {
        // 一条极淡的分隔线，比给每行套一张卡安静得多
        Box(
            Modifier
                .fillMaxWidth()
                .padding(start = 16.dp)
                .height(1.dp)
                .background(scheme.outlineVariant.copy(alpha = 0.35f)),
        )
    }
}

@Composable
private fun StepButton(text: String, enabled: Boolean, onClick: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    Box(
        Modifier
            .size(32.dp)
            .clip(G3CornerShape(10.dp))
            .background(
                if (enabled) scheme.primary.copy(alpha = 0.14f)
                else scheme.surfaceVariant.copy(alpha = 0.4f)
            )
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text,
            fontSize = 16.sp,
            fontWeight = FontWeight.Medium,
            color = if (enabled) scheme.primary else scheme.onSurfaceVariant.copy(alpha = 0.4f),
        )
    }
}
