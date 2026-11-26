package com.desertcomm.core

import android.graphics.Bitmap
import com.google.zxing.BarcodeFormat
import com.journeyapps.barcodescanner.BarcodeEncoder

object QrHelper {
    fun generateQrBitmap(content: String, sizePx: Int = 600): Bitmap? = runCatching {
        BarcodeEncoder().encodeBitmap(content, BarcodeFormat.QR_CODE, sizePx, sizePx)
    }.getOrNull()
}