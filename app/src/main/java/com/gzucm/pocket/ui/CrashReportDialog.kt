package com.gzucm.pocket.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.gzucm.pocket.core.CrashLog
import com.gzucm.pocket.ui.components.micaPanel

/**
 * 「刚刚崩溃了」的弹窗。
 *
 * 只在**上次启动崩过**时出现一次（[CrashLog.read] 读到文件才调用）。
 * 两个动作都给足：想反馈的点主按钮（复制 + 直接跳问卷），
 * 不想管的点「知道了」，日志当场删掉，不会再烦第二次。
 *
 * 为什么不用 AdaptiveSheet：这是个**打断式**提示，用户刚经历闪退、心态不稳，
 * 底部面板容易被当成「又要我填什么」；居中的小卡片 + 一句话说明更直接。
 */
@Composable
fun CrashReportDialog(
    report: CrashLog.Report,
    onDismiss: () -> Unit,
) {
    val ctx = LocalContext.current
    val scheme = MaterialTheme.colorScheme

    fun copyLog() {
        val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("广中医口袋崩溃日志", report.text))
    }

    /*
     * 只复制，**不直接跳浏览器**。
     *
     * 本来写的是「复制 + ACTION_VIEW 打开问卷」，2026-11-20 实机测出来：
     * 点下去 hua 地打开一个「周大福黄金手镯 · 摇一摇跳转」的广告页 ——
     * 系统默认浏览器把这个链接劫持成开屏广告了。
     * 用户刚经历闪退，再被弹一脸广告，只会更不敢反馈。
     *
     * 所以退一步：复制好、告诉用户去哪儿粘。想发微信发微信，
     * 想填问卷就从「设置 → 问题反馈」进去，链路他自己选，谁也劫持不了。
     */
    fun copyAndDismiss() {
        copyLog()
        Toast.makeText(
            ctx,
            "崩溃日志已复制，粘到「设置 → 问题反馈」的问卷里，或者直接发我",
            Toast.LENGTH_LONG,
        ).show()
        onDismiss()
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 22.dp)
                .micaPanel(20.dp)
                .padding(horizontal = 20.dp, vertical = 18.dp),
        ) {
            Text(
                "广中医口袋刚刚崩溃了",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Medium,
                color = scheme.onSurface,
            )
            Spacer(Modifier.height(6.dp))
            Text(
                "上次闪退的现场已经记在这台手机上了。把下面这段发给我，就能查到是哪一步出的问题 —— " +
                    "不用你再描述当时点了什么。",
                fontSize = 12.5.sp,
                lineHeight = 18.sp,
                color = scheme.onSurfaceVariant,
            )

            Spacer(Modifier.height(14.dp))

            // 异常摘要：一行就让人看出是哪类问题（空指针 / 越界 / 无限约束…）
            Text(
                report.headline.ifBlank { "（没有读到异常类型）" },
                fontFamily = FontFamily.Monospace,
                fontSize = 11.5.sp,
                lineHeight = 16.sp,
                color = scheme.error,
                maxLines = 3,
            )

            Spacer(Modifier.height(10.dp))

            // 完整日志：等宽、可滚，最多 200dp 高，不把弹窗撑满
            Box(
                Modifier
                    .fillMaxWidth()
                    .heightIn(max = 200.dp)
                    .micaPanel(12.dp)
                    .padding(horizontal = 12.dp, vertical = 10.dp),
            ) {
                Text(
                    report.text,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 10.sp,
                    lineHeight = 14.sp,
                    color = scheme.onSurfaceVariant,
                    modifier = Modifier.verticalScroll(rememberScrollState()),
                )
            }

            Spacer(Modifier.height(16.dp))

            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.End,
            ) {
                TextButton(onClick = onDismiss) {
                    Text("知道了", fontSize = 13.sp, color = scheme.onSurfaceVariant)
                }
                Spacer(Modifier.width(8.dp))
                Button(onClick = ::copyAndDismiss) {
                    Text("复制崩溃日志", fontSize = 13.sp)
                }
            }
        }
    }
}
