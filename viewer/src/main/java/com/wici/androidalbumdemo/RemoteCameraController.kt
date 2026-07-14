package com.wici.androidalbumdemo

import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.sin
import kotlin.math.sqrt

class RemoteCameraController(session: RemoteRenderSession) {
    private val initialCamera = session.camera.copy(w2c = session.camera.w2c.copyOf())
    private val initialPivot = Vec3.from(session.pivot)
    private val initialFocusDistance = session.focusDistance
    private val maxFramePixels = session.limits.maxFramePixels
    private val initialWorldUp = initialCamera.row(1) * -1.0

    private var currentCamera = initialCamera.copy(w2c = initialCamera.w2c.copyOf())
    private var currentPivot = initialPivot
    private var currentFocusDistance = initialFocusDistance

    init {
        initialCamera.validated(maxFramePixels)
        require(initialFocusDistance.isFinite() && initialFocusDistance > 0.0) { "focusDistance must be positive" }
    }

    fun camera(): RemoteCamera = currentCamera.copy(w2c = currentCamera.w2c.copyOf())
    fun pivot(): DoubleArray = currentPivot.toArray()
    fun focusDistance(): Double = currentFocusDistance

    fun reset(): RemoteCamera {
        currentCamera = initialCamera.copy(w2c = initialCamera.w2c.copyOf())
        currentPivot = initialPivot
        currentFocusDistance = initialFocusDistance
        return camera()
    }

    fun resize(width: Int, height: Int): RemoteCamera {
        currentCamera = currentCamera.resized(width, height, maxFramePixels)
        return camera()
    }

    /** Orbit angles are radians around the initial scene-up axis and the camera right axis. */
    fun orbit(yawRadians: Double, pitchRadians: Double): RemoteCamera {
        require(yawRadians.isFinite() && pitchRadians.isFinite()) { "orbit angles must be finite" }
        var offset = currentCamera.eye() - currentPivot
        offset = offset.rotated(initialWorldUp, yawRadians)
        val yawedForward = (offset * -1.0).normalized()
        val yawedRight = yawedForward.cross(initialWorldUp).normalized()
        val pitched = offset.rotated(yawedRight, pitchRadians)
        val candidateForward = (pitched * -1.0).normalized()
        require(abs(candidateForward.dot(initialWorldUp)) < 0.9999) { "orbit would cross the navigation pole" }
        currentCamera = lookAt(currentPivot + pitched, currentPivot, initialWorldUp, currentCamera)
            .validated(maxFramePixels)
        return camera()
    }

    /** Pan deltas are viewport pixels; the pivot moves with the camera. */
    fun pan(deltaPixelsX: Double, deltaPixelsY: Double): RemoteCamera {
        require(deltaPixelsX.isFinite() && deltaPixelsY.isFinite()) { "pan deltas must be finite" }
        val right = currentCamera.row(0)
        val down = currentCamera.row(1)
        val shift = right * (-deltaPixelsX * currentFocusDistance / currentCamera.intrinsics.fx) +
            down * (-deltaPixelsY * currentFocusDistance / currentCamera.intrinsics.fy)
        val newPivot = currentPivot + shift
        require(newPivot.isFinite()) { "pan exceeds finite camera range" }
        val candidate = currentCamera.withEye(currentCamera.eye() + shift).validated(maxFramePixels)
        currentPivot = newPivot
        currentCamera = candidate
        return camera()
    }

    /** Positive delta moves closer; the exponential mapping cannot cross the pivot. */
    fun dolly(delta: Double): RemoteCamera {
        require(delta.isFinite()) { "dolly delta must be finite" }
        val scale = exp((-delta).coerceIn(-4.605170185988091, 4.605170185988091))
        val eye = currentCamera.eye()
        val offset = eye - currentPivot
        val newFocusDistance = (currentFocusDistance * scale).coerceAtLeast(1e-6)
        require(newFocusDistance.isFinite()) { "dolly exceeds finite focus range" }
        val candidate = currentCamera.withEye(currentPivot + offset * scale).validated(maxFramePixels)
        currentCamera = candidate
        currentFocusDistance = newFocusDistance
        return camera()
    }

    companion object {
        /**
         * The sole Nerfstudio/OpenGL -> protocol conversion. OpenGL C2W uses
         * +X right, +Y up, -Z forward; protocol camera space is OpenCV +X right,
         * +Y down, +Z forward. Therefore W2C_cv = diag(1,-1,-1) * inverse(C2W_gl).
         */
        fun fromNerfstudioOpenGl(
            metadata: SceneViewMetadata,
            pivot: DoubleArray,
            focusDistance: Double,
            maxFramePixels: Int,
            sessionId: String = "metadata",
        ): RemoteCameraController {
            metadata.validated()
            require(pivot.size == 3 && pivot.all(Double::isFinite)) { "pivot must contain three finite values" }
            require(focusDistance.isFinite() && focusDistance > 0.0) { "focusDistance must be positive" }
            val p = metadata.pose.map(Float::toDouble)
            val glW2cRows = arrayOf(
                doubleArrayOf(p[0], p[4], p[8], -(p[0] * p[3] + p[4] * p[7] + p[8] * p[11])),
                doubleArrayOf(p[1], p[5], p[9], -(p[1] * p[3] + p[5] * p[7] + p[9] * p[11])),
                doubleArrayOf(p[2], p[6], p[10], -(p[2] * p[3] + p[6] * p[7] + p[10] * p[11])),
            )
            val matrix = DoubleArray(16)
            for (row in 0..2) {
                val sign = if (row == 0) 1.0 else -1.0
                for (column in 0..3) matrix[row * 4 + column] = sign * glW2cRows[row][column]
            }
            matrix[15] = 1.0
            val camera = RemoteCamera(
                matrix,
                RemoteIntrinsics(metadata.fx.toDouble(), metadata.fy.toDouble(), metadata.cx.toDouble(), metadata.cy.toDouble()),
                metadata.imageWidth,
                metadata.imageHeight,
            ).validated(maxFramePixels)
            return RemoteCameraController(
                RemoteRenderSession(
                    sessionId, camera, pivot.copyOf(), focusDistance,
                    metadata.imageWidth, metadata.imageHeight,
                    RemoteRenderLimits(maxFramePixels, 1, 1.0),
                ),
            )
        }

        private fun lookAt(eye: Vec3, pivot: Vec3, worldUp: Vec3, template: RemoteCamera): RemoteCamera {
            val forward = (pivot - eye).normalized()
            val right = forward.cross(worldUp).normalized()
            val down = forward.cross(right).normalized()
            val matrix = doubleArrayOf(
                right.x, right.y, right.z, -right.dot(eye),
                down.x, down.y, down.z, -down.dot(eye),
                forward.x, forward.y, forward.z, -forward.dot(eye),
                0.0, 0.0, 0.0, 1.0,
            )
            return template.copy(w2c = matrix)
        }
    }
}

private data class Vec3(val x: Double, val y: Double, val z: Double) {
    operator fun plus(other: Vec3) = Vec3(x + other.x, y + other.y, z + other.z)
    operator fun minus(other: Vec3) = Vec3(x - other.x, y - other.y, z - other.z)
    operator fun times(value: Double) = Vec3(x * value, y * value, z * value)
    fun dot(other: Vec3) = x * other.x + y * other.y + z * other.z
    fun cross(other: Vec3) = Vec3(y * other.z - z * other.y, z * other.x - x * other.z, x * other.y - y * other.x)
    fun norm() = sqrt(dot(this))
    fun isFinite() = x.isFinite() && y.isFinite() && z.isFinite()
    fun normalized(): Vec3 {
        val length = norm()
        require(length.isFinite() && length > 1e-12) { "degenerate camera vector" }
        return this * (1.0 / length)
    }
    fun rotated(axis: Vec3, radians: Double): Vec3 {
        val unit = axis.normalized()
        val c = cos(radians)
        val s = sin(radians)
        return this * c + unit.cross(this) * s + unit * (unit.dot(this) * (1.0 - c))
    }
    fun toArray() = doubleArrayOf(x, y, z)

    companion object {
        fun from(value: DoubleArray): Vec3 {
            require(value.size == 3 && value.all(Double::isFinite)) { "pivot must contain three finite values" }
            return Vec3(value[0], value[1], value[2])
        }
    }
}

private fun RemoteCamera.row(index: Int) = Vec3(w2c[index * 4], w2c[index * 4 + 1], w2c[index * 4 + 2])

private fun RemoteCamera.eye(): Vec3 {
    val translation = Vec3(w2c[3], w2c[7], w2c[11])
    return Vec3(
        -(w2c[0] * translation.x + w2c[4] * translation.y + w2c[8] * translation.z),
        -(w2c[1] * translation.x + w2c[5] * translation.y + w2c[9] * translation.z),
        -(w2c[2] * translation.x + w2c[6] * translation.y + w2c[10] * translation.z),
    )
}

private fun RemoteCamera.withEye(eye: Vec3): RemoteCamera {
    require(eye.isFinite()) { "camera position must be finite" }
    val matrix = w2c.copyOf()
    matrix[3] = -row(0).dot(eye)
    matrix[7] = -row(1).dot(eye)
    matrix[11] = -row(2).dot(eye)
    return copy(w2c = matrix)
}
