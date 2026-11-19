package com.gzucm.pocket.ui.components

import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * 解析 G3 连续曲率圆角。
 *
 * 形状来自 Atrium 设计规范 §2.3（桌面 `液态玻璃DESIGN.md`）：
 *
 * ```
 * R = min(radius, 短边/2)
 * L = min(1.6R, 短边/2)
 * v = 1 - (1 - 1/√2)·R/L
 * a = max(0.02, (2v⁴ - v²)/(1 - v²))
 * f(t) = t⁴ / [a + (1-a)t²]
 * F(x,y) = f(x/L) + f(y/L) - 1 = 0
 * ```
 *
 * 它在直边接点处满足 `y′(0)=y″(0)=y‴(0)=0`，也就是 `κ=0` 且 `dκ/ds=0`，
 * 不像圆弧那样在接点突然出现曲率。
 *
 * ## 边界说明（不要对外宣称「严格 G3 覆盖率」）
 *
 * Compose 的 [Shape] 只能返回 [Outline.Generic] 的 Path，所以这里是
 * **直接对解析方程采样**再连成折线，不是文档里那种「解析覆盖率直接栅格化」。
 * 每角 32 段、缓存后只算一次，1080p 上的偏差远小于半个像素；
 * 但严格数学意义仍属于近似，不能拿它当导数证明。
 *
 * 正圆（`CircleShape`）不动：它没有直边接缝，本身曲率恒定。
 */
class G3CornerShape(
    private val topStart: Dp,
    private val topEnd: Dp = topStart,
    private val bottomEnd: Dp = topStart,
    private val bottomStart: Dp = topStart,
) : Shape {

    private var cachedSize: Size? = null
    private var cachedLayout: LayoutDirection? = null
    private var cachedOutline: Outline? = null

    @Synchronized
    override fun createOutline(
        size: Size,
        layoutDirection: LayoutDirection,
        density: Density,
    ): Outline {
        val hit = cachedOutline
        if (hit != null && size == cachedSize && layoutDirection == cachedLayout) return hit

        val w = size.width
        val h = size.height
        if (w <= 0f || h <= 0f) {
            return Outline.Generic(Path()).also {
                cachedSize = size
                cachedLayout = layoutDirection
                cachedOutline = it
            }
        }

        val short = min(w, h)
        with(density) {
            val tl = if (layoutDirection == LayoutDirection.Ltr) topStart else topEnd
            val tr = if (layoutDirection == LayoutDirection.Ltr) topEnd else topStart
            val br = if (layoutDirection == LayoutDirection.Ltr) bottomEnd else bottomStart
            val bl = if (layoutDirection == LayoutDirection.Ltr) bottomStart else bottomEnd

            val a = cornerProfile(tl.toPx(), short)
            val b = cornerProfile(tr.toPx(), short)
            val c = cornerProfile(br.toPx(), short)
            val d = cornerProfile(bl.toPx(), short)

            val path = buildPath(w, h, a, b, c, d)
            val out = Outline.Generic(path)
            cachedSize = size
            cachedLayout = layoutDirection
            cachedOutline = out
            return out
        }
    }
}

/** 解析轮廓的三参数。纯数据，供单测直接验导数条件。 */
internal data class G3Profile(val radius: Float, val extent: Float, val a: Float)

/** 按文档公式求 a；R=0 时返回退化 profile，调用方直接画矩形。 */
internal fun cornerProfile(radiusPx: Float, shortSide: Float): G3Profile {
    val r = radiusPx.coerceIn(0f, shortSide / 2f)
    if (r <= 0f) return G3Profile(0f, 0f, 0.02f)

    // L 用短边的一半封顶：任意一条边的两个角加起来都不会超过边长，
    // 否则相邻两角的过渡区会互相倒退，路径自交。
    val l = min(1.6f * r, shortSide / 2f)
    val v = 1f - (1f - 1f / sqrt(2f)) * r / l
    val v2 = v * v
    val a = max(0.02f, (2f * v2 * v2 - v2) / (1f - v2))
    return G3Profile(r, l, a)
}

/** `f(t) = t⁴/[a + (1-a)t²]`，端点钳死避免浮点越界。 */
internal fun g3F(t: Float, a: Float): Float {
    if (t <= 0f) return 0f
    if (t >= 1f) return 1f
    val t2 = t * t
    return t2 * t2 / (a + (1f - a) * t2)
}

/** 解 `f(t)=s`：令 u=t² 得 u² - s(1-a)u - sa = 0，取正根。 */
internal fun g3InverseF(s: Float, a: Float): Float {
    if (s <= 0f) return 0f
    if (s >= 1f) return 1f
    val k = 1f - a
    val u = (s * k + sqrt(s * s * k * k + 4f * s * a)) / 2f
    return sqrt(max(0f, u))
}

/** 给定沿边的距离 x，求轮廓上另一坐标 y（局部四分之一角坐标）。 */
internal fun g3CornerY(x: Float, p: G3Profile): Float {
    if (p.extent <= 0f) return 0f
    val t = (x / p.extent).coerceIn(0f, 1f)
    return p.extent * g3InverseF(1f - g3F(t, p.a), p.a)
}

private const val SAMPLES = 32

private data class CornerPts(val xs: FloatArray, val ys: FloatArray, val extent: Float)

private fun sample(p: G3Profile): CornerPts {
    val xs = FloatArray(SAMPLES + 1)
    val ys = FloatArray(SAMPLES + 1)
    /*
     * 直角也要返回**固定长度**的数组。
     * 之前这里返回 FloatArray(1)，buildPath 仍按 SAMPLES 索引，
     * 非对称圆角（如下两角 0、上两角 24）会直接 ArrayIndexOutOfBounds。
     * 现在所有点落在角点上，循环退化成原地踏步，形状就是直角。
     */
    if (p.radius <= 0f) return CornerPts(xs, ys, 0f)

    for (i in 0..SAMPLES) {
        val x = p.extent * i / SAMPLES
        xs[i] = x
        ys[i] = g3CornerY(x, p)
    }
    return CornerPts(xs, ys, p.extent)
}

/** 四角顺时针拼一圈；每个角独立用自己的半径和过渡范围。 */
private fun buildPath(
    w: Float,
    h: Float,
    tl: G3Profile,
    tr: G3Profile,
    br: G3Profile,
    bl: G3Profile,
): Path {
    val path = Path()
    if (tl.radius <= 0f && tr.radius <= 0f && br.radius <= 0f && bl.radius <= 0f) {
        path.addRect(androidx.compose.ui.geometry.Rect(0f, 0f, w, h))
        return path
    }

    val a = sample(tl)
    val b = sample(tr)
    val c = sample(br)
    val d = sample(bl)

    /*
     * ⚠️ 局部坐标不要直接当屏幕坐标用（2026-11-18 踩过：整页圆角变成十字缺口）。
     *
     * F(x,y)=0 里的局部原点在**过渡区的外角**，不是矩形角点。
     * 以左上角为例，外角在 (L,L)，矩形角点在 (0,0)，
     * 要映射成屏幕坐标必须取 (L-y, L-x)。直接用 (x,y) 或 (y,x)
     * 都会让曲线朝矩形内侧弯，NonZero 填充就把四角挖成十字。
     *
     * 可以这样自检：v=0 时应落在直边（左边或上边），
     * v=L/2 时应靠近矩形角点而不是远离它。
     *
     * sample() 里 xs[i]=v（局部 x，沿垂直边），
     * ys[i]=g3CornerY(v)=局部 y（水平方向）。
     */
    // 上边：左上角终点 → 右上角起点
    path.moveTo(a.extent, 0f)
    path.lineTo(w - b.extent, 0f)
    // 右上角：矩形点 (w-L+y, L-v)，v 从 L→0，从上边走到右边
    for (i in SAMPLES downTo 0) {
        path.lineTo(w - b.extent + b.ys[i], b.extent - b.xs[i])
    }
    // 右边
    path.lineTo(w, h - c.extent)
    // 右下角：矩形点 (w-L+y, h-L+v)，v 从 0→L，从右边走到下边
    for (i in 0..SAMPLES) {
        path.lineTo(w - c.extent + c.ys[i], h - c.extent + c.xs[i])
    }
    // 下边
    path.lineTo(d.extent, h)
    // 左下角：矩形点 (L-y, h-L+v)，v 从 L→0，从下边走到左边
    for (i in SAMPLES downTo 0) {
        path.lineTo(d.extent - d.ys[i], h - d.extent + d.xs[i])
    }
    // 左边
    path.lineTo(0f, a.extent)
    // 左上角：矩形点 (L-y, L-v)，v 从 0→L，从左边回到起点
    for (i in 0..SAMPLES) {
        path.lineTo(a.extent - a.ys[i], a.extent - a.xs[i])
    }
    path.close()
    return path
}
