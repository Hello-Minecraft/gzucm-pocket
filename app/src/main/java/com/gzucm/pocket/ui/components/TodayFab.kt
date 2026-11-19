package com.gzucm.pocket.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * 右下角「回到今日」圆形按钮。
 *
 * ## 什么时候出现
 *
 * 只在**没停在今天**时出现（致辉 2026-11-19 定）。停今天就自动消失 ——
 * 停在今天时这个按钮没有任何意义，占着右下角只会挡住卡片。
 * 滑走时淡入 + 轻微放大，滑回来时淡出，不做位移动画（位移会和卡片滑动打架）。
 *
 * 同一个组件给日视图和周视图用：日视图「回到今天那一页」，周视图「回到今天那一周」。
 * 调用方各自判断 `visible`，组件本身不碰业务。
 *
 * ## 为什么是主色投影
 *
 * 参考了小米日历那个「今」按钮（致辉提供的截图）。投影取主色而不是黑色，
 * 按钮周围会有一圈同色光晕，压在壁纸上时不会像一块黑色脏斑。
 *
 * ⚠️ 彩色投影要 API 28+ 才真的染色；minSdk 26，API 26/27 上会退化成普通黑色阴影。
 * 降级之后按钮本身仍然清楚，只是少了光晕 —— 不为了两个旧版本去掉这个效果。
 *
 * ## 位置为什么在底栏上方
 *
 * 课表页底部有一条会渐隐的浮动胶囊栏（[FloatingBar]），贴屏幕底边放会压住右边那个 tab。
 * 所以按钮的底边固定在胶囊栏顶部再往上 12dp，见 [TodayFabBottom]。
 */
@Composable
fun TodayFab(
    visible: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val scheme = MaterialTheme.colorScheme

    AnimatedVisibility(
        visible = visible,
        enter = fadeIn(tween(180)) + scaleIn(tween(180), initialScale = 0.82f),
        exit = fadeOut(tween(140)) + scaleOut(tween(140), targetScale = 0.82f),
        modifier = modifier,
    ) {
        Box(
            Modifier
                /*
                 * 顺序有意义：
                 *   1. navigationBarsPadding —— 手势条区域不能压
                 *   2. padding(bottom) —— 再往上让开底栏胶囊
                 * 两个都是「离屏幕底边多远」，加起来才是最终位置。
                 */
                .navigationBarsPadding()
                .padding(end = 16.dp, bottom = TodayFabBottom)
                .size(TodayFabSize)
                .shadow(
                    elevation = 12.dp,
                    shape = CircleShape,
                    /*
                     * 主色投影。alpha 给到 0.55/0.75 —— 再淡在浅色壁纸上看不出光晕，
                     * 再浓会让圆盘边缘发糊、像印上去的。
                     */
                    ambientColor = scheme.primary.copy(alpha = 0.55f),
                    spotColor = scheme.primary.copy(alpha = 0.75f),
                )
                .clip(CircleShape)
                .background(scheme.primary)
                .clickable(onClickLabel = "回到今天", onClick = onClick),
            contentAlignment = Alignment.Center,
        ) {
            /*
             * 用汉字「今」而不是图标：
             *   · 图标库里没有「回到今天」这种语义的图标，硬找一个箭头会表意不清
             *   · 小米日历、系统日历都用这个字，用户一眼认得
             */
            Text(
                text = "今",
                color = scheme.onPrimary,
                fontSize = 17.sp,
                fontWeight = FontWeight.SemiBold,
            )
        }
    }
}

/** 圆盘直径。52dp 略小于标准 FAB(56dp)，因为它在课表页只是辅助操作，不该抢主视觉。 */
private val TodayFabSize = 52.dp

/**
 * 按钮底边距屏幕底部（不含导航栏）的距离。
 *
 * 底栏胶囊的构成：
 *   `FloatingBarBottomGap(14dp)` + 胶囊内容高 ≈ 72dp（8dp 竖内边距 ×2
 *   + tab 的 5dp ×2 + 图标 21dp + 文字 14dp + 指示点 3.5dp）
 * 再留 12dp 呼吸，取整到 98dp。
 *
 * 这个值跟着底栏改就要跟着调；它不影响布局约束（按钮是浮层），
 * 所以宁可写死一个具体数字，也不要拿 `LocalFloatingBarInset`（170dp）——
 * 那是给滚动内容留白用的，含 overscroll，直接拿来定位会飘到屏幕中间。
 */
private val TodayFabBottom = 98.dp

/** 让调用方能算「按钮会不会压到内容」时复用的尺寸（目前只有测试用得到）。 */
internal val TodayFabReservedHeight = TodayFabBottom + TodayFabSize
