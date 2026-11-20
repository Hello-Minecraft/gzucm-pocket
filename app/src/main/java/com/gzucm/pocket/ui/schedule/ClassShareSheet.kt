package com.gzucm.pocket.ui.schedule

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
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
import com.gzucm.pocket.core.buildClassDoc
import com.gzucm.pocket.core.decodeClassToken
import com.gzucm.pocket.core.encodeClassToken
import com.gzucm.pocket.core.mergeClassTasks
import com.gzucm.pocket.ui.adaptive.LocalIsWidePane
import com.gzucm.pocket.ui.components.G3CornerShape
import com.gzucm.pocket.ui.icons.AppIcons

/**
 * 「班级作业」的内容区。
 *
 * **不是独立面板**：由 [AgendaListSheet] 在同一个 Dialog 里切换内容显示。
 * 手机端每个 `AdaptiveSheet` 都是一个 Dialog 窗口，面板叠面板会出现
 * 「按返回键直接退回桌面」（Dialog 吃掉 BACK 后 Activity 又收到一次，
 * 项目里踩过）。所以和「考试详情」用同一套做法：同一个面板，换内容。
 */
@Composable
internal fun ClassShareContent(
    agendas: List<Agenda>,
    onAgendasChange: (List<Agenda>) -> Unit,
    onClose: () -> Unit,
) {
    val ctx = LocalContext.current
    val scheme = MaterialTheme.colorScheme
    /** 班级名 = 学委名字。分享和导入用的是同一个值，用户填一次就够 */
    var className by rememberSaveable { mutableStateOf("") }
    var hint by remember { mutableStateOf<Pair<String, Boolean>?>(null) }
    var busy by remember { mutableStateOf(false) }

    val clipboard = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    fun readClip(): String =
        clipboard.primaryClip?.takeIf { it.itemCount > 0 }
            ?.getItemAt(0)?.coerceToText(ctx)?.toString().orEmpty()

    val pending = remember(agendas) { agendas.count { it.isTask && it.doneAt == null } }

    fun generate() {
        val name = className.trim()
        if (name.isEmpty()) {
            hint = "先填一下班级名 —— 同学靠它认出这是你们班的作业" to false
            return
        }
        val doc = buildClassDoc(name, agendas)
        if (doc.tasks.isEmpty()) {
            hint = "你现在还没有未完成的截止任务可以分享。先去「我的日程」里建几条" to false
            return
        }
        val token = encodeClassToken(doc)
        clipboard.setPrimaryClip(ClipData.newPlainText("班级作业分享串", token))
        hint = "已复制 ${doc.tasks.size} 条作业的分享串（${token.length} 字），发到班级群里就行" to true
    }

    fun import() {
        val text = readClip()
        if (text.isBlank()) {
            hint = "剪贴板是空的。先在群里长按复制学委发的分享串，再回来点这里" to false
            return
        }
        busy = true
        runCatching { decodeClassToken(text) }
            .onSuccess { doc ->
                val (next, result) = mergeClassTasks(agendas, doc)
                onAgendasChange(next)
                val who = doc.name.ifBlank { "班级" }
                hint = "$who：${result.summary()}" to true
            }
            .onFailure { e ->
                hint = (e.message ?: "这串读不出来，让对方重新发一次") to false
            }
        busy = false
    }

    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp)
            .padding(bottom = 26.dp),
    ) {
        Row(
            Modifier
                .padding(bottom = 8.dp)
                .clip(G3CornerShape(10.dp))
                .clickable(onClick = onClose)
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
            Text("返回我的日程", fontSize = 12.5.sp, color = scheme.onSurfaceVariant)
        }

        if (!LocalIsWidePane.current) {
            Text(
                "班级作业",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Medium,
                color = scheme.onSurface,
            )
            Spacer(Modifier.height(4.dp))
        }
        Text(
            "学委把自己建的截止任务打包成一行字发到群里，同学粘一下就能全部加进自己的列表 —— " +
                "今日页、逾期标红、勾选完成都跟普通任务一样。",
            fontSize = 12.5.sp,
            lineHeight = 18.sp,
            color = scheme.onSurfaceVariant,
        )

        Spacer(Modifier.height(18.dp))

        OutlinedTextField(
            value = className,
            onValueChange = { className = it.take(20) },
            label = { Text("班级名（建议填学委名字）", fontSize = 12.sp) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(12.dp),
        )
        Spacer(Modifier.height(6.dp))
        Text(
            "这个值就是「哪一份清单」。名字对不上，同学就拉不到你们班的作业。",
            fontSize = 11.5.sp,
            lineHeight = 16.sp,
            color = scheme.onSurfaceVariant.copy(alpha = 0.8f),
        )

        Spacer(Modifier.height(18.dp))

        Row(Modifier.fillMaxWidth()) {
            OutlinedButton(
                onClick = { generate() },
                modifier = Modifier.weight(1f),
                shape = RoundedCornerShape(12.dp),
            ) {
                Text("生成分享串", fontSize = 13.sp)
            }
            Spacer(Modifier.width(10.dp))
            Button(
                onClick = { import() },
                enabled = !busy,
                modifier = Modifier.weight(1f),
                shape = RoundedCornerShape(12.dp),
            ) {
                Text("导入剪贴板", fontSize = 13.sp)
            }
        }

        Spacer(Modifier.height(12.dp))
        Text(
            "分享时会带上你现在 ${pending} 条未完成的截止任务；导入只新增和更新，" +
                "你已经勾掉的作业不会被打回未完成。",
            fontSize = 11.5.sp,
            lineHeight = 16.sp,
            color = scheme.onSurfaceVariant.copy(alpha = 0.75f),
        )

        hint?.let { (text, ok) ->
            Spacer(Modifier.height(14.dp))
            Column(
                Modifier
                    .fillMaxWidth()
                    .background(
                        color = if (ok) {
                            scheme.primary.copy(alpha = 0.10f)
                        } else {
                            scheme.error.copy(alpha = 0.10f)
                        },
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

        Spacer(Modifier.height(20.dp))

        Text(
            "这一版走的是「手动发串」：学委加了新作业要再发一次。" +
                "等确认班里确实天天看，会接成打开 App 自动同步。",
            fontSize = 11.5.sp,
            lineHeight = 16.sp,
            color = scheme.onSurfaceVariant.copy(alpha = 0.6f),
        )
    }
}
