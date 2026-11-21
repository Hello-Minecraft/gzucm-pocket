package com.gzucm.pocket.ui.adaptive

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import com.gzucm.pocket.ui.components.G3CornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsPropertyKey
import androidx.compose.ui.semantics.SemanticsPropertyReceiver
import androidx.compose.ui.semantics.semantics
import kotlinx.coroutines.delay
import com.gzucm.pocket.ui.components.FloatingBarDimAlpha
import com.gzucm.pocket.ui.components.FloatingBarIdleMs

/**
 * 平板端的左侧导航栏。
 *
 * 替代手机端的悬浮底部 Tab —— 平板上横向空间远比纵向值钱，
 * 把导航挪到左侧之后，内容区能一直延伸到屏幕底部。
 *
 * 宽度 76dp：放得下 24dp 图标和两个汉字的标签，
 * 再宽就侵占内容区了。
 */
@Composable
fun AppNavigationRail(
    items: List<NavRailItem>,
    selected: Int,
    onSelect: (Int) -> Unit,
    /** 屏幕底部要留的安全区（手势条），由调用方从 WindowInsets 算好传进来 */
    bottomInset: androidx.compose.ui.unit.Dp = 0.dp,
    /**
     * 底部动作区（同步 / 设置）。
     *
     * 平板上把这些按钮从首页标题栏挪到这里，原因有三个：
     *   ① 标题栏能空出来只放日期，视觉上干净；
     *   ② 侧栏本来就是「全局操作」的落点，设置属于全局操作，放这儿符合直觉；
     *   ③ 拇指在平板左侧握持时，底部是最好够到的位置（顶部要跨屏幕）。
     *
     * 手机端不受影响 —— 那边还是原来的标题栏按钮。
     */
    bottomActions: @Composable () -> Unit = {},
    /**
     * 是否启用「静置自动渐隐」。只有课表页传 true。
     *
     * ## 为什么平板也要这个（2026-11-21 致辉要求）
     *
     * 手机端的悬浮底栏在课表页会静置 3 秒淡到 15%（见 `FloatingBar`）：
     * 课表是拿来「看」的，导航常年占着就是一层干扰。平板横屏时侧栏虽然只占 76dp，
     * 但整条竖着压在最左边，眼睛扫课表时总会被它拽一下。
     *
     * 策略和手机端**一模一样**，参数直接复用同一组常量，不另立一套：
     * - 静置 [FloatingBarIdleMs]（3 秒）→ 淡到 [FloatingBarDimAlpha]（0.15）
     * - 碰一下侧栏任意位置就唤醒，重新计时
     * - 半透明时的第一次点击**只唤醒、不切页**（那时候字几乎看不清，
     *   点下去大概率是「想把导航找回来」，直接切走就是误触）
     *
     * 侧栏仍然占 76dp 宽度，不做收起动画：课表横向要留出翻周手势的空间，
     * 宽度一变整列课会跟着跳，比淡一点更烦人。
     */
    autoHide: Boolean = false,
    modifier: Modifier = Modifier,
) {
    val scheme = MaterialTheme.colorScheme

    /*
     * 渐隐状态机。和 FloatingBar 里的写法保持逐行对应 ——
     * 两处逻辑不一致的话，用户在手机和平板之间切换会觉得「手感变了」。
     */
    var wakeTick by remember { mutableIntStateOf(0) }
    var dimmed by remember { mutableStateOf(false) }
    var dimmedAtDown by remember { mutableStateOf(false) }
    val currentDimmed by rememberUpdatedState(dimmed)
    val currentAutoHide by rememberUpdatedState(autoHide)

    LaunchedEffect(wakeTick, autoHide) {
        dimmed = false
        dimmedAtDown = false
        if (autoHide) {
            delay(FloatingBarIdleMs)
            dimmed = true
        }
    }
    val railAlpha by animateFloatAsState(
        targetValue = if (autoHide && dimmed) FloatingBarDimAlpha else 1f,
        animationSpec = tween(durationMillis = 260, easing = FastOutSlowInEasing),
        label = "navRailAlpha",
    )

    Column(
        modifier = modifier
            .fillMaxHeight()
            .width(NavigationRailWidth)
            /*
             * 触摸热区放在整条侧栏上，不是某个 Tab 上 ——
             * 点图标之间的空隙也算「操作侧栏」，和手机端底栏同一个做法。
             * awaitFirstDown 不 consume，下面各项的 clickable 照常收到点击。
             */
            .pointerInput(autoHide) {
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false)
                    if (currentAutoHide) {
                        dimmedAtDown = currentDimmed
                        wakeTick++
                    }
                }
            }
            .graphicsLayer { alpha = railAlpha }
            .semantics { navRailAlpha = railAlpha }
            .testTag("nav_rail")
            // 极淡的一层底，跟内容区分开但不抢眼
            .background(scheme.surface.copy(alpha = 0.55f))
            /*
             * 状态栏高度必须让开。
             *
             * 实机发现：不加这行，「今日」图标会和系统时间叠在一起 ——
             * App 是 edge-to-edge 的，状态栏是透明的，内容会画到时钟底下。
             * 底部同理，让开手势条。
             */
            .statusBarsPadding()
            .padding(bottom = bottomInset),
        horizontalAlignment = Alignment.CenterHorizontally,
        /*
         * 导航项在侧栏内**垂直居中**。
         *
         * 原来是顶部对齐，在 600dp 高的横屏上四个入口全挤在左上角，
         * 拇指要够到屏幕最上方才点得到 —— 平板上握着两侧，中间的落点最舒服。
         *
         * 用 Arrangement.Center 而不是加权 Spacer：后者会把间距也撑开，
         * 四个入口被拉得很散，看着像四个独立按钮而不是一组导航。
         */
        // 用 Top + 上下 weight 夹住：既居中又给底部动作留出独立位置
        verticalArrangement = Arrangement.spacedBy(6.dp, Alignment.Top),
    ) {
        /*
         * 上下各一个 weight(1f) 的空盒把导航项夹在中间，底部动作区单独贴底。
         *
         * 不能继续只用 Arrangement.Center：那样底部动作会和导航项混在同一组里，
         * 四个 Tab 加两个按钮挤成一列，反而看不出「上面是导航、下面是操作」。
         */
        Spacer(Modifier.weight(1f))

        items.forEachIndexed { index, item ->
            val isSelected = index == selected
            NavRailEntry(
                item = item,
                selected = isSelected,
                // 单测用：平板侧栏的渐隐/唤醒行为要能定位到具体某一项
                modifier = Modifier.testTag("nav_item_$index"),
                onClick = {
                    /*
                     * 半透明时第一次点击只唤醒，不切页 —— 和手机端底栏同一条规则。
                     * 此时 alpha 0.15 的图标几乎看不清，点下去大概率是「想把导航找回来」。
                     */
                    if (!dimmedAtDown) onSelect(index)
                    dimmedAtDown = false
                },
            )
        }

        Spacer(Modifier.weight(1f))

        // 底部动作区：同步 / 设置
        bottomActions()
        Spacer(Modifier.height(10.dp))
    }
}

/** 单测抓手：侧栏当前整体 alpha（渐隐过程中在 0.15~1 之间过渡） */
internal val NavRailAlphaKey = SemanticsPropertyKey<Float>("NavRailAlpha")
internal var SemanticsPropertyReceiver.navRailAlpha by NavRailAlphaKey

/** 一个导航项需要的信息。 */
@androidx.compose.runtime.Immutable
data class NavRailItem(
    val label: String,
    val icon: ImageVector,
)

@Composable
private fun NavRailEntry(
    item: NavRailItem,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val scheme = MaterialTheme.colorScheme
    // 选中态用主题主色的低透明度底 —— 跟随换肤，不写死颜色
    val bg = if (selected) scheme.primary.copy(alpha = 0.16f) else androidx.compose.ui.graphics.Color.Transparent
    val fg = if (selected) scheme.primary else scheme.onSurfaceVariant.copy(alpha = 0.75f)

    Column(
        modifier = modifier
            .width(NavigationRailWidth - 12.dp)
            .height(NavigationRailItemHeight)
            .clip(G3CornerShape(14.dp))
            .background(bg)
            /*
             * 用 selectable 而不是 clickable：
             * 无障碍会把它读成「单选」，TalkBack 能念出「已选中」。
             * 纯 clickable 的话读屏用户不知道自己现在在哪一页。
             */
            .selectable(
                selected = selected,
                onClick = onClick,
                role = Role.Tab,
            ),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(
            item.icon,
            contentDescription = null, // 下方文字已经说了，重复念一遍反而烦
            tint = fg,
            modifier = Modifier.size(24.dp),
        )
        Spacer(Modifier.height(5.dp))
        Text(
            item.label,
            fontSize = 11.5.sp,
            fontWeight = if (selected) FontWeight.Medium else FontWeight.Normal,
            color = fg,
            maxLines = 1,
        )
    }
}
/**
 * 侧栏底部的动作按钮（同步 / 设置）。
 *
 * 和导航项的区别：
 *   · 导航项是「切换页面」，用 selectable + 选中态高亮；
 *   · 动作项是「执行一次」，用 clickable，没有选中态，平时是低调的灰。
 *
 * 尺寸和导航项一致（同宽同高），这样一列排下来视觉上是统一的节奏。
 */
@Composable
fun RailActionButton(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
    /** 显示进度圈代替图标（同步中） */
    spinning: Boolean = false,
    /** 右上角小红点（有新版本） */
    badge: Boolean = false,
) {
    val scheme = MaterialTheme.colorScheme
    val fg = scheme.onSurfaceVariant.copy(alpha = 0.75f)

    Column(
        modifier = Modifier
            .width(NavigationRailWidth - 12.dp)
            .height(NavigationRailItemHeight)
            .clip(G3CornerShape(14.dp))
            .clickable(onClick = onClick),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Box(contentAlignment = Alignment.Center) {
            if (spinning) {
                androidx.compose.material3.CircularProgressIndicator(
                    modifier = Modifier.size(20.dp),
                    strokeWidth = 2.dp,
                    color = scheme.primary,
                )
            } else {
                Icon(
                    icon,
                    contentDescription = null,
                    tint = fg,
                    modifier = Modifier.size(24.dp),
                )
            }
            if (badge) {
                /*
                 * 小红点贴在图标右上角，白描边保证在浅色侧栏上也看得见。
                 *
                 * 不用 Badge 组件是因为它自带动画和底色，
                 * 在这么小的图标上反而糊成一团。
                 */
                Box(
                    Modifier
                        .align(Alignment.TopEnd)
                        .offset(x = 4.dp, y = (-2).dp)
                        .size(7.dp)
                        .clip(androidx.compose.foundation.shape.CircleShape)
                        .background(scheme.error),
                )
            }
        }
        Spacer(Modifier.height(5.dp))
        Text(
            label,
            fontSize = 11.5.sp,
            color = fg,
            maxLines = 1,
        )
    }
}
