package com.gzucm.pocket.ui.components

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import java.io.File

/**
 * 课表自定义背景。
 *
 * @param alpha 背景不透明度 0.15~1.0，由用户自己调。
 *   只作用于图片本身；再压一层极淡的底色保证课程块边缘不糊。
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
    val file = remember(path) { File(path) }
    if (!file.exists()) return

    val sharp = remember(path, file.length()) {
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

    // 开了模糊且糊好的图可用，就不必解原图了
    val bmp = if (useBlurred && blurred != null) blurred else sharp
    bmp ?: return

    val a = alpha.coerceIn(0.15f, 1f)

    Box(modifier.fillMaxSize()) {
        Image(
            bitmap = bmp,
            contentDescription = null,
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer { this.alpha = a },
            contentScale = ContentScale.Crop,
            filterQuality = FilterQuality.Medium,
        )
        // 极淡一层底色，压住图片高光，保证课程块边缘清晰
        val tint = MaterialTheme.colorScheme.background
        Box(
            Modifier
                .fillMaxSize()
                .background(tint.copy(alpha = 0.18f)),
        )
    }
}
