package com.gzucm.pocket.ui.settings

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
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
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
 *
 * 为什么不做拖拽排序：致辉 2026-11-20 选方案时也否掉了（编辑模式工作量大，
 * 而今日页现在没有排序需求）。真要排序时再加，别提前做。
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
    ) {
        TodaySectionsContent(
            sections = sections,
            onChange = onChange,
            showBack = false,
        )
    }
}

@Composable
fun TodaySectionsContent(
    sections: TodaySections,
    onChange: (TodaySections) -> Unit,
    /** 设置面板里作为子页面时显示「返回设置」；独立面板里不需要 */
    showBack: Boolean = true,
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

/**
 * 四块内容的列表：上面是「显示中」（可拖动排序），下面是「已减掉」（灰字，排最后）。
 *
 * ## 拖动排序为什么不用第三方库
 *
 * 只有四项、行高不固定，引一个 `reorderable` 依赖不划算，而且那种库通常绑死
 * `LazyColumn`，我们这个列表短、还要分两段。手写的代价就是下面这点代码：
 * 量每行高度 → 拖过半行就换位 → 用 `dragDelta` 补偿，让被拖的那行跟着手指走。
 *
 * ## 拖动中为什么不直接 onChange
 *
 * `onChange` 会改 `sections.order`，进而让整个列表重组。如果每动一次就提交，
 * 手势状态会被打断（`pointerInput` 的 key 变了就重启）。所以拖动期间只改本地
 * [localOrder]，**松手时才提交一次**。
 */
@Composable
private fun SectionList(
    sections: TodaySections,
    onChange: (TodaySections) -> Unit,
    weatherAvailable: Boolean,
) {
    val scheme = MaterialTheme.colorScheme
    // 外部变了（点 +/−、别处改配置）就跟上；拖动期间不会触发，因为那时没提交
    var localOrder by remember(sections.order) { mutableStateOf(sections.order) }
    var dragging by remember { mutableStateOf<TodayBlock?>(null) }
    var dragDelta by remember { mutableFloatStateOf(0f) }
    val heights = remember { mutableStateMapOf<TodayBlock, Int>() }

    fun commit() {
        if (localOrder != sections.order) onChange(sections.copy(order = localOrder))
        dragging = null
        dragDelta = 0f
    }

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
            val isDragging = dragging == block
            Row(
                Modifier
                    .fillMaxWidth()
                    // 拖动中的那行浮在别的行上面，不然会被下一行盖住
                    .zIndex(if (isDragging) 1f else 0f)
                    .offset { IntOffset(0, if (isDragging) dragDelta.roundToInt() else 0) }
                    .onGloballyPositioned { heights[block] = it.size.height }
                    .background(
                        if (isDragging) scheme.surface else androidx.compose.ui.graphics.Color.Transparent
                    )
                    .padding(start = 8.dp, end = 12.dp, top = 11.dp, bottom = 11.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                DragHandle(
                    modifier = Modifier.pointerInput(block) {
                        detectDragGesturesAfterLongPress(
                            onDragStart = {
                                dragging = block
                                dragDelta = 0f
                            },
                            onDrag = { change, drag ->
                                change.consume()
                                dragDelta += drag.y
                                val cur = localOrder.indexOf(block)
                                if (cur < 0) return@detectDragGesturesAfterLongPress
                                val self = (heights[block] ?: 1).toFloat()
                                // 过半行就换位；用「被拖那行自己的高度」当阈值，
                                // 不用相邻行的 —— 相邻行的值在换位前还拿不到
                                if (dragDelta > self * 0.5f && cur < localOrder.lastIndex) {
                                    val other = localOrder[cur + 1]
                                    val oh = (heights[other] ?: 1).toFloat()
                                    val next = localOrder.toMutableList()
                                    next.removeAt(cur)
                                    next.add(cur + 1, block)
                                    localOrder = next
                                    // 换过之后「原始位置」下移了 other 那么高，补偿掉才不跳
                                    dragDelta -= oh
                                } else if (dragDelta < -self * 0.5f && cur > 0) {
                                    val other = localOrder[cur - 1]
                                    val oh = (heights[other] ?: 1).toFloat()
                                    val next = localOrder.toMutableList()
                                    next.removeAt(cur)
                                    next.add(cur - 1, block)
                                    localOrder = next
                                    dragDelta += oh
                                }
                            },
                            onDragEnd = { commit() },
                            onDragCancel = { commit() },
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
                        onClick = { onChange(sections.hide(block)) },
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
                        onClick = { onChange(sections.show(block)) },
                    )
                }
                if (i != hidden.lastIndex) RowDivider(startPadding = 36.dp)
            }
        }
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
