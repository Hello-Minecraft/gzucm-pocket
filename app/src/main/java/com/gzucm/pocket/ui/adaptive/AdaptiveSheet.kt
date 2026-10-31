package com.gzucm.pocket.ui.adaptive

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.clickable
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * 当前面板是否渲染成「右侧侧栏」形态。
 *
 * 面板内部靠它决定要不要自己画大标题：底部 Sheet 没有顶栏，标题必须在正文里；
 * 侧栏的顶栏已经显示了标题，正文里再来一遍就重复了。
 *
 * 默认 false（手机形态），AdaptiveSheet 在渲染侧栏时覆盖。
 */
val LocalIsWidePane = androidx.compose.runtime.staticCompositionLocalOf { false }

/**
 * 统一的「弹出面板」容器，按窗口宽度自动选形态。
 *
 * - **手机 / 窄窗口** → 底部 ModalBottomSheet（原有行为）
 * - **平板 / 宽窗口** → 从右侧滑出的侧栏
 *
 * 为什么平板要用侧栏：11 寸横屏高只有 600dp 上下，底部 Sheet 一弹就盖住
 * 大半张课表；侧栏只占右边一块，左边内容还在原地，能边看边对照。
 *
 * 所有面板都走这一个入口，而不是各写各的 —— 否则九个面板会有九套圆角、
 * 九种关闭方式、九个「平板下忘了改」的角落。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AdaptiveSheet(
    visible: Boolean,
    onDismiss: () -> Unit,
    /** 侧栏宽度。内容特别多的面板可以调宽，默认 380dp */
    paneWidth: Dp = 380.dp,
    /** 侧栏顶部显示的标题；为空时只显示关闭按钮 */
    paneTitle: String? = null,
    /**
     * 手机端底部 Sheet 的开关。默认全部展开。
     *
     * 有些面板（比如电费同步）内容很高，需要它保持展开状态，
     * 否则用户要先上滑一次才能看到全部内容。
     */
    skipPartiallyExpanded: Boolean = true,
    /**
     * 正文是否由 AdaptiveSheet 负责滚动。
     *
     * 设成 false 用于「正文自己就会滚动」的面板（设置页、关于页）——
     * 两层垂直滚动容器叠在一起会直接崩溃：
     * 内层拿到无限高度约束，抛
     * 「Vertically scrollable component was measured with an infinity
     * maximum height constraints」。
     *
     * 这不是随便猜的，是实机 FATAL 堆栈里读出来的。
     */
    scrollable: Boolean = true,
    /**
     * 侧栏从**左侧**滑出（默认从右侧）。
     *
     * 平板横屏时导航栏在左边，面板也从左边出来，手指不用横跨整块屏幕。
     * 手机端（底部 Sheet）不受这个参数影响。
     */
    fromLeft: Boolean = false,
    content: @Composable ColumnScope.() -> Unit,
) {
    /*
     * 不可见时**什么都不渲染**：不占父容器空间、不拦截触摸。
     *
     * 这里有三个版本迭代的教训：
     *   ① 最初 `if (showXxx) { AdaptiveSheet(visible = true) }` ——
     *      组件首次进组合时 visible 已 true，进入动画不播。
     *   ② 改成常驻 + `BoxWithConstraints(fillMaxSize())` ——
     *      动画有了，但不可见时仍占满父容器：电费余额卡里嵌着
     *      「电费设置」面板，整张卡被撑成空白，余额数字和趋势图全被挤没。
     *   ③ 改用 Popup 承载 —— 不占位了，但 Popup 窗口只要存在
     *      就会拦截触摸（focusable=true），visible=false 时整个界面点不动，
     *      表现就是「App 卡死」。
     *
     * 现在这个写法：不可见直接 return（不占位、不拦触摸），
     * 可见时用 appear 标记触发一次进入动画。退场没有动画 ——
     * 这个取舍是刻意的：动画再好看也不能拿「点不动」去换。
     */
    if (!visible) return

    var appear by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(false) }
    androidx.compose.runtime.LaunchedEffect(Unit) { appear = true }

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val usePane = maxWidth >= 840.dp

        if (usePane) {
            RightSidePane(
                visible = appear,
                onDismiss = onDismiss,
                width = paneWidth,
                title = paneTitle,
                scrollable = scrollable,
                fromLeft = fromLeft,
                content = content,
            )
        } else {
            val scheme = MaterialTheme.colorScheme
            ModalBottomSheet(
                onDismissRequest = onDismiss,
                sheetState = rememberModalBottomSheetState(
                    skipPartiallyExpanded = skipPartiallyExpanded,
                ),
                containerColor = scheme.surface,
                shape = androidx.compose.foundation.shape.RoundedCornerShape(
                    topStart = 24.dp, topEnd = 24.dp,
                ),
            ) {
                Column(Modifier.fillMaxWidth(), content = content)
            }
        }
    }
}

/** 右侧滑出面板本体。 */
@Composable
private fun RightSidePane(
    visible: Boolean,
    onDismiss: () -> Unit,
    width: Dp,
    title: String?,
    scrollable: Boolean,
    fromLeft: Boolean,
    content: @Composable ColumnScope.() -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    androidx.compose.runtime.CompositionLocalProvider(LocalIsWidePane provides true) {
    /*
     * 遮罩 + 滑出，两段动画各管一摊：
     *
     *   遮罩：0.32 黑色，淡入淡出（跟底部 Sheet 的 scrim 同浓度）
     *   面板：从右侧滑入滑出
     *
     * 遮罩是必须的 —— 不压暗的话，面板和课表糊成一片，
     * 用户看不出「现在是在一个浮层里操作」。
     * 之前我写成全透明，理由是「想边看课表边看面板」，
     * 但那跟「看不清当前在哪一层」比，后者更影响使用。
     */
    androidx.compose.animation.AnimatedVisibility(
        visible = visible,
        enter = androidx.compose.animation.fadeIn(
            animationSpec = androidx.compose.animation.core.tween(180),
        ),
        exit = androidx.compose.animation.fadeOut(
            animationSpec = androidx.compose.animation.core.tween(180),
        ),
    ) {
        Box(
            Modifier
                .fillMaxSize()
                .background(androidx.compose.ui.graphics.Color.Black.copy(alpha = 0.32f))
                .clickable(
                    interactionSource = null,
                    indication = null,
                    onClickLabel = "关闭面板",
                    onClick = onDismiss,
                ),
        )
    }

    androidx.compose.animation.AnimatedVisibility(
        visible = visible,
        enter = slideInHorizontally(
            // 从左出来时起点是 -宽度，从右出来是 +宽度
            initialOffsetX = { if (fromLeft) -it else it },
            animationSpec = androidx.compose.animation.core.tween(
                durationMillis = 260,
                easing = androidx.compose.animation.core.FastOutSlowInEasing,
            ),
        ),
        exit = slideOutHorizontally(
            targetOffsetX = { if (fromLeft) -it else it },
            animationSpec = androidx.compose.animation.core.tween(
                durationMillis = 200,
                easing = androidx.compose.animation.core.FastOutSlowInEasing,
            ),
        ),
    ) {
        /*
         * 用 Box + align 定位，不用 Row + weight。
         *
         * Row 的 weight 会先吃掉剩余空间，再让固定宽度的子项在余量里塞 ——
         * 实机量出来面板只有 268dp 而不是 380dp，就是被 weight 挤压的。
         * Box 里靠右对齐 + 固定宽度，宽度才是真的。
         */
        Box(Modifier.fillMaxSize()) {
            Column(
                Modifier
                    .align(if (fromLeft) Alignment.CenterStart else Alignment.CenterEnd)
                    .width(width)
                    .fillMaxHeight()
                    .background(scheme.surface)
                    .statusBarsPadding(),
            ) {
                // 顶栏：标题 + 关闭
                /*
                 * top 给到 18dp：只靠 statusBarsPadding 的话（平板横屏状态栏很矮，
                 * 只有 13dp 左右），标题会贴着系统时间显示，看着很挤。
                 * 再叠 18dp 才舒展开。
                 */
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(start = 20.dp, end = 8.dp, top = 18.dp, bottom = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (!title.isNullOrBlank()) {
                        Text(
                            title,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Medium,
                            color = scheme.onSurfaceVariant,
                            modifier = Modifier.weight(1f),
                        )
                    } else {
                        Spacer(Modifier.weight(1f))
                    }
                    IconButton(onClick = onDismiss, modifier = Modifier.size(36.dp)) {
                        Icon(
                            Icons.Rounded.Close,
                            contentDescription = "关闭",
                            tint = scheme.onSurfaceVariant,
                            modifier = Modifier.size(19.dp),
                        )
                    }
                }
                Spacer(Modifier.height(2.dp))
                /*
                 * 正文区。
                 *
                 * 默认由这一层负责滚动（内容矮的面板省事）；
                 * 但正文自己已经有 verticalScroll 的面板必须传 scrollable = false，
                 * 否则两层滚动容器嵌套 → 内层拿到无限高度约束 → 直接崩溃。
                 */
                val bodyModifier = if (scrollable) {
                    Modifier
                        .fillMaxWidth()
                        .verticalScroll(rememberScrollState())
                        .padding(bottom = 24.dp)
                } else {
                    Modifier
                        .fillMaxWidth()
                        .weight(1f)
                }
                Column(bodyModifier, content = content)
            }
        }
    }
    }
}