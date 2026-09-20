package com.filezen.files.core.transfer

import android.os.Environment
import fi.iki.elonen.NanoHTTPD
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.File
import java.io.FileInputStream
import java.net.NetworkInterface
import java.net.URLConnection
import java.net.URLDecoder
import java.net.URLEncoder
import java.security.SecureRandom

/**
 * Phone ↔ PC transfer: serves a small web UI over the LAN so any browser on the
 * same Wi-Fi can upload files into — or download files from — the shared folder.
 * All routes live under a random per-launch token so other devices on the
 * network can't guess the URL.
 */
class TransferServer(
    val shareDir: File = File(
        Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
        "FileZen Share"),
    private val port: Int = 8765,
) {
    data class State(
        val running: Boolean = false,
        val url: String? = null,
        val error: String? = null,
    )

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state

    private var http: Server? = null
    private val token = buildString {
        val alphabet = "abcdefghjkmnpqrstuvwxyz23456789"
        val rnd = SecureRandom()
        repeat(6) { append(alphabet[rnd.nextInt(alphabet.length)]) }
    }

    val isRunning: Boolean get() = http?.isAlive == true

    /** Best-effort Wi-Fi/LAN IPv4 address of this device. */
    fun lanIp(): String? {
        runCatching {
            val ifaces = NetworkInterface.getNetworkInterfaces().toList()
            // Prefer common wifi/eth interfaces, then any site-local IPv4.
            val sorted = ifaces.sortedByDescending {
                it.name.startsWith("wlan") || it.name.startsWith("eth")
            }
            for (iface in sorted) {
                if (!iface.isUp || iface.isLoopback) continue
                for (addr in iface.inetAddresses.toList()) {
                    val host = addr.hostAddress ?: continue
                    if (addr.isLoopbackAddress || ':' in host) continue
                    if (addr.isSiteLocalAddress ||
                        host.startsWith("192.168.") || host.startsWith("10.") ||
                        host.startsWith("172.")) return host
                }
            }
            for (iface in ifaces) {
                if (!iface.isUp || iface.isLoopback) continue
                for (addr in iface.inetAddresses.toList()) {
                    val host = addr.hostAddress ?: continue
                    if (!addr.isLoopbackAddress && ':' !in host) return host
                }
            }
        }
        return null
    }

    @Synchronized
    fun start(): Result<String> = runCatching {
        if (isRunning) return@runCatching _state.value.url!!
        shareDir.mkdirs()
        if (!shareDir.isDirectory) error("Shared folder unavailable")
        val ip = lanIp() ?: error("No Wi-Fi connection")
        val server = Server()
        server.start(NanoHTTPD.SOCKET_READ_TIMEOUT, false)
        http = server
        val url = "http://$ip:$port/t/$token/"
        _state.value = State(running = true, url = url)
        url
    }.onFailure { e ->
        _state.value = State(running = false, url = null, error = e.message)
    }

    @Synchronized
    fun stop() {
        runCatching { http?.stop() }
        http = null
        _state.value = State(running = false, url = null)
    }

    // ---------- HTTP ----------

    private inner class Server : NanoHTTPD(port) {
        override fun serve(session: IHTTPSession): Response {
            val uri = session.uri ?: "/"
            val prefix = "/t/$token"
            if (uri == "/" || uri == prefix) return redirect("$prefix/")
            if (!uri.startsWith("$prefix/"))
                return fixed(Response.Status.NOT_FOUND, "Not found")
            val rest = uri.removePrefix("$prefix/").removeSuffix("/")
            return try {
                when {
                    session.method == Method.GET && rest.isEmpty() -> page()
                    session.method == Method.GET && rest == "list" -> jsonList()
                    session.method == Method.GET && rest.startsWith("d/") ->
                        download(dec(rest.removePrefix("d/")))
                    session.method == Method.POST && rest == "upload" -> upload(session)
                    session.method == Method.POST && rest == "del" -> delete(session)
                    session.method == Method.PUT && rest.startsWith("put/") ->
                        putFile(session, dec(rest.removePrefix("put/")))
                    else -> fixed(Response.Status.NOT_FOUND, "Not found")
                }
            } catch (e: Exception) {
                fixed(Response.Status.INTERNAL_ERROR, "Error: ${e.message}")
            }
        }

        private fun fixed(status: Response.Status, body: String): Response =
            newFixedLengthResponse(status, MIME_PLAINTEXT, body)

        private fun redirect(to: String): Response {
            val r = newFixedLengthResponse(Response.Status.REDIRECT, MIME_HTML, "")
            r.addHeader("Location", to)
            return r
        }

        private fun safeFile(name: String): File? {
            val clean = name.substringAfterLast('/').substringAfterLast('\\')
            if (clean.isBlank()) return null
            val f = File(shareDir, clean)
            return if (f.canonicalFile.parentFile == shareDir.canonicalFile) f else null
        }

        private fun download(name: String): Response {
            val f = safeFile(name)?.takeIf { it.isFile }
                ?: return fixed(Response.Status.NOT_FOUND, "No such file")
            val mime = URLConnection.guessContentTypeFromName(f.name)
                ?: "application/octet-stream"
            return newFixedLengthResponse(
                Response.Status.OK, mime, FileInputStream(f), f.length()
            ).apply { addHeader("Content-Disposition",
                "attachment; filename*=UTF-8''${enc(f.name)}") }
        }

        private fun upload(session: IHTTPSession): Response {
            val files = HashMap<String, String>()
            session.parseBody(files)
            if (files.isEmpty())
                return fixed(Response.Status.BAD_REQUEST, "no file")
            val params = session.parameters
            val saved = mutableListOf<String>()
            for ((param, tmpPath) in files) {
                // NanoHTTPD puts the client filename in parameters[param].
                val rawName = params[param]?.firstOrNull()
                val fname = rawName?.substringAfterLast('/')
                    ?.substringAfterLast('\\')?.ifBlank { null }
                    ?: "upload-${System.currentTimeMillis()}"
                val dest = unique(safeFile(fname) ?: continue)
                val tmp = File(tmpPath)
                if (!tmp.renameTo(dest)) runCatching {
                    tmp.copyTo(dest, overwrite = false)
                }
                tmp.delete()
                if (dest.isFile) saved.add(dest.name)
            }
            return json("""{"ok":true,"saved":${saved.size}}""")
        }

        private fun putFile(session: IHTTPSession, name: String): Response {
            val dest = unique(safeFile(name)
                ?: return fixed(Response.Status.BAD_REQUEST, "bad name"))
            val files = HashMap<String, String>()
            session.parseBody(files)
            val tmp = files["content"]?.let { File(it) }
                ?: return fixed(Response.Status.BAD_REQUEST, "empty body")
            if (!tmp.renameTo(dest)) runCatching { tmp.copyTo(dest, overwrite = false) }
            tmp.delete()
            return fixed(Response.Status.OK, "saved ${dest.name}\n")
        }

        private fun delete(session: IHTTPSession): Response {
            // urlencoded POST params are only parsed by parseBody().
            session.parseBody(HashMap<String, String>())
            val name = session.parameters["name"]?.firstOrNull()
                ?: return fixed(Response.Status.BAD_REQUEST, "missing name")
            val f = safeFile(dec(name))
                ?: return fixed(Response.Status.NOT_FOUND, "no such file")
            f.delete()
            return json("""{"ok":true}""")
        }

        private fun jsonList(): Response {
            val items = (shareDir.listFiles() ?: emptyArray())
                .filter { it.isFile }
                .sortedByDescending { it.lastModified() }
                .joinToString(",") { f ->
                    """{"name":"${escJson(f.name)}","size":${f.length()},"mtime":${f.lastModified()}}"""
                }
            return json("[$items]")
        }

        private fun json(body: String): Response =
            newFixedLengthResponse(Response.Status.OK, "application/json", body)

        private fun unique(f: File): File {
            if (!f.exists()) return f
            val base = f.nameWithoutExtension
            val ext = f.extension.let { if (it.isEmpty()) "" else ".$it" }
            var i = 1
            while (true) {
                val cand = File(f.parentFile, "$base ($i)$ext")
                if (!cand.exists()) return cand
                i++
            }
        }

        private fun dec(s: String): String =
            runCatching { URLDecoder.decode(s, "UTF-8") }.getOrDefault(s)

        private fun enc(s: String): String =
            URLEncoder.encode(s, "UTF-8").replace("+", "%20")

        private fun escJson(s: String): String =
            s.replace("\\", "\\\\").replace("\"", "\\\"")

        private fun page(): Response {
            val body = PAGE.replace("__TOKEN__", "/t/$token")
            return newFixedLengthResponse(Response.Status.OK, MIME_HTML, body)
        }
    }

    companion object {
        private val PAGE = """
<!doctype html><html><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1">
<title>FileZen Transfer</title>
<style>
:root{color-scheme:dark}
*{box-sizing:border-box;font-family:system-ui,-apple-system,sans-serif}
body{margin:0;background:#0a0f14;color:#e6edf3;min-height:100vh;
display:flex;flex-direction:column;align-items:center;padding:24px 16px}
.card{width:100%;max-width:640px;background:#121a22;border:1px solid #22303d;
border-radius:20px;padding:22px;margin-bottom:16px}
h1{font-size:20px;margin:0 0 4px;display:flex;align-items:center;gap:10px}
.logo{width:30px;height:30px;border-radius:9px;background:linear-gradient(135deg,#4fd1c5,#7c5cf0);
display:inline-flex;align-items:center;justify-content:center;font-size:16px}
.sub{color:#8b9aa8;font-size:13px;margin:0}
#drop{border:2px dashed #33475a;border-radius:16px;padding:34px 16px;text-align:center;
color:#8b9aa8;cursor:pointer;transition:.2s;margin-top:14px}
#drop.over{border-color:#4fd1c5;background:#4fd1c51a;color:#e6edf3}
#bar{height:6px;background:#22303d;border-radius:3px;margin-top:12px;overflow:hidden;display:none}
#bar i{display:block;height:100%;width:0%;background:linear-gradient(90deg,#4fd1c5,#7c5cf0);transition:width .15s}
.row{display:flex;align-items:center;gap:12px;padding:11px 4px;border-bottom:1px solid #1b2632;font-size:14px}
.row:last-child{border-bottom:none}
.row .nm{flex:1;overflow:hidden;text-overflow:ellipsis;white-space:nowrap}
.row .sz{color:#8b9aa8;font-size:12px;min-width:70px;text-align:right}
a.btn{color:#4fd1c5;text-decoration:none;font-size:13px;padding:6px 10px;
border:1px solid #2a3b4a;border-radius:10px}
a.btn:hover{background:#4fd1c51a}
button.del{background:none;border:1px solid #3d2a2a;color:#f08a8a;border-radius:10px;
font-size:13px;padding:6px 10px;cursor:pointer}
button.del:hover{background:#f08a8a1a}
.empty{color:#5a6a78;text-align:center;padding:26px 0;font-size:14px}
input[type=file]{display:none}
</style></head><body>
<div class="card">
<h1><span class="logo">⚡</span> FileZen Transfer</h1>
<p class="sub">Files land in <b>Download/FileZen Share</b> on the phone.</p>
<div id="drop">Drop files here or click to choose
<input id="pick" type="file" multiple></div>
<div id="bar"><i></i></div>
</div>
<div class="card"><h1 style="font-size:16px">Shared files</h1><div id="list"></div></div>
<script>
const T="__TOKEN__";
const $=s=>document.querySelector(s);
function fmt(n){if(n<1024)return n+" B";if(n<1048576)return(n/1024).toFixed(1)+" KB";
if(n<1073741824)return(n/1048576).toFixed(1)+" MB";return(n/1073741824).toFixed(2)+" GB"}
function esc(s){const d=document.createElement("div");d.textContent=s;return d.innerHTML}
async function refresh(){
const r=await fetch(T+"/list");const fs=await r.json();
$("#list").innerHTML=fs.length?fs.map(f=>
'<div class="row"><span class="nm">'+esc(f.name)+'</span><span class="sz">'+fmt(f.size)+'</span>'+
'<a class="btn" href="'+T+'/d/'+encodeURIComponent(f.name)+'">Download</a>'+
'<button class="del" onclick="del(\''+encodeURIComponent(f.name)+'\')">Delete</button></div>'
).join(""):'<div class="empty">No files yet — upload something!</div>';}
async function del(n){await fetch(T+"/del",{method:"POST",
headers:{"Content-Type":"application/x-www-form-urlencoded"},body:"name="+n});refresh();}
function upload(files){
const bar=$("#bar"),fill=bar.firstElementChild;
let i=0;(async()=>{for(const f of files){const fd=new FormData();fd.append("file",f);
await new Promise((res,rej)=>{const x=new XMLHttpRequest();x.open("POST",T+"/upload");
x.upload.onprogress=e=>{if(e.lengthComputable){bar.style.display="block";
fill.style.width=Math.round(e.loaded/e.total*100)+"%"}};
x.onload=res;x.onerror=rej;x.send(fd);});}
bar.style.display="none";fill.style.width="0";refresh();})();}
const drop=$("#drop"),pick=$("#pick");
drop.onclick=()=>pick.click();
pick.onchange=()=>{upload(pick.files);pick.value="";};
["dragover","dragleave","drop"].forEach(ev=>drop.addEventListener(ev,e=>{
e.preventDefault();drop.classList.toggle("over",ev==="dragover");
if(ev==="drop")upload(e.dataTransfer.files);}));
refresh();
</script></body></html>
""".trimIndent()
    }
}
