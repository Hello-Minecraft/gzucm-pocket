package com.gzucm.pocket.ui.schedule

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.gzucm.pocket.ui.components.CenterDialog
import com.gzucm.pocket.ui.components.DialogPrimaryButton
import com.gzucm.pocket.ui.components.DialogSecondaryButton
import com.gzucm.pocket.ui.components.G3CornerShape
import com.gzucm.pocket.ui.icons.AppIcons
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * 一份「可认领的清单」，UI 侧的镜像。
 *
 * 对应契约第 4 节 core 里的 `ClassPlanBrief`（`id` / `key` / `count` / `updatedAt`），
 * 但**不直接引用那个类**：这里只取界面要用的四个字段，集成时一行 map 就行，
 * UI 也就不依赖 ClassSync 什么时候落地。`maintainer`（学号）刻意不带上来 ——
 * 界面上不显示学号。
 */
data class ClaimablePlan(
    val id: String,
    val key: String,
    val count: Int,
    val updatedAt: Long,
)

/**
 * 今日页「班级日程」那一行的四种状态。
 *
 * 文案在渲染处拼（今日页 TASKS 卡顶部那一行），不在这里拼 ——
 * 状态只描述事实，措辞集中在一处，改口径只改一个文件。
 */
sealed interface ClassPlanBarState {

    /** 本班还没有人上传过 */
    data object Empty : ClassPlanBarState

    /** 有清单但都还没认领 */
    data class Unclaimed(val plans: List<ClaimablePlan>) : ClassPlanBarState

    /** 认领了 [key] 的清单，且远端没有比本地更新的版本 */
    data class Claimed(
        val key: String,
        val count: Int,
        /** 上次成功同步的时间戳；0 = 还没同步过 */
        val syncedAt: Long,
    ) : ClassPlanBarState

    /** 认领的清单有新版：新增 [added] 条、改了 [updated] 条 */
    data class Updated(
        val key: String,
        val added: Int,
        val updated: Int,
        val syncedAt: Long,
    ) : ClassPlanBarState
}

/**
 * 「今天 08:12」/「昨天 08:12」/「11-20 08:12」/「2025-11-20 08:12」。
 *
 * 只写时分对跨天不友好（凌晨看 22:00 会以为是今天），
 * 所以超过昨天就带上日期；跨年再补年份 —— 跟日程里截止日期的写法一个口径。
 */
fun classPlanSyncTime(ms: Long): String {
    if (ms <= 0L) return "还没同步"
    val dt = Instant.ofEpochMilli(ms).atZone(ZoneId.systemDefault())
    val day = dt.toLocalDate()
    val today = LocalDate.now()
    val hhmm = "%02d:%02d".format(dt.hour, dt.minute)
    return when {
        day == today -> "今天 $hhmm"
        day == today.minusDays(1) -> "昨天 $hhmm"
        day.year == today.year -> "%02d-%02d %s".format(day.monthValue, day.dayOfMonth, hhmm)
        else -> "%d-%02d-%02d %s".format(day.year, day.monthValue, day.dayOfMonth, hhmm)
    }
}

/**
 * 「新增 2 条、改了 1 条」。
 *
 * [sep] 让调用方决定顿号还是逗号：首页那一行是并列短语用「、」，
 * 弹窗正文是一整句话用「，」。
 * 一边为 0 就只说另一边；两边都为 0（只删了内容）说「有更新」。
 */
fun classPlanPatchText(added: Int, updated: Int, sep: String = "，"): String = when {
    added > 0 && updated > 0 -> "新增 $added 条${sep}改了 $updated 条"
    added > 0 -> "新增 $added 条"
    updated > 0 -> "改了 $updated 条"
    else -> "有更新"
}

/**
 * 场景一：首次发现本班有清单。
 *
 * 只负责画：认领动作通过 [onClaim] 抛给调用方（传 planId），不在这里发请求。
 *
 * 标题用「本班有班级日程」而不是「本班有 N 份」：N 已经逐行列出来了，
 * 标题再数一遍反而啰嗦。
 */
@Composable
fun ClassPlanClaimDialog(
    plans: List<ClaimablePlan>,
    onClaim: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    CenterDialog(
        title = "本班有班级日程",
        onDismiss = onDismiss,
        buttons = {
            DialogSecondaryButton("稍后", onDismiss)
        },
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(7.dp)) {
            plans.forEach { plan ->
                ClaimRow(plan = plan, onClaimClick = { onClaim(plan.id) })
            }
        }
        Spacer(Modifier.height(4.dp))
        WhatIsThis(scheme)
    }
}

/**
 * 场景二：认领的清单有新版。
 *
 * 只预览前 3 条的标题，**不展开全文**（契约第 8 节）。
 * 真正的内容对比放在点「查看并导入」之后，那时候列表里有颜色、有勾选，比在弹窗里读强。
 */
@Composable
fun ClassPlanUpdateDialog(
    key: String,
    added: Int,
    updated: Int,
    previewTitles: List<String>,
    onOpen: () -> Unit,
    onLater: () -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    val who = key.ifBlank { "学委" }
    CenterDialog(
        title = "$who 更新了班级日程",
        // 点遮罩 = 稍后：既然有这个选择，就别让「想关掉」变成「关不掉」
        onDismiss = onLater,
        buttons = {
            DialogPrimaryButton("查看并导入", onOpen)
            DialogSecondaryButton("稍后", onLater)
        },
    ) {
        Text(
            classPlanPatchText(added, updated),
            fontSize = 13.sp,
            lineHeight = 19.sp,
            color = scheme.onSurface,
        )
        if (previewTitles.isNotEmpty()) {
            Spacer(Modifier.height(12.dp))
            Column(
                Modifier
                    .fillMaxWidth()
                    .clip(G3CornerShape(11.dp))
                    .background(scheme.surfaceVariant.copy(alpha = 0.35f))
                    .padding(horizontal = 12.dp, vertical = 10.dp),
                verticalArrangement = Arrangement.spacedBy(5.dp),
            ) {
                previewTitles.take(3).forEach { title ->
                    Text(
                        "· $title",
                        fontSize = 12.sp,
                        lineHeight = 17.sp,
                        color = scheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                if (previewTitles.size > 3) {
                    Text(
                        "…还有 ${previewTitles.size - 3} 条",
                        fontSize = 11.sp,
                        color = scheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

/**
 * 场景三：认领的清单被学委删了。
 *
 * 唯一要说清的事是「已经导入的日程不会消失」—— 用户看到「被删除」第一反应是
 * 「我的作业呢」，所以这句必须是正文，不能塞进小字。
 */
@Composable
fun ClassPlanDeletedDialog(
    key: String,
    onDismiss: () -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    val who = key.ifBlank { "学委" }
    CenterDialog(
        title = "$who 的清单已被删除",
        onDismiss = onDismiss,
        buttons = {
            DialogPrimaryButton("知道了", onDismiss)
        },
    ) {
        Text(
            "学委删掉了这份清单，你已经导入的日程不会消失",
            fontSize = 13.sp,
            lineHeight = 19.sp,
            color = scheme.onSurfaceVariant,
        )
    }
}

// ── 内部零件 ──────────────────────────────────────────────────────────

/** 清单列表里的一行：学委名 / 条数 + 更新时间 / 认领 */
@Composable
private fun ClaimRow(plan: ClaimablePlan, onClaimClick: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    Row(
        Modifier
            .fillMaxWidth()
            .clip(G3CornerShape(11.dp))
            .background(scheme.surfaceVariant.copy(alpha = 0.35f))
            .padding(start = 12.dp, end = 6.dp, top = 9.dp, bottom = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                plan.key.ifBlank { "学委" },
                fontSize = 13.sp,
                fontWeight = FontWeight.Medium,
                color = scheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(2.dp))
            Text(
                "${plan.count} 条 · ${classPlanSyncTime(plan.updatedAt)}",
                fontSize = 11.sp,
                color = scheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.width(6.dp))
        Text(
            "认领",
            fontSize = 12.5.sp,
            fontWeight = FontWeight.Medium,
            color = scheme.primary,
            modifier = Modifier
                .clip(RoundedCornerShape(8.dp))
                .clickable(onClick = onClaimClick)
                .padding(horizontal = 10.dp, vertical = 6.dp),
        )
    }
}

/**
 * 可展开的「这是什么？」。
 *
 * 三句话是契约第 7 节的固定文案，一个字都别改：
 * 干什么 / 认领之后会怎样 / 怎么退出。展开状态用 rememberSaveable 存，
 * 转屏不会自己收回去。
 */
@Composable
private fun WhatIsThis(scheme: androidx.compose.material3.ColorScheme) {
    var open by rememberSaveable { mutableStateOf(false) }
    val lines = listOf(
        "学委把作业清单传到这里，你的 App 每天自动看一眼",
        "认领之后新作业会直接进你的日程，截止前会提醒你",
        "随时可以取消认领，已导入的日程不会消失",
    )
    Column(Modifier.fillMaxWidth()) {
        Row(
            Modifier
                .clip(RoundedCornerShape(8.dp))
                .clickable { open = !open }
                .padding(horizontal = 2.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                AppIcons.Info,
                contentDescription = null,
                modifier = Modifier.size(13.dp),
                tint = scheme.primary,
            )
            Spacer(Modifier.width(5.dp))
            Text("这是什么？", fontSize = 12.sp, color = scheme.primary)
            Spacer(Modifier.width(3.dp))
            Icon(
                if (open) AppIcons.KeyboardArrowDown else AppIcons.ChevronRight,
                contentDescription = null,
                modifier = Modifier.size(13.dp),
                tint = scheme.primary.copy(alpha = 0.7f),
            )
        }
        if (open) {
            Column(
                Modifier.padding(start = 2.dp, top = 2.dp),
                verticalArrangement = Arrangement.spacedBy(5.dp),
            ) {
                lines.forEach { line ->
                    Text(
                        "· $line",
                        fontSize = 11.5.sp,
                        lineHeight = 17.sp,
                        color = scheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}
