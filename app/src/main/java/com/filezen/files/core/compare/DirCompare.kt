package com.filezen.files.core.compare

import java.io.File

/**
 * Directory compare (Twig-style): recursive listing of two folders, then a
 * three-way classification — only in A, only in B, or present in both but
 * differing by size / modification time.
 */
object DirCompare {

    enum class Side { ONLY_A, ONLY_B, DIFFERENT, SAME }

    data class Result(
        val onlyA: List<String>,
        val onlyB: List<String>,
        val different: List<String>,
        val same: Int,
    )

    /** Compare [a] vs [b], relative paths keyed from each root. */
    fun compare(a: File, b: File): Result {
        val mapA = scan(a)
        val mapB = scan(b)
        val onlyA = ArrayList<String>()
        val onlyB = ArrayList<String>()
        val diff = ArrayList<String>()
        var same = 0
        for ((p, sa) in mapA) {
            val sb = mapB[p]
            when {
                sb == null -> onlyA += p
                sa != sb -> diff += p
                else -> same++
            }
        }
        for (p in mapB.keys) if (p !in mapA) onlyB += p
        return Result(onlyA.sorted(), onlyB.sorted(), diff.sorted(), same)
    }

    /** relativePath -> signature ("D" for dirs, "size|mtime" for files). */
    private fun scan(root: File): Map<String, String> {
        val out = HashMap<String, String>()
        val base = root.canonicalPath
        val stack = ArrayDeque<File>().apply { add(root) }
        while (stack.isNotEmpty()) {
            val d = stack.removeLast()
            val kids = try { d.listFiles() } catch (e: Exception) { null } ?: continue
            for (f in kids) {
                val rel = f.canonicalPath.removePrefix(base).trimStart('/')
                if (rel.isEmpty()) continue
                if (f.isDirectory) {
                    out[rel] = "D"
                    stack.add(f)
                } else {
                    // size + coarse mtime (sec) — avoids jittery ms diffs
                    out[rel] = "${f.length()}|${f.lastModified() / 1000}"
                }
            }
        }
        return out
    }
}
