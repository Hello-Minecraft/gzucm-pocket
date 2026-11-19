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
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.gzucm.pocket.core.Feedback
import com.gzucm.pocket.ui.adaptive.AdaptiveSheet
import com.gzucm.pocket.ui.components.G3CornerShape
import com.gzucm.pocket.ui.icons.AppIcons

/**
 * 问题反馈面板。
 *
 * ## 这一屏唯一的重要东西是那串版本号
 *
 * 反馈走外部问卷（[Feedback.SurveyUrl]），点「打开问卷」就跳出 App 了 ——
 * 用户跳出去之后**回不来**，而问卷第一题就要版本号。
 * 所以版本号必须是这一屏视觉上最大的元素（34sp），并且一点就能复制。
 *
 * 手抄一版「1.3.6」不难，但错一个数字，排查时就会对着错误的版本号找问题，
 * 比没有版本号更糟。所以「复制」比「显示」更关键。
 *
 * ## 链接为空时会降级
 *
 * 问卷还没建好（[Feedback.SurveyUrl] 是空串）时，主按钮变成「复制版本号」，
 * 用户仍然有可用动作，不会对着一个点不动的按钮发愣。
 */
@Composable
fun FeedbackSheet(
    onDismiss: () -> Unit,
    visible: Boolean = true,
) {
    AdaptiveSheet(
        visible = visible,
        onDismiss = onDismiss,
        paneTitle = "问题反馈",
    ) {
        FeedbackContent(onBack = onDismiss)
    }
}

/**
 * 反馈正文，**不含容器**。
 *
 * 和 [DonateContent] 一样：既能被 [FeedbackSheet] 包成底部弹层，
 * 也能直接嵌进设置面板当子页面。
 */
@Composable
fun FeedbackContent(onBack: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    val ctx = LocalContext.current
    val version = Feedback.appVersion

    fun copyVersion() {
        val cm = ctx.getSystemService(android.content.Context.CLIPBOARD_SERVICE)
            as android.content.ClipboardManager
        cm.setPrimaryClip(android.content.ClipData.newPlainText("App 版本号", version))
        android.widget.Toast.makeText(ctx, "已复制版本号 $version", android.widget.Toast.LENGTH_SHORT)
            .show()
    }

    fun openSurvey() {
        if (!Feedback.hasSurvey) return
        val ok = runCatching {
            ctx.startActivity(
                android.content.Intent(
                    android.content.Intent.ACTION_VIEW,
                    android.net.Uri.parse(Feedback.SurveyUrl),
                ).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        }.isSuccess
        if (!ok) {
            // 没有浏览器 / 链接被系统拦截：退化成复制版本号，别让用户卡在这里
            copyVersion()
            android.widget.Toast.makeText(
                ctx,
                "打不开网页，版本号已复制，可以粘贴到浏览器",
                android.widget.Toast.LENGTH_LONG,
            ).show()
        }
    }

    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 22.dp)
            .padding(bottom = 26.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        // ── 返回设置 ──────────────────────────────────────────
        Row(
            Modifier
                .fillMaxWidth()
                .padding(bottom = 6.dp),
        ) {
            Row(
                Modifier
                    .clip(G3CornerShape(10.dp))
                    .clickable(onClick = onBack)
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
                Text(
                    "返回设置",
                    fontSize = 12.5.sp,
                    color = scheme.onSurfaceVariant,
                )
            }
        }

        Text(
            "遇到问题了？",
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Medium,
        )
        Spacer(Modifier.height(2.dp))
        Text(
            "先记下版本号，问卷第一题要填",
            fontSize = 13.sp,
            color = scheme.onSurfaceVariant,
        )

        Spacer(Modifier.height(18.dp))

        // ── 版本号：整屏最大的一块，点一下就复制 ────────────────
        Column(
            Modifier
                .fillMaxWidth()
                .clip(G3CornerShape(14.dp))
                .background(scheme.primary.copy(alpha = 0.10f))
                .clickable(onClick = { copyVersion() })
                .padding(vertical = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                "当前版本",
                fontSize = 11.sp,
                letterSpacing = 1.sp,
                color = scheme.primary.copy(alpha = 0.75f),
            )
            Spacer(Modifier.height(2.dp))
            Text(
                version,
                /*
                 * 34sp：整个 App 里最大的字号（周标题 26sp、余额数字 30sp）。
                 * 这一屏的用途就是「让你记住这串数字」，所以它应该是主角。
                 */
                fontSize = 34.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 1.5.sp,
                color = scheme.primary,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                "点这里复制",
                fontSize = 11.sp,
                color = scheme.onSurfaceVariant.copy(alpha = 0.75f),
            )
        }

        Spacer(Modifier.height(16.dp))

        /*
         * 说明文字必须跟按钮一致。
         *
         * 问卷没配好时按钮是「复制版本号」，这里还讲「点打开问卷会跳浏览器」
         * 就自相矛盾了 —— 用户会去找一个不存在的按钮。
         */
        Text(
            if (Feedback.hasSurvey) {
                "点「打开问卷」会跳到浏览器。问卷里第一题就是版本号，" +
                    "把上面这串粘贴进去就行（复制过就不用背）。"
            } else {
                "问卷还在准备中。这一屏的作用是先把版本号拿到手，" +
                    "等问卷上线了就能直接填。"
            },
            fontSize = 12.5.sp,
            lineHeight = 19.sp,
            textAlign = TextAlign.Center,
            color = scheme.onSurfaceVariant,
        )

        Spacer(Modifier.height(18.dp))

        // ── 主按钮 ────────────────────────────────────────────
        if (Feedback.hasSurvey) {
            Box(
                Modifier
                    .fillMaxWidth()
                    .clip(G3CornerShape(12.dp))
                    .background(scheme.primary)
                    .clickable(onClick = { openSurvey() })
                    .padding(vertical = 14.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    "打开问卷",
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Medium,
                    color = scheme.onPrimary,
                )
            }
        } else {
            /*
             * 问卷还没建（链接为空）。这时候不摆一个点不动的按钮，
             * 而是把「复制版本号」升成主按钮 —— 用户至少能拿走这串数字。
             */
            Box(
                Modifier
                    .fillMaxWidth()
                    .clip(G3CornerShape(12.dp))
                    .background(scheme.primary)
                    .clickable(onClick = { copyVersion() })
                    .padding(vertical = 14.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    "复制版本号",
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Medium,
                    color = scheme.onPrimary,
                )
            }

        }

        Spacer(Modifier.height(16.dp))

        // ── 隐私提醒 ──────────────────────────────────────────
        Column(
            Modifier
                .fillMaxWidth()
                .clip(G3CornerShape(10.dp))
                .background(scheme.surfaceVariant.copy(alpha = 0.5f))
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(3.dp),
        ) {
            Text(
                "发截图之前",
                fontSize = 11.sp,
                fontWeight = FontWeight.Medium,
                color = scheme.onSurfaceVariant,
            )
            Text(
                "课表截图里往往有学号、姓名、教室。问卷是公开链接，" +
                    "发之前把不想露的部分涂掉。",
                fontSize = 11.sp,
                lineHeight = 16.sp,
                color = scheme.onSurfaceVariant.copy(alpha = 0.85f),
            )
        }
    }
}
