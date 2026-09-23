package com.deskstream.client.video

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaFormat
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.Process
import android.os.SystemClock
import android.util.Log
import android.view.Surface
import java.util.ArrayDeque
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Bounded asynchronous H.264/HEVC decoder that renders directly to a [Surface].
 *
 * The UDP thread only takes a tiny queue lock and transfers buffer ownership. MediaCodec
 * enumeration/configuration, complete-AU copies, queueing, output, and teardown are serialized on
 * one foreground-priority codec worker. This prevents large game frames from blocking UDP receive
 * and guarantees an old hardware codec is fully released before a replacement is configured.
 */
class VideoDecoder(
    /** One coalesced recovery signal per broken reference chain. */
    private val onDropOrError: () -> Unit,
    /** Returns an access-unit buffer to its originating MediaReceiver pool. */
    private val onBufferRelease: (ByteArray) -> Unit,
    /** Called when a decoded frame is released toward the Surface. */
    private val onFrameRendered: (latencyMs: Int) -> Unit = {}
) {
    private data class PendingFrame(
        val data: ByteArray,
        val length: Int,
        val keyframe: Boolean,
        val frameId: Long,
        val generation: Long,
        val enqueuedAtUs: Long
    )

    private data class StreamConfig(
        val generation: Long,
        val surface: Surface,
        val width: Int,
        val height: Int,
        val fps: Int,
        /** MediaCodec MIME for the negotiated STREAM_STARTED codec. */
        val mime: String
    )

    // Producer queue: the UDP thread never acquires any codec-state lock.
    private val queueLock = Any()
    private val pendingFrames = ArrayDeque<PendingFrame>()
    private var drainPosted = false
    @Volatile private var acceptingFrames = false
    @Volatile private var waitingForKeyframe = true
    @Volatile private var streamGeneration = 0L
    private val recoverySignaled = AtomicBoolean(false)
    private val released = AtomicBoolean(false)

    private val handlerThread = HandlerThread(
        "VideoDecoderCallback",
        Process.THREAD_PRIORITY_FOREGROUND
    ).apply { start() }
    private val handler = Handler(handlerThread.looper)

    // Worker-owned state. Only codec callbacks/runnables on [handler] touch these fields.
    private var workerGeneration = -1L
    private var streamConfig: StreamConfig? = null
    private var codec: MediaCodec? = null
    private var codecReleasePending = false
    private val pendingInputIndices = ArrayDeque<Int>()
    private val submitTimesUs = HashMap<Long, Long>()
    private var codecStartedAtUs = 0L
    private var lastInputQueuedAtUs = 0L
    private var lastOutputAtUs = 0L

    private val callback = object : MediaCodec.Callback() {
        override fun onInputBufferAvailable(mc: MediaCodec, index: Int) {
            if (mc !== codec) return
            pendingInputIndices.addLast(index)
            drainOnWorker()
        }

        override fun onOutputBufferAvailable(
            mc: MediaCodec,
            index: Int,
            info: MediaCodec.BufferInfo
        ) {
            if (mc !== codec) return
            val submittedUs = submitTimesUs.remove(info.presentationTimeUs)
            var sentToSurface = false
            try {
                mc.releaseOutputBuffer(index, true)
                sentToSurface = true
            } catch (_: IllegalStateException) {
                // A queued stale callback can race a normal stream reset.
            }
            if (sentToSurface && submittedUs != null) {
                val nowUs = nowUs()
                lastOutputAtUs = nowUs
                val latencyMs = ((nowUs - submittedUs) / 1000L)
                    .coerceIn(0L, 60_000L)
                    .toInt()
                onFrameRendered(latencyMs)
            }
        }

        override fun onError(mc: MediaCodec, error: MediaCodec.CodecException) {
            if (mc !== codec) return
            Log.e(
                TAG,
                "decoder error (recoverable=${error.isRecoverable}): ${error.diagnosticInfo}",
                error
            )
            scheduleCodecRecoveryOnWorker("codec callback error", notify = true)
        }

        override fun onOutputFormatChanged(mc: MediaCodec, format: MediaFormat) {
            if (mc === codec) Log.i(TAG, "decoder output format changed: $format")
        }
    }

    private val drainRunnable = Runnable {
        synchronized(queueLock) { drainPosted = false }
        val config = streamConfig ?: return@Runnable
        if (released.get() || codecReleasePending || config.generation != streamGeneration) return@Runnable

        if (codec == null) {
            val first = synchronized(queueLock) {
                discardStalePendingLocked(config.generation)
                pendingFrames.peekFirst()
            } ?: return@Runnable
            if (!first.keyframe) {
                signalQueueRecovery()
                return@Runnable
            }
            if (!configureCodecOnWorker(config)) {
                signalQueueRecovery()
                return@Runnable
            }
        }
        drainOnWorker()
    }

    private val watchdogRunnable = object : Runnable {
        override fun run() {
            val config = streamConfig ?: return
            if (released.get() || config.generation != streamGeneration) return

            val now = nowUs()
            val oldestPendingAgeUs = synchronized(queueLock) {
                pendingFrames.peekFirst()?.let { now - it.enqueuedAtUs } ?: 0L
            }
            val outputAnchor = maxOf(codecStartedAtUs, lastOutputAtUs)
            val watchdogArmed = codec != null &&
                now - codecStartedAtUs >= CODEC_STARTUP_GRACE_US
            val decodeStalled = watchdogArmed && lastInputQueuedAtUs > outputAnchor &&
                now - outputAnchor >= CODEC_OUTPUT_STALL_US
            val inputStalled = watchdogArmed && oldestPendingAgeUs >= CODEC_INPUT_STALL_US

            if (!codecReleasePending && (decodeStalled || inputStalled)) {
                val reason = if (decodeStalled) "no decoded output" else "no codec input slot"
                Log.w(TAG, "decoder watchdog recovery: $reason")
                scheduleCodecRecoveryOnWorker(reason, notify = true)
            }
            handler.postDelayed(this, WATCHDOG_INTERVAL_MS)
        }
    }

    /**
     * Begins a new stream epoch. Reset is posted before MediaReceiver starts, so any later drain
     * message is ordered after old-codec teardown and new surface/dimension setup.
     */
    fun resetForNewStream(surface: Surface, width: Int, height: Int, fps: Int, codec: String = "h264") {
        if (released.get()) return
        val generation = synchronized(queueLock) {
            streamGeneration += 1L
            acceptingFrames = true
            waitingForKeyframe = true
            recoverySignaled.set(false)
            clearPendingLocked()
            drainPosted = false
            streamGeneration
        }
        val config = StreamConfig(
            generation,
            surface,
            width,
            height,
            fps.coerceIn(MIN_FPS, MAX_FPS),
            mimeFor(codec)
        )
        if (!handler.post { resetOnWorker(config) }) {
            synchronized(queueLock) {
                acceptingFrames = false
                clearPendingLocked()
            }
        }
    }

    /**
     * Transfers one complete Annex-B access unit to the codec queue. No MediaCodec API and no
     * access-unit copy runs on this caller (the UDP receive thread).
     */
    fun submitFrame(data: ByteArray, length: Int, keyframe: Boolean, frameId: Long) {
        if (length <= 0 || length > data.size || length > MAX_ACCESS_UNIT_BYTES) {
            Log.w(TAG, "rejecting invalid AU frame=$frameId length=$length buffer=${data.size}")
            onBufferRelease(data)
            signalQueueRecovery()
            return
        }

        val now = nowUs()
        var releaseIncoming = false
        var notifyRecovery = false
        var postDrain = false

        synchronized(queueLock) {
            val generation = streamGeneration
            if (!acceptingFrames || released.get()) {
                releaseIncoming = true
            } else if (waitingForKeyframe && !keyframe) {
                releaseIncoming = true
                notifyRecovery = recoverySignaled.compareAndSet(false, true)
            } else {
                val oldestAgeUs = pendingFrames.peekFirst()?.let { now - it.enqueuedAtUs } ?: 0L
                val overflow = pendingFrames.size >= MAX_PENDING_FRAMES ||
                    oldestAgeUs >= MAX_PENDING_AGE_US

                if (overflow) {
                    clearPendingLocked()
                    waitingForKeyframe = true
                    if (!keyframe) {
                        releaseIncoming = true
                        notifyRecovery = recoverySignaled.compareAndSet(false, true)
                    }
                }

                if (!releaseIncoming) {
                    if (keyframe) {
                        waitingForKeyframe = false
                        recoverySignaled.set(false)
                    }
                    pendingFrames.addLast(
                        PendingFrame(data, length, keyframe, frameId, generation, now)
                    )
                    if (!drainPosted) {
                        drainPosted = true
                        postDrain = true
                    }
                }
            }
        }

        if (releaseIncoming) onBufferRelease(data)
        if (notifyRecovery) onDropOrError()
        if (postDrain && !handler.post(drainRunnable)) {
            synchronized(queueLock) {
                drainPosted = false
                clearPendingLocked()
            }
        }
    }

    /**
     * Stops and releases the codec in order on its worker and waits briefly for completion.
     * Stream changes are rare; bounded UI waiting here prevents a new hardware decoder from
     * racing an old instance that still owns the Surface/vendor component.
     */
    fun release() {
        if (!released.compareAndSet(false, true)) return
        synchronized(queueLock) {
            acceptingFrames = false
            streamGeneration += 1L
            waitingForKeyframe = true
            clearPendingLocked()
            drainPosted = false
        }

        if (Looper.myLooper() === handlerThread.looper) {
            releaseOnWorker()
            handlerThread.quitSafely()
            return
        }

        val done = CountDownLatch(1)
        val posted = handler.post {
            try {
                releaseOnWorker()
            } finally {
                done.countDown()
                handlerThread.quitSafely()
            }
        }
        if (posted) {
            try {
                if (!done.await(RELEASE_WAIT_MS, TimeUnit.MILLISECONDS)) {
                    Log.w(TAG, "timed out waiting for decoder release")
                }
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
            }
        }
    }

    // ---- codec worker ------------------------------------------------------------------

    private fun resetOnWorker(config: StreamConfig) {
        destroyCodecOnWorker(codec)
        codec = null
        codecReleasePending = false
        pendingInputIndices.clear()
        submitTimesUs.clear()
        workerGeneration = config.generation
        streamConfig = config
        codecStartedAtUs = 0L
        lastInputQueuedAtUs = 0L
        lastOutputAtUs = 0L
        handler.removeCallbacks(watchdogRunnable)
        handler.postDelayed(watchdogRunnable, WATCHDOG_INTERVAL_MS)
        scheduleDrain()
    }

    private fun drainOnWorker() {
        val activeCodec = codec ?: return
        val config = streamConfig ?: return
        while (!codecReleasePending && pendingInputIndices.isNotEmpty()) {
            val frame = synchronized(queueLock) {
                discardStalePendingLocked(config.generation)
                pendingFrames.pollFirst()
            } ?: return
            val index = pendingInputIndices.removeFirst()
            if (!feedInputOnWorker(activeCodec, index, frame)) return
        }
    }

    private fun feedInputOnWorker(
        activeCodec: MediaCodec,
        index: Int,
        frame: PendingFrame
    ): Boolean {
        var queued = false
        try {
            if (activeCodec !== codec || frame.generation != workerGeneration) return false
            val input = activeCodec.getInputBuffer(index)
                ?: throw IllegalStateException("decoder returned a null input buffer")
            input.clear()
            if (frame.length > input.remaining()) {
                Log.e(
                    TAG,
                    "AU exceeds codec input capacity: frame=${frame.frameId} " +
                        "length=${frame.length} capacity=${input.capacity()} codec=${activeCodec.name}"
                )
                throw IllegalArgumentException("access unit exceeds codec input capacity")
            }
            input.put(frame.data, 0, frame.length)
            var ptsUs = nowUs()
            while (submitTimesUs.containsKey(ptsUs)) ptsUs++
            activeCodec.queueInputBuffer(index, 0, frame.length, ptsUs, 0)
            submitTimesUs[ptsUs] = frame.enqueuedAtUs
            lastInputQueuedAtUs = ptsUs
            if (frame.keyframe) recoverySignaled.set(false)
            queued = true
        } catch (error: Exception) {
            Log.w(TAG, "queueInputBuffer failed for frame=${frame.frameId}", error)
        } finally {
            onBufferRelease(frame.data)
        }

        if (!queued && activeCodec === codec) {
            // Destroying the codec returns the owned input index. Leaving it unqueued would
            // progressively exhaust every input slot after large-AU failures on OEM codecs.
            scheduleCodecRecoveryOnWorker("input feed failure", notify = true)
        }
        return queued
    }

    private fun configureCodecOnWorker(config: StreamConfig): Boolean {
        var candidate: MediaCodec? = null
        return try {
            val baseFormat = buildFormat(config, tuned = false, lowLatency = false, vendorKeys = emptyMap())
            val decoderName = chooseDecoderName(baseFormat, config)
            candidate = if (decoderName != null) {
                MediaCodec.createByCodecName(decoderName)
            } else {
                MediaCodec.createDecoderByType(config.mime)
            }

            val capabilities = candidate.codecInfo.getCapabilitiesForType(config.mime)
            val lowLatency = Build.VERSION.SDK_INT >= Build.VERSION_CODES.R &&
                capabilities.isFeatureSupported(MediaCodecInfo.CodecCapabilities.FEATURE_LowLatency)
            val vendorKeys = vendorLowLatencyKeys(candidate.name)

            candidate.setCallback(callback, handler)
            try {
                candidate.configure(
                    buildFormat(config, tuned = true, lowLatency = lowLatency, vendorKeys = vendorKeys),
                    config.surface,
                    null,
                    0
                )
            } catch (tunedError: Exception) {
                // Realtime priority and vendor keys are hints, but a strict OEM codec may refuse
                // a configuration it cannot guarantee. Fall back to the plain, previously
                // shipped configuration rather than leaving the stream black.
                Log.w(TAG, "decoder rejected low-latency tuning; retrying plain config", tunedError)
                candidate.reset()
                candidate.setCallback(callback, handler)
                candidate.configure(
                    buildFormat(config, tuned = false, lowLatency = lowLatency, vendorKeys = emptyMap()),
                    config.surface,
                    null,
                    0
                )
            }
            candidate.start()
            if (released.get() || config.generation != streamGeneration) {
                destroyCodecOnWorker(candidate)
                return false
            }
            codec = candidate
            codecStartedAtUs = nowUs()
            lastOutputAtUs = codecStartedAtUs
            try {
                Log.i(TAG, decoderDescription(candidate, capabilities, config))
            } catch (diagnosticError: Exception) {
                Log.w(TAG, "decoder capability diagnostics unavailable", diagnosticError)
            }
            true
        } catch (error: Exception) {
            Log.e(TAG, "failed to configure/start decoder", error)
            if (codec === candidate) codec = null
            destroyCodecOnWorker(candidate)
            false
        }
    }

    /**
     * [tuned] = realtime codec priority plus [vendorKeys]. KEY_PRIORITY 0 is realtime and 1 is
     * best-effort (the value this used to send, which let TV decoders trade throughput for
     * power and drop frames around bursty keyframes).
     */
    private fun buildFormat(
        config: StreamConfig,
        tuned: Boolean,
        lowLatency: Boolean,
        vendorKeys: Map<String, Int>
    ): MediaFormat = MediaFormat.createVideoFormat(config.mime, config.width, config.height).apply {
        setInteger(MediaFormat.KEY_FRAME_RATE, config.fps)
        // Allow the codec to spin up to 2x real time so decode keeps up with bursty frames.
        setInteger(MediaFormat.KEY_OPERATING_RATE, config.fps * 2)
        setInteger(MediaFormat.KEY_PRIORITY, if (tuned) 0 else 1)
        setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, MAX_ACCESS_UNIT_BYTES)
        if (lowLatency && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            setInteger(MediaFormat.KEY_LOW_LATENCY, 1)
        }
        for ((key, value) in vendorKeys) setInteger(key, value)
    }

    private fun scheduleCodecRecoveryOnWorker(reason: String, notify: Boolean) {
        val old = codec
        codec = null
        pendingInputIndices.clear()
        submitTimesUs.clear()
        signalQueueRecovery(notify)
        if (old == null) return

        codecReleasePending = true
        // Recovery may originate inside a MediaCodec callback. Post teardown so the callback
        // returns first; the same serial worker guarantees release finishes before reconfigure.
        handler.post {
            Log.w(TAG, "releasing decoder for recovery: $reason")
            destroyCodecOnWorker(old)
            codecReleasePending = false
            scheduleDrain()
        }
    }

    private fun signalQueueRecovery(notify: Boolean = true) {
        var shouldNotify = false
        synchronized(queueLock) {
            clearPendingLocked()
            waitingForKeyframe = true
            if (notify) shouldNotify = recoverySignaled.compareAndSet(false, true)
        }
        if (shouldNotify) onDropOrError()
    }

    private fun releaseOnWorker() {
        handler.removeCallbacks(watchdogRunnable)
        destroyCodecOnWorker(codec)
        codec = null
        codecReleasePending = false
        pendingInputIndices.clear()
        submitTimesUs.clear()
        streamConfig = null
        workerGeneration = -1L
    }

    private fun destroyCodecOnWorker(value: MediaCodec?) {
        if (value == null) return
        try { value.stop() } catch (_: Exception) { }
        try { value.release() } catch (_: Exception) { }
    }

    // ---- queue helpers -----------------------------------------------------------------

    private fun scheduleDrain() {
        val shouldPost = synchronized(queueLock) {
            if (!acceptingFrames || released.get() || drainPosted) {
                false
            } else {
                drainPosted = true
                true
            }
        }
        if (shouldPost && !handler.post(drainRunnable)) {
            synchronized(queueLock) {
                drainPosted = false
                clearPendingLocked()
            }
        }
    }

    private fun discardStalePendingLocked(generation: Long) {
        while (true) {
            val first = pendingFrames.peekFirst() ?: return
            if (first.generation == generation) return
            onBufferRelease(pendingFrames.removeFirst().data)
        }
    }

    private fun clearPendingLocked() {
        while (pendingFrames.isNotEmpty()) {
            onBufferRelease(pendingFrames.removeFirst().data)
        }
    }

    // ---- codec selection/diagnostics ----------------------------------------------------

    private fun chooseDecoderName(format: MediaFormat, config: StreamConfig): String? {
        return try {
            val list = MediaCodecList(MediaCodecList.REGULAR_CODECS)
            val preferredName = list.findDecoderForFormat(format)
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return preferredName

            val preferredInfo = list.codecInfos.firstOrNull { it.name == preferredName }
            if (preferredInfo != null && isAcceleratedCompatible(preferredInfo, format, config)) {
                return preferredName
            }
            list.codecInfos.firstOrNull {
                isAcceleratedCompatible(it, format, config)
            }?.name ?: preferredName
        } catch (error: Exception) {
            Log.w(TAG, "format-based decoder selection failed; using MIME preference", error)
            null
        }
    }

    private fun isAcceleratedCompatible(
        info: MediaCodecInfo,
        format: MediaFormat,
        config: StreamConfig
    ): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return false
        if (info.isEncoder || info.isAlias || !info.isHardwareAccelerated || info.isSoftwareOnly) {
            return false
        }
        if (info.supportedTypes.none { it.equals(config.mime, ignoreCase = true) }) return false
        return try {
            val capabilities = info.getCapabilitiesForType(config.mime)
            capabilities.isFormatSupported(format) &&
                capabilities.videoCapabilities.areSizeAndRateSupported(
                    config.width,
                    config.height,
                    config.fps.toDouble()
                )
        } catch (_: Exception) {
            false
        }
    }

    private fun decoderDescription(
        selected: MediaCodec,
        capabilities: MediaCodecInfo.CodecCapabilities,
        config: StreamConfig
    ): String {
        val info = selected.codecInfo
        val hardware = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            info.isHardwareAccelerated && !info.isSoftwareOnly
        } else {
            !info.name.startsWith("OMX.google.") && !info.name.startsWith("c2.android.")
        }
        val performance = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val requested = MediaCodecInfo.VideoCapabilities.PerformancePoint(
                config.width,
                config.height,
                config.fps
            )
            val points = capabilities.videoCapabilities.supportedPerformancePoints
            when {
                points == null -> "unknown"
                points.any { it.covers(requested) } -> "covered"
                else -> "not-advertised"
            }
        } else {
            "unavailable"
        }
        val canonical = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            selected.canonicalName
        } else {
            selected.name
        }
        return "decoder ready: name=${selected.name} canonical=$canonical hardware=$hardware " +
            "${config.mime} ${config.width}x${config.height}@${config.fps} performance=$performance " +
            "maxAu=$MAX_ACCESS_UNIT_BYTES"
    }

    private fun nowUs(): Long = SystemClock.elapsedRealtimeNanos() / 1000L

    companion object {
        private const val TAG = "VideoDecoder"
        private const val MIME_AVC = "video/avc"
        private const val MIME_HEVC = "video/hevc"

        private fun mimeFor(codec: String): String =
            if (codec.equals("hevc", ignoreCase = true)) MIME_HEVC else MIME_AVC

        /** Stream sizes a PC desktop actually produces (720p mode, then common native sizes). */
        private val STREAM_SIZES = listOf(
            1280 to 720, 1920 to 1080, 2560 to 1440, 3440 to 1440, 3840 to 2160
        )

        /**
         * Codecs to offer in START_STREAM (§2.3), in preference order. HEVC is offered only
         * when a hardware HEVC decoder plays 1080p60 and every real stream size at 60 fps
         * that hardware H.264 plays — the server picks the resolution, so HEVC must never be
         * the reason a stream cannot decode. Comparing concrete sizes (not the advertised
         * maxima) matters: AVC decoders often report 4096x2304 while HEVC reports 3840x2160,
         * and a raw max-vs-max comparison rejected HEVC on TVs that decode 4K HEVC fine.
         */
        fun supportedCodecs(): List<String> {
            return try {
                val infos = MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos
                val hevcSizes = hardwareStreamSizes(infos, MIME_HEVC)
                val avcSizes = hardwareStreamSizes(infos, MIME_AVC)
                val hevcFits = (1920 to 1080) in hevcSizes && hevcSizes.containsAll(avcSizes)
                Log.i(TAG, "codec offer: hevc=$hevcSizes avc=$avcSizes -> hevc=${if (hevcFits) "yes" else "no"}")
                if (hevcFits) listOf("hevc", "h264") else listOf("h264")
            } catch (error: Exception) {
                Log.w(TAG, "codec enumeration failed; offering H.264 only", error)
                listOf("h264")
            }
        }

        /** [STREAM_SIZES] that some hardware decoder for [mime] plays at 60 fps. */
        private fun hardwareStreamSizes(infos: Array<MediaCodecInfo>, mime: String): Set<Pair<Int, Int>> {
            val sizes = mutableSetOf<Pair<Int, Int>>()
            for (info in infos) {
                if (info.isEncoder || info.supportedTypes.none { it.equals(mime, ignoreCase = true) }) continue
                val hardware = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    info.isHardwareAccelerated && !info.isSoftwareOnly
                } else {
                    !info.name.startsWith("OMX.google.") && !info.name.startsWith("c2.android.")
                }
                if (!hardware) continue
                val video = try {
                    info.getCapabilitiesForType(mime).videoCapabilities
                } catch (_: Exception) {
                    null
                } ?: continue
                for (size in STREAM_SIZES) {
                    if (video.areSizeAndRateSupported(size.first, size.second, 60.0)) sizes += size
                }
            }
            return sizes
        }

        /**
         * SoC-specific low-latency switches (the same keys Moonlight uses). Many Android TV
         * decoders ignore the standard KEY_LOW_LATENCY and otherwise hold several decoded
         * frames before output. Unknown vendor keys are ignored by other codecs, and a codec
         * that rejects them falls back to the plain configuration.
         */
        private fun vendorLowLatencyKeys(codecName: String): Map<String, Int> {
            val name = codecName.lowercase()
            return when {
                name.startsWith("omx.qcom") || name.startsWith("c2.qti") -> mapOf(
                    "vendor.qti-ext-dec-picture-order.enable" to 1,
                    "vendor.qti-ext-dec-low-latency.enable" to 1
                )
                name.startsWith("omx.hisi") || name.startsWith("c2.hisi") -> mapOf(
                    "vendor.hisi-ext-low-latency-video-dec.video-scene-for-low-latency-req" to 1,
                    "vendor.hisi-ext-low-latency-video-dec.video-scene-for-low-latency-rdy" to -1
                )
                name.startsWith("omx.exynos") || name.startsWith("c2.exynos") -> mapOf(
                    "vendor.rtc-ext-dec-low-latency.enable" to 1
                )
                name.startsWith("omx.amlogic") || name.startsWith("c2.amlogic") -> mapOf(
                    "vendor.low-latency.enable" to 1
                )
                else -> emptyMap()
            }
        }
        private const val MIN_FPS = 24
        private const val MAX_FPS = 240
        // This is only a producer-to-codec handoff bound, not a render queue. A 120 ms ceiling
        // absorbs short Android scheduler/vendor-codec stalls without decoding stale video;
        // the previous 3-frame/50 ms limit repeatedly broke healthy reference chains at 60 fps.
        private const val MAX_PENDING_FRAMES = 6
        private const val MAX_PENDING_AGE_US = 120_000L
        private const val CODEC_STARTUP_GRACE_US = 1_000_000L
        private const val CODEC_INPUT_STALL_US = 500_000L
        private const val CODEC_OUTPUT_STALL_US = 1_500_000L
        private const val WATCHDOG_INTERVAL_MS = 500L
        private const val RELEASE_WAIT_MS = 1_500L
        // Matches the Android protocol sanity ceiling: 4096 packets * 1200-byte payload.
        private const val MAX_ACCESS_UNIT_BYTES = 4096 * 1200
    }
}
