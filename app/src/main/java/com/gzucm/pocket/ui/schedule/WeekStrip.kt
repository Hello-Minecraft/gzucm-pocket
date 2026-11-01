package com.gzucm.pocket.ui.schedule

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * 横向周次选择条。当前周带强调点，点任意周跳转。
 * 自动滚动到选中周。
 */
@Composable
fun WeekStrip(
    totalWeeks: Int,
    currentWeek: Int,
    viewingWeek: Int,
    onSelect: (Int) -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    val listState = rememberLazyListState()

    /*
     * 只在「选中的周看不见了」时才滚动，而且不用动画。
     *
     * 原来的写法是每次 viewingWeek 变化就 animateScrollToItem(viewingWeek - 2)：
     *   · 用户点第 5 周 → onSelect(5) → viewingWeek 变 5 →
     *     这个 effect 立刻开始滚动动画
     *   · 滚动动画进行中，LazyRow 正在消费手势，
     *     用户紧接着点第 6 周，这一下会被动画吞掉
     *   · 表现就是「卡卡的，大多数时候点不动」（致辉 2026-11-01 报的）
     *
     * 现在两条限制：
     *   ① 目标项已经在可见范围内 → 不滚（大多数点击都命中这条）
     *   ② 确实需要滚时用 scrollToItem（瞬移），不跟用户的手抢
     *
     * 留一格余量：让选中项离边缘还有 1~2 格，点邻周时视图不会突然跳。
     */
    LaunchedEffect(viewingWeek) {
        val index = (viewingWeek - 1).coerceIn(0, (totalWeeks - 1).coerceAtLeast(0))
        val info = listState.layoutInfo
        if (info.visibleItemsInfo.isEmpty()) return@LaunchedEffect

        val firstVisible = info.visibleItemsInfo.first().index
        val lastVisible = info.visibleItemsInfo.last().index
        // 已经在可见范围内（留一格余量），不动
        if (index in (firstVisible + 1)..(lastVisible - 1)) return@LaunchedEffect

        val target = (index - 1).coerceAtLeast(0)
        listState.scrollToItem(target)
    }

    LazyRow(
        state = listState,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 18.dp),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(end = 6.dp),
        horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(6.dp),
    ) {
        items((1..totalWeeks).toList()) { w ->
            val active = w == viewingWeek
            val isCurrent = w == currentWeek
            Column(
                Modifier
                    .width(38.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(
                        if (active) scheme.primary.copy(alpha = 0.15f)
                        else Color.Transparent,
                    )
                    .border(
                        0.7.dp,
                        if (active) scheme.primary.copy(alpha = 0.45f)
                        else scheme.outlineVariant.copy(alpha = 0.6f),
                        RoundedCornerShape(10.dp),
                    )
                    .clickable { onSelect(w) }
                    .padding(vertical = 6.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    "$w",
                    fontSize = 13.sp,
                    fontWeight = if (active) FontWeight.Medium else FontWeight.Normal,
                    color = if (active) scheme.primary
                    else if (isCurrent) scheme.onSurface
                    else scheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(2.dp))
                Box(
                    Modifier
                        .size(if (isCurrent) 3.dp else 0.dp)
                        .clip(CircleShape)
                        .background(if (isCurrent) scheme.primary else Color.Transparent),
                )
            }
        }
    }
}