package com.localstream.client.ui

import android.content.Context
import android.content.res.ColorStateList
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Gravity
import android.view.KeyEvent
import android.view.SurfaceHolder
import android.view.Surface
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.localstream.client.R
import com.localstream.client.audio.AudioPlaybackState
import com.localstream.client.audio.AudioReceiver
import com.localstream.client.audio.AudioStats
import com.localstream.client.databinding.ActivityStreamBinding
import com.localstream.client.input.RemoteMouseController
import com.localstream.client.net.ControlClient
import com.localstream.client.net.MediaReceiver
import com.localstream.client.net.StreamStats
import com.localstream.client.proto.ServerMessage
import com.localstream.client.proto.CursorPosition
import com.localstream.client.proto.MousePacket
import com.localstream.client.video.VideoDecoder
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Fullscreen video playback. Owns the media (UDP) socket and decoder for the lifetime of the
 * SurfaceView's surface; the control channel itself belongs to the process-wide
 * [ControlClient] and survives well beyond this Activity.
 */
class StreamActivity : AppCompatActivity(), SurfaceHolder.Callback {

    private lateinit var binding: ActivityStreamBinding

    // Read from the MediaCodec callback thread (via the onDropOrError/onBufferRelease lambdas
    // handed to VideoDecoder) while written from the main thread -- @Volatile for visibility.
    @Volatile private var videoDecoder: VideoDecoder? = null
    @Volatile private var mediaReceiver: MediaReceiver? = null
    @Volatile private var audioReceiver: AudioReceiver? = null

    private lateinit var remoteMouse: RemoteMouseController
    /** Cursor insurance: DXGI capture never contains the host cursor — only DSMC feedback
     * draws it. Until the first DSMC arrives, integrate the outgoing motion packets
     * (byte 5 = mode, bytes 12/15 = x, 16/19 = y, big-endian) and show a virtual cursor
     * starting from the screen center after motion has flowed for [CURSOR_FALLBACK_DELAY_MS].
     * Real DSMC snaps the position and retires this path ([updateRemoteCursor]). */
    private var cursorDsmcSeen = false
    private var cursorFallbackJob: Job? = null
    private var cursorFallbackShown = false
    private var virtualCursorX = 0.5f
    private var virtualCursorY = 0.5f
    /** Diagnostics: every outgoing motion packet [onOutgoingMousePacket] has been handed,
     * counted before any guard, so the CURSOR stats line can show at a glance whether
     * packets stopped (0/flat), DSMC never returned (dsmc=none), or only the fallback is
     * drawing (fallback=shown). Lifetime counter: [stopReceivers] must NOT zero it, or a
     * read taken right after any stream (re)start would masquerade as "packets never
     * flowed" — that ambiguity hid the real story during testing. */
    private var cursorOutPackets = 0L
    /** How many [stopReceivers] tears this Activity has seen (every STREAM_STARTED,
     * reconnect, stall recovery and surface loss is one). out=0 with ep>1 means the epoch
     * restarted under the counter, not that input died. */
    private var cursorEpoch = 0
    /** Names the last event that took a VISIBLE cursor off screen ("none" until the first
     * hide): "input:&lt;status&gt;" / "mouse-off" from [applyMouseControls], otherwise the
     * [stopReceivers] reason. Answers "why is the cursor gone right now" from the stats
     * line alone. */
    private var lastCursorHide = "none"
    private var wifiLock: WifiManager.WifiLock? = null

    private var surfaceReady = false
    /** True once START_STREAM has been sent for the current READY session; reset whenever we
     * leave READY/STREAMING or the surface goes away, so we don't send it twice. */
    private var streamRequested = false
    private var statsVisible = false
    private var audioNegotiationJob: Job? = null
    private var inputNegotiationJob: Job? = null
    private var mouseHintJob: Job? = null
    private var stallRecoveryInProgress = false
    private var streamStartFailed = false
    private var audioStatus = "starting"
    private var audioDetail = "Waiting for the server"
    private var lastVideoStats: StreamStats? = null
    private var lastAudioStats: AudioStats? = null
    private var negotiatedBitrateKbps = 0
    /** Last dimensions received in STREAM_STARTED, so letterboxing can be re-applied when the
     * container is resized (fold/unfold, multi-window). */
    private var lastStreamWidth = 0
    private var lastStreamHeight = 0
    private var lastStreamFps = 0
    private var lastStreamCodec = "h264"
    private var lastStreamRecovery = "idr"
    /** Result of [requestTvGameMode] for the stats overlay. */
    private var tvGameModeStatus = "not requested"
    /** START_STREAM codec offer. Codec enumeration is slow-ish and fixed per device,
     * so it runs once per Activity. */
    private val offeredCodecs by lazy { VideoDecoder.supportedCodecs() }
    private var lastEncoderBackend = "media-foundation"
    private var mouseStatus = "negotiating"
    /** Ordered sequence space for keyboard traffic (the Ctrl taps of pinch zoom). */
    private var keySequence = 0L
    /** Leave confirmation is a modal dialog rather than a Snackbar: a TV remote's D-pad can
     * focus its buttons, it never auto-dismisses, and Back cancels it. */
    private var leaveDialog: AlertDialog? = null
    /** Snackbars are outside the XML overlay hierarchy, so keep track of them explicitly. Clean
     * screen dismisses existing bars and suppresses new ones until controls are restored. */
    private val activeSnackbars = mutableSetOf<Snackbar>()
    /** "Clean screen" mode hides the single overlay layer, leaving only the video surface.
     * Status children may keep updating while hidden (a visible child cannot escape its hidden
     * parent); the remote cursor deliberately lives OUTSIDE that layer so D-pad pointer control
     * still shows it over the video — see activity_stream.xml. */
    private var controlsHidden = false

    // ---- Remote (D-pad) pointer fallback --------------------------------------------------
    // Android TV remotes have no touchscreen, so the touch trackpad is out of their reach.
    // While controlsHidden is true,
    // D-pad direction keys drive the cursor directly and DPAD_CENTER/ENTER click, instead of
    // trying to time-share the D-pad with on-screen focus navigation (see handleRemotePointerKey).
    private val remotePointerHandler = Handler(Looper.getMainLooper())
    private var remotePointerDx = 0f
    private var remotePointerDy = 0f
    private var remotePointerRepeating = false
    private var remoteCenterLongClickFired = false
    private val remotePointerMoveRunnable = object : Runnable {
        override fun run() {
            if (remotePointerDx != 0f || remotePointerDy != 0f) {
                remoteMouse.nudge(remotePointerDx, remotePointerDy)
                remotePointerHandler.postDelayed(this, REMOTE_POINTER_REPEAT_MS)
            } else {
                remotePointerRepeating = false
            }
        }
    }
    private val remoteCenterLongPressRunnable = Runnable {
        remoteCenterLongClickFired = true
        remoteMouse.clickRight()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        binding = ActivityStreamBinding.inflate(layoutInflater)
        setContentView(binding.root)
        controlsHidden = savedInstanceState?.getBoolean(STATE_CONTROLS_HIDDEN) == true
        remoteMouse = RemoteMouseController(
            binding.surfaceView,
            sendMotion = { packet ->
                onOutgoingMousePacket(packet)
                mediaReceiver?.sendMousePacket(packet)
            },
            shouldHandleCleanScreenGesture = { controlsHidden },
            onCleanScreenReveal = { showControls() },
            // Same laptop-style trackpad as the controller screen: tap clicks, two fingers
            // scroll, pinch zooms (Ctrl + wheel).
            trackpad = true,
            zoomModifier = { down -> ControlClient.sendKeyboardKey(keySequence++, HID_LEFT_CTRL, down) },
            // The Ctrl taps go over TCP, so the zoom notch between them must too; over UDP it
            // could reach the PC outside the Ctrl press and scroll instead.
            sendZoomMotion = { packet ->
                onOutgoingMousePacket(packet)
                ControlClient.sendMousePacket(packet)
            }
        )
        createWifiLock()
        applyImmersiveMode()
        requestTvGameMode()

        binding.surfaceView.holder.addCallback(this)
        // A non-drawing view is left out of the window over a SurfaceView, and that region
        // only updates on layout; the translated cursor then vanished in clean screen. A
        // drawing full-screen layer keeps the cursor composited (see activity_stream.xml).
        binding.cursorLayer.setWillNotDraw(false)
        binding.streamRoot.setOnClickListener {
            toggleDiagnostics()
        }
        binding.tvStreamStatus.setOnClickListener { toggleDiagnostics() }
        binding.btnStats.setOnClickListener { toggleDiagnostics() }
        binding.btnHideControls.setOnClickListener { hideControls() }
        binding.btnLeave.setOnClickListener { confirmLeave() }
        binding.streamRoot.addOnLayoutChangeListener { _, l, t, r, b, oldL, oldT, oldR, oldB ->
            if ((r - l) != (oldR - oldL) || (b - t) != (oldB - oldT)) {
                applyAspectRatio(lastStreamWidth, lastStreamHeight)
            }
        }

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (controlsHidden) {
                    showControls()
                    return
                }
                // Android's edge-back gesture can be triggered while using the touchpad.
                // Leaving therefore requires an explicit action, never another gesture.
                confirmLeave()
            }
        })

        applyControlsVisibility()
        observeControlClient()
        // Give a D-pad-only remote (Android TV) something focused to land on immediately;
        // without this, the first D-pad press has no current focus to move from and appears
        // completely dead. See handleRemotePointerKey for the clean-screen fallback.
        binding.btnHideControls.isFocusableInTouchMode = true
        binding.root.post { if (!controlsHidden) binding.btnHideControls.requestFocus() }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putBoolean(STATE_CONTROLS_HIDDEN, controlsHidden)
        super.onSaveInstanceState(outState)
    }

    override fun onStart() {
        super.onStart()
        acquireWifiLock()
        maybeStartStream()
    }

    override fun onStop() {
        super.onStop()
        // Backgrounding stops the stream but keeps the control socket alive.
        ControlClient.stopStream()
        ControlClient.stopInput()
        remoteMouse.setEnabled(false)
        resetRemotePointerState()
        releaseWifiLock()
        streamRequested = false
        stopReceivers("lifecycle-stop")
    }

    override fun onDestroy() {
        leaveDialog?.dismiss()
        leaveDialog = null
        dismissActiveSnackbars()
        hideMouseGestureHint()
        resetRemotePointerState()
        cursorFallbackJob?.cancel()
        cursorFallbackJob = null
        super.onDestroy()
        stopReceivers("destroy")
        videoDecoder?.release()
        videoDecoder = null
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) applyImmersiveMode()
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        // F11 is a hardware-keyboard fallback for clean screen. Consume both key edges so a
        // connected keyboard can never receive a mismatched down/up pair.
        if (event.keyCode == KeyEvent.KEYCODE_F11) {
            if (event.action == KeyEvent.ACTION_UP && event.repeatCount == 0) {
                if (controlsHidden) showControls() else hideControls()
            }
            return true
        }
        // Some Android TV launchers/remotes never reach onBackPressedDispatcher while clean
        // screen has hidden every focusable view (nothing on screen "owns" the key). Handle the
        // reveal explicitly here so Back/Escape always works to get the overlay back, regardless
        // of launcher quirks; the normal "confirm leave" Back behavior (controls visible) still
        // goes through onBackPressedDispatcher below, untouched.
        if ((event.keyCode == KeyEvent.KEYCODE_BACK || event.keyCode == KeyEvent.KEYCODE_ESCAPE) &&
            controlsHidden
        ) {
            if (event.action == KeyEvent.ACTION_UP && event.repeatCount == 0) showControls()
            return true
        }
        // Only steals the D-pad once clean screen has hidden the (now focusable) toolbar --
        // otherwise D-pad still drives ordinary on-screen focus navigation between buttons, and
        // DPAD_CENTER/ENTER performs a normal button click via the standard focus system.
        if (controlsHidden && handleRemotePointerKey(event)) return true
        return super.dispatchKeyEvent(event)
    }

    /** D-pad-as-mouse fallback used only while clean screen is active (see [dispatchKeyEvent]).
     * Direction keys nudge the cursor at a fixed step on a repeating timer for as long as held;
     * DPAD_CENTER/ENTER left-clicks on a short press and right-clicks on a long press. */
    private fun handleRemotePointerKey(event: KeyEvent): Boolean {
        if (mouseStatus != "live") {
            // No usable pointer while the overlay is hidden (mouse still negotiating after a
            // reconnect, or unavailable). Returning false here leaves the remote completely
            // dead: nothing on screen can take focus, so every D-pad press would do nothing
            // at all. Consume the D-pad keys and reveal the controls instead. Non-D-pad keys
            // (volume, etc.) keep their normal system behavior.
            val isDpad = when (event.keyCode) {
                KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN,
                KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT,
                KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> true
                else -> false
            }
            if (!isDpad) return false
            if (event.action == KeyEvent.ACTION_UP && event.repeatCount == 0) showControls()
            return true
        }
        return when (event.keyCode) {
            KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN,
            KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT -> {
                handleRemotePointerDirection(event)
                true
            }
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> {
                handleRemotePointerCenter(event)
                true
            }
            else -> false
        }
    }

    private fun handleRemotePointerDirection(event: KeyEvent) {
        when (event.action) {
            KeyEvent.ACTION_DOWN -> {
                when (event.keyCode) {
                    KeyEvent.KEYCODE_DPAD_UP -> remotePointerDy = -REMOTE_POINTER_STEP_DP
                    KeyEvent.KEYCODE_DPAD_DOWN -> remotePointerDy = REMOTE_POINTER_STEP_DP
                    KeyEvent.KEYCODE_DPAD_LEFT -> remotePointerDx = -REMOTE_POINTER_STEP_DP
                    KeyEvent.KEYCODE_DPAD_RIGHT -> remotePointerDx = REMOTE_POINTER_STEP_DP
                }
                if (!remotePointerRepeating) {
                    remotePointerRepeating = true
                    remotePointerHandler.post(remotePointerMoveRunnable)
                }
            }
            KeyEvent.ACTION_UP -> when (event.keyCode) {
                KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN -> remotePointerDy = 0f
                KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT -> remotePointerDx = 0f
            }
        }
    }

    private fun handleRemotePointerCenter(event: KeyEvent) {
        when (event.action) {
            KeyEvent.ACTION_DOWN -> if (event.repeatCount == 0) {
                remoteCenterLongClickFired = false
                remotePointerHandler.postDelayed(remoteCenterLongPressRunnable, REMOTE_POINTER_LONG_PRESS_MS)
            }
            KeyEvent.ACTION_UP -> {
                remotePointerHandler.removeCallbacks(remoteCenterLongPressRunnable)
                if (!remoteCenterLongClickFired) remoteMouse.clickLeft()
            }
        }
    }

    private fun resetRemotePointerState() {
        remotePointerHandler.removeCallbacksAndMessages(null)
        remotePointerDx = 0f
        remotePointerDy = 0f
        remotePointerRepeating = false
        remoteCenterLongClickFired = false
    }

    /**
     * Asks the display for minimal post-processing (HDMI ALLM / the TV's own "game mode").
     * TV picture processing (motion smoothing, noise reduction, sharpening) typically adds
     * 30-100 ms that no stream statistic can see, so this is often the largest single latency
     * cut on a television. Android 11+ only; displays that cannot honor it ignore the hint.
     */
    private fun requestTvGameMode() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            tvGameModeStatus = "needs Android 11+"
            return
        }
        window.setPreferMinimalPostProcessing(true)
        val supported = try {
            display?.isMinimalPostProcessingSupported == true
        } catch (_: Exception) {
            false
        }
        tvGameModeStatus = if (supported) "requested" else "not supported by this display"
    }

    private fun applyImmersiveMode() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        val controller = WindowInsetsControllerCompat(window, binding.root)
        controller.hide(WindowInsetsCompat.Type.systemBars())
        controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
    }

    // ---- SurfaceHolder.Callback ---------------------------------------------------------

    override fun surfaceCreated(holder: SurfaceHolder) {
        surfaceReady = true
        videoDecoder?.release()
        videoDecoder = null
        maybeStartStream()
    }

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
        // No-op: video dimensions come from STREAM_STARTED, not the surface's pixel size.
    }

    override fun surfaceDestroyed(holder: SurfaceHolder) {
        surfaceReady = false
        streamRequested = false
        if (ControlClient.state.value == ControlClient.State.STREAMING) {
            ControlClient.stopStream()
        }
        stopReceivers("surface-destroyed")
        videoDecoder?.release()
        videoDecoder = null
    }

    // ---- Control channel plumbing --------------------------------------------------------

    private fun observeControlClient() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch { ControlClient.state.collect { state -> handleState(state) } }
                launch { ControlClient.events.collect { msg -> handleEvent(msg) } }
            }
        }
    }

    private fun handleState(state: ControlClient.State) {
        updateStatusChip()
        when (state) {
            ControlClient.State.READY -> {
                // Either a fresh session, or we just silently reconnected while this Activity
                // was in the foreground -- either way, (re)start the stream.
                if (!streamStartFailed) {
                    streamRequested = false
                    maybeStartStream()
                }
            }
            ControlClient.State.CONNECTING -> {
                showCenterStatus("Connecting to ${ControlClient.serverIp}…")
            }
            ControlClient.State.RECONNECTING -> {
                streamStartFailed = false
                streamRequested = false
                stopReceivers("reconnecting")
                showCenterStatus("Connection lost\nReconnecting to ${ControlClient.serverIp}…")
            }
            ControlClient.State.DISCONNECTED -> {
                streamStartFailed = false
                streamRequested = false
                stopReceivers("disconnected")
                showCenterStatus("Disconnected\nCheck that the PC server is still running")
            }
            ControlClient.State.PAIRING -> {
                // The server no longer recognizes our token (it was re-paired or reset) and a
                // reconnect landed in PAIRING. The PIN dialog lives on MainActivity -- go back
                // there instead of dead-ending on a black screen.
                finish()
            }
            else -> {}
        }
    }

    private fun handleEvent(msg: ServerMessage) {
        try {
            when (msg) {
                is ServerMessage.StreamStarted -> onStreamStarted(msg)
                is ServerMessage.AudioStarted -> onAudioStarted(msg)
                is ServerMessage.AudioUnavailable -> onAudioUnavailable(msg.message)
                ServerMessage.InputStarted -> onInputStarted()
                is ServerMessage.InputUnavailable -> onInputUnavailable(msg.message)
                ServerMessage.StreamStopped -> {
                    stopReceivers("stream-stopped")
                    if (!stallRecoveryInProgress && !streamStartFailed) {
                        showCenterStatus("Stream stopped")
                    }
                }
                is ServerMessage.Bitrate -> {
                    negotiatedBitrateKbps = msg.kbps
                    renderDiagnostics()
                }
                is ServerMessage.Error -> {
                    if (msg.code == "STREAM_FAILED" || msg.code == "ENCODER_UNAVAILABLE") {
                        // Do not immediately spin START_STREAM after a real server-side fault.
                        // Keep the Activity visible with the actual error instead of looking as
                        // though it crashed or disappeared.
                        streamStartFailed = true
                        streamRequested = true
                        stopReceivers("stream-error")
                    }
                    val description = describeStreamError(msg)
                    showCenterStatus(description)
                    showSnackbar(description, Snackbar.LENGTH_LONG)
                }
                else -> {}
            }
        } catch (e: Exception) {
            // A setup failure must leave the control socket alive and visible to the user.
            // It is also emitted to logcat with the full stack trace for device-specific fixes.
            Log.e(TAG, "stream event setup failed for ${msg::class.java.simpleName}", e)
            streamStartFailed = true
            streamRequested = true
            try { stopReceivers("setup-failed") } catch (_: Exception) { }
            val detail = e.message?.takeIf { it.isNotBlank() } ?: e.javaClass.simpleName
            showCenterStatus("Client setup failed: $detail")
            showSnackbar("Client setup failed; see logcat", Snackbar.LENGTH_LONG)
            ControlClient.stopStream()
        }
    }

    private fun maybeStartStream() {
        if (surfaceReady && !streamRequested && ControlClient.state.value == ControlClient.State.READY) {
            streamRequested = true
            showCenterStatus("Connected to ${ControlClient.serverIp}\nStarting video…")
            ControlClient.startStream(
                MAX_BITRATE_KBPS,
                TARGET_FPS,
                codecs = offeredCodecs,
                recovery = listOf("refresh"),
                fec = listOf("adaptive")
            )
        }
    }

    private fun onStreamStarted(msg: ServerMessage.StreamStarted) {
        stallRecoveryInProgress = false
        streamStartFailed = false
        binding.tvConnecting.visibility = View.GONE
        lastStreamWidth = msg.width
        lastStreamHeight = msg.height
        lastStreamFps = msg.fps
        lastStreamCodec = msg.codec
        lastStreamRecovery = msg.recovery
        lastEncoderBackend = msg.encoderBackend
        negotiatedBitrateKbps = 0
        lastVideoStats = null
        lastAudioStats = null
        audioStatus = "starting"
        audioDetail = "Negotiating with the PC"
        applyAspectRatio(msg.width, msg.height)
        updateStatusChip()

        val holder = binding.surfaceView.holder
        if (holder.surface == null || !holder.surface.isValid) {
            // The server has already entered STREAMING, while maybeStartStream only sends from
            // READY. Explicitly stop this receiver-less epoch so STREAM_STOPPED -> READY and a
            // later surfaceCreated can negotiate a fresh stream instead of staying black.
            streamRequested = false
            ControlClient.stopStream()
            return
        }

        // Always release + recreate on STREAM_STARTED: dimensions may differ, and frameId
        // numbering restarts server-side even if they don't.
        stopReceivers("new-stream")

        // Bind every callback to this exact stream epoch. A stale codec/receiver callback from
        // a prior restart can then only return its own buffer; it cannot poison the new epoch's
        // telemetry or force the new assembler back into IDR recovery.
        lateinit var receiver: MediaReceiver
        lateinit var decoder: VideoDecoder
        decoder = VideoDecoder(
            onDropOrError = {
                if (mediaReceiver === receiver && videoDecoder === decoder) {
                    receiver.notifyExternalDrop()
                }
            },
            onBufferRelease = { buffer -> receiver.bufferPool.release(buffer) },
            onFrameRendered = { latencyMs ->
                if (mediaReceiver === receiver && videoDecoder === decoder) {
                    receiver.recordDecodedFrame(latencyMs)
                }
            }
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            try {
                holder.surface.setFrameRate(
                    msg.fps.toFloat(),
                    Surface.FRAME_RATE_COMPATIBILITY_FIXED_SOURCE
                )
            } catch (_: Exception) { }
        }

        var mediaStartupError = "Media socket could not start"
        receiver = MediaReceiver(
            onFrame = { data, length, keyframe, frameId, _ ->
                if (mediaReceiver === receiver && videoDecoder === decoder) {
                    decoder.submitFrame(data, length, keyframe, frameId)
                } else {
                    receiver.bufferPool.release(data)
                }
            },
            onStats = { stats ->
                runOnUiThread {
                    if (mediaReceiver === receiver && videoDecoder === decoder) {
                        updateStatsOverlay(stats)
                    }
                }
            },
            onStalled = {
                runOnUiThread {
                    if (mediaReceiver === receiver && videoDecoder === decoder) {
                        restartStalledStream()
                    }
                }
            },
            onCursorPosition = { position ->
                runOnUiThread {
                    if (mediaReceiver === receiver && videoDecoder === decoder) {
                        updateRemoteCursor(position)
                    }
                }
            },
            onStartupError = { detail -> mediaStartupError = detail },
            refreshRecovery = msg.recovery == "refresh"
        )
        mediaReceiver = receiver
        videoDecoder = decoder
        decoder.resetForNewStream(holder.surface, msg.width, msg.height, msg.fps, msg.codec)
        if (!receiver.start(ControlClient.serverIp, msg.mediaPort, msg.clockBaseUs)) {
            mediaReceiver = null
            videoDecoder = null
            decoder.release()
            streamStartFailed = true
            streamRequested = true
            showCenterStatus("Client media setup failed: $mediaStartupError")
            showSnackbar(mediaStartupError, Snackbar.LENGTH_LONG)
            ControlClient.stopStream()
            return
        }

        negotiateMouseInput()

        binding.tvStreamStatus.visibility = View.VISIBLE
        renderDiagnostics()
        ControlClient.startAudio()
        audioNegotiationJob?.cancel()
        audioNegotiationJob = lifecycleScope.launch {
            delay(AUDIO_NEGOTIATION_TIMEOUT_MS)
            if (audioReceiver == null && audioStatus == "starting") {
                audioStatus = "unavailable"
                audioDetail = "No audio reply (the server may be an older version)"
                renderDiagnostics()
            }
        }
    }

    private fun negotiateMouseInput() {
        mouseStatus = "negotiating"
        applyMouseControls()
        ControlClient.startMouseKeyboardInput()
        inputNegotiationJob?.cancel()
        inputNegotiationJob = lifecycleScope.launch {
            delay(INPUT_NEGOTIATION_TIMEOUT_MS)
            if (mouseStatus == "negotiating") {
                mouseStatus = "unavailable"
                applyMouseControls()
            }
        }
    }

    private fun onInputStarted() {
        inputNegotiationJob?.cancel()
        inputNegotiationJob = null
        mouseStatus = "live"
        applyMouseControls()
        showMouseGestureHint()
        renderDiagnostics()
    }

    private fun onInputUnavailable(message: String) {
        inputNegotiationJob?.cancel()
        inputNegotiationJob = null
        mouseStatus = "unavailable"
        applyMouseControls()
        showSnackbar("Remote mouse unavailable: $message", Snackbar.LENGTH_LONG)
    }

    private fun applyMouseControls() {
        val active = mouseStatus == "live"
        remoteMouse.setEnabled(active)
        // Do not flash an unpositioned cursor in the top-left corner. It becomes visible
        // only after feedback arrives for the first motion packet.
        if (!active) {
            if (binding.tvRemoteCursor.visibility == View.VISIBLE) {
                lastCursorHide = "input:$mouseStatus"
            }
            binding.tvRemoteCursor.visibility = View.GONE
            hideMouseGestureHint()
            cursorFallbackJob?.cancel()
            cursorFallbackJob = null
            cursorFallbackShown = false
            // Un-retire the fallback together with the hide: the DSMC that retired it
            // belonged to the live stretch that just died, and a retirement outliving it
            // would leave the cursor permanently un-drawable if the next DSMC never
            // arrives. The first updateRemoteCursor after input returns re-arms it.
            cursorDsmcSeen = false
        }
    }

    private fun updateRemoteCursor(position: CursorPosition) {
        if (mouseStatus != "live") return
        val surface = binding.surfaceView
        val cursor = binding.tvRemoteCursor
        if (surface.width <= 0 || surface.height <= 0) return
        // Real DSMC feedback is on screen now — snap to it and retire the integrated
        // fallback so the two never fight over the same view. These flags move only after
        // the guards above: a DSMC that arrives before input goes live (or before the
        // surface exists) must not kill the fallback while nothing was actually drawn.
        cursorDsmcSeen = true
        cursorFallbackJob?.cancel()
        cursorFallbackJob = null
        if (position.hidden) {
            // Mirror the host: a fullscreen player hid its pointer, so the overlay goes too.
            // cursorDsmcSeen stays set, keeping the integrated fallback from redrawing it; the
            // next DSMC without the flag (pointer moved/shown again) brings it straight back.
            if (cursor.visibility == View.VISIBLE) lastCursorHide = "host-hidden"
            cursor.visibility = View.GONE
            return
        }
        // The vector's top-left point is its hotspot, so no size-based centering offset is
        // needed. Use width/height - 1 to mirror the absolute-input normalization exactly.
        cursor.translationX = surface.x + position.x / 65535f * (surface.width - 1)
        cursor.translationY = surface.y + position.y / 65535f * (surface.height - 1)
        cursor.visibility = View.VISIBLE
    }

    /**
     * Cursor insurance: the PC pointer is never part of the DXGI-captured frames — only DSMC
     * feedback ([updateRemoteCursor]) draws it. If that feedback never arrives (lost packet,
     * server without the feature), integrate the motion packets we already send — byte 5 is
     * the mode, bytes 12..15 / 16..19 the big-endian x/y (MousePacket) — starting from the
     * screen center, and reveal the overlay once motion has flowed for
     * [CURSOR_FALLBACK_DELAY_MS]. Runs on the main thread (touch or D-pad nudge).
     */
    private fun onOutgoingMousePacket(packet: ByteArray) {
        cursorOutPackets++
        if (cursorDsmcSeen || packet.size < MousePacket.SIZE) return
        if (mouseStatus != "live") return
        if (lastStreamWidth <= 0 || lastStreamHeight <= 0) return
        // Motion is always relative host-pixel deltas; the stream is the host desktop at
        // native size, so its dimensions convert them to the normalized overlay position.
        val x = readBeInt(packet, 12)
        val y = readBeInt(packet, 16)
        virtualCursorX = (virtualCursorX + x.toFloat() / lastStreamWidth).coerceIn(0f, 1f)
        virtualCursorY = (virtualCursorY + y.toFloat() / lastStreamHeight).coerceIn(0f, 1f)
        if (cursorFallbackShown) {
            renderVirtualCursor()
        } else if (cursorFallbackJob == null) {
            cursorFallbackJob = lifecycleScope.launch {
                delay(CURSOR_FALLBACK_DELAY_MS)
                cursorFallbackJob = null
                if (!cursorDsmcSeen && mouseStatus == "live") {
                    cursorFallbackShown = true
                    renderVirtualCursor()
                }
            }
        }
    }

    private fun renderVirtualCursor() {
        if (cursorDsmcSeen || mouseStatus != "live") return
        val surface = binding.surfaceView
        val cursor = binding.tvRemoteCursor
        if (surface.width <= 0 || surface.height <= 0) return
        cursor.translationX = surface.x + virtualCursorX * (surface.width - 1)
        cursor.translationY = surface.y + virtualCursorY * (surface.height - 1)
        cursor.visibility = View.VISIBLE
    }

    private fun readBeInt(bytes: ByteArray, offset: Int): Int =
        ((bytes[offset].toInt() and 0xff) shl 24) or
            ((bytes[offset + 1].toInt() and 0xff) shl 16) or
            ((bytes[offset + 2].toInt() and 0xff) shl 8) or
            (bytes[offset + 3].toInt() and 0xff)

    private fun showMouseGestureHint() {
        if (mouseStatus != "live") return
        mouseHintJob?.cancel()
        binding.tvGestureHint.animate().cancel()
        binding.tvGestureHint.alpha = 1f
        binding.tvGestureHint.visibility = View.VISIBLE
        mouseHintJob = lifecycleScope.launch {
            delay(MOUSE_HINT_VISIBLE_MS)
            binding.tvGestureHint.animate()
                .alpha(0f)
                .setDuration(MOUSE_HINT_FADE_MS)
                .withEndAction {
                    binding.tvGestureHint.visibility = View.GONE
                    binding.tvGestureHint.alpha = 1f
                }
                .start()
        }
    }

    private fun hideMouseGestureHint() {
        mouseHintJob?.cancel()
        mouseHintJob = null
        binding.tvGestureHint.animate().cancel()
        binding.tvGestureHint.alpha = 1f
        binding.tvGestureHint.visibility = View.GONE
    }

    private fun restartStalledStream() {
        restartStream("Video stalled\nRecovering stream…")
    }

    /**
     * Shared stop -> STREAM_STOPPED -> READY -> start restart path. [handleState] restarts the
     * pipeline as soon as the control channel reports READY again (the same reconnect-driven
     * restart used after a background/foreground cycle) instead of inventing a second state
     * machine.
     */
    private fun restartStream(statusMessage: String) {
        if (stallRecoveryInProgress || !surfaceReady ||
            ControlClient.state.value != ControlClient.State.STREAMING
        ) return
        stallRecoveryInProgress = true
        showCenterStatus(statusMessage)
        remoteMouse.setEnabled(false)
        hideMouseGestureHint()
        streamRequested = false
        // Wait for STREAM_STOPPED/READY before requesting the replacement pipeline. This
        // preserves STOP_STREAM -> START_STREAM ordering on the control connection.
        ControlClient.stopStream()
    }

    /** D-pad-friendly leave confirmation. The old Snackbar variant was effectively unusable
     * with a TV remote: its action could not be focused reliably with the D-pad and it
     * auto-dismissed after a few seconds, so LEAVE never happened (or the remote appeared
     * stuck). A modal dialog waits for an explicit choice and both buttons are focusable. */
    private fun confirmLeave() {
        if (leaveDialog?.isShowing == true) return
        leaveDialog = MaterialAlertDialogBuilder(this)
            .setTitle(R.string.leave_stream_prompt)
            .setPositiveButton(R.string.leave_stream_action) { _, _ ->
                ControlClient.stopStream()
                finish()
            }
            .setNegativeButton(R.string.leave_stream_cancel, null)
            .setOnDismissListener { leaveDialog = null }
            .show()
    }

    /** Hides every non-video view. Three-finger hold, Back, or F11 restores the overlay without
     * sharing a gesture with remote mouse click/drag input. */
    private fun hideControls() {
        if (controlsHidden) return
        dismissActiveSnackbars()
        controlsHidden = true
        // Starts the D-pad-as-mouse fallback from a clean state -- any in-flight direction hold
        // or pending long-press from just before Hide was pressed must not carry over.
        resetRemotePointerState()
        applyControlsVisibility()
        Toast.makeText(this, R.string.controls_hidden_hint, Toast.LENGTH_SHORT).show()
    }

    private fun showControls() {
        if (!controlsHidden) return
        controlsHidden = false
        resetRemotePointerState()
        applyControlsVisibility()
        // Hand focus back to a D-pad remote immediately -- otherwise the next D-pad press has
        // no current focus to start navigating on-screen buttons from.
        binding.btnHideControls.requestFocus()
    }

    private fun showSnackbar(message: CharSequence, duration: Int) {
        createSnackbar(message, duration)?.show()
    }

    private fun createSnackbar(message: CharSequence, duration: Int): Snackbar? {
        if (controlsHidden) return null
        val snackbar = Snackbar.make(binding.streamRoot, message, duration)
        activeSnackbars += snackbar
        snackbar.addCallback(object : Snackbar.Callback() {
            override fun onDismissed(transientBottomBar: Snackbar?, event: Int) {
                activeSnackbars -= snackbar
            }
        })
        return snackbar
    }

    private fun dismissActiveSnackbars() {
        activeSnackbars.toList().forEach { it.dismiss() }
        activeSnackbars.clear()
    }

    /** Hiding the parent layer is intentional: asynchronous reconnect and status updates can
     * change child visibility without breaking clean-screen mode. The remote cursor is a
     * sibling above this layer and keeps its own visibility driven by updateRemoteCursor. */
    private fun applyControlsVisibility() {
        binding.overlayLayer.visibility = if (controlsHidden) View.GONE else View.VISIBLE
        if (controlsHidden) {
            hideMouseGestureHint()
        }
        // The activity is already sticky-immersive at all times (see applyImmersiveMode()); a
        // system-bar swipe-reveal while controls are hidden should still auto-hide again, so
        // re-assert it here rather than introducing a second immersive mode.
        applyImmersiveMode()
    }

    private fun updateStatusChip() {
        val state = ControlClient.state.value
        val streaming = state == ControlClient.State.STREAMING && lastStreamWidth > 0 && lastStreamHeight > 0
        val (text, colorRes) = when {
            streaming ->
                getString(R.string.status_streaming, lastStreamWidth, lastStreamHeight, lastStreamFps) to R.color.status_green
            state == ControlClient.State.RECONNECTING -> getString(R.string.status_reconnecting) to R.color.status_red
            state == ControlClient.State.DISCONNECTED -> getString(R.string.status_disconnected) to R.color.status_red
            else -> getString(R.string.status_connecting) to R.color.status_amber
        }
        binding.tvStreamStatus.text = text
        binding.viewStatusDot.backgroundTintList =
            ColorStateList.valueOf(ContextCompat.getColor(this, colorRes))
    }

    private fun updateStatsOverlay(stats: StreamStats) {
        lastVideoStats = stats
        renderDiagnostics()
    }

    private fun toggleDiagnostics() {
        statsVisible = !statsVisible
        binding.tvStats.visibility = if (statsVisible) View.VISIBLE else View.GONE
        hideMouseGestureHint()
        renderDiagnostics()
    }

    private fun onAudioStarted(msg: ServerMessage.AudioStarted) {
        audioNegotiationJob?.cancel()
        audioNegotiationJob = null
        audioReceiver?.stop()
        audioStatus = "starting"
        audioDetail = "Opening Android low-latency audio output"
        lastAudioStats = null

        lateinit var receiver: AudioReceiver
        receiver = AudioReceiver(
            onState = { state, detail ->
                runOnUiThread {
                    if (audioReceiver !== receiver) return@runOnUiThread
                    audioDetail = detail
                    audioStatus = when (state) {
                        AudioPlaybackState.STARTING -> "starting"
                        AudioPlaybackState.READY -> "ready"
                        AudioPlaybackState.PLAYING -> "live"
                        AudioPlaybackState.ERROR -> "unavailable"
                    }
                    if (state == AudioPlaybackState.ERROR) {
                        audioReceiver = null
                    }
                    renderDiagnostics()
                }
            },
            onStats = { stats ->
                runOnUiThread {
                    if (audioReceiver !== receiver) return@runOnUiThread
                    lastAudioStats = stats
                    renderDiagnostics()
                }
            }
        )
        audioReceiver = receiver
        receiver.start(
            serverIp = ControlClient.serverIp,
            audioPort = msg.audioPort,
            sampleRate = msg.sampleRate,
            channels = msg.channels,
            format = msg.format,
            packetSamples = msg.packetSamples
        )
    }

    private fun onAudioUnavailable(message: String) {
        audioNegotiationJob?.cancel()
        audioNegotiationJob = null
        audioReceiver?.stop()
        audioReceiver = null
        audioStatus = "unavailable"
        audioDetail = message.ifEmpty { "PC system audio is unavailable" }
        renderDiagnostics()
        showSnackbar("Video is live, but audio is unavailable: $audioDetail", Snackbar.LENGTH_LONG)
    }

    /** [reason] is recorded as the hide cause when the cursor is currently visible, so the
     * CURSOR stats line can name the event that took it off screen. */
    private fun stopReceivers(reason: String) {
        cursorEpoch++
        if (binding.tvRemoteCursor.visibility == View.VISIBLE) lastCursorHide = reason
        audioNegotiationJob?.cancel()
        audioNegotiationJob = null
        inputNegotiationJob?.cancel()
        inputNegotiationJob = null
        hideMouseGestureHint()
        remoteMouse.setEnabled(false)
        binding.tvRemoteCursor.visibility = View.GONE
        cursorFallbackJob?.cancel()
        cursorFallbackJob = null
        cursorFallbackShown = false
        cursorDsmcSeen = false
        virtualCursorX = 0.5f
        virtualCursorY = 0.5f
        // cursorOutPackets intentionally survives: it is a lifetime counter (see field doc).
        val stoppedReceiver = mediaReceiver
        mediaReceiver = null
        stoppedReceiver?.stop()
        val stoppedDecoder = videoDecoder
        videoDecoder = null
        stoppedDecoder?.release()
        audioReceiver?.stop()
        audioReceiver = null
    }

    private fun showCenterStatus(message: String) {
        binding.tvConnecting.text = message
        binding.tvConnecting.visibility = View.VISIBLE
        // The connection-state chip (tvStreamStatus/viewStatusDot) is intentionally left alone
        // here: it now reflects Connecting/Streaming/Reconnecting continuously (see
        // updateStatusChip()), independent of this large center-screen message.
    }

    private fun renderDiagnostics() {
        if (lastStreamWidth <= 0 || lastStreamHeight <= 0) return
        updateStatusChip()

        val video = lastVideoStats
        val audio = lastAudioStats
        binding.tvStats.text = buildString {
            append("STATE  LIVE · ${ControlClient.serverIp}\n")
            append("VIDEO  ${lastStreamWidth}×${lastStreamHeight} @ $lastStreamFps · ${lastStreamCodec.uppercase()} (offered ${offeredCodecs.joinToString("/")}) · $lastStreamRecovery recovery\n")
            append("ENC    $lastEncoderBackend")
            if (negotiatedBitrateKbps > 0) append(" · $negotiatedBitrateKbps kbps target")
            append('\n')
            append("DEC    ${videoDecoder?.diagnostics ?: "not started"}\n")
            append("TV     game mode $tvGameModeStatus\n")
            if (video != null) {
                append("NET    ${video.kbps} kbps · ${"%.1f".format(video.lossPercent)}% loss\n")
                append(
                    "VIDEO  ${video.fps} fps decoded · ${video.assembledFps} assembled · " +
                        "${video.framesDropped} drops/s\n"
                )
                if (video.serverPipelineP95Ms >= 0 || video.captureToReceiveP95Ms >= 0 ||
                    video.decodeToSurfaceP95Ms >= 0
                ) {
                    append("LAT    ${video.serverPipelineP95Ms} ms server · ")
                    append("${video.captureToReceiveP95Ms} ms capture→receive · ")
                    append("${video.decodeToSurfaceP95Ms} ms decode→surface (p95)\n")
                }
            } else {
                append("NET    waiting for first video stats…\n")
            }
            append("AUDIO  $audioDetail")
            append('\n')
            if (audio != null) {
                val activity = if (audio.receivingAudio) "receiving" else "source quiet"
                append("A-NET  ${audio.kbps} kbps · ${"%.1f".format(audio.packetLossPercent)}% loss · $activity\n")
                append("A-OUT  ${audio.outputBufferMs} ms buffer · ${audio.outputDrops} drops · ${audio.underruns} underruns")
            } else {
                append("A-NET  waiting for audio packets…")
            }
            append("\nMOUSE  $mouseStatus")
            append("\nCURSOR out=$cursorOutPackets · dsmc=")
            append(if (cursorDsmcSeen) "seen" else "none")
            append(" · fallback=")
            append(
                when {
                    cursorFallbackShown -> "shown"
                    cursorFallbackJob != null -> "armed"
                    else -> "idle"
                }
            )
            append(" · vis=")
            append(if (binding.tvRemoteCursor.visibility == View.VISIBLE) "on" else "off")
            append(" · hide=").append(lastCursorHide)
            append(" · ep=").append(cursorEpoch)
        }
    }

    private fun createWifiLock() {
        // This is only a latency optimization. Some Android TV and OEM Wi-Fi services reject
        // low-latency locks, so failure must never take down the streaming Activity.
        try {
            val wifi = applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
                ?: return
            val mode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                WifiManager.WIFI_MODE_FULL_LOW_LATENCY
            } else {
                @Suppress("DEPRECATION")
                WifiManager.WIFI_MODE_FULL_HIGH_PERF
            }
            wifiLock = wifi.createWifiLock(mode, "LocalStream:low-latency").apply {
                setReferenceCounted(false)
            }
        } catch (_: Exception) {
            wifiLock = null
        }
    }

    private fun acquireWifiLock() {
        try { if (wifiLock?.isHeld != true) wifiLock?.acquire() } catch (_: Exception) { }
    }

    private fun releaseWifiLock() {
        try { if (wifiLock?.isHeld == true) wifiLock?.release() } catch (_: Exception) { }
    }

    private fun describeStreamError(msg: ServerMessage.Error): String = when (msg.code) {
        "ENCODER_UNAVAILABLE" -> "No supported hardware H.264 encoder was found on the PC"
        "STREAM_FAILED" -> "The PC could not start streaming: ${msg.message}"
        "CONNECTION_LOST" -> "Connection to the PC was lost"
        else -> msg.message.ifEmpty { "Streaming error (${msg.code})" }
    }

    private fun applyAspectRatio(streamWidth: Int, streamHeight: Int) {
        val container = binding.streamRoot
        if (container.width <= 0 || container.height <= 0) {
            container.post { applyAspectRatio(streamWidth, streamHeight) }
            return
        }
        if (streamWidth <= 0 || streamHeight <= 0) return

        val containerAspect = container.width.toFloat() / container.height.toFloat()
        val streamAspect = streamWidth.toFloat() / streamHeight.toFloat()

        val targetWidth: Int
        val targetHeight: Int
        if (containerAspect > streamAspect) {
            // Container is relatively wider than the stream -> pillarbox (bars on the sides).
            targetHeight = container.height
            targetWidth = (container.height * streamAspect).toInt()
        } else {
            // Container is relatively taller/narrower -> letterbox (bars top/bottom).
            targetWidth = container.width
            targetHeight = (container.width / streamAspect).toInt()
        }

        val lp = binding.surfaceView.layoutParams as FrameLayout.LayoutParams
        if (lp.width == targetWidth && lp.height == targetHeight && lp.gravity == Gravity.CENTER) {
            return // avoid a set-layout -> layout-change-listener -> set-layout loop
        }
        lp.width = targetWidth
        lp.height = targetHeight
        lp.gravity = Gravity.CENTER
        binding.surfaceView.layoutParams = lp
    }

    companion object {
        private const val TAG = "StreamActivity"
        private const val STATE_CONTROLS_HIDDEN = "controls_hidden"
        private const val MAX_BITRATE_KBPS = 50000
        private const val TARGET_FPS = 60
        private const val MOUSE_HINT_VISIBLE_MS = 4500L
        // Insurance cursor: only claim the pointer is missing after motion actually flowed.
        private const val CURSOR_FALLBACK_DELAY_MS = 150L
        private const val MOUSE_HINT_FADE_MS = 250L
        /** USB HID Keyboard-page usage for Left Control. */
        private const val HID_LEFT_CTRL = 0xE0
        private const val AUDIO_NEGOTIATION_TIMEOUT_MS = 3500L
        private const val INPUT_NEGOTIATION_TIMEOUT_MS = 2500L
        // D-pad-as-mouse fallback (clean-screen only; see handleRemotePointerKey).
        // 2 dp per 40 ms tick: remotes are held down, so precision beats speed. 2 dp falls in
        // RemoteMouseController's normal gain band (x2.5 instead of x3.5), so one tick moves
        // ~5 host px (~125 px/s held) - about a quarter of the previous 6 dp setting.
        private const val REMOTE_POINTER_STEP_DP = 2f
        private const val REMOTE_POINTER_REPEAT_MS = 40L
        private const val REMOTE_POINTER_LONG_PRESS_MS = 500L
    }
}
