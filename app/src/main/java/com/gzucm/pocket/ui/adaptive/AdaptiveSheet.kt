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
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SheetValue
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.getValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.flow.first
import com.gzucm.pocket.ui.components.G3CornerShape
import com.gzucm.pocket.ui.components.micaPanel
import com.gzucm.pocket.ui.components.micaSheetColor
import com.gzucm.pocket.ui.components.micaSideShadow
import com.gzucm.pocket.ui.components.isDarkSurface
import com.gzucm.pocket.ui.icons.AppIcons

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
 * 退场动画的时长（毫秒）。
 *
 * 和 [RightSidePane] 里 slideOut 的 200ms 对齐，多留 20ms 余量：
 * 卸载早了动画会被砍断，卸载晚了面板会在不可见状态下多占一会儿触摸。
 */
private const val EXIT_MS = 220L

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
     * 打开时直接顶到满屏，但**仍然可以下拉到半屏**（致辉 2026-11-14 定）。
     *
     * 只对手机端的底部 Sheet 生效，平板侧栏本来就没有「半屏」这回事。
     *
     * 为什么需要它：`skipPartiallyExpanded = false` 的面板打开时会停在半屏，
     * 表单字段多的时候得先往上拖一次才能看到全部（新建日程就是这样）。
     * 但直接把 skipPartiallyExpanded 改成 true 会把半屏这一档从状态机里删掉 ——
     * 拖下去只会一路关掉，回不到「半屏」。
     *
     * 所以这里保持三档状态机不动，只在面板开始出现时补一次 expand()：
     * 打开是满屏，往下拖仍然先落到半屏，再拖一次才关。
     *
     * 时机是等 targetValue 离开 Hidden 再 expand —— 早于那一刻调用的话
     * 锚点还没算出来，expand() 无处可去；也不能等 currentValue 落到半屏，
     * 那样会先在半屏明显停一下再往上顶。
     */
    expandOnOpen: Boolean = false,
    /**
     * 让面板**始终撑到最高**，不跟着内容高矮走。
     *
     * 为什么需要：`skipPartiallyExpanded = true` 时，M3 的 Expanded 锚点是
     * **内容测量出来的高度**，不是屏幕高度。内容一变矮，面板就整个往下缩一截 ——
     * 日程切成「截止任务」少了半天时间那几行，标题直接从屏幕顶端掉到中间
     * （致辉 2026-11-20 报的「窗口会从全屏缩小到下方小一级」）。
     *
     * 撑满之后锚点就是屏幕顶，切什么形态都不动。
     */
    fillHeight: Boolean = false,
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
    /*
     * 退场也要有动画（2026-11-21 致辉报「关闭是生硬的瞬间消失」）。
     *
     * 原来这里直接 `if (!visible) return` —— 整棵子树瞬间被摘掉：
     * 滑入有 260ms 动画，退出是啪一下没了，两截手感对不上。
     *
     * 改成「延迟卸载」：visible 变 false 之后仍然渲染 [EXIT_MS]，
     * 正好覆盖侧栏的退场动画，之后才真正移除。
     *
     * 为什么不用 AnimatedVisibility 包住最外层：它在不可见时仍会测量一次，
     * 而这套面板有「嵌在电费卡余额数字里」的用法，一测量就把卡片撑成空白
     * （见上面注释里的坑 ②）。延迟卸载只在关闭那两百毫秒内保留，代价可控。
     */
    var mounted by androidx.compose.runtime.remember {
        androidx.compose.runtime.mutableStateOf(visible)
    }
    androidx.compose.runtime.LaunchedEffect(visible) {
        if (visible) {
            mounted = true
        } else if (mounted) {
            kotlinx.coroutines.delay(EXIT_MS)
            mounted = false
        }
    }
    if (!mounted) return

    var appear by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(false) }
    androidx.compose.runtime.LaunchedEffect(Unit) { appear = true }

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val usePane = maxWidth >= 840.dp

        if (usePane) {
            /*
             * 这里必须是 `appear && visible`。
             *
             * 第一版只传了 `appear`（组合后恒为 true），于是关闭时
             * AnimatedVisibility 的 visible 一直是 true —— 退场动画永远不播，
             * 面板是被外层延迟卸载「抽走」的。致辉看到的「生硬消失」有两层原因，
             * 这是第二层。
             *
             * appear 的作用只是让「首次组合就 visible」时也能播进入动画。
             */
            RightSidePane(
                visible = appear && visible,
                onDismiss = onDismiss,
                width = paneWidth,
                title = paneTitle,
                scrollable = scrollable,
                fromLeft = fromLeft,
                content = content,
            )
        } else {
            val scheme = MaterialTheme.colorScheme
            val sheetState = rememberModalBottomSheetState(
                skipPartiallyExpanded = skipPartiallyExpanded,
            )
            if (expandOnOpen) {
                androidx.compose.runtime.LaunchedEffect(sheetState) {
                    // 面板开始出现（说明锚点已经算好）→ 直接顶到满屏
                    snapshotFlow { sheetState.targetValue }
                        .first { it != SheetValue.Hidden }
                    sheetState.expand()
                }
            }
            /*
             * 手机端的退场：主动调 hide() 播下滑动画。
             *
             * 不调的话，visible=false 之后面板会在原地僵住 220ms
             * （延迟卸载的窗口），然后整块消失 —— 和改之前一样生硬。
             */
            androidx.compose.runtime.LaunchedEffect(visible) {
                if (!visible) runCatching { sheetState.hide() }
            }
            ModalBottomSheet(
                onDismissRequest = onDismiss,
                /*
                 * 不要用 confirmValueChange 去拦 SheetValue.Hidden（2026-11-14 踩过）。
                 *
                 * 曾经为了「表单别被下拉误关」这么写过：
                 *     confirmValueChange = { it != SheetValue.Hidden }
                 * 结果面板关不掉了 —— M3 的 hide() 被永久拒绝，Dialog 窗口留在原地，
                 * scrim 继续拦截触摸，而面板内容因为 visible=false 已经从组合里移除，
                 * 表现就是**整屏什么都点不了**，只能杀进程。
                 *
                 * 跟当初 Popup 那个「存在即拦触摸」是同一类错误：为了省一次误触，
                 * 把关闭路径堵死了。误关要防，但只能靠在**数据层保住草稿**，
                 * 绝不能在窗口/手势层面拦。
                 */
                sheetState = sheetState,
                /*
                 * 底色走 micaSheetColor()，**不是** micaPanelColor()。
                 * 后者是卡片色，Sheet 用它会让容器和卡片同色 —— 浅色下靠阴影还能分，
                 * 深色下阴影是黑叠黑、只剩 3dp，整页塌成一块平板（2026-11-19 致辉报）。
                 * 现在三层：页面 #0A0C0F < Sheet #101318 < 卡片 #171C23。
                 */
                containerColor = micaSheetColor(),
                tonalElevation = 0.dp,
                shape = G3CornerShape(
                    topStart = 24.dp, topEnd = 24.dp,
                ),
            ) {
                Column(
                    Modifier
                        .fillMaxWidth()
                        .then(if (fillHeight) Modifier.fillMaxHeight() else Modifier),
                    content = content,
                )
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
                    /*
                     * 云母化：右侧滑出的面板向左投一圈柔和暗边。
                     * 从左边滑出的（平板左侧导航栏那一侧）不投 —— 它贴着屏幕边缘，
                     * 再画一层暗边等于凭空多一条黑缝。
                     */
                    .then(
                        if (fromLeft) {
                            Modifier
                        } else {
                            /*
                             * 2026-11-21 调轻（致辉报「双层阴影」）。
                             *
                             * 面板左边这层 18dp 渐变，叠在遮罩（0.32 黑）上面：
                             * 深色下原来给 0.50，两条暗边叠在一起像描了两次边。
                             * 降一档之后仍能看出版面浮起，但不会出现「黑边上还有一条黑边」。
                             */
                            Modifier.micaSideShadow(alpha = if (isDarkSurface()) 0.34f else 0.16f)
                        },
                    )
                    // 同 Sheet：容器用 micaSheetColor()，卡片才用 micaPanelColor()
                    .background(micaSheetColor())
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
                            AppIcons.Close,
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