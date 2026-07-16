package com.wici.androidalbumdemo

import kotlin.math.abs
import kotlin.math.sqrt

internal data class BoundedPan(
    val x: Float,
    val y: Float,
    val maxX: Float,
    val maxY: Float,
    val clamped: Boolean
)

/** Keeps camera translation inside an ellipse expressed as a fraction of the capture area. */
internal object PanBounds {
    fun clamp(
        x: Float,
        y: Float,
        referenceDepth: Float,
        focalPx: Float,
        contentWidthPx: Float,
        contentHeightPx: Float,
        maxShiftFraction: Float
    ): BoundedPan {
        if (
            !x.isFinite() || !y.isFinite() ||
            !referenceDepth.isFinite() || referenceDepth <= 0f ||
            !focalPx.isFinite() || focalPx <= 0f ||
            !contentWidthPx.isFinite() || contentWidthPx <= 0f ||
            !contentHeightPx.isFinite() || contentHeightPx <= 0f ||
            !maxShiftFraction.isFinite() || maxShiftFraction <= 0f
        ) {
            return BoundedPan(0f, 0f, 0f, 0f, clamped = x != 0f || y != 0f)
        }

        val worldUnitsPerPixel = referenceDepth / focalPx
        val maxX = worldUnitsPerPixel * contentWidthPx * maxShiftFraction
        val maxY = worldUnitsPerPixel * contentHeightPx * maxShiftFraction
        val normalizedX = x / maxX
        val normalizedY = y / maxY
        val normalizedLength = sqrt(normalizedX * normalizedX + normalizedY * normalizedY)
        if (!normalizedLength.isFinite() || normalizedLength <= 1f) {
            return BoundedPan(x, y, maxX, maxY, clamped = false)
        }

        val scale = 1f / normalizedLength
        val boundedX = x * scale
        val boundedY = y * scale
        return BoundedPan(
            x = boundedX,
            y = boundedY,
            maxX = maxX,
            maxY = maxY,
            clamped = abs(boundedX - x) > 1e-6f || abs(boundedY - y) > 1e-6f
        )
    }
}
