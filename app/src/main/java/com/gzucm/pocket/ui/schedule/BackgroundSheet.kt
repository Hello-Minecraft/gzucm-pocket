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
import androidx.compose.ui.graphics.graphicsLayer
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
    /** 课程格子底色的不透明度（磨砂层的浓淡） */
    cellOpacity: Float = 0.40f,
    onCellOpacityChange: (Float) -> Unit = {},
    /** 课程格子是否做磨砂 */
    cellFrost: Boolean = true,
    onCellFrostChange: (Boolean) -> Unit = {},
    /** 整张背景是否糊掉；开着时格子磨砂失效 */
    bgBlur: Boolean = false,
    onBgBlurChange: (Boolean) -> Unit = {},
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
                if (hasBg) {
                    "壁纸、模糊、格子底色都在这里调；改完立刻生效"
                } else {
                    "选一张图当课表背景。选完还能单独调模糊和格子底色"
                },
                fontSize = 12.sp,
                color = scheme.onSurfaceVariant,
            )

            Spacer(Modifier.height(18.dp))

            /*
             * 加一个小节标题。
             *
             * 原来两个按钮直接顶在说明文字下面，看不出这组是干什么的 ——
             * 尤其「换一张」和「恢复默认」并排时，像是两个平级的杂项操作。
             * 起个标题把这一组框住，逻辑才闭合。
             */
            Text(
                "自定义课表背景",
                fontSize = 13.5.sp,
                fontWeight = FontWeight.Medium,
            )
            Spacer(Modifier.height(8.dp))

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
                    "调的是壁纸本身有多浓：越高越像壁纸，越低越接近纯色底",
                    fontSize = 10.5.sp,
                    lineHeight = 14.sp,
                    color = scheme.onSurfaceVariant.copy(alpha = 0.7f),
                )
            }

            /*
             * 格子底色浓度。**不进 if (hasBg)**（2026-11-03 致辉指出的坑）。
             *
             * 一开始把它放在 hasBg 里面，理由是「没有壁纸就没有磨砂，拖了没用」——
             * 这个判断是错的：格子的课程色浓度和壁纸无关，没设壁纸时一样生效。
             * 后果更糟：点过「恢复默认」之后就再也调不到这个值了。
             *
             * 它和上面「背景不透明度」是两个不相干的旋钮：
             *   · 背景不透明度：壁纸本身多明显（连格子外面一起变）
             *   · 格子不透明度：格子上的课程色多实（只影响格子）
             */
            Spacer(Modifier.height(22.dp))
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text("格子不透明度", fontSize = 13.sp, color = scheme.onSurfaceVariant)
                Text(
                    "${(cellOpacity * 100).toInt()}%",
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium,
                    color = scheme.primary,
                )
            }
            Slider(
                value = cellOpacity,
                onValueChange = onCellOpacityChange,
                valueRange = 0.10f..1.00f,
                modifier = Modifier.fillMaxWidth(),
            )
            Text(
                // 磨砂关掉（或根本没有壁纸）时格子底下没有玻璃，别再说「盖在磨砂底上」
                if (hasBg && cellFrost && !bgBlur) {
                    "盖在磨砂底上的课程色有多实：100% 接近实心，10% 几乎只剩磨砂"
                } else {
                    "格子的课程色有多实：100% 接近实心，10% 几乎只剩背景"
                },
                fontSize = 10.5.sp,
                lineHeight = 14.sp,
                color = scheme.onSurfaceVariant.copy(alpha = 0.7f),
            )

            /*
             * 两个模糊开关**仍然只在有壁纸时出现** ——
             * 它们描述的正是「壁纸怎么糊」，没有壁纸无从谈起。
             */
            if (hasBg) {
                Spacer(Modifier.height(18.dp))
                ToggleRow(
                    title = "课程格子背景模糊",
                    desc = "每格单独磨砂，能看清格子背后是自己那片壁纸",
                    checked = cellFrost && !bgBlur,
                    enabled = !bgBlur,
                    onCheckedChange = onCellFrostChange,
                )
                Spacer(Modifier.height(10.dp))
                ToggleRow(
                    title = "全屏背景模糊",
                    desc = "整张壁纸都糊掉，格子只留课程色。开启后格子模糊不生效",
                    checked = bgBlur,
                    enabled = true,
                    onCheckedChange = onBgBlurChange,
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

/**
 * 一行开关：左边标题+说明，右边 Switch。
 *
 * 置灰时**只降透明度、仍然吃掉点击**，而不是彻底禁掉 ——
 * 这样点上去不会有「点了没反应以为是卡了」的错觉，
 * 配合说明文字能看懂为什么不让改。
 */
@Composable
private fun ToggleRow(
    title: String,
    desc: String,
    checked: Boolean,
    enabled: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    val dim = if (enabled) 1f else 0.42f
    Row(
        Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(
            Modifier
                .weight(1f)
                .clickable(enabled = enabled) { onCheckedChange(!checked) },
        ) {
            Text(
                title,
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium,
                color = scheme.onSurface.copy(alpha = dim),
            )
            Spacer(Modifier.height(3.dp))
            Text(
                desc,
                fontSize = 10.5.sp,
                lineHeight = 14.sp,
                color = scheme.onSurfaceVariant.copy(alpha = 0.7f * dim),
            )
        }
        Spacer(Modifier.width(12.dp))
        Box(Modifier.graphicsLayer { alpha = dim }) {
            Switch(
                checked = checked,
                onCheckedChange = onCheckedChange,
                enabled = enabled,
            )
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
