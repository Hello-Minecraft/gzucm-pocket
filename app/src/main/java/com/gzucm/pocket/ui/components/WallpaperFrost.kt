package com.gzucm.pocket.ui.components

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import java.io.File

/**
 * 课表壁纸在窗口中的位置。
 *
 * 为什么要有它：格子要「采样自己正后方那块壁纸」来画磨砂，
 * 就必须知道壁纸画在哪、格子又在哪。两者是兄弟节点，
 * 坐标只能靠 `boundsInRoot()` 在运行时对齐。
 *
 * ⚠️ **必须是 `compositionLocalOf`，不能是 `staticCompositionLocalOf`。**
 * 静态版不登记读取方，值变了也不会让格子重组 —— 而这里恰恰是
 * 「第一帧 null → 布局完成后才有值」，用静态版的话格子永远读到 null，
 * 磨砂整条路径静默失效（2026-11-03 踩过，表现为「完全看不出模糊」）。
 */
val LocalWallpaperBounds = compositionLocalOf<Rect?> { null }

/** 预先糊好的壁纸位图。全页只算一次，格子只做取样 */
val LocalBlurredWallpaper = compositionLocalOf<ImageBitmap?> { null }

/**
 * 格子底色不透明度（0.10~1.00）。
 *
 * 放在 CompositionLocal 里而不是一层层传参：周视图要经
 * WeekGrid → WeekdayColumn → CourseBlock 三层，日视图又是另一条链，
 * 中间全是布局参数，多插一个无关的 Float 只会让签名更乱。
 *
 * 同样必须是动态版：拖动滑块时要让格子立刻重画。
 */
val LocalCellOpacity = compositionLocalOf { 0.40f }

/**
 * 把壁纸解码成一张「小图 + 已模糊」的位图。
 *
 * 两条关键取舍：
 *
 * 1. **先降采样再模糊**。原图 4000px 宽，直接跑模糊要几秒。
 *    缩到最长边 200px 之后再糊，一是快，二是天然更柔和 ——
 *    小图放大时本身就会被插值再抹一遍。
 * 2. **不用 RenderEffect**。它要 API 31+，26~30 直接没效果。
 *    手写三次盒式模糊 ≈ 高斯，全版本一致，也能单测。
 *
 * ## 半径怎么换算成屏幕效果
 *
 * 模糊是在**小图**上做的，看到的是放大后的结果，所以要乘放大倍数：
 *
 * ```
 * 三次盒式 σ(小图) = radius × √(passes × (2r+1)²−1 / 12) ÷ ...  ≈ radius × 1.08
 * 屏幕 σ ≈ σ(小图) × 屏幕宽 / 小图宽
 * ```
 *
 * 按最长边 200px、屏幕 1080px 算，放大倍数约 5.4：
 *
 * | radius | 屏幕等效 σ | 观感 |
 * |---|---|---|
 * | 6 | ≈35px | 糊成一团，细节全丢（2026-11-03 初版，致辉说太重） |
 * | 3 | ≈17px | 还能看出色块轮廓，是「毛玻璃」不是「马赛克」 |
 *
 * @param maxDim 降采样后最长边（px）
 * @param radius 小图上的模糊半径（px），改它等于改最终的模糊强度
 */
@Composable
fun rememberBlurredWallpaper(
    path: String?,
    maxDim: Int = 200,
    radius: Int = 3,
): ImageBitmap? {
    if (path.isNullOrBlank()) return null
    val file = remember(path) { File(path) }
    if (!file.exists()) return null

    return remember(path, file.length(), maxDim, radius) {
        runCatching { blurFile(path, maxDim, radius)?.asImageBitmap() }.getOrNull()
    }
}

private fun blurFile(path: String, maxDim: Int, radius: Int): Bitmap? {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(path, bounds)
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

    var sample = 1
    while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= maxDim) sample *= 2

    val src = BitmapFactory.decodeFile(
        path,
        BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = Bitmap.Config.ARGB_8888
        },
    ) ?: return null
    return boxBlur(src, radius)
}

/**
 * 三次盒式模糊（近似高斯）。
 *
 * 盒式模糊可分离：先横着扫一遍、再竖着扫一遍，复杂度从 O(r²) 降到 O(r)，
 * 半径取大也不怕。扫三遍是因为单遍盒式会留下方形边界，三遍叠起来才像高斯。
 */
internal fun boxBlur(src: Bitmap, radius: Int, passes: Int = 3): Bitmap {
    val w = src.width
    val h = src.height
    if (w <= 0 || h <= 0) return src
    val r = radius.coerceAtLeast(1)

    var pixels = IntArray(w * h).also { src.getPixels(it, 0, w, 0, 0, w, h) }
    repeat(passes) {
        pixels = blurPass(pixels, w, h, r, horizontal = true)
        pixels = blurPass(pixels, w, h, r, horizontal = false)
    }

    val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
    out.setPixels(pixels, 0, w, 0, 0, w, h)
    return out
}

/** 单遍滑动窗口模糊。边界外按边缘像素延拓，避免四周发黑 */
private fun blurPass(src: IntArray, w: Int, h: Int, r: Int, horizontal: Boolean): IntArray {
    val out = IntArray(src.size)
    val len = if (horizontal) w else h
    val lines = if (horizontal) h else w
    val window = r * 2 + 1

    for (line in 0 until lines) {
        var a = 0
        var rr = 0
        var gg = 0
        var bb = 0

        for (i in -r..r) {
            val idx = i.coerceIn(0, len - 1)
            val p = src[if (horizontal) line * w + idx else idx * w + line]
            a += p ushr 24 and 0xFF
            rr += p ushr 16 and 0xFF
            gg += p ushr 8 and 0xFF
            bb += p and 0xFF
        }

        for (i in 0 until len) {
            val o = if (horizontal) line * w + i else i * w + line
            out[o] = (a / window shl 24) or
                (rr / window shl 16) or
                (gg / window shl 8) or
                (bb / window)

            val addIdx = (i + r + 1).coerceIn(0, len - 1)
            val subIdx = (i - r).coerceIn(0, len - 1)
            val add = src[if (horizontal) line * w + addIdx else addIdx * w + line]
            val sub = src[if (horizontal) line * w + subIdx else subIdx * w + line]

            a += (add ushr 24 and 0xFF) - (sub ushr 24 and 0xFF)
            rr += (add ushr 16 and 0xFF) - (sub ushr 16 and 0xFF)
            gg += (add ushr 8 and 0xFF) - (sub ushr 8 and 0xFF)
            bb += (add and 0xFF) - (sub and 0xFF)
        }
    }
    return out
}

/**
 * 格子磨砂是否开启。
 *
 * 关掉时格子只剩课程色；开了「全屏背景模糊」也会被强制关掉
 * （背景已经糊了，再逐格采样既看不出来又白花性能）。
 */
val LocalCellFrostEnabled = compositionLocalOf { true }

/**
 * 给一个元素铺「自己正后方那块壁纸的磨砂版」。
 *
 * 实现要点：**从预模糊位图里按坐标抠一块出来**，不是对屏幕做实时模糊。
 * 每格只是一次 drawImage，几十个格子也只是几十次贴图；
 * 换成逐格 backdrop blur（haze）要几十次离屏渲染，中端机必掉帧。
 *
 * 缩放按 `ContentScale.Crop` 的规则算（取较大的那个比例、居中裁切），
 * 保证磨砂块和底下的壁纸**逐像素对得上**，不会错位。
 *
 * ## ⚠️ 必须画满不透明，绝不能乘一个透明度
 *
 * 这里踩过一次坑（2026-11-03，致辉连着报了两遍「看不出模糊」）：
 * 最早把磨砂层按用户设的 40% 画，结果是
 *
 * ```
 * 40% 糊图  +  60% 下面透上来的清晰壁纸
 * ```
 *
 * 眼睛看到的主要还是清晰那份，糊的部分只是给它加了层雾。
 * 壁纸是大面积纯色时还能看出双影，换成近黑的壁纸配一个月亮，
 * 糊出来的月亮和清晰月亮几乎重合 —— 看上去就是「月面纹理清清楚楚」。
 *
 * 正确做法（致辉提的）：磨砂层**画满**，负责「格子后面是什么」；
 * 用户调的浓度只作用在**叠在上面的那层纯色**上（见调用方的 background）。
 * 这样格子背后永远只有糊的那一份，任何浓度都看得出磨砂。
 */
@Composable
fun Modifier.frostedBackdrop(
    bitmap: ImageBitmap?,
    alpha: Float = 1f,
    /**
     * 雾面浓度。固定值，**不要跟用户滑块联动**。
     *
     * 为什么需要它：磨砂层画满之后，格子底色等于「糊掉的壁纸」，
     * 亮度完全被壁纸决定 —— 壁纸那块是亮黄，格子就是亮黄，
     * 而文字颜色是按课程色算的，碰上浅色课名会直接糊在底上。
     *
     * 压一层主题 surface 色，把亮度拉回主题的可控范围：
     * 深色主题压暗、浅色主题提亮，字色逻辑才成立。
     * 它盖在**已经糊好的图**上，不会让背景重新变清晰。
     */
    veil: Float = 0.30f,
    veilColor: androidx.compose.ui.graphics.Color =
        androidx.compose.material3.MaterialTheme.colorScheme.surface,
): Modifier {
    if (bitmap == null) return this
    // 开关关掉就什么都不画，调用方照常叠课程色
    if (!LocalCellFrostEnabled.current) return this
    val wall = LocalWallpaperBounds.current ?: return this
    var self by remember { mutableStateOf<Rect?>(null) }

    return this
        .onGloballyPositioned { self = it.boundsInRoot() }
        .drawWithContent {
            val r = self
            if (r != null) {
                drawFrostedRegion(bitmap, wall, r, alpha)
                if (veil > 0f) {
                    drawRect(color = veilColor, alpha = veil.coerceIn(0f, 1f))
                }
            }
            drawContent()
        }
}

private fun DrawScope.drawFrostedRegion(
    bmp: ImageBitmap,
    wall: Rect,
    self: Rect,
    alpha: Float,
) {
    if (wall.width <= 0f || wall.height <= 0f) return

    val r = frostSrcRect(
        bmpW = bmp.width,
        bmpH = bmp.height,
        wallW = wall.width,
        wallH = wall.height,
        relX = self.left - wall.left,
        relY = self.top - wall.top,
        cellW = self.width,
        cellH = self.height,
    )

    drawImage(
        image = bmp,
        srcOffset = IntOffset(r.left, r.top),
        srcSize = IntSize(r.width, r.height),
        dstOffset = IntOffset.Zero,
        dstSize = IntSize(size.width.toInt().coerceAtLeast(1), size.height.toInt().coerceAtLeast(1)),
        alpha = alpha.coerceIn(0f, 1f),
        // 源图很小、放大倍数大，Low 就够（本身是糊的，再精细也看不出来）
        filterQuality = FilterQuality.Low,
    )
}

/**
 * 算出「格子在模糊位图上对应的那块区域」。
 *
 * 纯函数，抽出来是为了能单测 —— 这段是 ContentScale.Crop 的逆运算，
 * 写错的表现很隐蔽：磨砂块整体偏移几像素，肉眼只觉得「有点脏」，
 * 但说不出哪里不对，很难靠截图发现。
 *
 * Crop 的规则：等比放大到**盖满**壁纸区（取较大的比例），然后居中裁掉多余部分。
 * 已知格子相对壁纸左上角的偏移 (relX, relY)，反推它在原图上的坐标。
 *
 * @param bmpW/bmpH 模糊位图尺寸（px）
 * @param wallW/wallH 壁纸在屏幕上占的区域（px）
 * @param relX/relY 格子左上角相对壁纸左上角的偏移（px）
 * @param cellW/cellH 格子尺寸（px）
 */
internal fun frostSrcRect(
    bmpW: Int,
    bmpH: Int,
    wallW: Float,
    wallH: Float,
    relX: Float,
    relY: Float,
    cellW: Float,
    cellH: Float,
): IntRect {
    if (bmpW <= 0 || bmpH <= 0) return IntRect(0, 0, 1, 1)

    val scale = maxOf(wallW / bmpW, wallH / bmpH).coerceAtLeast(0.0001f)
    val drawnW = bmpW * scale
    val drawnH = bmpH * scale
    val ox = (wallW - drawnW) / 2f
    val oy = (wallH - drawnH) / 2f

    val x = ((relX - ox) / scale).toInt().coerceIn(0, bmpW - 1)
    val y = ((relY - oy) / scale).toInt().coerceIn(0, bmpH - 1)
    val w = (cellW / scale).toInt().coerceIn(1, (bmpW - x).coerceAtLeast(1))
    val h = (cellH / scale).toInt().coerceIn(1, (bmpH - y).coerceAtLeast(1))
    return IntRect(x, y, w, h)
}

/** 极简矩形，避免为主函数引入 Compose 的 Rect 依赖 */
internal data class IntRect(val left: Int, val top: Int, val width: Int, val height: Int)
