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

    LaunchedEffect(viewingWeek) {
        val target = (viewingWeek - 2).coerceAtLeast(0)
        listState.animateScrollToItem(target)
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