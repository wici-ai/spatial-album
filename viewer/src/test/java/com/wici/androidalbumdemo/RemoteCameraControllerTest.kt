package com.wici.androidalbumdemo

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.cos
import kotlin.math.sin

class RemoteCameraControllerTest {
    @Test fun `controller initializes and resets from server pivot focus and camera`() {
        val session = session()
        val controller = RemoteCameraController(session)
        assertArrayEquals(session.pivot, controller.pivot(), 0.0)
        assertEquals(6.25, controller.focusDistance(), 0.0)
        controller.pan(20.0, -11.0)
        controller.dolly(0.4)
        controller.orbit(0.2, -0.1)
        controller.reset()
        assertArrayEquals(session.camera.w2c, controller.camera().w2c, 0.0)
        assertArrayEquals(doubleArrayOf(3.0, -4.0, 5.0), controller.pivot(), 0.0)
        assertEquals(6.25, controller.focusDistance(), 0.0)
    }

    @Test fun `orbit pan dolly and anisotropic resize preserve a rigid camera`() {
        val controller = RemoteCameraController(session())
        controller.orbit(0.35, -0.22).validated(1_000_000)
        val pivotBeforePan = controller.pivot()
        controller.pan(15.0, -8.0).validated(1_000_000)
        assertTrue(!pivotBeforePan.contentEquals(controller.pivot()))
        val oldFocus = controller.focusDistance()
        controller.dolly(0.5).validated(1_000_000)
        assertTrue(controller.focusDistance() < oldFocus)
        val resized = controller.resize(320, 300)
        assertEquals(450.0, resized.intrinsics.fx, 1e-9)
        assertEquals(550.0, resized.intrinsics.fy, 1e-9)
        assertEquals(150.75, resized.intrinsics.cx, 1e-9)
        assertEquals(137.03125, resized.intrinsics.cy, 1e-9)
    }

    @Test fun `nerfstudio conversion performs the only OpenGL to OpenCV axis flip`() {
        val angle = 0.37f
        val c = cos(angle)
        val s = sin(angle)
        val metadata = SceneViewMetadata(
            SceneViewMetadata.VERSION, SceneViewMetadata.SOURCE, "camera-9", 640, 480,
            800f, 810f, 311.5f, 227.25f, SceneViewMetadata.POSE_ENCODING,
            floatArrayOf(c, -s, 0f, 1.25f, s, c, 0f, -2.5f, 0f, 0f, 1f, 3.75f),
        )
        val camera = RemoteCameraController.fromNerfstudioOpenGl(
            metadata, doubleArrayOf(1.0, 2.0, 3.0), 4.5, 640 * 480,
        ).camera()
        val expected = doubleArrayOf(
            c.toDouble(), s.toDouble(), 0.0, -(c * 1.25f + s * -2.5f).toDouble(),
            s.toDouble(), -c.toDouble(), 0.0, (-s * 1.25f + c * -2.5f).toDouble(),
            0.0, 0.0, -1.0, 3.75,
            0.0, 0.0, 0.0, 1.0,
        )
        assertArrayEquals(expected, camera.w2c, 1e-6)
        camera.validated(640 * 480)
    }

    @Test fun `invalid navigation inputs fail closed`() {
        val controller = RemoteCameraController(session())
        assertThrows(IllegalArgumentException::class.java) { controller.pan(Double.NaN, 0.0) }
        assertThrows(IllegalArgumentException::class.java) { controller.dolly(Double.POSITIVE_INFINITY) }
        assertThrows(IllegalArgumentException::class.java) { controller.resize(0, 480) }
    }

    private fun session(): RemoteRenderSession {
        val camera = RemoteCamera(
            doubleArrayOf(1.0, 0.0, 0.0, -3.0, 0.0, 1.0, 0.0, 4.0, 0.0, 0.0, 1.0, -11.25, 0.0, 0.0, 0.0, 1.0),
            RemoteIntrinsics(900.0, 880.0, 301.5, 219.25), 640, 480,
        ).validated(1_000_000)
        return RemoteRenderSession(
            "session_123", camera, doubleArrayOf(3.0, -4.0, 5.0), 6.25,
            1536, 1024, RemoteRenderLimits(1_000_000, 20_971_520, 120.0),
        )
    }
}
