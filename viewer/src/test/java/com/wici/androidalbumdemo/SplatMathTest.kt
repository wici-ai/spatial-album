package com.wici.androidalbumdemo

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class SplatMathTest {
    @Test
    fun asymmetricWireQuaternionAndCovarianceMatchFixedOracle() {
        val q = SplatMath.normalizeWireQuaternion(0.31f, -0.47f, 0.59f, 0.23f)
        assertArrayEquals(
            floatArrayOf(0.36584698f, -0.5546712f, 0.6962894f, 0.27143484f),
            floatArrayOf(q.w, q.x, q.y, q.z),
            1e-6f,
        )
        val covariance = FloatArray(6)
        SplatMath.writeCovarianceFromDirectScales(
            covariance, 0, 0.17f, 0.29f, 0.43f, 0.31f, -0.47f, 0.59f, 0.23f,
        )
        assertArrayEquals(
            floatArrayOf(0.08772045f, 0.012756889f, -0.0175202f, 0.12785725f, -0.07189341f, 0.08232231f),
            covariance,
            1e-6f,
        )
    }

    @Test
    fun changingWireQuaternionOrderFailsTheAsymmetricOracle() {
        val expected = FloatArray(6)
        val reordered = FloatArray(6)
        SplatMath.writeCovarianceFromDirectScales(expected, 0, 0.17f, 0.29f, 0.43f, 0.31f, -0.47f, 0.59f, 0.23f)
        SplatMath.writeCovarianceFromDirectScales(reordered, 0, 0.17f, 0.29f, 0.43f, 0.23f, 0.31f, -0.47f, 0.59f)
        assertFalse(expected.zip(reordered).all { (a, b) -> kotlin.math.abs(a - b) < 1e-6f })
        assertEquals(6, reordered.size)
    }
}
