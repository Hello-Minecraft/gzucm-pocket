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
 */
@Composable
fun CourseBackground(
    path: String?,
    alpha: Float,
    modifier: Modifier = Modifier,
) {
    if (path.isNullOrBlank()) return
    val file = remember(path) { File(path) }
    if (!file.exists()) return

    val bmp = remember(path, file.length()) {
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
    } ?: return

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
