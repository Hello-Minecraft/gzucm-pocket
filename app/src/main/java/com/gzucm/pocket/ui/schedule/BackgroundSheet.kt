package com.gzucm.pocket.ui.schedule

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Switch
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import com.gzucm.pocket.ui.adaptive.LocalIsWidePane
import com.gzucm.pocket.ui.adaptive.AdaptiveSheet
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * 课表背景设置面板。入口在课表页右上角，不放在总设置里。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BackgroundSheet(
    /** 显隐由调用方控制，好让退场动画播得完 */
    visible: Boolean = true,
    hasBg: Boolean,
    alpha: Float,
    /** 虚线网格开关 */
    gridLines: Boolean = false,
    onGridLinesChange: (Boolean) -> Unit = {},
    onPick: () -> Unit,
    onAlphaChange: (Float) -> Unit,
    onClear: () -> Unit,
    onDismiss: () -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    AdaptiveSheet(
        visible = visible,
        onDismiss = onDismiss,
        paneTitle = "课表外观",
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 22.dp)
                .padding(bottom = 28.dp),
        ) {
            /*
             * 标题在手机上还是留在正文里（底部 Sheet 没有顶栏），
             * 平板上则由侧栏的顶栏负责显示 —— 否则会出现两遍标题。
             */
            if (!LocalIsWidePane.current) {
                Text(
                    "课表外观",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Medium,
                )
                Spacer(Modifier.height(4.dp))
            }
            Text(
                if (hasBg) "调透明度让背景更淡或更清楚" else "选一张图当课表背景",
                fontSize = 12.sp,
                color = scheme.onSurfaceVariant,
            )

            Spacer(Modifier.height(18.dp))

            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Btn(
                    text = if (hasBg) "换一张" else "选择图片",
                    primary = true,
                    modifier = Modifier.weight(1f),
                    onClick = onPick,
                )
                if (hasBg) {
                    Btn(
                        text = "恢复默认",
                        primary = false,
                        modifier = Modifier.weight(1f),
                        onClick = onClear,
                    )
                }
            }

            if (hasBg) {
                Spacer(Modifier.height(22.dp))
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text("背景不透明度", fontSize = 13.sp, color = scheme.onSurfaceVariant)
                    Text(
                        "${(alpha * 100).toInt()}%",
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Medium,
                        color = scheme.primary,
                    )
                }
                Slider(
                    value = alpha,
                    onValueChange = onAlphaChange,
                    valueRange = 0.15f..1f,
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(
                    "调低更淡，字更清楚；调高更清楚看到图片",
                    fontSize = 10.5.sp,
                    color = scheme.onSurfaceVariant.copy(alpha = 0.7f),
                )
            }

            /*
             * 虚线网格开关。
             *
             * 平板端课程会并排分列，一块紧挨着一块 —— 不看列线很难判断
             * 「这块属于星期几」。打开后在课表上画灰色虚线，
             * 横线分「第几节」、竖线分「星期几」。
             *
             * 放在背景设置里而不是单独入口：它和背景一样属于「课表外观」，
             * 不需要在标题栏再挤一个按钮。
             */
            Spacer(Modifier.height(20.dp))
            HorizontalDivider(color = scheme.onSurfaceVariant.copy(alpha = 0.12f))
            Spacer(Modifier.height(16.dp))

            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        "显示网格线",
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Medium,
                    )
                    Spacer(Modifier.height(3.dp))
                    Text(
                        "用灰色虚线划分每一节课和每一天，方便看清课程属于哪一格",
                        fontSize = 10.5.sp,
                        lineHeight = 14.sp,
                        color = scheme.onSurfaceVariant.copy(alpha = 0.7f),
                    )
                }
                Spacer(Modifier.width(12.dp))
                Switch(
                    checked = gridLines,
                    onCheckedChange = onGridLinesChange,
                )
            }
        }
    }
}

@Composable
private fun Btn(
    text: String,
    primary: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    Box(
        modifier
            .clip(RoundedCornerShape(12.dp))
            .background(
                if (primary) scheme.primary.copy(alpha = 0.15f)
                else scheme.surfaceVariant.copy(alpha = 0.6f)
            )
            .clickable(onClick = onClick)
            .padding(vertical = 12.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text,
            fontSize = 13.sp,
            fontWeight = FontWeight.Medium,
            color = if (primary) scheme.primary else scheme.onSurfaceVariant,
        )
    }
}
