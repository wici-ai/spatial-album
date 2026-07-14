package com.wici.androidalbumdemo

import java.util.concurrent.Executor

data class ScheduledRemoteFrame(
    val generation: Long,
    val requestId: Long,
    val mode: String,
    val camera: RemoteCamera,
    val captureEpoch: Long = 0,
)

/**
 * A bounded latest-value scheduler. Exactly one request may execute and exactly
 * one pending value is retained; submitting again replaces that pending value.
 */
class LatestFrameScheduler<T>(
    private val executor: Executor,
    private val render: (ScheduledRemoteFrame) -> T,
    private val completed: (ScheduledRemoteFrame, Result<T>) -> Unit,
) {
    private val lock = Any()
    private var pending: ScheduledRemoteFrame? = null
    private var running = false
    private var stopped = false

    fun submit(frame: ScheduledRemoteFrame) {
        var start = false
        synchronized(lock) {
            if (stopped) return
            pending = frame
            if (!running) {
                running = true
                start = true
            }
        }
        if (start) executor.execute(::drain)
    }

    fun clearPending() = synchronized(lock) { pending = null }

    fun shutdown() = synchronized(lock) {
        stopped = true
        pending = null
    }

    internal fun snapshot(): Pair<Boolean, ScheduledRemoteFrame?> = synchronized(lock) { running to pending }

    private fun drain() {
        while (true) {
            val next = synchronized(lock) {
                if (stopped) {
                    running = false
                    null
                } else pending.also { pending = null }.also {
                    if (it == null) running = false
                }
            } ?: return
            val result = runCatching { render(next) }
            completed(next, result)
        }
    }
}

/** Guards UI commits independently from transport cancellation races. */
class RemoteFrameGate {
    private var generation = 0L
    private var displayedRequestId = -1L
    private var closed = false

    @Synchronized fun beginGeneration(): Long {
        check(!closed) { "frame gate is closed" }
        generation += 1
        displayedRequestId = -1
        return generation
    }

    @Synchronized fun accept(candidateGeneration: Long, requestId: Long): Boolean {
        if (closed || candidateGeneration != generation || requestId < displayedRequestId) return false
        displayedRequestId = requestId
        return true
    }

    @Synchronized fun close() { closed = true }
}

/** Invalidates a requested capture as soon as a newer interaction begins. */
class RemoteCaptureGate {
    private var epoch = 0L
    private var closed = false

    @Synchronized fun beginInteraction(): Long {
        if (!closed) epoch += 1
        return epoch
    }

    @Synchronized fun snapshot(): Long = epoch

    @Synchronized fun accept(candidateEpoch: Long): Boolean = !closed && candidateEpoch == epoch

    @Synchronized fun close() { closed = true }
}
