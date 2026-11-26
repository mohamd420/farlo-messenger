package com.desertcomm.core

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.webkit.MimeTypeMap
import android.widget.Toast
import androidx.core.content.FileProvider
import java.io.File
import java.util.Locale

object MediaActionsHelper {

    fun guessMimeType(fileName: String): String {
        val ext = fileName.substringAfterLast('.', "").lowercase(Locale.US)
        return when (ext) {
            "pdf" -> "application/pdf"
            "jpg", "jpeg" -> "image/jpeg"
            "png" -> "image/png"
            "webp" -> "image/webp"
            "mp4", "mkv", "webm" -> "video/mp4"
            "m4a", "mp3", "ogg", "3gp", "wav" -> "audio/*"
            "doc" -> "application/msword"
            "docx" -> "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
            "xls" -> "application/vnd.ms-excel"
            "xlsx" -> "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
            "ppt", "pptx" -> "application/vnd.ms-powerpoint"
            "txt", "csv", "json", "xml" -> "text/plain"
            "zip", "rar", "7z" -> "application/zip"
            "apk" -> "application/vnd.android.package-archive"
            else -> MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext) ?: "*/*"
        }
    }

    fun openFileNatively(context: Context, file: File) {
        runCatching {
            val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
            val mime = guessMimeType(file.name)
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, mime)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(Intent.createChooser(intent, "فتح باستخدام"))
        }.onFailure { err ->
            Toast.makeText(context, "لا يوجد تطبيق لفتح هذا الملف: ${err.message}", Toast.LENGTH_LONG).show()
        }
    }

    fun shareFile(context: Context, file: File) {
        runCatching {
            val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
            val mime = guessMimeType(file.name)
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = mime
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(Intent.createChooser(intent, "مشاركة الملف"))
        }.onFailure { err ->
            Toast.makeText(context, "تعذرت المشاركة: ${err.message}", Toast.LENGTH_LONG).show()
        }
    }

    fun saveToDownloads(context: Context, file: File, displayName: String = file.name) {
        runCatching {
            val mime = guessMimeType(displayName)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val values = ContentValues().apply {
                    put(MediaStore.Downloads.DISPLAY_NAME, displayName)
                    put(MediaStore.Downloads.MIME_TYPE, mime)
                    put(MediaStore.Downloads.RELATIVE_PATH, "${Environment.DIRECTORY_DOWNLOADS}/Farlo")
                }
                val uri = context.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                    ?: error("تعذر إنشاء ملف في التنزيلات")
                context.contentResolver.openOutputStream(uri)?.use { out ->
                    file.inputStream().use { it.copyTo(out) }
                }
            } else {
                @Suppress("DEPRECATION")
                val dir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "Farlo").apply { mkdirs() }
                file.copyTo(File(dir, displayName), overwrite = true)
            }
            Toast.makeText(context, "تم الحفظ في Downloads/Farlo/$displayName", Toast.LENGTH_LONG).show()
        }.onFailure { err ->
            Toast.makeText(context, "فشل الحفظ: ${err.message}", Toast.LENGTH_LONG).show()
        }
    }

    fun saveBitmapToDownloads(context: Context, bmp: android.graphics.Bitmap, name: String = "Sada_${System.currentTimeMillis()}.jpg") {
        runCatching {
            val tmp = File(context.cacheDir, name)
            tmp.outputStream().use { bmp.compress(android.graphics.Bitmap.CompressFormat.JPEG, 92, it) }
            saveToDownloads(context, tmp, name)
            tmp.delete()
        }.onFailure { err ->
            Toast.makeText(context, "فشل حفظ الصورة: ${err.message}", Toast.LENGTH_LONG).show()
        }
    }
}