package com.gzucm.pocket.ui.schedule

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
fun SessionSheet(session: CourseSession, onDismiss: () -> Unit) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val color = courseColor(session.name)
    val scheme = MaterialTheme.colorScheme

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = scheme.surface,
        shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp),
    ) {
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
                    session.category.ifBlank { "课程" },
                    fontSize = 11.5.sp,
                    color = scheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.height(9.dp))
            Text(
                session.name,
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Medium,
                lineHeight = 28.sp,
            )

            Spacer(Modifier.height(20.dp))

            // 上课内容：只有按周查询才有（sknrjj），放最前面，这是最有信息量的一条
            if (session.content.isNotBlank()) {
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
                            session.content,
                            fontSize = 14.5.sp,
                            lineHeight = 21.sp,
                            fontWeight = FontWeight.Medium,
                        )
                    }
                }
                Spacer(Modifier.height(16.dp))
            }

            InfoLine("时间", "${session.startTime} — ${session.endTime}  ·  第 ${session.startUnit}-${session.endUnit} 节")
            if (session.date.isNotBlank()) InfoLine("日期", session.date)
            if (session.room.isNotBlank()) InfoLine("地点", session.room)
            if (session.teachers.isNotBlank()) InfoLine("授课教师", session.teachers)
            if (session.teachingMode.isNotBlank()) InfoLine("教学模式", session.teachingMode)
            if (session.category.isNotBlank()) InfoLine("教学环节", session.category)
            if (session.teachForm.isNotBlank()) InfoLine("教学形式", session.teachForm)
            if (session.mergedClasses.isNotBlank()) InfoLine("合班上课", session.mergedClasses)
            if (session.studyHours.isNotBlank() && session.studyHours != "0") {
                InfoLine("课时", "${session.studyHours} 学时")
            }
            if (session.code.isNotBlank()) InfoLine("课程号", session.code)
            if (session.courseSeq.isNotBlank()) InfoLine("课序号", session.courseSeq)

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
                    "第 ${session.week} 周",
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Medium,
                    color = color,
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    weekdayName(session.weekday),
                    fontSize = 12.sp,
                    color = scheme.onSurfaceVariant,
                )
            }
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