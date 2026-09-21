package com.filezen.files.core.delta

import java.io.File
import java.io.InputStream

/**
 * FastCDC content-defined chunker — a Kotlin port of Google's
 * cdc-file-transfer `fastcdc.h` (https://github.com/google/cdc-file-transfer,
 * Apache-2.0), 32-bit gear variant with the "regression hashing" boundary rule:
 * a boundary is declared when the rolling hash dips under
 * `threshold = MAX_UINT32 / (avg-min+1)`, preferring the point that cleared the
 * most high bits.
 *
 * The chunker itself is pure Kotlin — it only uses java.io — so it runs
 * identically on the JVM and Android.
 */
class FastCdc(
    val minSize: Int = 4 * 1024,
    val avgSize: Int = 16 * 1024,
    val maxSize: Int = 64 * 1024,
) {
    init {
        require(avgSize >= 1 && minSize <= avgSize && avgSize <= maxSize)
    }

    /** Boundary criterion: hash <= threshold (unsigned compare). */
    private val threshold: Int = (-1L and 0xffff_ffffL).div(avgSize - minSize + 1L).toInt()

    data class Chunk(val offset: Long, val length: Int, val sha256: ByteArray)

    /** Stream [input] and invoke [onChunk] for each content-defined chunk. */
    fun chunks(input: InputStream, onChunk: (offset: Long, bytes: ByteArray) -> Unit) {
        val buf = ByteArray(64 * 1024)
        var data = ByteArray(0)
        var base = 0L
        while (true) {
            // Ensure we have at least maxSize bytes buffered (or EOF).
            if (data.size < maxSize) {
                val need = maxSize - data.size
                val tmp = ByteArray(need.coerceAtMost(buf.size))
                var filled = 0
                var eof = false
                while (filled < tmp.size) {
                    val n = input.read(tmp, filled, tmp.size - filled)
                    if (n < 0) { eof = true; break }
                    filled += n
                }
                data = if (filled == 0) data else data + tmp.copyOf(filled)
                if (eof && data.isEmpty()) return
            }
            if (data.isEmpty()) return
            val len = minOf(data.size, maxSize)
            val cut = findBoundary(data, len)
            onChunk(base, data.copyOfRange(0, cut))
            base += cut
            data = data.copyOfRange(cut, data.size)
            if (data.isEmpty() && cut == 0) return
        }
    }

    /** Port of ChunkerTmpl::FindChunkBoundary — index of the boundary cut. */
    private fun findBoundary(data: ByteArray, len0: Int): Int {
        var len = len0
        if (len > maxSize) len = maxSize
        var rcLen = len
        var rcMask = 0L
        var hash = -1 // all 1s, as in the C++ version
        var i = if (minSize > 32) minSize - 32 else 0
        while (i < minSize && i < len) {
            hash = (hash shl 1) + GEAR32[data[i].toInt() and 0xff]
            i++
        }
        if (len <= minSize) return len // buffer smaller than min chunk
        while (i < len) {
            if ((hash.toLong() and rcMask) == 0L) {
                if ((hash.toLong() and 0xffff_ffffL) <= (threshold.toLong() and 0xffff_ffffL)) {
                    return i
                }
                rcLen = i
                rcMask = 0xffff_ffffL
                while ((hash.toLong() and rcMask) != 0L) rcMask = rcMask shl 1
            }
            hash = (hash shl 1) + GEAR32[data[i].toInt() and 0xff]
            i++
        }
        return if ((hash.toLong() and rcMask) != 0L) rcLen.coerceAtMost(len) else i
    }

    companion object {
        private val GEAR32 = intArrayOf(
    -894406945, -1301184200, 1008879219, 1000142067, -1724868049, 1286497318, 1557210763, -91398546,
    1036947606, 66507194, 281793990, 2050154352, 1554708864, 518324577, -616682785, -626935909,
    -1970836517, 1428245967, 54322268, -1814418088, 747208347, 2091092942, 1806288607, 1682913319,
    395419145, -1734796209, -1339891188, -1360164379, -1404637022, 2011736956, -2134001562, 486057045,
    875465340, -2110753467, 1750864063, -189474035, -712771947, -281286664, 1866742644, -78421241,
    -772882681, 1069861887, -1672044513, 210199553, 1971500452, -1233255719, -216064694, 814615548,
    -1023678363, 413135462, -382722174, 211417330, -1254706647, -243923061, 2120452991, -1313768645,
    -597061512, -300860738, -531396663, -2092212301, 1702357076, 1177620182, -385262961, 107395620,
    1866360025, 1621178310, 10747505, 623388624, -915888227, 1907225031, -1190451489, -1061723788,
    1403300200, 495771271, -301505241, 2024645613, 572695759, 253814961, 1382420521, 1323725382,
    586691987, -1066701454, -378011343, 1185583123, -1340530398, -1467271137, 998788263, -1643018056,
    685777491, -1583530042, -364537579, -1246124152, -1774026656, 1966417080, 1640614290, 1806120404,
    1722495038, -1128410430, -471893303, 789378653, -839283118, -1237229336, 1804331378, 1163931506,
    -873774578, -643646138, -1240083292, 486309737, 1614186965, -1011035944, -992421247, -1493157590,
    669229246, 1047601914, -1622150442, 984701377, -1548628924, 1423050252, -1620686253, -1215088045,
    400599101, -1420562186, -78330238, -1025711618, -1222067860, 664944755, 902662605, 371640745,
    -1647348739, 708881720, 840727538, -2053137710, -785540801, -883495364, -111266084, -1965402037,
    677964220, -487507986, -1480966994, -348971768, 707665859, 334334288, 1887092494, -154645064,
    -1105510753, -1969707281, -1473651983, -1885349384, 494928635, -529185628, -694371959, 51128770,
    1326334503, 730086490, 1641433594, 614609525, 699103572, 446134166, -1345830725, 1760195817,
    1373733409, 2121968825, -667243404, 385089460, -1585306528, 2037269095, -833116801, -552228882,
    -167145708, 1806981821, 1368603701, 2033008408, 1215484638, -429238372, 1464926121, 345855090,
    1473438892, -1692389187, -1497433408, 2125527677, 1384691934, -42689636, 1401531941, -1868358314,
    128757694, -1610973652, 430188081, -1135076782, -1034847530, -466100688, -681660457, -2087193105,
    -1991442332, -354083863, -1600701196, -794963745, 1699603260, -1721136142, -1296159908, 2113044905,
    -597959993, -1195612137, -1028084734, 1733625995, 1123938932, -682041184, 266368858, 990419683,
    318943612, -1141827307, 821276047, -1976035493, 98413545, 1715973188, 1230441157, 1792562755,
    1617048654, -112110793, 1072957358, -1880040411, 772043298, 1034629886, 1888067391, 1497629380,
    2077983229, -1187966764, -513941249, 1919267678, 268173245, 592098849, 514281176, -2100648012,
    1072167503, -1448384245, -2098685634, -1563206945, -126343227, 1518398, -1568352245, -1923912601,
    -700664531, -958698053, 1379802021, 1434215734, 1097396582, -676528831, -569860560, -1089046269,
    1427780096, -1557088512, -1853997377, 1517899362, -464702634, -1613917161, 820747147, 1848897361
        )
    }
}
