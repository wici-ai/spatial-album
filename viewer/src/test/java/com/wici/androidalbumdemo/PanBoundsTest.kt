package com.wici.androidalbumdemo

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.sqrt

class PanBoundsTest {
    @Test
    fun leavesPanInsideCaptureLimitUnchanged() {
        val result = clamp(x = 0.2f, y = -0.1f)

        assertEquals(0.2f, result.x, EPSILON)
        assertEquals(-0.1f, result.y, EPSILON)
        assertFalse(result.clamped)
    }

    @Test
    fun clampsHorizontalPanToCaptureFraction() {
        val result = clamp(x = 10f, y = 0f)

        assertEquals(0.7f, result.maxX, EPSILON)
        assertEquals(0.35f, result.maxY, EPSILON)
        assertEquals(result.maxX, result.x, EPSILON)
        assertEquals(0f, result.y, EPSILON)
        assertTrue(result.clamped)
    }

    @Test
    fun limitsDiagonalPanToOneEllipticalBudget() {
        val result = clamp(x = 0.7f, y = 0.35f)
        val normalizedLength = sqrt(
            (result.x / result.maxX) * (result.x / result.maxX) +
                (result.y / result.maxY) * (result.y / result.maxY)
        )

        assertEquals(1f, normalizedLength, EPSILON)
        assertTrue(result.x < result.maxX)
        assertTrue(result.y < result.maxY)
        assertTrue(result.clamped)
    }

    @Test
    fun zoomedInReferenceDepthTightensExistingPan() {
        val rest = clamp(x = 10f, y = 0f, referenceDepth = 2f)
        val zoomedIn = clamp(x = rest.x, y = 0f, referenceDepth = 1f)

        assertEquals(rest.maxX * 0.5f, zoomedIn.maxX, EPSILON)
        assertEquals(zoomedIn.maxX, zoomedIn.x, EPSILON)
        assertTrue(zoomedIn.clamped)
    }

    @Test
    fun invalidProjectionResetsUnsafePan() {
        val result = clamp(x = 3f, y = -2f, focalPx = Float.NaN)

        assertEquals(0f, result.x, EPSILON)
        assertEquals(0f, result.y, EPSILON)
        assertTrue(result.clamped)
    }

    private fun clamp(
        x: Float,
        y: Float,
        referenceDepth: Float = 2f,
        focalPx: Float = 1000f
    ): BoundedPan = PanBounds.clamp(
        x = x,
        y = y,
        referenceDepth = referenceDepth,
        focalPx = focalPx,
        contentWidthPx = 1000f,
        contentHeightPx = 500f,
        maxShiftFraction = 0.35f
    )

    private companion object {
        const val EPSILON = 1e-5f
    }
}
