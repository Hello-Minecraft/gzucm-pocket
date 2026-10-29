package com.gzucm.pocket.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.launch
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.gzucm.pocket.core.ReleaseNotes

/**
 * 关于软件：应用信息 + 版本更新日志。
 *
 * 从设置页进来（设置 → 关于软件），不直接铺在设置页面上。
 */
@Composable
fun AboutScreen(onBack: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    val ver = runCatching { com.gzucm.pocket.BuildConfig.VERSION_NAME }.getOrDefault("—")
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val scope = androidx.compose.runtime.rememberCoroutineScope()

    var checking by remember { mutableStateOf(false) }
    var downloading by remember { mutableStateOf(false) }
    var updateMsg by remember { mutableStateOf<String?>(null) }
    var updateOk by remember { mutableStateOf(true) }
    var newVersion by remember { mutableStateOf<String?>(null) }
    var releasePage by remember { mutableStateOf("") }
    var apkUrl by remember { mutableStateOf<String?>(null) }

    fun openUrl(url: String) {
        if (url.isBlank()) return
        runCatching {
            ctx.startActivity(
                android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(url))
                    .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        }
    }

    fun check() {
        if (checking) return
        checking = true
        updateMsg = null
        newVersion = null
        scope.launch {
            when (val r = com.gzucm.pocket.core.Updater.check()) {
                is com.gzucm.pocket.core.Updater.Result.Newer -> {
                    updateOk = true
                    newVersion = r.info.latestVersion
                    releasePage = r.info.pageUrl
                    apkUrl = r.info.apkUrl
                    updateMsg = "发现新版本 v${r.info.latestVersion}" +
                        if (r.info.sizeMb > 0) "（${"%.1f".format(r.info.sizeMb)} MB）" else ""
                }
                com.gzucm.pocket.core.Updater.Result.UpToDate -> {
                    updateOk = true
                    updateMsg = "已是最新版本 v$ver"
                }
                is com.gzucm.pocket.core.Updater.Result.Failed -> {
                    updateOk = false
                    updateMsg = r.reason
                }
            }
            checking = false
        }
    }

    fun download() {
        val url = apkUrl
        if (url.isNullOrBlank()) {
            // 没有 apk 附件就去 Release 页
            openUrl(releasePage)
            return
        }
        if (downloading) return
        downloading = true
        updateMsg = null
        when (val r = com.gzucm.pocket.core.ApkDownloader.enqueue(ctx, url, newVersion ?: "new")) {
            is com.gzucm.pocket.core.ApkDownloader.Result.Ok -> {
                updateOk = true
                updateMsg = "已开始下载 ${r.fileName}，完成后点通知栏安装"
            }
            is com.gzucm.pocket.core.ApkDownloader.Result.Failed -> {
                updateOk = false
                updateMsg = r.reason
            }
        }
        downloading = false
    }

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 18.dp),
    ) {
        Spacer(Modifier.height(10.dp))

        // 返回
        Row(
            Modifier
                .clip(RoundedCornerShape(10.dp))
                .clickable(onClick = onBack)
                .padding(vertical = 6.dp, horizontal = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                Icons.Rounded.ArrowBack,
                contentDescription = "返回",
                modifier = Modifier.size(18.dp),
                tint = scheme.onSurfaceVariant,
            )
            Spacer(Modifier.width(6.dp))
            Text("设置", fontSize = 13.sp, color = scheme.onSurfaceVariant)
        }

        Spacer(Modifier.height(10.dp))
        Text(
            "关于软件",
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Medium,
        )

        Spacer(Modifier.height(18.dp))

        // 应用卡
        Column(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(14.dp))
                .background(scheme.surface)
                .padding(16.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("广中医口袋", fontSize = 15.sp, fontWeight = FontWeight.Medium)
                Spacer(Modifier.weight(1f))
                Text(
                    "v$ver",
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium,
                    color = scheme.primary,
                )
            }
            Spacer(Modifier.height(6.dp))
            Text(
                "广州中医药大学 教务 + 一卡通 客户端",
                fontSize = 11.5.sp,
                color = scheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(2.dp))
            Text(
                "数据直连学校系统，不经过任何第三方服务器；账号密码只提交给学校认证页，App 不保存。",
                fontSize = 11.sp,
                lineHeight = 17.sp,
                color = scheme.onSurfaceVariant.copy(alpha = 0.85f),
            )

            Spacer(Modifier.height(12.dp))

            // 检查更新：直连 GitHub Releases
            Row(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(10.dp))
                    .background(scheme.surfaceVariant.copy(alpha = 0.3f))
                    .clickable(enabled = !checking && !downloading) { check() }
                    .padding(horizontal = 12.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    when {
                        checking -> "正在检查…"
                        downloading -> "正在下载…"
                        else -> "检查更新"
                    },
                    fontSize = 12.5.sp,
                    color = scheme.primary,
                    fontWeight = FontWeight.Medium,
                )
                Spacer(Modifier.weight(1f))
                if (checking) {
                    androidx.compose.material3.CircularProgressIndicator(
                        modifier = Modifier.size(14.dp),
                        strokeWidth = 1.6.dp,
                    )
                } else {
                    Icon(
                        Icons.Rounded.Refresh,
                        contentDescription = null,
                        modifier = Modifier.size(14.dp),
                        tint = scheme.primary.copy(alpha = 0.8f),
                    )
                }
            }

            if (updateMsg != null) {
                Spacer(Modifier.height(8.dp))
                Text(
                    updateMsg!!,
                    fontSize = 11.sp,
                    lineHeight = 16.sp,
                    color = if (updateOk) scheme.primary else scheme.error,
                )
            }

            // 有新版本：给下载 / 打开浏览器两个入口
            if (newVersion != null) {
                Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "下载 v$newVersion",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Medium,
                        color = scheme.surface,
                        modifier = Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .background(scheme.primary)
                            .clickable(enabled = !downloading) { download() }
                            .padding(horizontal = 12.dp, vertical = 7.dp),
                    )
                    Spacer(Modifier.width(10.dp))
                    Text(
                        "在浏览器打开",
                        fontSize = 11.5.sp,
                        color = scheme.onSurfaceVariant,
                        modifier = Modifier
                            .clickable { openUrl(releasePage) }
                            .padding(vertical = 6.dp),
                    )
                }
            }
        }

        Spacer(Modifier.height(16.dp))

        // 更新日志
        Column(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(14.dp))
                .background(scheme.surface)
                .padding(16.dp),
        ) {
            Text("更新日志", fontSize = 13.sp, color = scheme.onSurfaceVariant)
            Spacer(Modifier.height(14.dp))

            ReleaseNotes.entries.forEachIndexed { idx, e ->
                if (idx > 0) {
                    Spacer(Modifier.height(14.dp))
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .height(1.dp)
                            .background(scheme.onSurfaceVariant.copy(alpha = 0.08f)),
                    )
                    Spacer(Modifier.height(14.dp))
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "v${e.version}",
                        fontSize = 12.5.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = if (idx == 0) scheme.primary else scheme.onSurface,
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        e.date,
                        fontSize = 10.5.sp,
                        color = scheme.onSurfaceVariant.copy(alpha = 0.7f),
                    )
                    if (idx == 0) {
                        Spacer(Modifier.width(8.dp))
                        Box(
                            Modifier
                                .clip(RoundedCornerShape(5.dp))
                                .background(scheme.primary.copy(alpha = 0.14f))
                                .padding(horizontal = 6.dp, vertical = 1.dp),
                        ) {
                            Text("当前", fontSize = 9.5.sp, color = scheme.primary)
                        }
                    }
                }
                Spacer(Modifier.height(5.dp))
                Text(
                    e.headline,
                    fontSize = 12.sp,
                    lineHeight = 18.sp,
                    color = scheme.onSurface,
                )
                // 只有最新一版展开细节，历史版本只留一句话
                if (e.items.isNotEmpty() && idx == 0) {
                    Spacer(Modifier.height(6.dp))
                    e.items.forEach { line ->
                        Row(Modifier.padding(bottom = 3.dp)) {
                            Text("· ", fontSize = 11.5.sp, color = scheme.onSurfaceVariant)
                            Text(
                                line,
                                fontSize = 11.5.sp,
                                lineHeight = 17.sp,
                                color = scheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        }

        Spacer(Modifier.height(28.dp))
    }
}
