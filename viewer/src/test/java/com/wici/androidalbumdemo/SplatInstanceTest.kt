package com.wici.androidalbumdemo

import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class SplatInstanceTest {
    @Test
    fun asymmetricInstancePinsProductionStrideAndEveryAttributeOffset() {
        val center = floatArrayOf(-1.25f, 2.5f, -4.75f)
        val color = floatArrayOf(0.11f, 0.37f, 0.59f, 0.73f)
        val ellipse = floatArrayOf(13f, -7f, 3f, 11f, 0.019f, -0.007f, 0.031f)
        val buffer = ByteBuffer.allocate(SplatInstance.BYTES).order(ByteOrder.nativeOrder())

        SplatInstance.write(buffer, center, 0, color, 0, ellipse)

        assertEquals(56, SplatInstance.BYTES)
        assertEquals(0, SplatInstance.CENTER_OFFSET_BYTES)
        assertEquals(12, SplatInstance.COLOR_OFFSET_BYTES)
        assertEquals(28, SplatInstance.AXIS0_OFFSET_BYTES)
        assertEquals(36, SplatInstance.AXIS1_OFFSET_BYTES)
        assertEquals(44, SplatInstance.CONIC_OFFSET_BYTES)
        buffer.flip()
        val actual = FloatArray(SplatInstance.FLOATS) { buffer.float }
        assertArrayEquals(center + color + ellipse, actual, 0f)
    }
}
