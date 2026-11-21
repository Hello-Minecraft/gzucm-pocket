package com.gzucm.pocket.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties

/** 卡片最大宽度。再宽一行字就长得读不动了，与 Material 的 dialog 上限一致。 */
private val DialogMaxWidth = 340.dp

/** 左右各留这么多，屏宽减两倍就是卡片宽度 */
private val DialogSideMargin = 24.dp

/** 正文最高多少；再高就滚，不把标题和按钮挤出屏幕 */
private val DialogBodyMaxHeight = 360.dp

/**
 * 居中弹窗基座。
 *
 * ## 为什么不用 ModalBottomSheet
 *
 * 致辉明确否掉了「从下往上滑」那种（2026-11-22）。班级日程的三种提示都是
 * **打断式**的一问一答，底部面板会让人以为「又要我填什么」，
 * 而且这三种提示往往在打开 App 的瞬间就弹，底部面板会盖住首页内容。
 *
 * ## 宽度自己算
 *
 * [DialogProperties.usePlatformDefaultWidth] 关掉之后，窗口铺满屏幕，
 * 卡片宽度由这里的 [BoxWithConstraints] 决定：
 * `min(340.dp, 屏宽 - 48.dp)`。不用 `LocalConfiguration.screenWidthDp` ——
 * 那个是 Activity 的窗口宽度，分屏/横屏下跟弹窗实际可用宽度不一定一致。
 *
 * ## 布局
 *
 * 标题 → 正文（可滚，最高 [DialogBodyMaxHeight]）→ 底部按钮（竖排、整宽）。
 * 按钮竖排是刻意的：三种弹窗的按钮都只有一个或两个，
 * 竖排比横排更好点，也不会出现「两个长文案挤在一行被截断」。
 *
 * @param title 标题，一行，不会自动折行
 * @param onDismiss 关闭（返回键 / 点遮罩 / 调 [DialogSecondaryButton]）
 * @param subtitle 标题下面的一句补充，可不给
 * @param dismissOnOutsideClick 点遮罩和按返回是否关闭。强制二选一的场景传 false
 * @param buttons 底部按钮区。用 [DialogPrimaryButton] / [DialogSecondaryButton]，从上往下排
 * @param body 正文区，超出高度自己滚
 */
@Composable
fun CenterDialog(
    title: String,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    dismissOnOutsideClick: Boolean = true,
    buttons: @Composable ColumnScope.() -> Unit,
    body: @Composable ColumnScope.() -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            dismissOnBackPress = dismissOnOutsideClick,
            dismissOnClickOutside = dismissOnOutsideClick,
            usePlatformDefaultWidth = false,
        ),
    ) {
        BoxWithConstraints(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center,
        ) {
            val cardWidth = minOf(
                DialogMaxWidth,
                (maxWidth - DialogSideMargin * 2).coerceAtLeast(240.dp),
            )
            Surface(
                modifier = modifier.width(cardWidth),
                // 24dp 是致辉定的：比日程卡的 14~16 大一圈，像「浮在上面的一层纸」
                shape = G3CornerShape(24.dp),
                // 底色跟面板同一个函数取：深色下比 surface 亮一档，浅色直接用 surface
                color = micaPanelColor(),
                contentColor = scheme.onSurface,
                // 比卡片高一档（卡片是深 3 / 浅 7），弹窗压在遮罩上，需要更明显的浮起
                shadowElevation = if (isDarkSurface()) 8.dp else 16.dp,
            ) {
                Column(Modifier.padding(horizontal = 22.dp, vertical = 20.dp)) {
                    Text(
                        title,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Medium,
                        color = scheme.onSurface,
                    )
                    if (!subtitle.isNullOrBlank()) {
                        Spacer(Modifier.height(4.dp))
                        Text(
                            subtitle,
                            fontSize = 12.sp,
                            lineHeight = 17.sp,
                            color = scheme.onSurfaceVariant,
                        )
                    }
                    Spacer(Modifier.height(14.dp))
                    Column(
                        Modifier
                            .fillMaxWidth()
                            .heightIn(max = DialogBodyMaxHeight)
                            .verticalScroll(rememberScrollState()),
                        content = body,
                    )
                    Spacer(Modifier.height(18.dp))
                    Column(
                        Modifier.fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                        content = buttons,
                    )
                }
            }
        }
    }
}

/** 弹窗主按钮：整宽、12dp 圆角，一屏只有一个 */
@Composable
fun DialogPrimaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Button(
        onClick = onClick,
        modifier = modifier
            .fillMaxWidth()
            .height(42.dp),
        shape = G3CornerShape(12.dp),
    ) {
        Text(text, fontSize = 13.5.sp, fontWeight = FontWeight.Medium)
    }
}

/** 弹窗次按钮：「稍后」「知道了」。整宽、无填充，视觉上比主按钮轻一档 */
@Composable
fun DialogSecondaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    TextButton(
        onClick = onClick,
        modifier = modifier
            .fillMaxWidth()
            .height(40.dp),
        shape = G3CornerShape(12.dp),
    ) {
        Text(
            text,
            fontSize = 13.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
