package com.filezen.files.core.cleaner

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import java.io.File
import java.security.MessageDigest
import kotlin.math.abs
import kotlin.math.ln

/**
 * Similar-photo analysis — a port of ClearLens's ImageAnalyzer + PhotoRepository
 * (https://github.com/intrusiveicebear/ClearLens, MIT) adapted from MediaStore
 * URIs to FileZen's on-disk index.
 *
 * Pipeline: decode a small bitmap per image → 64-bit difference hash +
 * sharpness (Laplacian variance) + brightness + detail variance → group into
 * exact duplicates (SHA-256 of content), similar photos (Hamming ≤ 7 inside
 * 4-band LSH buckets, same aspect ratio) and low-quality flags (blank / very
 * dark / blurry). Each group keeps the best copy via [LensPhoto.qualityScore].
 * Nothing is deleted automatically — the UI pre-selects the recommendations
 * and the user reviews them.
 */
object PhotoLens {

    data class Metrics(
        val perceptualHash: Long,
        val sharpness: Double,
        val brightness: Double,
        val variance: Double,
        val width: Int,
        val height: Int,
    )

    data class LensPhoto(
        val path: String,
        val name: String,
        val sizeBytes: Long,
        val lastModified: Long,
        val isFavourite: Boolean,
        val metrics: Metrics?,
        val exactHash: String? = null,
    ) {
        val qualityScore: Double
            get() {
                val m = metrics ?: return if (isFavourite) 10_000.0 else 0.0
                val resolution = (m.width.toLong() * m.height.toLong()).coerceAtLeast(1L)
                val resolutionScore = ln(resolution.toDouble())
                val exposureScore = 1.0 - abs(m.brightness - 0.5)
                return m.sharpness * 2.0 + resolutionScore + exposureScore * 4.0 +
                    if (isFavourite) 10_000.0 else 0.0
            }
    }

    enum class FindingType(val label: String) {
        EXACT_DUPLICATE("Duplicates"),
        SIMILAR("Similar"),
        BLURRY("Possibly blurry"),
        DARK("Very dark"),
        BLANK("Blank / accidental"),
    }

    data class FindingGroup(
        val id: String,
        val type: FindingType,
        val title: String,
        val explanation: String,
        val photos: List<LensPhoto>,
        val recommendedDeletePaths: Set<String>,
    ) {
        val recoverableBytes: Long
            get() = photos.filter { it.path in recommendedDeletePaths }.sumOf { it.sizeBytes }
    }

    data class ScanReport(
        val scanned: Int,
        val failed: Int,
        val groups: List<FindingGroup>,
    ) {
        val flaggedPhotos: Int get() = groups.sumOf { it.photos.size }
        val recoverableBytes: Long get() = groups.sumOf { it.recoverableBytes }
    }

    // ---------- Metrics (ported from ImageAnalyzer) ----------

    fun metrics(source: Bitmap): Metrics {
        val sample = Bitmap.createScaledBitmap(source, 64, 64, true)
        val gray = DoubleArray(64 * 64)
        var sum = 0.0
        for (y in 0 until 64) {
            for (x in 0 until 64) {
                val value = luminance(sample.getPixel(x, y))
                gray[y * 64 + x] = value
                sum += value
            }
        }
        if (sample !== source) sample.recycle()

        val mean = sum / gray.size
        var varianceSum = 0.0
        var laplacianSum = 0.0
        var laplacianSquaredSum = 0.0
        var laplacianCount = 0
        for (y in 1 until 63) {
            for (x in 1 until 63) {
                val center = gray[y * 64 + x]
                val laplacian =
                    gray[(y - 1) * 64 + x] + gray[(y + 1) * 64 + x] +
                        gray[y * 64 + x - 1] + gray[y * 64 + x + 1] - 4.0 * center
                laplacianSum += laplacian
                laplacianSquaredSum += laplacian * laplacian
                laplacianCount++
            }
        }
        for (value in gray) varianceSum += (value - mean) * (value - mean)
        val laplacianMean = laplacianSum / laplacianCount.coerceAtLeast(1)
        val sharpness = laplacianSquaredSum / laplacianCount.coerceAtLeast(1) -
            laplacianMean * laplacianMean

        val tiny = Bitmap.createScaledBitmap(source, 9, 8, true)
        var hash = 0L
        var bit = 0
        for (y in 0 until 8) {
            for (x in 0 until 8) {
                if (luminance(tiny.getPixel(x, y)) > luminance(tiny.getPixel(x + 1, y))) {
                    hash = hash or (1L shl bit)
                }
                bit++
            }
        }
        if (tiny !== source) tiny.recycle()

        return Metrics(
            perceptualHash = hash,
            sharpness = sharpness,
            brightness = mean,
            variance = varianceSum / gray.size,
            width = source.width,
            height = source.height,
        )
    }

    fun hammingDistance(first: Long, second: Long): Int =
        java.lang.Long.bitCount(first xor second)

    fun sha256(file: File): String? = try {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { stream ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val count = stream.read(buffer)
                if (count <= 0) break
                digest.update(buffer, 0, count)
            }
        }
        digest.digest().joinToString("") { "%02x".format(it) }
    } catch (_: Exception) { null }

    // ---------- Grouping (ported from PhotoRepository) ----------

    fun findExactDuplicates(photos: List<LensPhoto>): List<FindingGroup> {
        val groups = mutableListOf<FindingGroup>()
        val hashed = photos.mapNotNull { p ->
            val h = sha256(File(p.path)) ?: return@mapNotNull null
            p.copy(exactHash = h)
        }
        for (sameHash in hashed.groupBy { it.exactHash }.values.filter { it.size > 1 }) {
            val keeper = sameHash.maxBy { it.qualityScore }
            groups += FindingGroup(
                id = "exact-${keeper.exactHash}",
                type = FindingType.EXACT_DUPLICATE,
                title = "${sameHash.size} exact copies",
                explanation = "These files contain exactly the same photo. The best copy is marked Keep.",
                photos = sameHash.sortedByDescending { it.qualityScore },
                recommendedDeletePaths = sameHash.mapNotNullTo(mutableSetOf()) {
                    if (it.path == keeper.path) null else it.path
                },
            )
        }
        return groups
    }

    fun findSimilar(photos: List<LensPhoto>): List<FindingGroup> {
        val usable = photos.filter { it.metrics != null }
        if (usable.size < 2) return emptyList()
        val parent = IntArray(usable.size) { it }
        fun root(v: Int): Int {
            var c = v
            while (parent[c] != c) { parent[c] = parent[parent[c]]; c = parent[c] }
            return c
        }
        fun union(a: Int, b: Int) {
            val ra = root(a); val rb = root(b)
            if (ra != rb) parent[rb] = ra
        }
        // Four-band LSH buckets avoid an O(n²) gallery scan.
        val buckets = HashMap<Long, MutableList<Int>>()
        for (i in usable.indices) {
            val hash = usable[i].metrics!!.perceptualHash
            val candidates = mutableSetOf<Int>()
            for (band in 0 until 4) {
                val value = (hash ushr (band * 16)) and 0xffffL
                buckets[(band.toLong() shl 16) or value]?.let(candidates::addAll)
            }
            for (cand in candidates) {
                val other = usable[cand]
                val aspectA = usable[i].metrics!!.width.toDouble() / usable[i].metrics!!.height.coerceAtLeast(1)
                val aspectB = other.metrics!!.width.toDouble() / other.metrics!!.height.coerceAtLeast(1)
                if (abs(aspectA - aspectB) < 0.08 &&
                    hammingDistance(hash, other.metrics!!.perceptualHash) <= 7
                ) union(i, cand)
            }
            for (band in 0 until 4) {
                val value = (hash ushr (band * 16)) and 0xffffL
                buckets.getOrPut((band.toLong() shl 16) or value) { mutableListOf() }.add(i)
            }
        }
        return usable.indices.groupBy(::root).values.filter { it.size > 1 }.map { idx ->
            val group = idx.map(usable::get).sortedByDescending { it.qualityScore }
            FindingGroup(
                id = "similar-${group.first().path.hashCode()}",
                type = FindingType.SIMILAR,
                title = "${group.size} similar photos",
                explanation = "These look alike. The sharpest, best-exposed photo is marked Keep.",
                photos = group,
                recommendedDeletePaths = group.drop(1).mapTo(mutableSetOf()) { it.path },
            )
        }
    }

    fun classifyLowQuality(photo: LensPhoto): FindingGroup? {
        val m = photo.metrics ?: return null
        val (type, title, explanation) = when {
            m.variance < 0.0012 -> Triple(
                FindingType.BLANK, "Possible blank or pocket photo",
                "This image has almost no visual detail. Review it before removing it.")
            m.brightness < 0.055 -> Triple(
                FindingType.DARK, "Extremely dark photo",
                "Almost nothing is visible. Check whether it is a memory worth keeping.")
            m.sharpness < 0.00042 && m.variance > 0.003 -> Triple(
                FindingType.BLURRY, "Possibly blurry photo",
                "This photo has unusually soft detail. Zoom in before deciding.")
            else -> return null
        }
        return FindingGroup(
            id = "quality-${photo.path.hashCode()}",
            type = type, title = title, explanation = explanation,
            photos = listOf(photo),
            // Quality judgements are less certain — nothing pre-selected.
            recommendedDeletePaths = emptySet(),
        )
    }

    /** Decode a bounded bitmap for analysis (max ~256px each side). */
    fun decodeThumbnail(file: File): Bitmap? {
        val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, opts)
        if (opts.outWidth <= 0) return null
        var sample = 1
        while (opts.outWidth / (sample * 2) >= 256 || opts.outHeight / (sample * 2) >= 256) sample *= 2
        val load = BitmapFactory.Options().apply { inSampleSize = sample }
        return BitmapFactory.decodeFile(file.absolutePath, load)
    }

    private fun luminance(color: Int): Double {
        val r = Color.red(color) / 255.0
        val g = Color.green(color) / 255.0
        val b = Color.blue(color) / 255.0
        return 0.2126 * r + 0.7152 * g + 0.0722 * b
    }
}
