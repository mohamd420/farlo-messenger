package com.desertcomm.security

import android.content.Context
import android.util.Base64
import java.math.BigInteger
import java.security.MessageDigest

data class SafetyNumberInfo(
    val formattedDigits: String,
    val hasPeerPublicKey: Boolean,
    val isVerified: Boolean
)

object SafetyNumberHelper {
    private const val PREFS_NAME = "desertcomm_secure_prefs"
    private const val KEY_VERIFIED = "verified_peers_v1"

    fun isPeerVerified(context: Context, peerId: String): Boolean {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val set = prefs.getStringSet(KEY_VERIFIED, emptySet()) ?: emptySet()
        return set.contains(peerId.trim().uppercase())
    }

    fun setPeerVerified(context: Context, peerId: String, verified: Boolean) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val set = (prefs.getStringSet(KEY_VERIFIED, emptySet()) ?: emptySet()).toMutableSet()
        val key = peerId.trim().uppercase()
        if (verified) set.add(key) else set.remove(key)
        prefs.edit().putStringSet(KEY_VERIFIED, set).apply()
    }

    fun compute(context: Context, myId: String, peerId: String): SafetyNumberInfo {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val myPubB64 = prefs.getString("static_pub_b64", null)
        val peerPubB64 = prefs.getString("pub_${peerId.trim().uppercase()}", null)

        val myBytes = myPubB64?.let { runCatching { Base64.decode(it, Base64.NO_WRAP) }.getOrNull() }
            ?: myId.uppercase().toByteArray(Charsets.UTF_8)
        val peerBytes = peerPubB64?.let { runCatching { Base64.decode(it, Base64.NO_WRAP) }.getOrNull() }
            ?: peerId.uppercase().toByteArray(Charsets.UTF_8)

        // ترتيب معياري لضمان تطابق الرقم 100% على الهاتفين
        val a = "${myId.uppercase()}|" to myBytes
        val b = "${peerId.uppercase()}|" to peerBytes
        val ordered = if (a.first <= b.first) listOf(a, b) else listOf(b, a)

        val md = MessageDigest.getInstance("SHA-256")
        ordered.forEach { (idStr, keyBytes) ->
            md.update(idStr.toByteArray(Charsets.UTF_8))
            md.update(keyBytes)
        }
        var digest = md.digest()
        // 50 جولة خلط إضافية لبصمة قوية
        repeat(50) { digest = MessageDigest.getInstance("SHA-256").digest(digest) }

        val big = BigInteger(1, digest).toString().padStart(30, '0').take(30)
        val grouped = big.chunked(5).joinToString("  ")
        return SafetyNumberInfo(
            formattedDigits = grouped,
            hasPeerPublicKey = peerPubB64 != null,
            isVerified = isPeerVerified(context, peerId)
        )
    }
}