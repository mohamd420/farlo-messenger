package com.desertcomm.core

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Base64
import com.desertcomm.security.HardwareKeystore
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okio.BufferedSink
import org.json.JSONObject
import java.io.File
import java.net.InetSocketAddress
import java.net.Proxy
import java.security.SecureRandom
import java.util.UUID
import java.util.concurrent.TimeUnit

data class UploadedMediaResult(val downloadUrl: String, val keyB64: String, val fileName: String, val sizeBytes: Long)

object EncryptedMediaTransfer {
    private val directClient = OkHttpClient.Builder()
        .followRedirects(false)
        .followSslRedirects(false)
        .connectTimeout(8, TimeUnit.SECONDS)
        .writeTimeout(45, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    private val torClient = OkHttpClient.Builder()
        .proxy(Proxy(Proxy.Type.SOCKS, InetSocketAddress("127.0.0.1", 9050)))
        .followRedirects(false)
        .followSslRedirects(false)
        .connectTimeout(25, TimeUnit.SECONDS)
        .writeTimeout(120, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .build()

    private fun clientFor(context: Context): OkHttpClient = if (HardwareKeystore.isTorProxyEnabled(context)) directClient.newBuilder().proxy(Proxy(Proxy.Type.SOCKS, InetSocketAddress("127.0.0.1", com.desertcomm.p2p.EmbeddedTorManager.socksPort))).followRedirects(false)
        .followSslRedirects(false)
        .connectTimeout(25, TimeUnit.SECONDS).build() else directClient

    fun stableCacheFile(context: Context, keyB64: String, fileName: String): File {
        val dir = File(context.filesDir, "farlo_media").apply { mkdirs(); runCatching { File(this, ".nomedia").createNewFile() } }
        val sid = keyB64.replace(Regex("[^a-zA-Z0-9]"), "").take(16).ifEmpty { "m" }
        val clean = fileName.replace(Regex("[^a-zA-Z0-9._-]"), "_")
        return File(dir, "${sid}_${clean}")
    }

    fun getFileNameAndSize(context: Context, uri: Uri): Pair<String, Long> {
        var name = "file_${System.currentTimeMillis()}"
        var size = 0L
        context.contentResolver.query(uri, null, null, null, null)?.use { c ->
            val ni = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            val si = c.getColumnIndex(OpenableColumns.SIZE)
            if (c.moveToFirst()) {
                if (ni >= 0) name = c.getString(ni) ?: name
                if (si >= 0) size = c.getLong(si)
            }
        }
        return name to size
    }

    private fun progressBody(bytes: ByteArray, onProgress: (Float) -> Unit): RequestBody =
        object : RequestBody() {
            override fun contentType() = "application/octet-stream".toMediaType()
            override fun contentLength() = bytes.size.toLong()
            override fun writeTo(sink: BufferedSink) {
                val total = bytes.size.coerceAtLeast(1)
                var offset = 0
                while (offset < total) {
                    val len = (total - offset).coerceAtMost(16_384)
                    sink.write(bytes, offset, len)
                    offset += len
                    onProgress(0.15f + 0.80f * (offset.toFloat() / total.toFloat()))
                }
            }
        }

    private fun uploadToCatbox(client: OkHttpClient, enc: ByteArray, onP: (Float) -> Unit): String {
        val body = MultipartBody.Builder().setType(MultipartBody.FORM)
            .addFormDataPart("reqtype", "fileupload")
            .addFormDataPart("fileToUpload", "enc_${UUID.randomUUID().toString().take(8)}.bin", progressBody(enc, onP))
            .build()
        val req = Request.Builder().url("https://catbox.moe/user/api.php").header("User-Agent", "Mozilla/5.0").post(body).build()
        return client.newCall(req).execute().use { resp ->
            if (!resp.request.url.isHttps) error("Insecure redirect blocked"); if (!resp.isSuccessful) error("catbox HTTP ${resp.code}")
            val url = resp.body?.string()?.trim().orEmpty()
            if (!url.startsWith("http")) error("invalid catbox url")
            url
        }
    }

    private fun uploadToFilebin(client: OkHttpClient, enc: ByteArray, onP: (Float) -> Unit): String {
        val bin = "sada${UUID.randomUUID().toString().replace("-", "").take(10)}"
        val fname = "enc_${UUID.randomUUID().toString().take(6)}.bin"
        val url = "https://filebin.net/$bin/$fname"
        val req = Request.Builder().url(url).header("User-Agent", "Mozilla/5.0").post(progressBody(enc, onP)).build()
        client.newCall(req).execute().use { resp ->
            if (!resp.request.url.isHttps) error("Insecure redirect blocked"); if (!resp.isSuccessful) error("filebin HTTP ${resp.code}")
        }
        return url
    }

    private fun uploadToTmpFiles(client: OkHttpClient, enc: ByteArray, onP: (Float) -> Unit): String {
        val body = MultipartBody.Builder().setType(MultipartBody.FORM)
            .addFormDataPart("file", "enc_${UUID.randomUUID().toString().take(8)}.bin", progressBody(enc, onP))
            .build()
        val req = Request.Builder().url("https://tmpfiles.org/api/v1/upload").header("User-Agent", "Mozilla/5.0").post(body).build()
        val json = client.newCall(req).execute().use { resp ->
            if (!resp.request.url.isHttps) error("Insecure redirect blocked"); if (!resp.isSuccessful) error("tmpfiles HTTP ${resp.code}")
            resp.body?.string() ?: error("empty")
        }
        val raw = JSONObject(json).optJSONObject("data")?.optString("url").orEmpty().replaceFirst("http://", "https://")
        if (!raw.startsWith("http")) error("invalid tmpfiles url")
        return if (raw.contains("tmpfiles.org/dl/")) raw else raw.replaceFirst("https://tmpfiles.org/", "https://tmpfiles.org/dl/")
    }

    private fun uploadToNtfy(context: Context, client: OkHttpClient, enc: ByteArray, onP: (Float) -> Unit): String {
        val baseUrl = HardwareKeystore.getRelayServerUrl(context).trimEnd('/')
        val topic = "sada_blob_${UUID.randomUUID().toString().replace("-", "").take(16)}"
        val req = Request.Builder().url("$baseUrl/$topic").put(progressBody(enc, onP))
            .header("Filename", "enc_${UUID.randomUUID().toString().take(8)}.bin").build()
        val json = client.newCall(req).execute().use { resp ->
            if (!resp.request.url.isHttps) error("Insecure redirect blocked"); if (!resp.isSuccessful) error("ntfy HTTP ${resp.code}")
            resp.body?.string() ?: error("empty")
        }
        return JSONObject(json).optJSONObject("attachment")?.optString("url")?.takeIf { it.startsWith("http") } ?: error("no url")
    }

    fun encryptAndUploadBytes(context: Context, rawBytes: ByteArray, fileName: String, onProgress: (Float) -> Unit): Result<UploadedMediaResult> = runCatching {
        onProgress(0.05f)
        val fileKey = ByteArray(32).also { SecureRandom().nextBytes(it) }
        val encrypted = NativeBridgeV3.sealDtnBody(fileKey, rawBytes) ?: error("فشل تشفير الملف")
        onProgress(0.15f)
        val client = clientFor(context)
        val customUrl = HardwareKeystore.getRelayServerUrl(context)

        val dlUrl = if (!customUrl.contains("ntfy.sh")) {
            runCatching { uploadToNtfy(context, client, encrypted, onProgress) }
                .recoverCatching { uploadToCatbox(client, encrypted, onProgress) }
                .recoverCatching { uploadToFilebin(client, encrypted, onProgress) }
                .getOrThrow()
        } else {
            runCatching { uploadToTmpFiles(client, encrypted, onProgress) }
                .recoverCatching { uploadToCatbox(client, encrypted, onProgress) }
                .recoverCatching { uploadToFilebin(client, encrypted, onProgress) }
                .getOrThrow()
        }

        onProgress(1.0f)
        val k64 = Base64.encodeToString(fileKey, Base64.NO_WRAP); val cleanName = fileName.replace("|", "_"); runCatching { stableCacheFile(context, k64, cleanName).writeBytes(rawBytes) }; UploadedMediaResult(dlUrl, k64, cleanName, rawBytes.size.toLong())
    }

    fun downloadAndDecryptToFile(context: Context, uid: String, downloadUrl: String, keyB64: String, fileName: String, maxBytes: Long = 25L * 1024L * 1024L, onProgress: (Float) -> Unit): Result<File> = runCatching {
        val dir = File(context.filesDir, "sada_media").apply { mkdirs(); runCatching { File(this, ".nomedia").createNewFile() } }
        val outFile = stableCacheFile(context, keyB64, fileName)
        if (outFile.exists() && outFile.length() > 0) {
            onProgress(1.0f)
            return@runCatching outFile
        }
        val client = clientFor(context)

        val customHost = runCatching { java.net.URI(HardwareKeystore.getRelayServerUrl(context)).host?.lowercase().orEmpty() }.getOrDefault("")
        val allowed = listOf("files.catbox.moe", "catbox.moe", "filebin.net", "tmpfiles.org") + listOf(customHost).filter { it.isNotBlank() }
        val maxMediaBytes = maxBytes

        fun validateSafeUrl(rawUrl: String) {
            val u = java.net.URI(rawUrl)
            val h = u.host?.lowercase().orEmpty()
            if (u.scheme?.lowercase() != "https" || allowed.none { h == it || h.endsWith(".$it") }) {
                error("Untrusted media host blocked: $h")
            }
            if (h == "localhost" || h.endsWith(".localhost") || h.endsWith(".home") || h.endsWith(".local") || h.endsWith(".internal") || h.endsWith(".lan") || h.contains(":") || Regex("""^\d{1,3}(\.\d{1,3}){3}$""").matches(h)) { error("Direct/local host blocked: $h") }
            if (!HardwareKeystore.isTorProxyEnabled(context)) {
                val addrs = java.net.InetAddress.getAllByName(h)
                if (addrs.any { it.isLoopbackAddress || it.isSiteLocalAddress || it.isLinkLocalAddress || it.isAnyLocalAddress || it.isMulticastAddress }) {
                    error("Private/local IP blocked: $h")
                }
            }
        }

        fun fetchBytes(targetUrl: String): Pair<String, ByteArray> {
            var currentUrl = targetUrl
            repeat(4) {
                validateSafeUrl(currentUrl)
                val rb = Request.Builder().url(currentUrl).header("User-Agent", "Mozilla/5.0").get()
                if (currentUrl.contains("filebin.net")) rb.header("Cookie", "verified=2024-05-24")
                client.newCall(rb.build()).execute().use { resp ->
                    if (resp.code in 301..308) {
                        val loc = resp.header("Location") ?: error("Empty redirect")
                        currentUrl = java.net.URI(currentUrl).resolve(loc).toString()
                        return@use
                    }
                    if (!resp.request.url.isHttps) error("Insecure redirect blocked")
                    if (!resp.isSuccessful) error("فشل تنزيل الملف (HTTP ${resp.code})")
                    val ct = resp.header("Content-Type").orEmpty()
                    val body = resp.body ?: error("محتوى فارغ")
                    val total = body.contentLength()
                    if (total > maxMediaBytes) error("حجم الملف يتجاوز 25 MB")
                    val bos = java.io.ByteArrayOutputStream()
                    val buf = ByteArray(16_384)
                    var downloaded = 0L
                    body.byteStream().use { ins ->
                        while (true) {
                            val r = ins.read(buf)
                            if (r == -1) break
                            downloaded += r
                            if (downloaded > maxMediaBytes) error("حجم الملف يتجاوز 25 MB")
                            bos.write(buf, 0, r)
                            if (total > 0) onProgress((downloaded.toFloat() / total.toFloat()).coerceIn(0.05f, 0.90f))
                        }
                    }
                    return ct to bos.toByteArray()
                }
            }
            error("Too many redirects")
        }

        val fixedUrl = downloadUrl.replace("/dl/dl/", "/dl/")
        validateSafeUrl(fixedUrl)
        val (ct1, bytes1) = fetchBytes(fixedUrl)
        val encryptedBytes = if (ct1.contains("text/html", ignoreCase = true) ||
            (bytes1.size > 15 && String(bytes1, 0, 15, Charsets.UTF_8).trimStart().let { it.startsWith("<!DOCTYPE", true) || it.startsWith("<html", true) })
        ) {
            val html = String(bytes1, Charsets.UTF_8)
            val realUrl = Regex("""href="(https?://tmpfiles\.org/dl/[^"]+)"""").find(html)?.groupValues?.get(1)
                ?: error("انتهت صلاحية رابط الملف")
            if (!realUrl.startsWith("https://tmpfiles.org/dl/")) error("Untrusted extracted media host"); fetchBytes(realUrl).second
        } else bytes1

        val key = Base64.decode(keyB64, Base64.NO_WRAP)
        val plain = NativeBridgeV3.openDtnBody(key, encryptedBytes) ?: error("فشل فك تشفير الملف")
        outFile.writeBytes(plain)
        onProgress(1.0f)
        outFile
    }
}