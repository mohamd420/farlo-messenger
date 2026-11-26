package com.desertcomm.core

import android.content.Context
import android.net.Uri
import android.util.Base64
import com.desertcomm.security.HardwareKeystore
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.SecureRandom
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

object EncryptedBackupManager {
    private const val MAGIC = "SADA_BK2|"
    private const val PREFS_NAME = "desertcomm_secure_prefs"

    private fun deriveKey(password: String, salt: ByteArray): ByteArray {
        val spec = PBEKeySpec(password.toCharArray(), salt, 65_536, 256)
        return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
    }

    fun exportEncryptedBackup(context: Context, password: String): Result<String> = runCatching {
        if (password.length < 4) error("كلمة المرور يجب أن تكون 4 أحرف على الأقل")
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val bundle = JSONObject()

        val strMap = JSONObject()
        val setMap = JSONObject()
        val boolMap = JSONObject()
        val skipKeys = setOf("wrapped_dtn_key", "wrapped_dtn_iv", "wrapped_static_priv", "wrapped_static_iv")

        prefs.all.forEach { (k, v) ->
            if (k in skipKeys) return@forEach
            when (v) {
                is String -> strMap.put(k, v)
                is Boolean -> boolMap.put(k, v)
                is Set<*> -> {
                    val arr = JSONArray()
                    v.forEach { item -> if (item is String) arr.put(item) }
                    setMap.put(k, arr)
                }
            }
        }
        bundle.put("strings", strMap)
        bundle.put("sets", setMap)
        bundle.put("booleans", boolMap)

        val history = HardwareKeystore.loadEncryptedHistory(context)
        if (history != null) {
            bundle.put("chat_history_json", history)
        }

        val salt = ByteArray(16).also { SecureRandom().nextBytes(it) }
        val key = deriveKey(password, salt)
        val cipher = NativeBridgeV3.sealDtnBody(key, bundle.toString().toByteArray(Charsets.UTF_8))
            ?: error("فشل تشفير النسخة الاحتياطية")

        val payload = MAGIC +
            Base64.encodeToString(salt, Base64.NO_WRAP) + "|" +
            Base64.encodeToString(cipher, Base64.NO_WRAP)

        val stamp = SimpleDateFormat("yyyyMMdd_HHmm", Locale.US).format(Date())
        val fileName = "Sada_Backup_$stamp.sadaenc"
        val tmp = File(context.cacheDir, fileName)
        tmp.writeText(payload, Charsets.UTF_8)
        MediaActionsHelper.saveToDownloads(context, tmp, fileName)
        tmp.delete()
        fileName
    }

    fun restoreEncryptedBackup(context: Context, uri: Uri, password: String): Result<Int> = runCatching {
        if (password.length < 4) error("أدخل كلمة مرور النسخة الاحتياطية")
        val raw = context.contentResolver.openInputStream(uri)?.use { it.readBytes().toString(Charsets.UTF_8) }
            ?: error("تعذر قراءة الملف")
        if (!raw.startsWith(MAGIC)) error("هذا الملف ليس نسخة احتياطية صالحة لتطبيق فارلو")
        val parts = raw.removePrefix(MAGIC).split("|", limit = 2)
        if (parts.size != 2) error("ملف النسخة الاحتياطية تالف")

        val salt = Base64.decode(parts[0], Base64.NO_WRAP)
        val cipher = Base64.decode(parts[1], Base64.NO_WRAP)
        val key = deriveKey(password, salt)
        val plain = NativeBridgeV3.openDtnBody(key, cipher)
            ?: error("كلمة المرور خاطئة أو الملف تالف")

        val bundle = JSONObject(String(plain, Charsets.UTF_8))
        val editor = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit()
        var restoredItems = 0

        bundle.optJSONObject("strings")?.let { obj ->
            obj.keys().forEach { k -> editor.putString(k, obj.getString(k)); restoredItems++ }
        }
        bundle.optJSONObject("booleans")?.let { obj ->
            obj.keys().forEach { k -> editor.putBoolean(k, obj.getBoolean(k)); restoredItems++ }
        }
        bundle.optJSONObject("sets")?.let { obj ->
            obj.keys().forEach { k ->
                val arr = obj.getJSONArray(k)
                val set = mutableSetOf<String>()
                for (i in 0 until arr.length()) set.add(arr.getString(i))
                editor.putStringSet(k, set)
                restoredItems++
            }
        }
        editor.apply()

        val hist = bundle.optString("chat_history_json", "")
        if (hist.isNotEmpty()) {
            HardwareKeystore.saveEncryptedHistory(context, hist)
            restoredItems++
        }
        restoredItems
    }
}