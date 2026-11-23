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
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
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
import com.gzucm.pocket.ui.components.CenterDialog
import com.gzucm.pocket.ui.components.DialogPrimaryButton
import com.gzucm.pocket.ui.components.DialogSecondaryButton
import com.gzucm.pocket.ui.components.G3CornerShape
import com.gzucm.pocket.ui.icons.AppIcons
import com.gzucm.pocket.core.ClassImportResult
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
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
    /**
     * 发布到本班云端（2026-11-22）。null = 这版功能不可用（没配 FC），
     * 按钮就不出现 —— 而不是点了报错。
     *
     * 参数带整份 plan 而不是让调用方自己从 state 里取：这里刚填的密钥、
     * 学籍里的班级都可能还没回写到 ViewModel，传值比读 state 稳。
     */
    onPublish: ((ClassPlan) -> Unit)? = null,
    /** 从本班拉取清单（手动兜底，和粘分享串并列）。null 时不显示 */
    onFetchFromClass: (() -> Unit)? = null,
    /**
     * 删掉已发布到本班的那一份（null = 功能不可用）。
     *
     * 2026-11-23 补：发布确认框和教程都写着「发布后可以删除」，
     * 但界面上一直没有这个入口 —— 承诺了没做，现在补上。
     */
    onDeletePublished: (() -> Unit)? = null,
    /** 本机维护的所有清单，用于「切换清单」那一屏 */
    plans: List<ClassPlan> = listOf(plan),
    /** 切到另一份去编辑（按密钥） */
    onSelectPlan: ((String) -> Unit)? = null,
    /** 新建一份清单（参数是学委刚起好的名字） */
    onCreatePlan: ((String) -> Unit)? = null,
    /** 已发布到本班的清单（按密钥），列表里标「已发布 / 草稿」 */
    publishedPlanKeys: Set<String> = emptySet(),
) {
    val ctx = LocalContext.current
    val scheme = MaterialTheme.colorScheme
    val clipboard = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager

    var mode by remember { mutableStateOf<Mode>(Mode.LIST) }
    /*
     * 进来先看「我的清单」，不是直接进编辑页（2026-11-23 致辉定的排版）。
     *
     * 编辑页是「一份清单的内部」，列表页才是「我手上有哪几摊事」。
     * 先看完有什么，再决定改哪一份，顺序才顺 —— 原来进来就是编辑页，
     * 等于默认学委只有一份清单。
     */
    var showPlanList by remember { mutableStateOf(true) }
    /** 「修改清单名」对话框中转的名字 */
    var renamingPlan by remember { mutableStateOf<String?>(null) }
    var hint by remember { mutableStateOf<Pair<String, Boolean>?>(null) }
    /** 待确认的导入：解析出来了但还没灌进日程 */
    var pendingImport by remember { mutableStateOf<ClassPlan?>(null) }

    /*
     * 密钥**不预填**（2026-11-21 致辉定的）：
     * 它填的是「全班共识的那个名字」（通常是学委），而登录这台手机的人
     * 可能只是帮忙维护的班长。自动填成自己的名字会让同学认错清单。
     * 维护人是另一个字段，那个才自动填。
     */
    var key by rememberSaveable { mutableStateOf(plan.key) }

    /** 「发布到本班」的确认框。每次发布都要过一次，不是只第一次 */
    var showPublishConfirm by remember { mutableStateOf(false) }

    /** 删除已发布清单的确认框 */
    var showDeleteConfirm by remember { mutableStateOf(false) }

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
        // 先解析、先预览，**不直接灌进日程**：一窝蜂导入会让人分不清多出来什么
        runCatching { decodeClassToken(text) }
            .onSuccess { pendingImport = it }
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

    if (showPublishConfirm) {
        val who = studentClass?.takeIf { it.isNotBlank() }
        CenterDialog(
            title = "发布到本班？",
            onDismiss = { showPublishConfirm = false },
            buttons = {
                DialogPrimaryButton(
                    text = "确认发布",
                    onClick = {
                        showPublishConfirm = false
                        val outgoing = plan.copy(
                            key = key.trim(),
                            className = who ?: plan.className,
                            maintainer = maintainer?.takeIf { it.isNotBlank() } ?: plan.maintainer,
                            updatedAt = System.currentTimeMillis(),
                        )
                        persist(outgoing)
                        onPublish?.invoke(outgoing)
                    },
                )
                DialogSecondaryButton(
                    text = "再改改",
                    onClick = { showPublishConfirm = false },
                )
            },
        ) {
            Text(
                "上传后${if (who != null) "「$who」全班" else "本班所有"}同学都会收到提醒，" +
                    "这不是你一个人的备忘。请确认这是全班都需要的作业，不要上传个人笔记。",
                fontSize = 12.5.sp,
                lineHeight = 19.sp,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Spacer(Modifier.height(10.dp))
            Text(
                "共 ${plan.tasks.size} 条 · 发布后可以删除，但不能改别人发布的清单",
                fontSize = 11.5.sp,
                lineHeight = 16.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }

    renamingPlan?.let { draft ->
        CenterDialog(
            title = "修改清单名",
            onDismiss = { renamingPlan = null },
            buttons = {
                DialogPrimaryButton(
                    text = "保存",
                    onClick = {
                        if (draft.isNotBlank()) {
                            persist(plan.copy(name = draft.trim()))
                        }
                        renamingPlan = null
                    },
                )
                DialogSecondaryButton("取消", { renamingPlan = null })
            },
        ) {
            Text(
                "同学认领后看到的就是这个名字。",
                fontSize = 12.5.sp,
                lineHeight = 19.sp,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Spacer(Modifier.height(10.dp))
            OutlinedTextField(
                value = draft,
                onValueChange = { renamingPlan = it.take(30) },
                label = { Text("清单名", fontSize = 12.sp) },
                placeholder = { Text("例如：期末作业清单", fontSize = 13.sp) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
            )
        }
    }

    if (showDeleteConfirm) {
        CenterDialog(
            title = "删除已发布的清单？",
            onDismiss = { showDeleteConfirm = false },
            buttons = {
                DialogPrimaryButton(
                    text = "删除",
                    onClick = {
                        showDeleteConfirm = false
                        onDeletePublished?.invoke()
                    },
                )
                DialogSecondaryButton(
                    text = "算了",
                    onClick = { showDeleteConfirm = false },
                )
            },
        ) {
            Text(
                "本班同学下次打开 App 会看到「清单已被删除」。" +
                    "他们已经导入的日程不会消失，会变成自己普通的日程。",
                fontSize = 12.5.sp,
                lineHeight = 19.sp,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Spacer(Modifier.height(10.dp))
            Text(
                "你自己这份清单还在，改完可以重新发布。",
                fontSize = 11.5.sp,
                lineHeight = 16.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }

    if (showPlanList) {
        PlanListScreen(
            plans = plans,
            publishedKeys = publishedPlanKeys,
            currentKey = plan.key,
            onPick = { key ->
                onSelectPlan?.invoke(key)
                showPlanList = false
            },
            onCreate = { name ->
                onCreatePlan?.invoke(name)
                showPlanList = false
            },
            // 第一屏的返回 = 关掉面板回「我的日程」。
            // 原来写成 `showPlanList = false` 会和编辑页的「返回我的清单」对跳 ——
            // 两个页面互相弹，点了谁也关不掉（2026-11-23 致辉测出来的）。
            onBack = onClose,
            scheme = scheme,
        )
    } else when (val m = mode) {
        Mode.LIST -> Column(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .padding(bottom = 26.dp)
                .verticalScroll(rememberScrollState()),
        ) {
            BackRow({ showPlanList = true }, "返回我的清单")

            if (!LocalIsWidePane.current) {
                Text(
                    "班级日程",
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.Medium,
                    color = scheme.onSurface,
                )
                Spacer(Modifier.height(8.dp))
            }
            Text(
                "维护一份作业清单，发布后本班同学打开 App 就能看到，" +
                    "认领一下，作业就进他们自己的日程。",
                fontSize = 12.5.sp,
                lineHeight = 18.sp,
                color = scheme.onSurfaceVariant,
            )

            Spacer(Modifier.height(12.dp))

            /*
             * 清单名显示成标题，改的时候点右边（2026-11-23 致辉定的排版）。
             *
             * 之前做成常驻的 OutlinedTextField，整页看着像个表单；
             * 但这一页的主语是「清单里的东西」，名字只是它的标题。
             */
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "「${plan.name.ifBlank { "未命名清单" }}」",
                    modifier = Modifier.weight(1f),
                    fontSize = 14.5.sp,
                    fontWeight = FontWeight.Medium,
                    color = scheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    "修改清单名",
                    fontSize = 12.sp,
                    color = scheme.primary,
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .clickable { renamingPlan = plan.name }
                        .padding(horizontal = 6.dp, vertical = 4.dp),
                )
            }
            Spacer(Modifier.height(5.dp))
            Text(
                "日程与任务：${plan.tasks.size} 条",
                fontSize = 12.5.sp,
                color = scheme.onSurfaceVariant,
            )

            Spacer(Modifier.height(6.dp))
            /*
             * 教程入口写在这一屏，而不是「设置 → 帮助」里：
             * 学委第一次打开这个面板时手上没有清单、也不知道该不该发，
             * 这正是他最需要看说明的时刻。离开这一屏之后就再没有这个时机了。
             */
            Row(
                Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .clickable {
                        runCatching { ctx.startActivity(ClassPlanGuideActivity.intent(ctx)) }
                    }
                    .padding(vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    AppIcons.Info,
                    contentDescription = null,
                    modifier = Modifier.size(13.dp),
                    tint = scheme.primary,
                )
                Spacer(Modifier.width(5.dp))
                Text(
                    "第一次发清单？先看一遍教程",
                    fontSize = 12.sp,
                    color = scheme.primary,
                )
            }

            Spacer(Modifier.height(14.dp))

            // 自动识别的两项：班级（云端主索引）和维护人（谁在上传）
            InfoLine("本班", studentClass, scheme)
            InfoLine("维护人", maintainer, scheme)

            // 换一份 / 再建一份。只有一份时不显示（点进去就是它，没什么可换的）
            if ((plans.size > 1 || onCreatePlan != null) && !LocalIsWidePane.current) {
                Spacer(Modifier.height(10.dp))
                Row(
                    Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .clickable { showPlanList = true }
                        .padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        AppIcons.EventNote,
                        contentDescription = null,
                        modifier = Modifier.size(13.dp),
                        tint = scheme.primary,
                    )
                    Spacer(Modifier.width(5.dp))
                    Text(
                        if (plans.size > 1) "换一份清单（本机 ${plans.size} 份）" else "再建一份清单",
                        fontSize = 12.sp,
                        color = scheme.primary,
                    )
                }
            }

            Spacer(Modifier.height(18.dp))

            /*
             * 导入预览：先看清「谁发的、几条、会不会覆盖」，再决定。
             *
             * 人数一多，一窝蜂导入最危险 —— 用户第二天发现日程里多出八条
             * 不知道谁加的作业，还得一条条辨认。这一步多花两秒，省一路返工。
             */
            pendingImport?.let { incoming ->
                val preview = remember(incoming, myAgendas) {
                    mergeClassTasks(myAgendas, incoming)
                }
                val cls = incoming.className.takeIf { it.isNotBlank() }
                val who = incoming.key.ifBlank { "同学" }
                Column(
                    Modifier
                        .fillMaxWidth()
                        .clip(G3CornerShape(12.dp))
                        .background(scheme.primary.copy(alpha = 0.08f))
                        .border(1.dp, scheme.primary.copy(alpha = 0.45f), G3CornerShape(12.dp))
                        .padding(13.dp),
                ) {
                    Text(
                        "$who 的清单",
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Medium,
                        color = scheme.onSurface,
                    )
                    if (cls != null) {
                        Spacer(Modifier.height(2.dp))
                        Text(cls, fontSize = 11.5.sp, color = scheme.onSurfaceVariant)
                    }
                    Spacer(Modifier.height(7.dp))
                    Text(
                        preview.second.summary(),
                        fontSize = 11.5.sp,
                        lineHeight = 16.sp,
                        color = scheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(10.dp))
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                        Text(
                            "取消",
                            fontSize = 12.5.sp,
                            color = scheme.onSurfaceVariant,
                            modifier = Modifier
                                .clip(RoundedCornerShape(8.dp))
                                .clickable { pendingImport = null }
                                .padding(horizontal = 10.dp, vertical = 6.dp),
                        )
                        Spacer(Modifier.width(6.dp))
                        Button(
                            onClick = {
                                onImportToAgenda(preview.first)
                                pendingImport = null
                                hint = "$who 的清单：${preview.second.summary()}" to true
                            },
                            shape = RoundedCornerShape(10.dp),
                        ) {
                            Text(
                                if (preview.second.hasChanges) {
                                    "导入 ${preview.second.added + preview.second.updated} 条"
                                } else {
                                    "确认"
                                },
                                fontSize = 12.5.sp,
                            )
                        }
                    }
                }
                Spacer(Modifier.height(16.dp))
            }

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

            /*
             * 密钥紧贴发布按钮（2026-11-22 改）。
             *
             * 原来它排在任务列表**上方**：任务一多，用户往下滚到「发布到本班」时，
             * 密钥框早就被顶出屏幕了，眼前只剩一个点了会报错的按钮。
             * 致辉实测就是这样 —— 任务清单里找不到填密钥的地方。
             *
             * 现在它是「发布」区块的第一行，和按钮同屏，并在为空时把按钮置灰：
             * 让人在按下去之前就知道缺什么，而不是按了才被告知。
             */
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(1.dp)
                    .background(scheme.outlineVariant.copy(alpha = 0.45f)),
            )
            Spacer(Modifier.height(16.dp))

            Text(
                "发布到本班",
                fontSize = 13.sp,
                fontWeight = FontWeight.Medium,
                color = scheme.onSurface,
            )
            Spacer(Modifier.height(8.dp))

            OutlinedTextField(
                value = key,
                onValueChange = {
                    key = it.take(12)
                    persist(plan.copy(key = key))
                },
                label = { Text("密钥", fontSize = 12.sp) },
                // 这句提示是致辉反复要求的固定文案（2026-11-23 又强调一次）：
                // 学委不知道该填什么，同学也不知道该输什么，都靠这一行。
                placeholder = { Text("一般是学委名字", fontSize = 13.sp) },
                singleLine = true,
                isError = key.isBlank(),
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
            )
            Spacer(Modifier.height(6.dp))
            Text(
                if (key.isBlank()) {
                    "填了密钥才能发布 —— 一般填学委的名字"
                } else {
                    "同学打开 App 要输这个才能看到清单。填完记得在群里说一声「密钥是学委名字」"
                },
                fontSize = 11.5.sp,
                lineHeight = 16.sp,
                color = if (key.isBlank()) {
                    scheme.error
                } else {
                    scheme.onSurfaceVariant.copy(alpha = 0.8f)
                },
            )

            Spacer(Modifier.height(12.dp))

            Row(Modifier.fillMaxWidth()) {
                Button(
                    onClick = {
                        val name = key.trim()
                        if (name.isEmpty()) {
                            hint = "先填密钥 —— 填全班都知道的那个名字，通常是学委" to false
                        } else if (plan.tasks.isEmpty()) {
                            hint = "清单还是空的，先加几条作业再发布" to false
                        } else if (plan.tasks.size > CLASS_PLAN_MAX_TASKS) {
                            hint = "一份清单最多 $CLASS_PLAN_MAX_TASKS 条，现在 ${plan.tasks.size} 条" to false
                        } else {
                            /*
                             * 每次发布都确认（不是只第一次）：
                             * 这是唯一会让全班手机响一次的动作，手滑的成本比多点一下高得多。
                             */
                            showPublishConfirm = true
                        }
                    },
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(12.dp),
                    // 密钥不填就不让按：按了才报错的成本比置灰高一截
                    enabled = onPublish != null && key.isNotBlank(),
                ) { Text("发布到本班", fontSize = 12.5.sp) }
                Spacer(Modifier.width(9.dp))
                OutlinedButton(
                    onClick = { share() },
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(12.dp),
                ) { Text("分享给同学", fontSize = 12.5.sp) }
            }

            Spacer(Modifier.height(9.dp))

            Row(Modifier.fillMaxWidth()) {
                OutlinedButton(
                    onClick = { onFetchFromClass?.invoke() },
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(12.dp),
                    enabled = onFetchFromClass != null,
                ) { Text("从本班获取", fontSize = 12.5.sp) }
                Spacer(Modifier.width(9.dp))
                OutlinedButton(
                    onClick = { importFromClip() },
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(12.dp),
                ) { Text("粘贴分享串", fontSize = 12.5.sp) }
            }

            if (onDeletePublished != null && key.isNotBlank()) {
                Spacer(Modifier.height(18.dp))
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(1.dp)
                        .background(scheme.outlineVariant.copy(alpha = 0.45f)),
                )
                Spacer(Modifier.height(14.dp))
                Text(
                    "删除已发布的清单",
                    fontSize = 12.5.sp,
                    fontWeight = FontWeight.Medium,
                    color = scheme.error,
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .clickable { showDeleteConfirm = true }
                        .padding(horizontal = 2.dp, vertical = 4.dp),
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    "只从班上撤下来，你自己这份清单和同学已导入的日程都还在",
                    fontSize = 11.5.sp,
                    lineHeight = 16.sp,
                    color = scheme.onSurfaceVariant.copy(alpha = 0.8f),
                )
            }

            if (onPublish != null) {
                Spacer(Modifier.height(7.dp))
                Text(
                    "发布后本班会收到提醒 · 每人最多 2 份清单，每份 $CLASS_PLAN_MAX_TASKS 条",
                    fontSize = 11.sp,
                    lineHeight = 15.sp,
                    color = scheme.onSurfaceVariant.copy(alpha = 0.75f),
                )
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
            // 导入来的条目沿用日程的 id，用它认「哪些是日程导入、时间只读」
            agendaIds = remember(myAgendas) { myAgendas.map { it.id }.toSet() },
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
                if (task.isEvent) {
                    listOfNotNull(
                        task.course.takeIf { it.isNotBlank() },
                        "${dayLabel(task.day)} ${hhmm(task.startMinute)}-${hhmm(task.endMinute)}",
                        task.place.takeIf { it.isNotBlank() },
                    ).joinToString(" · ")
                } else {
                    listOfNotNull(
                        task.course.takeIf { it.isNotBlank() },
                        dueLabelWithTime(task.dueAt),
                    ).joinToString(" · ")
                },
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

/** 清单条目的两种形态。日程和截止任务各有一套字段，切换时两套状态各自保留 */
private enum class TaskKind { DUE, EVENT }

/** 新建 / 编辑一条作业。内联在这一页里，不新开窗口 */
@Composable
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
private fun TaskEditView(
    existing: ClassTaskDto?,
    copyFrom: ClassTaskDto?,
    scheme: androidx.compose.material3.ColorScheme,
    /**
     * 本机全部日程的 id。
     *
     * 只用来认出「从日程导入」的条目 —— 导入时原样沿用了那条日程的 id
     * （见 `Agenda.toClassTask`），清单里自己新建的条目拿的是新 UUID。
     * 光看 `day` 非空区分不出来：这一版放开「日程」类型之后，
     * 自己新建的日程形态条目也有 day，而且必须可编辑。
     */
    agendaIds: Set<String>,
    onCancel: () -> Unit,
    onSave: (ClassTaskDto) -> Unit,
) {
    val source = existing ?: copyFrom
    /** 编辑的是一条从日程导入的条目（时间只读） */
    val importedEvent = existing != null && existing.isEvent && existing.id in agendaIds

    /** 原值的本地时间表示，回填时分用；只算一次，三个初始值共用 */
    val sourceDueLocal = source?.dueAt
        ?.let { Instant.ofEpochMilli(it).atZone(ZoneId.systemDefault()).toLocalDateTime() }

    var kind by rememberSaveable {
        mutableStateOf(if (source?.isEvent == true) TaskKind.EVENT else TaskKind.DUE)
    }
    var title by rememberSaveable { mutableStateOf(source?.title.orEmpty()) }
    var course by rememberSaveable { mutableStateOf(source?.course.orEmpty()) }
    var note by rememberSaveable { mutableStateOf(source?.note.orEmpty()) }
    var error by remember { mutableStateOf<String?>(null) }

    // 截止任务：日期（可空 = 长期待办）+ 时分，默认 23:59
    var dueDay by rememberSaveable { mutableStateOf(sourceDueLocal?.toLocalDate()?.toString()) }
    var dueHour by rememberSaveable { mutableStateOf(sourceDueLocal?.hour ?: 23) }
    var dueMinute by rememberSaveable { mutableStateOf(sourceDueLocal?.minute ?: 59) }

    // 日程：日期 + 起止分钟 + 地点
    var eventDay by rememberSaveable {
        mutableStateOf(source?.day?.takeIf { it.isNotBlank() } ?: LocalDate.now().toString())
    }
    var startMinute by rememberSaveable {
        mutableStateOf(if (source?.isEvent == true) source.startMinute else 8 * 60 + 30)
    }
    var endMinute by rememberSaveable {
        mutableStateOf(if (source?.isEvent == true) source.endMinute else 9 * 60 + 30)
    }
    var place by rememberSaveable { mutableStateOf(source?.place.orEmpty()) }

    /**
     * 同时只展开一个选择器（"dueDate" / "dueTime" / "eventDate" / "start" / "end"）。
     *
     * 跟着 AgendaEditSheet 的做法互斥：日历 360dp 高、滚轮 132dp 高，
     * 放开两个同时展开，表单会被拉长一倍多，后面的字段全被挤出屏幕。
     */
    var openPicker by remember { mutableStateOf<String?>(null) }

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
                existing != null -> if (kind == TaskKind.EVENT) "改一条日程" else "改一条作业"
                copyFrom != null -> if (kind == TaskKind.EVENT) "复制一条日程" else "复制一条作业"
                kind == TaskKind.EVENT -> "新建日程"
                else -> "新建作业"
            },
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Medium,
            color = scheme.onSurface,
        )
        Spacer(Modifier.height(14.dp))

        /*
         * 类型切换：截止任务 / 日程。
         *
         * 视觉照搬 AgendaEditSheet 的「按节次 / 按时间」胶囊条 —— 同一种表单
         * 在两处出现，长得不一样会让人以为是两个功能。
         *
         * 从日程导入的条目不出这个开关：它的时间该回日程里改（见下面只读卡片）。
         */
        if (!importedEvent) {
            Text("类型", fontSize = 12.sp, color = scheme.onSurfaceVariant)
            Spacer(Modifier.height(6.dp))
            Row(
                Modifier
                    .fillMaxWidth()
                    .clip(G3CornerShape(10.dp))
                    .background(scheme.onSurfaceVariant.copy(alpha = 0.09f))
                    .padding(3.dp),
                horizontalArrangement = Arrangement.spacedBy(3.dp),
            ) {
                listOf(TaskKind.DUE to "截止任务", TaskKind.EVENT to "日程").forEach { (k, label) ->
                    val on = k == kind
                    Box(
                        Modifier
                            .weight(1f)
                            .clip(G3CornerShape(8.dp))
                            .background(
                                if (on) scheme.primary.copy(alpha = 0.16f)
                                else Color.Transparent
                            )
                            .clickable {
                                if (k != kind) {
                                    kind = k
                                    // 切换后旧的报错（「结束时间要晚于开始时间」之类）已经无关
                                    error = null
                                    openPicker = null
                                }
                            }
                            .padding(vertical = 8.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            label,
                            fontSize = 12.5.sp,
                            fontWeight = if (on) FontWeight.Medium else FontWeight.Normal,
                            color = if (on) scheme.primary else scheme.onSurfaceVariant,
                        )
                    }
                }
            }
            Spacer(Modifier.height(14.dp))
        }

        OutlinedTextField(
            value = title,
            onValueChange = { title = it.take(60); error = null },
            label = {
                Text(if (kind == TaskKind.EVENT) "名称" else "作业名", fontSize = 12.sp)
            },
            placeholder = {
                Text(
                    if (kind == TaskKind.EVENT) "例如：中医内科见习" else "例如：中医内科学期末论文",
                    fontSize = 13.sp,
                )
            },
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

        if (importedEvent) {
            /*
             * 从日程导入的条目：时间和地点只读。
             *
             * 不在这里给「改时间」的入口 —— 清单里的日程只是「顺手带过来」的，
             * 真要调时间该回日程里改。两边都放开会造出两份互相打架的时间。
             */
            Column(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(scheme.surfaceVariant.copy(alpha = 0.35f))
                    .padding(horizontal = 14.dp, vertical = 13.dp),
            ) {
                Text(
                    listOfNotNull(
                        source?.day?.takeIf { it.isNotBlank() }?.let { dayLabel(it) },
                        source?.let { "${hhmm(it.startMinute)} — ${hhmm(it.endMinute)}" },
                        source?.place?.takeIf { it.isNotBlank() },
                    ).joinToString(" · "),
                    fontSize = 12.5.sp,
                    color = scheme.onSurface,
                )
                Spacer(Modifier.height(3.dp))
                Text(
                    "从日程导入，时间和地点保持原样；要改请回日程里改",
                    fontSize = 11.sp,
                    color = scheme.onSurfaceVariant.copy(alpha = 0.75f),
                )
            }
            Spacer(Modifier.height(10.dp))
        } else if (kind == TaskKind.DUE) {
            /*
             * 截止日期：点开就是系统日历（M3 DatePicker），**不用文本输入**，
             * 也**不能**用 DatePickerDialog —— 那会在 AdaptiveSheet 上再叠一个
             * Dialog 窗口，BACK 被内层吃掉之后 Activity 又收到一次，直接退回桌面
             * （项目里踩过，见 PITFALLS）。日历摊在表单里，见 [InlineDateCard]。
             */
            InlineDateCard(
                label = if (dueDay == null) "截止日期（选填）" else "截止日期",
                iso = dueDay,
                placeholder = "长期待办",
                pickerTitle = "选择截止日期",
                expanded = openPicker == "dueDate",
                // 只有截止任务能留空（= 长期待办）；日程必须有一个日期
                allowClear = true,
                onToggle = { openPicker = if (openPicker == "dueDate") null else "dueDate" },
                onClear = { dueDay = null; openPicker = null },
                onConfirm = { dueDay = it; openPicker = null },
                scheme = scheme,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                "留空就是长期待办，没有截止时间",
                fontSize = 11.sp,
                color = scheme.onSurfaceVariant.copy(alpha = 0.75f),
            )
            /*
             * 时分只在选了日期之后才出现：没有日期的话，设几点都没有落点，
             * 亮着反而让人以为「设了 10:00 就成了一条 10:00 的待办」。
             */
            if (dueDay != null) {
                Spacer(Modifier.height(10.dp))
                FieldRow(
                    label = "截止时间",
                    value = hhmm(dueHour * 60 + dueMinute),
                    expanded = openPicker == "dueTime",
                    onClick = { openPicker = if (openPicker == "dueTime") null else "dueTime" },
                )
                if (openPicker == "dueTime") {
                    Spacer(Modifier.height(4.dp))
                    MinuteWheels(dueHour * 60 + dueMinute) { dueHour = it / 60; dueMinute = it % 60 }
                    Spacer(Modifier.height(2.dp))
                    Text(
                        "默认 23:59 —— 当天要交的事，到当天结束前都还算没迟",
                        fontSize = 11.sp,
                        color = scheme.onSurfaceVariant.copy(alpha = 0.75f),
                    )
                }
            }
        } else {
            // ── 日程形态 ────────────────────────────────────────────────
            InlineDateCard(
                label = "日期",
                iso = eventDay,
                placeholder = "选择日期",
                pickerTitle = "选择日期",
                expanded = openPicker == "eventDate",
                allowClear = false,
                onToggle = { openPicker = if (openPicker == "eventDate") null else "eventDate" },
                onClear = {},
                onConfirm = { eventDay = it; openPicker = null },
                scheme = scheme,
            )
            Spacer(Modifier.height(10.dp))

            FieldRow(
                label = "开始时间",
                value = hhmm(startMinute),
                expanded = openPicker == "start",
                onClick = { openPicker = if (openPicker == "start") null else "start" },
            )
            if (openPicker == "start") {
                Spacer(Modifier.height(4.dp))
                MinuteWheels(startMinute) { startMinute = it }
            }
            Spacer(Modifier.height(6.dp))

            FieldRow(
                label = "结束时间",
                value = hhmm(endMinute),
                expanded = openPicker == "end",
                onClick = { openPicker = if (openPicker == "end") null else "end" },
            )
            if (openPicker == "end") {
                Spacer(Modifier.height(4.dp))
                MinuteWheels(endMinute) { endMinute = it }
            }
            Spacer(Modifier.height(4.dp))
            Text(
                "${hhmm(startMinute)} — ${hhmm(endMinute)}",
                fontSize = 11.sp,
                color = scheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(10.dp))

            OutlinedTextField(
                value = place,
                onValueChange = { place = it.take(40) },
                label = { Text("地点（选填）", fontSize = 12.sp) },
                placeholder = { Text("例如：教学楼 A302", fontSize = 12.sp) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
            )
        }
        Spacer(Modifier.height(10.dp))

        OutlinedTextField(
            value = note,
            onValueChange = { note = it.take(300) },
            // 「要求」是作业的说法；日程形态下它就是普通备注
            label = {
                Text(if (kind == TaskKind.EVENT) "备注（选填）" else "要求（选填）", fontSize = 12.sp)
            },
            placeholder = {
                Text(
                    if (kind == TaskKind.EVENT) "带什么、注意什么…" else "字数、格式、交到哪里…",
                    fontSize = 12.sp,
                )
            },
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
                        error = if (kind == TaskKind.EVENT) "名称不能空着" else "作业名不能空着"
                        return@Button
                    }
                    /*
                     * 日程必须是一段有方向的时段：结束早于或等于开始，
                     * 存下去在今日页会画成一块倒着的方块，时间区间也没有意义。
                     * 截止任务没有区间概念，不校验。
                     */
                    if (kind == TaskKind.EVENT && endMinute <= startMinute) {
                        error = "结束时间要晚于开始时间"
                        return@Button
                    }
                    val id = existing?.id ?: UUID.randomUUID().toString()
                    // importedEvent 已经蕴含 existing != null，这里只是给编译器一个非空引用
                    val imported = if (importedEvent) existing else null
                    onSave(
                        when {
                            /*
                             * 从日程导入的条目：只回写用户能改的三项，时间和地点
                             * 原样带回去 —— 这是上面只读卡片的同一条约束。
                             */
                            imported != null -> imported.copy(
                                title = t,
                                course = course.trim(),
                                note = note.trim(),
                                updatedAt = System.currentTimeMillis(),
                            )
                            kind == TaskKind.EVENT -> ClassTaskDto(
                                id = id,
                                title = t,
                                course = course.trim(),
                                // 日程形态：day 非空，dueAt 必须置空，否则两边都会认领它
                                day = eventDay,
                                startMinute = startMinute,
                                endMinute = endMinute,
                                place = place.trim(),
                                dueAt = null,
                                note = note.trim(),
                                updatedAt = System.currentTimeMillis(),
                            )
                            else -> ClassTaskDto(
                                id = id,
                                title = t,
                                course = course.trim(),
                                /*
                                 * 截止任务没有「当天时段」的概念，日程三件套一律清空。
                                 * 不清的话，从日程切成截止任务后旧 day 还在，
                                 * 这条会被继续当日程渲染，dueAt 反而没人看。
                                 */
                                day = "",
                                startMinute = 0,
                                endMinute = 0,
                                place = "",
                                dueAt = dueDay?.let { d ->
                                    LocalDate.parse(d)
                                        .atTime(dueHour, dueMinute)
                                        .atZone(ZoneId.systemDefault())
                                        .toInstant().toEpochMilli()
                                },
                                note = note.trim(),
                                updatedAt = System.currentTimeMillis(),
                            )
                        },
                    )
                },
                modifier = Modifier.weight(1f),
                shape = RoundedCornerShape(12.dp),
            ) { Text("保存", fontSize = 13.sp) }
        }
    }
}

/**
 * 内联展开的日期卡片（标签 + 已选日期 + 展开的 M3 日历）。
 *
 * **不用 DatePickerDialog**：那会在 AdaptiveSheet 上再叠一个 Dialog 窗口，
 * BACK 被内层吃掉之后 Activity 又收到一次，直接退回桌面（见 PITFALLS）。
 * 所以日历就摊在表单里 —— 和这个表单原来「截止日期」那一段是同一套做法，
 * 只是抽出来给截止任务和日程共用。
 */
@Composable
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
private fun InlineDateCard(
    label: String,
    iso: String?,
    placeholder: String,
    pickerTitle: String,
    expanded: Boolean,
    /** 能不能清空：只有截止任务可以（空 = 长期待办）；日程必须带一个日期 */
    allowClear: Boolean,
    onToggle: () -> Unit,
    onClear: () -> Unit,
    onConfirm: (String) -> Unit,
    scheme: androidx.compose.material3.ColorScheme,
) {
    /*
     * DatePicker 的 selectedDateMillis 是「UTC 的当天零点」，不是本地零点。
     * 回填和回读都按 UTC 折算，否则在负时区会整体差一天
     * （东八区刚好也是同一天，所以原来的写法在这边看不出问题）。
     */
    fun msToIso(ms: Long): String =
        Instant.ofEpochMilli(ms).atZone(ZoneOffset.UTC).toLocalDate().toString()

    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .border(
                1.dp,
                if (expanded) scheme.primary else scheme.outline,
                RoundedCornerShape(12.dp),
            )
            .clickable(onClick = onToggle)
            .padding(horizontal = if (expanded) 0.dp else 14.dp, vertical = 13.dp),
    ) {
        Row(
            Modifier.padding(horizontal = if (expanded) 14.dp else 0.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(label, fontSize = 11.5.sp, color = scheme.onSurfaceVariant)
            Spacer(Modifier.weight(1f))
            Text(
                iso?.let { dayLabel(it) } ?: placeholder,
                fontSize = 13.sp,
                color = if (iso == null) scheme.onSurfaceVariant else scheme.onSurface,
            )
        }
        if (expanded) {
            val pickerState = androidx.compose.material3.rememberDatePickerState(
                initialSelectedDateMillis = iso
                    ?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
                    ?.atStartOfDay(ZoneOffset.UTC)
                    ?.toInstant()?.toEpochMilli(),
            )
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                if (allowClear) {
                    Text(
                        "清除",
                        fontSize = 12.5.sp,
                        color = scheme.onSurfaceVariant,
                        modifier = Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .clickable(onClick = onClear)
                            .padding(horizontal = 10.dp, vertical = 6.dp),
                    )
                    Spacer(Modifier.width(4.dp))
                }
                Text(
                    "确定",
                    fontSize = 12.5.sp,
                    color = scheme.primary,
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .clickable {
                            pickerState.selectedDateMillis?.let { onConfirm(msToIso(it)) }
                        }
                        .padding(horizontal = 10.dp, vertical = 6.dp),
                )
            }
            androidx.compose.material3.DatePicker(
                state = pickerState,
                // 默认标题是英文（M3 没带中文资源），这里覆盖掉
                title = {
                    Text(
                        pickerTitle,
                        fontSize = 12.5.sp,
                        color = scheme.onSurfaceVariant,
                        modifier = Modifier.padding(start = 24.dp, top = 16.dp),
                    )
                },
                headline = {
                    Text(
                        pickerState.selectedDateMillis?.let { dayLabel(msToIso(it)) } ?: "未选择",
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
}

/**
 * 「时 : 分」两列滚轮，值用「当天第几分钟」表达（和 ClassTaskDto 一致）。
 *
 * 这里必须用 rememberUpdatedState 现读当前值：[WheelColumn] 里的
 * LaunchedEffect 只在 (state, count) 变化时重启，会一直握着**第一次**传进去的
 * onSelect。如果回调里直接捕获 hour/minute，先滚「时」再滚「分」时，
 * 后一次回调拿到的还是旧的小时，会把刚选的时顶回去。
 */
@Composable
private fun MinuteWheels(minuteOfDay: Int, onChange: (Int) -> Unit) {
    val scheme = MaterialTheme.colorScheme
    val current by rememberUpdatedState(minuteOfDay)
    val change by rememberUpdatedState(onChange)
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        WheelColumn(
            count = 24,
            initialIndex = minuteOfDay / 60,
            label = { "%02d".format(it) },
            onSelect = { h -> change(h * 60 + current % 60) },
            modifier = Modifier.weight(1f),
        )
        Text(":", fontSize = 16.sp, color = scheme.onSurfaceVariant)
        WheelColumn(
            count = 60,
            initialIndex = minuteOfDay % 60,
            label = { "%02d".format(it) },
            onSelect = { m -> change(current / 60 * 60 + m) },
            modifier = Modifier.weight(1f),
        )
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
        compareBy({ it.sortKey() }, { it.title }),
    )

/** 排序键：日程按当天零点，DDL 按截止时刻；都没有的排最后 */
private fun ClassTaskDto.sortKey(): Long = when {
    isEvent -> runCatching { LocalDate.parse(day).toEpochDay() * 86_400_000L }
        .getOrDefault(Long.MAX_VALUE)
    else -> dueAt ?: Long.MAX_VALUE
}

/** 「11月25日 周二」 */
private fun dayLabel(iso: String): String {
    val d = runCatching { LocalDate.parse(iso) }.getOrNull() ?: return iso
    val dow = listOf("周一", "周二", "周三", "周四", "周五", "周六", "周日")[d.dayOfWeek.value - 1]
    return "${d.monthValue}月${d.dayOfMonth}日 $dow"
}

private fun hhmm(minute: Int): String = "%02d:%02d".format(minute / 60, minute % 60)

private fun epochToDay(ms: Long): LocalDate =
    Instant.ofEpochMilli(ms).atZone(ZoneId.systemDefault()).toLocalDate()

/** 表单里显示的日期：「11月25日 周二」 */
private fun dayLabel(ms: Long): String {
    val d = epochToDay(ms)
    val dow = listOf("周一", "周二", "周三", "周四", "周五", "周六", "周日")[d.dayOfWeek.value - 1]
    return "${d.monthValue}月${d.dayOfMonth}日 $dow"
}

/**
 * 「11-25」这种短标签；没截止就「长期」。
 *
 * 跨年的带上年份：12 月建一条 1 月 5 号交的作业，只写「01-05」
 * 会让人以为是去年那件事。
 */
private fun dueLabel(ms: Long?): String =
    if (ms == null) "长期" else {
        val d = epochToDay(ms)
        if (d.year == LocalDate.now().year) {
            "%02d-%02d".format(d.monthValue, d.dayOfMonth)
        } else {
            "${d.year}-%02d-%02d".format(d.monthValue, d.dayOfMonth)
        }
    }

/**
 * 清单行里的截止：日期；**只有设了非默认时分才补 HH:mm**。
 *
 * 表单里默认 23:59（当天结束前都算没迟），绝大多数条目都是这个值，
 * 每行都拖一条「23:59」只是噪音；但用户特意改成 14:00 的那种，
 * 不显示出来就等于白设了。
 */
private fun dueLabelWithTime(ms: Long?): String {
    if (ms == null) return "长期"
    val t = Instant.ofEpochMilli(ms).atZone(ZoneId.systemDefault())
    val base = dueLabel(ms)
    return if (t.hour == 23 && t.minute == 59) base else "$base %02d:%02d".format(t.hour, t.minute)
}

/** 一份清单最多几条。和云函数 `bad_count` 的阈值必须一致（契约 §2.3） */
private const val CLASS_PLAN_MAX_TASKS = 80

/**
 * 「我的清单」那一屏：本机维护的所有清单，点一份进去改。
 *
 * 为什么单独一层（2026-11-23 致辉定的「先建清单再加条目」）：
 * 一个班常常不止一摊事 —— 期末作业、见习安排、实验报告各是各的清单，
 * 各有各的名字和密钥。把这些全塞在一个列表里，同学认领时根本分不清
 * 「这 13 条是作业还是见习安排」。
 */
@Composable
private fun PlanListScreen(
    plans: List<ClassPlan>,
    publishedKeys: Set<String>,
    currentKey: String,
    onPick: (String) -> Unit,
    onCreate: (String) -> Unit,
    onBack: () -> Unit,
    scheme: androidx.compose.material3.ColorScheme,
) {
    var naming by remember { mutableStateOf(false) }
    var draftName by remember { mutableStateOf("") }

    // 先起名，再进编辑（2026-11-23 致辉定的顺序）。名字是这份清单的身份，
    // 建的时候空着，列表里就只能显示「未命名清单」，等于没法认。
    if (naming) {
        CenterDialog(
            title = "新建清单",
            onDismiss = { naming = false },
            buttons = {
                DialogPrimaryButton(
                    text = "建好了",
                    onClick = {
                        if (draftName.isNotBlank()) {
                            naming = false
                            onCreate(draftName.trim())
                        }
                    },
                )
                DialogSecondaryButton("取消", { naming = false })
            },
        ) {
            Text(
                "这份清单叫什么？同学认领之后看到的就是这个名字。",
                fontSize = 12.5.sp,
                lineHeight = 19.sp,
                color = scheme.onSurface,
            )
            Spacer(Modifier.height(10.dp))
            OutlinedTextField(
                value = draftName,
                onValueChange = { draftName = it.take(30) },
                label = { Text("清单名", fontSize = 12.sp) },
                placeholder = { Text("例如：期末作业清单", fontSize = 13.sp) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
            )
        }
    }

    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp)
            .padding(bottom = 26.dp)
            .verticalScroll(rememberScrollState()),
    ) {
        BackRow(onBack, "返回我的日程")

        if (!LocalIsWidePane.current) {
            Text(
                "班级日程",
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Medium,
                color = scheme.onSurface,
            )
            Spacer(Modifier.height(10.dp))
        }

        // 「我的清单：N 条」+ 右侧「＋ 新建清单」
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "我的清单：${plans.size} 条",
                fontSize = 12.5.sp,
                color = scheme.onSurfaceVariant,
            )
            Spacer(Modifier.weight(1f))
            Text(
                "＋ 新建清单",
                fontSize = 12.5.sp,
                fontWeight = FontWeight.Medium,
                color = scheme.primary,
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .clickable {
                        draftName = ""
                        naming = true
                    }
                    .padding(horizontal = 6.dp, vertical = 4.dp),
            )
        }

        Spacer(Modifier.height(10.dp))

        if (plans.isEmpty()) {
            Text(
                "还没有清单。一摊事一份 —— 期末作业、见习安排各建一份，" +
                    "同学凭各自的密钥认领。",
                fontSize = 12.sp,
                lineHeight = 17.sp,
                color = scheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(10.dp))
        }

        plans.forEach { p ->
            val published = p.key in publishedKeys
            Row(
                Modifier
                    .fillMaxWidth()
                    .clip(G3CornerShape(12.dp))
                    .background(
                        if (p.key == currentKey) {
                            scheme.primary.copy(alpha = 0.09f)
                        } else {
                            scheme.surfaceVariant.copy(alpha = 0.28f)
                        },
                    )
                    .clickable { onPick(p.key) }
                    .padding(horizontal = 13.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        "「${p.name.ifBlank { "未命名清单" }}」",
                        fontSize = 13.5.sp,
                        fontWeight = FontWeight.Medium,
                        color = scheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Spacer(Modifier.height(3.dp))
                    Text(
                        buildString {
                            append("密钥：")
                            append(p.key.ifBlank { "未设" })
                            append("，")
                            append(if (published) "已发布" else "草稿")
                        },
                        fontSize = 11.sp,
                        color = scheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Icon(
                    AppIcons.ChevronRight,
                    contentDescription = null,
                    modifier = Modifier.size(15.dp),
                    tint = scheme.onSurfaceVariant.copy(alpha = 0.6f),
                )
            }
            Spacer(Modifier.height(7.dp))
        }
    }
}
