package com.filezen.files.core.tlsh

import java.io.File
import java.io.InputStream

/**
 * TLSH (Trend Micro Locality Sensitive Hash) — fuzzy digest for finding
 * near-duplicate files even when bytes differ. Ported from
 * github.com/trendmicro/tlsh (Apache-2.0 OR BSD, per upstream dual license).
 * Standard build: 128 effective buckets, 1-byte checksum → "T1…" digest.
 */
class Tlsh {

    private val buckets = IntArray(BUCKETS)      // 256 always
    private val w = ByteArray(5)                 // 5-byte sliding window
    private var dataLen = 0
    private var checksum = 0
    private var code: ByteArray? = null          // CODE_SIZE packed quartiles
    private var lvalue = 0
    private var q1ratio = 0
    private var q2ratio = 0

    fun update(data: ByteArray, off: Int = 0, len: Int = data.size - off) {
        var j = (dataLen % 5)
        for (i in off until off + len) {
            w[j] = data[i]
            if (dataLen >= 4) {
                val j1 = (j + 4) % 5
                val j2 = (j + 3) % 5
                val j3 = (j + 2) % 5
                val j4 = (j + 1) % 5
                checksum = fbm(1, u(w[j]), u(w[j1]), checksum)
                buckets[fbm(49, u(w[j]), u(w[j1]), u(w[j2]))]++
                buckets[fbm(12, u(w[j]), u(w[j1]), u(w[j3]))]++
                buckets[fbm(178, u(w[j]), u(w[j2]), u(w[j3]))]++
                buckets[fbm(166, u(w[j]), u(w[j2]), u(w[j4]))]++
                buckets[fbm(84, u(w[j]), u(w[j1]), u(w[j4]))]++
                buckets[fbm(230, u(w[j]), u(w[j3]), u(w[j4]))]++
            }
            dataLen++
            j = (j + 1) % 5
        }
    }

    /** Finishes the digest. Returns false when data is too short/degenerate. */
    fun finish(): Boolean {
        if (dataLen < MIN_DATA_LENGTH) return false
        val (q1, q2, q3) = findQuartiles()
        if (q3 == 0) return false
        var nonzero = 0
        for (i in 0 until EFF_BUCKETS) if (buckets[i] > 0) nonzero++
        if (nonzero <= EFF_BUCKETS / 2) return false
        val out = ByteArray(CODE_SIZE)
        for (i in 0 until CODE_SIZE) {
            var h = 0
            for (jj in 0 until 4) {
                val k = buckets[4 * i + jj]
                val v = when {
                    k > q3 -> 3
                    k > q2 -> 2
                    k > q1 -> 1
                    else -> 0
                }
                h = h or (v shl (jj * 2))
            }
            out[i] = h.toByte()
        }
        code = out
        lvalue = lCapturing(dataLen)
        q1ratio = ((q1 * 100) / q3) % 16
        q2ratio = ((q2 * 100) / q3) % 16
        return true
    }

    val isValid get() = code != null

    /** Canonical "T1…" hex string (nibble-swapped like upstream). */
    fun digest(): String {
        val c = code ?: return ""
        val sb = StringBuilder("T1")
        sb.appendHex(swap(checksum))
        sb.appendHex(swap(lvalue))
        sb.appendHex(swap(q1ratio or (q2ratio shl 4)))
        for (i in CODE_SIZE - 1 downTo 0) sb.appendHex(c[i].toInt() and 0xFF)
        return sb.toString()
    }

    /** Difference score vs another digest — lower means more similar. */
    fun diff(other: Tlsh, lenDiff: Boolean = true): Int {
        val a = code ?: return Int.MAX_VALUE
        val b = other.code ?: return Int.MAX_VALUE
        var diff = 0
        if (lenDiff) {
            val ld = modDiff(lvalue, other.lvalue, 256)
            diff += if (ld <= 1) ld else ld * 12
        }
        val q1d = modDiff(q1ratio, other.q1ratio, 16)
        diff += if (q1d <= 1) q1d else (q1d - 1) * 12
        val q2d = modDiff(q2ratio, other.q2ratio, 16)
        diff += if (q2d <= 1) q2d else (q2d - 1) * 12
        if (checksum != other.checksum) diff += 1
        for (i in 0 until CODE_SIZE) diff += byteDiff(a[i].toInt() and 0xFF, b[i].toInt() and 0xFF)
        return diff
    }

    /* ---- internals ---- */

    private fun findQuartiles(): Triple<Int, Int, Int> {
        // Quartiles over the first EFF_BUCKETS histogram entries (upstream's
        // find_quartile is a quickselect with shortcuts — plain quickselect is equivalent).
        val p1 = EFF_BUCKETS / 4 - 1
        val p2 = EFF_BUCKETS / 2 - 1
        val p3 = EFF_BUCKETS - EFF_BUCKETS / 4 - 1
        val q1v = quickSelect(IntArray(EFF_BUCKETS) { buckets[it] }, p1)
        val q2v = quickSelect(IntArray(EFF_BUCKETS) { buckets[it] }, p2)
        val q3v = quickSelect(IntArray(EFF_BUCKETS) { buckets[it] }, p3)
        return Triple(q1v, q2v, q3v)
    }

    private fun partition(buf: IntArray, left: Int, right: Int): Int {
        if (left == right) return left
        if (left + 1 == right) {
            if (buf[left] > buf[right]) { val t = buf[left]; buf[left] = buf[right]; buf[right] = t }
            return left
        }
        var ret = left
        val pivot = (left + right) / 2
        val v = buf[pivot]
        buf[pivot] = buf[right]; buf[right] = v
        for (i in left until right) if (buf[i] < v) {
            val t = buf[ret]; buf[ret] = buf[i]; buf[i] = t; ret++
        }
        buf[right] = buf[ret]; buf[ret] = v
        return ret
    }

    private fun quickSelect(buf: IntArray, k: Int): Int {
        var l = 0; var r = buf.size - 1
        while (true) {
            val ret = partition(buf, l, r)
            if (ret > k) r = ret - 1 else if (ret < k) l = ret + 1 else return buf[k]
        }
    }

    companion object {
        private const val BUCKETS = 256
        private const val EFF_BUCKETS = 128
        private const val CODE_SIZE = 32
        private const val MIN_DATA_LENGTH = 50

        private fun u(b: Byte) = b.toInt() and 0xFF
        private fun fbm(ms: Int, i: Int, j: Int, k: Int): Int =
            V_TABLE[V_TABLE[V_TABLE[ms xor i] xor j] xor k]

        private fun swap(b: Int) = ((b and 0xF) shl 4) or (b shr 4)

        private fun StringBuilder.appendHex(b: Int): StringBuilder =
            append(Integer.toHexString(b).uppercase().padStart(2, '0'))

        private fun modDiff(x: Int, y: Int, r: Int): Int {
            val dl = if (y > x) y - x else x - y
            val dr = if (y > x) x + r - y else y + r - x
            return minOf(dl, dr)
        }

        private fun pairDiff(p: Int, q: Int) = when (kotlin.math.abs(p - q)) { 0 -> 0; 1 -> 1; 2 -> 2; else -> 6 }
        private fun byteDiff(bv: Int, obv: Int): Int {
            var d = 0
            for (s in 0 until 4) d += pairDiff((bv shr (s * 2)) and 3, (obv shr (s * 2)) and 3)
            return d
        }

        private fun lCapturing(len: Int): Int {
            // binary search over TOPVAL like upstream
            var bottom = 0; var top = 169; var idx = 85
            while (true) {
                if (idx == 0) return 0
                if (len.toLong() <= TOPVAL[idx] && len.toLong() > TOPVAL[idx - 1]) return idx
                if (len.toLong() < TOPVAL[idx]) top = idx - 1 else bottom = idx + 1
                idx = (bottom + top) / 2
            }
        }

        /** Hash a stream; returns null when the file is too small/degenerate. */
        fun digest(input: InputStream, size: Long): Tlsh? {
            if (size < MIN_DATA_LENGTH) return null
            val t = Tlsh()
            val buf = ByteArray(64 * 1024)
            var n: Int
            while (input.read(buf).also { n = it } > 0) t.update(buf, 0, n)
            return if (t.finish()) t else null
        }

        fun digest(file: File): Tlsh? = file.inputStream().use { digest(it, file.length()) }
    }
}

private val V_TABLE = intArrayOf(
        1, 87, 49, 12, 176, 178, 102, 166, 121, 193, 6, 84, 249, 230, 44, 163,
        14, 197, 213, 181, 161, 85, 218, 80, 64, 239, 24, 226, 236, 142, 38, 200,
        110, 177, 104, 103, 141, 253, 255, 50, 77, 101, 81, 18, 45, 96, 31, 222,
        25, 107, 190, 70, 86, 237, 240, 34, 72, 242, 20, 214, 244, 227, 149, 235,
        97, 234, 57, 22, 60, 250, 82, 175, 208, 5, 127, 199, 111, 62, 135, 248,
        174, 169, 211, 58, 66, 154, 106, 195, 245, 171, 17, 187, 182, 179, 0, 243,
        132, 56, 148, 75, 128, 133, 158, 100, 130, 126, 91, 13, 153, 246, 216, 219,
        119, 68, 223, 78, 83, 88, 201, 99, 122, 11, 92, 32, 136, 114, 52, 10,
        138, 30, 48, 183, 156, 35, 61, 26, 143, 74, 251, 94, 129, 162, 63, 152,
        170, 7, 115, 167, 241, 206, 3, 150, 55, 59, 151, 220, 90, 53, 23, 131,
        125, 173, 15, 238, 79, 95, 89, 16, 105, 137, 225, 224, 217, 160, 37, 123,
        118, 73, 2, 157, 46, 116, 9, 145, 134, 228, 207, 212, 202, 215, 69, 229,
        27, 188, 67, 124, 168, 252, 42, 4, 29, 108, 21, 247, 19, 205, 39, 203,
        233, 40, 186, 147, 198, 192, 155, 33, 164, 191, 98, 204, 165, 180, 117, 76,
        140, 36, 210, 172, 41, 54, 159, 8, 185, 232, 113, 196, 231, 47, 146, 120,
        51, 65, 28, 144, 254, 221, 93, 189, 194, 139, 112, 43, 71, 109, 184, 209)

private val TOPVAL = longArrayOf(
    1, 2, 3, 5, 7, 11, 17, 25, 38, 57, 86, 129,
    194, 291, 437, 656, 854, 1110, 1443, 1876, 2439, 3171, 3475, 3823,
    4205, 4626, 5088, 5597, 6157, 6772, 7450, 8195, 9014, 9916, 10907, 11998,
    13198, 14518, 15970, 17567, 19323, 21256, 23382, 25720, 28292, 31121, 34233, 37656,
    41422, 45564, 50121, 55133, 60646, 66711, 73382, 80721, 88793, 97672, 107439, 118183,
    130002, 143002, 157302, 173032, 190335, 209369, 230306, 253337, 278670, 306538, 337191, 370911,
    408002, 448802, 493682, 543050, 597356, 657091, 722800, 795081, 874589, 962048, 1058252, 1164078,
    1280486, 1408534, 1549388, 1704327, 1874759, 2062236, 2268459, 2495305, 2744836, 3019320, 3321252, 3653374,
    4018711, 4420582, 4862641, 5348905, 5883796, 6472176, 7119394, 7831333, 8614467, 9475909, 10423501, 11465851,
    12612437, 13873681, 15261050, 16787154, 18465870, 20312458, 22343706, 24578077, 27035886, 29739474, 32713425, 35984770,
    39583245, 43541573, 47895730, 52685306, 57953837, 63749221, 70124148, 77136564, 84850228, 93335252, 102668779, 112935659,
    124229227, 136652151, 150317384, 165349128, 181884040, 200072456, 220079703, 242087671, 266296456, 292926096, 322218735, 354440623,
    389884688, 428873168, 471760495, 518936559, 570830240, 627913311, 690704607, 759775136, 835752671, 919327967, 1011260767, 1112386880,
    1223623232, 1345985727, 1480584256, 1628642751, 1791507135, 1970657856, 2167723648, 2384496256, 2622945920, 2885240448, 3173764736, 3491141248,
    3840255616, 4224281216,)