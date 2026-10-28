package com.gzucm.pocket.ui.schedule

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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
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
    hasBg: Boolean,
    alpha: Float,
    onPick: () -> Unit,
    onAlphaChange: (Float) -> Unit,
    onClear: () -> Unit,
    onDismiss: () -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = scheme.surface,
        shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp),
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 22.dp)
                .padding(bottom = 28.dp),
        ) {
            Text(
                "课表背景",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Medium,
            )
            Spacer(Modifier.height(4.dp))
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
