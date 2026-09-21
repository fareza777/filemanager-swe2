package com.filezen.files.core.rs

/**
 * Reed-Solomon erasure coding over GF(2^8), generating polynomial 0x11D.
 * Ported from Backblaze's JavaReedSolomon (Apache-2.0)
 * https://github.com/Backblaze/JavaReedSolomon
 */
object Galois {

    const val FIELD_SIZE = 256
    private const val GENERATING_POLYNOMIAL = 0x11D

    val expTable = IntArray(FIELD_SIZE * 2)
    val logTable = IntArray(FIELD_SIZE)
    val multiplicationTable: Array<ByteArray>

    init {
        var x = 1
        for (i in 0 until FIELD_SIZE) {
            expTable[i] = x
            x = x shl 1
            if (x >= FIELD_SIZE) x = x xor GENERATING_POLYNOMIAL
        }
        // Wrap mod 255 (not 256): log sums up to 508 index back into 0..254
        for (i in FIELD_SIZE - 1 until FIELD_SIZE * 2) expTable[i] = expTable[i - (FIELD_SIZE - 1)]
        for (i in 0 until FIELD_SIZE - 1) logTable[expTable[i]] = i
        multiplicationTable = Array(FIELD_SIZE) { a ->
            ByteArray(FIELD_SIZE) { b -> multiply(a, b) }
        }
    }

    fun add(a: Int, b: Int) = a xor b

    fun multiply(a: Int, b: Int): Byte {
        if (a == 0 || b == 0) return 0
        return expTable[logTable[a and 0xFF] + logTable[b and 0xFF]].toByte()
    }

    fun exp(a: Int, n: Int): Byte {
        if (n == 0) return 1
        if (a == 0) return 0
        // a^n = exp[(log a * n) mod 255]
        val lg = (logTable[a and 0xFF].toLong() * n) % (FIELD_SIZE - 1)
        return expTable[lg.toInt()].toByte()
    }

    fun inverse(a: Int): Byte = expTable[FIELD_SIZE - 1 - logTable[a and 0xFF]].toByte()
}

/** Byte matrix with GF(2^8) arithmetic. */
class Matrix(val rows: Int, val cols: Int) {

    val data = ByteArray(rows * cols)

    operator fun get(r: Int, c: Int) = data[r * cols + c]
    fun set(r: Int, c: Int, v: Byte) { data[r * cols + c] = v }

    fun submatrix(rMin: Int, cMin: Int, rMax: Int, cMax: Int): Matrix {
        val out = Matrix(rMax - rMin, cMax - cMin)
        for (r in rMin until rMax)
            for (c in cMin until cMax)
                out.set(r - rMin, c - cMin, get(r, c))
        return out
    }

    fun times(other: Matrix): Matrix {
        require(cols == other.rows)
        val out = Matrix(rows, other.cols)
        for (r in 0 until rows)
            for (c in 0 until other.cols) {
                var v = 0
                for (i in 0 until cols)
                    v = v xor (Galois.multiplicationTable[get(r, i).toInt() and 0xFF][other[i, c].toInt() and 0xFF].toInt())
                out.set(r, c, v.toByte())
            }
        return out
    }

    fun invert(): Matrix {
        require(rows == cols)
        // Gaussian elimination over GF(2^8).
        val work = Array(rows) { r -> ByteArray(cols) { c -> get(r, c) } }
        val inv = Array(rows) { r -> ByteArray(cols) { c -> if (c == r) 1 else 0 } }
        for (c in 0 until cols) {
            // find pivot
            var pivot = c
            while (pivot < rows && work[pivot][c].toInt() == 0) pivot++
            require(pivot < rows) { "singular matrix" }
            if (pivot != c) {
                val t = work[c]; work[c] = work[pivot]; work[pivot] = t
                val t2 = inv[c]; inv[c] = inv[pivot]; inv[pivot] = t2
            }
            val scale = Galois.inverse(work[c][c].toInt() and 0xFF)
            for (j in 0 until cols) {
                work[c][j] = Galois.multiplicationTable[scale.toInt() and 0xFF][work[c][j].toInt() and 0xFF]
                inv[c][j] = Galois.multiplicationTable[scale.toInt() and 0xFF][inv[c][j].toInt() and 0xFF]
            }
            for (r in 0 until rows) {
                if (r == c) continue
                val f = work[r][c].toInt() and 0xFF
                if (f == 0) continue
                val tab = Galois.multiplicationTable[f]
                for (j in 0 until cols) {
                    work[r][j] = (work[r][j].toInt() xor tab[work[c][j].toInt() and 0xFF].toInt()).toByte()
                    inv[r][j] = (inv[r][j].toInt() xor tab[inv[c][j].toInt() and 0xFF].toInt()).toByte()
                }
            }
        }
        val out = Matrix(rows, cols)
        for (r in 0 until rows) for (c in 0 until cols) out.set(r, c, inv[r][c])
        return out
    }

    companion object {
        fun identity(n: Int) = Matrix(n, n).also { m ->
            for (i in 0 until n) m.set(i, i, 1)
        }

        /** vandermonde(r,c) = r^(cols-1-c) over GF(2^8). */
        fun vandermonde(rows: Int, cols: Int): Matrix {
            val m = Matrix(rows, cols)
            for (r in 0 until rows)
                for (c in 0 until cols)
                    m.set(r, c, Galois.exp(r, cols - 1 - c))
            return m
        }
    }
}

/**
 * Row-based RS coder: k data shards + m parity shards. Encoding matrix is
 * buildMatrix = invert(top k×k of vandermonde(k+m,k)) × vandermonde — so the
 * top k rows are the identity (data shards pass through).
 */
class ReedSolomon(val dataShards: Int, val parityShards: Int) {

    val matrix: Matrix = run {
        val vm = Matrix.vandermonde(dataShards + parityShards, dataShards)
        // [I; P] = vm × invert(top k×k of vm) — makes data rows the identity
        vm.times(vm.submatrix(0, 0, dataShards, dataShards).invert())
    }

    /** parity[r][i] = sum_c matrix[k+r][c] * data[c][i]; shards are blockSize each. */
    fun encodeParity(data: Array<ByteArray>, parity: Array<ByteArray>, off: Int, len: Int) {
        val m = matrix
        for (r in 0 until parityShards) {
            val out = parity[r]
            for (i in off until off + len) {
                var v = 0
                for (c in 0 until dataShards) {
                    val coef = m[dataShards + r, c].toInt() and 0xFF
                    if (coef != 0)
                        v = v xor Galois.multiplicationTable[coef][data[c][i].toInt() and 0xFF].toInt()
                }
                out[i] = v.toByte()
            }
        }
    }

    /**
     * Given the k+m shards of one stripe (some bad), recover the k data
     * shards in-place into [data]. [shardOk] flags which of k+m shards are
     * trustworthy. Returns false when fewer than k shards survive.
     */
    fun decodeStripe(
        data: Array<ByteArray>, parity: Array<ByteArray>,
        shardOk: BooleanArray, off: Int, len: Int,
    ): Boolean {
        val total = dataShards + parityShards
        val survivors = IntArray(total)
        var n = 0
        for (i in 0 until total) if (shardOk[i]) survivors[n++] = i
        if (n < dataShards) return false

        // Rows of the encoding matrix for the k survivors we will use.
        val sub = Matrix(dataShards, dataShards)
        for (r in 0 until dataShards)
            for (c in 0 until dataShards)
                sub.set(r, c, matrix[survivors[r], c])
        val inv = sub.invert()

        // Reconstruct every data shard that is bad/missing.
        val shardBytes: Array<ByteArray> = Array(total) { i ->
            when {
                i < dataShards -> data[i]
                else -> parity[i - dataShards]
            }
        }
        for (d in 0 until dataShards) {
            if (shardOk[d]) continue
            val out = data[d]
            for (i in off until off + len) {
                var v = 0
                for (r in 0 until dataShards) {
                    val coef = inv[d, r].toInt() and 0xFF
                    if (coef != 0)
                        v = v xor Galois.multiplicationTable[coef][shardBytes[survivors[r]][i].toInt() and 0xFF].toInt()
                }
                out[i] = v.toByte()
            }
        }
        return true
    }
}
