package com.filezen.files.core.vec

import kotlin.math.sqrt

/**
 * Local vector search engine — FileZen's port of sqlite-vec
 * (https://github.com/asg017/sqlite-vec, MIT license).
 *
 * sqlite-vec stores vectors inside SQLite tables and answers
 * "k nearest neighbours" queries with a brute-force scan — small, exact and
 * fully offline. FileZen keeps the vectors in the `doc_chunks` Room table
 * (half-precision/float16 BLOBs, like sqlite-vec's vec_f16) and this object
 * performs the exact KNN scan over them.
 */
object VecIndex {

    enum class Metric { COSINE, L2 }

    /** Pack a float32 vector to half-precision (2 bytes/component). */
    fun pack16(v: FloatArray): ByteArray {
        val out = ByteArray(v.size * 2)
        for (i in v.indices) {
            val h = floatToHalf(v[i])
            out[i * 2] = (h and 0xFF).toByte()
            out[i * 2 + 1] = ((h ushr 8) and 0xFF).toByte()
        }
        return out
    }

    fun unpack16(b: ByteArray): FloatArray {
        val v = FloatArray(b.size / 2)
        for (i in v.indices) {
            val h = (b[i * 2].toInt() and 0xFF) or ((b[i * 2 + 1].toInt() and 0xFF) shl 8)
            v[i] = halfToFloat(h)
        }
        return v
    }

    fun distance(a: FloatArray, b: FloatArray, metric: Metric): Float = when (metric) {
        Metric.COSINE -> {
            var d = 0f
            val n = minOf(a.size, b.size)
            for (i in 0 until n) d += a[i] * b[i]
            d // inputs are L2-normalised
        }
        Metric.L2 -> {
            var s = 0f
            val n = minOf(a.size, b.size)
            for (i in 0 until n) { val x = a[i] - b[i]; s += x * x }
            sqrt(s)
        }
    }

    /**
     * Exact KNN over stored vectors — the sqlite-vec `vec0` scan.
     * Returns (key, similarity-in-0..1) sorted best-first. For L2 the
     * similarity is `1/(1+dist)`; for COSINE it's the cosine itself.
     */
    fun <K> knn(
        query: FloatArray,
        rows: List<Pair<K, ByteArray>>,
        k: Int,
        metric: Metric = Metric.COSINE,
    ): List<Pair<K, Float>> {
        val scored = ArrayList<Pair<K, Float>>(rows.size)
        for ((key, blob) in rows) {
            val v = unpack16(blob)
            val sim = when (metric) {
                Metric.COSINE -> distance(query, v, Metric.COSINE)
                Metric.L2 -> 1f / (1f + distance(query, v, Metric.L2))
            }
            scored += key to sim
        }
        scored.sortByDescending { it.second }
        return scored.subList(0, minOf(k, scored.size))
    }

    // ---- IEEE-754 half conversion ----

    private fun floatToHalf(f: Float): Int {
        val bits = java.lang.Float.floatToIntBits(f)
        val sign = (bits ushr 16) and 0x8000
        val e = ((bits ushr 23) and 0xFF) - 127 + 15
        val m = bits and 0x7FFFFF
        return when {
            e <= 0 -> sign // underflow → signed zero
            e >= 31 -> sign or 0x7C00 // overflow → inf
            else -> sign or (e shl 10) or (m ushr 13)
        }
    }

    private fun halfToFloat(h: Int): Float {
        val sign = (h and 0x8000) shl 16
        val e = (h ushr 10) and 0x1F
        val m = h and 0x3FF
        val bits = when {
            e == 0 -> sign or (m shl 13) // denormal approximation
            e == 31 -> sign or 0x7F800000 or (m shl 13)
            else -> sign or ((e - 15 + 127) shl 23) or (m shl 13)
        }
        return java.lang.Float.intBitsToFloat(bits)
    }
}
