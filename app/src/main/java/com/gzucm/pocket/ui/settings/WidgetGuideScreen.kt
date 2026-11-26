package com.gzucm.pocket.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.gzucm.pocket.ui.components.G3CornerShape
import com.gzucm.pocket.ui.components.micaPanel
import com.gzucm.pocket.ui.icons.AppIcons
import com.gzucm.pocket.widget.CurrentInfo
import com.gzucm.pocket.widget.TodayCard
import com.gzucm.pocket.widget.TodayCards
import com.gzucm.pocket.widget.TodayRow
import com.gzucm.pocket.widget.TodayRowState
import com.gzucm.pocket.widget.WeekCellRole
import com.gzucm.pocket.widget.WidgetRender
import com.gzucm.pocket.widget.WidgetSnapshot
import com.gzucm.pocket.widget.WidgetSnapshotData
import java.time.LocalDateTime
import kotlin.math.min

/**
 * 「添加到桌面小组件」教程页。
 *
 * ## 为什么要有这一页
 *
 * 六个部件都是**标准安卓小部件**，但大部分 ROM 把它们埋在「全部应用 → 安卓小组件」
 * 的最底部（小米）、「窗口小工具 / 经典小工具」里（华为荣耀），系统卡片中心第一页
 * 根本看不到。用户按「添加卡片」的直觉去翻，翻不到就会以为 App 没做部件。
 * 这一页把四家主流 ROM 的路径写清楚，再让用户直接看到每个尺寸长什么样。
 *
 * ## 预览为什么不用截图
 *
 * 截图会随版本过期（改一个字就要重拍），而且深色模式对不上。
 * 这里直接调 [WidgetRender] 的纯函数拿**用户自己的今日课表**，再用 Compose
 * 按真机版式画出来 —— 和部件本身共用同一套数据与配色数值（[WidgetPalette]
 * 照抄 `widget_*` 的两套颜色资源），绝不会出现「教程里是一种样子，
 * 桌面上是另一种样子」。
 *
 * 版式口径（和 widget_*.xml 对齐）：24dp 圆角卡片、10dp 内边距、表头 10sp 次色、
 * 课程块用 widget_k1..k8 + 胶囊圆角、圆点用 widget_k{n}_dot、
 * 时间 9sp 次色在上 / 课名 11.5sp 主色在下、「正在上」用 26% 底 + 加粗。
 */
@Composable
fun WidgetGuideScreen(onBack: () -> Unit = {}) {
    val scheme = MaterialTheme.colorScheme
    val ctx = LocalContext.current
    // 只读一次：这一页是静态教程，不跟着时间跳；数据取用户自己的课表快照
    val snapshot = remember { WidgetSnapshot.load(ctx.applicationContext) }
    val now = remember { LocalDateTime.now() }
    var selected by remember { mutableIntStateOf(0) }
    val variant = WidgetPreviewSize.entries[selected]

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 18.dp),
    ) {
        Spacer(Modifier.height(10.dp))

        // 返回（和关于页同一套写法，返回键也是回到设置）
        Row(
            Modifier
                .clip(G3CornerShape(10.dp))
                .clickable(onClick = onBack)
                .padding(vertical = 6.dp, horizontal = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                AppIcons.ArrowBack,
                contentDescription = "返回",
                modifier = Modifier.size(18.dp),
                tint = scheme.onSurfaceVariant,
            )
            Spacer(Modifier.width(6.dp))
            Text("设置", fontSize = 13.sp, color = scheme.onSurfaceVariant)
        }

        Spacer(Modifier.height(10.dp))
        Text(
            "添加到桌面小组件",
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Medium,
        )
        Spacer(Modifier.height(18.dp))

        // ── 1. 说明 ────────────────────────────────────────────────
        Column(
            Modifier
                .fillMaxWidth()
                .micaPanel(14.dp)
                .padding(16.dp),
        ) {
            Text("先说清楚一件事", fontSize = 13.sp, color = scheme.onSurfaceVariant)
            Spacer(Modifier.height(8.dp))
            Text(
                "广中医口袋的小部件是标准安卓小部件，大部分手机把它收在「安卓小部件 / 窗口小工具」里，" +
                    "不在系统卡片中心的第一页 —— 按下面路径找就能看到。",
                fontSize = 12.sp,
                lineHeight = 19.sp,
                color = scheme.onSurface,
            )
        }

        Spacer(Modifier.height(16.dp))
        Text("各机型操作步骤", fontSize = 13.sp, color = scheme.onSurfaceVariant)
        Spacer(Modifier.height(8.dp))

        // ── 2. 四家 ROM 的路径 ────────────────────────────────────
        MachineGuides.forEachIndexed { idx, guide ->
            if (idx > 0) Spacer(Modifier.height(9.dp))
            Column(
                Modifier
                    .fillMaxWidth()
                    .micaPanel(14.dp)
                    .padding(16.dp),
            ) {
                Text(
                    guide.title,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium,
                    color = scheme.onSurface,
                )
                Spacer(Modifier.height(8.dp))
                guide.steps.forEachIndexed { i, step ->
                    if (i > 0) Spacer(Modifier.height(5.dp))
                    Row {
                        Text(
                            "${i + 1}. ",
                            fontSize = 12.sp,
                            lineHeight = 18.sp,
                            color = scheme.primary,
                            fontWeight = FontWeight.Medium,
                        )
                        Text(
                            step,
                            fontSize = 12.sp,
                            lineHeight = 18.sp,
                            color = scheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }

        // ── 3. 尺寸预览 ────────────────────────────────────────────
        Spacer(Modifier.height(20.dp))
        Text("尺寸预览", fontSize = 13.sp, color = scheme.onSurfaceVariant)
        Spacer(Modifier.height(8.dp))

        Row(
            Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            WidgetPreviewSize.entries.forEachIndexed { i, item ->
                val on = i == selected
                Box(
                    Modifier
                        .clip(G3CornerShape(10.dp))
                        .background(
                            if (on) scheme.primary.copy(alpha = 0.16f)
                            else scheme.surfaceVariant.copy(alpha = 0.6f),
                        )
                        .clickable { selected = i }
                        .padding(horizontal = 12.dp, vertical = 9.dp),
                ) {
                    Text(
                        item.label,
                        fontSize = 12.sp,
                        fontWeight = if (on) FontWeight.Medium else FontWeight.Normal,
                        color = if (on) scheme.primary else scheme.onSurfaceVariant,
                    )
                }
            }
        }

        Spacer(Modifier.height(12.dp))

        PreviewFrame(variant = variant, snapshot = snapshot, now = now)

        Spacer(Modifier.height(8.dp))
        Text(
            variant.caption,
            fontSize = 11.5.sp,
            lineHeight = 17.sp,
            color = scheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(4.dp))
        Text(
            "预览按真机比例缩放，实际显示以桌面为准。添加时把部件拖到桌面上，大小还能拖动边缘调整。",
            fontSize = 10.5.sp,
            lineHeight = 15.sp,
            color = scheme.onSurfaceVariant.copy(alpha = 0.75f),
        )

        Spacer(Modifier.height(28.dp))
    }
}

// ── 尺寸文件描述 ────────────────────────────────────────────────────────

/**
 * 六个可添加的尺寸。
 *
 * [widthDp] / [heightDp] 是**小米 14（houji，1200×2670，480dpi，5 列桌面）实测的
 * 格子尺寸**，不是 `appwidget-provider` 里声明的 minWidth / minHeight：
 * 官方那套 AOSP 最小宽度公式（70n-30）偏小（2 列算 110、4 列算 250），真机给的
 * 实际是 150 / 350，按 minWidth 画会让预览里的课名被挤到截断（真机不截断）。
 *
 * 其它机型（4 列桌面、平板、折叠屏）的格子尺寸会有出入，预览只对齐这一台。
 */
private enum class WidgetPreviewSize(
    val label: String,
    val widthDp: Float,
    val heightDp: Float,
    val caption: String,
) {
    Current("2×1 一行", 150f, 89f, "一行显示正在上 / 下一节"),
    Today("2×2 今日", 150f, 173f, "今天剩下的两节"),
    TodayWide("4×2 今日+地点", 350f, 173f, "两节 + 地点老师"),
    Timeline("4×3 今日课程", 350f, 267f, "剩下的课，最多 7 门"),
    ThreeDays("4×3 三日", 350f, 267f, "今天 / 明天 / 后天"),
    Week("4×3 周视图", 350f, 267f, "整周，同一门课跨节连成一块"),
}

private data class MachineGuide(val title: String, val steps: List<String>)

private val MachineGuides = listOf(
    MachineGuide(
        "小米 / Redmi（HyperOS、MIUI）",
        listOf(
            "桌面上双指捏合，或长按空白处进入编辑",
            "点「添加小部件」",
            "点「全部应用」，滑到最底部选「安卓小组件」",
            "找到「广中医口袋」，长按拖到桌面",
        ),
    ),
    MachineGuide(
        "华为 / 荣耀（HarmonyOS、MagicOS）",
        listOf(
            "桌面双指捏合",
            "点「服务卡片」，滑到最底部点「窗口小工具」（MagicOS 7 以上叫「经典小工具」）",
            "找到「广中医口袋」，点按或长按拖到桌面",
        ),
    ),
    MachineGuide(
        "OPPO / 一加（ColorOS）",
        listOf(
            "长按桌面空白处，或双指捏合",
            "点「插件」",
            "找到「广中医口袋」，点「添加」",
        ),
    ),
    MachineGuide(
        "vivo（OriginOS）",
        listOf(
            "长按桌面空白处，或双指捏合后点左下角「添加」",
            "选「原子组件」",
            "找到「广中医口袋」，长按拖到桌面",
        ),
    ),
)

// ── 预览容器 ────────────────────────────────────────────────────────────

/**
 * 「桌面」底板 + 等比缩放后的部件。
 *
 * 缩放 = `min(2.4, 330 / 部件宽)`：2x1 只有 150dp 宽，放大 2.2 倍才看得清文字；
 * 4x3 是 350dp，0.94 倍正好铺满内容宽 330dp。两种都居中放，
 * 外面这圈淡边框表示「桌面上的那一格」。
 */
@Composable
private fun PreviewFrame(
    variant: WidgetPreviewSize,
    snapshot: WidgetSnapshotData?,
    now: LocalDateTime,
) {
    val scheme = MaterialTheme.colorScheme
    val empty = snapshot == null || snapshot.sessions.isEmpty()
    val scale = min(2.4f, 330f / variant.widthDp)
    val w = variant.widthDp * scale
    val h = variant.heightDp * scale

    Box(
        Modifier
            .fillMaxWidth()
            .clip(G3CornerShape(16.dp))
            .background(scheme.surfaceVariant.copy(alpha = 0.22f))
            .border(
                1.dp,
                scheme.onSurfaceVariant.copy(alpha = 0.14f),
                G3CornerShape(16.dp),
            )
            .padding(horizontal = 10.dp, vertical = 18.dp),
        contentAlignment = Alignment.Center,
    ) {
        if (empty) {
            // 没同步过课表：不画空白的部件，直接告诉用户下一步做什么
            Text(
                "先在课表页同步一次课表，这里就能看到你自己的课",
                fontSize = 12.sp,
                lineHeight = 18.sp,
                color = scheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(vertical = 18.dp),
            )
            return@Box
        }

        // 外层按缩放后的尺寸占位，内层保持真机 dp 尺寸再整体缩放
        Box(Modifier.size(w.dp, h.dp)) {
            Box(
                Modifier
                    .align(Alignment.Center)
                    .size(variant.widthDp.dp, variant.heightDp.dp)
                    .graphicsLayer {
                        scaleX = scale
                        scaleY = scale
                    },
            ) {
                when (variant) {
                    WidgetPreviewSize.Current -> PreviewCurrent(
                        w = variant.widthDp,
                        h = variant.heightDp,
                        info = WidgetRender.currentInfo(snapshot, now),
                    )

                    WidgetPreviewSize.Today -> PreviewCards(
                        w = variant.widthDp,
                        h = variant.heightDp,
                        data = WidgetRender.todayCards(snapshot, now),
                        header = null,
                        gapDp = 8,
                    )

                    WidgetPreviewSize.TodayWide -> PreviewCards(
                        w = variant.widthDp,
                        h = variant.heightDp,
                        data = WidgetRender.todayCards(snapshot, now),
                        header = WidgetRender.header(snapshot, now.toLocalDate()),
                        gapDp = 6,
                        // 真机 widget_today_horizontal.xml：maxWidth 108dp（原 72dp 放不下
                        // 「综教楼808（智慧课室）」，真机上被截成「综教楼808（…」）
                        sideMaxWidthDp = 108f,
                    )

                    WidgetPreviewSize.Timeline -> PreviewTimeline(
                        w = variant.widthDp,
                        h = variant.heightDp,
                        snapshot = snapshot,
                        now = now,
                    )

                    WidgetPreviewSize.ThreeDays -> PreviewThreeDays(
                        w = variant.widthDp,
                        h = variant.heightDp,
                        snapshot = snapshot,
                        now = now,
                    )

                    WidgetPreviewSize.Week -> PreviewWeek(
                        w = variant.widthDp,
                        h = variant.heightDp,
                        snapshot = snapshot,
                        now = now,
                    )
                }
            }
        }
    }
}

/**
 * 部件底色（24dp 圆角，和 widget_bg.xml 一致），不带内边距。
 *
 * **必须重置 Text 样式**：`Text(fontSize = 8.5.sp)` 只改字号，行高会继续吃
 * MaterialTheme 的 bodyLarge（24sp）。部件的 XML 里 TextView 没有 lineSpacing，
 * 行高是字体的自然高度 —— 不重置的话 8.5sp 的时间会占 24sp 高，
 * 同块里的课名被挤成一条线（2026-11-26 截图测试里抓到的）。
 */
@Composable
private fun WidgetSurface(
    w: Float,
    h: Float,
    content: @Composable BoxScope.() -> Unit,
) {
    // 注意用 CompositionLocalProvider 直接覆盖，不要用 ProvideTextStyle：
    // 后者会 `LocalTextStyle.current.merge(value)`，未指定的 lineHeight 仍然
    // 继承 bodyLarge 的 24sp，等于没改。
    androidx.compose.runtime.CompositionLocalProvider(
        androidx.compose.material3.LocalTextStyle provides androidx.compose.ui.text.TextStyle.Default,
    ) {
        Box(
            Modifier
                .size(w.dp, h.dp)
                .clip(RoundedCornerShape(24.dp))
                .background(widgetPalette().bg),
            content = content,
        )
    }
}

/** 2x1：圆点 + 时间在上、课名在下 */
@Composable
private fun PreviewCurrent(w: Float, h: Float, info: CurrentInfo) {
    val palette = widgetPalette()
    WidgetSurface(w, h) {
        Box(
            Modifier
                .fillMaxSize()
                .padding(start = 10.dp, end = 10.dp, top = 6.dp, bottom = 6.dp),
            contentAlignment = Alignment.CenterStart,
        ) {
            if (info.emptyText != null) {
                Text(
                    info.emptyText,
                    fontSize = 11.sp,
                    color = palette.textWeak,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            } else {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Dot(6, info.colorIndex)
                    Spacer(Modifier.width(7.dp))
                    Column {
                        Text(
                            info.time,
                            fontSize = 9.sp,
                            color = palette.textDim,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            info.name,
                            fontSize = 11.5.sp,
                            color = palette.text,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        }
    }
}

/** 2x2 / 4x2：上下一共两张胶囊卡（4x2 多一列表头与地点 / 老师） */
@Composable
private fun PreviewCards(
    w: Float,
    h: Float,
    data: TodayCards,
    header: String?,
    gapDp: Int,
    /** 右侧地点 / 老师列的宽度上限，和真机 XML 的 maxWidth 同口径；2x2 没有这一列 */
    sideMaxWidthDp: Float = 0f,
) {
    val palette = widgetPalette()
    WidgetSurface(w, h) {
        Column(
            Modifier
                .fillMaxSize()
                .padding(8.dp),
        ) {
            if (header != null) {
                Text(
                    header,
                    fontSize = 10.sp,
                    color = palette.textDim,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.height(14.dp),
                )
                Spacer(Modifier.height(6.dp))
            }

            if (data.emptyText != null) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(
                        data.emptyText,
                        fontSize = 11.sp,
                        color = palette.textWeak,
                        maxLines = 2,
                        textAlign = TextAlign.Center,
                    )
                }
            } else {
                TodayCardBlock(
                    data.cards.getOrNull(0),
                    showSide = header != null,
                    sideMaxWidthDp = sideMaxWidthDp,
                )
                Spacer(Modifier.height(gapDp.dp))
                TodayCardBlock(
                    data.cards.getOrNull(1),
                    showSide = header != null,
                    sideMaxWidthDp = sideMaxWidthDp,
                )
            }
        }
    }
}

/** 一张课卡。null = 今天只剩一节课，空槽也要留着（否则第一张会被撑满整块） */
@Composable
private fun ColumnScope.TodayCardBlock(
    card: TodayCard?,
    showSide: Boolean,
    sideMaxWidthDp: Float,
) {
    if (card == null) {
        Spacer(Modifier.fillMaxWidth().weight(1f))
        return
    }
    val palette = widgetPalette()
    val i = kIndex(card.colorIndex)
    val ongoing = card.ongoing
    val ink = if (ongoing) palette.kText[i] else palette.text
    val sub = if (ongoing) ink else palette.textDim
    val weak = if (ongoing) ink else palette.textWeak

    Row(
        Modifier
            .fillMaxWidth()
            .weight(1f)
            // 只给底色切圆角、不裁内容：真机的背景 drawable 也不裁子视图，
            // 裁了之后胶囊两端会把「地点 / 老师」的边角切掉（截图里已验证）
            .background(if (ongoing) palette.kNow[i] else palette.kBg[i], RoundedCornerShape(50))
            .padding(start = 10.dp, end = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Dot(7, i)
        Spacer(Modifier.width(7.dp))
        Column(Modifier.weight(1f)) {
            Text(
                card.startText,
                fontSize = 9.sp,
                color = sub,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(1.dp))
            Text(
                card.name,
                fontSize = 11.5.sp,
                color = ink,
                fontWeight = if (ongoing) FontWeight.Bold else FontWeight.Normal,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (showSide) {
            Column(
                Modifier
                    // 真机 widget_today_horizontal.xml 里地点 / 老师是
                    // `marginStart=7dp + wrap_content + android:maxWidth=108dp`，
                    // 所以 padding 必须在 widthIn **外面**：写在里面会把内容宽度
                    // 压成 101dp，「综教楼808（智慧课室）」正好被截成「（智慧课…」
                    .padding(start = 7.dp)
                    .widthIn(max = sideMaxWidthDp.dp),
                horizontalAlignment = Alignment.End,
            ) {
                if (card.room.isNotBlank()) {
                    Text(
                        card.room,
                        fontSize = 9.5.sp,
                        // 显式行高：默认行高在部分字体上比 RemoteViews 的 TextView 高，
                        // 两行加起来会顶到胶囊圆角外（截图里看到的「被切」就是它）
                        lineHeight = 12.sp,
                        color = sub,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                if (card.teacher.isNotBlank()) {
                    Text(
                        card.teacher,
                        fontSize = 9.sp,
                        lineHeight = 11.5.sp,
                        color = weak,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

/** 4x3 今日课程：表头 + 一门课一行（行数按 267dp 的实测格子算，和真机同口径） */
@Composable
private fun PreviewTimeline(
    w: Float,
    h: Float,
    snapshot: WidgetSnapshotData?,
    now: LocalDateTime,
) {
    // 预览按小米 14 实测的 267dp 画，所以这里算到 7 行；桌面上格子更小时部件自己
    // 会算到 4 行 —— 两边同一个算式
    val data = WidgetRender.todayCourses(snapshot, now, WidgetRender.timelineRowsFor(h.toInt()))
    val palette = widgetPalette()

    WidgetSurface(w, h) {
        Column(
            Modifier
                .fillMaxSize()
                .padding(10.dp),
        ) {
            Text(
                WidgetRender.header(snapshot, now.toLocalDate()),
                fontSize = 10.sp,
                color = palette.textDim,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.height(14.dp),
            )
            Spacer(Modifier.height(6.dp))

            Column(Modifier.fillMaxWidth().weight(1f)) {
                if (data.emptyText != null) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text(
                            data.emptyText,
                            fontSize = 11.sp,
                            color = palette.textWeak,
                            maxLines = 2,
                            textAlign = TextAlign.Center,
                        )
                    }
                } else {
                    // 少于 4 门按 4 门排：空槽仍参与分配，卡片不会被一门课撑满
                    val allot = WidgetRender.widgetRowsToAllot(data.rows.size)
                    repeat(allot) { i ->
                        val row = data.rows.getOrNull(i)
                        if (row == null) {
                            Spacer(
                                Modifier
                                    .fillMaxWidth()
                                    .weight(1f)
                                    .padding(top = if (i > 0) 2.dp else 0.dp),
                            )
                        } else {
                            TimelineRowBlock(row, first = i == 0)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ColumnScope.TimelineRowBlock(row: TodayRow, first: Boolean) {
    val palette = widgetPalette()
    val i = kIndex(row.colorIndex)
    val ongoing = row.state == TodayRowState.ONGOING
    val ink = if (ongoing) palette.kText[i] else palette.text
    val sub = if (ongoing) ink else palette.textDim
    val weak = if (ongoing) ink else palette.textWeak

    Row(
        Modifier
            .fillMaxWidth()
            .weight(1f)
            .padding(top = if (first) 0.dp else 2.dp)
            .background(if (ongoing) palette.kNow[i] else palette.kBg[i], RoundedCornerShape(50))
            .padding(start = 10.dp, end = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            row.unitText,
            fontSize = 7.5.sp,
            color = weak,
            maxLines = 1,
            modifier = Modifier.width(22.dp),
        )
        Text(
            row.timeText,
            fontSize = 9.5.sp,
            color = sub,
            maxLines = 1,
            modifier = Modifier.width(40.dp),
        )
        Dot(6, i)
        Text(
            row.name,
            fontSize = 11.5.sp,
            color = ink,
            fontWeight = if (ongoing) FontWeight.Bold else FontWeight.Normal,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier
                .weight(1f)
                .padding(start = 7.dp),
        )
        if (row.room.isNotBlank() || row.teacher.isNotBlank()) {
            Column(
                Modifier
                    // 真机 widget_timeline.xml 的 tl{n}_room / tl{n}_teacher 是
                    // `marginStart=6dp + android:maxWidth=120dp`，padding 要在
                    // widthIn 外面，否则内容只剩 114dp
                    .padding(start = 6.dp)
                    .widthIn(max = 120.dp),
                horizontalAlignment = Alignment.End,
            ) {
                if (row.room.isNotBlank()) {
                    Text(
                        row.room,
                        fontSize = 9.5.sp,
                        lineHeight = 12.sp,
                        color = sub,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                if (row.teacher.isNotBlank()) {
                    Text(
                        row.teacher,
                        fontSize = 9.sp,
                        lineHeight = 11.5.sp,
                        color = weak,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

/** 4x3 三天：今天 / 明天 / 后天三列，每列 4 个等高槽位 */
@Composable
private fun PreviewThreeDays(
    w: Float,
    h: Float,
    snapshot: WidgetSnapshotData?,
    now: LocalDateTime,
) {
    val columns = WidgetRender.threeDays(snapshot, now, 4)
    val palette = widgetPalette()
    val accent = palette.accent
    val text = palette.text
    val dim = palette.textDim
    val weak = palette.textWeak
    val nowMinute = now.hour * 60 + now.minute

    WidgetSurface(w, h) {
        Column(
            Modifier
                .fillMaxSize()
                .padding(10.dp),
        ) {
            Text(
                WidgetRender.header(snapshot, now.toLocalDate()),
                fontSize = 10.sp,
                color = dim,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.height(14.dp),
            )
            Spacer(Modifier.height(6.dp))

            Row(
                Modifier
                    .fillMaxWidth()
                    .weight(1f),
            ) {
                columns.forEachIndexed { c, column ->
                    if (c > 0) Spacer(Modifier.width(6.dp))
                    Column(Modifier.weight(1f)) {
                        // 今天用强调色 + 加粗：三列只有 70dp 宽，靠颜色认「我在哪」
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                column.label,
                                fontSize = 10.5.sp,
                                color = if (column.isToday) accent else text,
                                fontWeight = if (column.isToday) FontWeight.Bold else FontWeight.Normal,
                                maxLines = 1,
                            )
                            Spacer(Modifier.width(4.dp))
                            Text(
                                column.dateText,
                                fontSize = 8.5.sp,
                                color = if (column.isToday) accent else dim,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        Spacer(Modifier.height(4.dp))
                        repeat(4) { i ->
                            val course = if (column.overflow > 0 && i == 3) {
                                null
                            } else {
                                column.courses.getOrNull(i)
                            }
                            val overflowRow = course == null && column.overflow > 0 && i == 3
                            if (i > 0) Spacer(Modifier.height(3.dp))
                            when {
                                course != null -> {
                                    val ci = kIndex(WidgetRender.colorIndexFor(course.name))
                                    val ongoing = nowMinute >= course.startMinute &&
                                        nowMinute < course.endMinute
                                    val ink = if (ongoing) {
                                        palette.kText[ci]
                                    } else {
                                        text
                                    }
                                    Column(
                                        Modifier
                                            .fillMaxWidth()
                                            .weight(1f)
                                            .background(
                                                if (ongoing) palette.kNow[ci] else palette.kBg[ci],
                                                RoundedCornerShape(50),
                                            )
                                            .padding(horizontal = 6.dp),
                                        verticalArrangement = androidx.compose.foundation.layout.Arrangement.Center,
                                    ) {
                                        Text(
                                            course.startText,
                                            fontSize = 8.5.sp,
                                            color = if (ongoing) ink else dim,
                                            maxLines = 1,
                                        )
                                        Text(
                                            course.name,
                                            fontSize = 10.sp,
                                            color = ink,
                                            fontWeight = if (ongoing) FontWeight.Bold else FontWeight.Normal,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                        )
                                    }
                                }

                                i == 0 && column.courses.isEmpty() -> Slot("没课", weak)
                                overflowRow -> Slot("还有 ${column.overflow} 节", weak)
                                // 空槽也要占高：少了它剩下的块会被撑成一大块，三列随之错位
                                else -> Spacer(Modifier.fillMaxWidth().weight(1f))
                            }
                        }
                    }
                }
            }
        }
    }
}

/** 三天视图里的透明文字槽（「没课」/「还有 N 节」） */
@Composable
private fun ColumnScope.Slot(label: String, color: Color) {
    Box(
        Modifier
            .fillMaxWidth()
            .weight(1f),
        contentAlignment = Alignment.CenterStart,
    ) {
        Text(label, fontSize = 10.sp, color = color, maxLines = 1)
    }
}

/** 4x3 周视图：表头 + 星期行 + 1~9 节网格（真机首屏就是这么几行，可往下滚） */
@Composable
private fun PreviewWeek(
    w: Float,
    h: Float,
    snapshot: WidgetSnapshotData?,
    now: LocalDateTime,
) {
    val grid = WidgetRender.weekGrid(snapshot, now)
    val palette = widgetPalette()
    val dim = palette.textDim
    val accent = palette.accent
    // 真机 4x3 的 ListView 首屏显示 1~9 节（第 10~12 节要往下滚），预览也画 9 行，
    // 不再按当前节次截窗 —— 打开时定位到当前节次只是 ListView 的行为，不是版式
    val unitRows = 9
    val nowRow = WidgetRender.currentRow(now)

    WidgetSurface(w, h) {
        Column(
            Modifier
                .fillMaxSize()
                .padding(10.dp),
        ) {
            Text(
                grid.header,
                fontSize = 10.sp,
                color = dim,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.height(14.dp),
            )
            Spacer(Modifier.height(4.dp))

            // 星期 + 日期行（左侧 26dp 与节次列对齐）
            Row(Modifier.fillMaxWidth().height(20.dp)) {
                Spacer(Modifier.width(26.dp))
                grid.weekdays.forEachIndexed { c, label ->
                    val isToday = c == grid.todayCol
                    Column(
                        Modifier
                            .weight(1f)
                            .padding(start = 1.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Text(
                            label,
                            fontSize = 8.sp,
                            color = if (isToday) accent else dim,
                            fontWeight = if (isToday) FontWeight.Bold else FontWeight.Normal,
                            maxLines = 1,
                        )
                        Text(
                            grid.dates.getOrNull(c).orEmpty(),
                            fontSize = 7.5.sp,
                            color = if (isToday) accent else dim,
                            maxLines = 1,
                        )
                    }
                }
            }
            Spacer(Modifier.height(2.dp))

            (1..unitRows).forEach { unit ->
                // 当前节次：整行一条淡色横带 + 节次号变主题色（和 WidgetWeekService 一致）
                val isNow = unit - 1 == nowRow
                Row(
                    Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .background(if (isNow) palette.weekRowNow else Color.Transparent),
                ) {
                    Text(
                        "$unit",
                        fontSize = 8.sp,
                        color = if (isNow) accent else dim,
                        maxLines = 1,
                        modifier = Modifier.width(26.dp),
                    )
                    (0..6).forEach { col ->
                        val cell = grid.cell(unit, col)
                        val hasCourse = cell.colorIndex >= 0
                        val ci = kIndex(cell.colorIndex)
                        Box(
                            Modifier
                                .weight(1f)
                                .fillMaxHeight()
                                .padding(start = 1.dp, top = 1.dp, bottom = 1.dp)
                                .clip(weekCellShape(cell.role))
                                .background(
                                    if (hasCourse) palette.kBg[ci] else Color.Transparent,
                                )
                                .padding(horizontal = 2.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            // 跨节的课只在起始格显示课名，其余格同色留空 —— 连成一块
                            if (cell.isStart && cell.name != null) {
                                Text(
                                    cell.name,
                                    fontSize = 7.5.sp,
                                    lineHeight = 8.5.sp,
                                    color = palette.text,
                                    maxLines = cell.span,
                                    overflow = TextOverflow.Ellipsis,
                                    textAlign = TextAlign.Center,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

/** 连块圆角：首格只圆上面、末格只圆下面、中间不圆 */
private fun weekCellShape(role: WeekCellRole): RoundedCornerShape = when (role) {
    WeekCellRole.SINGLE -> RoundedCornerShape(6.dp)
    WeekCellRole.FIRST -> RoundedCornerShape(topStart = 6.dp, topEnd = 6.dp)
    WeekCellRole.LAST -> RoundedCornerShape(bottomStart = 6.dp, bottomEnd = 6.dp)
    WeekCellRole.MIDDLE -> RoundedCornerShape(0.dp)
}

// ── 配色 / 圆点 ─────────────────────────────────────────────────────────

private const val KCount = 8

/** 越界按环形取，和 WidgetProvider 里的 kIndex 同口径，绝不抛异常 */
private fun kIndex(raw: Int): Int = ((raw % KCount) + KCount) % KCount

@Composable
private fun Dot(diameter: Int, colorIndex: Int) {
    Box(
        Modifier
            .size(diameter.dp)
            .clip(CircleShape)
            .background(widgetPalette().kDot[kIndex(colorIndex)]),
    )
}
