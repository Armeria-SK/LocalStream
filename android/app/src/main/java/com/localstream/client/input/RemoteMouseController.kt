package com.localstream.client.input

import android.os.Build
import android.os.SystemClock
import android.view.Choreographer
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.VelocityTracker
import android.view.View
import android.view.ViewConfiguration
import com.localstream.client.net.ControlClient
import com.localstream.client.proto.MousePacket
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.hypot
import kotlin.math.roundToInt

/** Converts touch gestures into low-latency relative (touchpad-style) remote mouse input. */
class RemoteMouseController(
    private val target: View,
    private val sendMotion: (ByteArray) -> Unit,
    /** The reveal path is captured only while clean screen is active, so ordinary three-finger
     * input cannot interfere with mouse use while the controls are visible. */
    private val shouldHandleCleanScreenGesture: () -> Boolean = { false },
    private val onCleanScreenReveal: () -> Unit = {},
    /** Laptop/phone trackpad semantics for a dedicated pad (controller screen): a single tap
     * clicks, tap-then-drag drags, two-finger scrolling follows the fingers on either axis
     * and glides on after a flick, and [attachClickZone] adds physical-style buttons. Off,
     * the stream surface keeps its double-tap-to-click rules so resting a finger on the
     * video never clicks. */
    private val trackpad: Boolean = false,
    /** Trackpad only: presses (true) / releases (false) Ctrl on the PC. When set, a two-finger
     * pinch zooms the way a Windows precision touchpad does, as Ctrl + wheel notches. The
     * caller sends the key because it owns the keyboard sequence space. */
    private val zoomModifier: ((Boolean) -> Unit)? = null,
    /** Carries the Ctrl-bracketed zoom wheel notch. It must be the ordered channel
     * [zoomModifier] uses, or the notch can reach the PC outside its Ctrl press and scroll
     * instead of zooming. Defaults to [sendMotion]. */
    private val sendZoomMotion: ((ByteArray) -> Unit)? = null
) : View.OnTouchListener {
    private val packet = ByteArray(MousePacket.SIZE)
    private var enabled = false
    private var motionSequence = 0L
    private var buttonSequence = 0L
    private var lastX = 0f
    private var lastY = 0f
    private var downX = 0f
    private var downY = 0f
    private var downAt = 0L
    private val displayDensity = target.resources.displayMetrics.density.coerceAtLeast(0.1f)
    private val touchSlopPx = ViewConfiguration.get(target.context).scaledTouchSlop.toFloat()
    private val doubleTapSlopPx = ViewConfiguration.get(target.context).scaledDoubleTapSlop.toFloat()
    private var pendingTapAt = 0L
    private var pendingTapX = 0f
    private var pendingTapY = 0f
    private var doubleTapInProgress = false
    private var lastMotionSentAtNanos = 0L
    private var moved = false
    private var leftHeld = false
    private var maxPointers = 1
    private var scrollRemainder = 0f
    private var pendingDx = 0f
    private var pendingDy = 0f
    private var twoFingerTravel = 0f
    // Trackpad-only state (see [trackpad]).
    private var zoneLeftHeld = false
    private var zoneRightHeld = false
    private var twoFingerMode = TWO_FINGER_UNDECIDED
    private var pinchStartSpan = 0f
    private var pinchStepSpan = 0f
    private var scrollTravelXDp = 0f
    private var scrollTravelYDp = 0f
    private var lastScrollMoveAt = 0L
    /** The touch that halted a glide is a "stop" gesture, never a click. */
    private var tapSuppressed = false
    private var velocityTracker: VelocityTracker? = null
    private val choreographer = Choreographer.getInstance()
    private var flingActive = false
    private var flingHorizontal = false
    private var flingVelocityDp = 0f
    private var flingRemainder = 0f
    private var flingLastFrameNanos = 0L
    private val flingFrame = object : Choreographer.FrameCallback {
        override fun doFrame(frameTimeNanos: Long) {
            if (!flingActive) return
            if (!enabled) {
                flingActive = false
                return
            }
            val dtMs = ((frameTimeNanos - flingLastFrameNanos) / 1_000_000f)
                .coerceIn(0f, FLING_MAX_FRAME_MS)
            flingLastFrameNanos = frameTimeNanos
            // Exact travel of an exponentially decaying velocity over this frame, so the
            // glide length does not depend on the display's refresh rate.
            val decay = exp(-dtMs / FLING_TIME_CONSTANT_MS)
            val travelDp = flingVelocityDp * FLING_TIME_CONSTANT_MS * (1f - decay)
            flingVelocityDp *= decay
            flingRemainder = emitNaturalScroll(flingHorizontal, travelDp, flingRemainder)
            if (abs(flingVelocityDp) < FLING_STOP_VELOCITY_DP_PER_MS) {
                flingActive = false
            } else {
                choreographer.postFrameCallback(this)
            }
        }
    }
    // Clean-screen reveal tracking is independent of mouse state so it still works while mouse
    // forwarding is unavailable or explicitly disabled.
    private var threeFingerActive = false
    private var threeFingerMoved = false
    private var threeFingerRevealFired = false
    private var threeFingerCentroidX = 0f
    private var threeFingerCentroidY = 0f
    private val cleanScreenReveal = Runnable {
        if (threeFingerActive && !threeFingerMoved && !threeFingerRevealFired) {
            threeFingerRevealFired = true
            onCleanScreenReveal()
        }
    }

    init { target.setOnTouchListener(this) }

    fun setEnabled(value: Boolean) {
        if (enabled == value) return
        enabled = value
        if (!value) reset()
    }

    fun clickLeft() {
        if (enabled) {
            clearPendingTap()
            sendClick("left")
        }
    }

    fun clickRight() {
        if (enabled) {
            clearPendingTap()
            sendClick("right")
        }
    }

    /** Directional nudge for non-touch input (D-pad/remote). [dxDp]/[dyDp] are in dp, same units
     * as a touch drag, and are pushed through the same relative-motion pipeline/gain curve so
     * D-pad and finger movement feel consistent. Always sent immediately (bypasses the 120 Hz
     * coalescing window) since D-pad repeats already arrive at a bounded rate from the caller. */
    fun nudge(dxDp: Float, dyDp: Float) {
        if (!enabled) return
        sendRelative(dxDp * displayDensity, dyDp * displayDensity, force = true)
    }

    /**
     * Turns [zone] into a laptop-style physical button for [button] ("left"/"right"): the
     * press holds the button on the PC until the finger lifts, so holding one zone while
     * another finger moves on the pad drags, and a finger that pressed the zone may slide
     * on to drag by itself.
     */
    fun attachClickZone(zone: View, button: String) {
        zone.setOnTouchListener(ClickZoneListener(button))
    }

    private inner class ClickZoneListener(private val button: String) : View.OnTouchListener {
        private var pointerId = INVALID_POINTER
        private var lastX = 0f
        private var lastY = 0f
        private var downX = 0f
        private var downY = 0f
        private var sliding = false

        override fun onTouch(view: View, event: MotionEvent): Boolean {
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    view.requestUnbufferedDispatch(event)
                    stopFling()
                    pointerId = event.getPointerId(0)
                    downX = event.x
                    downY = event.y
                    lastX = event.x
                    lastY = event.y
                    sliding = false
                    view.isPressed = true
                    view.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                    pressZone(button, true)
                }

                MotionEvent.ACTION_MOVE -> {
                    val index = event.findPointerIndex(pointerId)
                    if (index < 0) return true
                    val x = event.getX(index)
                    val y = event.getY(index)
                    // A resting thumb jitters; only a deliberate slide moves the cursor.
                    if (!sliding &&
                        hypot((x - downX).toDouble(), (y - downY).toDouble()) >= touchSlopPx
                    ) sliding = true
                    if (sliding && enabled) sendRelative(x - lastX, y - lastY)
                    lastX = x
                    lastY = y
                }

                MotionEvent.ACTION_POINTER_UP ->
                    if (event.getPointerId(event.actionIndex) == pointerId) release(view, event)

                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> release(view, event)
            }
            return true
        }

        private fun release(view: View, event: MotionEvent) {
            if (pointerId == INVALID_POINTER) return
            val index = event.findPointerIndex(pointerId)
            if (sliding && enabled && index >= 0) {
                sendRelative(event.getX(index) - lastX, event.getY(index) - lastY, force = true)
            }
            pointerId = INVALID_POINTER
            view.isPressed = false
            if (event.actionMasked != MotionEvent.ACTION_CANCEL &&
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1
            ) {
                view.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY_RELEASE)
            }
            pressZone(button, false)
        }
    }

    private fun pressZone(button: String, down: Boolean) {
        if (!enabled) return
        if (button == "left") {
            if (down == zoneLeftHeld) return
            zoneLeftHeld = down
            // Pressing the zone mid tap-drag adopts the already-held button, so lifting the
            // pad finger no longer ends the drag, exactly like a laptop's button.
            if (down && leftHeld) {
                leftHeld = false
                return
            }
        } else {
            if (down == zoneRightHeld) return
            zoneRightHeld = down
        }
        clearPendingTap()
        sendButton(button, down)
    }

    fun reset() {
        stopFling()
        if (leftHeld) sendButton("left", false)
        leftHeld = false
        if (zoneLeftHeld) sendButton("left", false)
        if (zoneRightHeld) sendButton("right", false)
        zoneLeftHeld = false
        zoneRightHeld = false
        clearPendingTap()
        ControlClient.resetMouse()
        cancelCleanScreenGesture()
        resetGestureOnly()
    }

    override fun onTouch(view: View, event: MotionEvent): Boolean {
        // Clean-screen reveal detection runs before mouse handling. It consumes the entire
        // three-finger sequence, including all lifts, so it cannot become a right/left click.
        when (event.actionMasked) {
            MotionEvent.ACTION_POINTER_DOWN -> {
                if (event.pointerCount == 3 && !threeFingerActive &&
                    shouldHandleCleanScreenGesture()
                ) {
                    // Release any in-progress drag and clear pending tap state before the reveal
                    // hold starts. Cursor motion may have preceded the third finger, but no
                    // button transition from this gesture can reach the host.
                    if (enabled) {
                        if (leftHeld) sendButton("left", false)
                        ControlClient.resetMouse()
                    }
                    leftHeld = false
                    clearPendingTap()
                    resetGestureOnly()
                    maxPointers = 3
                    threeFingerActive = true
                    threeFingerMoved = false
                    threeFingerRevealFired = false
                    threeFingerCentroidX = averageX(event)
                    threeFingerCentroidY = averageY(event)
                    target.removeCallbacks(cleanScreenReveal)
                    target.postDelayed(cleanScreenReveal, CLEAN_SCREEN_REVEAL_HOLD_MS)
                    return true
                } else if (threeFingerActive && event.pointerCount > 3) {
                    // A fourth+ finger disqualifies the hold, but the rest of the sequence is
                    // still consumed to prevent it falling through as mouse input.
                    threeFingerMoved = true
                    target.removeCallbacks(cleanScreenReveal)
                    return true
                }
            }

            MotionEvent.ACTION_MOVE -> if (threeFingerActive) {
                val cx = averageX(event)
                val cy = averageY(event)
                if (!threeFingerMoved &&
                    hypot((cx - threeFingerCentroidX).toDouble(), (cy - threeFingerCentroidY).toDouble()) >=
                    touchSlopPx * CLEAN_SCREEN_SLOP_MULTIPLIER
                ) {
                    threeFingerMoved = true
                    target.removeCallbacks(cleanScreenReveal)
                }
                return true
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP -> if (threeFingerActive) {
                if (event.actionMasked == MotionEvent.ACTION_UP) {
                    cancelCleanScreenGesture()
                    resetGestureOnly()
                } else if (event.pointerCount <= 3 && !threeFingerRevealFired) {
                    // One of the required fingers lifted before the hold completed.
                    threeFingerMoved = true
                    target.removeCallbacks(cleanScreenReveal)
                }
                return true
            }

            MotionEvent.ACTION_CANCEL -> if (threeFingerActive) {
                cancelCleanScreenGesture()
                resetGestureOnly()
                return true
            }

            else -> {}
        }

        // Returning true while clean screen is active keeps the sequence captured even if mouse
        // forwarding is off, allowing the second and third pointer events to reach this listener.
        if (!enabled) return shouldHandleCleanScreenGesture()
        if (trackpad) trackVelocity(event)
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                view.requestUnbufferedDispatch(event)
                // Like a phone list: touching a gliding scroll catches it, and that touch
                // must not also click whatever is now under the cursor.
                tapSuppressed = stopFling()
                downAt = event.eventTime
                doubleTapInProgress = isDoubleTapStart(downAt, event.x, event.y)
                // A second tap owns the pending first tap. An unrelated gesture starts a
                // fresh sequence instead of allowing a later accidental click.
                clearPendingTap()
                downX = event.x
                downY = event.y
                lastX = event.x
                lastY = event.y
                moved = false
                maxPointers = 1
                scrollRemainder = 0f
                twoFingerTravel = 0f
            }

            MotionEvent.ACTION_POINTER_DOWN -> {
                doubleTapInProgress = false
                clearPendingTap()
                maxPointers = maxOf(maxPointers, event.pointerCount)
                // Start multi-finger travel from the centroid at the moment the additional
                // finger lands. Tracking both axes prevents a horizontal two-finger swipe from
                // being mistaken for a stationary right-click tap when the fingers lift.
                lastX = averageX(event)
                lastY = averageY(event)
                if (event.pointerCount == 2) pinchStartSpan = span(event)
            }

            MotionEvent.ACTION_MOVE -> {
                maxPointers = maxOf(maxPointers, event.pointerCount)
                if (event.pointerCount >= 2) {
                    val x = averageX(event)
                    val y = averageY(event)
                    val deltaX = x - lastX
                    val deltaY = lastY - y
                    twoFingerTravel += hypot(deltaX.toDouble(), deltaY.toDouble()).toFloat()
                    if (twoFingerTravel >= touchSlopPx) moved = true
                    if (trackpad) {
                        // Only a gesture that has been exactly two fingers throughout scrolls:
                        // after a third finger lifts, the centroid jumps.
                        if (event.pointerCount == 2 && maxPointers == 2) {
                            trackpadTwoFinger(event, x - lastX, y - lastY)
                        }
                        lastX = x
                        lastY = y
                        return true
                    }
                    // MotionEvent coordinates are physical pixels. Convert to dp before
                    // producing wheel units so scrolling feels the same on phones with
                    // different display densities. Three-or-more-finger input is deliberately
                    // inert while the normal controls are visible; it must never scroll or
                    // become a right click.
                    if (event.pointerCount == 2) {
                        scrollRemainder += deltaY / displayDensity * SCROLL_UNITS_PER_DP
                    }
                    if (moved && event.pointerCount == 2) {
                        val wheel = scrollRemainder.toInt()
                        if (wheel != 0) {
                            send(MousePacket.MODE_RELATIVE, 0, 0, 0, wheel)
                            scrollRemainder -= wheel
                        }
                    }
                    lastX = x
                    lastY = y
                } else {
                    val dx = event.x - lastX
                    val dy = event.y - lastY
                    if (hypot((event.x - downX).toDouble(), (event.y - downY).toDouble()) >= touchSlopPx)
                        moved = true

                    if (trackpad && maxPointers > 1) {
                        // The finger left behind by a scroll or right-click tap must not nudge
                        // the cursor while the hand comes off the pad.
                    } else {
                        // Movement alone never presses a button. A double-tap followed by a
                        // move is the deliberate drag gesture.
                        if (!leftHeld && !zoneLeftHeld && moved && doubleTapInProgress) {
                            sendButton("left", true)
                            leftHeld = true
                        }
                        sendRelative(dx, dy)
                    }
                    lastX = event.x
                    lastY = event.y
                }
            }

            MotionEvent.ACTION_POINTER_UP -> {
                // A flick ends when the first of the two scrolling fingers lifts.
                if (trackpad && event.pointerCount == 2 && maxPointers == 2) maybeStartFling(event)
                // Continue smoothly with the remaining finger after a two-finger gesture.
                if (event.pointerCount == 2) {
                    val remaining = if (event.actionIndex == 0) 1 else 0
                    lastX = event.getX(remaining)
                    lastY = event.getY(remaining)
                }
            }

            MotionEvent.ACTION_UP -> {
                val upAt = event.eventTime

                // A gesture may end inside the 120 Hz coalescing window. Flush the final
                // position before clearing the accumulators so short swipes do not stop
                // short and the last part of a drag is not lost.
                if (moved && maxPointers == 1) {
                    sendRelative(event.x - lastX, event.y - lastY, force = true)
                    lastX = event.x
                    lastY = event.y
                }

                if (leftHeld) {
                    sendButton("left", false)
                    leftHeld = false
                } else if (!moved && maxPointers == 2 && !tapSuppressed &&
                    twoFingerTravel < touchSlopPx &&
                    upAt - downAt <= MAX_TAP_DURATION_MS
                ) {
                    clearPendingTap()
                    sendClick("right")
                } else if (maxPointers > 1) {
                    // A long/moving two-finger gesture or any three-or-more-finger gesture is
                    // neither a click nor the first half of a later double-tap.
                    clearPendingTap()
                } else if (!moved && upAt - downAt <= MAX_TAP_DURATION_MS) {
                    if (trackpad) {
                        // Tap-to-click, clicking at once: a second tap makes the double
                        // click, and remembering every tap lets tap-then-drag start a drag.
                        if (tapSuppressed || zoneLeftHeld || zoneRightHeld) {
                            clearPendingTap()
                        } else {
                            sendClick("left")
                            rememberTap(upAt, event.x, event.y)
                        }
                    } else if (doubleTapInProgress) {
                        // A single tap is intentionally movement-only. This second tap is
                        // the first point at which the surface itself may left-click.
                        sendClick("left")
                    } else {
                        rememberTap(upAt, event.x, event.y)
                    }
                } else {
                    clearPendingTap()
                }
                resetGestureOnly()
            }

            MotionEvent.ACTION_CANCEL -> {
                if (leftHeld) sendButton("left", false)
                leftHeld = false
                clearPendingTap()
                ControlClient.resetMouse()
                resetGestureOnly()
            }
        }
        return true
    }

    private fun sendRelative(dx: Float, dy: Float, force: Boolean = false) {
        val dxDp = dx / displayDensity
        val dyDp = dy / displayDensity
        val distanceDp = hypot(dxDp.toDouble(), dyDp.toDouble()).toFloat()
        val gain = when {
            distanceDp < PRECISE_MOTION_THRESHOLD_DP -> PRECISE_TOUCHPAD_GAIN
            distanceDp < FAST_MOTION_THRESHOLD_DP -> NORMAL_TOUCHPAD_GAIN
            else -> FAST_TOUCHPAD_GAIN
        }
        pendingDx += dxDp * gain
        pendingDy += dyDp * gain
        val now = SystemClock.elapsedRealtimeNanos()
        if (!force && now - lastMotionSentAtNanos < MIN_SEND_INTERVAL_NANOS) return
        val x = pendingDx.roundToInt()
        val y = pendingDy.roundToInt()
        pendingDx -= x
        pendingDy -= y
        if (x != 0 || y != 0) {
            send(MousePacket.MODE_RELATIVE, x, y, 0, 0, force = force)
        }
    }

    /**
     * A two-finger gesture commits to one meaning, as on a laptop: a pinch (the gap between
     * the fingers changes more than their midpoint travels) zooms, anything else scrolls,
     * railed to whichever axis the fingers first travel along since a vertical page scroll
     * that drifts sideways feels broken. [dx]/[dy] are centroid pixels.
     */
    private fun trackpadTwoFinger(event: MotionEvent, dx: Float, dy: Float) {
        val dxDp = dx / displayDensity
        val dyDp = dy / displayDensity
        when (twoFingerMode) {
            TWO_FINGER_UNDECIDED -> {
                scrollTravelXDp += dxDp
                scrollTravelYDp += dyDp
                val span = span(event)
                val spanChangePx = abs(span - pinchStartSpan)
                val centroidTravelPx =
                    hypot(scrollTravelXDp.toDouble(), scrollTravelYDp.toDouble()).toFloat() * displayDensity
                if (zoomModifier != null && pinchStartSpan > 0f &&
                    spanChangePx >= touchSlopPx && spanChangePx > centroidTravelPx
                ) {
                    twoFingerMode = TWO_FINGER_PINCH
                    // Moved: lifting a pinch must never read as a right-click tap.
                    moved = true
                    // Measured from touchdown, so the slop already spent counts toward the
                    // first zoom step.
                    pinchStepSpan = pinchStartSpan
                    pinchZoom(span)
                    return
                }
                lastScrollMoveAt = event.eventTime
                if (!moved) return
                val horizontal = abs(scrollTravelXDp) > abs(scrollTravelYDp)
                twoFingerMode = if (horizontal) TWO_FINGER_SCROLL_HORIZONTAL else TWO_FINGER_SCROLL_VERTICAL
                // Replay the travel spent crossing the slop so the page doesn't lag the fingers.
                scrollRemainder = emitNaturalScroll(
                    horizontal, if (horizontal) scrollTravelXDp else scrollTravelYDp, 0f
                )
            }

            TWO_FINGER_PINCH -> pinchZoom(span(event))

            else -> {
                lastScrollMoveAt = event.eventTime
                val horizontal = twoFingerMode == TWO_FINGER_SCROLL_HORIZONTAL
                scrollRemainder = emitNaturalScroll(horizontal, if (horizontal) dxDp else dyDp, scrollRemainder)
            }
        }
    }

    /**
     * One Ctrl + wheel notch per [PINCH_STEP_RATIO] change in finger spacing. Only whole
     * 120-unit notches are sent: many apps (Chromium among them) treat every Ctrl+wheel
     * message as a full zoom step whatever its delta, so fractional deltas would zoom
     * wildly. Ctrl is tapped around each notch rather than held for the gesture, so no
     * lost lift or dropped connection can leave it stuck down on the PC.
     */
    private fun pinchZoom(span: Float) {
        val modifier = zoomModifier ?: return
        if (pinchStepSpan <= 0f) return
        var steps = 0
        while (span >= pinchStepSpan * PINCH_STEP_RATIO && steps < PINCH_MAX_STEPS_PER_EVENT) {
            pinchStepSpan *= PINCH_STEP_RATIO
            steps++
        }
        while (span <= pinchStepSpan / PINCH_STEP_RATIO && steps > -PINCH_MAX_STEPS_PER_EVENT) {
            pinchStepSpan /= PINCH_STEP_RATIO
            steps--
        }
        if (steps == 0) return
        // Spreading zooms in, which is Ctrl + wheel away from the user (positive).
        modifier(true)
        send(MousePacket.MODE_RELATIVE, 0, 0, 0, steps * WHEEL_NOTCH, via = sendZoomMotion ?: sendMotion)
        modifier(false)
    }

    private fun span(event: MotionEvent): Float =
        hypot((event.getX(0) - event.getX(1)).toDouble(), (event.getY(0) - event.getY(1)).toDouble())
            .toFloat()

    /**
     * Phone-style scrolling: the content follows the fingers. Windows' +wheel scrolls up and
     * +hwheel scrolls right, so a downward drag is +vwheel and a leftward drag +hwheel.
     * Returns the sub-unit remainder to carry into the next call.
     */
    private fun emitNaturalScroll(horizontal: Boolean, fingerDp: Float, remainder: Float): Float {
        val total = remainder + (if (horizontal) -fingerDp else fingerDp) * SCROLL_UNITS_PER_DP
        val wheel = total.toInt()
        if (wheel != 0) {
            if (horizontal) {
                send(MousePacket.MODE_RELATIVE, 0, 0, wheel, 0)
            } else {
                send(MousePacket.MODE_RELATIVE, 0, 0, 0, wheel)
            }
        }
        return total - wheel
    }

    private fun trackVelocity(event: MotionEvent) {
        val tracker = velocityTracker ?: VelocityTracker.obtain().also { velocityTracker = it }
        if (event.actionMasked == MotionEvent.ACTION_DOWN) tracker.clear()
        tracker.addMovement(event)
    }

    private fun maybeStartFling(event: MotionEvent) {
        val tracker = velocityTracker ?: return
        if (twoFingerMode != TWO_FINGER_SCROLL_VERTICAL && twoFingerMode != TWO_FINGER_SCROLL_HORIZONTAL) return
        // Fingers that paused before lifting meant "stop here". The tracker only sees move
        // samples, so it would otherwise still report the speed from before the pause.
        if (event.eventTime - lastScrollMoveAt > FLING_MAX_IDLE_MS) return
        tracker.computeCurrentVelocity(1)
        val horizontal = twoFingerMode == TWO_FINGER_SCROLL_HORIZONTAL
        var sum = 0f
        for (i in 0 until event.pointerCount) {
            val id = event.getPointerId(i)
            sum += if (horizontal) tracker.getXVelocity(id) else tracker.getYVelocity(id)
        }
        val velocityDp = sum / event.pointerCount / displayDensity
        if (abs(velocityDp) < FLING_MIN_VELOCITY_DP_PER_MS) return
        flingHorizontal = horizontal
        flingVelocityDp = velocityDp.coerceIn(-FLING_MAX_VELOCITY_DP_PER_MS, FLING_MAX_VELOCITY_DP_PER_MS)
        flingRemainder = scrollRemainder
        flingLastFrameNanos = System.nanoTime()
        if (!flingActive) {
            flingActive = true
            choreographer.postFrameCallback(flingFrame)
        }
    }

    /** Returns whether a glide was actually running. */
    private fun stopFling(): Boolean {
        if (!flingActive) return false
        flingActive = false
        choreographer.removeFrameCallback(flingFrame)
        return true
    }

    private fun send(
        mode: Int,
        x: Int,
        y: Int,
        horizontalWheel: Int,
        verticalWheel: Int,
        force: Boolean = false,
        via: (ByteArray) -> Unit = sendMotion
    ) {
        val now = SystemClock.elapsedRealtimeNanos()
        if (!force && (x != 0 || y != 0) &&
            now - lastMotionSentAtNanos < MIN_SEND_INTERVAL_NANOS
        ) return
        lastMotionSentAtNanos = now
        MousePacket.write(
            packet,
            motionSequence++,
            mode,
            x,
            y,
            horizontalWheel,
            verticalWheel
        )
        via(packet)
    }

    private fun sendButton(button: String, down: Boolean) {
        ControlClient.sendMouseButton(buttonSequence++, button, down)
    }

    private fun sendClick(button: String) {
        ControlClient.sendMouseClick(buttonSequence, button)
        buttonSequence += 2
    }

    private fun isDoubleTapStart(now: Long, x: Float, y: Float): Boolean {
        if (pendingTapAt == 0L || now - pendingTapAt > DOUBLE_TAP_TIMEOUT_MS) return false
        return hypot((x - pendingTapX).toDouble(), (y - pendingTapY).toDouble()) <= doubleTapSlopPx
    }

    private fun rememberTap(now: Long, x: Float, y: Float) {
        pendingTapAt = now
        pendingTapX = x
        pendingTapY = y
    }

    private fun clearPendingTap() {
        pendingTapAt = 0L
        pendingTapX = 0f
        pendingTapY = 0f
    }

    private fun resetGestureOnly() {
        moved = false
        maxPointers = 1
        scrollRemainder = 0f
        pendingDx = 0f
        pendingDy = 0f
        twoFingerTravel = 0f
        doubleTapInProgress = false
        twoFingerMode = TWO_FINGER_UNDECIDED
        pinchStartSpan = 0f
        pinchStepSpan = 0f
        scrollTravelXDp = 0f
        scrollTravelYDp = 0f
        tapSuppressed = false
    }

    private fun cancelCleanScreenGesture() {
        target.removeCallbacks(cleanScreenReveal)
        threeFingerActive = false
        threeFingerMoved = false
        threeFingerRevealFired = false
    }

    private fun averageY(event: MotionEvent): Float {
        var total = 0f
        for (i in 0 until event.pointerCount) total += event.getY(i)
        return total / event.pointerCount
    }

    private fun averageX(event: MotionEvent): Float {
        var total = 0f
        for (i in 0 until event.pointerCount) total += event.getX(i)
        return total / event.pointerCount
    }

    companion object {
        private const val PRECISE_MOTION_THRESHOLD_DP = 1.0f
        private const val FAST_MOTION_THRESHOLD_DP = 4.0f
        private const val PRECISE_TOUCHPAD_GAIN = 1.5f
        private const val NORMAL_TOUCHPAD_GAIN = 2.5f
        private const val FAST_TOUCHPAD_GAIN = 3.5f
        private const val SCROLL_UNITS_PER_DP = 6.0f
        private val DOUBLE_TAP_TIMEOUT_MS = ViewConfiguration.getDoubleTapTimeout().toLong()
        private val MAX_TAP_DURATION_MS = ViewConfiguration.getLongPressTimeout().toLong()
        private const val CLEAN_SCREEN_REVEAL_HOLD_MS = 600L
        private const val CLEAN_SCREEN_SLOP_MULTIPLIER = 2f
        private const val MIN_SEND_INTERVAL_NANOS = 1_000_000_000L / 120L
        private const val INVALID_POINTER = -1
        private const val TWO_FINGER_UNDECIDED = 0
        private const val TWO_FINGER_SCROLL_VERTICAL = 1
        private const val TWO_FINGER_SCROLL_HORIZONTAL = 2
        private const val TWO_FINGER_PINCH = 3
        /** One Windows wheel detent (WHEEL_DELTA). */
        private const val WHEEL_NOTCH = 120
        /** Finger spacing must grow/shrink by this factor per zoom step: a typical pinch
         * (about 2.5x) gives four steps, so browser zoom moves roughly 100% -> 175%. */
        private const val PINCH_STEP_RATIO = 1.25f
        private const val PINCH_MAX_STEPS_PER_EVENT = 4
        /** Glide tuning, in dp per millisecond of finger speed. The start threshold sits well
         * above a deliberate slow scroll so only a real flick keeps going. */
        private const val FLING_MIN_VELOCITY_DP_PER_MS = 0.3f
        private const val FLING_MAX_VELOCITY_DP_PER_MS = 6f
        private const val FLING_STOP_VELOCITY_DP_PER_MS = 0.02f
        /** Speed falls to 1/e every this many ms; a flick glides about velocity x this far. */
        private const val FLING_TIME_CONSTANT_MS = 325f
        private const val FLING_MAX_IDLE_MS = 50L
        private const val FLING_MAX_FRAME_MS = 50f
    }
}
