package com.wici.androidalbumdemo

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class LatestFrameSchedulerTest {
    @Test fun coalescesToOneInFlightAndLatestPending() {
        val executor = Executors.newSingleThreadExecutor()
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val completed = CountDownLatch(2)
        val rendered = mutableListOf<Long>()
        val scheduler = LatestFrameScheduler(
            executor,
            render = { frame ->
                synchronized(rendered) { rendered += frame.requestId }
                if (frame.requestId == 1L) { entered.countDown(); release.await(2, TimeUnit.SECONDS) }
                frame.requestId
            },
            completed = { _, _ -> completed.countDown() },
        )
        scheduler.submit(frame(1))
        assertTrue(entered.await(2, TimeUnit.SECONDS))
        scheduler.submit(frame(2))
        scheduler.submit(frame(3))
        assertTrue(scheduler.snapshot().first)
        assertEquals(3L, scheduler.snapshot().second?.requestId)
        release.countDown()
        assertTrue(completed.await(2, TimeUnit.SECONDS))
        assertEquals(listOf(1L, 3L), synchronized(rendered) { rendered.toList() })
        scheduler.shutdown()
        executor.shutdownNow()
    }

    @Test fun shutdownDropsPendingAndFutureFrames() {
        val executor = Executors.newSingleThreadExecutor()
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val rendered = mutableListOf<Long>()
        val scheduler = LatestFrameScheduler<Long>(executor, {
            synchronized(rendered) { rendered += it.requestId }
            entered.countDown(); release.await(2, TimeUnit.SECONDS); it.requestId
        }, { _, _ -> })
        scheduler.submit(frame(1))
        assertTrue(entered.await(2, TimeUnit.SECONDS))
        scheduler.submit(frame(2))
        scheduler.shutdown()
        scheduler.submit(frame(3))
        release.countDown()
        executor.shutdown()
        assertTrue(executor.awaitTermination(2, TimeUnit.SECONDS))
        assertEquals(listOf(1L), rendered)
    }

    @Test fun frameGateRejectsOldGenerationOldRequestAndClosedUpdates() {
        val gate = RemoteFrameGate()
        val first = gate.beginGeneration()
        assertTrue(gate.accept(first, 4))
        assertFalse(gate.accept(first, 3))
        val second = gate.beginGeneration()
        assertFalse(gate.accept(first, 99))
        assertTrue(gate.accept(second, 0))
        gate.close()
        assertFalse(gate.accept(second, 1))
    }

    @Test fun renderFailureReachesCompletionCallback() {
        val executor = Executors.newSingleThreadExecutor()
        val completed = CountDownLatch(1)
        var message: String? = null
        val scheduler = LatestFrameScheduler<Long>(executor, {
            throw RemoteTransportException("network failed")
        }, { _, result ->
            message = result.exceptionOrNull()?.message
            completed.countDown()
        })
        scheduler.submit(frame(1))
        assertTrue(completed.await(2, TimeUnit.SECONDS))
        assertEquals("network failed", message)
        scheduler.shutdown()
        executor.shutdownNow()
    }

    private fun frame(id: Long) = ScheduledRemoteFrame(1, id, "interactive", camera())

    private fun camera() = RemoteCamera(
        doubleArrayOf(1.0, 0.0, 0.0, 0.0, 0.0, 1.0, 0.0, 0.0, 0.0, 0.0, 1.0, 0.0, 0.0, 0.0, 0.0, 1.0),
        RemoteIntrinsics(500.0, 500.0, 320.0, 240.0), 640, 480,
    )
}
