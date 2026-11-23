package com.gzucm.pocket.ui.schedule

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.gzucm.pocket.core.ClassTaskDto
import com.gzucm.pocket.core.ClassTaskState
import com.gzucm.pocket.ui.ClassPlanDetailUi
import com.gzucm.pocket.ui.components.CenterDialog
import com.gzucm.pocket.ui.components.DialogPrimaryButton
import com.gzucm.pocket.ui.components.DialogSecondaryButton
import com.gzucm.pocket.ui.components.G3CornerShape
import com.gzucm.pocket.ui.components.isDarkSurface
import com.gzucm.pocket.ui.icons.AppIcons
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/** 橙色提示。和今日页那条班级日程警告同一个口径，浅色下压深一档 */
private val PlanWarnLight = Color(0xFFB2620C)
private val PlanWarnDark = Color(0xFFF2AC66)

/** 一条列表行至少这么高 —— 再矮勾选框和两行字会挤在一起 */
private val RowMinHeight = 56.dp

/**
 * 班级清单详情页：认领 / 导入之前，先让同学看见这份清单里到底有什么。
 *
 * ## 为什么要有这一层
 *
 * 原来的「认领」是**直接全量灌进日程**。致辉的原话是「点开应该能看到具体日程」——
 * 用户看不到吃进了什么，就没法拒绝一条自己根本不打算做的作业；
 * 而清单是学委的，里面必然有几条跟他无关。
 *
 * ## 为什么是整页而不是面板 / Dialog
 *
 * 这一层要放几十行可勾选的条目 + 一整段说明。Dialog 装不下；底部面板虽然装得下，
 * 但列表要上下翻，而「往下翻」和「下拉关闭面板」是同一个手势 —— 看着看着清单就没了。
 * 整页之后滑动只是滚动，返回键才是退出（2026-11 致辉报的正是误触下滑）。
 *
 * 唯一允许的 Dialog 是「取消认领」的二次确认 —— 它是一次性的一问一答，
 * 不存在误触下滑的问题。
 *
 * ## 勾选状态
 *
 * 只记「用户手动改过哪几条」，不存整份选中集合，见下面 overrides 的注释。
 */
@Composable
fun ClassPlanDetailContent(
    detail: ClassPlanDetailUi,
    onConfirm: (Set<String>) -> Unit,
    onUnsubscribe: () -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    val tasks = detail.plan.tasks
    val warn = if (isDarkSurface()) PlanWarnDark else PlanWarnLight

    /*
     * ── 勾选状态为什么这么存 ────────────────────────────────────────
     *
     * 面板是「有缓存先显示、网络回来再换成最新」的（见 openClassPlanDetail），
     * 所以 detail 在面板开着的时候**一定会被替换一次**。如果把
     * `mutableStateOf(detail.plan.tasks.map { it.id }.toSet())` 当初始值、
     * 用 `remember(detail)` 去记，网络一回来用户的勾选就被重置了 —— 这是这里
     * 最容易写错的地方。
     *
     * 现在只存「用户手动翻过哪几条」的覆盖值，默认值每次都按**最新的 detail** 现算：
     *   · 用户动过的条目 → 用 overrides 里的值，网络刷新不影响；
     *   · 没动过的条目   → `id !in declined`（declined 变了就跟着变，这才是对的）；
     *   · 新冒出来的条目 → 自动拿到最新默认值，不需要做集合对账。
     *
     * remember 的 key 是 detail.id：同一个面板换一份清单时（理论上不会，
     * 但面板是复用组合位置的）必须清空，否则上份清单的勾选会漏过来。
     */
    var overrides by remember(detail.id) { mutableStateOf<Map<String, Boolean>>(emptyMap()) }

    fun stateOf(id: String): ClassTaskState = detail.states[id] ?: ClassTaskState.NEW

    /** 这条当前是否被勾中。已导入的恒为 true（灰掉且不可改） */
    fun checkedOf(task: ClassTaskDto): Boolean =
        stateOf(task.id) == ClassTaskState.IMPORTED ||
            (overrides[task.id] ?: (task.id !in detail.declined))

    /*
     * 只有「还能动」的条目计入 N。
     *
     * 已加入的条目虽然显示为勾中，但它们早就在日程里了 —— 算进 N 会让按钮写着
     * 「导入 5 条」却只动 2 条，用户会以为坏了。
     */
    val selected = tasks
        .filter { stateOf(it.id) != ClassTaskState.IMPORTED && checkedOf(it) }
        .mapTo(LinkedHashSet()) { it.id }

    var confirmUnsubscribe by remember(detail.id) { mutableStateOf(false) }
    // 说明默认展开：第一次认领的人最需要读它，收起来等于没写
    var helpOpen by remember(detail.id) { mutableStateOf(true) }

    /*
     * 整页：高度由 Activity 的骨架给满。
     * 列表自己滚，外面不要再套 verticalScroll —— 两层滚动叠一起会抛
     * infinite constraints（这个坑原来在 AdaptiveSheet 里踩过一次）。
     */
    Column(Modifier.fillMaxSize()) {

        // ── 1. 来源行 ─────────────────────────────────────────
        Column(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 18.dp)
                .padding(top = 8.dp, bottom = 4.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                /*
                 * 两行：**班级**在上（系统认出来的，同学核对用），
                 * 「谁维护 · 多少条」在下。
                 *
                 * 2026-11-23 改：原来上面写的是 key，但 key 是同学自己输入的
                 * 那道口令，不是给人看的名片。该摊在明面上的是**班级**。
                 */
                val owner = detail.plan.maintainer.trim()
                val cls = detail.plan.className.trim()
                // 清单名优先（2026-11-23 加）：这是学委自己起的那摊事的名字，
                // 老数据没有就退回班级名
                val title = detail.plan.name.trim().ifBlank {
                    cls.ifBlank { detail.key.ifBlank { "班级作业" } }
                }
                val sub = buildString {
                    // 标题已经是班级名时别再重复一遍（老数据没有清单名就会这样）
                    if (cls.isNotBlank() && cls != title) append("$cls · ")
                    if (owner.isNotBlank()) append("$owner 维护 · ")
                    append("${tasks.size} 条")
                }
                Column(Modifier.weight(1f)) {
                    Text(
                        title,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Medium,
                        color = scheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Spacer(Modifier.height(2.dp))
                    Text(
                        sub,
                        fontSize = 11.sp,
                        color = scheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Spacer(Modifier.width(8.dp))
                Text(
                    // classPlanSyncTime(0) 本身就是「还没同步」，不要再拼一个「同步」
                    if (detail.syncedAt <= 0L) {
                        classPlanSyncTime(0L)
                    } else {
                        "${classPlanSyncTime(detail.syncedAt)} 同步"
                    },
                    fontSize = 11.sp,
                    color = scheme.onSurfaceVariant,
                )
            }
            if (detail.fromCache) {
                Spacer(Modifier.height(3.dp))
                Text(
                    "显示的是上次同步的内容，可能不是最新",
                    fontSize = 11.sp,
                    lineHeight = 15.sp,
                    color = warn,
                )
            }
        }

        // ── 2. 条目列表 ───────────────────────────────────────
        LazyColumn(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
            contentPadding = PaddingValues(bottom = 6.dp),
        ) {
            if (tasks.isEmpty()) {
                item(key = "empty") { EmptyPlan() }
            } else {
                itemsIndexed(tasks, key = { _, task -> task.id }) { index, task ->
                    val state = stateOf(task.id)
                    PlanTaskRow(
                        task = task,
                        state = state,
                        checked = checkedOf(task),
                        onToggle = {
                            // 只翻这一条，别动其它条目的选择
                            overrides = overrides + (task.id to !checkedOf(task))
                        },
                    )
                    if (index != tasks.lastIndex) {
                        HorizontalDivider(
                            modifier = Modifier.padding(horizontal = 18.dp),
                            thickness = 1.dp,
                            color = scheme.outlineVariant.copy(alpha = 0.4f),
                        )
                    }
                }
            }

            // ── 3. 「这是什么？」────────────────────────────────
            item(key = "help") {
                PlanHelpBlock(
                    open = helpOpen,
                    onToggle = { helpOpen = !helpOpen },
                )
            }
        }

        // ── 4. 底部按钮区（不随列表滚）────────────────────────
        HorizontalDivider(
            thickness = 1.dp,
            color = scheme.outlineVariant.copy(alpha = 0.4f),
        )
        Column(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 18.dp)
                .padding(top = 12.dp, bottom = 20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Button(
                onClick = { onConfirm(selected) },
                enabled = selected.isNotEmpty(),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(44.dp),
                shape = G3CornerShape(12.dp),
            ) {
                Text(
                    if (detail.claimed) {
                        "导入 ${selected.size} 条"
                    } else {
                        "认领并导入 ${selected.size} 条"
                    },
                    fontSize = 13.5.sp,
                    fontWeight = FontWeight.Medium,
                )
            }
            if (detail.claimed) {
                Spacer(Modifier.height(2.dp))
                TextButton(
                    onClick = { confirmUnsubscribe = true },
                    modifier = Modifier.height(36.dp),
                ) {
                    Text("取消认领", fontSize = 12.sp, color = scheme.onSurfaceVariant)
                }
            }
        }

        /*
         * 取消认领的二次确认。
         *
         * 整页之上唯一允许的 Dialog —— 它是一次性的一问一答，不存在下滑误触。
         * 确认后调 onUnsubscribe，由 Activity 决定退回哪一页（这里不管导航）。
         */
        if (confirmUnsubscribe) {
            CenterDialog(
                title = "取消认领？",
                onDismiss = { confirmUnsubscribe = false },
                buttons = {
                    DialogPrimaryButton(
                        text = "取消认领",
                        onClick = {
                            confirmUnsubscribe = false
                            onUnsubscribe()
                        },
                    )
                    DialogSecondaryButton(
                        text = "算了",
                        onClick = { confirmUnsubscribe = false },
                    )
                },
            ) {
                Text(
                    "取消之后不再同步这份清单。已经导入的日程会留下来，变成你自己的普通日程。",
                    fontSize = 13.sp,
                    lineHeight = 19.sp,
                    color = scheme.onSurfaceVariant,
                )
            }
        }
    }
}

// ── 列表行 ────────────────────────────────────────────────────────────

/**
 * 一条清单条目。
 *
 * 整行可点，不只让勾选框可点 —— M3 的勾选框命中区只有 48dp，
 * 手指在想勾的那条上点空一次就会以为「勾不动」。
 *
 * [ClassTaskState.IMPORTED] 的条目灰掉且不可改：它已经在日程里了，
 * 再勾一次只是重跑一遍合并，没有任何意义。
 */
@Composable
private fun PlanTaskRow(
    task: ClassTaskDto,
    state: ClassTaskState,
    checked: Boolean,
    onToggle: () -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    val locked = state == ClassTaskState.IMPORTED

    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = RowMinHeight)
            .then(
                if (locked) {
                    Modifier
                } else {
                    Modifier
                        .clip(G3CornerShape(10.dp))
                        .clickable(onClick = onToggle)
                },
            )
            .padding(start = 6.dp, end = 16.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(
            checked = checked,
            // 勾选框只负责「报告点了」，真正的状态由整行那一份 overrides 决定，
            // 不使用它给的 newValue —— 否则勾选框和整行各算一次，容易翻成相反值
            onCheckedChange = if (locked) null else { _: Boolean -> onToggle() },
            enabled = !locked,
            colors = CheckboxDefaults.colors(
                checkedColor = scheme.primary,
                uncheckedColor = scheme.onSurfaceVariant,
                checkmarkColor = scheme.onPrimary,
                // 已加入：灰底 + 主题的对比色勾，深浅色下都看得见
                disabledCheckedColor = scheme.onSurfaceVariant,
                disabledUncheckedColor = scheme.outline,
                disabledIndeterminateColor = scheme.onSurfaceVariant,
            ),
        )

        Column(Modifier.weight(1f)) {
            Text(
                task.title,
                fontSize = 13.sp,
                fontWeight = FontWeight.Medium,
                color = scheme.onSurface,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(2.dp))
            Text(
                planTaskSubtitle(task),
                fontSize = 11.sp,
                lineHeight = 15.sp,
                color = scheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }

        Spacer(Modifier.width(8.dp))
        PlanStatePill(state)
    }
}

/** 右侧状态胶囊：四种状态四种文案，颜色只分「灰 / 主题 / 橙」三档 */
@Composable
private fun PlanStatePill(state: ClassTaskState) {
    val scheme = MaterialTheme.colorScheme
    val warn = if (isDarkSurface()) PlanWarnDark else PlanWarnLight

    val label: String
    val fg: Color
    val bg: Color
    when (state) {
        ClassTaskState.IMPORTED -> {
            label = "已加入"
            fg = scheme.onSurfaceVariant
            bg = scheme.surfaceVariant
        }
        ClassTaskState.NEW -> {
            label = "新增"
            fg = scheme.primary
            bg = scheme.primary.copy(alpha = 0.12f)
        }
        ClassTaskState.CHANGED -> {
            label = "学委改过"
            fg = scheme.primary
            bg = scheme.primary.copy(alpha = 0.12f)
        }
        ClassTaskState.CONFLICT -> {
            label = "两边都改过"
            fg = warn
            bg = warn.copy(alpha = 0.14f)
        }
    }

    Text(
        label,
        modifier = Modifier
            .clip(RoundedCornerShape(6.dp))
            .background(bg)
            .padding(horizontal = 6.dp, vertical = 2.dp),
        fontSize = 11.sp,
        color = fg,
        maxLines = 1,
    )
}

/** 空清单也要说一句，别只留一块白板 */
@Composable
private fun EmptyPlan() {
    val scheme = MaterialTheme.colorScheme
    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 18.dp)
            .padding(vertical = 34.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("这份清单是空的", fontSize = 13.sp, color = scheme.onSurfaceVariant)
        Spacer(Modifier.height(4.dp))
        Text(
            "学委还没往里加作业",
            fontSize = 11.sp,
            color = scheme.onSurfaceVariant.copy(alpha = 0.8f),
        )
    }
}

// ── 「这是什么？」─────────────────────────────────────────────────────

/**
 * 四段说明，默认展开。
 *
 * 文案是固定的（契约 §7），重点有两句必须在前三行里读到：
 * 「勾中的才会进你的日程」和「学委的提醒不会覆盖你的」。
 */
@Composable
private fun PlanHelpBlock(open: Boolean, onToggle: () -> Unit) {
    val scheme = MaterialTheme.colorScheme

    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 18.dp)
            .padding(top = 10.dp, bottom = 4.dp),
    ) {
        Row(
            Modifier
                .clip(RoundedCornerShape(8.dp))
                .clickable(onClick = onToggle)
                .padding(vertical = 6.dp),
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

        if (!open) return@Column

        Spacer(Modifier.height(4.dp))
        Column(
            Modifier
                .fillMaxWidth()
                .clip(G3CornerShape(12.dp))
                .background(scheme.surfaceVariant.copy(alpha = 0.35f))
                .padding(horizontal = 12.dp, vertical = 11.dp),
            verticalArrangement = Arrangement.spacedBy(11.dp),
        ) {
            HelpSection(
                "这份清单从哪来",
                "学委把全班的作业和安排的截止日期整理成一份清单，发布到云端。" +
                    "你的 App 每天自动看一眼，有变化就提醒你。",
            )
            HelpSection(
                "点「导入」之后",
                "你勾中的条目会变成你自己的日程，在今日页和日程页都能看到。" +
                    "截止提醒按你自己的设置来，学委设的提醒不会覆盖你。",
                "学委之后改了日期，你这边会跟着更新；你勾掉的完成状态不会被重置。",
            )
            HelpSection(
                "不想要的可以不导入",
                "没勾的条目不会进你的日程，也不会再反复提醒你。想改主意随时回到这里重新勾。",
            )
            HelpSection(
                "谁会看到",
                "只有本班同学（用学校账号登录）能读到这份清单。" +
                    "你的日程、你勾了什么，都不会传出去。",
            )
        }
    }
}

/** 一段说明：小标题 + 一到两段正文 */
@Composable
private fun HelpSection(title: String, vararg paragraphs: String) {
    val scheme = MaterialTheme.colorScheme
    Column {
        Text(
            title,
            fontSize = 12.5.sp,
            fontWeight = FontWeight.Medium,
            color = scheme.primary,
        )
        paragraphs.forEach { text ->
            Spacer(Modifier.height(3.dp))
            Text(
                text,
                fontSize = 13.sp,
                lineHeight = 20.sp,
                color = scheme.onSurfaceVariant,
            )
        }
    }
}

// ── 副行文案 ──────────────────────────────────────────────────────────

/**
 * 副行：时间 + 地点。两种形态各走各的 ——
 *
 *   · 日程（见习 / 实验课）：`11-25 14:00-16:00 · 黄埔区中医院`
 *   · DDL：`截止 11-24 23:59`
 *   · 都没有：`长期待办`
 *
 * 地点为空不补一个孤零零的「·」，这是最容易露痕迹的地方。
 */
private fun planTaskSubtitle(task: ClassTaskDto): String {
    if (task.isEvent) {
        val head = "${planShortDay(task.day)} ${planHhmm(task.startMinute)}-${planHhmm(task.endMinute)}"
        return if (task.place.isBlank()) head else "$head · ${task.place}"
    }
    val due = task.dueAt
    return if (due == null) "长期待办" else "截止 ${planShortDateTime(due)}"
}

/** `yyyy-MM-dd` → `11-25`；跨年补年份，和日程里的截止日期一个口径 */
private fun planShortDay(iso: String): String {
    val d = runCatching { LocalDate.parse(iso) }.getOrNull() ?: return iso
    return if (d.year == LocalDate.now().year) {
        "%02d-%02d".format(d.monthValue, d.dayOfMonth)
    } else {
        "%d-%02d-%02d".format(d.year, d.monthValue, d.dayOfMonth)
    }
}

/** 截止时间：`11-24 23:59` */
private fun planShortDateTime(ms: Long): String {
    val dt = Instant.ofEpochMilli(ms).atZone(ZoneId.systemDefault())
    val d = dt.toLocalDate()
    val day = if (d.year == LocalDate.now().year) {
        "%02d-%02d".format(d.monthValue, d.dayOfMonth)
    } else {
        "%d-%02d-%02d".format(d.year, d.monthValue, d.dayOfMonth)
    }
    return "$day %02d:%02d".format(dt.hour, dt.minute)
}

private fun planHhmm(minute: Int): String = "%02d:%02d".format(minute / 60, minute % 60)
