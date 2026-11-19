package com.gzucm.pocket.ui.components

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.layout.findRootCoordinates
import androidx.compose.ui.node.invalidateDraw
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
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
 * 当前课表页有没有自定义壁纸。
 *
 * 没有壁纸时 [CourseBackground] 在 `bmp ?: return` 那行直接返回，不画任何东西，
 * 格子自然也没有磨砂层 —— 卡片只剩半透明的课程色，这时候得靠**不透明衬底**
 * 把它变成实心云母面板，否则 elevation 阴影会从卡底下透出来。
 *
 * ## 为什么用显式标记，而不是判断「壁纸 bounds 非空」
 *
 * [LocalWallpaperBounds] 有两个不可靠的时候：
 *   · 换/删壁纸后 `onGloballyPositioned` 不再回调，旧值会留着；
 *   · 布局完成前它是 null，但壁纸其实是有的是。
 * 两头都不准。这里只关心「用户有没有设壁纸」这一个事实，就用一个专门的值。
 */
val LocalHasCustomBackdrop = compositionLocalOf { false }

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
    /*
     * 解码 + 模糊放后台线程（2026-11-06）。
     *
     * 原来是 `remember { blurFile(...) }`，在**组合阶段、主线程**同步执行：
     * 进课表页、换壁纸那一帧要等解码完，能卡一下。
     * 现在先返回 null（格子退回纯课程色，不影响布局），算好了再出现磨砂。
     *
     * key 里带文件长度：换图时文件名本来就会变（见 ScheduleScreen 选图），
     * 长度只是双保险。
     */
    val len = if (path.isNullOrBlank()) -1L else File(path).length()
    val result by produceState<ImageBitmap?>(null, path, len, maxDim, radius) {
        value = if (path.isNullOrBlank() || len <= 0L) {
            null
        } else {
            withContext(Dispatchers.IO) {
                runCatching { blurFile(path, maxDim, radius)?.asImageBitmap() }.getOrNull()
            }
        }
    }
    return result
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

    /*
     * ## 为什么不再用 onGloballyPositioned + mutableStateOf（2026-11-06 修「磨砂跟不上」）
     *
     * 原来：布局后回调把位置写进 state → 绘制时读 state。时序是
     *
     *   第 N 帧：布局（格子已滚到新位置）→ 绘制（读到的还是第 N-1 帧的位置）→ 回调写入新值
     *   第 N+1 帧：因为 state 变了才重画，这时才对齐
     *
     * 所以磨砂块**永远比格子晚一帧**，滑得越快错位越大；而且滚动时每个格子每帧都写一次
     * state，几十个格子一起触发重组，中端机上就是卡顿。
     *
     * 现在改成 Modifier.Node（见 [FrostedBackdropNode]）：放置时记坐标、位置变了只失效
     * **这一格的绘制**，绘制时当场取 boundsInRoot。不经过 state，不触发重组。
     */
    return this then FrostedBackdropElement(bitmap, wall, alpha, veil, veilColor)
}

/**
 * 磨砂的 Modifier.Node 实现。
 *
 * 为什么不能只「在绘制时取坐标」：verticalScroll / HorizontalPager 滚动时，
 * 子节点是在**放置层**挪位置（带 layer 的 place），RenderNode 直接平移，
 * **不保证重新执行 draw**。不重画的话贴的还是上一帧抠下来的那块，照样错位。
 *
 * 所以要在「位置真的变了」的时候主动 [invalidateDraw]：
 *   · [onPlaced] / [onRemeasured] 拿到新坐标（每次放置都会回调，包括滚动）
 *   · 和上次画的位置比，变了才失效 —— 静止时零开销
 *   · invalidateDraw 只重跑这一格的绘制，**不触发重组**，比原来写 mutableStateOf 便宜得多
 */
private data class FrostedBackdropElement(
    val bitmap: ImageBitmap,
    val wall: Rect,
    val alpha: Float,
    val veil: Float,
    val veilColor: androidx.compose.ui.graphics.Color,
) : androidx.compose.ui.node.ModifierNodeElement<FrostedBackdropNode>() {

    override fun create() = FrostedBackdropNode(bitmap, wall, alpha, veil, veilColor)

    override fun update(node: FrostedBackdropNode) {
        node.bitmap = bitmap
        node.wall = wall
        node.alpha = alpha
        node.veil = veil
        node.veilColor = veilColor
        node.lastDrawn = null
        node.invalidateDraw()
    }

    override fun androidx.compose.ui.platform.InspectorInfo.inspectableProperties() {
        name = "frostedBackdrop"
    }
}

private class FrostedBackdropNode(
    var bitmap: ImageBitmap,
    var wall: Rect,
    var alpha: Float,
    var veil: Float,
    var veilColor: androidx.compose.ui.graphics.Color,
) : androidx.compose.ui.Modifier.Node(),
    androidx.compose.ui.node.DrawModifierNode,
    androidx.compose.ui.node.LayoutAwareModifierNode {

    private var coords: androidx.compose.ui.layout.LayoutCoordinates? = null

    /** 上次实际用来抠图的位置；和当前位置不同才需要重画 */
    var lastDrawn: Rect? = null

    /*
     * 缓存的 BitmapShader：位图不换就一直复用，每帧只更新 localMatrix。
     *
     * 为什么用 shader 而不是 drawImage(抠一块)——2026-11-19 第二次修「横滑拉伸」：
     *
     * drawImage 要自己算「源矩形」和「目标矩形」，两者宽高比稍有偏差（取整、
     * 或者某一边被边界裁了）就是拉伸。上一版按交集把两边同比例缩，纯函数测下来
     * 是对的，真机上仍然看得见 —— 说明还有别的东西在破坏这个比例。
     *
     * BitmapShader 是**一次固定比例的仿射变换**：矩阵里只有 scale 和 translate，
     * 没有任何"目标尺寸"参与，物理上不可能被拉长。越界部分由 CLAMP 用边缘像素延拓，
     * 也不会出现"半张卡没磨砂"。
     */
    private var shader: android.graphics.BitmapShader? = null
    private var shaderOwnedBy: ImageBitmap? = null
    private var brush: androidx.compose.ui.graphics.ShaderBrush? = null
    private val matrix = android.graphics.Matrix()

    override fun onPlaced(coordinates: androidx.compose.ui.layout.LayoutCoordinates) {
        coords = coordinates
        val now = runCatching { coordinates.unclippedBoundsInRoot() }.getOrNull() ?: return
        if (now != lastDrawn) invalidateDraw()
    }

    override fun androidx.compose.ui.graphics.drawscope.ContentDrawScope.draw() {
        val c = coords
        if (c != null && c.isAttached) {
            val r = c.unclippedBoundsInRoot()
            lastDrawn = r
            drawFrostedWallpaper(bitmap, wall, r, alpha)
            if (veil > 0f) {
                drawRect(color = veilColor, alpha = veil.coerceIn(0f, 1f))
            }
        }
        drawContent()
    }

    /**
     * 把「格子正后方那块壁纸」按固定比例贴到格子上。
     *
     * 矩阵只做两件事：放大 [scale] 倍、平移到位图原点相对格子左上角的位置。
     * 没有任何目标尺寸参与，所以不管格子宽高多少、有没有越界，比例都不会变。
     */
    private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawFrostedWallpaper(
        bmp: ImageBitmap,
        wall: Rect,
        self: Rect,
        alpha: Float,
    ) {
        if (wall.width <= 0f || wall.height <= 0f) return
        if (bmp.width <= 0 || bmp.height <= 0) return

        val sh = obtainShader(bmp) ?: return
        val br = obtainBrush(sh) ?: return

        val tf = frostTransform(
            bmpW = bmp.width,
            bmpH = bmp.height,
            wallW = wall.width,
            wallH = wall.height,
            relX = self.left - wall.left,
            relY = self.top - wall.top,
        ) ?: return

        // 位图像素 → 格子局部坐标：先缩放，再平移到「位图原点相对格子左上角」的位置
        matrix.setScale(tf.scale, tf.scale)
        matrix.postTranslate(tf.dx, tf.dy)
        sh.setLocalMatrix(matrix)

        drawRect(brush = br, alpha = alpha.coerceIn(0f, 1f))
    }

    private fun obtainShader(bmp: ImageBitmap): android.graphics.BitmapShader? {
        val cur = shader
        if (cur != null && shaderOwnedBy === bmp) return cur
        val s = runCatching {
            android.graphics.BitmapShader(
                bmp.asAndroidBitmap(),
                android.graphics.Shader.TileMode.CLAMP,
                android.graphics.Shader.TileMode.CLAMP,
            )
        }.getOrNull() ?: return null
        shader = s
        shaderOwnedBy = bmp
        brush = null // 旧 brush 绑的是旧 shader
        return s
    }

    private fun obtainBrush(sh: android.graphics.BitmapShader): androidx.compose.ui.graphics.ShaderBrush? {
        val cur = brush
        if (cur != null) return cur
        val b = androidx.compose.ui.graphics.ShaderBrush(sh)
        brush = b
        return b
    }
}

/**
 * 磨砂的「位图像素 → 格子局部坐标」变换。
 *
 * 只有 scale 和 translate 两个量，**没有任何目标尺寸参与** ——
 * 这正是「不会拉伸」的数学保证：同一张位图在同一块壁纸区上，
 * 不管格子多宽多高、有没有滑出屏幕，scale 都是同一个常数。
 *
 * 用法：`canvasX = bitmapX * scale + dx`
 *
 * @param scale 1 个位图像素占多少屏幕像素（Crop 规则：取较大的那个比例）
 * @param dx/dy 位图原点（左上角）在格子局部坐标里的位置；越界时是负的，交给 CLAMP 延拓
 */
internal data class FrostTransform(val scale: Float, val dx: Float, val dy: Float)

/**
 * 纯函数，抽出来是为了能单测 —— 这段是 `ContentScale.Crop` 的逆运算。
 *
 * Crop 的规则：等比放大到**盖满**壁纸区（取较大的比例），然后居中裁掉多余部分。
 * 已知格子相对壁纸左上角的偏移 (relX, relY)，反推位图原点该落在哪。
 *
 * @return null 表示尺寸非法
 */
internal fun frostTransform(
    bmpW: Int,
    bmpH: Int,
    wallW: Float,
    wallH: Float,
    relX: Float,
    relY: Float,
): FrostTransform? {
    if (bmpW <= 0 || bmpH <= 0 || wallW <= 0f || wallH <= 0f) return null
    val scale = maxOf(wallW / bmpW, wallH / bmpH).coerceAtLeast(0.0001f)
    val ox = (wallW - bmpW * scale) / 2f
    val oy = (wallH - bmpH * scale) / 2f
    return FrostTransform(scale = scale, dx = ox - relX, dy = oy - relY)
}

/**
 * 节点在窗口里的**完整**边界，**不裁剪**。
 *
 * ## 为什么必须自己算，不能用 `boundsInRoot()`
 *
 * `boundsInRoot()` 的实际实现是
 * `findRootCoordinates().localBoundingBoxOf(this, clipBounds = true)` ——
 * **默认把矩形裁到所有父容器的裁剪区内**。
 *
 * 磨砂要的恰恰是「卡片真实位置」，哪怕它有一半在屏幕外：
 *
 * ```
 * 卡片真实 x ∈ [-304, 486]  →  boundsInRoot() 返回 [0, 486]（left 被钳到 0）
 * ```
 *
 * 后果是采样点从 -304 变成 0，偏了 304px —— 卡片里贴的是屏幕左边缘那块壁纸，
 * 而不是它正后方那块。表现就是「磨砂有，但位置是错的」。
 *
 * ## 为什么只有相邻页出问题、当前页一直是对的
 *
 * 卡片完整落在屏幕内时，裁剪不改变任何数字 —— 当前页永远正确。
 * 只有当它被 Pager 拖出屏幕、露出一半时才会被钳，所以「前一天滑进来」必错，
 * 「当前页」永远对。致辉 2026-11-19 的原话：
 * 「后一天的模糊就是实时的……前一天的模糊位置在动画完成时的位置」。
 *
 * `clipBounds = false` 关掉裁剪就拿到真实矩形。节点没有缩放/旋转，
 * 所以 `localBoundingBoxOf` 直接给出 左上角 + 自身宽高。
 */
private fun androidx.compose.ui.layout.LayoutCoordinates.unclippedBoundsInRoot(): Rect =
    findRootCoordinates().localBoundingBoxOf(this, clipBounds = false)
