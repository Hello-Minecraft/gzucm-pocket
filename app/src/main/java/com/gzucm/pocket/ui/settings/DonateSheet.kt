package com.gzucm.pocket.ui.settings

import android.graphics.BitmapFactory
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Image
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowBack
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * 一档打赏。
 *
 * @param asset 打包在 assets 里的**固定金额**收款码 —— 扫码后微信直接显示该金额，
 *   用户不用再选一次。
 */
private data class Tier(
    val price: String,
    val title: String,
    val tail: String,
    val asset: String,
)

private val Tiers = listOf(
    Tier("4", "请作者喝一杯蜜雪柠檬水", "解解渴", "donate_4.png"),
    Tier("9.9", "请作者喝一杯库迪咖啡", "继续干活", "donate_9_9.png"),
    Tier("19.9", "请作者喝一杯喜茶清爽芭乐提", "芭乐提好喝", "donate_19_9.png"),
)

/**
 * 打赏面板，两级：
 *   一级 = 三档列表
 *   二级 = 该档的收款码大图
 *
 * App 内**不参与支付**，只展示收款码；钱从付款人微信直接进作者零钱。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DonateSheet(onDismiss: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    var selected by remember { mutableStateOf<Tier?>(null) }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = scheme.surface,
        shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp),
    ) {
        AnimatedContent(
            targetState = selected,
            transitionSpec = { fadeIn() togetherWith fadeOut() },
            label = "donate",
        ) { tier ->
            if (tier == null) {
                TierList(onPick = { selected = it })
            } else {
                TierDetail(tier = tier, onBack = { selected = null })
            }
        }
    }
}

@Composable
private fun TierList(onPick: (Tier) -> Unit) {
    val scheme = MaterialTheme.colorScheme
    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 22.dp)
            .padding(bottom = 26.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            "觉得软件做的不错？",
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Medium,
        )
        Spacer(Modifier.height(2.dp))
        Text("请作者喝杯咖啡", fontSize = 13.sp, color = scheme.onSurfaceVariant)

        Spacer(Modifier.height(18.dp))

        Column(
            Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Tiers.forEach { t ->
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(scheme.surfaceVariant.copy(alpha = 0.45f))
                        .clickable { onPick(t) }
                        .padding(horizontal = 14.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        "¥${t.price}",
                        fontSize = 15.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = scheme.primary,
                        modifier = Modifier.width(58.dp),
                    )
                    Column(Modifier.weight(1f)) {
                        Text(t.title, fontSize = 13.sp, lineHeight = 18.sp)
                        Text(
                            t.tail,
                            fontSize = 10.5.sp,
                            color = scheme.onSurfaceVariant.copy(alpha = 0.75f),
                        )
                    }
                    Icon(
                        Icons.Rounded.ChevronRight,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp),
                        tint = scheme.onSurfaceVariant.copy(alpha = 0.4f),
                    )
                }
            }
        }

        Spacer(Modifier.height(18.dp))

        Box(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .background(scheme.primary.copy(alpha = 0.07f))
                .padding(horizontal = 14.dp, vertical = 11.dp),
        ) {
            Column {
                Text(
                    "帮帮孩子吧QaQ没钱给梁圣充token了",
                    fontSize = 12.5.sp,
                    lineHeight = 18.sp,
                )
                Spacer(Modifier.height(3.dp))
                Text("（非常感谢您）", fontSize = 11.sp, color = scheme.onSurfaceVariant)
            }
        }

        Spacer(Modifier.height(12.dp))
        Text(
            "纯自愿，不请也照样用全部功能",
            fontSize = 10.5.sp,
            color = scheme.onSurfaceVariant.copy(alpha = 0.65f),
        )
    }
}

@Composable
private fun TierDetail(tier: Tier, onBack: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    val ctx = LocalContext.current

    val qr = remember(tier.asset) {
        runCatching {
            ctx.assets.open(tier.asset).use { BitmapFactory.decodeStream(it) }?.asImageBitmap()
        }.getOrNull()
    }

    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 22.dp)
            .padding(bottom = 26.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        // 返回一级
        Row(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(10.dp))
                .clickable(onClick = onBack)
                .padding(vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                Icons.Rounded.ArrowBack,
                contentDescription = "返回",
                modifier = Modifier.size(17.dp),
                tint = scheme.onSurfaceVariant,
            )
            Spacer(Modifier.width(5.dp))
            Text("换一档", fontSize = 12.sp, color = scheme.onSurfaceVariant)
        }

        Spacer(Modifier.height(8.dp))

        Text(
            "¥${tier.price}",
            fontSize = 30.sp,
            fontWeight = FontWeight.Medium,
            color = scheme.primary,
        )
        Spacer(Modifier.height(2.dp))
        Text(tier.title, fontSize = 13.sp)
        Spacer(Modifier.height(2.dp))
        Text(tier.tail, fontSize = 10.5.sp, color = scheme.onSurfaceVariant)

        Spacer(Modifier.height(16.dp))

        if (qr != null) {
            Box(
                Modifier
                    .clip(RoundedCornerShape(16.dp))
                    .background(Color.White)
                    .padding(8.dp),
            ) {
                Image(
                    bitmap = qr,
                    contentDescription = "微信收款码 ¥${tier.price}",
                    modifier = Modifier.size(230.dp),
                    contentScale = ContentScale.Fit,
                    filterQuality = FilterQuality.None,
                )
            }
        } else {
            Box(
                Modifier
                    .size(230.dp)
                    .clip(RoundedCornerShape(16.dp))
                    .background(scheme.surfaceVariant.copy(alpha = 0.4f)),
                contentAlignment = Alignment.Center,
            ) {
                Text("收款码缺失", fontSize = 12.sp, color = scheme.onSurfaceVariant)
            }
        }

        Spacer(Modifier.height(12.dp))

        Text(
            "打开微信「扫一扫」扫描即可，金额已固定为 ¥${tier.price}",
            fontSize = 11.5.sp,
            color = scheme.onSurfaceVariant,
            lineHeight = 17.sp,
        )
        Spacer(Modifier.height(4.dp))
        Text(
            "不方便扫码也可截图后在微信「相册」里选这张图",
            fontSize = 10.5.sp,
            color = scheme.onSurfaceVariant.copy(alpha = 0.7f),
        )

        Spacer(Modifier.height(14.dp))
        Text(
            "纯自愿，不进任何第三方平台",
            fontSize = 10.5.sp,
            color = scheme.onSurfaceVariant.copy(alpha = 0.6f),
        )
    }
}
