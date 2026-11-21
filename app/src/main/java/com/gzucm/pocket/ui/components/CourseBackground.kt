package com.gzucm.pocket.ui.components

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.layout
import java.io.File

/**
 * 压在自定义壁纸上的文字该用深色还是浅色。
 *
 * 依据设计文档 §5.2：**按背景亮度切换**——亮背景用深色字，暗背景用浅色字。
 * 原来是「浅色主题一律垫白底」再改成「一律加深」，两种都不对：
 * 壁纸本身的明暗跟主题无关，必须看它自己的颜色。
 */
enum class BackdropInk { Unknown, Dark, Light }

/** 当前壁纸决定的前景墨色。没有壁纸 / 还没解码完时是 [BackdropInk.Unknown]，
 * 各处退回主题原来的 onSurfaceVariant，不改变默认观感。 */
val LocalBackdropInk = compositionLocalOf { BackdropInk.Unknown }

/**
 * 压在壁纸上的三档墨色（设计文档 §5.2）。
 *
 * | level | 深色字 | 浅色字 |
 * |---|---|---|
 * | 0 主 | #14181C 92% | 白 96% |
 * | 1 次 | #14181C 66% | 白 76% |
 * | 2 弱 | #14181C 46% | 白 56% |
 *
 * @return null 表示没有壁纸 / 还没判定，调用方用主题自己的颜色
 */
fun backdropInkColor(ink: BackdropInk, level: Int): androidx.compose.ui.graphics.Color? {
    if (ink == BackdropInk.Unknown) return null
    val dark = ink == BackdropInk.Dark
    val base = if (dark) androidx.compose.ui.graphics.Color(0xFF14181C)
    else androidx.compose.ui.graphics.Color.White
    val alpha = when (level) {
        0 -> if (dark) 0.92f else 0.96f
        1 -> if (dark) 0.66f else 0.76f
        else -> if (dark) 0.46f else 0.56f
    }
    return base.copy(alpha = alpha)
}

/**
 * 采样壁纸**上半部分**的平均亮度来决定墨色。
 *
 * 为什么只看上半部分：课表页那些直接压在背景上的文字（第 N 周、日期范围、
 * 星期、节次列）全在屏幕上部；上下渐变很常见的壁纸，用全图平均会被下半部
 * 带偏，出现「上半明明是亮的，字却用了浅色」。
 *
 * 阈值 0.5 对静态壁纸够用：文档里的 0.50/0.42 双阈值是为了防止玻璃背后
 * 实时内容在临界亮度来回闪，这里壁纸不变、没有抖动来源。
 */
@Composable
fun rememberBackdropInk(bitmap: androidx.compose.ui.graphics.ImageBitmap?): BackdropInk {
    if (bitmap == null) return BackdropInk.Unknown
    val avg = remember(bitmap) { averageBackdropLuminance(bitmap) }
    return if (avg >= 0.5f) BackdropInk.Dark else BackdropInk.Light
}

/**
 * 卡片内部文字的墨色：按**卡片实际底色**判定，不是壁纸原亮度。
 *
 * ## 为什么不能直接用 [rememberBackdropInk]
 *
 * 卡片底色不是壁纸本身，而是「磨砂壁纸 + [veil] 的 surface 雾面 + 半透明课程色」。
 * veil 把亮度往主题方向拉了 30%，用壁纸原亮度判会系统性偏高/偏低。
 *
 * 致辉 2026-11-19 复现的两个极端（格子不透明度 10% 时最明显）：
 *
 * | 场景 | 卡片实际底色 | 原字色 | 对比度 |
 * |---|---|---|---|
 * | 纯黑壁纸 + 浅色主题 | 磨砂黑 + 30% 白雾 ≈ `#4C4C4C` | `#14181C` | **2.1:1** |
 * | 纯白壁纸 + 深色主题 | 白磨砂 + 30% 的 `#171C23` ≈ `#B9B9B9` | `#E9EDF0` | **1.7:1** |
 *
 * WCAG 正文要求 4.5:1，两个都只有三分之一，教师/教室那两行还额外乘了透明度，
 * 最先消失。按实际底色切墨色之后，前者取白字、后者取黑字。
 *
 * ## 为什么用整图而不是顶部 45%
 *
 * [rememberBackdropInk] 只看顶部，是因为它服务的文字（第 N 周、星期条）都在屏幕上部。
 * 卡片是铺满整屏的，所以这里取全图平均。
 */
@Composable
fun rememberCardInk(
    bitmap: androidx.compose.ui.graphics.ImageBitmap?,
    veil: Float = 0.30f,
): BackdropInk {
    if (bitmap == null) return BackdropInk.Unknown
    val veilLum = MaterialTheme.colorScheme.surface.luminance()
    val avg = remember(bitmap) { averageBackdropLuminance(bitmap, topFraction = 1f) }
    // 卡片底色 = 壁纸 × (1-veil) + 雾面色 × veil（课程色 alpha 很低，忽略）
    val eff = avg * (1f - veil) + veilLum * veil
    return if (eff >= 0.5f) BackdropInk.Dark else BackdropInk.Light
}

/**
 * 卡片内一层文字的取色：优先按墨色，墨色未知（没壁纸 / 还没解码）时回退主题色。
 *
 * @param level 0 = 主要（课名），1 = 次要（教师），2 = 更次要（节次、教室、下课时间）
 * @param alphaMul 额外透明度（「已上过」的淡出、次要行的 0.75 等）
 */
internal fun cardTextColor(
    ink: BackdropInk,
    level: Int,
    fallback: androidx.compose.ui.graphics.Color,
    alphaMul: Float = 1f,
): androidx.compose.ui.graphics.Color {
    val c = backdropInkColor(ink, level) ?: fallback
    return if (alphaMul >= 1f) c
    else c.copy(alpha = (c.alpha * alphaMul).coerceIn(0f, 1f))
}

/**
 * 模糊小图顶部区域的平均亮度（0..1）。
 *
 * 抽样步长 4px：200px 的图本身已经很粗糙，再逐像素算没有意义；
 * 只在换壁纸时算一次，成本可以忽略。
 */
internal fun averageBackdropLuminance(
    bitmap: androidx.compose.ui.graphics.ImageBitmap,
    topFraction: Float = 0.45f,
): Float {
    if (bitmap.width <= 0 || bitmap.height <= 0) return 0.5f
    val pixels = bitmap.toPixelMap()
    val rows = (pixels.height * topFraction).toInt().coerceIn(1, pixels.height)
    var sum = 0.0
    var count = 0
    var y = 0
    while (y < rows) {
        var x = 0
        while (x < pixels.width) {
            sum += pixels[x, y].luminance()
            count++
            x += 4
        }
        y += 4
    }
    if (count == 0) return 0.5f
    return (sum / count).toFloat()
}

/**
 * 背景的两层绘制参数。抽成纯函数是为了单测能钉住两套口径：
 * 浅色满绘 + 遮罩，深色维持原来的图片半透明 + 固定 tint。
 */
internal data class BackdropPaint(val imageAlpha: Float, val tintAlpha: Float)

internal fun backdropPaint(alpha: Float, light: Boolean): BackdropPaint {
    val a = alpha.coerceIn(0.15f, 1f)
    return if (light) {
        BackdropPaint(imageAlpha = 1f, tintAlpha = (1f - a) * 0.6f)
    } else {
        BackdropPaint(imageAlpha = a, tintAlpha = 0.18f)
    }
}

/**
 * 课表自定义背景。
 *
 * @param alpha 背景浓度 0.15~1.0，由用户自己调。
 *   深色 = 图片不透明度（原样保留）；浅色 = 盖在满绘图片上的浅色遮罩强度，
 *   拉高更显色。两种口径下「拉高 = 图片更清楚」的直觉一致。
 * @param blurred 预先糊好的整张壁纸。传 null 表示没算或有没壁纸
 * @param useBlurred true 时直接画 [blurred] 而不是原图 ——
 *   就是「全屏背景模糊」开关。这里不做坐标换算：两个分支用的都是
 *   `ContentScale.Crop` 铺满同一个 Box，尺寸和裁切完全一致，
 *   切换开关时画面不会跳。
 */
@Composable
fun CourseBackground(
    path: String?,
    alpha: Float,
    modifier: Modifier = Modifier,
    blurred: androidx.compose.ui.graphics.ImageBitmap? = null,
    useBlurred: Boolean = false,
) {
    if (path.isNullOrBlank()) return
    val len = remember(path) { File(path).length() }
    if (len <= 0L) return

    /*
     * 原图解码放后台线程（2026-11-06）。原来在组合阶段主线程解码几 MB 的图，
     * 进课表页那一帧会顿一下。解出来之前先不画（底下是主题背景色，不会闪白）。
     *
     * 开了「全屏背景模糊」并且模糊图已就绪时，压根不需要原图 —— 跳过解码。
     */
    val needSharp = !(useBlurred && blurred != null)
    val sharp by androidx.compose.runtime.produceState<androidx.compose.ui.graphics.ImageBitmap?>(
        null, path, len, needSharp,
    ) {
        if (!needSharp) return@produceState
        value = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            runCatching {
                // 先读尺寸再按半分辨率解码，避免大图 OOM
                val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeFile(path, opts)
                val sample = when {
                    opts.outWidth > 2000 || opts.outHeight > 2000 -> 2
                    else -> 1
                }
                BitmapFactory.decodeFile(
                    path,
                    BitmapFactory.Options().apply { inSampleSize = sample },
                )?.asImageBitmap()
            }.getOrNull()
        }
    }

    // 开了模糊且糊好的图可用，就不必解原图了
    val bmp = if (useBlurred && blurred != null) blurred else sharp
    bmp ?: return

    /*
     * 浅色模式的「灰蒙」根因（致辉 2026-11-18 报）：
     *
     * 原来图片按 a 半透明绘制，剩下 (1-a) 透出的是页面底色 #F6F7F9；
     * 上面再铺一层 background@0.18。默认 a=0.45 时浅灰白占 63%，
     * 壁纸只剩 37% —— 看到的就是「图片上蒙了一层灰」。
     *
     * 新做法：浅色下图片始终**满绘**，滑块改为控制盖在上面的浅色遮罩。
     * 这样任何浓度下背后都只有那张图，不会出现「半透明图 + 灰白底」的双层感。
     * 遮罩乘 0.6：默认值 0.45 时遮罩只有 33%，壁纸占 67%，灰蒙大幅减轻；
     * 拉到 1.0 遮罩归零，图片完全显色。
     *
     * 深色模式**不动**：原来透出的是近黑底，看着是「变暗」不是「灰蒙」，
     * 而且课程块配色是按深色底调的，改了反而会破坏对比度。
     *
     * 判断深浅走实际配色的亮度而不是 ThemeState：截图测试直接给
     * GzucmTheme 传 darkTheme，并不改 ThemeState，用后者会和真实配色对不上。
     */
    val lightBackdrop = MaterialTheme.colorScheme.background.luminance() > 0.5f
    val paint = backdropPaint(alpha, lightBackdrop)

    Box(modifier.fillMaxSize()) {
        Image(
            bitmap = bmp,
            contentDescription = null,
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer { this.alpha = paint.imageAlpha },
            contentScale = ContentScale.Crop,
            filterQuality = FilterQuality.Medium,
        )
        // 浅色：这层就是滑块的遮罩；深色：仍是原来那层压高光的底色
        if (paint.tintAlpha > 0f) {
            Box(
                Modifier
                    .fillMaxSize()
                    .background(
                        MaterialTheme.colorScheme.background.copy(alpha = paint.tintAlpha),
                    ),
            )
        }
    }
}
