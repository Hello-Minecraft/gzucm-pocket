package com.gzucm.pocket.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * 云母面板（致辉 2026-11-19 定，同日收敛到原生阴影）。
 *
 * 「无描边 + 阴影」的立体面板，替代原来「纯色贴片 + 一圈 0.6~1dp 描边」的做法。
 *
 * ## 阴影只做一件事：把系统的 elevation 原样用上
 *
 * 不自定义 ambient / spot 颜色，不分档，不加"接触影"层。
 * 系统的两束阴影（ambient 均匀扩散 + spot 点光源投影）就是 Material 的纸片物理模型，
 * 光源挂在**窗口**上而不是每张卡上 —— 所以卡片越靠屏幕下方，影越长越深，
 * 滚动时还会跟着变。致辉 2026-11-19 认定这是「好设计，不用修」。
 *
 * 唯一按深浅分开的是数值：深色 3dp、浅色 7dp。
 * 深色背景已是 #0A0C0F，elevation 给高了只会压出一圈脏光晕，那不是立体感。
 *
 * ## 不要按卡片高度/用途分档
 *
 * 试过给"高卡"单独降一档（MicaLevel.Tall），撤掉了：高度只决定底边到光源的距离，
 * 真正的主变量是卡片在光源坐标系里的位置，那是滚动中动态变化的。
 * 静态分档治不了，只会让高卡永远比矮卡轻一档。
 *
 * ## 阴影形状用 RoundedCornerShape，本体用 G3
 *
 * minSdk 是 26，自定义 Path 的 elevation 阴影在 API 26/27 不渲染
 * （系统只给 rect / roundRect / oval 的 outline 算阴影）。
 * 折中：阴影层用同半径的 [RoundedCornerShape]，本体用 [G3CornerShape] 画底。
 * 两者在同半径下差不到一像素，7dp 的模糊里看不出来，但全版本都有影。
 *
 * ## 层级
 *
 * 每个面板只有两层：`shadow`（一个 graphicsLayer）+ `background`（一次绘制）。
 * 不再单独 `clip` —— [androidx.compose.foundation.background] 带 shape 的重载
 * 已经按 G3 轮廓画底，面板内容都在这圈内缩的 padding 里，没有溢出可裁。
 * 深色多一层 1dp 顶边高光（drawBehind，画一条线，可忽略）。
 */
/**
 * 面板的阴影高度。
 *
 * 浅色 7dp / 深色 3dp。深色给得低：背景 #0A0C0F 上再叠黑色 elevation，
 * 不会更立体，只会把卡片四周压出一圈脏光晕。深色的层次靠底色提亮
 * （[MicaDarkFill]）+ 顶边高光。
 *
 * 抽成函数是因为 [micaPanel] / [micaShadow] 各算一次，复制两份迟早改漏。
 */
internal fun micaElevation(dark: Boolean): Dp = if (dark) 3.dp else 7.dp

/** 深色面板底色：比 surface 亮一档，Mica 的明度分层。 */
internal val MicaDarkFill = Color(0xFF171C23)

/** 浅色直接用主题 surface（纯白），深色用提亮后的 [MicaDarkFill]。 */
internal fun micaPanelFill(surface: Color, dark: Boolean): Color =
    if (dark) MicaDarkFill else surface

/** 当前主题是否算深色。按实际配色的亮度判，不读 ThemeState（截图测试直接传 darkTheme）。 */
@Composable
fun isDarkSurface(): Boolean =
    MaterialTheme.colorScheme.background.luminance() < 0.5f

/** 面板底色。渐变卡不要用它，直接把 brush 传给 [micaPanel]。 */
@Composable
fun micaPanelColor(): Color {
    val scheme = MaterialTheme.colorScheme
    return micaPanelFill(scheme.surface, scheme.background.luminance() < 0.5f)
}

/**
 * 深色 Sheet / 侧栏的容器底色：比页面背景亮，但**比面板暗一档**。
 *
 * ## 为什么要单独一个值（2026-11-19 致辉报「设置页底色跟面板完全一样」）
 *
 * 原来 Sheet 容器和它里面的卡片都取 [MicaDarkFill]，同一个值。
 * 浅色下还能靠 7dp 阴影分开；深色下阴影是黑叠黑、只剩 3dp，等于没有，
 * 整个设置页塌成一块平板，分组感完全消失。
 *
 * 三层的明度关系（深色）：
 *
 * ```
 * 页面 background  #0A0C0F   lum 0.0036
 * └ Sheet          #101318   lum 0.0064   ← 这个常量
 *   └ 卡片         #171C23   lum 0.0113   ← [MicaDarkFill]，全局不变
 * ```
 *
 * 两段差值（0.0028 / 0.0049）比用 surface 更均匀 —— 见下面"为什么不直接用 surface"。
 *
 * ## 为什么不直接用主题的 surface(#12151A)
 *
 * 它跟卡片只差 5~9 个色阶（lum 差 0.0039），仍然偏细；
 * 而 [MicaSheetFill] 拉开到 7~11（0.0049）。差一点点，但深色下就是"看得见/看不见"的分界。
 *
 * ## 为什么不反过来把卡片提亮
 *
 * 提亮卡片会让**设置页的卡片比今日页/成绩页/电费页的卡片亮一档**。
 * 那些页面是 background(#0A0C0F) 打底、卡片 #171C23，对比本来就够；
 * 为 Sheet 单独提亮等于同一种材质出现两个亮度，跨页面不一致。
 * 改 Sheet 只影响 Sheet 和右侧 Pane 两处，卡片色全局统一。
 *
 * 浅色不做这个分层：白 Sheet + 白卡片，7dp 阴影已经画出边界，再压暗反而发灰。
 */
internal val MicaSheetFill = Color(0xFF101318)

/** 浅色保持主题 surface；深色降到 [MicaSheetFill]。 */
internal fun micaSheetFill(surface: Color, dark: Boolean): Color =
    if (dark) MicaSheetFill else surface

/** Sheet / 侧栏容器底色。和 [micaPanelColor] 配合出三层：页面 < Sheet < 卡片。 */
@Composable
fun micaSheetColor(): Color {
    val scheme = MaterialTheme.colorScheme
    return micaSheetFill(scheme.surface, scheme.background.luminance() < 0.5f)
}

/**
 * 只加阴影、不动底色的版本。
 *
 * 给「自己已经画了磨砂底 + 半透明课程色」的卡用（日视图课程卡）：
 * 它们的底色由壁纸和课程色共同决定，套 [micaPanel] 会被刷成不透明 surface。
 *
 * 调用方仍要自己把内容裁到 G3 圆角（
 * `micaShadow(corner) → clip(G3CornerShape(corner)) → 自己的底色`）。
 */
@Composable
fun Modifier.micaShadow(corner: Dp): Modifier {
    val dark = MaterialTheme.colorScheme.background.luminance() < 0.5f
    val shadowShape = remember(corner) { RoundedCornerShape(corner) }
    // 不传 ambientColor / spotColor：系统默认就是这套材质该有的样子
    return this.shadow(
        elevation = micaElevation(dark),
        shape = shadowShape,
        // 不裁内容：本体用的是 G3 圆角，裁成圆角矩形就白做了
        clip = false,
    )
}

/**
 * 把容器变成云母面板。
 *
 * @param corner 圆角。必须与内容里其它 G3 圆角一致，否则阴影和本体对不上。
 * @param color 覆盖底色；默认走 [micaPanelColor]。
 * @param brush 品牌色渐变（余额卡、电量卡）。给了 brush 就忽略 color。
 *   **必须是"主色 → 面板色"这种两端同底子的渐变**，
 *   不要铺"亮 → 暗"的斜向渐变：3% 的色差在渐变终点会积成一条灰带，
 *   看着像卡片底下多了一条边（2026-11-19 踩过，见 PITFALLS 第十九节）。
 */
@Composable
fun Modifier.micaPanel(
    corner: Dp,
    color: Color = Color.Unspecified,
    brush: Brush? = null,
): Modifier {
    val scheme = MaterialTheme.colorScheme
    val dark = scheme.background.luminance() < 0.5f
    val fill = if (color == Color.Unspecified) {
        micaPanelFill(scheme.surface, dark)
    } else {
        color
    }
    val g3 = remember(corner) { G3CornerShape(corner) }
    val shadowShape = remember(corner) { RoundedCornerShape(corner) }

    return this
        .shadow(
            elevation = micaElevation(dark),
            shape = shadowShape,
            clip = false,
        )
        .then(
            if (brush != null) {
                Modifier.background(brush, g3)
            } else {
                Modifier.background(fill, g3)
            },
        )
        .then(if (dark) Modifier.micaTopEdge() else Modifier)
}

/**
 * 顶边内高光。深色下这是"立体感"的主要来源，浅色下白卡上白线看不见，所以只在深色挂。
 *
 * 画在 background 之后、内容之前（drawBehind 的绘制层级低于子节点），不会盖住文字。
 * 只画一条线，不额外开图层。
 */
private fun Modifier.micaTopEdge(): Modifier = drawBehind {
    val inset = 14.dp.toPx()
    if (size.width <= inset * 2) return@drawBehind
    drawLine(
        color = Color.White.copy(alpha = 0.075f),
        start = Offset(inset, 0.6.dp.toPx()),
        end = Offset(size.width - inset, 0.6.dp.toPx()),
        strokeWidth = 1.dp.toPx(),
    )
}

/**
 * 侧栏投影：给直角面板的左边缘补一圈柔和暗边。
 *
 * [micaPanel] 的阴影是四周均匀的，用在整高侧栏上会变成"上下也发黑"，
 * 所以侧栏单独用这条。一次 drawRect，不开图层。
 */
fun Modifier.micaSideShadow(width: Dp = 18.dp, alpha: Float = 0.22f): Modifier = drawBehind {
    val w = width.toPx()
    drawRect(
        brush = Brush.horizontalGradient(
            colors = listOf(Color.Black.copy(alpha = alpha), Color.Transparent),
            startX = -w,
            endX = 0f,
        ),
        topLeft = Offset(-w, 0f),
        size = androidx.compose.ui.geometry.Size(w, size.height),
    )
}
