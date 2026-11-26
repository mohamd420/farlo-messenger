package com.desertcomm.core

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.util.Base64
import android.util.LruCache
import java.io.ByteArrayOutputStream

object ImageHelper {
    private val bitmapCache = LruCache<Int, Bitmap>(40)
    fun compressUriToBase64(context: Context, uri: Uri, maxDim: Int = 512, quality: Int = 45): Result<String> = runCatching {
        val input = context.contentResolver.openInputStream(uri) ?: error("تعذر فتح الصورة")
        val original = input.use { BitmapFactory.decodeStream(it) } ?: error("صورة غير صالحة")
        val scale = minOf(1f, maxDim.toFloat() / maxOf(original.width, original.height).coerceAtLeast(1))
        val w = (original.width * scale).toInt().coerceAtLeast(1)
        val h = (original.height * scale).toInt().coerceAtLeast(1)
        val scaled = if (scale < 1f) Bitmap.createScaledBitmap(original, w, h, true) else original

        val bos = ByteArrayOutputStream()
        val fmt = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Bitmap.CompressFormat.WEBP_LOSSY
        } else {
            @Suppress("DEPRECATION")
            Bitmap.CompressFormat.WEBP
        }
        scaled.compress(fmt, quality, bos)
        if (scaled !== original) scaled.recycle()
        original.recycle()
        Base64.encodeToString(bos.toByteArray(), Base64.NO_WRAP)
    }

    fun decodeBase64ToBitmap(b64: String): Bitmap? {
        val key = b64.hashCode()
        bitmapCache.get(key)?.let { return it }
        return runCatching {
            val bytes = Base64.decode(b64, Base64.NO_WRAP)
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.also { bitmapCache.put(key, it) }
        }.getOrNull()
    }
}