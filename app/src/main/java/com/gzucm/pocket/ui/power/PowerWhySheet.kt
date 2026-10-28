package com.gzucm.pocket.ui.power

import androidx.compose.foundation.background
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
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * 「电费不准？」说明。
 *
 * 定位：不是免责声明，是把**用户真正会踩的几个坑**一次讲清楚。
 * 所以除了来源与到账时间，还补了：绑错房间、余额单位、明细只覆盖当月、更新频率。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PowerWhySheet(roomLabel: String, onDismiss: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    val ctx = LocalContext.current
    val store = remember { com.gzucm.pocket.core.SessionStore(ctx.applicationContext) }

    val syncedAt = remember {
        val ts = store.powerCheckedAt
        if (ts <= 0) "—"
        else java.text.SimpleDateFormat("MM-dd HH:mm", java.util.Locale.CHINA)
            .format(java.util.Date(ts))
    }

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
                "电费数据说明",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Medium,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                "当前显示 $roomLabel · 更新于 $syncedAt",
                fontSize = 11.5.sp,
                color = scheme.onSurfaceVariant,
            )

            Spacer(Modifier.height(16.dp))

            // 用户点名要说的两条
            Point(
                "数据来源",
                "本内容提取自「广州中医药大学一卡通」小程序。请前往小程序确认是否绑定了当前宿舍。",
                highlight = true,
            )
            Point(
                "充值到账",
                "电费充值后约 15 分钟到账，请耐心等待。刚充完看到余额没变是正常的，别重复充值。",
                highlight = true,
            )

            Spacer(Modifier.height(4.dp))

            // 补充的几条：都是实际会踩的
            Point(
                "显示 0 度但宿舍有电",
                "多半是房间号没绑对。去小程序里核对校区、楼栋、宿舍号，三样都要对上。",
            )
            Point(
                "余额是「度」不是「元」",
                "这里显示的是剩余电量，单位是度。充值时按金额折算成度数，所以和微信里的扣款金额不是同一个数。",
            )
            Point(
                "免费电量不显示",
                "学校发的补贴电量单独计算，不并入这里的余额。余额为 0 仍能用电时，通常是在用免费电量。",
            )
            Point(
                "每日明细只覆盖当月",
                "只保留当前自然月的逐日记录，往月的拿不到。",
            )
            Point(
                "更新频率",
                "每天首次打开 App 会在后台自动更新一次（不弹窗）。想立刻刷新，在电费页点「更新」。",
            )

            Spacer(Modifier.height(14.dp))

            Text(
                "如果和小程序里的数字对不上，以小程序和宿管处为准。",
                fontSize = 10.5.sp,
                lineHeight = 16.sp,
                color = scheme.onSurfaceVariant.copy(alpha = 0.75f),
            )
        }
    }
}

@Composable
private fun Point(title: String, body: String, highlight: Boolean = false) {
    val scheme = MaterialTheme.colorScheme
    Row(Modifier.padding(bottom = 14.dp)) {
        // 小圆点
        Spacer(
            Modifier
                .padding(top = 6.dp)
                .size(5.dp)
                .clip(CircleShape)
                .background(
                    if (highlight) scheme.primary else scheme.onSurfaceVariant.copy(alpha = 0.45f)
                )
        )
        Spacer(Modifier.width(10.dp))
        Column {
            Text(
                title,
                fontSize = 12.5.sp,
                fontWeight = FontWeight.Medium,
                color = if (highlight) scheme.primary else scheme.onSurface,
            )
            Spacer(Modifier.height(2.dp))
            Text(
                body,
                fontSize = 12.sp,
                lineHeight = 18.sp,
                color = scheme.onSurfaceVariant,
            )
        }
    }
}
