package com.wici.androidalbumdemo

import android.content.Context
import android.graphics.BitmapFactory
import android.view.MotionEvent
import android.widget.ImageView
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.abs
import kotlin.math.hypot

/** Standalone remote surface. MainActivity integration is intentionally deferred to S8. */
class RemoteRenderView(
    context: Context,
    private val client: RemoteRenderClient,
    private val source: RemoteSessionSource,
    private val status: (String) -> Unit = {},
    private val error: (RemoteTransportException) -> Unit = {},
    private val captureReady: (RemoteFrame) -> Unit = {},
    private val interactionStarted: () -> Unit = {},
    executor: ExecutorService? = null,
) : ImageView(context), InteractiveRenderSurface {
    private val worker = executor ?: Executors.newSingleThreadExecutor { runnable -> Thread(runnable, "wici-remote-render") }
    private val ownsExecutor = executor == null
    private val gate = RemoteFrameGate()
    private val nextRequestId = AtomicLong()
    private val stateLock = Any()
    private var session: RemoteRenderSession? = null
    private var cameraController: RemoteCameraController? = null
    private var generation = 0L
    private var createInFlight = false
    private var paused = true
    private var closed = false
    private var lastX = 0f
    private var lastY = 0f
    private var pinchDistance = 0f
    private var pinchCenterX = 0f
    private var pinchCenterY = 0f
    private var pinching = false

    private val scheduler = LatestFrameScheduler(worker, ::renderScheduled, ::completeScheduled)

    init {
        scaleType = ScaleType.FIT_CENTER
        isClickable = true
    }

    override fun resume() {
        val shouldCreate = synchronized(stateLock) {
            if (closed) false else {
                paused = false
                (session == null && !createInFlight).also { if (it) createInFlight = true }
            }
        }
        if (shouldCreate) worker.execute(::createSession)
        else requestFrame("interactive")
    }

    override fun pause() {
        synchronized(stateLock) { paused = true }
        scheduler.clearPending()
        client.cancelActive()
    }

    override fun reset() {
        interactionStarted()
        synchronized(stateLock) { cameraController?.reset() }
        requestFrame("interactive")
    }

    override fun shutdown() {
        val sessionId = synchronized(stateLock) {
            if (closed) return
            closed = true
            paused = true
            session?.sessionId.also {
                session = null
                cameraController = null
            }
        }
        gate.close()
        scheduler.shutdown()
        client.cancelActive()
        if (sessionId != null) worker.execute { runCatching { client.deleteSession(sessionId) } }
        if (ownsExecutor) worker.shutdown()
    }

    override fun onSizeChanged(width: Int, height: Int, oldWidth: Int, oldHeight: Int) {
        super.onSizeChanged(width, height, oldWidth, oldHeight)
        if (width <= 0 || height <= 0) return
        synchronized(stateLock) {
            val current = cameraController?.camera() ?: return@synchronized
            val maxPixels = session?.limits?.maxFramePixels ?: return@synchronized
            val scale = minOf(1.0, kotlin.math.sqrt(maxPixels.toDouble() / (width.toDouble() * height)))
            cameraController?.resize((width * scale).toInt().coerceAtLeast(1), (height * scale).toInt().coerceAtLeast(1))
            if (current.width == width && current.height == height) return@synchronized
        }
        requestFrame("interactive")
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                interactionStarted()
                lastX = event.x; lastY = event.y; pinching = false; pinchDistance = 0f
            }
            MotionEvent.ACTION_POINTER_DOWN -> if (event.pointerCount >= 2) {
                interactionStarted()
                pinching = true
                pinchDistance = distance(event)
                pinchCenterX = centerX(event)
                pinchCenterY = centerY(event)
            }
            MotionEvent.ACTION_MOVE -> {
                synchronized(stateLock) {
                    val controller = cameraController ?: return true
                    if (event.pointerCount >= 2) {
                        val current = distance(event)
                        val currentCenterX = centerX(event)
                        val currentCenterY = centerY(event)
                        if (pinching && pinchDistance > 1f && current > 1f) {
                            val distanceDelta = current - pinchDistance
                            val centerDx = currentCenterX - pinchCenterX
                            val centerDy = currentCenterY - pinchCenterY
                            val centerMove = hypot(centerDx, centerDy)
                            if (abs(distanceDelta) > centerMove * 0.5f && abs(distanceDelta) > 0.75f) {
                                controller.dolly(distanceDelta.toDouble() / 160.0)
                            }
                            if (centerMove > 0.75f && centerMove >= abs(distanceDelta) * 0.35f) {
                                controller.pan(centerDx.toDouble(), centerDy.toDouble())
                            }
                        }
                        pinchDistance = current
                        pinchCenterX = currentCenterX
                        pinchCenterY = currentCenterY
                        pinching = true
                    } else {
                        val dx = event.x - lastX; val dy = event.y - lastY
                        lastX = event.x; lastY = event.y
                        controller.orbit(-dx.toDouble() * 0.006, -dy.toDouble() * 0.006)
                    }
                }
                requestFrame("interactive")
            }
            MotionEvent.ACTION_POINTER_UP -> {
                val remaining = if (event.actionIndex == 0) 1 else 0
                if (remaining < event.pointerCount) {
                    lastX = event.getX(remaining)
                    lastY = event.getY(remaining)
                }
                pinching = false
                pinchDistance = 0f
            }
            MotionEvent.ACTION_UP -> {
                pinching = false; pinchDistance = 0f
                requestFrame("capture")
            }
            MotionEvent.ACTION_CANCEL -> {
                pinching = false; pinchDistance = 0f
                // Cancellation ends interaction without requesting capture.
            }
        }
        return true
    }

    private fun createSession() {
        try {
            val created = client.createSession(source)
            val active = synchronized(stateLock) {
                createInFlight = false
                if (closed) false else {
                    session = created
                    cameraController = RemoteCameraController(created)
                    generation = gate.beginGeneration()
                    !paused
                }
            }
            if (!active) {
                if (synchronized(stateLock) { closed }) runCatching { client.deleteSession(created.sessionId) }
                return
            }
            post { status("Remote renderer connected") }
            requestFrame("interactive")
        } catch (failure: Throwable) {
            val shouldReport = synchronized(stateLock) {
                createInFlight = false
                !closed && !paused
            }
            if (shouldReport) report(failure)
        }
    }

    private fun requestFrame(mode: String) {
        val scheduled = synchronized(stateLock) {
            if (closed || paused || session == null || cameraController == null) null
            else ScheduledRemoteFrame(generation, nextRequestId.getAndIncrement(), mode, cameraController!!.camera())
        }
        if (scheduled != null) scheduler.submit(scheduled)
    }

    private fun renderScheduled(frame: ScheduledRemoteFrame): RemoteFrame {
        val current = synchronized(stateLock) {
            session?.takeIf { !closed && !paused && generation == frame.generation }
        } ?: throw RemoteTransportException("remote session is no longer active")
        return client.renderFrame(current, frame.requestId, frame.mode, frame.camera)
    }

    private fun completeScheduled(frame: ScheduledRemoteFrame, result: Result<RemoteFrame>) {
        result.onSuccess { remote ->
            if (!gate.accept(frame.generation, remote.metadata.requestId)) return@onSuccess
            if (frame.mode == "capture") {
                post { if (isRenderable(frame.generation)) captureReady(remote) }
            } else {
                val bitmap = BitmapFactory.decodeByteArray(remote.body, 0, remote.body.size)
                if (bitmap == null) report(RemoteTransportException("interactive response is not a decodable JPEG"))
                else if (bitmap.width != remote.metadata.width || bitmap.height != remote.metadata.height) {
                    bitmap.recycle()
                    report(RemoteTransportException("interactive JPEG dimensions do not match frame metadata"))
                }
                else post { if (isRenderable(frame.generation)) setImageBitmap(bitmap) else bitmap.recycle() }
            }
        }.onFailure(::report)
    }

    private fun isRenderable(candidate: Long) = synchronized(stateLock) {
        !closed && !paused && generation == candidate
    }

    private fun report(failure: Throwable) {
        val transport = failure as? RemoteTransportException
            ?: RemoteTransportException("remote renderer failed: ${failure.message}", cause = failure)
        post { if (synchronized(stateLock) { !closed && !paused }) error(transport) }
    }

    private fun distance(event: MotionEvent): Float =
        if (event.pointerCount < 2) 0f else hypot(event.getX(0) - event.getX(1), event.getY(0) - event.getY(1))

    private fun centerX(event: MotionEvent) = (event.getX(0) + event.getX(1)) * 0.5f
    private fun centerY(event: MotionEvent) = (event.getY(0) + event.getY(1)) * 0.5f
}
