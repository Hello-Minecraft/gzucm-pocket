package com.gzucm.pocket.ui.power

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material3.Icon
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.font.FontWeight
import kotlin.math.roundToInt
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.Canvas
import com.gzucm.pocket.core.PowerDaily
import com.gzucm.pocket.ui.components.LocalFloatingBarInset
import com.gzucm.pocket.ui.components.Fmt
import com.gzucm.pocket.core.PowerInfo

@Composable
fun PowerScreen(
    power: PowerInfo?,
    daily: List<PowerDaily>,
    loading: Boolean,
    error: String? = null,
    onSync: () -> Unit = {},
    powerAlertOn: Boolean = true,
    onPowerAlertChange: (Boolean) -> Unit = {},
    onOpenRebindGuide: () -> Unit = {},
) {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    // 提到这里：之前挂在 LazyColumn 的 item 里，卡片滚出屏幕会被销毁，
    // 面板会突然消失，状态也跟着丢
    var showWhy by remember { mutableStateOf(false) }

    if (power == null) {
        Column(
            Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .padding(horizontal = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            if (loading) {
                CircularProgressIndicator(Modifier.size(28.dp), strokeWidth = 2.5.dp)
                Spacer(Modifier.height(14.dp))
                Text(
                    "正在读取电费…",
                    fontSize = 12.5.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                Text(
                    if (error != null) "电费没读出来" else "暂时读不到电费数据",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Medium,
                )
                if (error != null) {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        error,
                        fontSize = 11.5.sp,
                        lineHeight = 17.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                    )
                }
                Spacer(Modifier.height(20.dp))

                // 主入口：用 WebView 走一遍，cookie 由浏览器自己管，不会搬错
                Box(
                    Modifier
                        .clip(RoundedCornerShape(12.dp))
                        .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.15f))
                        .clickable(onClick = onSync)
                        .padding(horizontal = 20.dp, vertical = 10.dp),
                ) {
                    Text(
                        "同步校园卡",
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Medium,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
                Spacer(Modifier.height(10.dp))
                Text(
                    "会打开学校的一卡通页面，登录一次即可",
                    fontSize = 10.5.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                )
            }
        }
        return
    }

    val avg7 = daily.takeLast(7).filter { it.usage > 0 }.let { l ->
        if (l.isEmpty()) 0.0 else l.sumOf { it.usage } / l.size
    }

    /*
     * 上次更新时间要提到布局之前算。
     *
     * 原来它写在 LazyColumn 的 item 里，两套布局（左右分栏 / 单列）
     * 都要用同一个值 —— 留在 item 里就得算两遍，而且分栏后左列单独滚动，
     * item 复用时机不一样，值可能不一致。
     */
    val updatedText = remember(power) {
        val ts = com.gzucm.pocket.core.SessionStore(ctx.applicationContext).powerCheckedAt
        if (ts <= 0) "" else {
            val fmt = java.text.SimpleDateFormat("MM-dd HH:mm", java.util.Locale.CHINA)
            "更新于 " + fmt.format(java.util.Date(ts))
        }
    }

    /*
     * 电费设置面板挂在页面最外层。
     *
     * 它内部是「全屏遮罩 + 侧栏」，之前塞在余额卡的 Column 里，
     * 平板上一点开就把卡片撑满整列，看上去是一大块绿色渐变铺满左栏。
     * 面板本身是覆盖层，本来就不该参与卡片的排版。
     */
    Box(Modifier.fillMaxSize()) {
    BoxWithConstraints(
        Modifier
            .fillMaxSize()
            .statusBarsPadding(),
    ) {
    val wide = maxWidth >= 840.dp
    val hPad = if (wide) 32.dp else 18.dp

    if (wide) {
        /*
         * 宽屏：左右分栏。左边**不滚动**、三块按可用高度均分撑满；右边独立滚动。
         *
         * 左列三块（表头 / 余额卡 / 趋势卡）的分配：
         *   表头是固定高度，不参与分配；
         *   余额卡按内容实测高度（它有自己的内边距和固定字号，硬拉会变形）；
         *   趋势卡吃掉剩下的全部高度，内部再把「折线」和「差值柱」按比例分。
         *
         * 为什么用 BoxWithConstraints 量高度而不是权重均分：
         * 余额卡的高度由字号和内边距决定，不是弹性容器 ——
         * 用 weight 分它会把它拉长，中间留一片空白反而更难看。
         * 量出来它真实占多少，剩下的全给趋势图，才真的「撑满」。
         */
        Column(
            Modifier
                .fillMaxSize()
                .padding(horizontal = hPad),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            PowerHeader(updatedText = updatedText, onSync = onSync)

            Row(
                Modifier
                    .fillMaxWidth()
                    .weight(1f),
                horizontalArrangement = Arrangement.spacedBy(24.dp),
            ) {
                Column(
                    Modifier
                        .weight(1.12f)
                        .fillMaxHeight(),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    BalanceCard(
                        power = power,
                        avg7 = avg7,
                        onOpenWhy = { showWhy = true },
                    )
                    if (daily.size >= 3) {
                        // 趋势卡吃掉剩余高度，内部两图再按 62:38 分
                        Box(Modifier.weight(1f)) {
                            TrendCard(
                                daily = daily,
                                fillHeight = true,
                                showDelta = true,
                            )
                        }
                    }
                }

                LazyColumn(
                    Modifier
                        .weight(1f)
                        .fillMaxHeight(),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(
                        bottom = LocalFloatingBarInset.current + 12.dp,
                    ),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    item {
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .padding(bottom = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                "每日明细",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Medium,
                            )
                            Spacer(Modifier.weight(1f))
                            Text(
                                "共 ${daily.size} 天",
                                fontSize = 10.5.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    items(daily.reversed(), key = { it.date }) { d ->
                        DailyRow(day = d)
                    }
                }
            }
        }
    } else {
        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(
                start = hPad, end = hPad, top = 12.dp,
                bottom = LocalFloatingBarInset.current + 12.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item { PowerHeader(updatedText = updatedText, onSync = onSync) }
            item {
                BalanceCard(
                    power = power,
                    avg7 = avg7,
                    onOpenWhy = { showWhy = true },
                )
            }
            if (daily.size >= 3) {
                item { TrendCard(daily = daily) }
            }
            item {
                Text(
                    "每日明细",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier.padding(top = 6.dp, bottom = 2.dp),
                )
            }
            items(daily.reversed(), key = { it.date }) { d ->
                DailyRow(day = d)
            }
        }
    }
    } // BoxWithConstraints

    PowerWhySheet(
        visible = showWhy,
        roomLabel = power.roomLabel,
        onDismiss = { showWhy = false },
        alertOn = powerAlertOn,
        onAlertChange = onPowerAlertChange,
        // 先收起设置面板：两层面板叠在一起会互相挡
        onBuyInMiniProgram = { showWhy = false; onOpenRebindGuide() },
    )
    } // Box
}

/** 表头：宿舍电量标签 + 上次更新时间 + 手动更新入口。 */
@Composable
private fun PowerHeader(updatedText: String, onSync: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    Row(
        Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            "宿舍电量",
            fontSize = 13.sp,
            color = scheme.onSurfaceVariant,
        )
        Spacer(Modifier.weight(1f))
        if (updatedText.isNotEmpty()) {
            Text(
                updatedText,
                fontSize = 9.5.sp,
                color = scheme.onSurfaceVariant.copy(alpha = 0.55f),
                modifier = Modifier.padding(end = 2.dp),
            )
            Spacer(Modifier.width(4.dp))
        }
        Row(
            Modifier
                .clip(RoundedCornerShape(8.dp))
                .clickable(onClick = onSync)
                .padding(horizontal = 9.dp, vertical = 5.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                Icons.Rounded.Refresh,
                contentDescription = null,
                modifier = Modifier.size(14.dp),
                tint = scheme.primary,
            )
            Spacer(Modifier.width(4.dp))
            Text(
                "更新",
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium,
                color = scheme.primary,
            )
        }
    }
}

/**
 * 余额卡：房号 + 大号余额 + 可用天数。
 *
 * 「电费设置」的入口和它自己的面板都放在这里 ——
 * 面板状态 `showWhy` 由外层持有，因为这个卡在两套布局里都要用，
 * 状态不能跟着卡片一起重建。
 */
@Composable
private fun BalanceCard(
    power: PowerInfo,
    avg7: Double,
    onOpenWhy: () -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(
                Brush.linearGradient(
                    listOf(
                        scheme.primary.copy(alpha = 0.14f),
                        scheme.surface,
                    ),
                ),
            )
            .padding(horizontal = 20.dp, vertical = 20.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                power.roomLabel,
                style = MaterialTheme.typography.bodySmall,
                color = scheme.onSurfaceVariant,
            )
            Spacer(Modifier.width(10.dp))
            Row(
                Modifier
                    .clip(RoundedCornerShape(6.dp))
                    .clickable { onOpenWhy() }
                    .padding(horizontal = 3.dp, vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // 原来的「!」圆圈换成调节图标，跟「设置」这个语义更搭
                Icon(
                    Icons.Rounded.Tune,
                    contentDescription = null,
                    modifier = Modifier.size(13.dp),
                    tint = scheme.onSurfaceVariant.copy(alpha = 0.65f),
                )
                Spacer(Modifier.width(4.dp))
                Text(
                    "电费设置",
                    fontSize = 10.5.sp,
                    color = scheme.onSurfaceVariant,
                    textDecoration = TextDecoration.Underline,
                )
            }
        }
        Spacer(Modifier.height(10.dp))
        Row(verticalAlignment = Alignment.Bottom) {
            Text(
                formatNum(power.balance),
                fontSize = 44.sp,
                fontWeight = FontWeight.Medium,
                letterSpacing = (-1.5).sp,
                color = scheme.primary,
            )
            Spacer(Modifier.width(6.dp))
            Text(
                power.unit,
                fontSize = 15.sp,
                color = scheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 8.dp),
            )
        }
        if (avg7 > 0) {
            Spacer(Modifier.height(6.dp))
            Text(
                "近 7 天日均 ${Fmt.d2(avg7)} 度 · " + run {
                    val days = power.balance / avg7
                    when {
                        days < 1 -> "不足 1 天，建议充值"
                        days > 999 -> "可用很久"
                        else -> "可用约 ${days.toInt()} 天"
                    }
                },
                style = MaterialTheme.typography.bodySmall,
                color = scheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * 用电趋势卡片：折线 + 相对日均的差值柱。
 *
 * [chartHeight] 让宽屏把它拉高 —— 平板上左列内容本来就少，
 * 图矮矮一条会显得整页空荡荡。
 *
 * 差值柱是致辉要的：折线只告诉你「用了多少」，
 * 差值柱直接说「这天比平时多用了还是少用了」，一眼就能看出异常天。
 */
@Composable
private fun TrendCard(
    daily: List<PowerDaily>,
    chartHeight: androidx.compose.ui.unit.Dp = 110.dp,
    showDelta: Boolean = false,
    /** 撑满父容器剩余高度，并把两张图按可用空间分配（宽屏左列用） */
    fillHeight: Boolean = false,
) {
    val scheme = MaterialTheme.colorScheme
    val pts = remember(daily) { daily.takeLast(30) }

    val outer = if (fillHeight) {
        Modifier.fillMaxSize()
    } else {
        Modifier.fillMaxWidth()
    }

    Column(
        outer
            .clip(RoundedCornerShape(16.dp))
            .background(scheme.surface)
            .padding(horizontal = 16.dp, vertical = 15.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(
                    "用电趋势",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Medium,
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    "最近 ${minOf(daily.size, 30)} 天",
                    fontSize = 10.5.sp,
                    color = scheme.onSurfaceVariant,
                )
            }
            DeltaLegend(show = showDelta)
        }
        Spacer(Modifier.height(14.dp))

        if (fillHeight) {
            /*
             * 撑满模式：先用 BoxWithConstraints 量出卡片内剩余多少高度，
             * 再按 62 : 38 分给折线图和差值柱。
             *
             * 为什么不留固定高度：平板横屏 900dp、竖屏 1200dp、
             * 分屏后可能只有 600dp —— 写死高度总有一档是错的：
             * 高了会溢出画到卡片外面，矮了底部留一片空白。
             * 量出来再分，任何高度都刚好撑满。
             *
             * 两张图各自也有下限（折线 90dp、差值柱 56dp），
             * 扣掉固定开销后剩余不足时按比例压缩，不会出现负数高度。
             */
            BoxWithConstraints(Modifier.fillMaxWidth().weight(1f)) {
                /*
                 * 折线和差值柱按 55 : 45 分，但**折线设上限 240dp**。
                 *
                 * 一开始按 62 : 38 分，横屏 900dp 高时折线被拉到 380dp ——
                 * 8 个数据点摊在这么高的画布上，线陡得像心电图，
                 * 反而不如矮一点时看得清趋势。
                 * 折线超过 240dp 之后信息量不再增加，多出来的高度给差值柱更划算。
                 *
                 * 底部还要留 20dp：差值柱的日期标签在最下方，
                 * 不留的话会被卡片内边距切掉半个字。
                 */
                val fixedOverhead = (if (showDelta) 30.dp else 0.dp) + 20.dp
                val avail = (maxHeight - fixedOverhead).coerceAtLeast(120.dp)
                val lineH = if (showDelta) {
                    (avail * 0.55f).coerceAtMost(240.dp).coerceAtLeast(90.dp)
                } else {
                    avail
                }
                // 差值柱拿走折线用剩的全部高度，但至少 56dp 才读得出正负
                val deltaH = if (showDelta) {
                    (avail - lineH).coerceAtLeast(56.dp)
                } else {
                    0.dp
                }

                Column(Modifier.fillMaxSize()) {
                    UsageChart(pts, height = lineH)
                    if (showDelta) {
                        Spacer(Modifier.height(10.dp))
                        // 剩余高度全给差值柱，它内部再按「标题 / 柱区 / 日期标签」分
                        Box(Modifier.weight(1f).fillMaxWidth()) {
                            DeltaBars(pts, height = deltaH)
                        }
                    }
                }
            }
        } else {
            UsageChart(pts, height = chartHeight)
            if (showDelta) {
                Spacer(Modifier.height(18.dp))
                DeltaBars(pts, height = chartHeight * 0.62f)
            }
        }
    }
}

/** 差值柱的图例：两个小色块 + 说明。只在显示差值柱时出现。 */
@Composable
private fun DeltaLegend(show: Boolean) {
    if (!show) return
    val scheme = MaterialTheme.colorScheme
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier
                .size(7.dp)
                .clip(RoundedCornerShape(2.dp))
                .background(scheme.secondary),
        )
        Spacer(Modifier.width(4.dp))
        Text(
            "省",
            fontSize = 9.5.sp,
            color = scheme.onSurfaceVariant,
        )
        Spacer(Modifier.width(10.dp))
        Box(
            Modifier
                .size(7.dp)
                .clip(RoundedCornerShape(2.dp))
                .background(scheme.primary),
        )
        Spacer(Modifier.width(4.dp))
        Text(
            "超日均",
            fontSize = 9.5.sp,
            color = scheme.onSurfaceVariant,
        )
    }
}

/**
 * 每天用量相对「近 7 天日均」的差值柱，零轴居中。
 *
 * 颜色用主题正负搭配（致辉选的）：超出日均用 primary、低于日均用 secondary。
 * 不用 error 红是因为「今天多用了两度电」算不上错误，
 * 用告警色会把日常波动搞得像出了事故。
 *
 * 基线取整段数据的日均而不是固定 7 天 —— 图里只有 8 天时，
 * 「近 7 天日均」和全段日均几乎一样，但在 30 天视图里
 * 用全段日均更能反映「这段时间的常态」。
 */
@Composable
private fun DeltaBars(points: List<PowerDaily>, height: androidx.compose.ui.unit.Dp) {
    if (points.size < 2) return
    val scheme = MaterialTheme.colorScheme
    val over = scheme.primary
    val under = scheme.secondary
    val measurer = rememberTextMeasurer()

    val avg = remember(points) {
        val valid = points.filter { it.usage > 0 }
        if (valid.isEmpty()) 0.0 else valid.sumOf { it.usage } / valid.size
    }
    if (avg <= 0) return

    // 最大偏离量决定纵向缩放；至少给 0.1 免得全零时除零
    val maxAbs = remember(points, avg) {
        points.maxOfOrNull { kotlin.math.abs(it.usage - avg) }?.toFloat()?.coerceAtLeast(0.1f) ?: 0.1f
    }

    var sel by remember(points) { mutableIntStateOf(-1) }
    val picked = points.getOrNull(sel)
    // 纵轴数字占的宽度。柱子从这条线右边开始画，不跟数字抢地方。
    val axisW = 26.dp

    Column(Modifier.fillMaxSize()) {
        /*
         * 顶部读数。原来这里只写「相对日均 X 度」，选中某天时改写成
         * 那一天的日期和差值 —— 图本身太矮，再单开一行会把柱子压没。
         */
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (picked != null) {
                val delta = picked.usage - avg
                Text(
                    picked.date,
                    fontSize = 10.5.sp,
                    fontWeight = FontWeight.Medium,
                    color = scheme.onSurface,
                )
                Text(
                    if (delta >= 0) "超过日均 ${Fmt.d2(delta)} 度"
                    else "低于日均 ${Fmt.d2(-delta)} 度",
                    fontSize = 10.5.sp,
                    fontWeight = FontWeight.Medium,
                    color = if (delta >= 0) over else under,
                )
            } else {
                Text(
                    "相对日均 ${Fmt.d2(avg)} 度",
                    fontSize = 10.5.sp,
                    color = scheme.onSurfaceVariant,
                )
                Text(
                    "左右滑动查看每一天",
                    fontSize = 9.5.sp,
                    color = scheme.onSurfaceVariant.copy(alpha = 0.55f),
                )
            }
        }
        Spacer(Modifier.height(5.dp))
        /*
         * Canvas 用 weight(1f) 吃掉剩余高度，不写死 ——
         * 写死的话，父容器给了多少高度它都用不上，
         * 多出来的空间全变成卡片底部的空白。
         */
        Canvas(
            Modifier
                .fillMaxWidth()
                .weight(1f)
                .testTag("delta_bars")
                .pointerInput(points) {
                    // 柱状图是按等分格子排的，不是折线那种点连线，所以按 slot 取索引
                    fun pick(x: Float) {
                        val axisPx = axisW.toPx()
                        val chartW = (size.width - axisPx).coerceAtLeast(1f)
                        if (size.width <= 0) return
                        val slot = chartW / points.size
                        val rel = (x - axisPx).coerceIn(0f, chartW - 0.01f)
                        sel = (rel / slot).toInt().coerceIn(0, points.lastIndex)
                    }
                    detectDragGestures(
                        onDragStart = { off -> pick(off.x) },
                        onDrag = { change, _ -> pick(change.position.x); change.consume() },
                    )
                }
                .pointerInput(points) {
                    detectTapGestures { off ->
                        val axisPx = axisW.toPx()
                        val chartW = (size.width - axisPx).coerceAtLeast(1f)
                        val slot = chartW / points.size
                        val rel = (off.x - axisPx).coerceIn(0f, chartW - 0.01f)
                        val i = (rel / slot).toInt().coerceIn(0, points.lastIndex)
                        // 再点同一根柱子就取消选中
                        sel = if (sel == i) -1 else i
                    }
                },
        ) {
            val n = points.size
            val axisX = axisW.toPx()
            val chartW = (size.width - axisX).coerceAtLeast(1f)
            val slot = chartW / n
            val barW = (slot * 0.58f).coerceAtLeast(1.5f)
            val mid = size.height / 2f
            // 上下各留 6% 余量，柱顶不贴边
            val amp = size.height * 0.44f

            // 三条参考线：+max / 0 / -max。零轴最重，另两条压到很浅。
            drawLine(
                color = scheme.onSurfaceVariant.copy(alpha = 0.14f),
                start = Offset(axisX, mid - amp),
                end = Offset(size.width, mid - amp),
                strokeWidth = 1f,
            )
            drawLine(
                color = scheme.onSurfaceVariant.copy(alpha = 0.12f),
                start = Offset(axisX, mid + amp),
                end = Offset(size.width, mid + amp),
                strokeWidth = 1f,
            )
            drawLine(
                color = scheme.onSurfaceVariant.copy(alpha = 0.26f),
                start = Offset(axisX, mid),
                end = Offset(size.width, mid),
                strokeWidth = 1f,
            )

            // 纵轴数字：跟三条线一一对应，右对齐贴在轴左侧
            val labelStyle = TextStyle(
                fontSize = 8.5.sp,
                color = scheme.onSurfaceVariant.copy(alpha = 0.7f),
            )
            listOf(
                "+" + Fmt.d1(maxAbs.toDouble()) to mid - amp,
                "0" to mid,
                "-" + Fmt.d1(maxAbs.toDouble()) to mid + amp,
            ).forEach { (label, y) ->
                val layout = measurer.measure(label, labelStyle)
                drawText(
                    layout,
                    topLeft = Offset(
                        axisX - layout.size.width - 5f,
                        (y - layout.size.height / 2f).coerceAtLeast(0f),
                    ),
                )
            }

            points.forEachIndexed { i, p ->
                val delta = (p.usage - avg).toFloat()
                val h = (delta / maxAbs) * amp
                val cx = axisX + slot * i + slot / 2f
                val top = if (delta >= 0) mid - h else mid
                val bottom = if (delta >= 0) mid else mid + kotlin.math.abs(h)
                // 高度太小时给 1px，否则「几乎等于日均」的那几天会凭空消失
                val drawH = (bottom - top).coerceAtLeast(1f)
                // 选中的那根加深，其余半透明，一眼能找到拖到哪了
                val emphasis = if (sel < 0 || sel == i) 0.85f else 0.4f
                drawRect(
                    color = (if (delta >= 0) over else under).copy(alpha = emphasis),
                    topLeft = Offset(cx - barW / 2f, if (delta >= 0) mid - drawH else mid),
                    size = androidx.compose.ui.geometry.Size(barW, drawH),
                )
            }

            if (sel >= 0) {
                val cx = axisX + slot * sel + slot / 2f
                drawLine(
                    color = scheme.onSurface.copy(alpha = 0.35f),
                    start = Offset(cx, 0f),
                    end = Offset(cx, size.height),
                    strokeWidth = 1.2f,
                )
            }
        }
        Spacer(Modifier.height(4.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(
                points.first().date.takeLast(5),
                fontSize = 9.sp,
                lineHeight = 11.sp,
                color = scheme.onSurfaceVariant.copy(alpha = 0.55f),
                modifier = Modifier.padding(start = axisW),
            )
            Text(
                points.last().date.takeLast(5),
                fontSize = 9.sp,
                lineHeight = 11.sp,
                color = scheme.onSurfaceVariant.copy(alpha = 0.55f),
            )
        }
    }
}

/** 每日明细的一行：日期 …… 用量 · 余量。 */
@Composable
private fun DailyRow(day: PowerDaily) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(11.dp))
            .background(MaterialTheme.colorScheme.surface)
            .padding(horizontal = 14.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            day.date,
            fontSize = 12.5.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.weight(1f))
        Text(
            "用 ${Fmt.d2(day.usage)}",
            fontSize = 12.5.sp,
            fontWeight = FontWeight.Medium,
        )
        Spacer(Modifier.width(14.dp))
        Text(
            "余 ${Fmt.d2(day.remain)}",
            fontSize = 11.5.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * 用电趋势折线图。
 *
 * 支持左右滑动选中某一天：竖线定位 + 该点高亮，
 * 日期与用量显示在图**上方**（图内空间太挤，字会压线）。
 */
@Composable
private fun UsageChart(
    points: List<PowerDaily>,
    height: androidx.compose.ui.unit.Dp = 110.dp,
) {
    val scheme = MaterialTheme.colorScheme
    val line = scheme.primary
    val maxV = (points.maxOfOrNull { it.usage } ?: 0.1).coerceAtLeast(0.1)
    var sel by remember(points) { mutableIntStateOf(-1) }
    val picked = points.getOrNull(sel)

    Column {
        // 顶部读数：没选中时给操作提示，选中后显示日期 + 用量
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (picked != null) {
                Text(
                    picked.date,
                    fontSize = 11.5.sp,
                    fontWeight = FontWeight.Medium,
                    color = scheme.onSurface,
                )
                Text(
                    "${Fmt.d2(picked.usage)} 度",
                    fontSize = 12.5.sp,
                    fontWeight = FontWeight.Medium,
                    color = line,
                )
            } else {
                Text(
                    "左右滑动查看某一天的用量",
                    fontSize = 10.5.sp,
                    color = scheme.onSurfaceVariant.copy(alpha = 0.65f),
                )
                Text(
                    "峰值 ${Fmt.d2(maxV)} 度",
                    fontSize = 10.sp,
                    color = scheme.onSurfaceVariant.copy(alpha = 0.6f),
                )
            }
        }

        Spacer(Modifier.height(8.dp))

        Canvas(
            Modifier
                .fillMaxWidth()
                .height(height)
                .pointerInput(points) {
                    // 拖动时按 x 落到最近的点；手指不用精确对准
                    fun pick(x: Float) {
                        if (size.width <= 0 || points.size < 2) return
                        val step = size.width / (points.size - 1).toFloat()
                        val i = (x / step).roundToInt().coerceIn(0, points.lastIndex)
                        sel = i
                    }
                    detectDragGestures(
                        onDragStart = { off -> pick(off.x) },
                        onDrag = { change, _ -> pick(change.position.x); change.consume() },
                    )
                }
                .pointerInput(points) {
                    detectTapGestures { off ->
                        if (size.width <= 0 || points.size < 2) return@detectTapGestures
                        val step = size.width / (points.size - 1).toFloat()
                        val i = (off.x / step).roundToInt().coerceIn(0, points.lastIndex)
                        // 再点同一处就取消选中
                        sel = if (sel == i) -1 else i
                    }
                },
        ) {
            if (points.size < 2) return@Canvas
            val w = size.width
            val h = size.height
            val stepX = w / (points.size - 1).toFloat()

            fun yOf(v: Float) = h - (v / maxV.toFloat()) * (h * 0.86f) - h * 0.04f

            val path = Path()
            val fill = Path()
            points.forEachIndexed { i, p ->
                val x = stepX * i
                val y = yOf(p.usage.toFloat())
                if (i == 0) {
                    path.moveTo(x, y)
                    fill.moveTo(x, h)
                    fill.lineTo(x, y)
                } else {
                    path.lineTo(x, y)
                    fill.lineTo(x, y)
                }
                if (i == points.lastIndex) {
                    fill.lineTo(x, h)
                    fill.close()
                }
            }

            drawPath(fill, Brush.verticalGradient(listOf(line.copy(alpha = 0.22f), Color.Transparent)))
            drawPath(path, line, style = Stroke(width = 2.2f))

            if (picked != null) {
                // 选中：竖线 + 高亮点
                val x = stepX * sel
                val y = yOf(picked.usage.toFloat())
                drawLine(
                    color = line.copy(alpha = 0.45f),
                    start = Offset(x, 0f),
                    end = Offset(x, h),
                    strokeWidth = 1.4f,
                )
                drawCircle(color = line.copy(alpha = 0.18f), radius = 7.5f, center = Offset(x, y))
                drawCircle(color = line, radius = 3.6f, center = Offset(x, y))
            } else {
                // 未选中：保留最后一天的点，给个「线到这里为止」的锚
                val last = points.last()
                drawCircle(color = line, radius = 3.4f, center = Offset(w, yOf(last.usage.toFloat())))
            }
        }

        Spacer(Modifier.height(6.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(
                points.first().date.takeLast(5),
                fontSize = 9.5.sp,
                color = scheme.onSurfaceVariant.copy(alpha = 0.6f),
            )
            Text(
                points.last().date.takeLast(5),
                fontSize = 9.5.sp,
                color = scheme.onSurfaceVariant.copy(alpha = 0.6f),
            )
        }
    }
}

private fun formatNum(v: Double): String = Fmt.trim2(v)