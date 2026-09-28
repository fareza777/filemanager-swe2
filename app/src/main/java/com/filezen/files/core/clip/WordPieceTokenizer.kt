package com.filezen.files.core.clip

import java.io.File

/**
 * BERT WordPiece tokenizer for the multilingual CLIP text encoder
 * (sentence-transformers/clip-ViT-B-32-multilingual-v1 — vocab.txt, cased,
 * max_seq_length 128). Basic tokenization: whitespace + punctuation split
 * with CJK chars isolated; then greedy longest-match WordPiece.
 */
class WordPieceTokenizer(vocabFile: File) {

    private val vocab: Map<String, Int> = vocabFile.readLines()
        .mapIndexed { i, t -> t.trim() to i }
        .filter { it.first.isNotEmpty() }
        .toMap()

    private val unkId = vocab["[UNK]"] ?: 100
    private val clsId = vocab["[CLS]"] ?: 101
    private val sepId = vocab["[SEP]"] ?: 102

    companion object { const val MAX_LEN = 128 }

    /** Returns (inputIds, attentionMask), each length MAX_LEN. */
    fun encode(text: String): Pair<LongArray, LongArray> {
        val words = basicTokenize(text)
        val ids = ArrayList<Int>(MAX_LEN)
        for (w in words) {
            for (id in wordPiece(w)) {
                ids += id
                if (ids.size >= MAX_LEN - 2) break
            }
            if (ids.size >= MAX_LEN - 2) break
        }
        val input = LongArray(MAX_LEN)
        val mask = LongArray(MAX_LEN)
        val seq = IntArray(ids.size + 2)
        seq[0] = clsId
        for (i in ids.indices) seq[i + 1] = ids[i]
        seq[ids.size + 1] = sepId
        for (i in seq.indices) { input[i] = seq[i].toLong(); mask[i] = 1 }
        return input to mask
    }

    private fun basicTokenize(text: String): List<String> {
        val clean = text.map { ch -> if (isWhitespace(ch)) ' ' else ch }
            .joinToString("")
        val out = ArrayList<String>()
        val sb = StringBuilder()
        fun flush() { if (sb.isNotEmpty()) { out += sb.toString(); sb.clear() } }
        for (ch in clean) {
            when {
                isCjk(ch) -> { flush(); out += ch.toString() }
                isPunctuation(ch) || isControl(ch) -> { flush(); if (!isControl(ch)) out += ch.toString() }
                else -> sb.append(ch)
            }
        }
        flush()
        // split each word further on embedded punctuation (BERT does punct as
        // separate tokens even mid-word for cased models — WordPiece handles it)
        return out.flatMap { w -> splitPunct(w) }
    }

    private fun splitPunct(w: String): List<String> {
        var start = 0; var inWs = true
        val out = ArrayList<String>()
        for (i in w.indices) {
            val p = isPunctuation(w[i])
            if (p && !inWs) { out += w.substring(start, i); start = i; inWs = true }
            else if (!p && inWs) { start = i; inWs = false }
        }
        if (!inWs) out += w.substring(start)
        return out
    }

    private fun wordPiece(word: String): List<Int> {
        if (word.length > 100) return listOf(unkId)
        val tokens = ArrayList<Int>()
        var start = 0
        while (start < word.length) {
            var end = word.length; var found = -1
            while (end > start) {
                val sub = (if (start > 0) "##" else "") + word.substring(start, end)
                val id = vocab[sub]
                if (id != null) { found = id; tokens += id; start = end; break }
                end--
            }
            if (found < 0) return listOf(unkId)
        }
        return tokens
    }

    private fun isWhitespace(c: Char) = c == ' ' || c == '\t' || c == '\n' || c == '\r' ||
        Character.getType(c).toByte() == Character.SPACE_SEPARATOR
    private fun isControl(c: Char) = (Character.getType(c).toByte() == Character.CONTROL ||
        Character.getType(c).toByte() == Character.FORMAT) && c != '\t' && c != '\n' && c != '\r'
    private fun isPunctuation(c: Char): Boolean {
        val t = Character.getType(c)
        return t in Character.CONNECTOR_PUNCTUATION..Character.OTHER_PUNCTUATION ||
            (c.code in 33..47) || (c.code in 58..64) || (c.code in 91..96) || (c.code in 123..126)
    }
    private fun isCjk(c: Char) = c.code in 0x4E00..0x9FFF || c.code in 0x3400..0x4DBF ||
        c.code in 0xF900..0xFAFF || c.code in 0x20000..0x2A6DF
}
