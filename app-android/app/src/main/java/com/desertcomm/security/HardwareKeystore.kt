package com.desertcomm.security

import android.content.Context
import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import java.io.File
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

object HardwareKeystore {
    private const val ANDROID_KEYSTORE = "AndroidKeyStore"
    private const val MASTER_ALIAS = "DesertComm_v31_MasterKey"
    private const val PREFS_NAME = "desertcomm_secure_prefs"
    private const val KEY_WRAPPED_DTN = "wrapped_dtn_key"
    private const val KEY_WRAPPED_IV = "wrapped_dtn_iv"
    private const val KEY_ANTI_ROLLBACK = "security_patch_version"

    private fun getOrCreateHardwareKey(): SecretKey {
        val ks = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        (ks.getKey(MASTER_ALIAS, null) as? SecretKey)?.let { return it }

        val keyGen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        val builder = KeyGenParameterSpec.Builder(
            MASTER_ALIAS,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
        )
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            try {
                builder.setIsStrongBoxBacked(true)
                keyGen.init(builder.build())
                return keyGen.generateKey()
            } catch (_: Exception) {
                // الرجوع التلقائي إلى TEE إذا لم تتوفر شريحة StrongBox مستقلة
                builder.setIsStrongBoxBacked(false)
            }
        }
        keyGen.init(builder.build())
        return keyGen.generateKey()
    }

    fun getOrCreateDtnStorageKey(context: Context): ByteArray {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val encB64 = prefs.getString(KEY_WRAPPED_DTN, null)
        val ivB64 = prefs.getString(KEY_WRAPPED_IV, null)
        val hwKey = getOrCreateHardwareKey()

        if (encB64 != null && ivB64 != null) {
            runCatching {
                val cipher = Cipher.getInstance("AES/GCM/NoPadding")
                cipher.init(Cipher.DECRYPT_MODE, hwKey, GCMParameterSpec(128, Base64.decode(ivB64, Base64.NO_WRAP)))
                cipher.doFinal(Base64.decode(encB64, Base64.NO_WRAP))
            }.getOrNull()?.let { return it }
        }

        val rawKey = ByteArray(32).also { SecureRandom().nextBytes(it) }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, hwKey)
        val wrapped = cipher.doFinal(rawKey)
        prefs.edit()
            .putString(KEY_WRAPPED_DTN, Base64.encodeToString(wrapped, Base64.NO_WRAP))
            .putString(KEY_WRAPPED_IV, Base64.encodeToString(cipher.iv, Base64.NO_WRAP))
            .apply()
        return rawKey
    }

    fun getOrCreatePermanentId(context: Context, generator: () -> String): String {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.getString("permanent_dc_id", null)?.let { return it }
        val newId = generator()
        prefs.edit().putString("permanent_dc_id", newId).apply()
        return newId
    }

    fun getSavedPeerIds(context: Context): List<String> {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return (prefs.getStringSet("saved_peer_ids", emptySet()) ?: emptySet()).sorted()
    }

    fun savePeerId(context: Context, peerId: String): List<String> {
        val clean = peerId.trim().uppercase()
        if (!clean.startsWith("DC-")) return getSavedPeerIds(context)
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val updated = (prefs.getStringSet("saved_peer_ids", emptySet()) ?: emptySet()).toMutableSet()
        if (updated.add(clean)) {
            prefs.edit().putStringSet("saved_peer_ids", updated).apply()
        }
        return updated.sorted()
    }

    fun getAllNicknames(context: Context): Map<String, String> {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val raw = prefs.getStringSet("peer_nicknames", emptySet()) ?: emptySet()
        return raw.mapNotNull { entry ->
            val idx = entry.indexOf('=')
            if (idx > 0) entry.substring(0, idx) to entry.substring(idx + 1) else null
        }.toMap()
    }

    fun setPeerNickname(context: Context, peerId: String, nickname: String): Map<String, String> {
        val cleanId = peerId.trim().uppercase()
        val cleanName = nickname.trim()
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val map = getAllNicknames(context).toMutableMap()
        if (cleanName.isEmpty()) {
            map.remove(cleanId)
        } else {
            map[cleanId] = cleanName
        }
        val encoded = map.entries.map { "${it.key}=${it.value}" }.toSet()
        prefs.edit().putStringSet("peer_nicknames", encoded).apply()
        return map
    }

    fun persistDtnBundles(context: Context, bundles: List<ByteArray>) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val encoded = bundles.map { Base64.encodeToString(it, Base64.NO_WRAP) }.toSet()
        prefs.edit().putStringSet("persisted_dtn_bundles", encoded).apply()
    }

    fun loadPersistedDtnBundles(context: Context): List<ByteArray> {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val raw = prefs.getStringSet("persisted_dtn_bundles", emptySet()) ?: emptySet()
        return raw.mapNotNull { runCatching { Base64.decode(it, Base64.NO_WRAP) }.getOrNull() }
    }

    fun getNetworkE2eeKey(context: Context): ByteArray {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val pass = prefs.getString("network_e2ee_passphrase", "DesertComm-v3.1-Default-E2EE")!!
        return MessageDigest.getInstance("SHA-256").digest(pass.toByteArray(Charsets.UTF_8))
    }

    fun saveEncryptedHistory(context: Context, serialized: String) {
        runCatching {
            val hwKey = getOrCreateHardwareKey()
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, hwKey)
            val ct = cipher.doFinal(serialized.toByteArray(Charsets.UTF_8))
            val out = ByteArray(1 + cipher.iv.size + ct.size)
            out[0] = cipher.iv.size.toByte()
            System.arraycopy(cipher.iv, 0, out, 1, cipher.iv.size)
            System.arraycopy(ct, 0, out, 1 + cipher.iv.size, ct.size)
            File(context.filesDir, "desertcomm_chats.enc").writeBytes(out)
        }
    }

    fun loadEncryptedHistory(context: Context): String? = runCatching {
        val f = File(context.filesDir, "desertcomm_chats.enc")
        if (!f.exists()) return@runCatching null
        val raw = f.readBytes()
        if (raw.size < 16) return@runCatching null
        val ivLen = raw[0].toInt() and 0xFF
        val iv = raw.copyOfRange(1, 1 + ivLen)
        val ct = raw.copyOfRange(1 + ivLen, raw.size)
        val hwKey = getOrCreateHardwareKey()
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, hwKey, GCMParameterSpec(128, iv))
        String(cipher.doFinal(ct), Charsets.UTF_8)
    }.getOrNull()

    fun getOrCreateStaticKeypair(context: Context, keygen: () -> Pair<ByteArray, ByteArray>): Pair<ByteArray, ByteArray> {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val encPrivB64 = prefs.getString("wrapped_static_priv", null)
        val ivB64 = prefs.getString("wrapped_static_iv", null)
        val pubB64 = prefs.getString("static_pub_b64", null)
        val hwKey = getOrCreateHardwareKey()

        if (encPrivB64 != null && ivB64 != null && pubB64 != null) {
            runCatching {
                val cipher = Cipher.getInstance("AES/GCM/NoPadding")
                cipher.init(Cipher.DECRYPT_MODE, hwKey, GCMParameterSpec(128, Base64.decode(ivB64, Base64.NO_WRAP)))
                val priv = cipher.doFinal(Base64.decode(encPrivB64, Base64.NO_WRAP))
                val pub = Base64.decode(pubB64, Base64.NO_WRAP)
                priv to pub
            }.getOrNull()?.let { return it }
        }

        val (priv, pub) = keygen()
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, hwKey)
        val wrapped = cipher.doFinal(priv)
        prefs.edit()
            .putString("wrapped_static_priv", Base64.encodeToString(wrapped, Base64.NO_WRAP))
            .putString("wrapped_static_iv", Base64.encodeToString(cipher.iv, Base64.NO_WRAP))
            .putString("static_pub_b64", Base64.encodeToString(pub, Base64.NO_WRAP))
            .apply()
        return priv to pub
    }

    fun deriveIdFromPubKey(pubKey: ByteArray): String {
        val hash = MessageDigest.getInstance("SHA-256").digest(pubKey)
        return "DC-" + hash.take(4).joinToString("") { "%02X".format(it) }
    }

    @Synchronized
    fun savePeerPublicKey(context: Context, peerId: String, pubKey: ByteArray): Boolean {
        if (pubKey.size != 32) return false
        val normId = peerId.trim().uppercase()
        if (normId.startsWith("DC-") && deriveIdFromPubKey(pubKey) != normId) return false
        val existing = getPeerPublicKey(context, normId)
        if (existing != null) return existing.contentEquals(pubKey)
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.edit().putString("pub_$normId", Base64.encodeToString(pubKey, Base64.NO_WRAP)).commit()
    }

    @Synchronized
    fun getPeerPublicKey(context: Context, peerId: String): ByteArray? {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val b64 = prefs.getString("pub_${peerId.trim().uppercase()}", null) ?: return null
        return runCatching { Base64.decode(b64, Base64.NO_WRAP) }.getOrNull()?.takeIf { it.size == 32 }
    }

    fun getBlockedPeers(context: Context): Set<String> {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getStringSet("blocked_peers", emptySet()) ?: emptySet()
    }

    fun setPeerBlocked(context: Context, peerId: String, blocked: Boolean): Set<String> {
        val clean = peerId.trim().uppercase()
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val updated = getBlockedPeers(context).toMutableSet()
        if (blocked) updated.add(clean) else updated.remove(clean)
        prefs.edit().putStringSet("blocked_peers", updated).apply()
        return updated
    }

    fun removeSavedPeer(context: Context, peerId: String): List<String> {
        val clean = peerId.trim().uppercase()
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val updated = (prefs.getStringSet("saved_peer_ids", emptySet()) ?: emptySet()).toMutableSet()
        updated.remove(clean)
        prefs.edit().putStringSet("saved_peer_ids", updated).apply()
        return updated.sorted()
    }

    fun isDarkMode(context: Context): Boolean =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).getBoolean("ui_dark_mode", true)

    fun setDarkMode(context: Context, dark: Boolean) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit().putBoolean("ui_dark_mode", dark).apply()
    }

    fun isMuted(context: Context): Boolean =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).getBoolean("notif_muted", false)

    fun setMuted(context: Context, muted: Boolean) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit().putBoolean("notif_muted", muted).apply()
    }

    fun getAllGroups(context: Context): Map<String, String> {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val raw = prefs.getStringSet("saved_groups", emptySet()) ?: emptySet()
        return raw.mapNotNull { entry ->
            val idx = entry.indexOf('=')
            if (idx > 0) entry.substring(0, idx) to entry.substring(idx + 1) else null
        }.toMap()
    }

    fun saveGroup(context: Context, groupId: String, groupName: String, groupKey32: ByteArray): Map<String, String> {
        val cleanId = groupId.trim().uppercase()
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val map = getAllGroups(context).toMutableMap()
        map[cleanId] = groupName.trim().ifEmpty { cleanId }
        val encoded = map.entries.map { "${it.key}=${it.value}" }.toSet()
        prefs.edit()
            .putStringSet("saved_groups", encoded)
            .putString("grp_key_$cleanId", Base64.encodeToString(groupKey32, Base64.NO_WRAP))
            .apply()
        return map
    }

    fun getGroupKey(context: Context, groupId: String): ByteArray? {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val b64 = prefs.getString("grp_key_${groupId.trim().uppercase()}", null) ?: return null
        return runCatching { Base64.decode(b64, Base64.NO_WRAP) }.getOrNull()?.takeIf { it.size == 32 }
    }

    fun removeGroup(context: Context, groupId: String): Map<String, String> {
        val cleanId = groupId.trim().uppercase()
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val map = getAllGroups(context).toMutableMap()
        map.remove(cleanId)
        val encoded = map.entries.map { "${it.key}=${it.value}" }.toSet()
        prefs.edit().putStringSet("saved_groups", encoded).remove("grp_key_$cleanId").apply()
        return map
    }

    fun isAppLockEnabled(context: Context): Boolean =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).getString("app_lock_pin_hash", null) != null

    fun setAppLockPin(context: Context, pin: String?) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        if (pin.isNullOrBlank()) {
            prefs.edit().remove("app_lock_pin_hash").apply()
        } else {
            val salt = ByteArray(16).also { SecureRandom().nextBytes(it) }
            val spec = javax.crypto.spec.PBEKeySpec(pin.toCharArray(), salt, 100_000, 256)
            val dk = javax.crypto.SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
            prefs.edit().putString("app_lock_pin_hash", "pbkdf2:" + Base64.encodeToString(salt, Base64.NO_WRAP) + ":" + Base64.encodeToString(dk, Base64.NO_WRAP)).apply()
        }
    }

    fun verifyAppLockPin(context: Context, pin: String): Boolean {
        val stored = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString("app_lock_pin_hash", null) ?: return true
        if (stored.startsWith("pbkdf2:")) {
            val p = stored.split(":")
            if (p.size != 3) return false
            val salt = Base64.decode(p[1], Base64.NO_WRAP)
            val expected = Base64.decode(p[2], Base64.NO_WRAP)
            val spec = javax.crypto.spec.PBEKeySpec(pin.toCharArray(), salt, 100_000, 256)
            val actual = javax.crypto.SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
            return MessageDigest.isEqual(expected, actual)
        }
        val legacy = MessageDigest.getInstance("SHA-256").digest("SADA-PIN:$pin".toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
        if (stored == legacy) { setAppLockPin(context, pin); return true }
        return false
    }

    fun isTorProxyEnabled(context: Context): Boolean =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).getBoolean("tor_proxy_enabled", false)

    fun setTorProxyEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit().putBoolean("tor_proxy_enabled", enabled).apply()
    }

    fun isOnlineRelayEnabled(context: Context): Boolean =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).getBoolean("online_relay_enabled", true)

    fun setOnlineRelayEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit().putBoolean("online_relay_enabled", enabled).apply()
    }

    fun getRelayServerUrl(context: Context): String =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString("relay_server_url", "https://ntfy.sh") ?: "https://ntfy.sh"

    fun setRelayServerUrl(context: Context, url: String) {
        val raw = url.trim().trimEnd('/'); val clean = if (raw.startsWith("https://", true)) raw else "https://ntfy.sh"
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit().putString("relay_server_url", clean).apply()
    }

    fun verifyAndAdvancePatchVersion(context: Context, incomingVersion: Long): Boolean {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val current = prefs.getLong(KEY_ANTI_ROLLBACK, 310L)
        if (incomingVersion <= current) return false
        prefs.edit().putLong(KEY_ANTI_ROLLBACK, incomingVersion).apply()
        return true
    }

    private const val PQ_KEY_ALIAS = "farlo_pq_state_aes_v1"

    private fun getOrCreatePqAesKey(): javax.crypto.SecretKey {
        val ks = java.security.KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey(PQ_KEY_ALIAS, null) as? javax.crypto.SecretKey)?.let { return it }
        val kg = javax.crypto.KeyGenerator.getInstance(android.security.keystore.KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        kg.init(
            android.security.keystore.KeyGenParameterSpec.Builder(
                PQ_KEY_ALIAS,
                android.security.keystore.KeyProperties.PURPOSE_ENCRYPT or android.security.keystore.KeyProperties.PURPOSE_DECRYPT
            ).setBlockModes(android.security.keystore.KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(android.security.keystore.KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build()
        )
        return kg.generateKey()
    }

    @Synchronized
    fun savePqSessionState(context: Context, peerTag: String, stateBytes: ByteArray) {
        runCatching {
            val cipher = javax.crypto.Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(javax.crypto.Cipher.ENCRYPT_MODE, getOrCreatePqAesKey())
            val iv = cipher.iv
            val ct = cipher.doFinal(stateBytes)
            val blob = ByteArray(iv.size + ct.size)
            System.arraycopy(iv, 0, blob, 0, iv.size)
            System.arraycopy(ct, 0, blob, iv.size, ct.size)
            val prefs = context.getSharedPreferences("farlo_pq_sessions", Context.MODE_PRIVATE)
            prefs.edit().putString(peerTag, Base64.encodeToString(blob, Base64.NO_WRAP)).commit()
        }
    }

    @Synchronized
    fun loadAllPqSessionStates(context: Context): Map<String, ByteArray> {
        val out = mutableMapOf<String, ByteArray>()
        val prefs = context.getSharedPreferences("farlo_pq_sessions", Context.MODE_PRIVATE)
        val key = runCatching { getOrCreatePqAesKey() }.getOrNull() ?: return out
        prefs.all.forEach { (tag, v) ->
            val b64 = v as? String ?: return@forEach
            runCatching {
                val blob = Base64.decode(b64, Base64.NO_WRAP)
                if (blob.size > 28) {
                    val cipher = javax.crypto.Cipher.getInstance("AES/GCM/NoPadding")
                    cipher.init(javax.crypto.Cipher.DECRYPT_MODE, key, javax.crypto.spec.GCMParameterSpec(128, blob, 0, 12))
                    out[tag] = cipher.doFinal(blob, 12, blob.size - 12)
                }
            }
        }
        return out
    }
}