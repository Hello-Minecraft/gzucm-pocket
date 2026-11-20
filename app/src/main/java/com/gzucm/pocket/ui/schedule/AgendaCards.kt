package com.gzucm.pocket.ui.schedule

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import com.gzucm.pocket.ui.components.G3CornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.gzucm.pocket.core.AgendaLogic
import com.gzucm.pocket.core.AgendaOccurrence
import com.gzucm.pocket.core.EXAM_COLOR_INDEX
import com.gzucm.pocket.core.taskDueLabel
import com.gzucm.pocket.ui.components.LocalBlurredWallpaper
import com.gzucm.pocket.ui.components.frostedBackdrop
import com.gzucm.pocket.ui.icons.AppIcons
import com.gzucm.pocket.ui.theme.CoursePalette
import com.gzucm.pocket.ui.theme.courseColor

/**
 * 日程的配色。
 *
 * -1 表示自动：按标题散列到课程色板，但**避开课程用的取色口径**是做不到的，
 * 所以额外给一层「虚线边框」来区分来源，光靠颜色不够。
 */
@Composable
internal fun agendaColor(o: AgendaOccurrence): Color = when {
    // 考试统一用主题的 error 色：不管用户给日程挑了哪个颜色，
    // 考试必须是全场最扎眼的那个，否则「明天要考试」会被一堆日程淹没
    o.colorIndex == EXAM_COLOR_INDEX -> MaterialTheme.colorScheme.error
    // 逾期的截止任务也用 error 色 —— 和考试同级扎眼，提醒「这事该做了」
    o.isTask && o.doneAt == null && o.deadlineAt?.let { it < System.currentTimeMillis() } == true ->
        MaterialTheme.colorScheme.error
    o.colorIndex in CoursePalette.indices -> CoursePalette[o.colorIndex]
    else -> courseColor(o.title)
}

/** 角标文案：考试 / 截止任务 / 待办 / 日程。角标颜色沿用卡片主色 */
internal fun agendaBadgeText(o: AgendaOccurrence): String = when {
    o.colorIndex == EXAM_COLOR_INDEX -> "考试"
    // 班级来的作业单独一档：导入了两份清单时，一眼看出哪条该找谁问
    o.origin.startsWith("class:") -> "班级"
    o.isTask && o.deadlineAt != null -> "截止任务"
    o.isTask -> "待办"
    else -> "日程"
}

/**
 * 截止任务的勾选圈。**受控组件**：完成状态从 `isDone` 读，不在内部存。
 *
 * 交互（致辉 2026-11-17 两轮定稿）：
 *   未完成点一下 → 震动 + 对钩 scale 弹一下 → 立即回调写 doneAt
 *   → 卡片不变淡不消失，沉到列表最下方，第二天自动不见。
 *   已完成点一下 → 直接取消完成（回到未完成），同样震动。
 *
 * 为什么状态必须外部传：
 *   · 卡片完成后**留在列表里**，内部 `var done=true` 会让它再也点不回来；
 *   · 列表按位置复用组合时，内部状态会串到下一张卡上（踩过，见 PITFALLS 第十二节）。
 *
 * 震动走 [LocalHapticFeedback]（系统触摸反馈，无需 VIBRATE 权限），
 * 尊重用户在系统里关掉触摸反馈的偏好 —— 比直接调 Vibrator 稳妥。
 */
@Composable
internal fun TaskCheckCircle(isDone: Boolean, onToggle: () -> Unit) {
    val haptic = LocalHapticFeedback.current
    val scheme = MaterialTheme.colorScheme
    // 只在「刚勾上」这一下弹一下；撤销回来不弹
    var popped by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(
        targetValue = if (popped) 1.2f else 1f,
        animationSpec = tween(160),
        label = "check-scale",
    )
    Box(
        Modifier
            .size(30.dp)
            .scale(scale)
            .clip(androidx.compose.foundation.shape.CircleShape)
            .background(
                if (isDone) scheme.primary.copy(alpha = 0.85f) else Color.Transparent,
            )
            .border(
                1.8.dp,
                if (isDone) Color.Transparent else scheme.onSurfaceVariant.copy(alpha = 0.55f),
                androidx.compose.foundation.shape.CircleShape,
            )
            .clickable {
                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                if (!isDone) popped = true
                onToggle() // 即时回调，不依赖组合存活
            },
        contentAlignment = Alignment.Center,
    ) {
        androidx.compose.material3.Icon(
            AppIcons.Check,
            contentDescription = if (isDone) "已完成，点此取消" else "标记完成",
            tint = if (isDone) scheme.onPrimary else scheme.onSurfaceVariant.copy(alpha = 0.7f),
            modifier = Modifier.size(15.dp),
        )
    }
}

/**
 * 周课表网格里的日程块。
 *
 * **刻意不走 placeSessions**：课程的重叠分簇是按节次区间算的，日程是真实分钟，
 * 混进去会改变 cols 数量，把原本能读清的课名压成色标。日程单独一层画，
 * 用 zIndex 叠在课程块上面、半透明底色 + 虚线边框，
 * 冲突时能同时看到两边，又不破坏课程排布。
 *
 * 位置由 [AgendaGeometry.minuteToRow] 算出的浮点行号决定（乘 cellHeight 得 y），
 * 不依赖 slotIndex，所以落在课间 / 午休 / 21:20 之后的日程也能画出来。
 */
@Composable
internal fun AgendaGridBlock(
    occurrence: AgendaOccurrence,
    startRow: Float,
    endRow: Float,
    cellHeight: Dp,
    width: Dp,
    /** 同格已有几条日程：错开一点点，别完全盖住 */
    layer: Int = 0,
    /**
     * 紧凑模式。
     *
     * 两种情况必须走它，否则会破坏课程可读性：
     *   ① 与课程时段重叠 —— 满宽卡片会把课名整个盖住（实测「五官学」被压得只剩一个字）
     *   ② 落在课间 / 午休的空档 —— 空档在网格里只占 0.2 行，换算出来只有 6dp 高，
     *      按正常卡片画就是一条看不清的细线（实测午休的「部门例会」）
     * 紧凑模式固定 26dp 高、单行标题、底色更实，像一条贴在时段起点的小标签。
     */
    compact: Boolean = false,
    /**
     * 收窄靠右。
     *
     * 用于「和课程抢同一块地方」的情况：手机上一列只有 52dp，
     * 满宽标签会把课名整个盖掉（实测「五官学」被压成「五官…」）。
     * 收窄到 62% 靠右之后，左侧还能读出课名，右侧也能看到日程的标题和时间。
     * （52% 时文字区只剩 19dp，「经典等级考」要折 3 行、时间还会被裁一位。）
     */
    narrow: Boolean = false,
    /**
     * 时段重叠时的层号 / 总层数（多条日程或考试撞在同一段时间）。
     *
     * 排布规则跟课程一致：手机层叠、宽屏并排，由调用方决定走哪条；
     * 这里只负责按层号算尺寸和透明度。
     */
    layerIndex: Int = 0,
    layerCount: Int = 1,
    /** 宽屏档位（≥840dp）：重叠时并排而不是层叠 */
    wide: Boolean = false,
    /** 层叠时是否最上面那层 —— 只有它显示完整内容，下面的只留首字 */
    onTop: Boolean = true,
    onClick: () -> Unit,
) {
    val color = agendaColor(occurrence)
    val scheme = MaterialTheme.colorScheme
    val spanDp = ((endRow - startRow) * cellHeight.value).dp
    val height = if (compact) 26.dp else spanDp.coerceAtLeast(34.dp)
    val shift = (layer % 3) * 3
    val inset = (layer % 3) * 3
    val fullWidth = if (narrow) {
        (width * 0.62f).coerceAtLeast(18.dp)
    } else {
        (width - (2 + inset).dp).coerceAtLeast(16.dp)
    }
    val cardWidth: Dp
    val xOffset: Dp
    when {
        // 宽屏并排：n 条各占 1/n，中间留 1dp 缝
        layerCount > 1 && wide -> {
            val per = (width - 1.dp) / layerCount
            cardWidth = (per - 1.dp).coerceAtLeast(14.dp)
            xOffset = per * layerIndex + 1.dp
        }
        // 手机层叠：每往上一层右边缩 3dp（课程卡片就是这个手感）
        layerCount > 1 -> {
            val insetDp = (3 * layerIndex).dp
            cardWidth = (width - 2.dp - insetDp).coerceAtLeast(16.dp)
            xOffset = 1.dp + insetDp
        }
        else -> {
            cardWidth = fullWidth
            xOffset = if (narrow) (width * 0.38f) else (1 + shift).dp
        }
    }
    // 虚线边框：和课程块的实线边框区分开，一眼能看出这不是教务排的课
    val dash = androidx.compose.ui.graphics.PathEffect.dashPathEffect(floatArrayOf(7f, 5f), 0f)

    Box(
        Modifier
            .offset(x = xOffset, y = cellHeight.value.dp * startRow)
            .size(width = cardWidth, height = height)
            .clip(G3CornerShape(7.dp))
            .frostedBackdrop(bitmap = LocalBlurredWallpaper.current)
            .background(
                color.copy(
                    alpha = when {
                        // 层叠时下层压淡，一眼能看出上面还压着一张
                        layerCount > 1 && !onTop -> 0.14f
                        layerCount > 1 -> 0.32f
                        compact -> 0.55f
                        else -> 0.22f
                    },
                ),
            )
            .drawBehind {
                drawRoundRect(
                    color = color.copy(alpha = 0.95f),
                    cornerRadius = androidx.compose.ui.geometry.CornerRadius(7.dp.toPx()),
                    style = androidx.compose.ui.graphics.drawscope.Stroke(
                        width = 1.2.dp.toPx(),
                        pathEffect = dash,
                    ),
                )
            }
            .clickable(onClickLabel = "查看日程 ${occurrence.title}", onClick = onClick)
            // 左右只留 3dp：手机一格本来就只有 52dp，之前左色条 + 间距吃掉 7dp，
            // 导致「14:30」被裁成「4:30」。色条已去掉，靠虚线边框区分类别就够了。
            .padding(horizontal = 3.dp, vertical = 2.dp),
    ) {
        /*
         * 行数按**实际高度**给，不是固定 2 行。
         *
         * 课程块就是这么做的（跨 6 节给 20 行），所以「中医妇科学」能竖着折成 3 行读完。
         * 日程块之前写死 maxLines = 2，14:30–17:30 这种跨三小时的块明明有 100dp 高，
         * 却只肯显示两行，剩下的字全变省略号。
         *
         * 分配顺序：**标题 > 起止时间 > 地点**。
         * 标题是认「这是什么」的唯一线索；时间决定要不要点进去；地点最次要，
         * 所以只有行数还宽裕时才给（6 行以上）。矮块宁可不显示地点，
         * 也不要挤掉标题 —— 点开详情随时能看全。
         */
        val budget = ((height - 2.dp) / 11.5.dp).toInt().coerceAtLeast(1)
        val timeLines = when {
            compact -> 0
            budget >= 5 -> 2
            budget >= 3 -> 1
            else -> 0
        }
        val placeLines = when {
            compact || occurrence.place.isBlank() -> 0
            // 高块给两行：中文教室名普遍 6~8 个字（「新课室楼301」），
            // 一行只能塞 5 个字，必然变省略号，白瞎了下面还空着的几十 dp
            budget - timeLines >= 7 -> 2
            budget - timeLines >= 4 -> 1
            else -> 0
        }
        val titleLines = (budget - timeLines - placeLines).coerceAtLeast(1)

        if (!onTop) {
            /*
             * 被压住的层：不画完整内容（放不下），但也不能是纯色块 ——
             * 给标题首字，能认出是哪一条，也知道点开有内容。
             * 跟课程卡片 showText=false 的处理保持一致。
             */
            Text(
                occurrence.title.take(1),
                fontSize = 9.sp,
                fontWeight = FontWeight.Medium,
                color = scheme.onSurface.copy(alpha = 0.9f),
                maxLines = 1,
                overflow = TextOverflow.Clip,
            )
            return@Box
        }

        Column(Modifier.fillMaxSize()) {
            Text(
                occurrence.title,
                fontSize = 9.5.sp,
                lineHeight = 11.sp,
                fontWeight = FontWeight.Medium,
                color = scheme.onSurface,
                maxLines = titleLines,
                overflow = TextOverflow.Ellipsis,
            )
            if (placeLines > 0) {
                Text(
                    occurrence.place,
                    fontSize = 8.sp,
                    lineHeight = 9.5.sp,
                    color = scheme.onSurfaceVariant.copy(alpha = 0.9f),
                    maxLines = placeLines,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (timeLines > 0) {
                Spacer(Modifier.height(1.5.dp))
                /*
                 * 起止时间**竖排两行**，不写「08:30-11:00」。
                 *
                 * 横向一行要 11 个字符 ≈ 44dp，而窄列文字区只有 22dp 左右，
                 * 必然被裁（之前就是「14:30」→「4:30」）。竖排每行 5 个字符 ≈ 20dp，
                 * 最窄的 62% 收窄列也放得下。
                 */
                Text(
                    AgendaLogic.hhmm(occurrence.startMinute),
                    fontSize = 8.sp,
                    lineHeight = 9.5.sp,
                    color = scheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Clip,
                )
                if (timeLines >= 2) {
                    Text(
                        AgendaLogic.hhmm(occurrence.endMinute),
                        fontSize = 8.sp,
                        lineHeight = 9.5.sp,
                        color = scheme.onSurfaceVariant.copy(alpha = 0.75f),
                        maxLines = 1,
                        overflow = TextOverflow.Clip,
                    )
                }
            }
        }
    }
}

/**
 * 日视图 / 今日页里的日程卡片。
 *
 * 和课程卡片的区别：左侧色条更粗、右上角一个「日程」小字，
 * 一眼能看出这不是教务排的课。
 */
@Composable
internal fun AgendaDayCard(
    occurrence: AgendaOccurrence,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    /** 非 null 表示这是可勾选的截止任务 / 待办；普通日程和考试传 null */
    onTaskCheck: (() -> Unit)? = null,
) {
    val color = agendaColor(occurrence)
    val scheme = MaterialTheme.colorScheme
    val done = occurrence.isTask && occurrence.doneAt != null
    val overdue = occurrence.isTask && occurrence.deadlineAt?.let {
        it < System.currentTimeMillis() && occurrence.doneAt == null
    } == true
    /*
     * 已完成的整卡压到 45% 透明度（致辉 2026-11-17 要的「变淡」）：
     * 不删不闪，留在最下方当"刚做完"的回执；第二天自动消失。
     * 不用 alpha 叠在背景色上而是整卡 graphicsLayer —— 文字一起淡，才是"作废"的观感。
     */
    val dim = if (done) 0.45f else 1f
    Box(
        modifier
            .fillMaxWidth()
            .graphicsLayer { alpha = dim }
            .clip(G3CornerShape(12.dp))
            .frostedBackdrop(bitmap = LocalBlurredWallpaper.current)
            .background(color.copy(alpha = 0.12f))
            .border(1.dp, color.copy(alpha = 0.55f), G3CornerShape(12.dp))
            .clickable(onClickLabel = "查看日程 ${occurrence.title}", onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier
                    .width(4.dp)
                    .height(40.dp)
                    .clip(G3CornerShape(2.dp))
                    .background(color),
            )
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        occurrence.title,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Medium,
                        color = scheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        textDecoration = if (done) {
                            androidx.compose.ui.text.style.TextDecoration.LineThrough
                        } else null,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    Spacer(Modifier.width(6.dp))
                    /*
                     * 角标四态：考试 / 截止任务 / 待办 / 日程。
                     * 文字最稳 —— 颜色、虚线在通知、截图、色觉障碍几处都靠不住。
                     */
                    Text(
                        agendaBadgeText(occurrence),
                        fontSize = 9.sp,
                        color = color,
                        modifier = Modifier
                            .clip(G3CornerShape(4.dp))
                            .background(color.copy(alpha = 0.16f))
                            .padding(horizontal = 4.dp, vertical = 1.dp),
                    )
                }
                Spacer(Modifier.height(4.dp))
                if (occurrence.isTask) {
                    // 任务：显示截止倒计时，逾期标红置顶（逾期状态由列表排序保证）
                    Text(
                        buildString {
                            append(taskDueLabel(occurrence.deadlineAt, System.currentTimeMillis()))
                            // 班级来的：补一句「来自 张三」，两份清单混在一起时能分清
                            if (occurrence.origin.startsWith("class:")) {
                                append(" · 来自 ")
                                append(occurrence.origin.removePrefix("class:"))
                            }
                        },
                        fontSize = 11.sp,
                        color = if (overdue) scheme.error else scheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                } else {
                    val timeText = AgendaLogic.hhmm(occurrence.startMinute) +
                        " — " + AgendaLogic.hhmm(occurrence.endMinute)
                    val extra = if (occurrence.unitStart > 0) {
                        val span = if (occurrence.unitEnd > occurrence.unitStart) {
                            "-${occurrence.unitEnd}"
                        } else ""
                        " · 第 ${occurrence.unitStart}$span 节"
                    } else ""
                    Text(
                        timeText + extra +
                            (if (occurrence.place.isBlank()) "" else " · ${occurrence.place}"),
                        fontSize = 11.sp,
                        color = scheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                /*
                 * 第二行：考试类型 + 座位号（致辉 2026-11-14 要的）。
                 * 座位对考试是刚需（进场要对号入座），所以只要拿到了就一定显示。
                 */
                val noteText = listOf(
                    occurrence.note,
                    if (occurrence.seat.isNotBlank()) "座位 ${occurrence.seat}" else "",
                ).filter { it.isNotBlank() }.joinToString(" · ")
                if (noteText.isNotBlank()) {
                    Text(
                        noteText,
                        fontSize = 10.5.sp,
                        color = scheme.onSurfaceVariant.copy(alpha = 0.75f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            // 截止任务右侧勾选圈：点击播对钩动画 + 震动，回调由 ViewModel 标记完成
            if (onTaskCheck != null) {
                Spacer(Modifier.width(10.dp))
                TaskCheckCircle(isDone = done, onToggle = onTaskCheck)
            }
        }
    }
}
