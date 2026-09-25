package com.localstream.client.ui

import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.localstream.client.R
import com.localstream.client.databinding.ActivityControllerBinding
import com.localstream.client.input.RemoteMouseController
import com.localstream.client.net.ControlClient
import com.localstream.client.proto.ServerMessage
import kotlinx.coroutines.launch

/**
 * Controller-role screen: this device drives the PC's mouse and
 * keyboard while another client owns the screen output.
 *
 * - Touchpad → control-channel `MOUSE_MOTION`: this connection never learned a
 *   media endpoint, so the 28-byte DSMI datagram travels as its JSON twin. The pad behaves
 *   like a laptop trackpad (tap-to-click, natural two-finger scroll with glide, pinch
 *   zoom as Ctrl + wheel) with
 *   physical-style left/right click zones along its bottom edge.
 * - Text field → phone IME as composer: every edit is diffed into `KEYBOARD_TEXT`
 *   (Unicode, carries kana/kanji/emoji) or Backspace `KEYBOARD_KEY` transitions at the
 *   PC's focused caret. Tapping the field raises the phone keyboard, which is the
 *   whole point of the field existing.
 */
class ControllerActivity : AppCompatActivity() {

    private lateinit var binding: ActivityControllerBinding
    private lateinit var remoteMouse: RemoteMouseController

    /** True between INPUT_STARTED and the input path dying; the host has no input managers
     * before that, and packets sent earlier would be silently dropped (leaving the
     * composer's record of what was typed diverged from the PC). */
    private var inputEnabled = false

    /** One ordered sequence space for ALL keyboard traffic: HID transitions and
     * Unicode bursts share it because the host tracks a single monotonic last-sequence. */
    private var keySequence = 0L

    /** Last composer text actually forwarded, so each edit becomes a minimal diff. */
    private var lastSentText = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityControllerBinding.inflate(layoutInflater)
        setContentView(binding.root)

        remoteMouse = RemoteMouseController(
            binding.touchpad,
            sendMotion = ControlClient::sendMousePacket,
            // Clean screen is a stream-viewer concern; the controller screen is always
            // controls-visible, so three-finger gestures belong to the touchpad itself.
            shouldHandleCleanScreenGesture = { false },
            onCleanScreenReveal = { },
            trackpad = true,
            // Pinch → Ctrl + wheel on the PC, sharing the one keyboard sequence space.
            zoomModifier = { down -> ControlClient.sendKeyboardKey(keySequence++, HID_LEFT_CTRL, down) }
        )
        binding.touchpad.setOnTouchListener(remoteMouse)
        remoteMouse.attachClickZone(binding.zoneLeftClick, "left")
        remoteMouse.attachClickZone(binding.zoneRightClick, "right")
        // Touch goes through the zone listeners; these only serve accessibility activation.
        binding.zoneLeftClick.setOnClickListener { remoteMouse.clickLeft() }
        binding.zoneRightClick.setOnClickListener { remoteMouse.clickRight() }
        // Rounded pad corners must also clip the zones' pressed highlight.
        binding.trackpadFrame.clipToOutline = true

        binding.etRemoteText.addTextChangedListener(composerWatcher)
        // IME "Enter" (singleLine action key): send a real HID Enter — a '\n' character
        // has no key position on the host and most apps ignore it as text input.
        binding.etRemoteText.setOnEditorActionListener { _, _, _ ->
            sendUsageKey(HID_ENTER)
            true
        }

        binding.btnEnter.setOnClickListener { sendUsageKey(HID_ENTER) }
        binding.btnBackspace.setOnClickListener { sendUsageKey(HID_BACKSPACE) }

        observeClient()
    }

    override fun onPause() {
        super.onPause()
        // The client MUST reset the keyboard when its capture is released. Keys are
        // never held across messages here (each send is a down/up pair), so this is a
        // belt-and-braces release for anything the host still tracks.
        ControlClient.sendKeyboardReset()
    }

    private fun observeClient() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch { ControlClient.state.collect { state -> onClientState(state) } }
                launch { ControlClient.events.collect { msg -> onClientEvent(msg) } }
            }
        }
    }

    private fun onClientState(state: ControlClient.State) {
        when (state) {
            ControlClient.State.READY, ControlClient.State.STREAMING -> {
                // Resend on every (re)entry: a reconnect created a brand-new server session
                // with no input managers, and INPUT_START is idempotent on a live one.
                ControlClient.startMouseKeyboardInput()
                binding.tvControllerStatus.text = getString(R.string.controller_status_ready)
            }
            ControlClient.State.CONNECTING, ControlClient.State.RECONNECTING -> {
                inputEnabled = false
                remoteMouse.setEnabled(false)
                binding.tvControllerStatus.text = getString(R.string.controller_status_connecting)
            }
            ControlClient.State.PAIRING, ControlClient.State.DISCONNECTED -> {
                // MainActivity owns both the PIN dialog and the retry list; hand back.
                finish()
            }
        }
    }

    private fun onClientEvent(msg: ServerMessage) {
        when (msg) {
            ServerMessage.InputStarted -> {
                inputEnabled = true
                remoteMouse.setEnabled(true)
                binding.tvControllerStatus.text = getString(R.string.controller_status_live)
            }
            is ServerMessage.InputUnavailable -> {
                inputEnabled = false
                remoteMouse.setEnabled(false)
                binding.tvControllerStatus.text =
                    getString(R.string.controller_status_input_off, msg.message)
            }
            else -> {}
        }
    }

    private val composerWatcher = object : TextWatcher {
        override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
        override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
        override fun afterTextChanged(s: Editable?) = forwardEdit(s?.toString().orEmpty())
    }

    /**
     * Turns a composer-buffer change into the smallest keystroke stream: an append becomes
     * one KEYBOARD_TEXT, a deletion becomes Backspace taps, and a mid-string edit (IME
     * conversion, suggestion pick) backspaces to the shared prefix first. Both ends
     * converge on the final text even though intermediate states flash on the PC — the
     * same rhythm as typing kana and converting on the host itself.
     *
     * While input isn't live yet the record is left at "" so the first live edit sends the
     * whole buffer rather than silently dropping what was typed before INPUT_STARTED.
     */
    private fun forwardEdit(text: String) {
        val old = lastSentText
        if (text == old || !inputEnabled) return
        val common = sharedPrefixLength(old, text)
        repeat(old.length - common) { sendUsageKey(HID_BACKSPACE) }
        val added = text.substring(common)
        if (added.isNotEmpty()) ControlClient.sendKeyboardText(keySequence++, added)
        lastSentText = text
    }

    private fun sharedPrefixLength(a: String, b: String): Int {
        val limit = minOf(a.length, b.length)
        var i = 0
        while (i < limit && a[i] == b[i]) i++
        return i
    }

    /** One HID usage as a down/up pair on the shared keyboard sequence. */
    private fun sendUsageKey(usage: Int) {
        ControlClient.sendKeyboardKey(keySequence++, usage, true)
        ControlClient.sendKeyboardKey(keySequence++, usage, false)
    }

    private companion object {
        /** USB HID Keyboard-page usages: Enter/Return, Backspace, Left Control. */
        const val HID_ENTER = 0x28
        const val HID_BACKSPACE = 0x2A
        const val HID_LEFT_CTRL = 0xE0
    }
}
