package com.filezen.files.core.vcd

import java.io.File

/**
 * File time machine — keeps a version history for a file as a VCDIFF
 * delta chain (xdelta-style) in `<dir>/.filezen-versions/<name>/`.
 *
 *   v1.full           — full copy of the first snapshot
 *   vN.vcd            — VCDIFF delta from v(N-1) to vN
 *   manifest.json     — {versions:[{n, time, size, delta, note}]}
 */
object TimeMachine {

    class Version(val n: Int, val time: Long, val size: Long, val deltaFile: String, val note: String)
    class Store(val dir: File, val fileName: String, val versions: List<Version>)

    private fun dirFor(f: File) =
        File(f.parentFile, ".filezen-versions/" + f.name)

    fun storeFor(f: File): Store? {
        val d = dirFor(f)
        val mf = File(d, "manifest.json")
        if (!mf.exists()) return null
        val list = mf.readLines().filter { it.isNotBlank() }.mapNotNull { line ->
            val p = line.split('|')
            if (p.size < 4) null else
                Version(p[0].toInt(), p[1].toLong(), p[2].toLong(), p[3], p.getOrElse(4) { "" })
        }
        return if (list.isEmpty()) null else Store(d, f.name, list)
    }

    /** Snapshot the file's current content as the next version. Returns the new Version. */
    fun snapshot(f: File, note: String = ""): Version {
        val d = dirFor(f).apply { mkdirs() }
        val mf = File(d, "manifest.json")
        val current = f.readBytes()
        val store = storeFor(f)
        return if (store == null) {
            val v1 = File(d, "v1.full")
            v1.writeBytes(current)
            val list = listOf(Version(1, System.currentTimeMillis(), current.size.toLong(), v1.name, note))
            writeManifest(mf, f.name, list)
            list.last()
        } else {
            val prev = reconstruct(store, store.versions.size)
            val n = store.versions.size + 1
            val df = File(d, "v$n.vcd")
            df.writeBytes(Vcdiff.encode(prev, current))
            val list = store.versions + Version(n, System.currentTimeMillis(), current.size.toLong(), df.name, note)
            writeManifest(mf, f.name, list)
            list.last()
        }
    }

    /** Rebuild the bytes of version [n] (clamped to existing). */
    fun reconstruct(store: Store, n: Int): ByteArray {
        var bytes = File(store.dir, store.versions[0].deltaFile).readBytes()
        for (i in 1 until minOf(n, store.versions.size)) {
            val d = File(store.dir, store.versions[i].deltaFile).readBytes()
            bytes = Vcdiff.decode(bytes, d)
        }
        return bytes
    }

    /** Restore version [n] to `<name>.v<n>.restored` next to the file. */
    fun restore(store: Store, n: Int): File {
        val f = File(store.dir.parentFile!!.parentFile!!, store.fileName)
        val out = File(f.parentFile, "${f.name}.v$n.restored")
        out.writeBytes(reconstruct(store, n))
        return out
    }

    /** Space used by the version store vs the file's current size. */
    fun storeBytes(store: Store): Long =
        store.dir.walkTopDown().filter { it.isFile }.sumOf { it.length() }

    fun deleteStore(f: File) = dirFor(f).deleteRecursively()

    private fun writeManifest(mf: File, name: String, versions: List<Version>) {
        mf.writeText(versions.joinToString("\n") { v ->
            "${v.n}|${v.time}|${v.size}|${v.deltaFile}|${v.note.replace('|', '/')}"
        })
    }
}
