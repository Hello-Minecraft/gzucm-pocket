package com.gzucm.pocket.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
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
import androidx.compose.material.icons.Icons
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
