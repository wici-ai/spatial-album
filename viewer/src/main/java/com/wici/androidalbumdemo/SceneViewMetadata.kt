package com.wici.androidalbumdemo

import kotlin.math.abs

/** Registration-owned camera contract for a multi-view scene artifact. */
data class SceneViewMetadata(
    val version: String,
    val source: String,
    val cameraId: String,
    val imageWidth: Int,
    val imageHeight: Int,
    val fx: Float,
    val fy: Float,
    val cx: Float,
    val cy: Float,
    val poseEncoding: String,
    /** Nerfstudio/OpenGL camera-to-world, row-major 3x4. */
    val pose: FloatArray,
) {
    fun validated(): ValidatedSceneView {
        require(version == VERSION) { "unsupported scene camera version: $version" }
        require(source == SOURCE) { "scene camera must be registration-owned" }
        require(cameraId.isNotBlank()) { "camera_id is required" }
        require(imageWidth > 0 && imageHeight > 0) { "invalid image dimensions" }
        require(listOf(fx, fy, cx, cy).all(Float::isFinite) && fx > 0f && fy > 0f) {
            "invalid camera intrinsics"
        }
        require(cx in 0f..imageWidth.toFloat() && cy in 0f..imageHeight.toFloat()) {
            "principal point is outside the image"
        }
        require(poseEncoding == POSE_ENCODING) { "unsupported pose encoding: $poseEncoding" }
        require(pose.size == 12 && pose.all(Float::isFinite)) { "pose must contain 12 finite values" }

        val r0 = floatArrayOf(pose[0], pose[1], pose[2])
        val r1 = floatArrayOf(pose[4], pose[5], pose[6])
        val r2 = floatArrayOf(pose[8], pose[9], pose[10])
        fun dot(a: FloatArray, b: FloatArray) = a.indices.sumOf { (a[it] * b[it]).toDouble() }.toFloat()
        require(abs(dot(r0, r0) - 1f) <= RIGID_EPSILON &&
            abs(dot(r1, r1) - 1f) <= RIGID_EPSILON &&
            abs(dot(r2, r2) - 1f) <= RIGID_EPSILON &&
            abs(dot(r0, r1)) <= RIGID_EPSILON &&
            abs(dot(r0, r2)) <= RIGID_EPSILON &&
            abs(dot(r1, r2)) <= RIGID_EPSILON
        ) { "pose rotation is not orthonormal" }
        val det = r0[0] * (r1[1] * r2[2] - r1[2] * r2[1]) -
            r0[1] * (r1[0] * r2[2] - r1[2] * r2[0]) +
            r0[2] * (r1[0] * r2[1] - r1[1] * r2[0])
        require(abs(det - 1f) <= RIGID_EPSILON) { "pose rotation must be right-handed" }

        val tx = pose[3]
        val ty = pose[7]
        val tz = pose[11]
        // OpenGL column-major world-to-camera = inverse([R|t]) = [R^T|-R^T t].
        val view = floatArrayOf(
            pose[0], pose[1], pose[2], 0f,
            pose[4], pose[5], pose[6], 0f,
            pose[8], pose[9], pose[10], 0f,
            -(pose[0] * tx + pose[4] * ty + pose[8] * tz),
            -(pose[1] * tx + pose[5] * ty + pose[9] * tz),
            -(pose[2] * tx + pose[6] * ty + pose[10] * tz),
            1f,
        )
        return ValidatedSceneView(this, view)
    }

    companion object {
        const val VERSION = "scene-view-v1"
        const val SOURCE = "registration"
        const val POSE_ENCODING = "nerfstudio-opengl-c2w-row-major-3x4"
        private const val RIGID_EPSILON = 1e-3f
    }
}

class ValidatedSceneView internal constructor(
    val metadata: SceneViewMetadata,
    val worldToCameraColumnMajor: FloatArray,
)
