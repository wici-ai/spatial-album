package com.wici.androidalbumdemo

import java.nio.ByteBuffer

/** Canonical CPU-to-GLES instance layout. Keep offsets aligned with SplatRenderer.createGeometry. */
internal object SplatInstance {
    const val FLOATS = 14
    const val BYTES = FLOATS * 4
    const val CENTER_OFFSET_BYTES = 0
    const val COLOR_OFFSET_BYTES = 3 * 4
    const val AXIS0_OFFSET_BYTES = 7 * 4
    const val AXIS1_OFFSET_BYTES = 9 * 4
    const val CONIC_OFFSET_BYTES = 11 * 4

    fun write(
        buffer: ByteBuffer,
        center: FloatArray,
        centerOffset: Int,
        color: FloatArray,
        colorOffset: Int,
        ellipse: FloatArray,
    ) {
        repeat(3) { buffer.putFloat(center[centerOffset + it]) }
        repeat(4) { buffer.putFloat(color[colorOffset + it]) }
        repeat(7) { buffer.putFloat(ellipse[it]) }
    }
}
