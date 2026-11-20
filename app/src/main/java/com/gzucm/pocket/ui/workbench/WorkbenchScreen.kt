package com.gzucm.pocket.ui.workbench

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.gzucm.pocket.core.CampusApp
import com.gzucm.pocket.core.CampusApps
import com.gzucm.pocket.core.CampusDefaultCommon
import com.gzucm.pocket.ui.components.G3CornerShape
import com.gzucm.pocket.ui.components.LocalFloatingBarInset
import com.gzucm.pocket.ui.components.micaPanel
import com.gzucm.pocket.ui.components.micaPanelColor
import com.gzucm.pocket.ui.components.micaShadow
import com.gzucm.pocket.ui.icons.AppIcons

/**
 * 工作台：把原来分散在学校各个系统里的入口收在一页。
 *
 * 2026-11-21 替换掉原来的「成绩」Tab（致辉定：成绩收进工作台的一张卡片）。
 *
 * ## 为什么只放有地址的入口
 *
 * 合作者的原型列了 21 个，14 个没确认入口。全摆出来的话，用户点十个有七个
 * 弹「待确认」——比不放还糟。现在 [CampusApps] 里只留有正式地址的八条，
 * 以后确认一条补一条 url，网格自动多一格。
 *
 * ## 图标为什么用「首字」而不是矢量图
 *
 * 原型用 `material-icons-extended`（20MB 依赖换八枚图标）。这里改用首字方块：
 * 零依赖、跟着主题色走、八个格子的辨识度其实比一堆同色线性图标更高。
 */
@Composable
fun WorkbenchScreen(
    /** 学籍姓名，用来在标题下打一句招呼。null = 还没拉到 */
    studentName: String? = null,
    /** 已修课程数，给成绩卡片当副标题 */
    gradeCount: Int = 0,
    onOpenGrades: () -> Unit,
    onOpenApp: (CampusApp) -> Unit,
    /** 打开次数（appId → 次数），常用区按它排序 */
    openCounts: Map<String, Int> = emptyMap(),
) {
    val scheme = MaterialTheme.colorScheme
    val wide = LocalConfiguration.current.screenWidthDp >= 840
    var query by rememberSaveable { mutableStateOf("") }

    val term = query.trim()
    val filtering = term.isNotEmpty()

    val commons = remember(openCounts, wide) {
        /*
         * 只从「能在 App 内打开」的应用里选常用。
         *
         * 跳企业微信的那几个（实验室、宿管、路路通、库巴扎）点了只是把用户送出去，
         * App 里一次都没看到内容 —— 算进常用会把真正常用的挤下去。
         * 这里再过滤一次而不是只靠「不计数」：早期版本已经给它们计过数了，
         * 不清掉的历史数据还得靠这道闸拦住。
         */
        val byCount = CampusApps
            .filter { it.kind == com.gzucm.pocket.core.CampusAppKind.WEB }
            .filter { (openCounts[it.id] ?: 0) > 0 }
            .sortedByDescending { openCounts[it.id] ?: 0 }
            .map { it.id }
        (byCount + CampusDefaultCommon).distinct()
            .mapNotNull { id -> CampusApps.firstOrNull { it.id == id } }
            .take(if (wide) 6 else 4)
    }

    val grid = remember(term) {
        if (!filtering) CampusApps
        else CampusApps.filter { it.name.contains(term, ignoreCase = true) }
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(scheme.background),
    ) {
        LazyColumn(
            Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .then(if (wide) Modifier.widthIn(max = 860.dp) else Modifier)
                .align(Alignment.TopCenter),
            contentPadding = PaddingValues(
                start = 18.dp,
                end = 18.dp,
                top = 6.dp,
                bottom = LocalFloatingBarInset.current + 16.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            item(key = "header") {
                Column {
                    Text(
                        "工作台",
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Medium,
                        color = scheme.onSurface,
                    )
                    Spacer(Modifier.height(3.dp))
                    Text(
                        if (studentName.isNullOrBlank()) {
                            "学校的服务都收在这里，不用记网址"
                        } else {
                            "$studentName，学校的服务都收在这里，不用记网址"
                        },
                        fontSize = 12.sp,
                        color = scheme.onSurfaceVariant,
                    )
                }
            }

            item(key = "search") {
                SearchField(
                    value = query,
                    onValueChange = { query = it },
                )
            }

            if (!filtering) {
                item(key = "grades") {
                    GradesCard(
                        gradeCount = gradeCount,
                        onClick = onOpenGrades,
                    )
                }

                if (commons.isNotEmpty()) {
                    item(key = "common_title") { SectionTitle("常用") }
                    item(key = "common") {
                        /*
                         * 列数取「实际有几个」，不用固定的 4：
                         * 常用只有 3 个时，固定 4 列会让它们各占 1/3 宽，
                         * 和下面「学校服务」的 1/4 宽对不上，看着像另一套网格。
                         */
                        AppGrid(
                            apps = commons,
                            columns = commons.size.coerceIn(1, if (wide) 6 else 4),
                            onOpen = onOpenApp,
                        )
                    }
                }
            }

            item(key = "all_title") {
                SectionTitle(if (filtering) "搜索结果 · ${grid.size}" else "学校服务")
            }

            if (grid.isEmpty()) {
                item(key = "empty") {
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .padding(vertical = 40.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            "没有叫「$term」的服务",
                            fontSize = 13.sp,
                            color = scheme.onSurfaceVariant,
                        )
                    }
                }
            } else {
                item(key = "all") {
                    AppGrid(
                        apps = grid,
                        columns = if (wide) 6 else 4,
                        onOpen = onOpenApp,
                    )
                }
            }
        }
    }
}

/** 成绩卡片：工作台的第一张，也是最大的一张 */
@Composable
private fun GradesCard(gradeCount: Int, onClick: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    Row(
        Modifier
            .fillMaxWidth()
            .micaShadow(18.dp)
            .clip(G3CornerShape(18.dp))
            .background(micaPanelColor())
            .clickable(onClick = onClick)
            .padding(horizontal = 18.dp, vertical = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .size(42.dp)
                .clip(G3CornerShape(12.dp))
                .background(scheme.primary.copy(alpha = 0.16f)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                AppIcons.Insights,
                contentDescription = null,
                tint = scheme.primary,
                modifier = Modifier.size(22.dp),
            )
        }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(
                "我的成绩",
                fontSize = 15.sp,
                fontWeight = FontWeight.Medium,
                color = scheme.onSurface,
            )
            Spacer(Modifier.height(3.dp))
            Text(
                if (gradeCount > 0) "已修 $gradeCount 门 · 学分构成 / 公选分类 / 体测" else "学分构成 / 公选分类 / 体测",
                fontSize = 11.5.sp,
                color = scheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Icon(
            AppIcons.ChevronRight,
            contentDescription = null,
            tint = scheme.onSurfaceVariant.copy(alpha = 0.6f),
            modifier = Modifier.size(18.dp),
        )
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(
        text,
        fontSize = 12.5.sp,
        fontWeight = FontWeight.Medium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(start = 2.dp),
    )
}

/**
 * 应用网格。
 *
 * 用 Row 手排而不是 LazyVerticalGrid：这个网格要嵌在 LazyColumn 里，
 * 而 LazyVerticalGrid 不能塞进 LazyColumn（无限高度约束直接崩）。
 * 条目只有个位数到二十条，手排没有性能问题。
 */
@Composable
private fun AppGrid(
    apps: List<CampusApp>,
    columns: Int,
    onOpen: (CampusApp) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        apps.chunked(columns).forEach { row ->
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                row.forEach { app ->
                    AppTile(app = app, onOpen = { onOpen(app) }, modifier = Modifier.weight(1f))
                }
                // 最后一行不满时补空位，免得格子被拉宽
                repeat(columns - row.size) {
                    Spacer(Modifier.weight(1f))
                }
            }
        }
    }
}

@Composable
private fun AppTile(app: CampusApp, onOpen: () -> Unit, modifier: Modifier = Modifier) {
    val scheme = MaterialTheme.colorScheme
    // 打不开的（证书过期等）整格降透明度：看得见、知道有这东西，但一眼能看出不能用
    val broken = app.kind == com.gzucm.pocket.core.CampusAppKind.BROKEN
    val fg = if (broken) scheme.onSurfaceVariant.copy(alpha = 0.5f) else scheme.onSurface
    Column(
        modifier
            .clip(G3CornerShape(14.dp))
            .background(micaPanelColor())
            .clickable(onClick = onOpen)
            .padding(horizontal = 8.dp, vertical = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(Modifier.size(36.dp), contentAlignment = Alignment.Center) {
            Box(
                Modifier
                    .fillMaxSize()
                    .clip(G3CornerShape(11.dp))
                    .background(
                        if (broken) scheme.surfaceVariant.copy(alpha = 0.4f)
                        else scheme.primary.copy(alpha = 0.13f),
                    ),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    app.name.take(1),
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Medium,
                    color = if (broken) fg else scheme.primary,
                )
            }
            /*
             * 角标挂在方块右上角、稍微探出去一点。
             *
             * 「企微」= 这格点了是跳企业微信，不是在 App 里打开（见 CampusApps 的注释）；
             * 「http」= 明文地址，提醒一下。
             * 字号压到 8sp 是刻意的：它只是提示，不该比应用名还抢眼。
             */
            if (app.badge.isNotEmpty()) {
                Text(
                    app.badge,
                    fontSize = 8.sp,
                    lineHeight = 10.sp,
                    color = if (broken) fg else scheme.primary,
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .offset(x = 7.dp, y = (-5).dp)
                        .clip(RoundedCornerShape(4.dp))
                        .background(micaPanelColor())
                        .padding(horizontal = 3.dp, vertical = 1.dp),
                )
            }
        }
        Spacer(Modifier.height(7.dp))
        Text(
            app.name,
            fontSize = 11.sp,
            lineHeight = 14.sp,
            color = fg,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun SearchField(value: String, onValueChange: (String) -> Unit) {
    val scheme = MaterialTheme.colorScheme
    Row(
        Modifier
            .fillMaxWidth()
            .clip(G3CornerShape(13.dp))
            .background(micaPanelColor())
            .padding(horizontal = 13.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            AppIcons.Search,
            contentDescription = null,
            tint = scheme.onSurfaceVariant.copy(alpha = 0.7f),
            modifier = Modifier.size(16.dp),
        )
        Spacer(Modifier.width(9.dp))
        Box(Modifier.weight(1f)) {
            if (value.isEmpty()) {
                Text(
                    "搜索学校服务",
                    fontSize = 13.sp,
                    color = scheme.onSurfaceVariant.copy(alpha = 0.6f),
                )
            }
            BasicTextField(
                value = value,
                onValueChange = onValueChange,
                singleLine = true,
                textStyle = TextStyle(fontSize = 13.sp, color = scheme.onSurface),
                cursorBrush = SolidColor(scheme.primary),
                modifier = Modifier.fillMaxWidth(),
            )
        }
        if (value.isNotEmpty()) {
            Icon(
                AppIcons.Close,
                contentDescription = "清空",
                tint = scheme.onSurfaceVariant.copy(alpha = 0.7f),
                modifier = Modifier
                    .size(16.dp)
                    .clip(RoundedCornerShape(50))
                    .clickable { onValueChange("") },
            )
        }
    }
}
