package com.filezen.files.core.remote

import android.util.Xml
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.xmlpull.v1.XmlPullParser
import java.io.InputStream
import java.net.URI
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

/**
 * Hand-written WebDAV client — PROPFIND Depth:1 for listing, GET/PUT/DELETE/
 * MKCOL/MOVE for mutations. OkHttp because HttpURLConnection refuses
 * non-standard verbs like PROPFIND.
 * [RemoteConnection.root] may carry a base path (e.g. "/remote.php/dav/files/me").
 */
class WebDavFs(private val c: RemoteConnection) : RemoteFs {

    private val base: String = run {
        val scheme = if (c.port == 443 || c.extra.equals("https", true)) "https" else "http"
        val b = StringBuilder("$scheme://${c.host}:${c.port}")
        val root = c.root.trim()
        if (root.isNotEmpty()) b.append(if (root.startsWith("/")) root else "/$root")
        b.toString().trimEnd('/')
    }

    private val http = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    private fun urlFor(path: String): String =
        base + "/" + path.trim('/').split('/')
            .joinToString("/") { URLEncoder.encode(it, "UTF-8").replace("+", "%20") }

    private fun req(url: String, method: String, body: ByteArray? = null,
                    headers: Map<String, String> = emptyMap()): Response {
        val b = Request.Builder().url(url).method(
            method,
            body?.toRequestBody("application/octet-stream".toMediaType()))
        if (c.user.isNotEmpty() || c.pass.isNotEmpty()) {
            val tok = android.util.Base64.encodeToString(
                "${c.user}:${c.pass}".toByteArray(), android.util.Base64.NO_WRAP)
            b.header("Authorization", "Basic $tok")
        }
        headers.forEach { (k, v) -> b.header(k, v) }
        if (body != null) b.header("Content-Type", "text/xml; charset=utf-8")
        return http.newCall(b.build()).execute()
    }

    private fun check(r: Response, op: String, ok: Set<Int>) {
        if (r.code !in ok) {
            r.close()
            throw FsException("$op failed: HTTP ${r.code} — ${r.message}")
        }
    }

    private val propfindBody = """<?xml version="1.0" encoding="utf-8" ?>
<D:propfind xmlns:D="DAV:"><D:prop>
<D:displayname/><D:getcontentlength/><D:getlastmodified/><D:resourcetype/>
</D:prop></D:propfind>""".toByteArray()

    override fun list(path: String): List<RemoteEntry> {
        val r = req(urlFor(path), "PROPFIND", propfindBody, mapOf("Depth" to "1"))
        check(r, "PROPFIND", setOf(207))
        val entries = r.body!!.byteStream().use { parseMultistatus(it, path) }
        r.close()
        return entries
    }

    private fun parseMultistatus(input: InputStream, requested: String): List<RemoteEntry> {
        val p: XmlPullParser = Xml.newPullParser()
        p.setInput(input, "UTF-8")
        val out = ArrayList<RemoteEntry>()
        var href = ""
        var name = ""
        var size = 0L
        var mtime = 0L
        var isDir = false
        var tag = ""

        fun flush() {
            val norm = try { URI(href).path.trimEnd('/') } catch (e: Exception) { href.trimEnd('/') }
            val reqNorm = try { URI(urlFor(requested)).path.trimEnd('/') }
                catch (e: Exception) { ("/" + requested.trim('/')).trimEnd('/') }
            if (norm.isNotEmpty() && norm != reqNorm) {
                val nm = name.ifBlank { norm.substringAfterLast('/') }
                out += RemoteEntry(nm,
                    RemoteFs.joinPath(requested.trim('/'), nm), isDir, size, mtime)
            }
            href = ""; name = ""; size = 0; mtime = 0; isDir = false
        }

        fun parseDate(s: String): Long = runCatching {
            java.time.format.DateTimeFormatter.RFC_1123_DATE_TIME.parse(s.trim(),
                java.time.Instant::from).toEpochMilli()
        }.getOrElse {
            runCatching { java.time.Instant.parse(s.trim()).toEpochMilli() }.getOrDefault(0L)
        }

        var ev = p.eventType
        while (ev != XmlPullParser.END_DOCUMENT) {
            when (ev) {
                XmlPullParser.START_TAG -> {
                    tag = p.name.substringAfter(':')
                    when (tag) {
                        "href" -> href = p.nextText().trim()
                        "displayname" -> name = p.nextText().trim()
                        "getcontentlength" -> size = p.nextText().trim().toLongOrNull() ?: 0
                        "getlastmodified" -> mtime = parseDate(p.nextText())
                        "collection" -> isDir = true
                    }
                }
                XmlPullParser.END_TAG -> {
                    if (p.name.substringAfter(':') == "response") flush()
                }
            }
            ev = p.next()
        }
        out.sortWith(compareBy({ !it.isDir }, { it.name.lowercase() }))
        return out
    }

    override fun stat(path: String): RemoteEntry? {
        val r = req(urlFor(path), "PROPFIND", propfindBody, mapOf("Depth" to "0"))
        if (r.code != 207) { r.close(); return null }
        val single = r.body!!.byteStream().use { parseMultistatus(it, path) }
        r.close()
        return single.firstOrNull()
            ?: RemoteEntry(path.substringAfterLast('/'), path, path.endsWith("/"), 0, 0)
    }

    override fun openInput(path: String): InputStream {
        val r = req(urlFor(path), "GET")
        check(r, "GET", setOf(200, 206))
        // Keep the response open; the caller closes the stream.
        val body = r.body!!
        return object : java.io.FilterInputStream(body.byteStream()) {
            override fun close() {
                super.close()
                r.close()
            }
        }
    }

    override fun write(path: String, input: InputStream, len: Long) {
        val bytes = input.readBytes()
        val r = req(urlFor(path), "PUT", bytes)
        check(r, "PUT", setOf(200, 201, 204))
        r.close()
    }

    override fun mkdir(path: String) {
        val r = req(urlFor(path), "MKCOL")
        check(r, "MKCOL", setOf(200, 201, 204, 405)) // 405 = already exists
        r.close()
    }

    override fun delete(path: String, isDir: Boolean) {
        val r = req(urlFor(path) + if (isDir) "/" else "", "DELETE")
        check(r, "DELETE", setOf(200, 202, 204, 404))
        r.close()
    }

    override fun rename(from: String, to: String) {
        val r = req(urlFor(from), "MOVE", null,
            mapOf("Destination" to urlFor(to), "Overwrite" to "T"))
        check(r, "MOVE", setOf(200, 201, 204, 412))
        r.close()
    }

    override fun close() {
        http.dispatcher.executorService.shutdown()
    }
}
