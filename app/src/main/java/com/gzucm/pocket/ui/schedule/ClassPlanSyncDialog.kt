package com.gzucm.pocket.ui.schedule

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
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
    /** 维护人姓名（学籍里的），空 = 老数据没有这个字段，界面上省略这一截 */
    val ownerName: String = "",
    /** 清单名，如「期末作业清单」 */
    val name: String = "",
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
        /** 服务端 id。点这一行要能打开它的详情，所以必须带着 */
        val id: String,
        /** 密钥。**不在界面上显示** —— 它是同学输进去的那道口令，不是名字 */
        val key: String,
        /** 维护人姓名，副标题用 */
        val ownerName: String,
        /** 清单名（学委起的），标题用 */
        val planName: String = "",
        val count: Int,
        /** 上次成功同步的时间戳；0 = 还没同步过 */
        val syncedAt: Long,
    ) : ClassPlanBarState

    /** 认领的清单有新版：新增 [added] 条、改了 [updated] 条 */
    data class Updated(
        /** 同 [Claimed.id]：点「查看」要进对应的那份清单 */
        val id: String,
        /** 密钥，不显示（见 [Claimed.key]） */
        val key: String,
        val ownerName: String,
        val planName: String = "",
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
 * 场景一：首次发现本班有清单（整页）。
 *
 * 原来是 [CenterDialog] 里的内容，现在改成 [ClassPlanActivity] 的一整页：
 * 这里要列清单、输密钥、底下还有四行说明，弹窗高度不够时只能缩在一个小框里滚，
 * 手指一滑还容易把弹窗带下去（误触）。整页之后滑动只是滚动。
 *
 * 标题用「认领班级日程」而不是「本班有 N 份」：N 已经逐行列出来了，
 * 标题再数一遍反而啰嗦。标题在 Activity 顶栏。
 *
 * 只负责画：认领动作通过 [onSubmitKey] 抛给调用方（带 planId 和密钥），
 * 不在这里发请求。
 */
@Composable
fun ClassPlanClaimScreen(
    /** 学籍里的行政班，只读展示 —— 同学靠它确认 App 认对了班级 */
    className: String,
    /** 本班现有的清单摘要，用来让同学挑一份，**不展示密钥** */
    plans: List<ClaimablePlan>,
    /**
     * 校验「这一份的密钥」。**异步**：本地索引里没有这份清单（或 key 是旧的）时
     * 还要往服务端拉一次最新列表，所以结果走回调。
     */
    onSubmitKey: (planId: String, key: String, onResult: (Boolean) -> Unit) -> Unit,
    /** 退出认领（整页的出口，等于 finish） */
    onClose: () -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    var input by rememberSaveable { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var checking by remember { mutableStateOf(false) }
    /*
     * 默认选中第一份：本班只有一份清单是常态，默认选中让同学少点一次；
     * 多份时也有个明确的起点，不至于对着列表不知道从哪下手。
     * 以 plans 作为 key，列表变了（重新拉了索引）就重选，不会残留一个不存在的 id。
     */
    var selectedId by rememberSaveable(plans) { mutableStateOf(plans.firstOrNull()?.id) }

    fun tryClaim() {
        if (checking) return
        val plan = plans.firstOrNull { it.id == selectedId }
        error = when {
            plan == null -> "先选一份清单"
            input.isBlank() -> "先填密钥再点"
            else -> null
        }
        if (plan == null || input.isBlank()) return
        checking = true
        onSubmitKey(plan.id, input) { ok ->
            checking = false
            error = if (ok) null else "这个密钥对不上这一份清单，问问发清单的同学"
        }
    }

    /*
     * 正文可滚、按钮固定在底部：清单多的时候滚的是中间那段，
     * 「查看清单」不会被顶出屏幕 —— 这一点在原来的弹窗里做不到。
     */
    Column(
        Modifier
            .fillMaxSize()
            .padding(horizontal = 20.dp)
            .padding(bottom = 26.dp),
    ) {
        Column(
            Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState()),
        ) {
            /*
             * 先选清单、再输密钥（2026-11-23 改）。
             *
             * 原来只让同学输一个密钥，靠密钥反推是哪一份清单。但一个班可以有多份
             * 清单、各有各的密钥，人一旦记混就会「输对了密钥却认领错清单」，
             * 而弹窗本身又只列了名字不能点，等于让同学对着列表干猜。
             *
             * 现在把「选哪一份」和「证明有权看」拆成两步：列表里只给名字、条数、
             * 维护人（都是发布时就公开的信息），密钥仍然要另外找维护人要 ——
             * 所以列表可以放心点，密钥该保密的还是保密。
             *
             * 班级是系统认出来的（只读），清单是同学自己选的，密钥是同学自己填的。
             */
            Column(
                Modifier
                    .fillMaxWidth()
                    .clip(G3CornerShape(11.dp))
                    .background(scheme.surfaceVariant.copy(alpha = 0.35f))
                    .padding(horizontal = 12.dp, vertical = 10.dp),
            ) {
                Text("你的班级", fontSize = 11.sp, color = scheme.onSurfaceVariant)
                Spacer(Modifier.height(3.dp))
                Text(
                    className.ifBlank { "还没读到学籍里的班级" },
                    fontSize = 13.5.sp,
                    fontWeight = FontWeight.Medium,
                    color = scheme.onSurface,
                    maxLines = 2,
                )
            }

            Spacer(Modifier.height(12.dp))

            Text(
                if (plans.size == 1) "本班现有 1 份，选它：" else "本班现有 ${plans.size} 份，选一份：",
                fontSize = 11.5.sp,
                color = scheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(6.dp))
            if (plans.isEmpty()) {
                // 正常不会到这儿（没清单就不弹这个窗），留着是为了列表为空时也有话说
                Text(
                    "还没拉到清单，稍后再试",
                    fontSize = 11.5.sp,
                    color = scheme.onSurfaceVariant,
                )
            } else {
                /*
                 * 整行可点，不做 RadioButton：弹窗宽度本来就紧，单选用底色 + 右侧勾
                 * 已经够清楚，多一个圆点会把清单名挤到换行。
                 */
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    plans.forEach { p ->
                        val selected = p.id == selectedId
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .clip(G3CornerShape(11.dp))
                                .background(
                                    if (selected) {
                                        scheme.primary.copy(alpha = 0.10f)
                                    } else {
                                        scheme.surfaceVariant.copy(alpha = 0.30f)
                                    },
                                )
                                .clickable {
                                    selectedId = p.id
                                    error = null
                                }
                                .padding(horizontal = 12.dp, vertical = 9.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(
                                    p.name.ifBlank { "未命名清单" },
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.Medium,
                                    color = if (selected) scheme.primary else scheme.onSurface,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                Spacer(Modifier.height(2.dp))
                                Text(
                                    if (p.ownerName.isBlank()) {
                                        "${p.count} 条"
                                    } else {
                                        "${p.count} 条 · ${p.ownerName} 维护"
                                    },
                                    fontSize = 11.sp,
                                    color = scheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                            if (selected) {
                                Spacer(Modifier.width(8.dp))
                                Icon(
                                    AppIcons.Check,
                                    contentDescription = "已选中",
                                    modifier = Modifier.size(16.dp),
                                    tint = scheme.primary,
                                )
                            }
                        }
                    }
                }
            }

            Spacer(Modifier.height(12.dp))

            OutlinedTextField(
                value = input,
                onValueChange = {
                    input = it.take(20)
                    error = null
                },
                label = { Text("密钥", fontSize = 12.sp) },
                placeholder = { Text("一般是学委名字", fontSize = 13.sp) },
                singleLine = true,
                isError = error != null,
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
            )
            Spacer(Modifier.height(6.dp))
            Text(
                error ?: "这一份的密钥由维护的人给你，输对了才能看到内容。",
                fontSize = 11.5.sp,
                lineHeight = 16.sp,
                color = if (error != null) {
                    scheme.error
                } else {
                    scheme.onSurfaceVariant.copy(alpha = 0.8f)
                },
            )

            Spacer(Modifier.height(2.dp))
            WhatIsThis(scheme)
            Spacer(Modifier.height(8.dp))
        }

        Spacer(Modifier.height(12.dp))
        Button(
            onClick = { tryClaim() },
            enabled = !checking,
            modifier = Modifier
                .fillMaxWidth()
                .height(44.dp),
            shape = RoundedCornerShape(12.dp),
        ) {
            Text(
                if (checking) "正在核对…" else "查看清单",
                fontSize = 13.5.sp,
                fontWeight = FontWeight.Medium,
            )
        }
        TextButton(
            onClick = onClose,
            modifier = Modifier
                .fillMaxWidth()
                .height(40.dp),
        ) {
            Text("稍后", fontSize = 13.sp, color = scheme.onSurfaceVariant)
        }
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
    // key 是**清单标识**（同学认领时核对的那行字），不是人名 ——
    // 维护人是另一个字段，自动取学籍姓名。两者别混。
    val who = key.ifBlank { "班级清单" }
    CenterDialog(
        title = "$who 有更新",
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
    val who = key.ifBlank { "班级清单" }
    CenterDialog(
        title = "$who 已被删除",
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

/**
 * 可展开的「这是什么？」。
 *
 * **默认展开**（2026-11-22 改）。原来收起着，结果是：第一次见到这个功能的
 * 同学只看到一行「本班有 1 份班级日程」，不知道它从哪来、会对自己做什么，
 * 直接点「稍后」就再也不看了。折起来省的那 60dp 换来的是没人看得懂。
 *
 * 四句话对应四个真问题：这是什么东西 / 点了会怎样 / 不想要呢 / 谁知道我做了什么。
 * 更长的完整说明在清单详情页底部，那里篇幅不受限。
 */
@Composable
private fun WhatIsThis(scheme: androidx.compose.material3.ColorScheme) {
    var open by rememberSaveable { mutableStateOf(true) }
    val lines = listOf(
        "学委把全班的作业和安排整理成清单，发到云端",
        "输入他给你的密钥，就能看到清单、挑自己需要的导入",
        "导入后作业会变成你自己的日程，截止前按你的设置提醒",
        "只有本班同学能认领，你勾了什么不会传出去",
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
