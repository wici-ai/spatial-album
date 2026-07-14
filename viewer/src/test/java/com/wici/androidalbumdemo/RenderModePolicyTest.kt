package com.wici.androidalbumdemo

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RenderModePolicyTest {
    @Test fun `renderer mode persistence defaults safely to automatic`() {
        assertEquals(RendererMode.AUTOMATIC, RendererMode.fromPersisted(null))
        assertEquals(RendererMode.AUTOMATIC, RendererMode.fromPersisted("future"))
        assertEquals(RendererMode.LOCAL, RendererMode.fromPersisted("local"))
        assertEquals(RendererMode.REMOTE, RendererMode.fromPersisted("remote"))
    }

    @Test fun `capability parser requires exact protocol value`() {
        val capabilities = RenderModePolicy.capabilitiesFromHealth(
            """{"capabilities":["remote-render-v1","remote-render-v2",7]}"""
        )
        assertTrue(RenderModePolicy.REMOTE_PROTOCOL in capabilities)
        assertFalse("REMOTE-RENDER-V1" in capabilities)
        assertEquals(emptySet<String>(), RenderModePolicy.capabilitiesFromHealth("{}"))
    }

    @Test fun `automatic selects remote only for reachable exact capability`() {
        val exact = setOf(RenderModePolicy.REMOTE_PROTOCOL)
        assertEquals(InitialRendererDecision.REMOTE, RenderModePolicy.initial(RendererMode.AUTOMATIC, true, exact))
        assertEquals(InitialRendererDecision.LOCAL, RenderModePolicy.initial(RendererMode.AUTOMATIC, true, emptySet()))
        assertEquals(InitialRendererDecision.LOCAL, RenderModePolicy.initial(RendererMode.AUTOMATIC, false, exact))
        assertEquals(InitialRendererDecision.LOCAL, RenderModePolicy.initial(RendererMode.LOCAL, true, exact))
    }

    @Test fun `explicit remote never silently falls back when capability is absent`() {
        assertEquals(InitialRendererDecision.REMOTE_UNAVAILABLE, RenderModePolicy.initial(RendererMode.REMOTE, true, emptySet()))
        assertEquals(InitialRendererDecision.REMOTE_UNAVAILABLE, RenderModePolicy.initial(RendererMode.REMOTE, false, emptySet()))
    }

    @Test fun `automatic recreates at most once then falls back`() {
        assertEquals(
            RemoteFailureDecision.RETRY_REMOTE_ONCE,
            RenderModePolicy.afterRemoteFailure(RendererMode.AUTOMATIC, retryable = true, sessionExpired = false, recreationAlreadyUsed = false),
        )
        assertEquals(
            RemoteFailureDecision.FALLBACK_LOCAL,
            RenderModePolicy.afterRemoteFailure(RendererMode.AUTOMATIC, retryable = true, sessionExpired = false, recreationAlreadyUsed = true),
        )
        assertEquals(
            RemoteFailureDecision.FALLBACK_LOCAL,
            RenderModePolicy.afterRemoteFailure(RendererMode.AUTOMATIC, retryable = false, sessionExpired = false, recreationAlreadyUsed = false),
        )
        assertEquals(
            RemoteFailureDecision.RETRY_REMOTE_ONCE,
            RenderModePolicy.afterRemoteFailure(RendererMode.AUTOMATIC, retryable = false, sessionExpired = true, recreationAlreadyUsed = false),
        )
        assertEquals(
            RemoteFailureDecision.FALLBACK_LOCAL,
            RenderModePolicy.afterRemoteFailure(RendererMode.AUTOMATIC, retryable = false, sessionExpired = true, recreationAlreadyUsed = true),
        )
    }

    @Test fun `explicit remote exposes errors even when retryable`() {
        assertEquals(
            RemoteFailureDecision.SHOW_REMOTE_ERROR,
            RenderModePolicy.afterRemoteFailure(RendererMode.REMOTE, retryable = true, sessionExpired = true, recreationAlreadyUsed = false),
        )
    }
}
