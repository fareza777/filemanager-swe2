package com.filezen.files.core.semsearch

import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import kotlin.math.sqrt

/**
 * Lightweight on-device text embedding: hashed token n-grams + character
 * n-grams + a few statistical features folded into a 256-dim L2-normalised
 * vector. Fully offline — no model files, no network.
 */
object Embedding {
    const val DIM = 256
    private const val NGRAM_SIZE = 3

    private val stopWords = setOf(
        "the", "a", "an", "is", "are", "was", "were", "be", "been", "being",
        "have", "has", "had", "do", "does", "did", "will", "would", "could",
        "should", "may", "might", "must", "shall", "can", "need", "used",
        "to", "of", "in", "for", "on", "with", "at", "by", "from", "as",
        "into", "through", "during", "before", "after", "above", "below",
        "between", "under", "and", "or", "but", "not", "no", "this", "that",
        "it", "its", "yang", "dan", "di", "ke", "dari", "untuk", "dengan",
        "ini", "itu", "atau", "tidak", "ada", "pada",
    )

    /** Tokenise text into searchable terms (lowercased, stop-words removed). */
    fun terms(text: String): List<String> =
        tokenize(text.lowercase().replace(Regex("[\\p{Punct}\\s]+"), " ").trim())

    fun embed(text: String): FloatArray {
        val cleaned = text.lowercase()
            .replace(Regex("[\\p{Punct}\\s]+"), " ").trim()
        val tokens = tokenize(cleaned)
        val ngrams = ngrams(tokens)
        val v = FloatArray(DIM)
        for (g in ngrams) {
            val h = hash(g)
            val idx = (h and 0x7FFFFFFF) % DIM
            val sign = if ((h shr 31) and 1 == 0) 1f else -1f
            v[idx] += sign * weight(g)
        }
        // Statistical features on the last dims.
        val len = cleaned.length.toFloat()
        v[DIM - 3] = (len / 1000f).coerceIn(0f, 1f)
        v[DIM - 2] = cleaned.count { it.isDigit() }.toFloat() / maxOf(len, 1f)
        v[DIM - 1] = tokens.size.toFloat().coerceIn(0f, 1f)
        l2(v)
        return v
    }

    private fun tokenize(text: String): List<String> {
        val out = ArrayList<String>()
        val sb = StringBuilder()
        for (ch in text) {
            if (ch.isWhitespace()) {
                if (sb.isNotEmpty()) { out += sb.toString(); sb.clear() }
            } else sb.append(ch)
        }
        if (sb.isNotEmpty()) out += sb.toString()
        return out.filter { it.isNotBlank() && it !in stopWords }
    }

    private fun ngrams(tokens: List<String>): List<String> {
        val out = ArrayList<String>(tokens.size * 4)
        out += tokens
        for (size in 2..NGRAM_SIZE)
            for (i in 0..tokens.size - size)
                out += tokens.subList(i, i + size).joinToString("_")
        for (t in tokens) if (t.length >= 2)
            for (i in 0..t.length - 2)
                out += "c_${t.substring(i, minOf(i + 2, t.length))}"
        return out
    }

    private fun weight(g: String): Float = when {
        g.startsWith("c_") -> 0.5f
        g.contains("_") -> 1.5f
        else -> 1.0f
    }

    private fun hash(s: String): Int {
        val d = MessageDigest.getInstance("MD5")
            .digest(s.toByteArray(StandardCharsets.UTF_8))
        return ((d[0].toInt() and 0xFF) shl 24) or ((d[1].toInt() and 0xFF) shl 16) or
               ((d[2].toInt() and 0xFF) shl 8) or (d[3].toInt() and 0xFF)
    }

    private fun l2(v: FloatArray) {
        var n = 0f; for (x in v) n += x * x
        n = sqrt(n)
        if (n > 0) for (i in v.indices) v[i] /= n
    }

    fun cosine(a: FloatArray, b: FloatArray): Float {
        if (a.size != b.size) return 0f
        var d = 0f
        for (i in a.indices) d += a[i] * b[i]
        return d // both vectors are L2-normalised
    }
}
