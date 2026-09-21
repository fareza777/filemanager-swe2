package com.filezen.files.core.vcd

import java.io.ByteArrayOutputStream
import java.io.File

/**
 * VCDIFF (RFC 3284) encoder/decoder — the xdelta delta format
 * (xdelta algorithm per https://github.com/jmacd/xdelta, GPL — this is a
 * clean implementation of the public file-format spec).
 *
 * The encoder emits COPY instructions with VCD_SELF addresses only —
 * always valid VCDIFF that any conforming decoder (incl. xdelta3) reads.
 * The decoder implements every address mode (SELF, HERE, NEAR 0-3,
 * SAME 0-5) so it also decodes deltas produced by real xdelta3.
 */
object Vcdiff {

    private const val NOOP = 0; private const val ADD = 1
    private const val RUN = 2; private const val COPY = 3
    private const val S_NEAR = 4; private const val S_SAME = 3

    private const val MIN_MATCH = 8
    private const val ANCHOR = 16

    // ---- default instruction code table (RFC 3284 §5.6) ------------------

    /** table[i] = intArrayOf(inst1,size1,mode1, inst2,size2,mode2) */
    val TABLE: Array<IntArray> = buildTable()

    private fun buildTable(): Array<IntArray> {
        val t = Array(256) { intArrayOf(NOOP, 0, 0, NOOP, 0, 0) }
        var i = 0
        t[i++] = intArrayOf(RUN, 0, 0, NOOP, 0, 0)                 // 0
        for (s in 0..17) t[i++] = intArrayOf(ADD, s, 0, NOOP, 0, 0) // 1..18
        for (mode in 0..8) {                                       // 19..162
            t[i++] = intArrayOf(COPY, 0, mode, NOOP, 0, 0)
            for (s in 4..18) t[i++] = intArrayOf(COPY, s, mode, NOOP, 0, 0)
        }
        for (mode in 0..5)                                         // 163..234
            for (add in 1..4)
                for (cp in 4..6)
                    t[i++] = intArrayOf(ADD, add, 0, COPY, cp, mode)
        for (mode in 6..8)                                         // 235..246
            for (add in 1..4)
                t[i++] = intArrayOf(ADD, add, 0, COPY, 4, mode)
        for (mode in 0..8)                                         // 247..255
            t[i++] = intArrayOf(COPY, 4, mode, ADD, 1, 0)
        check(i == 256)
        return t
    }

    // ---- helpers ---------------------------------------------------------

    private fun putVarint(out: ByteArrayOutputStream, v0: Long) {
        var v = v0
        val buf = ByteArray(10); var n = 0
        buf[n++] = (v and 0x7F).toByte(); v = v ushr 7
        while (v != 0L) { buf[n++] = ((v and 0x7F) or 0x80).toInt().toByte(); v = v ushr 7 }
        for (j in n - 1 downTo 0) out.write(buf[j].toInt())
    }

    private class Reader(val b: ByteArray, var p: Int = 0, val end: Int = b.size) {
        fun u8(): Int = if (p < end) b[p++].toInt() and 0xFF else throw IllegalStateException("eof")
        fun varint(): Long {
            var v = 0L
            while (true) {
                val c = u8()
                v = (v shl 7) or (c and 0x7F).toLong()
                if (c and 0x80 == 0) return v
            }
        }
    }

    // ---- encoder ---------------------------------------------------------

    /** Produce a VCDIFF delta: apply(src, delta) == tgt. */
    fun encode(src: ByteArray, tgt: ByteArray): ByteArray {
        // index 16-byte prefixes of source
        val index = HashMap<Int, ArrayList<Int>>()
        var p = 0
        while (p + ANCHOR <= src.size) {
            val h = hash(src, p)
            index.getOrPut(h) { ArrayList() }.add(p)
            p++
        }

        val data = ByteArrayOutputStream()   // ADD literals + RUN bytes
        val inst = ByteArrayOutputStream()   // opcodes + inline sizes
        val addr = ByteArrayOutputStream()   // COPY addresses (SELF: varint)

        fun emitAdd(pos: Int, len: Int) {
            if (len == 0) return
            if (len in 1..17) inst.write(1 + len) else { inst.write(1); putVarint(inst, len.toLong()) }
            data.write(tgt, pos, len)
        }
        fun emitCopy(offset: Int, len: Int) {
            if (len in 4..18) inst.write(16 + len) else { inst.write(19); putVarint(inst, len.toLong()) }
            putVarint(addr, offset.toLong())
        }

        var pos = 0; var litStart = 0
        fun flushLit(to: Int) = emitAdd(litStart, to - litStart)

        while (pos < tgt.size) {
            // best match
            var bestLen = 0; var bestOff = 0
            if (pos + ANCHOR <= tgt.size) {
                index[hash(tgt, pos)]?.let { cands ->
                    for (s in cands) {
                        var a = s; var b = pos
                        // extend backwards (keep COPY contiguous with target)
                        while (b > litStart && a > 0 && src[a - 1] == tgt[b - 1] && b > 0) { a--; b-- }
                        var l = 0
                        while (a + l < src.size && b + l < tgt.size && src[a + l] == tgt[b + l]) l++
                        if (l > bestLen) { bestLen = l; bestOff = a }
                    }
                }
            }
            if (bestLen >= MIN_MATCH) {
                // bestLen already spans the back-extended match; recompute
                // its start deterministically.
                var start = pos; var off = bestOff
                while (start > litStart && off > 0 && tgt[start - 1] == src[off - 1]) { start--; off-- }
                flushLit(start)
                emitCopy(off, bestLen)
                pos = start + bestLen
                litStart = pos
            } else {
                pos++
            }
        }
        flushLit(tgt.size)

        // ---- assemble ----
        val out = ByteArrayOutputStream()
        out.write(0xD6); out.write(0xC3); out.write(0xC4); out.write(0) // magic + version
        out.write(0) // Hdr_Indicator: no secondary, no code table
        // window
        out.write(1)                 // Win_Indicator: VCD_SOURCE
        putVarint(out, src.size.toLong())
        putVarint(out, 0)            // source position
        val body = ByteArrayOutputStream()
        putVarint(body, tgt.size.toLong())   // target window size
        body.write(0)                        // delta indicator
        putVarint(body, data.size().toLong())
        putVarint(body, inst.size().toLong())
        putVarint(body, addr.size().toLong())
        body.write(data.toByteArray()); body.write(inst.toByteArray()); body.write(addr.toByteArray())
        putVarint(out, body.size().toLong())
        out.write(body.toByteArray())
        return out.toByteArray()
    }

    private fun hash(b: ByteArray, p: Int): Int {
        var h = 0
        for (i in 0 until ANCHOR) h = h * 31 + (b[p + i].toInt() and 0xFF)
        return h
    }

    // ---- decoder ---------------------------------------------------------

    fun decode(src: ByteArray, delta: ByteArray): ByteArray {
        val r = Reader(delta)
        require(r.u8() == 0xD6 && r.u8() == 0xC3 && r.u8() == 0xC4 && r.u8() == 0)
        { "not a VCDIFF file" }
        require(r.u8() == 0) { "unsupported header indicator" }
        val out = ByteArrayOutputStream()
        while (r.p < r.end) decodeWindow(src, r, out)
        return out.toByteArray()
    }

    private fun decodeWindow(src: ByteArray, r: Reader, out: ByteArrayOutputStream) {
        val winInd = r.u8()
        val srcLen: Int; val srcPos: Long
        var srcData = src
        if (winInd and 1 != 0) { srcLen = r.varint().toInt(); srcPos = r.varint()
            // we only support whole-source windows from position 0
            if (srcPos != 0L || srcLen > src.size) {
                srcData = src.copyOfRange(srcPos.toInt(), (srcPos + srcLen).toInt())
            } else if (srcLen < src.size) srcData = src.copyOf(srcLen)
        } else { srcLen = 0; srcPos = 0 }
        val deltaLen = r.varint().toInt()
        val deltaEnd = r.p + deltaLen
        val tgtLen = r.varint().toInt()
        r.u8() // delta indicator (no secondary compression supported)
        val dataLen = r.varint().toInt()
        val instLen = r.varint().toInt()
        val addrLen = r.varint().toInt()
        val dataR = Reader(r.b, r.p, r.p + dataLen); r.p += dataLen
        val instR = Reader(r.b, r.p, r.p + instLen); r.p += instLen
        val addrR = Reader(r.b, r.p, r.p + addrLen); r.p += addrLen

        val win = ByteArray(tgtLen); var wp = 0
        val near = IntArray(S_NEAR); var nearSlot = 0
        val same = IntArray(S_SAME * 256)

        fun decodeAddr(mode: Int): Int = when (mode) {
            0 -> addrR.varint().toInt()                       // SELF
            1 -> wp - addrR.varint().toInt()                  // HERE
            in 2 until 2 + S_NEAR -> addrR.varint().toInt() + near[mode - 2]
            else -> same[(mode - 2 - S_NEAR) * 256 + addrR.u8()]
        }

        while (wp < tgtLen) {
            val op = instR.u8()
            val e = TABLE[op]
            var k = 0
            while (k < 6 && wp < tgtLen) {
                val inst = e[k]; var size = e[k + 1]; val mode = e[k + 2]
                k += 3
                if (inst == NOOP) continue
                if (size == 0) size = instR.varint().toInt()
                when (inst) {
                    ADD -> {
                        dataR.b.copyInto(win, wp, dataR.p, dataR.p + size)
                        dataR.p += size; wp += size
                    }
                    RUN -> {
                        val v = dataR.u8().toByte()
                        win.fill(v, wp, wp + size); wp += size
                    }
                    COPY -> {
                        val a = decodeAddr(mode)
                        near[nearSlot] = a; nearSlot = (nearSlot + 1) % S_NEAR
                        same[a % (S_SAME * 256)] = a
                        val take = minOf(size, tgtLen - wp)
                        for (j in 0 until take) {
                            val i = a + j
                            win[wp++] = if (i < srcData.size) srcData[i] else win[i - srcData.size]
                        }
                    }
                }
            }
        }
        out.write(win)
        r.p = deltaEnd.coerceAtMost(r.end)
    }
}
