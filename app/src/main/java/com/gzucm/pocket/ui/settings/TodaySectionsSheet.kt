package com.gzucm.pocket.ui.settings

import android.content.Context
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import com.gzucm.pocket.core.TodayBlock
import com.gzucm.pocket.core.TodaySections
import kotlin.math.roundToInt
import com.gzucm.pocket.ui.adaptive.AdaptiveSheet
import com.gzucm.pocket.ui.components.G3CornerShape
import com.gzucm.pocket.ui.components.micaPanel
import com.gzucm.pocket.ui.icons.AppIcons

/**
 * 「今日页显示」面板。
 *
 * 两个入口共用同一个正文（[TodaySectionsContent]）：
 *   · 设置 → 今日页显示（作为设置面板的子页面，和「导出课表数据」同一层）
 *   · 今日页长按任意卡片（作为独立面板弹出）
 *
 * 为什么不做成「只能从设置进」：调这几个开关的时候人就在首页，
 * 跳出去、再跳回来才能看到效果，改一次就不想改第二次了。
 */
@Composable
fun TodaySectionsSheet(
    visible: Boolean,
    onDismiss: () -> Unit,
    sections: TodaySections,
    onChange: (TodaySections) -> Unit,
) {
    AdaptiveSheet(
        visible = visible,
        onDismiss = onDismiss,
        paneTitle = "今日页显示",
        /*
         * 侧栏里**不能带垂直滚动**。
         *
         * 这一页的主要内容是长按拖动排序，而行的手势是
         * `detectDragGesturesAfterLongPress`：手指按下后的轻微抖动一旦超过
         * touch slop，外层 `verticalScroll` 会先把手势拿去滚动，
         * 长按检测当场被取消 —— 表现就是「平板上怎么按都拖不动」
         * （2026-11-22 致辉报的）。手机端走 ModalBottomSheet，本来就没有滚动容器，
         * 所以只有平板出问题。
         *
         * 去掉滚动之后内容必须放得下：下面 `showHeader = !isPane` 会把
         * 侧栏里重复的大标题和说明省掉（顶栏已经写了标题），
         * 剩下的列表 + 天数卡在 600dp 高的横屏上也装得下。
         */
        scrollable = false,
    ) {
        TodaySectionsContent(
            sections = sections,
            onChange = onChange,
            showBack = false,
            // 侧栏顶栏已经有「今日页显示」，正文再来一遍是重复
            showHeader = !com.gzucm.pocket.ui.adaptive.LocalIsWidePane.current,
        )
    }
}

@Composable
fun TodaySectionsContent(
    sections: TodaySections,
    onChange: (TodaySections) -> Unit,
    /** 设置面板里作为子页面时显示「返回设置」；独立面板里不需要 */
    showBack: Boolean = true,
    /**
     * 是否渲染正文里的大标题和那段「关掉的块不会删除任何数据」的说明。
     *
     * 平板侧栏传 false：顶栏 [AdaptiveSheet] 的 paneTitle 已经写了「今日页显示」，
     * 正文再重复一遍既啰嗦又白占高度（侧栏不滚动，高度得省着用）。
     */
    showHeader: Boolean = true,
    onBack: () -> Unit = {},
    /**
     * 这个构建里有没有天气服务（见 [com.gzucm.pocket.core.Weather.enabled]）。
     *
     * 没有 key 时这一行要显式置灰并说明原因 —— 否则用户打开开关、首页毫无变化，
     * 只会认为「开关坏了」。
     */
    weatherAvailable: Boolean = com.gzucm.pocket.core.Weather.enabled,
) {
    val scheme = MaterialTheme.colorScheme

    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp)
            .padding(bottom = 26.dp),
    ) {
        if (showBack) {
            Row(
                Modifier
                    .padding(bottom = 8.dp)
                    .clip(G3CornerShape(10.dp))
                    .clickable(onClick = onBack)
                    .padding(vertical = 6.dp, horizontal = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    AppIcons.ArrowBack,
                    contentDescription = null,
                    modifier = Modifier.size(17.dp),
                    tint = scheme.onSurfaceVariant,
                )
                Spacer(Modifier.width(5.dp))
                Text("返回设置", fontSize = 12.5.sp, color = scheme.onSurfaceVariant)
            }
        }

        if (showHeader) {
            Text(
                "今日页显示",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Medium,
            )
            Spacer(Modifier.height(3.dp))
            Text(
                "关掉的块不会删除任何数据，只是今日页不显示。长按今日页任意卡片也能打开这里。",
                fontSize = 12.5.sp,
                lineHeight = 18.sp,
                color = scheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(16.dp))
        }

        SectionList(
            sections = sections,
            onChange = onChange,
            weatherAvailable = weatherAvailable,
        )

        Spacer(Modifier.height(14.dp))

        // ── 提前天数 ──────────────────────────────────────────────
        Column(
            Modifier
                .fillMaxWidth()
                .micaPanel(14.dp)
                .padding(horizontal = 16.dp, vertical = 13.dp),
        ) {
            /*
             * 标签在左、步进器在右 —— 和上面那组「文字左、控件右」同一条竖线，
             * 第一版把步进器也摆在左下方，右侧空出一大片，看着像没排完。
             */
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text("「接下来」显示范围", fontSize = 13.5.sp, color = scheme.onSurface)
                    Spacer(Modifier.height(2.dp))
                    Text(
                        "往后 ${sections.upcomingDays} 天内的日程和考试（不含今天）",
                        fontSize = 11.5.sp,
                        color = scheme.onSurfaceVariant,
                    )
                }
                Spacer(Modifier.width(12.dp))
                StepButton(
                    text = "−",
                    enabled = sections.upcomingDays > TodaySections.MinDays,
                ) { onChange(sections.copy(upcomingDays = sections.upcomingDays - 1)) }
                Text(
                    "${sections.upcomingDays}",
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Medium,
                    color = scheme.primary,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                    modifier = Modifier.width(34.dp),
                )
                StepButton(
                    text = "+",
                    enabled = sections.upcomingDays < TodaySections.MaxDays,
                ) { onChange(sections.copy(upcomingDays = sections.upcomingDays + 1)) }
            }
        }

        Spacer(Modifier.height(14.dp))

        Text(
            "今日课程不能减 —— 这一页就是给今天用的；不过它可以拖到任意位置。" +
                "其余四块都能减，减掉的会沉到列表最下面，点 + 加回来。",
            fontSize = 11.5.sp,
            lineHeight = 17.sp,
            color = scheme.onSurfaceVariant.copy(alpha = 0.85f),
        )
    }
}

/** 被拖起来那行的圆角。拖动条和背景用同一个，免得两层圆角对不上 */
private val DragRowShape = RoundedCornerShape(10.dp)

/**
 * 抓住拖动条那一下的震动时长（毫秒）。
 *
 * 25ms 是「嗒」一声就停：再短转子马达反应不过来，再长就变成「嗡」，
 * 拖一次烦一次。
 */
private const val GrabVibrationMillis = 25L

/**
 * 取系统振动器。
 *
 * API 31 起 `VIBRATOR_SERVICE` 被 `VibratorManager` 接管（多马达设备得走它
 * 才拿得到默认马达），更老的版本只有老接口，所以两条都写。
 * 拿不到就返回 null，上层 `?.vibrate` 自然跳过 —— 震动是锦上添花，不能因为它崩。
 */
private fun createGrabVibrator(context: Context): Vibrator? =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        context.getSystemService(VibratorManager::class.java)?.defaultVibrator
    } else {
        @Suppress("DEPRECATION")
        context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
    }

/**
 * 五块内容的列表：上面是「显示中」（可拖动排序），下面是「已减掉」（灰字，排最后）。
 *
 * ## 拖动排序为什么手写
 *
 * 只有五项、行高不固定、还要分两段，引一个 `reorderable` 库不划算
 * （那种库基本绑死 `LazyColumn`）。手写的代价就是下面这点几何计算：
 * 量行高 → 越过相邻项一半就换位 → 松手落地。
 *
 * ## 【重要】拖动期间绝不重排 [localOrder]
 *
 * 2026-11-20 第一版是「每换一次位就提交一次」。三个症状一起冒出来：
 *
 *   ① **一次只能拖一格**，手指还没松，拖动就断了；
 *   ② 断了之后 pointer 事件回到外面的 `ModalBottomSheet`，
 *      它的下拉关闭手势接着管 —— **往下一拖，整个面板被关掉**；
 *   ③ 每次提交都重建一遍 `sections`，列表跟着重组，交换全是瞬跳。
 *
 * 根因是 `forEachIndexed` 里没有 key。Compose 只能按「第几个位置」认人，
 * 顺序一变，第 0 个 slot 里的内容就从 A 换成了 B，`pointerInput(block)` 的
 * key 跟着变 → 手势协程被取消重建。此时手指还按在屏幕上，没有新的 down
 * 事件，`awaitFirstDown` 就一直等 —— 表现就是「拖到一格就僵住」。
 *
 * 现在的模型：**拖动只记录「从第几项出发、走了多远」，视觉位置全靠
 * `offset` 表达，松手才真正重排一次。** 顺序不变 → slot 不变 → 手势不断。
 *
 * 另外 [DragState] 是一个 `remember` 出来的对象，`pointerInput` 的 lambda
 * 捕获的永远是同一个实例；如果换成几个裸的 `mutableStateOf`，lambda 里
 * 读的东西变了，Compose 就可能认为 handler 换了、再重启一次手势。
 *
 * ## 动画
 *
 * 让位用 [Animatable] + `spring`：被拖那行跨过谁，谁就平滑地让出高度，
 * 而不是瞬间跳过去。松手时重排索引和归零偏移是同一帧的事，
 * 两者正好抵消 —— 见 [SectionList] 里 `snapTo` 那段注释。
 */
@Composable
private fun SectionList(
    sections: TodaySections,
    onChange: (TodaySections) -> Unit,
    weatherAvailable: Boolean,
) {
    val scheme = MaterialTheme.colorScheme
    /*
     * 组合期取好放在这里，不要写进 pointerInput 的 lambda 体里 ——
     * 它是 remember 出来的同一个实例，闭包捕获它不会让 Compose 认为 handler 换了。
     * handler 一换，手势就会被取消重建，见上面的长注释。
     */
    val context = LocalContext.current
    val vibrator = remember(context) { createGrabVibrator(context) }
    // 外部变了（点 +/−、别处改配置）就跟上；拖动期间不会触发，因为那时没提交
    var localOrder by remember(sections.order) { mutableStateOf(sections.order) }
    val drag = remember { DragState() }

    /*
     * 手势回调里要用最新的 sections / onChange，但不能直接捕获它们 ——
     * 那样每次上层重组都会让 pointerInput 的 lambda 变成新实例。
     * rememberUpdatedState 把「最新值」藏进一个稳定的 State 里，闭包只认它。
     */
    val latestSections by rememberUpdatedState(sections)
    val latestOnChange by rememberUpdatedState(onChange)

    val dragging = drag.from in localOrder.indices && drag.to in localOrder.indices
    val draggedHeight =
        if (dragging) (drag.heights[localOrder[drag.from]] ?: 0).toFloat() else 0f

    Column(Modifier.micaPanel(14.dp)) {
        if (localOrder.isEmpty()) {
            Text(
                "其余四块都减掉了，今日页只剩课程。点下面的 + 可以加回来。",
                fontSize = 12.sp,
                lineHeight = 18.sp,
                color = scheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 16.dp),
            )
        }

        localOrder.forEachIndexed { index, block ->
            // key 让 Compose 按「是哪一块」而不是「第几个位置」认人。
            // 这里拖动期间顺序没变，key 主要是防将来改动踩回同一个坑。
            key(block) {
                val isDragged = dragging && index == drag.from

                /*
                 * 让位：被拖那行要落到的位置和它当前位置之间的行，反向平移一格。
                 * 向下拖（to > from）时，中间那些行上移；向上拖时下移。
                 */
                val shiftTarget = when {
                    !dragging || isDragged -> 0f
                    drag.to > drag.from && index > drag.from && index <= drag.to -> -draggedHeight
                    drag.to < drag.from && index >= drag.to && index < drag.from -> draggedHeight
                    else -> 0f
                }
                val shift = remember { Animatable(0f) }
                LaunchedEffect(shiftTarget, dragging) {
                    if (!dragging) {
                        /*
                         * 松手：偏移直接归零。
                         *
                         * 此刻 localOrder 已经重排过，这一行的「自然位置」正好等于
                         * 它上一步的「自然位置 + shift」。所以把 shift 从 ±h 瞬间
                         * 抹成 0，视觉上是**原地不动**的 —— 既不用动画，也不会跳。
                         * 这里若是 animateTo(0f)，反而会看见它多滑一段。
                         */
                        shift.snapTo(0f)
                    } else {
                        /*
                         * 用 MediumLow（400）而不是 Medium（1500）。
                         * 1500 只跑 120ms 左右，眼睛基本抓不住，「交换」看起来
                         * 还是弹了一下就到位；400 大约 300ms，能看清谁给谁让了位，
                         * 也不至于拖沓到影响下一次拖动。
                         */
                        shift.animateTo(
                            targetValue = shiftTarget,
                            animationSpec = spring(
                                dampingRatio = Spring.DampingRatioNoBouncy,
                                stiffness = Spring.StiffnessMediumLow,
                            ),
                        )
                    }
                }

                Column(
                    Modifier
                        .zIndex(if (isDragged) 1f else 0f)
                        .onGloballyPositioned {
                            // 只在真的变了才写，否则每次布局都 invalidate 一次 state
                            if (drag.heights[block] != it.size.height) {
                                drag.heights[block] = it.size.height
                            }
                        }
                        .offset {
                            /*
                             * 被拖那行跟着手指走（**不做动画**，否则像拖一块橡皮），
                             * 其余行用 shift 平滑让位。
                             * 读在 layout lambda 里，动画每帧只触发重新布局，不触发重组。
                             */
                            IntOffset(
                                0,
                                (if (isDragged) drag.dy else shift.value).roundToInt(),
                            )
                        },
                ) {
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .graphicsLayer {
                                // 拖动中浮起来：阴影像样，别的行也不会从它身上透出来
                                shadowElevation = if (isDragged) 8.dp.toPx() else 0f
                                shape = DragRowShape
                            }
                            .background(
                                color = if (isDragged) scheme.surface else Color.Transparent,
                                shape = DragRowShape,
                            )
                            .padding(start = 8.dp, end = 12.dp, top = 11.dp, bottom = 11.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        DragHandle(
                            /*
                             * testTag 给单测用：平板侧栏里「长按拖动排序」要靠它定位。
                             * 拖动条是 28×34dp 的小热区，按文字找节点再猜偏移量太脆。
                             */
                            modifier = Modifier
                                .testTag("drag_${block.key}")
                                .pointerInput(block) {
                                detectDragGesturesAfterLongPress(
                                    onDragStart = {
                                        /*
                                         * 长按到位这一下弹一下：手指得知道「已经抓住了」，
                                         * 否则拖动条只有 28×34dp，「刚才那 500ms 到底算不算数」
                                         * 全靠猜。
                                         *
                                         * 用 Vibrator 而不是 performHapticFeedback：
                                         * 后者跟着系统的「触摸反馈」开关走，实测这台机器
                                         * `haptic_feedback_enabled = 0`，一声不响 ——
                                         * 而这一下恰恰是拖动唯一的开始信号。
                                         * 静音时也震是刻意的：这是交互确认，不是通知。
                                         */
                                        vibrator?.vibrate(
                                            VibrationEffect.createOneShot(
                                                GrabVibrationMillis,
                                                VibrationEffect.DEFAULT_AMPLITUDE,
                                            )
                                        )
                                        val idx = localOrder.indexOf(block)
                                        if (idx >= 0) {
                                            drag.from = idx
                                            drag.to = idx
                                            drag.dy = 0f
                                        }
                                    },
                                    onDrag = { change, amount ->
                                        // 先消费，再算 —— 不消费的话外层 Sheet 的
                                        // 下拉关闭会同时跟进，两个手势一起动
                                        change.consume()
                                        val from = drag.from
                                        if (from in localOrder.indices) {
                                            /*
                                             * 夹住可拖范围：最多拖到列表两端。
                                             * 不夹的话手指继续往下，这一行会飘出列表外面，
                                             * 看着就像在拖面板本身 —— 也更容易触发误关。
                                             */
                                            var down = 0f
                                            for (i in from + 1..localOrder.lastIndex) {
                                                down += (drag.heights[localOrder[i]] ?: 0).toFloat()
                                            }
                                            var up = 0f
                                            for (i in 0 until from) {
                                                up += (drag.heights[localOrder[i]] ?: 0).toFloat()
                                            }
                                            drag.dy = (drag.dy + amount.y).coerceIn(-up, down)
                                            drag.to = drag.targetIndex(localOrder)
                                        }
                                    },
                                    onDragEnd = {
                                        val from = drag.from
                                        val to = drag.to
                                        if (
                                            from in localOrder.indices &&
                                            to in localOrder.indices &&
                                            from != to
                                        ) {
                                            /*
                                             * 落地调模型自己的 move()，**不要**拿 localOrder
                                             * 拼一个新列表再提上去。
                                             *
                                             * localOrder 是本地副本，可能比上层慢一拍。
                                             * 2026-11-20 实机丢过一次块：本地是 4 项、
                                             * 上层已是 5 项，拿本地的列表去覆盖，
                                             * 多出来那个块就从全量顺序里被抹掉了 ——
                                             * 它既不在显示组也不在隐藏组，界面上凭空消失。
                                             *
                                             * move() 以上层的 order 为基准，两边永远一致。
                                             */
                                            val committed = latestSections.move(from, to)
                                            localOrder = committed.order
                                            latestOnChange(committed)
                                        }
                                        // 重排和归零在同一批 state 里生效，中间不会渲染
                                        drag.reset()
                                    },
                                    onDragCancel = { drag.reset() },
                                )
                            },
                        )

                        Column(Modifier.weight(1f)) {
                            Text(
                                block.label,
                                fontSize = 13.5.sp,
                                color = scheme.onSurface,
                            )
                            Spacer(Modifier.height(2.dp))
                            Text(
                                subtitleOf(block, sections, weatherAvailable),
                                fontSize = 11.5.sp,
                                lineHeight = 16.sp,
                                color = scheme.onSurfaceVariant,
                            )
                        }
                        Spacer(Modifier.width(10.dp))
                        if (block.removable) {
                            ToggleDot(
                                on = true,
                                enabled = block != TodayBlock.WEATHER || weatherAvailable,
                                onClick = { latestOnChange(sections.hide(block)) },
                            )
                        } else {
                            // 课程不可减：摆一个「固定」占住按钮的位置，免得看起来像漏了控件
                            Text(
                                "固定",
                                fontSize = 11.sp,
                                color = scheme.onSurfaceVariant.copy(alpha = 0.6f),
                                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                                modifier = Modifier.width(30.dp),
                            )
                        }
                    }
                    if (index != localOrder.lastIndex) RowDivider()
                }
            }
        }

        // ── 已减掉的一组：间隙更大 + 一条分割，字变灰 ─────────────────
        val hidden = sections.hidden
        if (hidden.isNotEmpty()) {
            if (localOrder.isNotEmpty()) {
                Spacer(Modifier.height(16.dp))
                Box(
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp)
                        .height(1.dp)
                        .background(scheme.outlineVariant.copy(alpha = 0.5f)),
                )
                Spacer(Modifier.height(4.dp))
            }
            hidden.forEachIndexed { i, block ->
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(start = 8.dp, end = 12.dp, top = 11.dp, bottom = 11.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    // 占住拖动条的位置，让标题和上面那一组对齐
                    Spacer(Modifier.width(28.dp))
                    Column(Modifier.weight(1f)) {
                        Text(
                            block.label,
                            fontSize = 13.5.sp,
                            color = scheme.onSurfaceVariant.copy(alpha = 0.75f),
                        )
                        Spacer(Modifier.height(2.dp))
                        Text(
                            subtitleOf(block, sections, weatherAvailable),
                            fontSize = 11.5.sp,
                            lineHeight = 16.sp,
                            color = scheme.onSurfaceVariant.copy(alpha = 0.5f),
                        )
                    }
                    Spacer(Modifier.width(10.dp))
                    ToggleDot(
                        on = false,
                        enabled = block != TodayBlock.WEATHER || weatherAvailable,
                        onClick = { latestOnChange(sections.show(block)) },
                    )
                }
                if (i != hidden.lastIndex) RowDivider(startPadding = 36.dp)
            }
        }
    }
}

/**
 * 拖动过程中的全部临时状态。
 *
 * 为什么不是几个独立的 `remember { mutableStateOf(...) }`：
 * `pointerInput` 的 handler 一旦被 Compose 判定为「换了」，它会取消协程重建。
 * 手指还按在屏幕上，没有新的 down 事件，`awaitFirstDown` 就一直等 ——
 * 表现就是拖动突然僵住。而 handler 是否被认成同一个，看它捕获的东西是不是
 * 同一个实例。全部塞进一个 `remember` 出来的对象里，捕获的只有这一个引用，
 * 重组多少次都相等，手势就不会断。
 */
private class DragState {
    /** 从列表的第几项开始拖。-1 = 没在拖 */
    var from by mutableIntStateOf(-1)

    /** 当前会落到第几项。拖动中实时算，松手时用它重排 */
    var to by mutableIntStateOf(-1)

    /** 跟手位移（px），正数向下 */
    var dy by mutableFloatStateOf(0f)

    /** 每行实测高度（含行间分割线）。键是块本身，不是下标 —— 顺序变了也不会错位 */
    val heights = mutableStateMapOf<TodayBlock, Int>()

    fun reset() {
        from = -1
        to = -1
        dy = 0f
    }

    /**
     * 按累计位移算出「现在应该落在第几项」。
     *
     * 判据是「越过下一项的一半」—— 阈值用**相邻项自己的高度**，不用被拖行的：
     * 行高并不完全一致（副标题有长有短），拿被拖行的高度当尺子，
     * 矮行怎么拖都换不了位。
     */
    fun targetIndex(order: List<TodayBlock>): Int {
        val start = from
        if (start !in order.indices) return -1
        var idx = start
        var rest = dy
        if (dy > 0f) {
            while (idx < order.lastIndex) {
                val h = (heights[order[idx + 1]] ?: 0).toFloat()
                if (h <= 0f || rest < h * 0.5f) break
                rest -= h
                idx++
            }
        } else if (dy < 0f) {
            while (idx > 0) {
                val h = (heights[order[idx - 1]] ?: 0).toFloat()
                if (h <= 0f || -rest < h * 0.5f) break
                rest += h
                idx--
            }
        }
        return idx
    }
}

private fun subtitleOf(
    block: TodayBlock,
    sections: TodaySections,
    weatherAvailable: Boolean,
): String = when (block) {
    TodayBlock.WEATHER ->
        if (weatherAvailable) "现在多少度、未来三天预报；减掉后不再请求天气服务"
        else "这个版本没有配置天气服务"
    TodayBlock.POWER -> "余额、日均用量、还能用几天"
    TodayBlock.UPCOMING -> "未来几天还有哪些日程和考试"
    TodayBlock.WEEK_OVERVIEW -> "这周每天几节课，哪天最忙"
    TodayBlock.COURSE -> "这一页的主体，不能减；想放最上面或最下面直接拖"
    TodayBlock.TASKS -> "今天要交的作业和日程；打勾后变淡留在最下面，第二天自动消失"
}

/** 行间那条极淡的线。比给每行套一张卡安静得多 */
@Composable
private fun RowDivider(startPadding: androidx.compose.ui.unit.Dp = 36.dp) {
    Box(
        Modifier
            .fillMaxWidth()
            .padding(start = startPadding, end = 12.dp)
            .height(1.dp)
            .background(MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f)),
    )
}

/**
 * 左侧的「三」字拖动条。
 *
 * **只有它是长按拖动的热区**，不是整行 —— 行本身还要留给 +/− 和将来的点击，
 * 整行可拖会让「想点一下」变成「不小心拖动」。
 */
@Composable
private fun DragHandle(modifier: Modifier = Modifier) {
    Box(
        modifier
            .size(width = 28.dp, height = 34.dp),
        contentAlignment = Alignment.Center,
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
            repeat(3) {
                Box(
                    Modifier
                        .width(13.dp)
                        .height(1.5.dp)
                        .background(
                            MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.55f)
                        ),
                )
            }
        }
    }
}

/**
 * 右侧那个 −/+ 圆角方块。
 *
 * [on] = 现在显示着 → 画「−」（点它减掉）；否则画「+」（点它加回来）。
 * 用符号而不是 Switch：这一列同时承担「开关」和「排在哪」两件事，
 * Switch 那个滑块会让人下意识去横滑，而这行是纵向拖的。
 */
@Composable
private fun ToggleDot(on: Boolean, enabled: Boolean, onClick: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    Box(
        Modifier
            .size(30.dp)
            .alpha(if (enabled) 1f else 0.4f)
            .clip(G3CornerShape(9.dp))
            .background(
                if (on) scheme.surfaceVariant.copy(alpha = 0.5f)
                else scheme.primary.copy(alpha = 0.14f)
            )
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            if (on) "−" else "+",
            fontSize = 17.sp,
            fontWeight = FontWeight.Medium,
            color = if (on) scheme.onSurfaceVariant else scheme.primary,
        )
    }
}

@Composable
private fun StepButton(text: String, enabled: Boolean, onClick: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    Box(
        Modifier
            .size(32.dp)
            .clip(G3CornerShape(10.dp))
            .background(
                if (enabled) scheme.primary.copy(alpha = 0.14f)
                else scheme.surfaceVariant.copy(alpha = 0.4f)
            )
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text,
            fontSize = 16.sp,
            fontWeight = FontWeight.Medium,
            color = if (enabled) scheme.primary else scheme.onSurfaceVariant.copy(alpha = 0.4f),
        )
    }
}
