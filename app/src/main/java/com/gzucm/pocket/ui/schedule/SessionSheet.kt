package com.gzucm.pocket.ui.schedule

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.gzucm.pocket.core.CourseSession
import com.gzucm.pocket.ui.theme.courseColor

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SessionSheet(
    /**
     * 显隐。由调用方传真实状态、**外层不要再包 if**。
     *
     * 包 if 的话组件第一次进组合时 visible 就已经是 true，
     * AnimatedVisibility 认为「初始就可见」，进入动画不播、
     * 退场时整棵子树被直接摘掉 —— 面板会「啪」地出现，
     * 平板侧栏完全没有滑动效果。
     */
    visible: Boolean = true,
    session: CourseSession,
    onDismiss: () -> Unit,
    /**
     * 与这门课**时段重叠**的其他课（不含自己）。
     *
     * 传进来的话，顶部会多一条「重叠课程」切换条 ——
     * 点另一个课名就切到那门课的详情，不用关掉再点开。
     */
    overlapGroup: List<CourseSession> = emptyList(),
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    // 当前看的是哪一门：点切换条会变
    var current by remember(session) { mutableStateOf(session) }
    val color = courseColor(current.name)
    val scheme = MaterialTheme.colorScheme

    /*
     * 统一走 AdaptiveSheet：手机是底部 Sheet，平板是从右侧滑出的侧栏。
     *
     * 原来这里写死 ModalBottomSheet —— 11 寸横屏上弹出来会盖住大半张课表，
     * 想对照着看「这门课前后还有没有别的」就做不到了。
     */
    com.gzucm.pocket.ui.adaptive.AdaptiveSheet(
        visible = visible,
        onDismiss = onDismiss,
        paneTitle = "课程详情",
        paneWidth = 400.dp,
    ) {
        SessionDetailContent(
            session = session,
            current = current,
            onCurrentChange = { current = it },
            overlapGroup = overlapGroup,
        )
    }
}


/**
 * 课程详情的内容主体，**不含容器**。
 *
 * 抽出来是因为要同时给两套容器用：
 *   · 手机：底部 ModalBottomSheet
 *   · 平板：右侧滑出面板
 * 两边内容完全一样，只有容器和圆角不同 —— 复制一份的话，
 * 以后加字段必然改一处漏一处。
 */
@Composable
internal fun SessionDetailContent(
    session: CourseSession,
    current: CourseSession,
    onCurrentChange: (CourseSession) -> Unit,
    overlapGroup: List<CourseSession>,
) {
    val color = courseColor(current.name)
    val scheme = MaterialTheme.colorScheme
    val currentRef = current

        Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 22.dp)
            .padding(bottom = 30.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier
                    .size(9.dp)
                    .clip(CircleShape)
                    .background(color),
            )
            Spacer(Modifier.width(9.dp))
            Text(
                currentRef.category.ifBlank { "课程" },
                fontSize = 11.5.sp,
                color = scheme.onSurfaceVariant,
            )
        }
        /*
         * 重叠课程切换条。
         *
         * 只在「同一时段还有别的课」时出现 —— 这时候只看一门是看不到全貌的，
         * 用户需要知道「这个时间段一共压了几门」。
         * 用横向可滚动的胶囊，课名长的也能读。
         */
        if (overlapGroup.isNotEmpty()) {
            Spacer(Modifier.height(12.dp))
            Text(
                "重叠课程",
                fontSize = 10.5.sp,
                color = scheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(7.dp))
            Row(
                Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(7.dp),
            ) {
                (listOf(session) + overlapGroup).forEach { c ->
                    val sel = c === current || c == current
                    val cc = courseColor(c.name)
                    Box(
                        Modifier
                            .clip(RoundedCornerShape(9.dp))
                            .background(
                                if (sel) cc.copy(alpha = 0.20f)
                                else scheme.surfaceVariant.copy(alpha = 0.40f)
                            )
                            .border(
                                if (sel) 1.dp else 0.dp,
                                if (sel) cc.copy(alpha = 0.75f)
                                else androidx.compose.ui.graphics.Color.Transparent,
                                RoundedCornerShape(9.dp),
                            )
                            .clickable { onCurrentChange(c) }
                            .padding(horizontal = 11.dp, vertical = 7.dp),
                    ) {
                        Text(
                            c.name,
                            fontSize = 11.5.sp,
                            fontWeight = if (sel) FontWeight.Medium else FontWeight.Normal,
                            color = if (sel) cc else scheme.onSurfaceVariant,
                            maxLines = 1,
                        )
                    }
                }
            }
        }

        Spacer(Modifier.height(9.dp))
        Text(
            current.name,
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Medium,
            lineHeight = 28.sp,
        )

        Spacer(Modifier.height(20.dp))

        // 上课内容：只有按周查询才有（sknrjj），放最前面，这是最有信息量的一条
        if (current.content.isNotBlank()) {
            Box(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(color.copy(alpha = 0.10f))
                    .padding(horizontal = 14.dp, vertical = 11.dp),
            ) {
                Column {
                    Text(
                        "本次内容",
                        fontSize = 10.5.sp,
                        color = scheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        current.content,
                        fontSize = 14.5.sp,
                        lineHeight = 21.sp,
                        fontWeight = FontWeight.Medium,
                    )
                }
            }
            Spacer(Modifier.height(16.dp))
        }

        InfoLine("时间", "${current.startTime} — ${current.endTime}  ·  第 ${current.startUnit}-${current.endUnit} 节")
        if (current.date.isNotBlank()) InfoLine("日期", current.date)
        if (current.room.isNotBlank()) InfoLine("地点", current.room)
        if (current.teachers.isNotBlank()) InfoLine("授课教师", current.teachers)
        if (current.teachingMode.isNotBlank()) InfoLine("教学模式", current.teachingMode)
        if (current.category.isNotBlank()) InfoLine("教学环节", current.category)
        if (current.teachForm.isNotBlank()) InfoLine("教学形式", current.teachForm)
        if (current.mergedClasses.isNotBlank()) InfoLine("合班上课", current.mergedClasses)
        if (current.studyHours.isNotBlank() && current.studyHours != "0") {
            InfoLine("课时", "${current.studyHours} 学时")
        }
        if (current.code.isNotBlank()) InfoLine("课程号", current.code)
        if (current.courseSeq.isNotBlank()) InfoLine("课序号", current.courseSeq)

        Spacer(Modifier.height(18.dp))

        Row(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .background(color.copy(alpha = 0.1f))
                .padding(horizontal = 14.dp, vertical = 11.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "第 ${current.week} 周",
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium,
                color = color,
            )
            Spacer(Modifier.width(8.dp))
            Text(
                weekdayName(current.weekday),
                fontSize = 12.sp,
                color = scheme.onSurfaceVariant,
            )
        }
    }
}

private fun weekdayName(w: Int): String =
    listOf("周一", "周二", "周三", "周四", "周五", "周六", "周日").getOrElse(w - 1) { "" }

@Composable
private fun InfoLine(label: String, value: String) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 7.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Text(
            label,
            fontSize = 12.5.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(52.dp),
        )
        Text(
            value,
            fontSize = 13.sp,
            lineHeight = 20.sp,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f),
        )
    }
}