package com.filezen.files.core.sig

import java.io.File
import java.util.zip.ZipFile

/**
 * True file-type detection — FileZen's port of the Siegfried approach
 * (https://github.com/richardlehane/siegfried, Apache-2.0): identify files by
 * internal magic-byte signatures (PRONOM-style), not by extension. ZIP-family
 * containers (docx/apk/xlsx/…) are opened and inspected like Siegfried's
 * container matcher.
 *
 * `detect()` answers: what this file really is, and whether its extension
 * is honest.
 */
object SigDetect {

    data class Sig(
        val name: String,
        val mime: String,
        val exts: Set<String>,
        val offset: Int,
        val magic: ByteArray,
    )

    data class Result(
        val path: String,
        val ext: String,
        val detected: String?,   // null = unknown
        val mime: String?,
        val matches: Boolean,    // extension consistent with content?
        val viaContainer: Boolean = false,
    )

    private fun b(hex: String): ByteArray =
        hex.chunked(2).map { it.toInt(16).toByte() }.toByteArray()

    private fun s(txt: String): ByteArray = txt.toByteArray(Charsets.US_ASCII)

    /** Ordered — first match wins; container types refined afterwards. */
    val SIGNATURES: List<Sig> = listOf(
        Sig("PNG image", "image/png", setOf("png"), 0, b("89504E470D0A1A0A")),
        Sig("JPEG image", "image/jpeg", setOf("jpg", "jpeg", "jpe"), 0, b("FFD8FF")),
        Sig("GIF image", "image/gif", setOf("gif"), 0, s("GIF8")),
        Sig("BMP image", "image/bmp", setOf("bmp", "dib"), 0, s("BM")),
        Sig("TIFF image (LE)", "image/tiff", setOf("tif", "tiff"), 0, b("49492A00")),
        Sig("TIFF image (BE)", "image/tiff", setOf("tif", "tiff"), 0, b("4D4D002A")),
        Sig("ICO icon", "image/x-icon", setOf("ico"), 0, b("00000100")),
        Sig("Photoshop document", "image/vnd.adobe.photoshop", setOf("psd"), 0, s("8BPS")),
        Sig("PDF document", "application/pdf", setOf("pdf"), 0, s("%PDF-")),
        Sig("ZIP container", "application/zip", setOf("zip", "apk", "jar", "docx", "xlsx", "pptx", "odt", "ods", "odp", "epub", "ipa", "cbz", "aab"), 0, b("504B0304")),
        Sig("ZIP container (empty)", "application/zip", setOf("zip"), 0, b("504B0506")),
        Sig("ZIP container (spanned)", "application/zip", setOf("zip"), 0, b("504B0708")),
        Sig("OLE compound (legacy Office)", "application/x-ole-storage", setOf("doc", "xls", "ppt", "msi", "msg", "pub"), 0, b("D0CF11E0A1B11AE1")),
        Sig("GZIP", "application/gzip", setOf("gz", "gzip", "tgz"), 0, b("1F8B")),
        Sig("BZIP2", "application/x-bzip2", setOf("bz2", "tbz2"), 0, s("BZh")),
        Sig("XZ", "application/x-xz", setOf("xz", "txz"), 0, b("FD377A585A00")),
        Sig("7-Zip archive", "application/x-7z-compressed", setOf("7z"), 0, b("377ABCAF271C")),
        Sig("RAR archive", "application/vnd.rar", setOf("rar"), 0, b("526172211A0700")),
        Sig("RAR5 archive", "application/vnd.rar", setOf("rar"), 0, b("526172211A070100")),
        Sig("Zstandard", "application/zstd", setOf("zst"), 0, b("28B52FFD")),
        Sig("LZ4 frame", "application/x-lz4", setOf("lz4"), 0, b("04224D18")),
        Sig("SQLite database", "application/vnd.sqlite3", setOf("db", "sqlite", "sqlite3", "db3"), 0, s("SQLite format 3\u0000")),
        Sig("ELF executable", "application/x-elf", setOf("elf", "so", "o", "bin"), 0, b("7F454C46")),
        Sig("Dalvik executable (DEX)", "application/vnd.android.dex", setOf("dex"), 0, s("dex\n")),
        Sig("Windows executable (PE)", "application/vnd.microsoft.portable-executable", setOf("exe", "dll", "sys", "scr", "msi"), 0, s("MZ")),
        Sig("Java class", "application/java-vm", setOf("class"), 0, b("CAFEBABE")),
        Sig("WebAssembly", "application/wasm", setOf("wasm"), 0, b("0061736D")),
        Sig("FLAC audio", "audio/flac", setOf("flac"), 0, s("fLaC")),
        Sig("OGG container", "application/ogg", setOf("ogg", "oga", "ogv", "opus"), 0, s("OggS")),
        Sig("MIDI", "audio/midi", setOf("mid", "midi"), 0, s("MThd")),
        Sig("MP3 (ID3)", "audio/mpeg", setOf("mp3"), 0, s("ID3")),
        Sig("MP3 (frame sync)", "audio/mpeg", setOf("mp3"), 0, b("FFFB")),
        Sig("MP3 (frame sync)", "audio/mpeg", setOf("mp3"), 0, b("FFF3")),
        Sig("MP3 (frame sync)", "audio/mpeg", setOf("mp3"), 0, b("FFF2")),
        Sig("ISO-9660 disc image", "application/x-iso9660-image", setOf("iso"), 0x8001, s("CD001")),
        Sig("TAR archive", "application/x-tar", setOf("tar"), 257, s("ustar")),
        Sig("Rich Text", "application/rtf", setOf("rtf"), 0, s("{\\rtf")),
        Sig("PostScript", "application/postscript", setOf("ps", "eps"), 0, s("%!PS")),
        Sig("MPEG video", "video/mpeg", setOf("mpg", "mpeg", "m2v", "vob"), 0, b("000001BA")),
        Sig("Windows CAB", "application/vnd.ms-cab-compressed", setOf("cab"), 0, s("MSCF")),
        Sig("Apple Disk Image", "application/x-apple-diskimage", setOf("dmg"), 0, s("koly")),
    )

    /** RIFF-family needs a second look at offset 8. */
    private fun riffType(head: ByteArray): Pair<String, String>? {
        if (head.size < 12 || !head.copyOfRange(0, 4).contentEquals(s("RIFF"))) return null
        return when (String(head.copyOfRange(8, 12), Charsets.US_ASCII)) {
            "WAVE" -> "WAV audio" to "audio/wav"
            "AVI " -> "AVI video" to "video/x-msvideo"
            "WEBP" -> "WebP image" to "image/webp"
            else -> null
        }
    }

    /** ftyp-brand containers (MP4/MOV/M4A/HEIC/AVIF/3GP). */
    private fun ftypType(head: ByteArray): Pair<String, Pair<String, Set<String>>>? {
        if (head.size < 12 || !head.copyOfRange(4, 8).contentEquals(s("ftyp"))) return null
        val brand = String(head.copyOfRange(8, 12), Charsets.US_ASCII)
        return when {
            brand.startsWith("isom") || brand.startsWith("mp4") || brand == "M4V " || brand == "dash" ->
                "MP4 video" to ("video/mp4" to setOf("mp4", "m4v"))
            brand.startsWith("qt") -> "QuickTime video" to ("video/quicktime" to setOf("mov", "qt"))
            brand == "M4A " || brand == "M4B " -> "M4A audio" to ("audio/mp4" to setOf("m4a", "m4b"))
            brand.startsWith("heic") || brand.startsWith("heix") || brand == "mif1" ->
                "HEIC image" to ("image/heic" to setOf("heic", "heif"))
            brand.startsWith("avif") -> "AVIF image" to ("image/avif" to setOf("avif"))
            brand.startsWith("3gp") || brand.startsWith("3g2") ->
                "3GPP video" to ("video/3gpp" to setOf("3gp", "3g2"))
            brand == "jp2 " -> "JPEG 2000" to ("image/jp2" to setOf("jp2"))
            else -> "ISO Base Media ($brand)" to ("video/mp4" to setOf("mp4"))
        }
    }

    /** Peek inside a ZIP for the well-known sub-format markers. */
    private fun zipSubtype(f: File): Pair<String, Pair<String, Set<String>>>? = try {
        ZipFile(f).use { z ->
            val names = HashSet<String>()
            val e = z.entries()
            while (e.hasMoreElements()) names += e.nextElement().name
            when {
                "AndroidManifest.xml" in names && "classes.dex" in names ->
                    "Android app (APK)" to ("application/vnd.android.package-archive" to setOf("apk"))
                names.any { it.startsWith("word/") } ->
                    "Word document (DOCX)" to ("application/vnd.openxmlformats-officedocument.wordprocessingml.document" to setOf("docx"))
                names.any { it.startsWith("xl/") } ->
                    "Excel spreadsheet (XLSX)" to ("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet" to setOf("xlsx"))
                names.any { it.startsWith("ppt/") } ->
                    "PowerPoint (PPTX)" to ("application/vnd.openxmlformats-officedocument.presentationml.presentation" to setOf("pptx"))
                "mimetype" in names -> { // ODF/EPUB store format in first entry
                    val mime = runCatching {
                        z.getInputStream(z.getEntry("mimetype")).readBytes()
                            .toString(Charsets.US_ASCII).trim()
                    }.getOrNull()
                    when {
                        mime == "application/epub+zip" ->
                            "EPUB ebook" to (mime to setOf("epub"))
                        mime?.startsWith("application/vnd.oasis") == true ->
                            "OpenDocument" to (mime to setOf("odt", "ods", "odp"))
                        else -> null
                    }
                }
                "META-INF/MANIFEST.MF" in names ->
                    "Java archive (JAR)" to ("application/java-archive" to setOf("jar"))
                else -> null
            }
        }
    } catch (_: Throwable) { null }

    fun detect(f: File): Result {
        val ext = f.extension.lowercase()
        val head = ByteArray(0x8001 + 8)
        val n = try {
            f.inputStream().use { it.read(head) }
        } catch (_: Throwable) { -1 }
        if (n < 0) return Result(f.absolutePath, ext, null, null, matches = true)

        val h = head.copyOf(n)

        riffType(h)?.let { (name, mime) ->
            val exts = when (name) {
                "WAV audio" -> setOf("wav")
                "AVI video" -> setOf("avi")
                else -> setOf("webp")
            }
            return Result(f.absolutePath, ext, name, mime, ext in exts)
        }
        ftypType(h)?.let { (name, mm) ->
            return Result(f.absolutePath, ext, name, mm.first, ext in mm.second)
        }

        for (sig in SIGNATURES) {
            if (h.size < sig.offset + sig.magic.size) continue
            var ok = true
            for (i in sig.magic.indices) {
                if (h[sig.offset + i] != sig.magic[i]) { ok = false; break }
            }
            if (!ok) continue
            // Refine ZIP-family by inspecting entries.
            if (sig.name.startsWith("ZIP")) {
                val sub = zipSubtype(f)
                if (sub != null) {
                    val (name, mm) = sub
                    return Result(f.absolutePath, ext, name, mm.first,
                        ext in mm.second, viaContainer = true)
                }
            }
            return Result(f.absolutePath, ext, sig.name, sig.mime, ext in sig.exts)
        }
        // Text fallback: mostly-printable → plain text.
        val sample = h.take(4096)
        val bad = sample.count { c ->
            c == 0xFF.toByte() || (c < 0x20 && c != 0x0A.toByte() && c != 0x09.toByte() && c != 0x0D.toByte())
        }
        if (sample.isNotEmpty() && bad.toFloat() / sample.size < 0.05f) {
            return Result(f.absolutePath, ext, "Plain text", "text/plain",
                ext in setOf("txt", "md", "csv", "log", "xml", "json", "html", "htm", "ini", "cfg", "kt", "java", "py", "js", "ts", "c", "cpp", "h", "sh", "bat", "sql", "yml", "yaml", "toml", "srt", "vtt", ""))
        }
        return Result(f.absolutePath, ext, null, null, matches = true)
    }
}
