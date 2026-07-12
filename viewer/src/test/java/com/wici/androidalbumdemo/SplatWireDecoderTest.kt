package com.wici.androidalbumdemo

import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SplatWireDecoderTest {
    private fun row(alpha: Int = 173, x: Float = 1.25f): ByteArray =
        ByteBuffer.allocate(32).order(ByteOrder.LITTLE_ENDIAN).apply {
            putFloat(x); putFloat(-2.5f); putFloat(3.75f)
            putFloat(0.17f); putFloat(0.29f); putFloat(0.43f)
            put(19); put(83); put(211.toByte()); put(alpha.toByte())
            put(168.toByte()); put(68); put(204.toByte()); put(157.toByte())
        }.array()

    @Test fun decodesAsymmetricCanonicalRowAtNonzeroBoundary() {
        val framed = byteArrayOf(7, 9, 11) + row() + byteArrayOf(13)
        val decoded = SplatWireDecoder.decodeRow(framed, 3)
        assertEquals(1.25f, decoded.x, 0f)
        assertEquals(0.17f, decoded.sx, 0f)
        assertEquals(19, decoded.r); assertEquals(173, decoded.a)
        assertEquals(0.3125f, decoded.qw, 0f)
        assertEquals(-0.46875f, decoded.qx, 0f)
        assertEquals(0.59375f, decoded.qy, 0f)
        assertEquals(0.2265625f, decoded.qz, 0f)
        assertTrue(decoded.numericValid)
    }

    @Test fun exposesAlphaFilteredAndInvalidRowsWithoutHidingThem() {
        assertEquals(4, SplatWireDecoder.decodeRow(row(alpha = 4)).a)
        assertFalse(SplatWireDecoder.decodeRow(row(x = Float.NaN)).numericValid)
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsPartialTrailingBytes() { SplatWireDecoder.decodeRows(row() + byteArrayOf(1)) }

    @Test fun conservesCompleteRows() {
        assertEquals(2, SplatWireDecoder.decodeRows(row() + row(alpha = 4)).size)
    }
}
