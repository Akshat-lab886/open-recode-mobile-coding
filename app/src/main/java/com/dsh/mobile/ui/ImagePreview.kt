package com.dsh.mobile.ui

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import java.io.File

/**
 * Renders an image artifact or workspace file.
 *
 * Decoding is deliberately DOWNSCALED. A tablet with ~1GB of RAM decoding a
 * 12-megapixel camera photo at full size is a real OOM, and it would take the
 * whole app down from a simple tap on a file row. [sampleSizeFor] is pure and
 * unit-tested; only the actual decode needs Android.
 */
object ImagePreview {

    /** Longest edge we render at. Enough to read a screenshot, cheap to decode. */
    const val MAX_DIMENSION: Int = 1600

    /** Refuse absurd images rather than spending seconds decoding them. */
    const val MAX_DECODE_BYTES: Long = 24L * 1024 * 1024

    /**
     * The power-of-two subsampling factor that brings [width]x[height] under
     * [maxDimension] on its longest edge.
     *
     * Power-of-two only because BitmapFactory only honours powers of two, so any
     * other value is silently rounded and the result is not predictable.
     */
    fun sampleSizeFor(width: Int, height: Int, maxDimension: Int = MAX_DIMENSION): Int {
        if (width <= 0 || height <= 0 || maxDimension <= 0) return 1
        var longest = maxOf(width, height)
        var sample = 1
        while (longest / 2 >= maxDimension) {
            longest /= 2
            sample *= 2
        }
        return sample
    }

    /** Decodes [file] downscaled, or null if it is not a decodable image. */
    fun decode(file: File, maxDimension: Int = MAX_DIMENSION): Bitmap? {
        if (!file.isFile || file.length() > MAX_DECODE_BYTES) return null
        return try {
            // Read the bounds first, decode with subsampling second. Decoding
            // straight to a bitmap is what blows up memory.
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(file.absolutePath, bounds)
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
            val opts = BitmapFactory.Options().apply {
                inSampleSize = sampleSizeFor(bounds.outWidth, bounds.outHeight, maxDimension)
            }
            BitmapFactory.decodeFile(file.absolutePath, opts)
        } catch (_: Throwable) {
            // OutOfMemoryError included: a failed image must never take down
            // the app the user was browsing files in.
            null
        }
    }
}

/** The preview body for [file], choosing image or text automatically. */
@Composable
fun FilePreviewBody(file: File, text: String?, modifier: Modifier = Modifier) {
    val bitmap = remember(file.absolutePath, file.lastModified()) {
        ImagePreview.decode(file)
    }
    when {
        bitmap != null -> Image(
            bitmap = bitmap.asImageBitmap(),
            contentDescription = null,
            contentScale = ContentScale.Fit,
            modifier = modifier.fillMaxWidth().heightIn(max = 520.dp),
        )
        text != null -> Text(
            text,
            style = MaterialTheme.typography.bodySmall,
            modifier = modifier.fillMaxWidth(),
        )
        else -> Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(
                "Cannot preview this file.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
