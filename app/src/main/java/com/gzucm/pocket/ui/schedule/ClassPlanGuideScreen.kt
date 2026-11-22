package com.gzucm.pocket.ui.schedule

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.gzucm.pocket.ui.components.micaPanel
import com.gzucm.pocket.ui.icons.AppIcons

/**
 * 学委教程页正文。
 *
 * ## 排版口径
 *
 * 一节的标题和正文放在同一张 [micaPanel] 卡片里：七节拆成七张卡之后，
 * 用户一眼能数出「还有几节没看」，比一整片长文好定位。
 * 节标题 15sp Medium、正文 13sp/20sp —— 和 [ClassPlanSheet] 里的说明文字同档，
 * 两处并排看不会一个大一个小。
 *
 * ## 宽屏为什么限宽 640dp
 *
 * 平板横屏全宽铺满时，一行能塞 40 多个汉字，眼睛从行首扫到行尾要来回折返，
 * 读三行就串行。640dp 大约 28 个汉字一行，接近纸质说明的舒适宽度。
 * 卡片本身跟着限宽，不然卡片比文字宽出两截更难看。
 *
 * ## 加粗
 *
 * 原文里的加粗用 `**...**` 标记，由 [rich] 解析成 Medium 字重。
 * 只加在按钮名（「加一条」「发布到本班」）和配额数字上 ——
 * 通篇加粗等于没加粗。
 */
@Composable
fun ClassPlanGuideScreen(onClose: () -> Unit) {
    val scheme = MaterialTheme.colorScheme

    Column(
        Modifier
            .fillMaxSize()
            .background(scheme.background),
    ) {
        // 顶栏：唯一确定的出口。正文再长也一直贴在顶上，不用滑到顶部才能退
        Row(
            Modifier
                .fillMaxWidth()
                .statusBarsPadding()
                .padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                Modifier
                    .size(36.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .clickable(onClick = onClose),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    AppIcons.ArrowBack,
                    contentDescription = "返回",
                    tint = scheme.onSurface,
                    modifier = Modifier.size(20.dp),
                )
            }
            Spacer(Modifier.width(6.dp))
            Text(
                "班级日程怎么用",
                fontSize = 15.sp,
                fontWeight = FontWeight.Medium,
                color = scheme.onSurface,
            )
        }

        BoxWithConstraints(
            Modifier
                .fillMaxWidth()
                .weight(1f)
                .navigationBarsPadding(),
        ) {
            val wide = maxWidth >= 600.dp

            Column(
                Modifier
                    .align(Alignment.TopCenter)
                    .then(if (wide) Modifier.widthIn(max = 640.dp) else Modifier)
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 18.dp)
                    .padding(top = 4.dp, bottom = 28.dp),
            ) {
                Section("这份清单是给全班的") {
                    Body(
                        "你维护的是一份**全班共用**的作业清单。发布之后，本班每个装了新版" +
                            "「广中医口袋」的同学，打开 App 都会看到「本班有班级日程」，" +
                            "认领一下，这些作业就进他们自己的日程了。",
                    )
                    Spacer(Modifier.height(8.dp))
                    Body(
                        "所以只放**全班都需要的东西**：作业截止时间、见习安排、考试周。" +
                            "个人备忘放你自己的日程里。",
                    )
                }

                Spacer(Modifier.height(12.dp))

                Section("三步发布") {
                    Step(
                        no = "1",
                        title = "加条目",
                        body = "点「**加一条**」，填标题。有截止时间的填截止时间；" +
                            "是「某天某时段」的（见习、实验课这种）就选日期和时间段，顺手填上地点。",
                    )
                    Spacer(Modifier.height(10.dp))
                    Step(
                        no = "2",
                        title = "填密钥",
                        body = "密钥是全班同学用来找到你这份清单的名字，一般就填你自己的名字。" +
                            "班长代维护的话也填学委的名字——同学认的是学委。",
                    )
                    Spacer(Modifier.height(10.dp))
                    Step(
                        no = "3",
                        title = "发布到本班",
                        body = "发布前会让你再确认一次，因为这一下**全班都会收到提醒**。",
                    )
                }

                Spacer(Modifier.height(12.dp))

                Section("改了之后怎么办") {
                    Body("改完直接点「**发布到本班**」再发一次，不用重新发群消息。")
                    Spacer(Modifier.height(8.dp))
                    Body(
                        "同学那边只会更新你改过的那几条，他们**自己勾掉的完成状态不会被重置**。",
                    )
                }

                Spacer(Modifier.height(12.dp))

                Section("配额") {
                    Bullet("每人最多 **2 份清单**")
                    Bullet("每份最多 **80 条**")
                    Bullet("一个班最多 **8 份**")
                    Spacer(Modifier.height(8.dp))
                    Body("超过会被服务端拒绝。需要更多就找同学分工，比如各科课代表各维护一份。")
                }

                Spacer(Modifier.height(12.dp))

                Section("三件别做的事") {
                    Bullet("不要把个人日程整份传上去，里面有你的私事")
                    Bullet("不要同一个人传两份一样的清单，同学会看到两个")
                    Bullet(
                        "不要拿它当通知用。同学什么时候打开 App 什么时候才看到，" +
                            "急事还是在群里说",
                    )
                }

                Spacer(Modifier.height(12.dp))

                Section("删掉自己那份") {
                    Body(
                        "在「班级日程」面板底部可以删除。删了之后同学那边会提示「清单已被删除」，" +
                            "但他们已经导入的日程不会消失，会变成他们自己的普通日程。",
                    )
                }

                Spacer(Modifier.height(12.dp))

                Section("原来的分享串还能用") {
                    Body(
                        "生成一串码发群里那个老办法还在，没删。适合同学还没升到新版、" +
                            "或者群里只有几个人需要的情况。全班都用的话，走发布更省事。",
                    )
                }
            }
        }
    }
}

// ── 子组件 ────────────────────────────────────────────────────────────

/**
 * 一节。标题和正文同卡，卡片本身负责圆角/阴影/底色（[micaPanel]），
 * 这里只管内边距 —— 14dp 是卡片内的统一留白。
 */
@Composable
private fun Section(title: String, content: @Composable ColumnScope.() -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .micaPanel(corner = 16.dp)
            .padding(14.dp),
    ) {
        Text(
            title,
            fontSize = 15.sp,
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Spacer(Modifier.height(8.dp))
        content()
    }
}

/** 正文段落。行高 20sp 给中文留足行间，13sp 之上再挤会糊成一片。 */
@Composable
private fun Body(text: String, modifier: Modifier = Modifier) {
    Text(
        rich(text),
        fontSize = 13.sp,
        lineHeight = 20.sp,
        color = MaterialTheme.colorScheme.onSurface,
        modifier = modifier,
    )
}

/**
 * 带圆点的一条。
 *
 * 圆点占固定 12dp 宽、正文折行后对齐第二行行首 ——
 * 用 `Text("·　$text")` 拼一串的话，换行会顶到圆点下面，缩进就断了。
 */
@Composable
private fun Bullet(text: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
        Text(
            "·",
            fontSize = 13.sp,
            lineHeight = 20.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(12.dp),
        )
        Text(
            rich(text),
            fontSize = 13.sp,
            lineHeight = 20.sp,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f),
        )
    }
}

/** 三步发布里的一步：序号圆点 + 步骤名，正文缩到步骤名下面。 */
@Composable
private fun Step(no: String, title: String, body: String) {
    val scheme = MaterialTheme.colorScheme
    Column(Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier
                    .size(18.dp)
                    .clip(CircleShape)
                    .background(scheme.primary.copy(alpha = 0.14f)),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    no,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Medium,
                    color = scheme.primary,
                )
            }
            Spacer(Modifier.width(8.dp))
            Text(
                title,
                fontSize = 13.sp,
                fontWeight = FontWeight.Medium,
                color = scheme.onSurface,
            )
        }
        Spacer(Modifier.height(4.dp))
        Body(body, Modifier.padding(start = 26.dp))
    }
}

/**
 * 把 `**...**` 解析成 Medium 字重。
 *
 * 用标记串而不是给每段拼 `AnnotatedString`：文案以后要从别处复制粘贴过来，
 * 带标记的原文能直接对照，不用在一堆 `append` / `withStyle` 里找哪句是加粗的。
 * 奇数个 `**` 时最后一段按普通文字收尾，不会吞字。
 */
private fun rich(text: String): AnnotatedString = buildAnnotatedString {
    var bold = false
    text.split("**").forEach { part ->
        if (bold) withStyle(SpanStyle(fontWeight = FontWeight.Medium)) { append(part) }
        else append(part)
        bold = !bold
    }
}
