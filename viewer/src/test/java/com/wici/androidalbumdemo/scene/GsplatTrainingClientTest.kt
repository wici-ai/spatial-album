package com.wici.androidalbumdemo.scene

import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.nio.file.Files

class GsplatTrainingClientTest {
    private fun manifest() = ReconstructionManifest.from(
        ReviewedScene("room", setOf("image_a", "video@1000"), emptySet(), "image_a")
    )

    @Test fun `manifest forces CUDA preload contract`() {
        val json = GsplatTrainingClient.buildManifest(manifest())
        assertEquals(GSPLAT_TRAINING_PROTOCOL, json.getString("protocol"))
        assertEquals(listOf("image_a", "video@1000"), (0 until json.getJSONArray("selectedCandidateIds").length()).map {
            json.getJSONArray("selectedCandidateIds").getString(it)
        })
        val options = json.getJSONObject("options")
        assertTrue(options.getBoolean("preloadTrainingData"))
        assertEquals(768 * 1024, options.getInt("preloadChunkBytes"))
        assertEquals(8, options.getInt("batchSize"))
        assertEquals(30_000, options.getInt("equivalentSteps"))
    }

    @Test fun `multipart contains every explicitly selected candidate`() {
        val temp = Files.createTempFile("gsplat-client", ".multipart").toFile()
        try {
            GsplatTrainingClient.buildMultipart(
                temp,
                "boundary",
                manifest(),
                listOf(
                    GsplatTrainingCandidate("video@1000", "video.jpg", "image/jpeg") { ByteArrayInputStream("two".toByteArray()) },
                    GsplatTrainingCandidate("image_a", "image.jpg", "image/jpeg") { ByteArrayInputStream("one".toByteArray()) },
                ),
            )
            val wire = temp.readText()
            assertEquals(2, Regex("X-Wici-Candidate-Id:").findAll(wire).count())
            assertTrue(wire.contains("X-Wici-Candidate-Id: image_a"))
            assertTrue(wire.contains("X-Wici-Candidate-Id: video@1000"))
            assertTrue(wire.contains("preloadTrainingData"))
        } finally {
            temp.delete()
        }
    }


}
