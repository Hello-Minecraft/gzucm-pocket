package com.gzucm.pocket.ui.schedule

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.gzucm.pocket.core.Agenda
import com.gzucm.pocket.core.ClassPlan
import com.gzucm.pocket.core.ClassTaskDto
import com.gzucm.pocket.core.decodeClassToken
import com.gzucm.pocket.core.encodeClassToken
import com.gzucm.pocket.core.mergeClassTasks
import com.gzucm.pocket.core.toClassTask
import com.gzucm.pocket.ui.adaptive.LocalIsWidePane
import com.gzucm.pocket.ui.components.G3CornerShape
import com.gzucm.pocket.ui.icons.AppIcons
import com.gzucm.pocket.core.ClassImportResult
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID

/**
 * 班级日程的内容区。
 *
 * **不是独立面板**：由 [AgendaListSheet] 在同一个 Dialog 里切换内容显示。
 * 手机端每个 `AdaptiveSheet` 都是一个 Dialog 窗口，面板叠面板会出现
 * 「按返回键直接退回桌面」（Dialog 吃掉 BACK 后 Activity 又收到一次）。
 *
 * 三块内容共用一个 Column，靠 [mode] 切换：清单 / 编辑一条 / 从日程挑。
 * 每一步都是「返回上一步」而不是「开一个新窗口」。
 */
@Composable
internal fun ClassPlanContent(
    plan: ClassPlan,
    onPlanChange: (ClassPlan) -> Unit,
    myAgendas: List<Agenda>,
    onImportToAgenda: (List<Agenda>) -> Unit,
    onClose: () -> Unit,
    /** 学籍里的姓名 = 维护人，**自动填、不可改**（不是密钥） */
    maintainer: String? = null,
    /** 学籍里的行政班，只用于显示「你是哪个班的」，不参与密钥 */
    studentClass: String? = null,
) {
    val ctx = LocalContext.current
    val scheme = MaterialTheme.colorScheme
    val clipboard = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager

    var mode by remember { mutableStateOf<Mode>(Mode.LIST) }
    var hint by remember { mutableStateOf<Pair<String, Boolean>?>(null) }

    /*
     * 密钥**不预填**（2026-11-21 致辉定的）：
     * 它填的是「全班共识的那个名字」（通常是学委），而登录这台手机的人
     * 可能只是帮忙维护的班长。自动填成自己的名字会让同学认错清单。
     * 维护人是另一个字段，那个才自动填。
     */
    var key by rememberSaveable { mutableStateOf(plan.key) }

    fun persist(next: ClassPlan) {
        /*
         * 每次保存都把学籍里的班级带上 —— 这是云端版的主索引。
         * 本地（分享串）只用于显示「这是 X 班的清单」，
         * 但传出去的信息越全，以后接云端越省事。
         */
        onPlanChange(
            next.copy(
                className = studentClass?.takeIf { it.isNotBlank() } ?: next.className,
                maintainer = maintainer?.takeIf { it.isNotBlank() } ?: next.maintainer,
                updatedAt = System.currentTimeMillis(),
            ),
        )
    }

    fun readClip(): String =
        clipboard.primaryClip?.takeIf { it.itemCount > 0 }
            ?.getItemAt(0)?.coerceToText(ctx)?.toString().orEmpty()

    fun importFromClip() {
        val text = readClip()
        if (text.isBlank()) {
            hint = "剪贴板是空的。先在群里长按复制学委发的分享串" to false
            return
        }
        runCatching { decodeClassToken(text) }
            .onSuccess { incoming ->
                val (next, result) = mergeClassTasks(myAgendas, incoming)
                onImportToAgenda(next)
                val cls = incoming.className.takeIf { it.isNotBlank() }
                val who = incoming.key.ifBlank { "同学" }
                hint = "$who 的清单${if (cls != null) "（$cls）" else ""}：${result.summary()}" to true
            }
            .onFailure { hint = (it.message ?: "这串读不出来") to false }
    }

    fun share() {
        val name = key.trim()
        if (name.isEmpty()) {
            hint = "先填密钥 —— 填全班都知道的那个名字，通常是学委" to false
            return
        }
        if (plan.tasks.isEmpty()) {
            hint = "清单还是空的，先加几条作业再分享" to false
            return
        }
        val token = encodeClassToken(
            plan.copy(
                key = name,
                className = studentClass?.takeIf { it.isNotBlank() } ?: plan.className,
                maintainer = maintainer?.takeIf { it.isNotBlank() } ?: plan.maintainer,
            ),
        )
        clipboard.setPrimaryClip(ClipData.newPlainText("班级日程分享串", token))
        hint = "已复制 ${plan.tasks.size} 条作业的分享串（${token.length} 字），发到班级群里就行" to true
    }

    when (val m = mode) {
        Mode.LIST -> Column(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .padding(bottom = 26.dp)
                .verticalScroll(rememberScrollState()),
        ) {
            BackRow(onClose, "返回我的日程")

            if (!LocalIsWidePane.current) {
                Text(
                    "班级日程",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Medium,
                    color = scheme.onSurface,
                )
                Spacer(Modifier.height(4.dp))
            }
            Text(
                "你自己维护一份作业清单，同学粘一下分享串就能全部加进自己的日程。",
                fontSize = 12.5.sp,
                lineHeight = 18.sp,
                color = scheme.onSurfaceVariant,
            )

            Spacer(Modifier.height(14.dp))

            // 自动识别的两项：班级（云端主索引）和维护人（谁在上传）
            InfoLine("本班", studentClass, scheme)
            InfoLine("维护人", maintainer, scheme)

            Spacer(Modifier.height(14.dp))

            OutlinedTextField(
                value = key,
                onValueChange = {
                    key = it.take(12)
                    persist(plan.copy(key = key))
                },
                label = { Text("密钥（学委名字）", fontSize = 12.sp) },
                placeholder = { Text("例如：张三", fontSize = 13.sp) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
            )
            Spacer(Modifier.height(6.dp))
            Text(
                "填全班都知道的那个名字，通常是学委 —— 同学凭它认领这份清单。" +
                    "谁在维护都可以，维护人是上面自动填的那个。",
                fontSize = 11.5.sp,
                lineHeight = 16.sp,
                color = scheme.onSurfaceVariant.copy(alpha = 0.8f),
            )

            Spacer(Modifier.height(18.dp))

            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "我的清单 · ${plan.tasks.size} 条",
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium,
                    color = scheme.onSurface,
                )
                Spacer(Modifier.weight(1f))
                Text(
                    "按截止日排序",
                    fontSize = 11.sp,
                    color = scheme.onSurfaceVariant.copy(alpha = 0.7f),
                )
            }
            Spacer(Modifier.height(8.dp))

            if (plan.tasks.isEmpty()) {
                Box(
                    Modifier
                        .fillMaxWidth()
                        .padding(vertical = 26.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        "还没有作业。点下面的「＋ 新建作业」加一条，或者从自己的日程里挑几条搬过来",
                        fontSize = 12.sp,
                        lineHeight = 17.sp,
                        color = scheme.onSurfaceVariant,
                    )
                }
            } else {
                sortedTasks(plan).forEach { task ->
                    TaskRow(
                        task = task,
                        scheme = scheme,
                        onEdit = { mode = Mode.Edit(task, copyFrom = null) },
                        onCopy = { mode = Mode.Edit(task = null, copyFrom = task) },
                        onDelete = {
                            persist(plan.copy(tasks = plan.tasks.filterNot { it.id == task.id }))
                            hint = "已经删了「${task.title}」" to true
                        },
                    )
                    Spacer(Modifier.height(7.dp))
                }
            }

            Spacer(Modifier.height(16.dp))

            Row(Modifier.fillMaxWidth()) {
                OutlinedButton(
                    onClick = { mode = Mode.Edit(null, null) },
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(12.dp),
                ) { Text("＋ 新建作业", fontSize = 12.5.sp) }
                Spacer(Modifier.width(9.dp))
                OutlinedButton(
                    onClick = { mode = Mode.PICK },
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(12.dp),
                ) { Text("从我的日程导入", fontSize = 12.5.sp) }
            }

            Spacer(Modifier.height(9.dp))

            Row(Modifier.fillMaxWidth()) {
                Button(
                    onClick = { share() },
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(12.dp),
                ) { Text("分享给同学", fontSize = 12.5.sp) }
                Spacer(Modifier.width(9.dp))
                OutlinedButton(
                    onClick = { importFromClip() },
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(12.dp),
                ) { Text("导入同学的", fontSize = 12.5.sp) }
            }

            hint?.let { (text, ok) ->
                Spacer(Modifier.height(14.dp))
                HintBox(text, ok, scheme)
            }

            Spacer(Modifier.height(18.dp))
            Text(
                "这份清单只存在这台手机上。换手机或重装之前，记得把分享串存一份到微信收藏 —— " +
                    "它同时也是唯一不会被别人改动的备份。",
                fontSize = 11.5.sp,
                lineHeight = 16.sp,
                color = scheme.onSurfaceVariant.copy(alpha = 0.6f),
            )
        }

        is Mode.Edit -> TaskEditView(
            existing = m.task,
            copyFrom = m.copyFrom,
            scheme = scheme,
            onCancel = { mode = Mode.LIST },
            onSave = { dto ->
                val next = plan.tasks.filterNot { it.id == dto.id } + dto
                persist(plan.copy(tasks = next))
                hint = if (m.task == null) "已加「${dto.title}」" to true else "已改「${dto.title}」" to true
                mode = Mode.LIST
            },
        )

        Mode.PICK -> PickAgendaView(
            agendas = myAgendas,
            alreadyIn = plan.tasks.map { it.id }.toSet(),
            scheme = scheme,
            onCancel = { mode = Mode.LIST },
            onConfirm = { picked ->
                val next = plan.tasks + picked.map { it.toClassTask() }
                persist(plan.copy(tasks = next))
                hint = "从日程搬过来 ${picked.size} 条" to true
                mode = Mode.LIST
            },
        )
    }
}

// ── 子界面 ────────────────────────────────────────────────────────────

private sealed interface Mode {
    data object LIST : Mode
    data class Edit(val task: ClassTaskDto?, val copyFrom: ClassTaskDto?) : Mode
    object PICK : Mode
}

/** 一行「标签 + 值」的只读信息，值拿不到就整行不显示 */
@Composable
private fun InfoLine(
    label: String,
    value: String?,
    scheme: androidx.compose.material3.ColorScheme,
) {
    if (value.isNullOrBlank()) return
    Row(
        Modifier
            .fillMaxWidth()
            .padding(bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            AppIcons.Check,
            contentDescription = null,
            modifier = Modifier.size(13.dp),
            tint = scheme.primary,
        )
        Spacer(Modifier.width(5.dp))
        Text("$label：", fontSize = 11.5.sp, color = scheme.onSurfaceVariant)
        Text(value, fontSize = 11.5.sp, color = scheme.primary)
    }
}

@Composable
private fun BackRow(onClick: () -> Unit, label: String) {
    val scheme = MaterialTheme.colorScheme
    Row(
        Modifier
            .padding(bottom = 8.dp)
            .clip(G3CornerShape(10.dp))
            .clickable(onClick = onClick)
            .padding(vertical = 6.dp, horizontal = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            AppIcons.ArrowBack,
            contentDescription = null,
            modifier = Modifier.size(17.dp),
            tint = scheme.onSurfaceVariant,
        )
        Spacer(Modifier.width(5.dp))
        Text(label, fontSize = 12.5.sp, color = scheme.onSurfaceVariant)
    }
}

@Composable
private fun TaskRow(
    task: ClassTaskDto,
    scheme: androidx.compose.material3.ColorScheme,
    onEdit: () -> Unit,
    onCopy: () -> Unit,
    onDelete: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(G3CornerShape(11.dp))
            .background(scheme.surfaceVariant.copy(alpha = 0.35f))
            .padding(horizontal = 11.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                task.title,
                fontSize = 13.sp,
                fontWeight = FontWeight.Medium,
                color = scheme.onSurface,
                maxLines = 2,
            )
            Spacer(Modifier.height(2.dp))
            Text(
                listOfNotNull(
                    task.course.takeIf { it.isNotBlank() },
                    dueLabel(task.dueAt),
                ).joinToString(" · "),
                fontSize = 11.5.sp,
                color = scheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.width(6.dp))
        MiniAction("改", scheme) { onEdit() }
        MiniAction("复制", scheme) { onCopy() }
        MiniAction("删", scheme, danger = true) { onDelete() }
    }
}

@Composable
private fun MiniAction(
    text: String,
    scheme: androidx.compose.material3.ColorScheme,
    danger: Boolean = false,
    onClick: () -> Unit,
) {
    Text(
        text,
        fontSize = 11.5.sp,
        color = if (danger) scheme.error else scheme.primary,
        modifier = Modifier
            .clip(RoundedCornerShape(7.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 7.dp, vertical = 4.dp),
    )
}

/** 新建 / 编辑一条作业。内联在这一页里，不新开窗口 */
@Composable
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
private fun TaskEditView(
    existing: ClassTaskDto?,
    copyFrom: ClassTaskDto?,
    scheme: androidx.compose.material3.ColorScheme,
    onCancel: () -> Unit,
    onSave: (ClassTaskDto) -> Unit,
) {
    val source = existing ?: copyFrom
    var title by rememberSaveable { mutableStateOf(source?.title.orEmpty()) }
    var course by rememberSaveable { mutableStateOf(source?.course.orEmpty()) }
    var dueMillis by rememberSaveable { mutableStateOf(source?.dueAt) }
    var showCalendar by remember { mutableStateOf(false) }
    var note by rememberSaveable { mutableStateOf(source?.note.orEmpty()) }
    var error by remember { mutableStateOf<String?>(null) }

    /*
     * 左右只留 10dp：M3 的 DatePicker 内部是固定 360dp，外面留 20dp 的话
     * 手机（约 393dp 宽）上只剩 353dp，日历会被压扁、星期几挤成一团。
     */
    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 10.dp)
            .padding(bottom = 26.dp)
            .verticalScroll(rememberScrollState()),
    ) {
        BackRow(onCancel, "返回清单")
        Text(
            when {
                existing != null -> "改一条作业"
                copyFrom != null -> "复制一条作业"
                else -> "新建作业"
            },
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Medium,
            color = scheme.onSurface,
        )
        Spacer(Modifier.height(14.dp))

        OutlinedTextField(
            value = title,
            onValueChange = { title = it.take(60); error = null },
            label = { Text("作业名", fontSize = 12.sp) },
            placeholder = { Text("例如：中医内科学期末论文", fontSize = 13.sp) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(12.dp),
        )
        Spacer(Modifier.height(10.dp))

        OutlinedTextField(
            value = course,
            onValueChange = { course = it.take(20) },
            label = { Text("科目（选填）", fontSize = 12.sp) },
            placeholder = { Text("标题里写了就不用填", fontSize = 12.sp) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(12.dp),
        )
        Spacer(Modifier.height(10.dp))

        /*
         * 截止日期：点开就是系统日历（M3 DatePicker），**不用文本输入**。
         *
         * 但**不能**用 DatePickerDialog —— 那会在 AdaptiveSheet 上再叠一个
         * Dialog 窗口，BACK 被内层吃掉之后 Activity 又收到一次，直接退回桌面
         * （项目里踩过，见 PITFALLS）。所以改成在这张表单里内联展开。
         */
        Column(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .border(
                    1.dp,
                    if (showCalendar) scheme.primary else scheme.outline,
                    RoundedCornerShape(12.dp),
                )
                .clickable { showCalendar = !showCalendar }
                .padding(horizontal = if (showCalendar) 0.dp else 14.dp, vertical = 13.dp),
        ) {
            Row(
                Modifier.padding(horizontal = if (showCalendar) 14.dp else 0.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    if (dueMillis == null) "截止日期（选填）" else "截止日期",
                    fontSize = 11.5.sp,
                    color = scheme.onSurfaceVariant,
                )
                Spacer(Modifier.weight(1f))
                Text(
                    if (dueMillis == null) "长期待办" else dayLabel(dueMillis!!),
                    fontSize = 13.sp,
                    color = if (dueMillis == null) scheme.onSurfaceVariant else scheme.onSurface,
                )
            }
            if (showCalendar) {
                val pickerState = androidx.compose.material3.rememberDatePickerState(
                    initialSelectedDateMillis = dueMillis,
                )
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                ) {
                    Text(
                        "清除",
                        fontSize = 12.5.sp,
                        color = scheme.onSurfaceVariant,
                        modifier = Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .clickable { dueMillis = null; showCalendar = false }
                            .padding(horizontal = 10.dp, vertical = 6.dp),
                    )
                    Spacer(Modifier.width(4.dp))
                    Text(
                        "确定",
                        fontSize = 12.5.sp,
                        color = scheme.primary,
                        modifier = Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .clickable {
                                pickerState.selectedDateMillis?.let { dueMillis = it }
                                showCalendar = false
                            }
                            .padding(horizontal = 10.dp, vertical = 6.dp),
                    )
                }
                androidx.compose.material3.DatePicker(
                    state = pickerState,
                    // 默认标题是英文（M3 没带中文资源），这里覆盖掉
                    title = {
                        Text(
                            "选择截止日期",
                            fontSize = 12.5.sp,
                            color = scheme.onSurfaceVariant,
                            modifier = Modifier.padding(start = 24.dp, top = 16.dp),
                        )
                    },
                    headline = {
                        Text(
                            pickerState.selectedDateMillis?.let { dayLabel(it) } ?: "未选择",
                            fontSize = 20.sp,
                            fontWeight = FontWeight.Medium,
                            color = scheme.onSurface,
                            modifier = Modifier.padding(start = 24.dp, bottom = 12.dp),
                        )
                    },
                    // 关掉「切换为输入模式」：那个模式会切出英文的日期输入框
                    showModeToggle = false,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
        Spacer(Modifier.height(4.dp))
        Text(
            "留空就是长期待办，没有截止时间",
            fontSize = 11.sp,
            color = scheme.onSurfaceVariant.copy(alpha = 0.75f),
        )
        Spacer(Modifier.height(10.dp))

        OutlinedTextField(
            value = note,
            onValueChange = { note = it.take(300) },
            label = { Text("要求（选填）", fontSize = 12.sp) },
            placeholder = { Text("字数、格式、交到哪里…", fontSize = 12.sp) },
            minLines = 2,
            maxLines = 4,
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(12.dp),
        )

        error?.let {
            Spacer(Modifier.height(8.dp))
            Text(it, fontSize = 11.5.sp, color = scheme.error)
        }

        Spacer(Modifier.height(16.dp))
        Row(Modifier.fillMaxWidth()) {
            OutlinedButton(
                onClick = onCancel,
                modifier = Modifier.weight(1f),
                shape = RoundedCornerShape(12.dp),
            ) { Text("取消", fontSize = 13.sp) }
            Spacer(Modifier.width(9.dp))
            Button(
                onClick = {
                    val t = title.trim()
                    if (t.isEmpty()) {
                        error = "作业名不能空着"
                        return@Button
                    }
                    val dueAt = dueMillis
                    onSave(
                        ClassTaskDto(
                            // 新建用新 id；改和复制都沿用原来的（复制换新 id）
                            id = when {
                                existing != null -> existing.id
                                else -> UUID.randomUUID().toString()
                            },
                            title = t,
                            course = course.trim(),
                            dueAt = dueAt,
                            note = note.trim(),
                            updatedAt = System.currentTimeMillis(),
                        ),
                    )
                },
                modifier = Modifier.weight(1f),
                shape = RoundedCornerShape(12.dp),
            ) { Text("保存", fontSize = 13.sp) }
        }
    }
}

/** 从自己的日程里挑几条搬进清单 */
@Composable
private fun PickAgendaView(
    agendas: List<Agenda>,
    alreadyIn: Set<String>,
    scheme: androidx.compose.material3.ColorScheme,
    onCancel: () -> Unit,
    onConfirm: (List<Agenda>) -> Unit,
) {
    val candidates = remember(agendas) {
        agendas.filter { it.doneAt == null }
            .sortedWith(compareBy({ it.deadlineAt ?: Long.MAX_VALUE }, { it.title }))
    }
    var picked by remember { mutableStateOf(setOf<String>()) }

    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp)
            .padding(bottom = 26.dp),
    ) {
        BackRow(onCancel, "返回清单")
        Text(
            "从我的日程导入",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Medium,
            color = scheme.onSurface,
        )
        Spacer(Modifier.height(4.dp))
        Text(
            "挑要放进班级清单的条目。已经在清单里的会标出来，重复选不会产生两条。",
            fontSize = 12.sp,
            lineHeight = 17.sp,
            color = scheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(12.dp))

        if (candidates.isEmpty()) {
            Text(
                "你的日程里没有可导入的条目",
                fontSize = 12.5.sp,
                color = scheme.onSurfaceVariant,
                modifier = Modifier.padding(vertical = 30.dp),
            )
        } else {
            LazyColumn(
                Modifier
                    .fillMaxWidth()
                    .heightIn(max = 420.dp),
                verticalArrangement = Arrangement.spacedBy(7.dp),
                contentPadding = PaddingValues(bottom = 10.dp),
            ) {
                items(candidates, key = { it.id }) { a ->
                    val inPlan = a.id in alreadyIn
                    val on = a.id in picked
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clip(G3CornerShape(10.dp))
                            .background(
                                if (on) scheme.primary.copy(alpha = 0.12f)
                                else scheme.surfaceVariant.copy(alpha = 0.30f),
                            )
                            .then(
                                if (on) Modifier.border(1.dp, scheme.primary.copy(alpha = 0.6f), G3CornerShape(10.dp))
                                else Modifier,
                            )
                            .clickable(enabled = !inPlan) {
                                picked = if (on) picked - a.id else picked + a.id
                            }
                            .padding(horizontal = 11.dp, vertical = 9.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(
                                a.title,
                                fontSize = 12.5.sp,
                                color = if (inPlan) scheme.onSurfaceVariant else scheme.onSurface,
                                maxLines = 2,
                            )
                            Spacer(Modifier.height(2.dp))
                            Text(
                                listOfNotNull(
                                    if (a.isTask) "截止任务" else "日程",
                                    a.deadlineAt?.let { dueLabel(it) },
                                ).joinToString(" · "),
                                fontSize = 11.sp,
                                color = scheme.onSurfaceVariant.copy(alpha = 0.8f),
                            )
                        }
                        if (inPlan) {
                            Text("已在清单", fontSize = 11.sp, color = scheme.onSurfaceVariant)
                        } else {
                            Box(
                                Modifier
                                    .size(18.dp)
                                    .clip(RoundedCornerShape(5.dp))
                                    .background(
                                        if (on) scheme.primary else android.graphics.Color.TRANSPARENT.let { scheme.surface },
                                    )
                                    .border(1.2.dp, if (on) scheme.primary else scheme.outline, RoundedCornerShape(5.dp)),
                                contentAlignment = Alignment.Center,
                            ) {
                                if (on) {
                                    Icon(
                                        AppIcons.Check,
                                        contentDescription = null,
                                        modifier = Modifier.size(12.dp),
                                        tint = scheme.onPrimary,
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }

        Spacer(Modifier.height(14.dp))
        Row(Modifier.fillMaxWidth()) {
            OutlinedButton(
                onClick = onCancel,
                modifier = Modifier.weight(1f),
                shape = RoundedCornerShape(12.dp),
            ) { Text("取消", fontSize = 13.sp) }
            Spacer(Modifier.width(9.dp))
            Button(
                onClick = { onConfirm(candidates.filter { it.id in picked }) },
                enabled = picked.isNotEmpty(),
                modifier = Modifier.weight(1f),
                shape = RoundedCornerShape(12.dp),
            ) { Text(if (picked.isEmpty()) "选几条" else "导入 ${picked.size} 条", fontSize = 13.sp) }
        }
    }
}

@Composable
private fun HintBox(text: String, ok: Boolean, scheme: androidx.compose.material3.ColorScheme) {
    Column(
        Modifier
            .fillMaxWidth()
            .background(
                color = if (ok) scheme.primary.copy(alpha = 0.10f) else scheme.error.copy(alpha = 0.10f),
                shape = RoundedCornerShape(10.dp),
            )
            .padding(horizontal = 12.dp, vertical = 10.dp),
    ) {
        Text(
            text,
            fontSize = 12.sp,
            lineHeight = 17.sp,
            fontFamily = if (ok) FontFamily.Default else FontFamily.Monospace,
            color = if (ok) scheme.primary else scheme.error,
        )
    }
}

// ── 小工具 ────────────────────────────────────────────────────────────

private fun sortedTasks(plan: ClassPlan): List<ClassTaskDto> =
    plan.tasks.sortedWith(
        compareBy({ it.dueAt ?: Long.MAX_VALUE }, { it.title }),
    )

private fun epochToDay(ms: Long): LocalDate =
    Instant.ofEpochMilli(ms).atZone(ZoneId.systemDefault()).toLocalDate()

/** 表单里显示的日期：「11月25日 周二」 */
private fun dayLabel(ms: Long): String {
    val d = epochToDay(ms)
    val dow = listOf("周一", "周二", "周三", "周四", "周五", "周六", "周日")[d.dayOfWeek.value - 1]
    return "${d.monthValue}月${d.dayOfMonth}日 $dow"
}

/** 「11-25」这种短标签；没截止就「长期」 */
private fun dueLabel(ms: Long?): String =
    if (ms == null) "长期" else {
        val d = epochToDay(ms)
        "%02d-%02d".format(d.monthValue, d.dayOfMonth)
    }
