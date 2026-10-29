package com.gzucm.pocket.ui.components

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay

/**
 * 首次同步时的占位提示。
 *
 * 为什么需要：首次安装没有缓存，要等「周历 + 逐周课表」全部回来才有东西可看。
 * 好网络下 0.2 秒，弱网下可能几十秒 —— 这段时间界面是空的，用户只会以为卡死了。
 *
 * 文案轮播而不是写死一句，是因为等待时间长的时候静止的文字会让人以为它挂了。
 */
@Composable
fun SyncPlaceholder(
    done: Int,
    total: Int,
    modifier: Modifier = Modifier,
) {
    val scheme = MaterialTheme.colorScheme
    var idx by remember { mutableIntStateOf(0) }

    val lines = listOf(
        "正在抓水母…",
        "网络好像开了个小差，再等等",
        "首次进入要拉整学期课表，会比平时慢一点",
        "快好了，再给一点点时间",
    )

    LaunchedEffect(Unit) {
        while (true) {
            delay(2_600)
            idx = (idx + 1) % lines.size
        }
    }

    Column(
        modifier
            .fillMaxWidth()
            .padding(horizontal = 18.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        // 三个点的呼吸动画，表示还活着
        val transition = rememberInfiniteTransition(label = "dots")
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            repeat(3) { i ->
                val a by transition.animateFloat(
                    initialValue = 0.25f,
                    targetValue = 1f,
                    animationSpec = infiniteRepeatable(
                        animation = tween(700, delayMillis = i * 160, easing = LinearEasing),
                        repeatMode = RepeatMode.Reverse,
                    ),
                    label = "dot$i",
                )
                Box(
                    Modifier
                        .size(7.dp)
                        .alpha(a)
                        .clip(CircleShape)
                        .background(scheme.primary),
                )
            }
        }

        Spacer(Modifier.height(16.dp))

        Text(
            lines[idx],
            fontSize = 13.sp,
            color = scheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )

        if (total > 0) {
            Spacer(Modifier.height(14.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier
                        .width(120.dp)
                        .height(3.dp)
                        .clip(RoundedCornerShape(2.dp))
                        .background(scheme.onSurfaceVariant.copy(alpha = 0.15f)),
                ) {
                    Box(
                        Modifier
                            .fillMaxWidth(done.toFloat() / total)
                            .height(3.dp)
                            .clip(RoundedCornerShape(2.dp))
                            .background(scheme.primary),
                    )
                }
                Spacer(Modifier.width(8.dp))
                Text(
                    "$done/$total 周",
                    fontSize = 10.5.sp,
                    color = scheme.onSurfaceVariant.copy(alpha = 0.8f),
                    fontWeight = FontWeight.Medium,
                )
            }
        }
    }
}
