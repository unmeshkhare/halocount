package com.graviton.halocount

import java.util.Arrays
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

private const val ON: Byte = 1
private const val OFF: Byte = 0
private const val PIF = 3.14159265f

/** One counted object, in grid coordinates. */
class Blob(
    @JvmField var x: Float,
    @JvmField var y: Float,
    @JvmField var r: Float,
    @JvmField var area: Float,
    @JvmField var manual: Boolean = false
)

class Result(
    @JvmField val blobs: ArrayList<Blob>,
    @JvmField val gw: Int,
    @JvmField val gh: Int,
    /** brightness gap between objects and background (0..255); small = hard to see */
    @JvmField val contrast: Float,
    /** true when the reference object size was learned automatically */
    @JvmField val autoSize: Boolean
)

/**
 * Pure-Kotlin object counter that works on a small grayscale grid.
 *
 * Pipeline: Otsu threshold -> polarity from the border -> 3x3 majority clean-up ->
 * chamfer distance transform -> h-maxima watershed seeds (splits touching objects) ->
 * dust filter -> size-based split of merged blobs.
 *
 * Not thread-safe: one instance per thread. Fill [gray] and then call [analyze].
 */
class Counter(@JvmField val w: Int, @JvmField val h: Int) {

    private val n = w * h

    /** Input luma grid, row-major, one unsigned byte per cell. */
    @JvmField
    val gray = ByteArray(n)

    private val mask = ByteArray(n)
    private val tmp = ByteArray(n)
    private val dt = IntArray(n)
    private val label = IntArray(n)
    private val order = IntArray(n)
    private val parent = IntArray(n)
    private val peak = IntArray(n)
    private val hist = IntArray(256)
    private val off = intArrayOf(-w - 1, -w, -w + 1, -1, 1, w - 1, w, w + 1)
    private val roots = IntArray(8)

    private fun g(i: Int): Int = gray[i].toInt() and 0xFF

    private fun find(a: Int): Int {
        var r = a
        while (parent[r] != r) r = parent[r]
        var c = a
        while (parent[c] != r) {
            val nx = parent[c]
            parent[c] = r
            c = nx
        }
        return r
    }

    private fun empty(contrast: Float): Result =
        Result(ArrayList(), w, h, contrast, true)

    /**
     * @param refAreaFrac reference object area as a fraction of the grid area,
     *                    or 0 to learn it automatically.
     */
    fun analyze(refAreaFrac: Float): Result {
        // ---- 1. Otsu threshold -------------------------------------------------
        Arrays.fill(hist, 0)
        for (i in 0 until n) hist[g(i)]++
        var sumAll = 0L
        for (i in 0..255) sumAll += i.toLong() * hist[i]
        var wB = 0L
        var sumB = 0L
        var bestT = 127
        var bestV = -1.0
        for (t in 0..255) {
            wB += hist[t]
            if (wB == 0L) continue
            val wF = n.toLong() - wB
            if (wF == 0L) break
            sumB += t.toLong() * hist[t]
            val mB = sumB.toDouble() / wB
            val mF = (sumAll - sumB).toDouble() / wF
            val v = wB.toDouble() * wF.toDouble() * (mB - mF) * (mB - mF)
            if (v > bestV) {
                bestV = v
                bestT = t
            }
        }
        var cD = 0L
        var sD = 0L
        for (t in 0..bestT) {
            cD += hist[t]
            sD += t.toLong() * hist[t]
        }
        val cL = n.toLong() - cD
        val sL = sumAll - sD
        val mD = if (cD > 0L) sD.toFloat() / cD else 0f
        val mL = if (cL > 0L) sL.toFloat() / cL else 255f
        val contrast = mL - mD
        if (contrast < MIN_CONTRAST) return empty(contrast)

        // ---- 2. polarity: the background is what touches the frame border ------
        var bs = 0L
        var bc = 0
        for (x in 0 until w) {
            bs += g(x) + g((h - 1) * w + x)
            bc += 2
        }
        for (y in 1 until h - 1) {
            bs += g(y * w) + g(y * w + w - 1)
            bc += 2
        }
        val darkObjects = (bs.toFloat() / bc) > bestT
        val t = bestT
        for (i in 0 until n) {
            val v = g(i)
            val isObj = if (darkObjects) v <= t else v > t
            mask[i] = if (isObj) ON else OFF
        }

        // ---- 3. 3x3 majority filter, frame border forced to background ---------
        Arrays.fill(tmp, OFF)
        for (y in 1 until h - 1) {
            val row = y * w
            for (x in 1 until w - 1) {
                val i = row + x
                val s = mask[i - w - 1] + mask[i - w] + mask[i - w + 1] +
                    mask[i - 1] + mask[i] + mask[i + 1] +
                    mask[i + w - 1] + mask[i + w] + mask[i + w + 1]
                if (s >= 5) tmp[i] = ON
            }
        }

        // ---- 4. chamfer 3-4 distance transform ---------------------------------
        for (i in 0 until n) dt[i] = if (tmp[i] == ON) INF else 0
        for (y in 1 until h - 1) {
            val row = y * w
            for (x in 1 until w - 1) {
                val i = row + x
                if (dt[i] != 0) {
                    var v = dt[i]
                    var a = dt[i - 1] + 3
                    if (a < v) v = a
                    a = dt[i - w - 1] + 4
                    if (a < v) v = a
                    a = dt[i - w] + 3
                    if (a < v) v = a
                    a = dt[i - w + 1] + 4
                    if (a < v) v = a
                    dt[i] = v
                }
            }
        }
        for (y in h - 2 downTo 1) {
            val row = y * w
            for (x in w - 2 downTo 1) {
                val i = row + x
                if (dt[i] != 0) {
                    var v = dt[i]
                    var a = dt[i + 1] + 3
                    if (a < v) v = a
                    a = dt[i + w + 1] + 4
                    if (a < v) v = a
                    a = dt[i + w] + 3
                    if (a < v) v = a
                    a = dt[i + w - 1] + 4
                    if (a < v) v = a
                    dt[i] = v
                }
            }
        }

        // ---- 5. counting sort of object cells, highest distance first ----------
        var maxDt = 0
        for (i in 0 until n) if (dt[i] > maxDt) maxDt = dt[i]
        if (maxDt == 0) return empty(contrast)
        val cnt = IntArray(maxDt + 2)
        for (i in 0 until n) {
            val d = dt[i]
            if (d > 0) cnt[d]++
        }
        var acc = 0
        for (v in maxDt downTo 1) {
            val c = cnt[v]
            cnt[v] = acc
            acc += c
        }
        val total = acc
        for (i in 0 until n) {
            val d = dt[i]
            if (d > 0) {
                order[cnt[d]] = i
                cnt[d]++
            }
        }

        // ---- 6. h-maxima watershed: grow seeds from the top, merge shallow ones -
        Arrays.fill(label, -1)
        var k = 0
        for (idx in 0 until total) {
            val p = order[idx]
            val d = dt[p]
            var m = 0
            for (o in 0 until 8) {
                val l = label[p + off[o]]
                if (l >= 0) {
                    val r = find(l)
                    var dup = false
                    for (j in 0 until m) {
                        if (roots[j] == r) {
                            dup = true
                            break
                        }
                    }
                    if (!dup) {
                        roots[m] = r
                        m++
                    }
                }
            }
            if (m == 0) {
                parent[k] = k
                peak[k] = d
                label[p] = k
                k++
            } else {
                var s = roots[0]
                for (j in 1 until m) {
                    val c = roots[j]
                    if (peak[c] > peak[s] || (peak[c] == peak[s] && c < s)) s = c
                }
                for (j in 0 until m) {
                    val c = roots[j]
                    if (c != s) {
                        val hh = max(HMIN, (HREL * peak[c]).toInt())
                        if (peak[c] - d <= hh) parent[c] = s
                    }
                }
                label[p] = s
            }
        }

        // ---- 7. gather per-object statistics -----------------------------------
        val idxOf = IntArray(k)
        var rc = 0
        for (c in 0 until k) {
            if (find(c) == c) {
                idxOf[c] = rc
                rc++
            } else {
                idxOf[c] = -1
            }
        }
        val rp = IntArray(rc)
        for (c in 0 until k) if (idxOf[c] >= 0) rp[idxOf[c]] = peak[c]
        val area = IntArray(rc)
        val sx = DoubleArray(rc)
        val sy = DoubleArray(rc)
        val sxx = DoubleArray(rc)
        val sxy = DoubleArray(rc)
        val syy = DoubleArray(rc)
        val csx = DoubleArray(rc)
        val csy = DoubleArray(rc)
        val cn = IntArray(rc)
        for (i in 0 until total) {
            val p = order[i]
            val r = idxOf[find(label[p])]
            val x = (p % w).toDouble()
            val y = (p / w).toDouble()
            area[r]++
            sx[r] += x
            sy[r] += y
            sxx[r] += x * x
            sxy[r] += x * y
            syy[r] += y * y
            if (dt[p] * 10 >= 7 * rp[r]) {
                csx[r] += x
                csy[r] += y
                cn[r]++
            }
        }

        // ---- 8. filters: giant regions, dust ------------------------------------
        val keep = ArrayList<Int>()
        for (r in 0 until rc) if (area[r] <= GIANT * n) keep.add(r)
        if (keep.isEmpty()) return empty(contrast)
        val peaks = IntArray(keep.size)
        for (i in keep.indices) peaks[i] = rp[keep[i]]
        peaks.sort()
        val medPeak = peaks[peaks.size / 2]
        val minPeak = max(MIN_PEAK, (0.4f * medPeak).toInt())
        val kept = ArrayList<Int>()
        for (r in keep) if (rp[r] >= minPeak) kept.add(r)
        if (kept.isEmpty()) return empty(contrast)

        // ---- 9. reference size (manual or automatic) ---------------------------
        var ref = 0f
        var auto = true
        if (refAreaFrac > 0f) {
            ref = refAreaFrac * n
            auto = false
        } else if (kept.size >= 5) {
            val ar = IntArray(kept.size)
            for (i in kept.indices) ar[i] = area[kept[i]]
            ar.sort()
            ref = ar[ar.size / 2].toFloat()
        }
        val finalList = ArrayList<Int>()
        for (r in kept) if (ref <= 0f || area[r] >= 0.3f * ref) finalList.add(r)

        // ---- 10. emit blobs; split merged ones along their main axis -----------
        val out = ArrayList<Blob>()
        for (r in finalList) {
            val a = area[r].toFloat()
            var count = 1
            if (ref > 0f && a > SPLIT_RATIO * ref) count = max(2, (a / ref).roundToInt())
            val rEq = max(rp[r] / 3f, sqrt(a / PIF))
            if (count == 1) {
                val nn = max(1, cn[r])
                out.add(Blob((csx[r] / nn).toFloat(), (csy[r] / nn).toFloat(), rEq, a))
            } else {
                val mx = sx[r] / a
                val my = sy[r] / a
                val vxx = sxx[r] / a - mx * mx
                val vyy = syy[r] / a - my * my
                val vxy = sxy[r] / a - mx * my
                val l1 = (vxx + vyy) / 2.0 + sqrt(((vxx - vyy) / 2.0) * ((vxx - vyy) / 2.0) + vxy * vxy)
                val th = 0.5 * atan2(2.0 * vxy, vxx - vyy)
                val len = sqrt(12.0 * max(l1, 0.0))
                val each = a / count
                val er = sqrt(each / PIF)
                for (j in 0 until count) {
                    val tt = ((j + 0.5) / count - 0.5) * len
                    out.add(
                        Blob(
                            (mx + cos(th) * tt).toFloat(),
                            (my + sin(th) * tt).toFloat(),
                            er,
                            each
                        )
                    )
                }
            }
        }
        return Result(out, w, h, contrast, auto)
    }

    companion object {
        private const val INF = 1_000_000
        private const val HMIN = 6
        private const val HREL = 0.36f
        private const val MIN_PEAK = 6
        private const val SPLIT_RATIO = 1.65f
        private const val GIANT = 0.35f
        const val MIN_CONTRAST = 14f
    }
}
