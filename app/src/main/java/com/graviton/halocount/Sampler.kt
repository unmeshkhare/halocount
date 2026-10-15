package com.graviton.halocount

import androidx.camera.core.ImageProxy

/**
 * Reads the Y (luma) plane of a camera frame and writes a small, upright grayscale grid.
 * Each grid cell averages four samples, which is cheap and removes most sensor noise.
 */
object Sampler {

    /** Size of the frame after rotating it upright, as (width, height). */
    fun uprightSize(img: ImageProxy): Pair<Int, Int> {
        val crop = img.cropRect
        val cw = crop.width()
        val ch = crop.height()
        val rot = img.imageInfo.rotationDegrees
        return if (rot == 90 || rot == 270) Pair(ch, cw) else Pair(cw, ch)
    }

    fun fill(img: ImageProxy, dst: ByteArray, gw: Int, gh: Int) {
        val plane = img.planes[0]
        val buf = plane.buffer
        val rs = plane.rowStride
        val ps = plane.pixelStride
        val crop = img.cropRect
        val cl = crop.left
        val ct = crop.top
        val cw = crop.width()
        val ch = crop.height()
        val rot = img.imageInfo.rotationDegrees
        val swap = rot == 90 || rot == 270
        val uw = if (swap) ch else cw
        val uh = if (swap) cw else ch
        val cellW = uw.toFloat() / gw
        val cellH = uh.toFloat() / gh
        var o = 0
        for (gy in 0 until gh) {
            for (gx in 0 until gw) {
                var sum = 0
                for (k in 0..3) {
                    val fx = (gx + 0.25f + 0.5f * (k and 1)) * cellW
                    val fy = (gy + 0.25f + 0.5f * (k shr 1)) * cellH
                    var ux = fx.toInt()
                    if (ux > uw - 1) ux = uw - 1
                    var uy = fy.toInt()
                    if (uy > uh - 1) uy = uh - 1
                    var sx: Int
                    var sy: Int
                    when (rot) {
                        90 -> {
                            sx = uy
                            sy = ch - 1 - ux
                        }
                        180 -> {
                            sx = cw - 1 - ux
                            sy = ch - 1 - uy
                        }
                        270 -> {
                            sx = cw - 1 - uy
                            sy = ux
                        }
                        else -> {
                            sx = ux
                            sy = uy
                        }
                    }
                    sum += buf.get((ct + sy) * rs + (cl + sx) * ps).toInt() and 0xFF
                }
                dst[o] = (sum shr 2).toByte()
                o++
            }
        }
    }
}
