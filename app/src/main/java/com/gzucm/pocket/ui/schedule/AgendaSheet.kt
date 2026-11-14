@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.gzucm.pocket.ui.schedule

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.gestures.snapping.rememberSnapFlingBehavior
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.snapshotFlow
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.NotificationsActive
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.gzucm.pocket.core.Agenda
import com.gzucm.pocket.core.AgendaOccurrence
import com.gzucm.pocket.core.AgendaLogic
import com.gzucm.pocket.core.AgendaReminder
import com.gzucm.pocket.core.EXAM_COLOR_INDEX
import com.gzucm.pocket.core.ExamItem
import com.gzucm.pocket.core.Semester
import com.gzucm.pocket.ui.adaptive.AdaptiveSheet
import com.gzucm.pocket.ui.adaptive.LocalIsWidePane
import com.gzucm.pocket.ui.theme.CoursePalette
import com.gzucm.pocket.ui.theme.courseColor
import java.time.LocalDate
import java.time.format.DateTimeParseException
import java.time.temporal.ChronoUnit
import java.util.UUID

// ── 小工具 ──────────────────────────────────────────────────────────────
//
// 时间统一用「当天第几分钟」表达，表单里两种输入模式都换算成 startMinute /
// endMinute。这里的手写换算不依赖 DatePicker / TimePicker（项目里从没用过，
// API 不确定），保证一定能编译。

private const val MIN_UNIT = 1
private const val MAX_UNIT = 14

private val WeekdayNames = listOf("周一", "周二", "周三", "周四", "周五", "周六", "周日")

private fun weekdayName(w: Int): String = WeekdayNames.getOrElse(w - 1) { "" }

/** "HH:mm" → 当天第几分钟；格式非法返回 null */
private fun parseHHmm(text: String): Int? {
    val t = text.trim()
    val colon = t.indexOf(':')
    if (colon <= 0) return null
    val h = t.substring(0, colon).toIntOrNull() ?: return null
    val m = t.substring(colon + 1).toIntOrNull() ?: return null
    if (h !in 0..23 || m !in 0..59) return null
    return h * 60 + m
}

/** 分钟 → "HH:mm" */
private fun hhmm(minute: Int): String {
    val m = minute.coerceIn(0, 1439)
    return "%02d:%02d".format(m / 60, m % 60)
}

private fun parseIso(iso: String): LocalDate? = try {
    LocalDate.parse(iso)
} catch (e: DateTimeParseException) {
    null
}

/** 找上课时间最接近 minute 的节次（时间 → 节次时「取最近的节次」） */
private fun nearestStartUnit(minute: Int): Int {
    var best = MIN_UNIT
    var bestDiff = Int.MAX_VALUE
    for (u in MIN_UNIT..MAX_UNIT) {
        val d = kotlin.math.abs(AgendaLogic.minutesOfUnit(u).first - minute)
        if (d < bestDiff) {
            bestDiff = d
            best = u
        }
    }
    return best
}

/** 找下课时间最接近 minute 的节次 */
private fun nearestEndUnit(minute: Int): Int {
    var best = MIN_UNIT
    var bestDiff = Int.MAX_VALUE
    for (u in MIN_UNIT..MAX_UNIT) {
        val d = kotlin.math.abs(AgendaLogic.minutesOfUnit(u).second - minute)
        if (d < bestDiff) {
            bestDiff = d
            best = u
        }
    }
    return best
}

/** 把日期夹到学期范围内（学期为空时不夹，避免 coerceIn 对 LocalDate 的边界顾虑） */
private fun clampDate(d: LocalDate, first: LocalDate?, last: LocalDate?): LocalDate {
    var r = d
    if (first != null && r.isBefore(first)) r = first
    if (last != null && r.isAfter(last)) r = last
    return r
}

// ── 编辑态 ──────────────────────────────────────────────────────────────
//
// 只存表单里的「原始输入」，不预先折算成 Agenda。两套时间表示都保留：
//   · byUnit = true  → unitStart / unitEnd 是准（按节次）
//   · byUnit = false → startText / endText 是准（按时间）
// 切换模式时把当前时刻换算到另一套表示，保证不丢；保存/校验只看当前准的那套。

private data class EditDraft(
    val title: String,
    val dateText: String,
    val place: String,
    val note: String,
    val colorIndex: Int,
    val weekly: Boolean,
    val byUnit: Boolean,
    val unitStart: Int,
    val unitEnd: Int,
    val startText: String,
    val endText: String,
    /** 提醒档位（分钟，0 = 准时）。空 = 不提醒。见 [com.gzucm.pocket.core.AgendaReminder.LEADS] */
    val remind: List<Int>,
)

/**
 * 未保存草稿的寄存处（进程内，不落盘）。
 *
 * 只留最近一条：在 A 里改一半被关掉、又去开 B，A 的草稿会被顶掉。
 * 更复杂的场景（多条并存、杀进程后恢复）不值得做 —— 这个功能的全部目的
 * 就是「刚才手滑关了，别让我从头再填一遍」。
 *
 * 放内存而不是 SessionStore：日程本身可以随系统备份，但半成品草稿
 * 跟着备份走反而添乱（换设备登录后凭空冒出别人的未完成编辑）。
 */
private object AgendaDraftCache {
    private var key: String? = null
    private var draft: EditDraft? = null

    fun peek(k: String): EditDraft? = if (key == k) draft else null

    fun save(k: String, d: EditDraft) {
        key = k
        draft = d
    }

    fun clear(k: String) {
        if (key == k) {
            key = null
            draft = null
        }
    }
}

private fun buildDraft(
    initial: Agenda?,
    draftDate: LocalDate?,
    draftStartMinute: Int?,
    semester: Semester?,
): EditDraft {
    /*
     * 起止分钟的种子。
     *
     * 新建时的默认值按日历 App 的通行做法：**现在 → 下一个整点**。
     * 之前的实现是写死 08:30–09:10，用户晚上新建一条日程要先滚半天。
     */
    val seedStart: Int
    val seedEnd: Int
    if (initial != null) {
        seedStart = initial.startMinute.coerceIn(0, 1439)
        seedEnd = initial.endMinute.coerceIn(seedStart + 1, 1440)
    } else {
        // 长按空白格时用反推出来的分钟；否则用当前时刻（取整到 5 分钟）
        val rawStart = draftStartMinute ?: run {
            val now = java.time.LocalTime.now()
            ((now.hour * 60 + now.minute) / 5) * 5
        }
        val s = rawStart.coerceIn(0, 1439)
        val nextHour = ((s / 60) + 1) * 60
        seedStart = s
        // 「到下一个小时结束」；剩余不足 30 分钟就再往后一小时，免得只有一小截
        seedEnd = (if (nextHour - s >= 30) nextHour else nextHour + 60).coerceAtMost(1440)
    }

    /*
     * 编辑：当初按节次建的就回到节次模式。
     * 新建：一律先给「按时间」—— 默认值就是当下时刻，直接看得见；
     * 想按节次再点一下切换，切换时会把当前时间吸附到最近的节次。
     */
    val byUnit = if (initial != null) {
        initial.unitStart in MIN_UNIT..MAX_UNIT
    } else {
        false
    }

    val unitStart: Int
    val unitEnd: Int
    if (initial != null && initial.unitStart in MIN_UNIT..MAX_UNIT) {
        unitStart = initial.unitStart.coerceIn(MIN_UNIT, MAX_UNIT)
        unitEnd = initial.unitEnd.coerceIn(unitStart, MAX_UNIT)
    } else {
        unitStart = nearestStartUnit(seedStart)
        unitEnd = nearestEndUnit(seedEnd).coerceAtLeast(unitStart)
    }

    // 当前准的那套表示，格式化给另一套做初值
    val effStart = if (byUnit) AgendaLogic.minutesOfUnit(unitStart).first else seedStart
    val effEnd = if (byUnit) AgendaLogic.minutesOfUnit(unitEnd).second else seedEnd

    /*
     * 日期初值由调用方给（[defaultDate]），这里只兜底「今天」。
     * 今天落在寒暑假里时夹回学期内 —— 表单本身只允许学期内日期，
     * 给一个必然报错的默认值等于让用户先改日期才能保存。
     */
    val resolvedDate = initial?.let { parseIso(it.date) }
        ?: draftDate
        ?: clampDate(LocalDate.now(), semester?.let { AgendaLogic.firstDay(it) },
            semester?.let { AgendaLogic.lastDay(it) })

    return EditDraft(
        title = initial?.title ?: "",
        dateText = resolvedDate.toString(),
        place = initial?.place ?: "",
        note = initial?.note ?: "",
        colorIndex = initial?.colorIndex ?: -1,
        weekly = initial?.weekly ?: false,
        byUnit = byUnit,
        unitStart = unitStart,
        unitEnd = unitEnd,
        startText = hhmm(effStart),
        endText = hhmm(effEnd),
        /*
         * 提醒：编辑沿用原值；新建默认「准时」。
         *
         * 默认不是「不提醒」——致辉 2026-11-15 定的：日程是用户主动加的事，
         * 加的时候多半就想被提醒一次；到点响一下，比事后发现忘了强。
         * 老数据的 `remindMinutesBefore` 字段已废（v1.3.5 上线时就没有 UI），
         * 反序列化后 `remindLeads` 是空列表 → 打开编辑显示「不提醒」，不会凭空多出通知。
         */
        remind = initial?.remindLeads ?: listOf(0),
    )
}

// ── 列表 ────────────────────────────────────────────────────────────────

/**
 * 我的日程：列出所有自建日程（规则，不展开每周实例）。
 * 常驻组合树，靠 visible 控制显隐，外层不要再包 if，否则进入动画不播。
 */
@Composable
fun AgendaListSheet(
    visible: Boolean,
    semester: Semester?,
    rules: List<Agenda>,
    /**
     * 教务抓来的考试安排（只读）。
     *
     * 放在日程列表底部而不是做成独立页面：考试和日程都是「某天某段时间要去做的事」，
     * 用户找考试自然会来翻这里；再加一个顶部图标反而让本来就挤的工具栏更难认。
     */
    exams: List<ExamItem> = emptyList(),
    /**
     * 「＋ 新建日程」预填的日期。
     *
     * 由课表页按当前视图算好：周视图看别的周 → 那一周周一；日视图 → 当前那天；
     * 本周 → 今天。null 时表单自己退回「今天」，今日页等入口不需要传。
     */
    defaultDraftDate: LocalDate? = null,
    onExamClick: (ExamItem) -> Unit = {},
    /**
     * 非 null 时，这个面板的内容换成考试详情。
     *
     * 不复用独立的 AdaptiveSheet：手机端每个面板都是一个 Dialog 窗口，
     * 详情叠在列表上会出现「按返回键把整个 App 退到桌面」——Dialog 消费掉
     * BACK 之后 Activity 又收到一次。同一个面板内切内容，返回键只会关面板。
     */
    examDetail: ExamItem? = null,
    onExamDetailClose: () -> Unit = {},
    /** 考试详情顶部的重叠切换（日程 + 考试混在一起） */
    examOverlapEntries: List<AgendaOccurrence> = emptyList(),
    examCurrentKey: String = "",
    onSwitchEntry: (AgendaOccurrence) -> Unit = {},
    onAdd: (draftDate: LocalDate?, draftStartMinute: Int?) -> Unit,
    onEdit: (Agenda) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val scheme = MaterialTheme.colorScheme
    // 两趟稳定排序：先按开始分钟，再按日期 → 最终「日期优先、同日按时间」。
    // ISO 日期零填充，字典序即时间序；sortedBy 是稳定排序，不用引 Comparator SAM。
    val sorted = remember(rules) {
        rules.sortedBy { it.startMinute }.sortedBy { it.date }
    }

    // 列表自己会滚动 → 必须关掉 AdaptiveSheet 的滚动，否则平板侧栏两层
    // verticalScroll 叠一起会抛 infinite maximum height constraints。
    AdaptiveSheet(
        visible = visible,
        onDismiss = onDismiss,
        paneTitle = if (examDetail != null) "考试详情" else "我的日程",
        paneWidth = 400.dp,
        scrollable = false,
    ) {
        if (examDetail != null) {
            ExamDetailContent(
                exam = examDetail,
                onBack = onExamDetailClose,
                modifier = modifier,
                overlapEntries = examOverlapEntries,
                currentEntryKey = examCurrentKey,
                onSwitchEntry = onSwitchEntry,
            )
            return@AdaptiveSheet
        }
        LazyColumn(
            modifier = modifier
                .fillMaxWidth()
                .padding(horizontal = 18.dp),
            contentPadding = PaddingValues(top = 6.dp, bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(9.dp),
        ) {
            item(key = "agenda_header") {
                Column {
                    if (!LocalIsWidePane.current) {
                        Text(
                            "我的日程",
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Medium,
                        )
                        Spacer(Modifier.height(4.dp))
                    }
                    Text(
                        "自己加的日程，和教务课表分开存",
                        fontSize = 12.sp,
                        color = scheme.onSurfaceVariant,
                    )
                }
            }

            item(key = "agenda_add") {
                Column {
                    Spacer(Modifier.height(8.dp))
                    ActionBtn(
                        text = "＋ 新建日程",
                        primary = true,
                        enabled = semester != null,
                        modifier = Modifier.fillMaxWidth(),
                        onClick = { onAdd(defaultDraftDate, null) },
                    )
                    if (semester == null) {
                        Spacer(Modifier.height(8.dp))
                        Text(
                            "需要先登录并同步校历，才能添加日程",
                            fontSize = 11.5.sp,
                            color = scheme.error,
                        )
                    }
                }
            }

            if (sorted.isEmpty()) {
                item(key = "agenda_empty") {
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .padding(vertical = 44.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            "还没有自建日程，点上面的按钮加一条",
                            fontSize = 13.sp,
                            color = scheme.onSurfaceVariant,
                        )
                    }
                }
            } else {
                items(sorted, key = { it.id }) { agenda ->
                    AgendaRow(agenda = agenda, semester = semester, onEdit = onEdit)
                }
            }

            /*
             * 考试安排（只读）。
             *
             * 不排序处理 date 字段：教务返回的格式没在真实数据上核对过，
             * 可能是 "2026-11-14"，也可能是 "2026-11-14 14:30"。
             * 按字符串排在没有前导零时会出错，索性保持接口给的顺序（一般已按时间排）。
             */
            if (exams.isNotEmpty()) {
                item(key = "exam_header") {
                    Column {
                        Spacer(Modifier.height(20.dp))
                        Text(
                            "考试安排",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Medium,
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(
                            "教务同步，只读",
                            fontSize = 12.sp,
                            color = scheme.onSurfaceVariant,
                        )
                    }
                }
                itemsIndexed(exams, key = { i, e -> "exam|$i|${e.course}|${e.date}" }) { _, exam ->
                    ExamRow(exam = exam, onClick = { onExamClick(exam) })
                }
            } else {
                /*
                 * 一条都没有时也给个回音。
                 *
                 * 静默不显示会让人以为「这功能没做」或者「App 坏了」——
                 * 实际上多半是教务还没排考（学期前几周常见）。
                 */
                item(key = "exam_empty") {
                    Column {
                        Spacer(Modifier.height(20.dp))
                        Text(
                            "考试安排",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Medium,
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(
                            "还没有考试安排。教务排考后会自动出现在这里，不需要手动操作。",
                            fontSize = 12.sp,
                            lineHeight = 17.sp,
                            color = scheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}

/** 考试卡片。字段全是原始字符串，原样展示。 */
@Composable
private fun ExamRow(exam: ExamItem, onClick: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    val accent = MaterialTheme.colorScheme.error
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(scheme.surfaceVariant.copy(alpha = 0.5f))
            .clickable(onClickLabel = "查看考试详情 ${exam.course}", onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                exam.course.ifBlank { "未命名考试" },
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            if (exam.type.isNotBlank()) {
                Spacer(Modifier.width(8.dp))
                Text(
                    exam.type,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Medium,
                    color = accent,
                )
            }
        }
        val whenText = listOf(exam.date, prettyExamTime(exam.time))
            .filter { it.isNotBlank() }
            .joinToString("  ")
        if (whenText.isNotBlank()) {
            Spacer(Modifier.height(5.dp))
            Text(whenText, fontSize = 12.sp, color = scheme.onSurfaceVariant)
        }
        val whereText = listOf(
            exam.room,
            if (exam.seat.isNotBlank()) "座位 ${exam.seat}" else "",
        ).filter { it.isNotBlank() }.joinToString(" · ")
        if (whereText.isNotBlank()) {
            Spacer(Modifier.height(3.dp))
            Text(whereText, fontSize = 11.5.sp, color = scheme.onSurfaceVariant.copy(alpha = 0.8f))
        }
    }
}

/**
 * 教务的时间串 → `HH:mm–HH:mm`。
 *
 * 实际返回长这样：`14:30:00--17:30:00`（两个连字符、秒位全 0）。
 * 原样显示又长又难看，还把卡片撑成两行。只取时分。
 */
private fun prettyExamTime(raw: String): String {
    if (raw.isBlank()) return ""
    val segs = raw.split(Regex("-+"))
        .map { it.trim() }
        .filter { it.isNotEmpty() }
    if (segs.isEmpty()) return raw
    fun hm(s: String) = if (s.length >= 5) s.take(5) else s
    return if (segs.size >= 2) "${hm(segs[0])}–${hm(segs[1])}" else hm(segs[0])
}

@Composable
private fun AgendaRow(
    agenda: Agenda,
    semester: Semester?,
    onEdit: (Agenda) -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    val date = parseIso(agenda.date)
    val inSemester = if (semester != null && date != null) {
        AgendaLogic.inSemester(semester, date)
    } else {
        true
    }
    val dim = if (inSemester) 1f else 0.45f

    val weekPart = if (semester != null && date != null) {
        " · 第 ${semester.weekOf(date)} 周 · ${weekdayName(date.dayOfWeek.value)}"
    } else {
        ""
    }
    val dateLine = agenda.date + weekPart
    val timeLine = "${hhmm(agenda.startMinute)} — ${hhmm(agenda.endMinute)}"
    val color = if (agenda.colorIndex in CoursePalette.indices) {
        CoursePalette[agenda.colorIndex]
    } else {
        courseColor(agenda.title)
    }

    Box(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(scheme.surfaceVariant.copy(alpha = 0.5f))
            .clickable { onEdit(agenda) }
            .padding(horizontal = 14.dp, vertical = 12.dp),
    ) {
        Column {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier
                        .size(8.dp)
                        .clip(CircleShape)
                        .background(color),
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    agenda.title,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium,
                    color = scheme.onSurface.copy(alpha = dim),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                if (agenda.weekly) {
                    Spacer(Modifier.width(6.dp))
                    Tag("每周")
                }
                /*
                 * 提醒标记：只有设了提醒才显示。
                 * 用铃铛而不是「提醒」文字，是因为这一行已经有「每周 / 不在本学期」
                 * 两个文字标签，再加一个会挤掉标题；图标 13dp 只占一点点。
                 */
                if (agenda.remindLeads.isNotEmpty()) {
                    Spacer(Modifier.width(6.dp))
                    androidx.compose.material3.Icon(
                        Icons.Rounded.NotificationsActive,
                        contentDescription = "已设提醒",
                        tint = scheme.primary.copy(alpha = dim),
                        modifier = Modifier.size(13.dp),
                    )
                }
                if (!inSemester) {
                    Spacer(Modifier.width(6.dp))
                    Tag("不在本学期", error = true)
                }
            }
            Spacer(Modifier.height(5.dp))
            Text(
                dateLine,
                fontSize = 11.5.sp,
                color = scheme.onSurfaceVariant.copy(alpha = dim),
            )
            Text(
                timeLine,
                fontSize = 11.5.sp,
                color = scheme.onSurfaceVariant.copy(alpha = dim),
            )
            if (agenda.place.isNotBlank()) {
                Text(
                    "地点 · ${agenda.place}",
                    fontSize = 11.5.sp,
                    color = scheme.onSurfaceVariant.copy(alpha = dim),
                )
            }
        }
    }
}

@Composable
private fun Tag(text: String, error: Boolean = false) {
    val scheme = MaterialTheme.colorScheme
    Box(
        Modifier
            .clip(RoundedCornerShape(6.dp))
            .background(
                if (error) scheme.error.copy(alpha = 0.12f)
                else scheme.primary.copy(alpha = 0.12f)
            )
            .padding(horizontal = 6.dp, vertical = 2.dp),
    ) {
        Text(
            text,
            fontSize = 10.sp,
            color = if (error) scheme.error else scheme.primary,
        )
    }
}

// ── 编辑表单 ────────────────────────────────────────────────────────────

/**
 * 新建 / 编辑一条日程。时间支持「按节次」「按时间」两种输入，日期框在学期内。
 * 正文自带 verticalScroll → scrollable = false。
 */
@Composable
fun AgendaEditSheet(
    visible: Boolean,
    semester: Semester?,
    initial: Agenda?,
    draftDate: LocalDate?,
    draftStartMinute: Int?,
    onSave: (Agenda) -> Unit,
    onDelete: (String) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    /** 同一时段压着的全部条目（含自己）。两条以上时顶部出现切换胶囊 */
    overlapEntries: List<AgendaOccurrence> = emptyList(),
    /** 当前在看的这条的 key，用来高亮 */
    currentEntryKey: String = "",
    onSwitchEntry: (AgendaOccurrence) -> Unit = {},
) {
    val scheme = MaterialTheme.colorScheme
    val hasSemester = semester != null
    val firstDay = semester?.let { AgendaLogic.firstDay(it) }
    val lastDay = semester?.let { AgendaLogic.lastDay(it) }
    val rangeText = if (firstDay != null && lastDay != null) "$firstDay ~ $lastDay" else "—"

    /*
     * 通知权限：自己查、自己要，不依赖上层传参。
     *
     * 面板是常驻组合树（visible 控制显隐），每次打开都要重新查一遍 ——
     * 用户可能刚在系统设置里点过允许。Activity 启动时已经要过一次，
     * 这里只在「被拒绝过」的情况下作为补救入口。
     */
    val ctx = androidx.compose.ui.platform.LocalContext.current
    var notifyGranted by remember { mutableStateOf(hasNotifyPermission(ctx)) }
    val requestNotify = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.RequestPermission(),
    ) { notifyGranted = it }
    LaunchedEffect(visible) {
        if (visible) notifyGranted = hasNotifyPermission(ctx)
    }

    /*
     * 默认日期在这一层就算定，[buildDraft] 和草稿 key 共用同一个值。
     *
     * 来源优先级：编辑已有 → 入参预填（长按格子 / 日视图那天 / 周视图那一周）
     * → 今天（夹回学期内）。
     */
    val defaultDate = initial?.let { parseIso(it.date) }
        ?: clampDate(draftDate ?: LocalDate.now(), firstDay, lastDay)

    /*
     * 草稿缓存：误关面板时不丢已填内容。
     *
     * 用户反馈「下拉把面板关了，刚编辑的全没了」。第一版想用
     * `confirmValueChange` 拦掉关闭手势，结果 M3 的 hide() 被永久拒绝、
     * Dialog 关不掉、整屏点不动 —— 比误关严重得多，当天撤掉。
     *
     * 现在改成：关闭手势不拦（下拉、遮罩、返回都能正常关），但**内容留在这里**，
     * 下次打开同一条自动恢复。新建的 key 里带上默认日期 ——
     * 不加的话，「第 5 周新建」填一半关掉、翻到第 12 周再新建，
     * 恢复出来的是第 5 周那份，刚改好的默认日期又被顶回去。
     *
     * 不弹「已恢复」提示了（致辉 2026-11-14）：内容对得上就够，
     * 多一条横幅反而是干扰。
     */
    val draftKey = initial?.id ?: "new|$defaultDate"
    /*
     * 不能用 remember(draftKey) 缓存这个值。
     *
     * 第一次进组合时缓存里还是 null，remember 会把 null 记一辈子 ——
     * 面板关掉再打开时 key 没变，读到的仍是那个 null，草稿永远恢复不了。
     * 直接读（两次字段比较，无开销）。
     */
    val cachedDraft = AgendaDraftCache.peek(draftKey)

    // visible 也作为 key：每次重新打开都从入参重建草稿，不残留上次没保存的编辑。
    var draft by remember(initial, defaultDate, draftStartMinute, semester, visible) {
        mutableStateOf(cachedDraft ?: buildDraft(initial, defaultDate, draftStartMinute, semester))
    }
    // 每次改动落进缓存；面板不可见时不写，否则关闭那一刻会用默认值盖掉草稿
    LaunchedEffect(draft, draftKey, visible) {
        if (visible) AgendaDraftCache.save(draftKey, draft)
    }
    // 删除用二次点击确认（项目里没有用过带 confirmButton 的 AlertDialog，避免不确定 API）
    var confirmDelete by remember(initial, visible) { mutableStateOf(false) }
    /*
     * 同时只允许展开一个滚轮（"date" / "start" / "end" / null）。
     *
     * 一开始三个滚轮各自独立展开，实测能把表单拉长一倍多，
     * 展开日期后「地点」直接被挤出屏幕 —— 互斥清爽得多。
     */
    var openWheel by remember(visible) { mutableStateOf<String?>(null) }

    // 模式切换：把「当前时刻」在两套表示之间换算，保证不丢
    val switchToUnit: () -> Unit = {
        val s = parseHHmm(draft.startText) ?: (8 * 60 + 30)
        val e = parseHHmm(draft.endText) ?: (s + 40)
        val us = nearestStartUnit(s)
        val ue = nearestEndUnit(e).coerceAtLeast(us)
        draft = draft.copy(
            byUnit = true,
            unitStart = us,
            unitEnd = ue,
            startText = hhmm(AgendaLogic.minutesOfUnit(us).first),
            endText = hhmm(AgendaLogic.minutesOfUnit(ue).second),
        )
    }
    val switchToTime: () -> Unit = {
        val s = AgendaLogic.minutesOfUnit(draft.unitStart).first
        val e = AgendaLogic.minutesOfUnit(draft.unitEnd).second
        draft = draft.copy(
            byUnit = false,
            startText = hhmm(s),
            endText = hhmm(e),
        )
    }
    val setUnits: (Int, Int) -> Unit = { us, ue ->
        val a = us.coerceIn(MIN_UNIT, MAX_UNIT)
        val b = ue.coerceIn(a, MAX_UNIT)
        draft = draft.copy(
            unitStart = a,
            unitEnd = b,
            startText = hhmm(AgendaLogic.minutesOfUnit(a).first),
            endText = hhmm(AgendaLogic.minutesOfUnit(b).second),
        )
    }

    // ── 校验 ──────────────────────────────────────────────────────────
    val parsedDate = parseIso(draft.dateText.trim())
    val dateInRange = parsedDate != null && semester != null &&
        AgendaLogic.inSemester(semester, parsedDate)

    // 当前准的那套表示换算成分钟
    val effStart: Int? = if (draft.byUnit) {
        AgendaLogic.minutesOfUnit(draft.unitStart).first
    } else {
        parseHHmm(draft.startText)
    }
    val effEnd: Int? = if (draft.byUnit) {
        AgendaLogic.minutesOfUnit(draft.unitEnd).second
    } else {
        parseHHmm(draft.endText)
    }

    val titleOk = draft.title.isNotBlank()
    val timeError: String? = if (effStart == null || effEnd == null) {
        "时间请按 HH:mm 填写，例如 08:30"
    } else if (effEnd <= effStart) {
        "结束时间要晚于开始时间"
    } else {
        null
    }

    // 红字提示按优先级给一条；有提示就禁用保存
    val errorText: String? = when {
        !hasSemester -> "需要先登录并同步校历，才能添加日程"
        !titleOk -> "标题不能为空"
        timeError != null -> timeError
        parsedDate == null -> "日期请按 yyyy-MM-dd 填写，例如 2026-11-14"
        !dateInRange -> "日期要落在本学期内（$rangeText）"
        else -> null
    }
    val canSave = errorText == null

    AdaptiveSheet(
        visible = visible,
        onDismiss = onDismiss,
        paneTitle = if (initial == null) "新建日程" else "编辑日程",
        paneWidth = 400.dp,
        scrollable = false,
        /*
         * 保留半屏态（三档：半屏 → 全屏 → 关闭）。
         *
         * 上面一直传 true，等于只有「全屏 / 关闭」两态，手指一拖就直接关掉，
         * 填一半的内容全没了。开启半屏后：全屏往下拖只会回到半屏，
         * 要关得从半屏再拖一次 —— 误触成本从「一次手滑」变成「连续两次」，
         * 而且中间那一步是可逆的。
         *
         * 但**打开时要满屏**（致辉 2026-11-14 定）：表单字段多，
         * 停在半屏得先往上拖一次才看得全。半屏留着但只在用户自己往下拖时出现。
         */
        skipPartiallyExpanded = false,
        expandOnOpen = true,
    ) {
        Column(
            modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
                .padding(bottom = 28.dp),
        ) {
            if (!LocalIsWidePane.current) {
                Text(
                    if (initial == null) "新建日程" else "编辑日程",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Medium,
                )
                Spacer(Modifier.height(6.dp))
            }

            OverlapSwitcher(
                entries = overlapEntries,
                currentKey = currentEntryKey,
                onSwitch = onSwitchEntry,
            )

            if (!hasSemester) {
                Box(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(scheme.error.copy(alpha = 0.10f))
                        .padding(horizontal = 14.dp, vertical = 11.dp),
                ) {
                    Text(
                        "需要先登录并同步校历，才能添加日程",
                        fontSize = 12.5.sp,
                        color = scheme.error,
                    )
                }
                Spacer(Modifier.height(16.dp))
            }

            // 标题
            LabeledInput(
                label = "标题",
                value = draft.title,
                onValueChange = { draft = draft.copy(title = it) },
                hint = "给这条日程起个名字",
            )
            Spacer(Modifier.height(14.dp))

            /*
             * 日期 / 时间：折叠行 + 展开式滚轮。
             *
             * 一开始做成常驻滚轮，实测两个问题：
             *   ① 日期 132dp + 时间两行 264dp，半屏被占满，下面的字段要滚很久
             *   ② 手指落在滚轮上想滚表单，结果把日期从 11/14 滑到了 10/30
             * 改成「点一行才展开」，平时只占一行，误触面积也小。
             */
            if (firstDay != null && lastDay != null) {
                val totalDays = ChronoUnit.DAYS.between(firstDay, lastDay).toInt() + 1
                val dateIdx = parsedDate
                    ?.let { ChronoUnit.DAYS.between(firstDay, it).toInt() }
                    ?.coerceIn(0, totalDays - 1) ?: 0
                FieldRow(
                    label = "日期",
                    value = parsedDate?.let {
                        "${it.monthValue}月${it.dayOfMonth}日 ${weekdayName(it.dayOfWeek.value)}"
                    } ?: "选择日期",
                    expanded = openWheel == "date",
                    onClick = { openWheel = if (openWheel == "date") null else "date" },
                )
                if (openWheel == "date") {
                    WheelColumn(
                        count = totalDays,
                        initialIndex = dateIdx,
                        label = { i ->
                            val d = firstDay.plusDays(i.toLong())
                            "${d.monthValue}月${d.dayOfMonth}日  ${weekdayName(d.dayOfWeek.value)}"
                        },
                        onSelect = { i ->
                            val iso = firstDay.plusDays(i.toLong()).toString()
                            if (draft.dateText != iso) draft = draft.copy(dateText = iso)
                        },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                Spacer(Modifier.height(4.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        if (parsedDate == null) {
                            "日期格式不对"
                        } else {
                            val wk = semester?.weekOf(parsedDate) ?: 0
                            "第 $wk 周 · ${weekdayName(parsedDate.dayOfWeek.value)}"
                        },
                        fontSize = 11.sp,
                        color = scheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f),
                    )
                    Text(
                        "范围 $rangeText",
                        fontSize = 10.sp,
                        color = scheme.onSurfaceVariant.copy(alpha = 0.6f),
                    )
                }
                Spacer(Modifier.height(12.dp))
            }

            // 时间：胶囊二选一
            Text("时间", fontSize = 12.sp, color = scheme.onSurfaceVariant)
            Spacer(Modifier.height(6.dp))
            Row(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(10.dp))
                    .background(scheme.onSurfaceVariant.copy(alpha = 0.09f))
                    .padding(3.dp),
                horizontalArrangement = Arrangement.spacedBy(3.dp),
            ) {
                listOf(true to "按节次", false to "按时间").forEach { (mode, label) ->
                    val on = mode == draft.byUnit
                    Box(
                        Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(8.dp))
                            .background(
                                if (on) scheme.primary.copy(alpha = 0.16f)
                                else Color.Transparent
                            )
                            .clickable {
                                if (mode != draft.byUnit) {
                                    if (mode) switchToUnit() else switchToTime()
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

            Spacer(Modifier.height(8.dp))
            if (draft.byUnit) {
                UnitStepper("起始节", draft.unitStart) { setUnits(it, draft.unitEnd) }
                Spacer(Modifier.height(4.dp))
                UnitStepper("结束节", draft.unitEnd) { setUnits(draft.unitStart, it) }
                Spacer(Modifier.height(6.dp))
                Text(
                    "第 ${draft.unitStart}-${draft.unitEnd} 节 · " +
                        "${hhmm(AgendaLogic.minutesOfUnit(draft.unitStart).first)} — " +
                        hhmm(AgendaLogic.minutesOfUnit(draft.unitEnd).second),
                    fontSize = 11.sp,
                    color = scheme.onSurfaceVariant,
                )
            } else {
                val sMin = parseHHmm(draft.startText) ?: (8 * 60 + 30)
                val eMin = parseHHmm(draft.endText) ?: (sMin + 60)
                FieldRow(
                    label = "开始",
                    value = hhmm(sMin),
                    expanded = openWheel == "start",
                    onClick = { openWheel = if (openWheel == "start") null else "start" },
                )
                if (openWheel == "start") {
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        WheelColumn(
                            count = 24,
                            initialIndex = sMin / 60,
                            label = { "%02d".format(it) },
                            onSelect = { h ->
                                val text = "%02d:%02d".format(h, sMin % 60)
                                if (draft.startText != text) draft = draft.copy(startText = text)
                            },
                            modifier = Modifier.weight(1f),
                        )
                        Text(":", fontSize = 16.sp, color = scheme.onSurfaceVariant,
                            modifier = Modifier.align(Alignment.CenterVertically))
                        WheelColumn(
                            count = 60,
                            initialIndex = sMin % 60,
                            label = { "%02d".format(it) },
                            onSelect = { m ->
                                val text = "%02d:%02d".format(sMin / 60, m)
                                if (draft.startText != text) draft = draft.copy(startText = text)
                            },
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
                Spacer(Modifier.height(6.dp))
                FieldRow(
                    label = "结束",
                    value = hhmm(eMin),
                    expanded = openWheel == "end",
                    onClick = { openWheel = if (openWheel == "end") null else "end" },
                )
                if (openWheel == "end") {
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        WheelColumn(
                            count = 24,
                            initialIndex = eMin / 60,
                            label = { "%02d".format(it) },
                            onSelect = { h ->
                                val text = "%02d:%02d".format(h, eMin % 60)
                                if (draft.endText != text) draft = draft.copy(endText = text)
                            },
                            modifier = Modifier.weight(1f),
                        )
                        Text(":", fontSize = 16.sp, color = scheme.onSurfaceVariant,
                            modifier = Modifier.align(Alignment.CenterVertically))
                        WheelColumn(
                            count = 60,
                            initialIndex = eMin % 60,
                            label = { "%02d".format(it) },
                            onSelect = { m ->
                                val text = "%02d:%02d".format(eMin / 60, m)
                                if (draft.endText != text) draft = draft.copy(endText = text)
                            },
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
                Spacer(Modifier.height(4.dp))
                Text(
                    "${hhmm(sMin)} — ${hhmm(eMin)}",
                    fontSize = 11.sp,
                    color = scheme.onSurfaceVariant,
                )
            }

            // 提醒
            Spacer(Modifier.height(16.dp))
            FieldRow(
                label = "提醒",
                value = AgendaReminder.summary(draft.remind),
                expanded = openWheel == "remind",
                onClick = { openWheel = if (openWheel == "remind") null else "remind" },
            )
            if (openWheel == "remind") {
                Spacer(Modifier.height(8.dp))
                RemindChips(
                    selected = draft.remind,
                    onSelect = { draft = draft.copy(remind = it) },
                )
            }
            /*
             * 设了提醒但通知权限没开 = 白设。和设置页「上课提醒」那里同一句文案，
             * 直接给一个可点的入口，不用用户自己去系统设置里翻。
             */
            if (draft.remind.isNotEmpty() && !notifyGranted) {
                Spacer(Modifier.height(6.dp))
                Text(
                    "通知权限未开启，提醒不会弹出 · 点这里开启",
                    fontSize = 10.5.sp,
                    lineHeight = 15.sp,
                    color = scheme.error,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable {
                            runCatching {
                                requestNotify.launch(android.Manifest.permission.POST_NOTIFICATIONS)
                            }
                        }
                        .padding(vertical = 4.dp),
                )
            }

            // 地点 / 备注
            Spacer(Modifier.height(16.dp))
            LabeledInput(
                label = "地点",
                value = draft.place,
                onValueChange = { draft = draft.copy(place = it) },
                hint = "可选，例如 图书馆",
            )
            Spacer(Modifier.height(14.dp))
            LabeledInput(
                label = "备注",
                value = draft.note,
                onValueChange = { draft = draft.copy(note = it) },
                hint = "可选",
                singleLine = false,
            )

            // 每周重复
            Spacer(Modifier.height(18.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("每周重复", fontSize = 14.sp, fontWeight = FontWeight.Medium)
                    Spacer(Modifier.height(3.dp))
                    Text(
                        "从所选日期起，每周同一天都出现，直到学期结束",
                        fontSize = 10.5.sp,
                        lineHeight = 14.sp,
                        color = scheme.onSurfaceVariant.copy(alpha = 0.7f),
                    )
                }
                Spacer(Modifier.width(12.dp))
                Switch(
                    checked = draft.weekly,
                    onCheckedChange = { draft = draft.copy(weekly = it) },
                )
            }

            // 颜色：-1 自动，其余对应 CoursePalette 下标
            Spacer(Modifier.height(18.dp))
            Text("颜色", fontSize = 12.sp, color = scheme.onSurfaceVariant)
            Spacer(Modifier.height(8.dp))
            Row(
                Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                val auto = draft.colorIndex < 0
                Box(
                    Modifier
                        .clip(RoundedCornerShape(10.dp))
                        .background(
                            if (auto) scheme.primary.copy(alpha = 0.16f)
                            else scheme.surfaceVariant.copy(alpha = 0.6f)
                        )
                        .clickable { draft = draft.copy(colorIndex = -1) }
                        .padding(horizontal = 12.dp, vertical = 9.dp),
                ) {
                    Text(
                        "自动",
                        fontSize = 12.sp,
                        color = if (auto) scheme.primary else scheme.onSurfaceVariant,
                    )
                }
                CoursePalette.forEachIndexed { i, c ->
                    ColorDot(
                        selected = draft.colorIndex == i,
                        color = c,
                        label = "颜色 ${i + 1}",
                        onClick = { draft = draft.copy(colorIndex = i) },
                    )
                }
            }

            // 校验提示 + 保存 / 删除
            Spacer(Modifier.height(20.dp))
            if (errorText != null) {
                Text(
                    errorText,
                    fontSize = 12.sp,
                    color = scheme.error,
                )
                Spacer(Modifier.height(10.dp))
            }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                if (initial != null) {
                    ActionBtn(
                        text = if (confirmDelete) "再点一次确认删除" else "删除",
                        primary = false,
                        danger = true,
                        modifier = Modifier.weight(1f),
                        onClick = {
                            if (confirmDelete) {
                                confirmDelete = false
                                // 真要删了，草稿没有留着的意义
                                AgendaDraftCache.clear(draftKey)
                                initial?.let { onDelete(it.id) }
                            } else {
                                confirmDelete = true
                            }
                        },
                    )
                }
                ActionBtn(
                    text = "保存",
                    primary = true,
                    enabled = canSave,
                    modifier = Modifier.weight(1f),
                    onClick = {
                        val s = effStart
                        val e = effEnd
                        val d = parsedDate
                        if (canSave && s != null && e != null && d != null) {
                            // 存下来了，草稿使命结束
                            AgendaDraftCache.clear(draftKey)
                            onSave(
                                Agenda(
                                    id = initial?.id ?: UUID.randomUUID().toString(),
                                    title = draft.title.trim(),
                                    date = d.toString(),
                                    startMinute = s,
                                    endMinute = e,
                                    place = draft.place.trim(),
                                    note = draft.note.trim(),
                                    colorIndex = draft.colorIndex,
                                    weekly = draft.weekly,
                                    unitStart = if (draft.byUnit) draft.unitStart else 0,
                                    unitEnd = if (draft.byUnit) draft.unitEnd else 0,
                                    /*
                                     * 提醒：表单里选了什么就存什么（-1 = 不提醒）。
                                     * 排程由 ViewModel 在保存后统一重排，这里只管落库。
                                     */
                                    remindLeads = draft.remind,
                                )
                            )
                        }
                    },
                )
            }
        }
    }
}

// ── 通用小控件 ──────────────────────────────────────────────────────────

/**
 * 标签 + 可编辑输入框。用 foundation 的 BasicTextField（value: String 重载，
 * 已确认 1.7.5 存在且稳定）自己套圆角底色，不碰 Material3 TextField 的
 * Experimental / colors API，保证编译通过；圆角 12dp、字号跟随主题。
 */
@Composable
private fun LabeledInput(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    hint: String,
    modifier: Modifier = Modifier,
    singleLine: Boolean = true,
) {
    val scheme = MaterialTheme.colorScheme
    Column(modifier) {
        if (label.isNotEmpty()) {
            Text(label, fontSize = 12.sp, color = scheme.onSurfaceVariant)
            Spacer(Modifier.height(6.dp))
        }
        Box(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .background(scheme.surfaceVariant.copy(alpha = 0.6f))
                .padding(horizontal = 14.dp, vertical = 12.dp),
        ) {
            BasicTextField(
                value = value,
                onValueChange = onValueChange,
                modifier = Modifier.fillMaxWidth(),
                textStyle = TextStyle(
                    fontSize = 14.sp,
                    color = scheme.onSurface,
                ),
                singleLine = singleLine,
                maxLines = if (singleLine) 1 else 4,
                minLines = 1,
                cursorBrush = SolidColor(scheme.primary),
                decorationBox = { inner ->
                    Box(contentAlignment = Alignment.CenterStart) {
                        if (value.isEmpty()) {
                            Text(
                                hint,
                                fontSize = 14.sp,
                                color = scheme.onSurfaceVariant.copy(alpha = 0.6f),
                            )
                        }
                        inner()
                    }
                },
            )
        }
    }
}

/** 节次步进：标题 + − 值 +，参照 SettingsScreen 的 LeadRow 写法 */
@Composable
private fun UnitStepper(title: String, value: Int, onChange: (Int) -> Unit) {
    val scheme = MaterialTheme.colorScheme
    Row(
        Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            title,
            fontSize = 12.5.sp,
            color = scheme.onSurface,
            modifier = Modifier.weight(1f),
        )
        IconButton(
            onClick = { onChange(value - 1) },
            enabled = value > MIN_UNIT,
        ) {
            Text(
                "−",
                fontSize = 16.sp,
                color = if (value > MIN_UNIT) scheme.primary
                else scheme.onSurfaceVariant.copy(alpha = 0.3f),
            )
        }
        Text(
            "第 $value 节",
            fontSize = 12.sp,
            fontWeight = FontWeight.Medium,
            textAlign = TextAlign.Center,
            modifier = Modifier.width(64.dp),
        )
        IconButton(
            onClick = { onChange(value + 1) },
            enabled = value < MAX_UNIT,
        ) {
            Text(
                "+",
                fontSize = 16.sp,
                color = if (value < MAX_UNIT) scheme.primary
                else scheme.onSurfaceVariant.copy(alpha = 0.3f),
            )
        }
    }
}

/** 一个色点，选中态用外圈描边 */
@Composable
private fun ColorDot(selected: Boolean, color: Color, label: String, onClick: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    Box(
        Modifier
            .size(30.dp)
            .clip(CircleShape)
            .background(color)
            .then(
                if (selected) Modifier.border(2.dp, scheme.onSurface, CircleShape)
                else Modifier.border(1.dp, scheme.outline.copy(alpha = 0.35f), CircleShape)
            )
            .clickable(onClickLabel = label, onClick = onClick),
    )
}

/** 圆角按钮，参照 BackgroundSheet 的 Btn 写法，增加 enabled / danger */
@Composable
private fun ActionBtn(
    text: String,
    primary: Boolean,
    enabled: Boolean = true,
    danger: Boolean = false,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    val tint = when {
        danger -> scheme.error
        primary -> scheme.primary
        else -> scheme.onSurfaceVariant
    }
    Box(
        modifier
            .clip(RoundedCornerShape(12.dp))
            .background(
                if (primary && enabled && !danger) scheme.primary.copy(alpha = 0.15f)
                else scheme.surfaceVariant.copy(alpha = 0.6f)
            )
            .clickable(enabled = enabled, onClick = onClick)
            .padding(vertical = 12.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text,
            fontSize = 13.sp,
            fontWeight = FontWeight.Medium,
            color = tint.copy(alpha = if (enabled) 1f else 0.4f),
        )
    }
}

// ── 滚轮选择器 ────────────────────────────────────────────────────────────
//
// 之前日期和时间都靠手输（HH:mm / yyyy-MM-dd），手机上要切键盘、还容易输错。
// 改成滚轮：滚动时吸附到整项，中间一行高亮，选中即生效。
//
// 两个硬约束：
//   ① 滚轮必须给**固定高度**。外层表单是 verticalScroll，LazyColumn 在无限高度
//      约束下会直接抛「infinity maximum height constraints」。
//   ② 用 contentPadding 上下各留半屏，第一项才会落在中间。

private val WheelItemHeight = 36.dp
private val WheelHeight = 132.dp

@Composable
private fun WheelColumn(
    count: Int,
    initialIndex: Int,
    label: (Int) -> String,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (count <= 0) return
    val scheme = MaterialTheme.colorScheme
    val state = rememberLazyListState(
        initialFirstVisibleItemIndex = initialIndex.coerceIn(0, count - 1),
    )
    val fling = rememberSnapFlingBehavior(state)
    val sidePad = (WheelHeight - WheelItemHeight) / 2

    // 停稳后回调。滚动过程中 firstVisibleItemIndex 会连续变，去重后只报真正换项。
    LaunchedEffect(state, count) {
        snapshotFlow { state.firstVisibleItemIndex }
            .collect { i -> if (i in 0 until count) onSelect(i) }
    }

    Box(modifier.height(WheelHeight)) {
        // 高亮条画在列表**下面**：画在上面会给文字蒙一层色
        Box(
            Modifier
                .align(Alignment.Center)
                .fillMaxWidth()
                .height(WheelItemHeight)
                .clip(RoundedCornerShape(8.dp))
                .background(scheme.primary.copy(alpha = 0.07f)),
        )
        LazyColumn(
            state = state,
            flingBehavior = fling,
            contentPadding = PaddingValues(vertical = sidePad),
            modifier = Modifier.fillMaxWidth(),
        ) {
            items(count) { i ->
                val selected = i == state.firstVisibleItemIndex
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(WheelItemHeight),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        label(i),
                        fontSize = if (selected) 17.sp else 13.5.sp,
                        fontWeight = if (selected) FontWeight.Medium else FontWeight.Normal,
                        color = if (selected) scheme.primary
                        else scheme.onSurfaceVariant.copy(alpha = 0.55f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

/** 一天里第几分钟 → 滚轮可读的 HH:mm */
private fun minuteLabel(m: (Int) -> Int): (Int) -> String = { i -> "%02d".format(m(i)) }

/**
 * 提醒档位：一排胶囊，放不下自动换行。**多选**。
 *
 * 可以同时选「提前 1 天 + 提前 30 分钟 + 准时」，每个档位各响一次
 * （致辉 2026-11-15 提的：考试这类事前一天提醒一次、当天再提醒一次是真实需求）。
 *
 * 「不提醒」是排他项：点它清空全部；一个都没选时它显示为选中。
 * 样式抄「重叠安排」那条（选中 = 主色淡底 + 细边框），不另造一套视觉。
 * 用胶囊不用滚轮：8 个离散值，滚轮要一格格翻，反而慢。
 */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun RemindChips(selected: List<Int>, onSelect: (List<Int>) -> Unit) {
    val scheme = MaterialTheme.colorScheme
    androidx.compose.foundation.layout.FlowRow(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(7.dp),
        verticalArrangement = Arrangement.spacedBy(7.dp),
    ) {
        AgendaReminder.LEADS.forEach { lead ->
            val sel = if (lead < 0) selected.isEmpty() else lead in selected
            Box(
                Modifier
                    .clip(RoundedCornerShape(9.dp))
                    .background(
                        if (sel) scheme.primary.copy(alpha = 0.18f)
                        else scheme.surfaceVariant.copy(alpha = 0.40f),
                    )
                    .border(
                        if (sel) 1.dp else 0.dp,
                        if (sel) scheme.primary.copy(alpha = 0.75f) else Color.Transparent,
                        RoundedCornerShape(9.dp),
                    )
                    .clickable {
                        onSelect(
                            when {
                                lead < 0 -> emptyList()
                                lead in selected -> selected - lead
                                else -> (selected + lead).sorted()
                            },
                        )
                    }
                    .padding(horizontal = 11.dp, vertical = 7.dp),
            ) {
                Text(
                    AgendaReminder.label(lead),
                    fontSize = 11.5.sp,
                    fontWeight = if (sel) FontWeight.Medium else FontWeight.Normal,
                    color = if (sel) scheme.primary else scheme.onSurfaceVariant,
                    maxLines = 1,
                )
            }
        }
    }
}

/** Android 13+ 才需要通知权限；低版本一律视为已授权 */
private fun hasNotifyPermission(ctx: android.content.Context): Boolean =
    android.os.Build.VERSION.SDK_INT < 33 ||
        androidx.core.content.ContextCompat.checkSelfPermission(
            ctx, android.Manifest.permission.POST_NOTIFICATIONS,
        ) == android.content.pm.PackageManager.PERMISSION_GRANTED

/**
 * 一行「标签 + 当前值 + 展开箭头」，点一下展开/收起下面的滚轮。
 *
 * 用文字箭头而不是图标：项目里 `Icons.Rounded.KeyboardArrowDown` 之类
 * 要额外引依赖，一个 ▾ 足够表达，也不会在字体缩放时错位。
 */
@Composable
private fun FieldRow(
    label: String,
    value: String,
    expanded: Boolean,
    onClick: () -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(scheme.surfaceVariant.copy(alpha = 0.45f))
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, fontSize = 13.sp, color = scheme.onSurfaceVariant)
        Spacer(Modifier.weight(1f))
        Text(
            value,
            fontSize = 14.sp,
            fontWeight = FontWeight.Medium,
            color = scheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Spacer(Modifier.width(6.dp))
        /*
         * 用 ▼ + 180° 旋转，不用 ▾/▴。
         * 实测某些系统字体没有 U+25BE / U+25B4，会渲染成一个点。
         */
        Text(
            "▼",
            fontSize = 8.sp,
            color = scheme.onSurfaceVariant,
            modifier = Modifier.graphicsLayer {
                rotationZ = if (expanded) 180f else 0f
            },
        )
    }
}

// ── 考试详情（只读）─────────────────────────────────────────────────────
//
// 课表里的考试块和日程列表面板都能点开这里。字段来自教务的考试任务接口，
// 主字段（时间/地点/座位）已经摆在外面了，这里补齐监考、校区、试卷号等。

@Composable
private fun ExamDetailContent(
    exam: ExamItem,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    overlapEntries: List<AgendaOccurrence> = emptyList(),
    currentEntryKey: String = "",
    onSwitchEntry: (AgendaOccurrence) -> Unit = {},
) {
    val scheme = MaterialTheme.colorScheme
    Column(
        modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp)
            .padding(bottom = 28.dp),
    ) {
            if (!LocalIsWidePane.current) {
                Text(
                    "考试详情",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Medium,
                )
                Spacer(Modifier.height(10.dp))
            }
            // 平板走侧栏，标题在 paneTitle 里已经有了；给一个返回入口
            Text(
                "← 返回日程列表",
                fontSize = 12.5.sp,
                color = scheme.primary,
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .clickable(onClick = onBack)
                    .padding(vertical = 6.dp),
            )
            Spacer(Modifier.height(4.dp))

            OverlapSwitcher(
                entries = overlapEntries,
                currentKey = currentEntryKey,
                onSwitch = onSwitchEntry,
            )

            Text(
                exam.course.ifBlank { "未命名考试" },
                fontSize = 17.sp,
                fontWeight = FontWeight.Medium,
                lineHeight = 24.sp,
            )
            if (exam.type.isNotBlank()) {
                Spacer(Modifier.height(6.dp))
                Box(
                    Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .background(scheme.error.copy(alpha = 0.12f))
                        .padding(horizontal = 8.dp, vertical = 3.dp),
                ) {
                    Text(exam.type, fontSize = 11.5.sp, color = scheme.error)
                }
            }

            Spacer(Modifier.height(16.dp))
            ExamInfoRow("日期", exam.date)
            ExamInfoRow("时间", prettyExamTime(exam.time))
            ExamInfoRow("考场", exam.room)
            ExamInfoRow("座位", exam.seat)

            if (exam.details.isNotEmpty()) {
                Spacer(Modifier.height(10.dp))
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(1.dp)
                        .background(scheme.onSurfaceVariant.copy(alpha = 0.12f)),
                )
                Spacer(Modifier.height(10.dp))
                exam.details.forEach { d ->
                    ExamInfoRow(d.label, d.value)
                }
            }
    }
}

@Composable
private fun ExamInfoRow(label: String, value: String) {
    if (value.isBlank()) return
    val scheme = MaterialTheme.colorScheme
    Row(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 7.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Text(
            label,
            fontSize = 12.5.sp,
            color = scheme.onSurfaceVariant,
            modifier = Modifier.width(78.dp),
        )
        Text(
            value,
            fontSize = 13.sp,
            lineHeight = 19.sp,
            color = scheme.onSurface,
            modifier = Modifier.weight(1f),
        )
    }
}

// ── 重叠安排切换条 ──────────────────────────────────────────────────────
//
// 和 SessionSheet 里「重叠课程」那一排胶囊一个样子：只在同一时段压了
// 两条以上（日程 / 考试）时出现，点了直接换看另一条。
// 不加这个的话，层叠的卡片下面那条只能靠 ⇄ 轮换才看得到，点开详情就断了。

@Composable
private fun OverlapSwitcher(
    entries: List<AgendaOccurrence>,
    currentKey: String,
    onSwitch: (AgendaOccurrence) -> Unit,
) {
    if (entries.size < 2) return
    val scheme = MaterialTheme.colorScheme
    Spacer(Modifier.height(12.dp))
    Text("重叠安排", fontSize = 10.5.sp, color = scheme.onSurfaceVariant)
    Spacer(Modifier.height(7.dp))
    Row(
        Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(7.dp),
    ) {
        entries.forEach { o ->
            val sel = o.key == currentKey
            val cc = if (o.colorIndex == EXAM_COLOR_INDEX) scheme.error else agendaColor(o)
            Box(
                Modifier
                    .clip(RoundedCornerShape(9.dp))
                    .background(
                        if (sel) cc.copy(alpha = 0.20f)
                        else scheme.surfaceVariant.copy(alpha = 0.40f),
                    )
                    .border(
                        if (sel) 1.dp else 0.dp,
                        if (sel) cc.copy(alpha = 0.75f) else Color.Transparent,
                        RoundedCornerShape(9.dp),
                    )
                    .clickable { onSwitch(o) }
                    .padding(horizontal = 11.dp, vertical = 7.dp),
            ) {
                Text(
                    o.title,
                    fontSize = 11.5.sp,
                    fontWeight = if (sel) FontWeight.Medium else FontWeight.Normal,
                    color = if (sel) cc else scheme.onSurfaceVariant,
                    maxLines = 1,
                )
            }
        }
    }
    Spacer(Modifier.height(4.dp))
}
