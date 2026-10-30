package com.gzucm.pocket.ui.settings

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * 自定义主题色。
 *
 * 就两块：上面一条色相条选「什么颜色」，下面一个方块选「多亮多浓」。
 * 不用色轮是因为方块在小屏上更好点，取色也更准。
 *
 * 内部用 HSV（方块的自然坐标），最后转成 ARGB 存 Long。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ColorPickerSheet(
    initial: Long,
    onPick: (Long) -> Unit,
    onDismiss: () -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    val start = remember(initial) {
        if (initial == 0L) Triple(160f, 0.65f, 0.85f) else Color(initial).toHsv()
    }
    var hue by remember { mutableFloatStateOf(start.first) }
    var sat by remember { mutableFloatStateOf(start.second) }
    var v by remember { mutableFloatStateOf(start.third) }

    val picked = hsvColor(hue, sat, v)

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
                .padding(bottom = 26.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "自定义颜色",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Medium,
                )
                Spacer(Modifier.weight(1f))
                // 预览：选中的颜色直接铺在圆上
                Box(
                    Modifier
                        .size(34.dp)
                        .clip(CircleShape)
                        .background(picked)
                        .border(1.dp, scheme.outline.copy(alpha = 0.5f), CircleShape),
                )
            }

            Spacer(Modifier.height(4.dp))
            Text(
                "#" + picked.toArgb().toUInt().toString(16).takeLast(6).uppercase(),
                fontSize = 11.5.sp,
                color = scheme.onSurfaceVariant,
            )

            Spacer(Modifier.height(18.dp))

            // ── 色相条 ─────────────────────────────────────────────
            Canvas(
                Modifier
                    .fillMaxWidth()
                    .height(26.dp)
                    .clip(RoundedCornerShape(13.dp))
                    .pointerInput(Unit) {
                        fun pick(x: Float) {
                            hue = (x / size.width).coerceIn(0f, 0.999f) * 360f
                        }
                        detectDragGestures(
                            onDragStart = { pick(it.x) },
                            onDrag = { c, _ -> pick(c.position.x); c.consume() },
                        )
                    }
                    .pointerInput(Unit) {
                        detectTapGestures { pick ->
                            hue = (pick.x / size.width).coerceIn(0f, 0.999f) * 360f
                        }
                    },
            ) {
                drawRect(
                    Brush.horizontalGradient(
                        listOf(
                            Color.Red, Color.Yellow, Color.Green,
                            Color.Cyan, Color.Blue, Color.Magenta, Color.Red,
                        )
                    )
                )
                // 当前色相的位置指示
                val x = (hue / 360f) * size.width
                drawCircle(Color.White, radius = size.height / 2.6f, center = Offset(x, size.height / 2))
                drawCircle(Color.Black.copy(alpha = 0.35f), radius = size.height / 2.6f, center = Offset(x, size.height / 2), style = androidx.compose.ui.graphics.drawscope.Stroke(1.5f))
            }

            Spacer(Modifier.height(14.dp))

            // ── 饱和度 / 明度方块 ──────────────────────────────────
            Canvas(
                Modifier
                    .fillMaxWidth()
                    .height(150.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .pointerInput(Unit) {
                        fun pick(p: Offset) {
                            sat = (p.x / size.width).coerceIn(0f, 1f)
                            v = (1f - p.y / size.height).coerceIn(0f, 1f)
                        }
                        detectDragGestures(
                            onDragStart = { pick(it) },
                            onDrag = { c, _ -> pick(c.position); c.consume() },
                        )
                    }
                    .pointerInput(Unit) {
                        detectTapGestures { pick ->
                            sat = (pick.x / size.width).coerceIn(0f, 1f)
                            v = (1f - pick.y / size.height).coerceIn(0f, 1f)
                        }
                    },
            ) {
                // 底层：当前色相 → 白；上层：透明 → 黑。两层叠起来就是 HSV 方块
                drawRect(Brush.horizontalGradient(listOf(hsvColor(hue, 1f, 1f), Color.White)))
                drawRect(Brush.verticalGradient(listOf(Color.Transparent, Color.Black)))

                val x = sat * size.width
                val y = (1f - v) * size.height
                drawCircle(Color.White, radius = 9f, center = Offset(x, y), style = androidx.compose.ui.graphics.drawscope.Stroke(2.5f))
                drawCircle(Color.Black.copy(alpha = 0.4f), radius = 11.5f, center = Offset(x, y), style = androidx.compose.ui.graphics.drawscope.Stroke(1f))
            }

            Spacer(Modifier.height(20.dp))

            Row(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(scheme.primary)
                    .clickable {
                        // 存 ARGB 的低 32 位，读的时候 Color(long) 直接还原
                        onPick(picked.toArgb().toLong() and 0xFFFFFFFFL)
                    }
                    .padding(vertical = 13.dp),
                horizontalArrangement = Arrangement.Center,
            ) {
                Text(
                    "使用这个颜色",
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium,
                    color = scheme.onPrimary,
                )
            }
        }
    }
}

// ── HSV ────────────────────────────────────────────────────────────────

/** @return [h(0..360), s(0..1), v(0..1)] */
internal fun Color.toHsv(): Triple<Float, Float, Float> {
    val r = red
    val g = green
    val b = blue
    val max = maxOf(r, g, b)
    val min = minOf(r, g, b)
    val d = max - min
    val h = when {
        d == 0f -> 0f
        max == r -> 60f * (((g - b) / d) % 6f)
        max == g -> 60f * (((b - r) / d) + 2f)
        else -> 60f * (((r - g) / d) + 4f)
    }
    val s = if (max == 0f) 0f else d / max
    return Triple((h + 360f) % 360f, s, max)
}

internal fun hsvColor(h: Float, s: Float, v: Float): Color {
    val c = v * s
    val hp = ((h % 360f) + 360f) % 360f / 60f
    val x = c * (1f - kotlin.math.abs(hp % 2f - 1f))
    val (r1, g1, b1) = when (hp.toInt()) {
        0 -> Triple(c, x, 0f)
        1 -> Triple(x, c, 0f)
        2 -> Triple(0f, c, x)
        3 -> Triple(0f, x, c)
        4 -> Triple(x, 0f, c)
        else -> Triple(c, 0f, x)
    }
    val m = v - c
    return Color(r1 + m, g1 + m, b1 + m)
}

/** 色卡上那颗「自定义」的彩虹圆，用渐变画 */
@Composable
internal fun CustomSwatchDot(hasColor: Boolean, color: Color, size: androidx.compose.ui.unit.Dp = 30.dp) {
    Box(
        Modifier
            .size(size)
            .clip(CircleShape)
            .then(
                if (hasColor) Modifier.background(color)
                else Modifier.background(
                    Brush.sweepGradient(
                        listOf(
                            Color(0xFFFF6B6B), Color(0xFFFFD166), Color(0xFF9BE564),
                            Color(0xFF4ADE9B), Color(0xFF56D4DD), Color(0xFF7FA8FF),
                            Color(0xFF9C8CFF), Color(0xFFE88BC4), Color(0xFFFF6B6B),
                        )
                    )
                )
            )
            .border(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.4f), CircleShape),
    )
}
