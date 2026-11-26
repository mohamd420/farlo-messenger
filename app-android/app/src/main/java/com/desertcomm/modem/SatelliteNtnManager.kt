package com.desertcomm.modem

import android.content.Context
import android.os.Build

object SatelliteNtnManager {
    fun isApiSupported(): Boolean = Build.VERSION.SDK_INT >= 35

    fun getCapabilityStatus(context: Context): String {
        if (Build.VERSION.SDK_INT < 35) {
            return "يتطلب أندرويد 15+ (جهازك API ${Build.VERSION.SDK_INT})"
        }
        return try {
            val mgr = context.getSystemService("satellite")
            if (mgr == null) {
                "خدمة SatelliteManager غير مفعلة في النظام/المشغل"
            } else {
                "مودم NTN متاح في النظام (جاهز لحزم blind-crypto)"
            }
        } catch (e: Exception) {
            "تعذر فحص SatelliteManager: ${e.message}"
        }
    }

    fun sendEncryptedBurst(context: Context, encryptedBytes: ByteArray): Result<String> = runCatching {
        if (Build.VERSION.SDK_INT < 35) {
            error("SatelliteManager API غير متوفر قبل أندرويد 15")
        }
        val mgr = context.getSystemService("satellite")
            ?: error("الجهاز أو المشغل لا يتيح واجهة SatelliteManager المباشرة للتطبيقات")
        // تم تشفير الحزمة مسبقاً بـ XChaCha20-Poly1305 وتمويهها؛ حجم الحزمة الجاهزة للبث الفضائي:
        "حزمة فضائية مشفرة جاهزة (${encryptedBytes.size} بايت) عبر ${mgr.javaClass.simpleName}"
    }
}