package com.gzucm.pocket.ui.power

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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
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
 * 换宿舍怎么办。
 *
 * 为什么不在 App 里做：实测一卡通页面在 WebView 里点不动支付。
 * 绑定房间的最后一步要走微信支付（一分钱验证），WebView 里点支付按钮没反应，
 * 流程卡在那里，房间永远绑不上。这不是参数问题，是微信支付在 WebView 里的限制。
 *
 * 所以这里只做「说清楚去哪做、做完怎么回来」。不去猜小程序的具体菜单路径 ——
 * 猜错了反而让人更困惑。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RebindGuideSheet(onDismiss: () -> Unit) {
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
                "换宿舍了怎么办",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Medium,
            )
            Spacer(Modifier.height(6.dp))
            Text(
                "改绑宿舍只能在小程序里完成，App 内做不了",
                fontSize = 11.5.sp,
                lineHeight = 17.sp,
                color = scheme.onSurfaceVariant,
            )

            Spacer(Modifier.height(20.dp))

            Step(
                1, "在微信里打开一卡通小程序",
                "搜「广州中医药大学一卡通」，进电费。",
            )
            Step(
                2, "重新绑定宿舍",
                "校区、楼栋、宿舍号都要选对，缺一个都查不到电。",
            )
            Step(
                3, "充值一个小金额",
                "要点击支付一次才会绑定上新宿舍。可以不用真的付款 —— " +
                    "进付款页再退出来就行。",
            )
            Step(
                4, "回 App 点「更新」",
                "电费页点一下「更新」，余额就会换成新宿舍的。",
            )

            Spacer(Modifier.height(8.dp))

            Box(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(scheme.tertiary.copy(alpha = 0.12f))
                    .padding(horizontal = 14.dp, vertical = 11.dp),
            ) {
                Text(
                    "小程序里显示已绑对但 App 还是 0 度，回去重新绑一次通常就好了。",
                    fontSize = 11.5.sp,
                    lineHeight = 17.sp,
                    color = scheme.tertiary,
                )
            }

            Spacer(Modifier.height(20.dp))

            Row(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(scheme.primary)
                    .clickable(onClick = onDismiss)
                    .padding(vertical = 13.dp),
                horizontalArrangement = Arrangement.Center,
            ) {
                Text(
                    "知道了",
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium,
                    color = scheme.onPrimary,
                )
            }
        }
    }
}

/** 带序号的一步 */
@Composable
private fun Step(index: Int, title: String, body: String) {
    val scheme = MaterialTheme.colorScheme
    Row(Modifier.padding(bottom = 16.dp)) {
        Box(
            Modifier
                .size(20.dp)
                .clip(CircleShape)
                .background(scheme.primary.copy(alpha = 0.16f)),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                "$index",
                fontSize = 11.sp,
                fontWeight = FontWeight.Medium,
                color = scheme.primary,
            )
        }
        Spacer(Modifier.width(11.dp))
        Column {
            Text(
                title,
                fontSize = 13.sp,
                fontWeight = FontWeight.Medium,
                color = scheme.onSurface,
            )
            Spacer(Modifier.height(3.dp))
            Text(
                body,
                fontSize = 12.sp,
                lineHeight = 18.sp,
                color = scheme.onSurfaceVariant,
            )
        }
    }
}