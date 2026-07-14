package com.wici.androidalbumdemo

import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class RemoteRenderProtocolTest {
    private fun cameraJson(vector: JSONObject) = JSONObject()
        .put("convention", RemoteRenderProtocol.CAMERA_CONVENTION)
        .put("w2c", vector.getJSONArray("w2c"))
        .put("intrinsics", vector.getJSONObject("intrinsics"))
        .put("width", vector.getInt("width"))
        .put("height", vector.getInt("height"))

    @Test fun `canonical backend vectors validate identically and resize exactly`() {
        val text = checkNotNull(javaClass.classLoader?.getResourceAsStream("remote-render-v1-camera-vectors.json"))
            .bufferedReader().use { it.readText() }
        val root = JSONObject(text)
        assertEquals(RemoteRenderProtocol.VERSION, root.getString("protocol"))
        assertEquals(RemoteRenderProtocol.CAMERA_CONVENTION, root.getString("convention"))
        val vectors = root.getJSONArray("vectors")
        val parsed = mutableMapOf<String, RemoteCamera>()
        for (index in 0 until vectors.length()) {
            val vector = vectors.getJSONObject(index)
            if (vector.getBoolean("valid")) {
                parsed[vector.getString("name")] = RemoteRenderProtocol.parseCamera(cameraJson(vector), 640 * 480)
            } else {
                assertThrows(RemoteProtocolException::class.java) {
                    RemoteRenderProtocol.parseCamera(cameraJson(vector), 640 * 480)
                }
            }
        }
        val resized = parsed.getValue("asymmetric-rigid-noncentered").resized(320, 240, 640 * 480)
        val expected = parsed.getValue("resize-half")
        assertArrayEquals(expected.w2c, resized.w2c, 0.0)
        assertEquals(expected.intrinsics, resized.intrinsics)
        assertEquals(expected.width, resized.width)
        assertEquals(expected.height, resized.height)
    }

    @Test fun `session parser accepts exact authoritative metadata`() {
        val session = RemoteRenderProtocol.parseSession(sessionJson())
        assertEquals("session_123", session.sessionId)
        assertArrayEquals(doubleArrayOf(4.0, -3.0, 2.0), session.pivot, 0.0)
        assertEquals(7.5, session.focusDistance, 0.0)
        assertEquals(1_638_400, session.limits.maxFramePixels)
        assertEquals(20_971_520L, session.limits.maxUploadBytes)
        assertEquals(120.0, session.limits.sessionIdleTtlSeconds, 0.0)
    }

    @Test fun `session parser rejects unknown fields protocol dimensions and limits`() {
        val cases = listOf(
            JSONObject(sessionJson()).put("alias", true),
            JSONObject(sessionJson()).put("protocol", "remote-render-v2"),
            JSONObject(sessionJson()).also { it.getJSONObject("camera").put("width", 0) },
            JSONObject(sessionJson()).also { it.getJSONObject("camera").getJSONObject("intrinsics").put("cx", 641) },
            JSONObject(sessionJson()).also { it.getJSONObject("limits").put("maxFramePixels", 1.5) },
        )
        cases.forEach { value ->
            assertThrows(RemoteProtocolException::class.java) { RemoteRenderProtocol.parseSession(value.toString()) }
        }
    }

    @Test fun `frame metadata validates protocol request id dimensions and MIME`() {
        val headers = mapOf(
            "x-wici-protocol" to RemoteRenderProtocol.VERSION,
            "X-Wici-Request-Id" to "42",
            "X-Wici-Frame-Width" to "640",
            "X-Wici-Frame-Height" to "480",
        )
        val frame = RemoteRenderProtocol.parseFrameMetadata("image/jpeg", headers, "interactive", 640 * 480)
        assertEquals(42, frame.requestId)
        assertEquals(640, frame.width)
        assertThrows(RemoteProtocolException::class.java) {
            RemoteRenderProtocol.parseFrameMetadata("image/png", headers, "interactive", 640 * 480)
        }
        assertThrows(RemoteProtocolException::class.java) {
            RemoteRenderProtocol.parseFrameMetadata("image/jpeg; charset=binary", headers, "interactive", 640 * 480)
        }
        assertThrows(RemoteProtocolException::class.java) {
            RemoteRenderProtocol.parseFrameMetadata("image/jpeg", headers + ("X-Wici-Request-Id" to "042"), "interactive", 640 * 480)
        }
        assertThrows(RemoteProtocolException::class.java) {
            RemoteRenderProtocol.parseFrameMetadata("image/jpeg", headers + ("X-Wici-Frame-Width" to "641"), "interactive", 640 * 480)
        }
    }

    @Test fun `error parser is strict and exposes stable machine fields`() {
        val parsed = RemoteRenderProtocol.parseError(
            """{"protocol":"remote-render-v1","error":{"code":"request_superseded","message":"old","retryable":true,"details":{"latest":8}}}""",
        )
        assertEquals("request_superseded", parsed.code)
        assertTrue(parsed.retryable)
        assertEquals(8, parsed.details?.getInt("latest"))
        assertThrows(RemoteProtocolException::class.java) {
            RemoteRenderProtocol.parseError("""{"protocol":"remote-render-v1","error":{"code":"made_up","message":"x","retryable":false}}""")
        }
        assertThrows(RemoteProtocolException::class.java) {
            RemoteRenderProtocol.parseError("""{"protocol":"remote-render-v1","error":{"code":"session_expired","message":"x","retryable":false,"extra":1}}""")
        }
        assertThrows(RemoteProtocolException::class.java) {
            RemoteRenderProtocol.parseError("""{"protocol":"remote-render-v1","error":{"code":"session_expired","message":"x","retryable":false}}""", "text/plain")
        }
    }

    @Test fun `request encoders retain exact protocol and reject invalid modes`() {
        val gallery = JSONObject(RemoteRenderProtocol.galleryCreateJson("photo-7"))
        assertEquals(RemoteRenderProtocol.VERSION, gallery.getString("protocol"))
        assertEquals("photo-7", gallery.getJSONObject("source").getString("photoId"))
        val camera = RemoteRenderProtocol.parseCamera(JSONObject(sessionJson()).getJSONObject("camera"), 640 * 480)
        val frame = JSONObject(RemoteRenderProtocol.frameRequestJson(9, "capture", camera, 640 * 480))
        assertEquals(9, frame.getLong("requestId"))
        assertEquals("capture", frame.getString("mode"))
        assertFalse(frame.getJSONObject("camera").has("matrix"))
        assertThrows(IllegalArgumentException::class.java) {
            RemoteRenderProtocol.frameRequestJson(1, "preview", camera, 640 * 480)
        }
    }

    private fun sessionJson() = """
        {
          "protocol":"remote-render-v1",
          "sessionId":"session_123",
          "camera":{
            "convention":"opencv-w2c-row-major-4x4",
            "w2c":[1,0,0,0,0,1,0,0,0,0,1,-2,0,0,0,1],
            "intrinsics":{"fx":900,"fy":880,"cx":301.5,"cy":219.25},
            "width":640,"height":480
          },
          "pivot":[4,-3,2],
          "focusDistance":7.5,
          "source":{"width":1536,"height":1024},
          "limits":{"maxFramePixels":1638400,"maxUploadBytes":20971520,"sessionIdleTtlSeconds":120}
        }
    """.trimIndent()
}
