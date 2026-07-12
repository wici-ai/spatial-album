package com.wici.androidalbumdemo

import kotlin.math.sqrt

/** Pure wire-to-render math shared by production decoding and JVM contract tests. */
internal object SplatMath {
    data class Quaternion(val w: Float, val x: Float, val y: Float, val z: Float)

    fun normalizeWireQuaternion(w: Float, x: Float, y: Float, z: Float): Quaternion {
        val norm = sqrt(w * w + x * x + y * y + z * z).coerceAtLeast(1e-8f)
        return Quaternion(w / norm, x / norm, y / norm, z / norm)
    }

    fun writeCovarianceFromDirectScales(
        out: FloatArray,
        base: Int,
        sx: Float,
        sy: Float,
        sz: Float,
        wireW: Float,
        wireX: Float,
        wireY: Float,
        wireZ: Float,
    ) {
        val (w, x, y, z) = normalizeWireQuaternion(wireW, wireX, wireY, wireZ)
        val xx = x * x
        val yy = y * y
        val zz = z * z
        val xy = x * y
        val xz = x * z
        val yz = y * z
        val wx = w * x
        val wy = w * y
        val wz = w * z
        val r00 = 1f - 2f * (yy + zz)
        val r01 = 2f * (xy - wz)
        val r02 = 2f * (xz + wy)
        val r10 = 2f * (xy + wz)
        val r11 = 1f - 2f * (xx + zz)
        val r12 = 2f * (yz - wx)
        val r20 = 2f * (xz - wy)
        val r21 = 2f * (yz + wx)
        val r22 = 1f - 2f * (xx + yy)
        val sx2 = sx * sx
        val sy2 = sy * sy
        val sz2 = sz * sz
        out[base] = r00 * r00 * sx2 + r01 * r01 * sy2 + r02 * r02 * sz2
        out[base + 1] = r00 * r10 * sx2 + r01 * r11 * sy2 + r02 * r12 * sz2
        out[base + 2] = r00 * r20 * sx2 + r01 * r21 * sy2 + r02 * r22 * sz2
        out[base + 3] = r10 * r10 * sx2 + r11 * r11 * sy2 + r12 * r12 * sz2
        out[base + 4] = r10 * r20 * sx2 + r11 * r21 * sy2 + r12 * r22 * sz2
        out[base + 5] = r20 * r20 * sx2 + r21 * r21 * sy2 + r22 * r22 * sz2
    }
}
