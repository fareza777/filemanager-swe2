package com.filezen.files.core.transfer

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.os.Environment
import fi.iki.elonen.NanoHTTPD
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.net.NetworkInterface
import java.net.URLConnection
import java.net.URLDecoder
import java.net.URLEncoder
import java.security.SecureRandom
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Phone ↔ PC transfer: serves a full file-manager web UI over the LAN so any
 * browser on the same Wi-Fi can browse the phone's storage, upload into the
 * current folder, download files (singly or zipped in bulk), and delete.
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

    data class NetAddr(val ip: String, val kind: String)

    /**
     * Every usable IPv4 on the device, labelled: "Wi-Fi", "Hotspot" (AP
     * interfaces — the phone is sharing its own network), or "VPN"
     * (tun/tailscale/wireguard — reachable from devices on that overlay,
     * e.g. Tailscale, even across different real networks).
     */
    fun lanAddrs(): List<NetAddr> {
        val out = LinkedHashMap<String, NetAddr>()
        runCatching {
            for (iface in NetworkInterface.getNetworkInterfaces().toList()) {
                if (!iface.isUp || iface.isLoopback) continue
                val n = iface.name.lowercase()
                for (addr in iface.inetAddresses.toList()) {
                    val host = addr.hostAddress ?: continue
                    if (addr.isLoopbackAddress || ':' in host) continue
                    val kind = when {
                        n.startsWith("ap") || n.contains("swlan") ||
                            host.startsWith("192.168.43.") -> "Hotspot"
                        n.startsWith("tun") || n.startsWith("tailscale") ||
                            n.startsWith("wg") || n.startsWith("ppp") -> "VPN"
                        n.startsWith("wlan") || n.startsWith("eth") -> "Wi-Fi"
                        addr.isSiteLocalAddress -> "LAN"
                        else -> continue   // cellular/public IPs aren't reachable inbound
                    }
                    out.putIfAbsent(host, NetAddr(host, kind))
                }
            }
        }
        // Best first: Wi-Fi, Hotspot, VPN, then anything else.
        val rank = mapOf("Wi-Fi" to 0, "Hotspot" to 1, "VPN" to 2, "LAN" to 3)
        return out.values.sortedBy { rank[it.kind] ?: 4 }
    }

    /** Primary address for the URL; null when there's no local network at all. */
    fun lanIp(): String? = lanAddrs().firstOrNull()?.ip

    /** Suggested starting points shown as chips on the web page. */
    fun roots(): List<Pair<String, String>> {
        val pub = { name: String ->
            Environment.getExternalStoragePublicDirectory(name).absolutePath }
        return buildList {
            add("Internal storage" to Environment.getExternalStorageDirectory().absolutePath)
            add("FileZen Share" to shareDir.absolutePath)
            add("Download" to pub(Environment.DIRECTORY_DOWNLOADS))
            add("Documents" to pub(Environment.DIRECTORY_DOCUMENTS))
            add("Pictures" to pub(Environment.DIRECTORY_PICTURES))
            add("DCIM" to pub(Environment.DIRECTORY_DCIM))
            add("Movies" to pub(Environment.DIRECTORY_MOVIES))
            add("Music" to pub(Environment.DIRECTORY_MUSIC))
        }.filter { File(it.second).isDirectory || it.first == "FileZen Share" }
    }

    @Synchronized
    fun start(): Result<String> = runCatching {
        if (isRunning) return@runCatching _state.value.url!!
        shareDir.mkdirs()
        if (!shareDir.isDirectory) error("Shared folder unavailable")
        val ip = lanIp() ?: error("No local network (Wi-Fi, hotspot or VPN)")
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
                    session.method == Method.GET && rest == "roots" -> jsonRoots()
                    session.method == Method.GET && rest == "list" ->
                        jsonList(session.parameters["path"]?.firstOrNull())
                    session.method == Method.GET && rest.startsWith("d/") ->
                        download(dec(rest.removePrefix("d/")))
                    session.method == Method.GET && rest.startsWith("thumb/") ->
                        thumb(dec(rest.removePrefix("thumb/")))
                    session.method == Method.GET && rest == "zip" ->
                        zip(session.parameters.getOrDefault("sel", emptyList()))
                    session.method == Method.POST && rest == "upload" -> upload(session)
                    session.method == Method.POST && rest == "mkdir" -> mkdir(session)
                    session.method == Method.POST && rest == "del" -> delete(session)
                    session.method == Method.POST && rest == "delall" -> deleteAll(session)
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

        // Path safety: everything served is under shared storage (/storage/...).
        private fun guard(path: String?): File? {
            if (path.isNullOrBlank()) return null
            val f = try { File(path).canonicalFile } catch (e: Exception) { return null }
            return if (f.absolutePath.startsWith("/storage/")) f else null
        }

        private fun safeFile(name: String): File? {
            val clean = name.substringAfterLast('/').substringAfterLast('\\')
            if (clean.isBlank()) return null
            val f = File(shareDir, clean)
            return if (f.canonicalFile.parentFile == shareDir.canonicalFile) f else null
        }

        private fun download(path: String): Response {
            val f = guard(path)?.takeIf { it.isFile }
                ?: return fixed(Response.Status.NOT_FOUND, "No such file")
            val mime = URLConnection.guessContentTypeFromName(f.name)
                ?: "application/octet-stream"
            return newFixedLengthResponse(
                Response.Status.OK, mime, FileInputStream(f), f.length()
            ).apply { addHeader("Content-Disposition",
                "attachment; filename*=UTF-8''${enc(f.name)}") }
        }

        /** 256px JPEG thumbnail for images & videos; 404 otherwise. */
        private fun thumb(path: String): Response {
            val f = guard(path)?.takeIf { it.isFile }
                ?: return fixed(Response.Status.NOT_FOUND, "No such file")
            val ext = f.extension.lowercase()
            val isImg = ext in listOf("jpg","jpeg","png","webp","gif","bmp","heic","heif")
            val isVid = ext in listOf("mp4","mkv","webm","3gp","mov","avi","m4v")
            val bmp = when {
                isImg -> {
                    val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                    BitmapFactory.decodeFile(f.absolutePath, o)
                    if (o.outWidth <= 0) null else {
                        var s = 1
                        while (o.outWidth / s > 512 || o.outHeight / s > 512) s *= 2
                        BitmapFactory.decodeFile(f.absolutePath,
                            BitmapFactory.Options().apply { inSampleSize = s })
                    }
                }
                isVid -> runCatching {
                    val r = MediaMetadataRetriever()
                    r.setDataSource(f.absolutePath)
                    val b = r.getFrameAtTime(0)
                    r.release()
                    b
                }.getOrNull()
                else -> null
            } ?: return fixed(Response.Status.NOT_FOUND, "no thumb")
            val w = bmp.width; val h = bmp.height
            val scale = 256f / maxOf(w, h)
            val scaled = if (scale < 1f)
                Bitmap.createScaledBitmap(bmp, (w*scale).toInt().coerceAtLeast(1),
                    (h*scale).toInt().coerceAtLeast(1), true) else bmp
            val bos = ByteArrayOutputStream()
            scaled.compress(Bitmap.CompressFormat.JPEG, 82, bos)
            return newFixedLengthResponse(Response.Status.OK, "image/jpeg",
                bos.toByteArray().inputStream(), bos.size().toLong())
        }

        /** Selected files (absolute paths) streamed back as one ZIP. */
        private fun zip(sels: List<String>): Response {
            val files = sels.mapNotNull { guard(dec(it)) }.filter { it.isFile }
            if (files.isEmpty()) return fixed(Response.Status.NOT_FOUND, "nothing to zip")
            if (files.sumOf { it.length() } > 700L * 1024 * 1024)
                return fixed(Response.Status.BAD_REQUEST, "selection too big (>700MB)")
            val bos = ByteArrayOutputStream()
            ZipOutputStream(bos).use { z ->
                files.forEach { f ->
                    z.putNextEntry(ZipEntry(f.name))
                    FileInputStream(f).use { it.copyTo(z) }
                    z.closeEntry()
                }
            }
            return newFixedLengthResponse(Response.Status.OK, "application/zip",
                bos.toByteArray().inputStream(), bos.size().toLong()
            ).apply { addHeader("Content-Disposition",
                "attachment; filename*=UTF-8''filezen-files.zip") }
        }

        private fun upload(session: IHTTPSession): Response {
            val files = HashMap<String, String>()
            session.parseBody(files)
            if (files.isEmpty())
                return fixed(Response.Status.BAD_REQUEST, "no file")
            val params = session.parameters
            // Optional target dir (the folder being browsed); defaults to shareDir.
            val destDir = params["path"]?.firstOrNull()?.let { dec(it) }
                ?.let { guard(it) }?.takeIf { it.isDirectory } ?: shareDir
            destDir.mkdirs()
            val saved = mutableListOf<String>()
            for ((param, tmpPath) in files) {
                // NanoHTTPD puts the client filename in parameters[param].
                val rawName = params[param]?.firstOrNull()
                val fname = rawName?.substringAfterLast('/')
                    ?.substringAfterLast('\\')?.ifBlank { null }
                    ?: "upload-${System.currentTimeMillis()}"
                val dest0 = File(destDir, fname).let { d ->
                    if (d.canonicalFile.parentFile == destDir.canonicalFile) d else null
                } ?: continue
                val dest = unique(dest0)
                val tmp = File(tmpPath)
                if (!tmp.renameTo(dest)) runCatching {
                    tmp.copyTo(dest, overwrite = false)
                }
                tmp.delete()
                if (dest.isFile) saved.add(dest.name)
            }
            return json("""{"ok":true,"saved":${saved.size}}""")
        }

        private fun mkdir(session: IHTTPSession): Response {
            session.parseBody(HashMap<String, String>())
            val parent = session.parameters["path"]?.firstOrNull()?.let { dec(it) }
                ?.let { guard(it) }?.takeIf { it.isDirectory }
                ?: return fixed(Response.Status.BAD_REQUEST, "bad dir")
            val name = session.parameters["name"]?.firstOrNull()
                ?.substringAfterLast('/')?.ifBlank { null }
                ?: return fixed(Response.Status.BAD_REQUEST, "missing name")
            val ok = File(parent, name).mkdirs() || File(parent, name).isDirectory
            return json("""{"ok":$ok}""")
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
            val path = session.parameters["path"]?.firstOrNull()
                ?: session.parameters["name"]?.firstOrNull()?.let { n ->
                    File(shareDir, n.substringAfterLast('/')).absolutePath }
                ?: return fixed(Response.Status.BAD_REQUEST, "missing path")
            val f = guard(dec(path))
                ?: return fixed(Response.Status.NOT_FOUND, "no such file")
            f.deleteRecursively()
            return json("""{"ok":true}""")
        }

        private fun deleteAll(session: IHTTPSession): Response {
            session.parseBody(HashMap<String, String>())
            val paths = session.parameters["paths"]?.firstOrNull()
                ?: return fixed(Response.Status.BAD_REQUEST, "missing paths")
            var n = 0
            for (p in paths.split("\n")) {
                if (p.isBlank()) continue
                val f = guard(dec(p)) ?: continue
                if (f.deleteRecursively()) n++
            }
            return json("""{"ok":true,"deleted":$n}""")
        }

        private fun jsonRoots(): Response = json(
            roots().joinToString(",", "[", "]") { (label, path) ->
                """{"name":"${escJson(label)}","path":"${escJson(path)}"}"""
            })

        private fun jsonList(pathParam: String?): Response {
            val dir = guard(pathParam?.let { dec(it) })?.takeIf { it.isDirectory }
                ?: shareDir.apply { mkdirs() }
            val parent = dir.canonicalFile.parentFile
                ?.takeIf { it.absolutePath.startsWith("/storage") }?.absolutePath
            val items = (dir.listFiles() ?: emptyArray())
                .filter { !it.isHidden }
                .sortedWith(compareByDescending<File> { it.isDirectory }
                    .thenBy { it.name.lowercase() })
                .joinToString(",") { f ->
                    """{"name":"${escJson(f.name)}","path":"${escJson(f.absolutePath)}","dir":${f.isDirectory},"size":${f.length()},"mtime":${f.lastModified()}}"""
                }
            return json("""{"path":"${escJson(dir.absolutePath)}","parent":${parent?.let { "\"${escJson(it)}\"" } ?: "null"},"items":[$items]}""")
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
body{margin:0;background:#0a0f14;color:#e6edf3;padding:16px}
.wrap{max-width:860px;margin:0 auto}
.card{background:#121a22;border:1px solid #22303d;border-radius:18px;padding:16px;margin-bottom:14px}
h1{font-size:19px;margin:0 0 2px;display:flex;align-items:center;gap:10px}
.logo{width:30px;height:30px;border-radius:9px;background:linear-gradient(135deg,#4fd1c5,#7c5cf0);
display:inline-flex;align-items:center;justify-content:center;font-size:16px}
.sub{color:#8b9aa8;font-size:12px;margin:0}
#crumbs{display:flex;flex-wrap:wrap;gap:2px;align-items:center;font-size:13px;margin:10px 0}
#crumbs a{color:#4fd1c5;text-decoration:none;padding:2px 4px;border-radius:6px}
#crumbs a:hover{background:#4fd1c522}
#crumbs b{color:#8b9aa8}
#roots{display:flex;gap:8px;flex-wrap:wrap;margin-top:10px}
.chip{background:#1a2632;border:1px solid #2a3b4a;color:#c8d4dd;border-radius:999px;
padding:6px 13px;font-size:12.5px;cursor:pointer}
.chip:hover,.chip.on{border-color:#4fd1c5;color:#4fd1c5;background:#4fd1c514}
.toolbar{display:flex;gap:8px;flex-wrap:wrap;align-items:center;margin-top:12px}
.btn{background:#1a2632;border:1px solid #2a3b4a;color:#e6edf3;border-radius:11px;
padding:8px 13px;font-size:13px;cursor:pointer;text-decoration:none;display:inline-flex;align-items:center;gap:6px}
.btn:hover{border-color:#4fd1c5}
.btn.primary{background:linear-gradient(135deg,#4fd1c5,#5b8cf0);color:#04231f;border:none;font-weight:600}
.btn.danger{color:#f08a8a}
.btn.danger:hover{border-color:#f08a8a;background:#f08a8a12}
.btn:disabled{opacity:.4;cursor:default}
#bar{height:5px;background:#22303d;border-radius:3px;margin-top:10px;overflow:hidden;display:none}
#bar i{display:block;height:100%;width:0%;background:linear-gradient(90deg,#4fd1c5,#7c5cf0);transition:width .15s}
.row{display:flex;align-items:center;gap:12px;padding:8px 6px;border-bottom:1px solid #16202b;font-size:14px;user-select:none}
.row:hover{background:#161f28}
.row:last-child{border-bottom:none}
.tn{width:40px;height:40px;border-radius:10px;object-fit:cover;background:#1a2632;flex:none}
.ico{width:40px;height:40px;border-radius:10px;background:#1a2632;display:flex;align-items:center;
justify-content:center;font-size:18px;flex:none}
.nm{flex:1;overflow:hidden;text-overflow:ellipsis;white-space:nowrap;cursor:pointer}
.dir .nm{color:#9fd8cf;font-weight:500}
.sz{color:#8b9aa8;font-size:12px;min-width:76px;text-align:right}
input[type=checkbox]{width:17px;height:17px;accent-color:#4fd1c5;cursor:pointer}
input[type=file]{display:none}
.empty{color:#5a6a78;text-align:center;padding:30px 0;font-size:14px}
#drop{border:2px dashed #33475a;border-radius:14px;padding:20px;text-align:center;
color:#8b9aa8;cursor:pointer;transition:.2s;margin-top:10px}
#drop.over{border-color:#4fd1c5;background:#4fd1c51a;color:#e6edf3}
</style></head><body><div class="wrap">
<div class="card">
<h1><span class="logo">⚡</span> FileZen Transfer</h1>
<p class="sub">Full phone storage, from your PC — same Wi-Fi only.</p>
<div id="roots"></div>
<div id="crumbs"></div>
<div class="toolbar">
<button class="btn" id="up">↑ Up</button>
<label class="btn"><input type="checkbox" id="all"> All</label>
<button class="btn" id="dl">⬇ Download zip</button>
<button class="btn danger" id="rm">🗑 Delete</button>
<button class="btn" id="mkdir">＋ Folder</button>
<label class="btn primary" for="pick">⬆ Upload here<input id="pick" type="file" multiple></label>
</div>
<div id="drop">…or drop files onto the list below</div>
<div id="bar"><i></i></div>
</div>
<div class="card" style="padding:8px 10px"><div id="list"></div></div>
<script>
const T="__TOKEN__";
const $=s=>document.querySelector(s);
let cwd="",items=[],sel=new Set();
function fmt(n){if(n<1024)return n+" B";if(n<1048576)return(n/1024).toFixed(1)+" KB";
if(n<1073741824)return(n/1048576).toFixed(1)+" MB";return(n/1073741824).toFixed(2)+" GB"}
function esc(s){const d=document.createElement("div");d.textContent=s;return d.innerHTML}
function isImg(n){return/\.(jpe?g|png|webp|gif|bmp|heic|heif)$/i.test(n)}
function isVid(n){return/\.(mp4|mkv|webm|3gp|mov|avi|m4v)$/i.test(n)}
const ICON={dir:"📁",img:"🖼",vid:"🎬",aud:"🎵",pdf:"📕",apk:"🤖",zip:"🗜",txt:"📄",oth:"📦"};
function iconOf(f){if(f.dir)return ICON.dir;const n=f.name.toLowerCase();
if(isImg(n))return ICON.img;if(isVid(n))return ICON.vid;
if(/\.(mp3|m4a|aac|flac|ogg|wav|opus)$/.test(n))return ICON.aud;
if(/\.pdf$/.test(n))return ICON.pdf;if(/\.(apk|apkm|xapk)$/.test(n))return ICON.apk;
if(/\.(zip|rar|7z|tar|gz|bz2)$/.test(n))return ICON.zip;
if(/\.(txt|log|md|csv|json|xml)$/.test(n))return ICON.txt;return ICON.oth}
function media(f){const u=T+"/thumb/"+encodeURIComponent(f.path);
if(isImg(f.name)||isVid(f.name))return'<img class="tn" loading="lazy" src="'+u+'" onerror="this.outerHTML=icoEl(\''+iconOf(f)+'\')">';
return icoEl(iconOf(f))}
function icoEl(c){return'<span class="ico">'+c+'</span>'}
async function roots(){const r=await fetch(T+"/roots");const rs=await r.json();
$("#roots").innerHTML=rs.map(x=>'<button class="chip" onclick="go(\''+encodeURIComponent(x.path)+'\')">'+esc(x.name)+"</button>").join("");}
function crumbs(){const segs=cwd.split("/").filter(Boolean);let acc="";
$("#crumbs").innerHTML='<a href="#" onclick="go(\''+encodeURIComponent("/storage")+'\');return false">/storage</a>'+
segs.slice(1).map(s=>{acc+="/"+s;return'<b>/</b><a href="#" onclick="go(\''+encodeURIComponent("/storage"+acc)+'\');return false">'+esc(s)+"</a>"}).join("");}
async function go(enc){sel.clear();$("#all").checked=false;
const r=await fetch(T+"/list?path="+enc);const d=await r.json();cwd=d.path;items=d.items;
crumbs();$("#up").disabled=!d.parent;
$("#up").onclick=()=>{if(d.parent)go(encodeURIComponent(d.parent))};
render();}
function render(){
$("#list").innerHTML=items.length?items.map((f,i)=>
'<div class="row'+(f.dir?" dir":"")+'"><input type="checkbox" data-i="'+i+'" '+(sel.has(f.path)?"checked":"")+'>'+
media(f)+'<span class="nm" onclick="openItem('+i+')">'+esc(f.name)+'</span>'+
'<span class="sz">'+(f.dir?"folder":fmt(f.size))+'</span>'+
(f.dir?"":'<a class="btn" href="'+T+'/d/'+encodeURIComponent(f.path)+'">⬇</a>')+"</div>"
).join(""):'<div class="empty">Empty folder — drop files here or hit Upload.</div>';
document.querySelectorAll("#list input[type=checkbox]").forEach(cb=>{
cb.onchange=e=>{const p=items[+cb.dataset.i].path;
if(cb.checked)sel.add(p);else sel.delete(p);};});}
window.openItem=i=>{const f=items[i];if(f.dir)go(encodeURIComponent(f.path));
else location.href=T+"/d/"+encodeURIComponent(f.path);};
$("#all").onchange=e=>{sel.clear();if(e.target.checked)items.forEach(f=>sel.add(f.path));render();};
$("#dl").onclick=()=>{if(!sel.size)return alert("Select files first");
const q=[...sel].map(p=>"sel="+encodeURIComponent(p)).join("&");
location.href=T+"/zip?"+q;};
$("#rm").onclick=async()=>{if(!sel.size)return alert("Select files first");
if(!confirm("Delete "+sel.size+" item(s) permanently from the phone?"))return;
await fetch(T+"/delall",{method:"POST",headers:{"Content-Type":"application/x-www-form-urlencoded"},
body:"paths="+[...sel].map(encodeURIComponent).join("%0A")});
sel.clear();$("#all").checked=false;refresh();};
$("#mkdir").onclick=async()=>{const n=prompt("New folder name");if(!n)return;
await fetch(T+"/mkdir",{method:"POST",headers:{"Content-Type":"application/x-www-form-urlencoded"},
body:"path="+encodeURIComponent(cwd)+"&name="+encodeURIComponent(n)});refresh();};
function refresh(){go(encodeURIComponent(cwd));}
function upload(files){const bar=$("#bar"),fill=bar.firstElementChild;
(async()=>{for(const f of files){const fd=new FormData();fd.append("file",f);
fd.append("path",cwd);
await new Promise((res,rej)=>{const x=new XMLHttpRequest();x.open("POST",T+"/upload");
x.upload.onprogress=e=>{if(e.lengthComputable){bar.style.display="block";
fill.style.width=Math.round(e.loaded/e.total*100)+"%"}};
x.onload=res;x.onerror=rej;x.send(fd);});}
bar.style.display="none";fill.style.width="0";refresh();})();}
const pick=$("#pick"),drop=$("#drop");
pick.onchange=()=>{upload(pick.files);pick.value="";};
drop.onclick=()=>pick.click();
["dragover","dragleave","drop"].forEach(ev=>drop.addEventListener(ev,e=>{
e.preventDefault();drop.classList.toggle("over",ev==="dragover");
if(ev==="drop")upload(e.dataTransfer.files);}));
document.querySelector("#list").parentElement.addEventListener("dragover",e=>e.preventDefault());
document.querySelector("#list").parentElement.addEventListener("drop",e=>{
e.preventDefault();if(e.dataTransfer.files.length)upload(e.dataTransfer.files);});
(async()=>{await roots();const r=await fetch(T+"/roots");
const rs=await r.json();const first=rs.find(x=>x.name==="FileZen Share")||rs[1]||rs[0];
go(encodeURIComponent(first.path));})();
</script></body></html>
""".trimIndent()
    }
}
