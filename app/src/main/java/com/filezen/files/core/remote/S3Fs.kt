package com.filezen.files.core.remote

import android.util.Xml
import org.xmlpull.v1.XmlPullParser
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * S3 backend with hand-written AWS SigV4 signing — ListObjectsV2 for listing
 * (delimiter="/" gives CommonPrefixes as dirs), GET/PUT/DELETE on objects.
 * [RemoteConnection.root] = "bucket" or "bucket/prefix"; user = access key id,
 * pass = secret access key; extra = region (default us-east-1); host may be a
 * custom endpoint (S3-compatible) — when host is set, path-style URLs are used.
 */
class S3Fs(private val c: RemoteConnection) : RemoteFs {

    private val region = c.extra.ifBlank { "us-east-1" }
    private val bucket = c.root.trim('/').substringBefore('/')
    private val prefix = c.root.trim('/').substringAfter('/', "")
    private val endpoint = if (c.host.isNotBlank()) c.host.trimEnd('/')
        else "https://s3.$region.amazonaws.com"

    private fun sha256Hex(b: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(b)
            .joinToString("") { "%02x".format(it) }

    private fun hmac(key: ByteArray, data: String): ByteArray =
        Mac.getInstance("HmacSHA256").apply {
            init(SecretKeySpec(key, "HmacSHA256"))
        }.doFinal(data.toByteArray(Charsets.UTF_8))

    private val amzFmt = SimpleDateFormat("yyyyMMdd'T'HHmmss'Z'", Locale.US)
        .apply { timeZone = TimeZone.getTimeZone("UTC") }
    private val dayFmt = SimpleDateFormat("yyyyMMdd", Locale.US)
        .apply { timeZone = TimeZone.getTimeZone("UTC") }

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")
        .replace("+", "%20").replace("%7E", "~")

    /** Sign + open a connection. [queryPairs] must be unencoded values. */
    private fun signed(method: String, key: String,
                       queryPairs: List<Pair<String, String>> = emptyList(),
                       body: ByteArray? = null): HttpURLConnection {
        val now = Date()
        val amzDate = amzFmt.format(now)
        val day = dayFmt.format(now)
        // Path-style URL: endpoint/bucket/key
        val path = "/" + listOf(bucket, prefix + key).filter { it.isNotEmpty() }
            .joinToString("/") { part -> part.split('/').joinToString("/") { enc(it) } }
        val qs = queryPairs.sortedBy { it.first }
            .joinToString("&") { "${enc(it.first)}=${enc(it.second)}" }
        val payloadHash = sha256Hex(body ?: ByteArray(0))
        val host = URL(endpoint).host

        val canonHeaders = "host:$host\nx-amz-content-sha256:$payloadHash\nx-amz-date:$amzDate\n"
        val signedHeaders = "host;x-amz-content-sha256;x-amz-date"
        val canonical = "$method\n$path\n$qs\n$canonHeaders\n$signedHeaders\n$payloadHash"
        val scope = "$day/$region/s3/aws4_request"
        val toSign = "AWS4-HMAC-SHA256\n$amzDate\n$scope\n${sha256Hex(canonical.toByteArray())}"
        val key = hmac("AWS4${c.pass}".toByteArray(), day)
            .let { hmac(it, region) }.let { hmac(it, "s3") }.let { hmac(it, "aws4_request") }
        val sig = hmac(key, toSign).joinToString("") { "%02x".format(it) }

        val url = endpoint + path + if (qs.isEmpty()) "" else "?$qs"
        return (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = 15000
            readTimeout = 60000
            setRequestProperty("x-amz-date", amzDate)
            setRequestProperty("x-amz-content-sha256", payloadHash)
            setRequestProperty("Authorization",
                "AWS4-HMAC-SHA256 Credential=${c.user}/$scope, SignedHeaders=$signedHeaders, Signature=$sig")
            if (body != null) {
                doOutput = true
                setFixedLengthStreamingMode(body.size)
                outputStream.use { it.write(body) }
            }
        }
    }

    private fun check(h: HttpURLConnection, op: String, vararg ok: Int) {
        if (h.responseCode !in ok.toSet()) {
            val err = runCatching {
                h.errorStream?.bufferedReader()?.readText()?.take(400)
            }.getOrNull()
            throw FsException("$op failed: HTTP ${h.responseCode} ${err ?: ""}")
        }
    }

    override fun list(path: String): List<RemoteEntry> {
        val p = prefix + path.trim('/') .let { if (it.isEmpty()) "" else "$it/" }
        val h = signed("GET", "", listOf(
            "list-type" to "2", "delimiter" to "/", "prefix" to p, "max-keys" to "1000"))
        check(h, "ListObjects", 200)
        val xml: XmlPullParser = Xml.newPullParser()
        xml.setInput(h.inputStream, "UTF-8")
        val out = ArrayList<RemoteEntry>()
        var tag = ""
        var inCommon = false
        var key = ""
        var size = 0L
        var mtime = 0L
        var ev = xml.eventType
        val isoFmt = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US)
            .apply { timeZone = TimeZone.getTimeZone("UTC") }
        fun flushKey() {
            if (key.isEmpty()) return
            val name = key.removePrefix(p).trimEnd('/')
            if (name.isNotEmpty()) out += RemoteEntry(name,
                RemoteFs.joinPath(path, name), false, size, mtime)
            key = ""; size = 0; mtime = 0
        }
        while (ev != XmlPullParser.END_DOCUMENT) {
            when (ev) {
                XmlPullParser.START_TAG -> {
                    tag = xml.name
                    if (tag == "CommonPrefixes") inCommon = true
                }
                XmlPullParser.END_TAG -> when (xml.name) {
                    "CommonPrefixes" -> inCommon = false
                    "Contents" -> flushKey()
                }
                XmlPullParser.TEXT -> when (tag) {
                    "Key" -> if (!inCommon) key = xml.text else {}
                    "Prefix" -> if (inCommon && xml.text != null) {
                        val name = xml.text.removePrefix(p).trimEnd('/')
                        if (name.isNotEmpty()) out += RemoteEntry(name,
                            RemoteFs.joinPath(path, name), true, 0, 0)
                    }
                    "Size" -> size = xml.text?.toLongOrNull() ?: 0
                    "LastModified" -> mtime = runCatching {
                        isoFmt.parse(xml.text.take(19))?.time ?: 0 }.getOrDefault(0)
                }
            }
            ev = xml.next()
        }
        h.disconnect()
        out.sortWith(compareBy({ !it.isDir }, { it.name.lowercase() }))
        return out
    }

    override fun stat(path: String): RemoteEntry? {
        val h = signed("HEAD", path)
        return if (h.responseCode == 200) {
            val e = RemoteEntry(path.substringAfterLast('/'), path, false,
                h.getHeaderFieldLong("Content-Length", 0),
                h.getHeaderFieldDate("Last-Modified", 0))
            h.disconnect(); e
        } else { h.disconnect(); null }
    }

    override fun openInput(path: String): InputStream {
        val h = signed("GET", path)
        check(h, "GET", 200)
        return h.inputStream
    }

    override fun write(path: String, input: InputStream, len: Long) {
        val bytes = input.readBytes()
        val h = signed("PUT", path, emptyList(), bytes)
        check(h, "PUT", 200, 201)
        h.disconnect()
    }

    override fun mkdir(path: String) {
        // S3 "directories" are zero-byte keys ending in /
        val h = signed("PUT", path.trimEnd('/') + "/", emptyList(), ByteArray(0))
        check(h, "MKDIR", 200)
        h.disconnect()
    }

    override fun delete(path: String, isDir: Boolean) {
        val h = signed("DELETE", if (isDir) path.trimEnd('/') + "/" else path)
        check(h, "DELETE", 204, 200, 404)
        h.disconnect()
    }

    override fun rename(from: String, to: String) {
        // S3 has no rename: copy object (x-amz-copy-source) then delete source.
        val copySource = enc("$bucket/${prefix}$from")
        val h = signedWithCopySource(to, copySource)
        check(h, "COPY", 200)
        h.disconnect()
        delete(from, false)
    }

    private fun signedWithCopySource(to: String,
                                     copySource: String): HttpURLConnection {
        val now = Date()
        val amzDate = amzFmt.format(now); val day = dayFmt.format(now)
        val path = "/" + listOf(bucket, prefix + to).filter { it.isNotEmpty() }
            .joinToString("/") { part -> part.split('/').joinToString("/") { enc(it) } }
        val payloadHash = sha256Hex(ByteArray(0))
        val host = URL(endpoint).host
        val canonHeaders = "host:$host\nx-amz-content-sha256:$payloadHash\n" +
            "x-amz-copy-source:$copySource\nx-amz-date:$amzDate\n"
        val signedHeaders = "host;x-amz-content-sha256;x-amz-copy-source;x-amz-date"
        val canonical = "PUT\n$path\n\n$canonHeaders\n$signedHeaders\n$payloadHash"
        val scope = "$day/$region/s3/aws4_request"
        val toSign = "AWS4-HMAC-SHA256\n$amzDate\n$scope\n${sha256Hex(canonical.toByteArray())}"
        val key = hmac("AWS4${c.pass}".toByteArray(), day)
            .let { hmac(it, region) }.let { hmac(it, "s3") }.let { hmac(it, "aws4_request") }
        val sig = hmac(key, toSign).joinToString("") { "%02x".format(it) }
        return (URL("$endpoint$path").openConnection() as HttpURLConnection).apply {
            requestMethod = "PUT"
            setRequestProperty("x-amz-date", amzDate)
            setRequestProperty("x-amz-content-sha256", payloadHash)
            setRequestProperty("x-amz-copy-source", copySource)
            setRequestProperty("Authorization",
                "AWS4-HMAC-SHA256 Credential=${c.user}/$scope, SignedHeaders=$signedHeaders, Signature=$sig")
        }
    }
}
