package com.wici.androidalbumdemo

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import kotlin.math.cos
import kotlin.math.sin

class SceneViewMetadataTest {
    private val angle = 0.63f
    private val c = cos(angle)
    private val s = sin(angle)

    private fun metadata(
        version: String = SceneViewMetadata.VERSION,
        source: String = SceneViewMetadata.SOURCE,
        pose: FloatArray = floatArrayOf(
            c, -s, 0f, 1.25f,
            s, c, 0f, -2.5f,
            0f, 0f, 1f, 3.75f,
        ),
    ) = SceneViewMetadata(
        version, source, "frame_00017", 1537, 991,
        1183.25f, 1179.5f, 760.75f, 501.125f,
        SceneViewMetadata.POSE_ENCODING, pose,
    )

    @Test fun `rigid inverse is the sole world-to-camera conversion`() {
        val result = metadata().validated()
        val expected = floatArrayOf(
            c, -s, 0f, 0f,
            s, c, 0f, 0f,
            0f, 0f, 1f, 0f,
            -(c * 1.25f + s * -2.5f),
            -(-s * 1.25f + c * -2.5f),
            -3.75f, 1f,
        )
        assertArrayEquals(expected, result.worldToCameraColumnMajor, 1e-5f)
        assertEquals("frame_00017", result.metadata.cameraId)
        assertEquals("8e269d6f202b664aa9b0325a78bf881b345724560c8021d184a99d6a8f123c09", result.poseSha256)
        assertEquals("8d16049b0ac01555ce1779e2db4dbdbae3ac4eeaf7095b7fd35d7e4b6bbaed50", result.intrinsicsSha256)
    }

    @Test fun `unknown version and non-registration source fail closed`() {
        assertThrows(IllegalArgumentException::class.java) { metadata(version = "scene-view-v2").validated() }
        assertThrows(IllegalArgumentException::class.java) { metadata(source = "gallery").validated() }
    }

    @Test fun `non-finite and non-rigid poses fail closed`() {
        assertThrows(IllegalArgumentException::class.java) {
            metadata(pose = metadata().pose.copyOf().also { it[3] = Float.NaN }).validated()
        }
        assertThrows(IllegalArgumentException::class.java) {
            metadata(pose = metadata().pose.copyOf().also { it[0] = 2f }).validated()
        }
        assertThrows(IllegalArgumentException::class.java) {
            metadata(pose = metadata().pose.copyOf().also { it[10] = -1f }).validated()
        }
    }
}
