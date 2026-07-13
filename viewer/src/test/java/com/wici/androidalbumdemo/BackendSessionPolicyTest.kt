package com.wici.androidalbumdemo

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BackendSessionPolicyTest {
    @Test
    fun `priority is manual then activated Box then healthy NSD then cloud`() {
        assertEquals(BackendSource.MANUAL, choose("manual", "box", "nsd").source)
        assertEquals(BackendSource.ACTIVATED_BOX, choose(null, "box", "nsd").source)
        assertEquals(BackendSource.NSD, choose(null, null, "nsd").source)
        assertEquals(BackendSource.CLOUD, choose(null, null, null).source)
    }

    @Test
    fun `local failure retries cloud exactly once while manual never falls back`() {
        val policy = RetryOncePolicy()
        assertTrue(policy.claimCloudRetry(BackendSource.ACTIVATED_BOX))
        assertFalse(policy.claimCloudRetry(BackendSource.ACTIVATED_BOX))
        assertFalse(RetryOncePolicy().claimCloudRetry(BackendSource.MANUAL))
    }

    @Test
    fun `stage text follows server state and readiness`() {
        assertEquals("Switching from Q&A...", BoxStageText.spatial("evicting", emptyMap()))
        assertEquals("Starting Orbit...", BoxStageText.spatial("starting", emptyMap()))
        assertEquals("Loading DiFix...", BoxStageText.spatial("starting", mapOf("orbit" to true)))
        assertEquals("Loading FLUX...", BoxStageText.spatial("starting", mapOf("orbit" to true, "difix" to true)))
        assertEquals(
            "Checking 3D server...",
            BoxStageText.spatial("starting", mapOf("orbit" to true, "difix" to true, "flux" to true))
        )
    }

    private fun choose(manual: String?, box: String?, nsd: String?) = BackendSessionPolicy.choose(
        manualUrl = manual,
        activatedBoxUrl = box,
        healthyNsdUrl = nsd,
        cloudUrl = "cloud"
    )
}
