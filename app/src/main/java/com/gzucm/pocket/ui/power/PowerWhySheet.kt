package com.gzucm.pocket.ui.power

import androidx.compose.foundation.clickable
import androidx.compose.foundation.selection.toggleable
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
import com.gzucm.pocket.ui.adaptive.AdaptiveSheet
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
fun PowerWhySheet(
    /**
     * 显隐。由调用方传真实状态、**外层不要再包 if**。
     *
     * 包 if 的话组件第一次进组合时 visible 就已经是 true，
     * AnimatedVisibility 认为「初始就可见」，进入动画不播、
     * 退场时整棵子树被直接摘掉 —— 面板会「啪」地出现，
     * 平板侧栏完全没有滑动效果。
     */
    visible: Boolean = true,
    roomLabel: String,
    onDismiss: () -> Unit,
    alertOn: Boolean = true,
    onAlertChange: (Boolean) -> Unit = {},
    /** 引导去小程序改绑（App 内 WebView 完成不了支付，绑不了） */
    onBuyInMiniProgram: () -> Unit = {},
) {
    val scheme = MaterialTheme.colorScheme
    val ctx = LocalContext.current
    val store = remember { com.gzucm.pocket.core.SessionStore(ctx.applicationContext) }

    val syncedAt = remember {
        val ts = store.powerCheckedAt
        if (ts <= 0) "—"
        else java.text.SimpleDateFormat("MM-dd HH:mm", java.util.Locale.CHINA)
            .format(java.util.Date(ts))
    }

    AdaptiveSheet(
        visible = visible,
        onDismiss = onDismiss,
        paneTitle = "电费说明",
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 22.dp)
                .padding(bottom = 28.dp),
        ) {
            Text(
                "电费设置",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Medium,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                "当前显示 $roomLabel · 更新于 $syncedAt",
                fontSize = 11.5.sp,
                color = scheme.onSurfaceVariant,
            )

            Spacer(Modifier.height(14.dp))

            // 电费不足提醒开关（跟着「电费不准？」一起放进来）
            Row(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(scheme.surfaceVariant.copy(alpha = 0.35f))
                    // 用 toggleable 单点处理；Switch 自己不接点击，
                    // 否则同一次点击会翻两次，看起来「卡卡的」
                    .toggleable(
                        value = alertOn,
                        role = androidx.compose.ui.semantics.Role.Switch,
                        onValueChange = onAlertChange,
                    )
                    .padding(horizontal = 14.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text("电费不足提醒", fontSize = 13.sp, fontWeight = FontWeight.Medium)
                    Text(
                        "可用不足 3 天时提醒，每天最多一次",
                        fontSize = 10.5.sp,
                        color = scheme.onSurfaceVariant,
                    )
                }
                Spacer(Modifier.width(10.dp))
                androidx.compose.material3.Switch(
                    checked = alertOn,
                    onCheckedChange = null,
                )
            }

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
            /**
             * 换宿舍的引导。
             *
             * 原本想在 App 内用 WebView 打开一卡通页面让用户自己改绑 —— 实测**不行**：
             * 绑定最后一步要走微信支付（一分钱验证），WebView 里点支付没反应，
             * 流程走不完，房间永远绑不上。
             *
             * 所以改成「告诉用户去哪做」：本校一卡通的房间绑定只能在小程序里完成。
             * 不去猜小程序的具体路径，只给必须满足的条件和一句重试提示。
             */
            PointLink(
                title = "宿舍号对不上？",
                body = "会显示 0 度或查不到电。校区、楼栋、宿舍号三样都要对上，缺一个都不行。",
                action = "换宿舍怎么办",
                onClick = onBuyInMiniProgram,
            )
            Point(
                "余额是「度」不是「元」",
                "这里显示的是剩余电量，单位是度。充值时按金额折算成度数，所以和微信里的扣款金额不是同一个数。1 元电费大约等于 1.5 度电。",
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
                "进电费页时如果数据超过 2 小时没更新，会自动弹窗同步一次；2 小时内直接用上次的结果。想立刻刷新就点「更新」。",
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

/**
 * 说明项 + 一个行内操作入口。
 *
 * 入口跟标题**同一行**，带下划线 —— 一行字就能看懂「这问题怎么办」，
 * 不用卡片、不用箭头，低频操作不该抢其他说明的注意力。
 */
@Composable
private fun PointLink(title: String, body: String, action: String, onClick: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    Row(Modifier.padding(bottom = 14.dp)) {
        Spacer(
            Modifier
                .padding(top = 6.dp)
                .size(5.dp)
                .clip(CircleShape)
                .background(scheme.primary)
        )
        Spacer(Modifier.width(10.dp))
        Column {
            Row(verticalAlignment = Alignment.CenterVertically) {
                // 标题也用主题色：整条都在提示「这里有操作」，跟纯灰的说明项分开
                Text(
                    title,
                    fontSize = 12.5.sp,
                    fontWeight = FontWeight.Medium,
                    color = scheme.primary,
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    action,
                    fontSize = 12.5.sp,
                    color = scheme.primary,
                    textDecoration = androidx.compose.ui.text.style.TextDecoration.Underline,
                    modifier = Modifier
                        .clip(RoundedCornerShape(4.dp))
                        .clickable(onClick = onClick)
                        .padding(vertical = 2.dp, horizontal = 2.dp),
                )
            }
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
