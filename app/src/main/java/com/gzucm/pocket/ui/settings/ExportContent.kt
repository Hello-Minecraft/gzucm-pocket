package com.gzucm.pocket.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import com.gzucm.pocket.ui.components.G3CornerShape
import com.gzucm.pocket.ui.components.micaPanel
import androidx.compose.foundation.verticalScroll
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
import com.gzucm.pocket.ui.icons.AppIcons

/**
 * 导出课表的子页面（设置 → 导出课表数据 → 这里）。
 *
 * 和「关于软件 / 打赏作者」同一层级：同一块面板只换正文，返回回到设置。
 *
 * 两条路分别对应两种场景（致辉 2026-11-18 定）：
 *
 * | 路径 | 出什么 | 怎么给对方 |
 * |---|---|---|
 * | 复制分享串 | 压缩后约 1300 字符的一行 `GZP1:…` | 直接粘进微信一条消息 |
 * | 导出 JSON 文件 | 完整明文，带逐周上课内容 | 走系统分享发文件 |
 *
 * 为什么两条都留：分享串受微信字数上限约束，必须砍掉上课内容；
 * 文件没有长度限制，才是完整数据。用户按「对方要什么」自己挑。
 */
@Composable
fun ExportContent(
    /** 课程模板条数（不是按周展开的 450 条） */
    courseCount: Int,
    /** 实际覆盖到的周数 */
    weeksCovered: Int,
    totalWeeks: Int,
    /** 分享串字符数，显示出来让他对「微信塞不塞得下」有体感 */
    tokenChars: Int,
    onBack: () -> Unit,
    onCopyToken: () -> Unit,
    onShareFile: () -> Unit,
) {
    val scheme = MaterialTheme.colorScheme

    Column(
        Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp)
            .padding(top = 6.dp, bottom = 24.dp),
    ) {
        // 面包屑返回：和关于页同一种写法，位置别的地方也照着来
        Row(
            Modifier
                .clip(G3CornerShape(8.dp))
                .clickable(onClick = onBack)
                .padding(vertical = 6.dp, horizontal = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                AppIcons.ArrowBack,
                contentDescription = "返回",
                modifier = Modifier.size(18.dp),
                tint = scheme.onSurfaceVariant,
            )
            Spacer(Modifier.width(6.dp))
            Text("设置", fontSize = 13.sp, color = scheme.onSurfaceVariant)
        }

        Spacer(Modifier.height(10.dp))
        Text(
            "导出课表",
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Medium,
        )

        Spacer(Modifier.height(8.dp))
        Text(
            buildString {
                append("$courseCount 门课")
                if (totalWeeks > 0) append(" · 覆盖 $weeksCovered/$totalWeeks 周")
                if (totalWeeks > 0 && weeksCovered < totalWeeks) {
                    append("（不完整，建议先「立即同步」）")
                }
            },
            fontSize = 11.5.sp,
            lineHeight = 17.sp,
            color = if (totalWeeks > 0 && weeksCovered < totalWeeks) scheme.error else scheme.onSurfaceVariant,
        )

        Spacer(Modifier.height(16.dp))

        // 两条路放进同一张卡片，中间一条分隔线（和设置页的分组习惯一致）
        Column(
            Modifier
                .fillMaxWidth()
                .micaPanel(14.dp),
        ) {
            ExportOption(
                icon = AppIcons.ViewAgenda,
                title = "复制分享串",
                /*
                 * 「不含教师」必须写出来（2026-11-18 实测 5100 字符之后加的）：
                 * 见习轮转的带教老师每周不同，115 个名字压不动，
                 * 带上就从 1751 涨到 3300，微信发不出去。要教师只能走文件。
                 */
                detail = "约 %,d 字符，粘进微信就能发（不含任课教师）".format(tokenChars),
                onClick = onCopyToken,
            )
            Box(
                Modifier
                    .fillMaxWidth()
                    .padding(start = 47.dp)
                    .height(0.5.dp)
                    .background(scheme.onSurfaceVariant.copy(alpha = 0.12f)),
            )
            ExportOption(
                icon = AppIcons.GridView,
                title = "导出 JSON 文件",
                detail = "完整明文，带逐周上课内容；走系统分享",
                onClick = onShareFile,
            )
        }

        Spacer(Modifier.height(14.dp))

        /*
         * 这句是致辉指定要的提示文案（2026-11-18）。
         * 放在两个选项下面而不是上面：先看到能做什么，再看到「不行怎么办」。
         */
        Row(
            Modifier
                .fillMaxWidth()
                .clip(G3CornerShape(10.dp))
                .background(scheme.primary.copy(alpha = 0.08f))
                .padding(horizontal = 12.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.Start,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                AppIcons.Info,
                contentDescription = null,
                modifier = Modifier.size(15.dp),
                tint = scheme.primary,
            )
            Spacer(Modifier.width(8.dp))
            Text(
                "微信发不出去？发文件就可以。",
                fontSize = 12.sp,
                color = scheme.primary,
            )
        }

        Spacer(Modifier.height(10.dp))

        // 网页那边怎么用，一句话说清；细节在仓库 docs/EXPORT_FORMAT.md
        Text(
            "分享串是精简版（课名/节次/周次/教室），文件是完整明文（含教师、类别、配色与逐周内容）。" +
                "网页端按 GZP2: 前缀或 .json 文件任选一种解析。",
            fontSize = 11.sp,
            lineHeight = 17.sp,
            color = scheme.onSurfaceVariant.copy(alpha = 0.85f),
        )
    }
}

@Composable
private fun ExportOption(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    detail: String,
    onClick: () -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            icon,
            contentDescription = null,
            modifier = Modifier.size(19.dp),
            tint = scheme.onSurface,
        )
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(title, fontSize = 13.5.sp, color = scheme.onSurface)
            Spacer(Modifier.height(3.dp))
            Text(detail, fontSize = 11.sp, lineHeight = 16.sp, color = scheme.onSurfaceVariant)
        }
        Spacer(Modifier.width(8.dp))
        Icon(
            AppIcons.ChevronRight,
            contentDescription = null,
            modifier = Modifier.size(17.dp),
            tint = scheme.onSurfaceVariant.copy(alpha = 0.5f),
        )
    }
}
