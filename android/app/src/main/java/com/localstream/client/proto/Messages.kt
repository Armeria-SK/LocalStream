package com.localstream.client.proto

import org.json.JSONArray
import org.json.JSONObject

/**
 * Control-channel JSON message models.
 *
 * We use org.json (bundled in the Android platform) rather than kotlinx.serialization to
 * avoid pulling in a KSP/serialization plugin for a handful of small, flat messages.
 */
const val PROTOCOL_VERSION = 1

// ---------------------------------------------------------------------------------------
// Outgoing (client -> server)
// ---------------------------------------------------------------------------------------

object ClientMessages {

    /**
     * [role] declares the session role: `"viewer"` (default — screen output) or
     * `"controller"` (touchpad & keyboard for a second device). Pre-v0.8 servers ignore
     * the unknown field, which keeps a controller attempt on an old server behaving
     * exactly like a viewer attempt (a plain BUSY).
     */
    fun hello(
        clientId: String,
        clientName: String,
        token: String,
        role: String = "viewer"
    ): String =
        JSONObject().apply {
            put("type", "HELLO")
            put("ver", PROTOCOL_VERSION)
            put("clientId", clientId)
            put("clientName", clientName)
            put("token", token)
            put("role", role)
        }.toString()

    fun pairRequest(): String =
        JSONObject().apply {
            put("type", "PAIR_REQUEST")
        }.toString()

    fun pairCode(pin: String): String =
        JSONObject().apply {
            put("type", "PAIR_CODE")
            put("pin", pin)
        }.toString()

    /**
     * [codecs] lists the codecs this device decodes in hardware, in preference order
     * ("hevc", "h264"); [recovery] the loss-recovery modes it implements ("refresh" = keeps
     * decoding across a lost frame and sends REQUEST_REFRESH). Both are optional on the wire:
     * a server that ignores them streams H.264 with IDR recovery, as before.
     */
    fun startStream(
        maxBitrateKbps: Int,
        fps: Int,
        codecs: List<String> = listOf("h264"),
        recovery: List<String> = emptyList()
    ): String =
        JSONObject().apply {
            put("type", "START_STREAM")
            put("maxBitrateKbps", maxBitrateKbps)
            put("fps", fps)
            put("codecs", JSONArray(codecs))
            put("recovery", JSONArray(recovery))
        }.toString()

    fun mediaReady(port: Int): String =
        JSONObject().apply {
            put("type", "MEDIA_READY")
            put("port", port.coerceIn(1, 65535))
        }.toString()

    fun audioReady(port: Int): String =
        JSONObject().apply {
            put("type", "AUDIO_READY")
            put("port", port.coerceIn(1, 65535))
        }.toString()

    fun stopStream(): String =
        JSONObject().apply {
            put("type", "STOP_STREAM")
        }.toString()

    fun startAudio(): String =
        JSONObject().apply {
            put("type", "AUDIO_START")
        }.toString()

    fun startMouseInput(): String =
        JSONObject().apply {
            put("type", "INPUT_START")
            put("mouse", true)
        }.toString()

    /**
     * Controller-role input negotiation: unlike [startMouseInput] this enables the
     * keyboard too, because a controller session never streams and therefore has no
     * STREAM_STARTED to piggyback the usual input start on.
     */
    fun startMouseKeyboardInput(): String =
        JSONObject().apply {
            put("type", "INPUT_START")
            put("mouse", true)
            put("keyboard", true)
        }.toString()

    /**
     * Control-channel mouse motion: the JSON twin of the 28-byte DSMI datagram,
     * for connections that never learned a media endpoint to send the UDP original on.
     */
    fun mouseMotion(
        sequence: Long,
        absolute: Boolean,
        x: Int,
        y: Int,
        hwheel: Int,
        vwheel: Int
    ): String =
        JSONObject().apply {
            put("type", "MOUSE_MOTION")
            put("sequence", sequence and 0xFFFFFFFFL)
            put("absolute", absolute)
            put("x", x)
            put("y", y)
            put("hwheel", hwheel)
            put("vwheel", vwheel)
        }.toString()

    /** One ordered HID key transition: `usage` is a USB HID Keyboard-page usage ID. */
    fun keyboardKey(sequence: Long, usage: Int, down: Boolean): String =
        JSONObject().apply {
            put("type", "KEYBOARD_KEY")
            put("sequence", sequence and 0xFFFFFFFFL)
            put("usage", usage)
            put("down", down)
        }.toString()

    /**
     * Unicode text burst: one ordered message covering the whole string, for IME
     * output with no HID key position (kana, kanji, emoji). Shares [sequence] space with
     * [keyboardKey] — the host keeps a single monotonic keyboard sequence.
     */
    fun keyboardText(sequence: Long, text: String): String =
        JSONObject().apply {
            put("type", "KEYBOARD_TEXT")
            put("sequence", sequence and 0xFFFFFFFFL)
            put("text", text)
        }.toString()

    fun keyboardReset(): String =
        JSONObject().apply { put("type", "KEYBOARD_RESET") }.toString()

    fun stopInput(): String =
        JSONObject().apply { put("type", "INPUT_STOP") }.toString()

    fun resetMouse(): String =
        JSONObject().apply { put("type", "MOUSE_RESET") }.toString()

    fun mouseButton(sequence: Long, button: String, down: Boolean): String =
        JSONObject().apply {
            put("type", "MOUSE_BUTTON")
            put("sequence", sequence and 0xFFFFFFFFL)
            put("button", button)
            put("down", down)
        }.toString()

    fun requestIdr(): String =
        JSONObject().apply {
            put("type", "REQUEST_IDR")
        }.toString()

    /** Loss repair on a refresh-recovery stream: one intra-refresh wave, no IDR. */
    fun requestRefresh(): String =
        JSONObject().apply {
            put("type", "REQUEST_REFRESH")
        }.toString()

    fun stats(
        framesOk: Int,
        framesAssembled: Int,
        framesDropped: Int,
        assemblyFramesDropped: Int,
        decoderFramesDropped: Int,
        fecPacketsRecovered: Int,
        videoPacketsReceived: Int,
        fecPacketsReceived: Int,
        bytes: Long,
        intervalMs: Long,
        serverPipelineP95Ms: Int,
        captureToReceiveP95Ms: Int,
        decodeToSurfaceP95Ms: Int
    ): String =
        JSONObject().apply {
            put("type", "STATS")
            put("framesOk", framesOk)
            put("framesAssembled", framesAssembled)
            put("framesDropped", framesDropped)
            put("assemblyFramesDropped", assemblyFramesDropped)
            put("decoderFramesDropped", decoderFramesDropped)
            put("fecPacketsRecovered", fecPacketsRecovered)
            put("videoPacketsReceived", videoPacketsReceived)
            put("fecPacketsReceived", fecPacketsReceived)
            put("bytes", bytes)
            put("intervalMs", intervalMs)
            put("serverPipelineP95Ms", serverPipelineP95Ms)
            put("captureToReceiveP95Ms", captureToReceiveP95Ms)
            put("decodeToSurfaceP95Ms", decodeToSurfaceP95Ms)
        }.toString()

    fun ping(t0Us: Long): String =
        JSONObject().apply {
            put("type", "PING")
            put("t0Us", t0Us)
        }.toString()
}

// ---------------------------------------------------------------------------------------
// Incoming (server -> client)
// ---------------------------------------------------------------------------------------

sealed class ServerMessage {
    data class HelloOk(val serverName: String, val width: Int, val height: Int) : ServerMessage()
    data object PairRequired : ServerMessage()
    data class Error(val code: String, val message: String) : ServerMessage()
    data class PairOk(val token: String) : ServerMessage()
    data class PairFail(val attemptsLeft: Int) : ServerMessage()
    data class StreamStarted(
        val mediaPort: Int,
        val width: Int,
        val height: Int,
        val fps: Int,
        val codec: String,
        /** "refresh" when the server repairs loss with intra refresh, else "idr". */
        val recovery: String,
        val encoderBackend: String,
        val clockBaseUs: Long
    ) : ServerMessage()
    data object StreamStopped : ServerMessage()
    data class AudioStarted(
        val audioPort: Int,
        val sampleRate: Int,
        val channels: Int,
        val format: String,
        val packetSamples: Int
    ) : ServerMessage()
    data class AudioUnavailable(val message: String) : ServerMessage()
    data object InputStarted : ServerMessage()
    data class InputUnavailable(val message: String) : ServerMessage()
    data class Bitrate(val kbps: Int) : ServerMessage()
    data class Pong(val t0Us: Long, val t1Us: Long, val t2Us: Long) : ServerMessage()

    /** Any type not recognized above; per spec, unknown types MUST be ignored. */
    data class Unknown(val type: String) : ServerMessage()

    companion object {
        /** Returns null only on malformed JSON / missing "type" — caller should treat that as
         * a protocol violation and close the socket. A recognized-but-unhandled type still
         * parses successfully as [Unknown]. */
        fun parse(json: String): ServerMessage? {
            val obj = try {
                JSONObject(json)
            } catch (e: Exception) {
                return null
            }
            val type = obj.optString("type", "")
            if (type.isEmpty()) return null

            return when (type) {
                "HELLO_OK" -> HelloOk(
                    serverName = obj.optString("serverName", ""),
                    width = obj.optInt("width", 0),
                    height = obj.optInt("height", 0)
                )
                "PAIR_REQUIRED" -> PairRequired
                "ERROR" -> Error(
                    code = obj.optString("code", ""),
                    message = obj.optString("message", "")
                )
                "PAIR_OK" -> PairOk(token = obj.optString("token", ""))
                "PAIR_FAIL" -> PairFail(attemptsLeft = obj.optInt("attemptsLeft", 0))
                "STREAM_STARTED" -> StreamStarted(
                    mediaPort = obj.optInt("mediaPort", 0),
                    width = obj.optInt("width", 0),
                    height = obj.optInt("height", 0),
                    fps = obj.optInt("fps", 0),
                    codec = obj.optString("codec", "h264"),
                    recovery = obj.optString("recovery", "idr"),
                    encoderBackend = obj.optString("encoderBackend", "media-foundation"),
                    clockBaseUs = obj.optLong("clockBaseUs", 0L)
                )
                "STREAM_STOPPED" -> StreamStopped
                "AUDIO_STARTED" -> AudioStarted(
                    audioPort = obj.optInt("audioPort", 0),
                    sampleRate = obj.optInt("sampleRate", 0),
                    channels = obj.optInt("channels", 0),
                    format = obj.optString("format", ""),
                    packetSamples = obj.optInt("packetSamples", 0)
                )
                "AUDIO_UNAVAILABLE" -> AudioUnavailable(
                    message = obj.optString("message", "System audio is unavailable")
                )
                "INPUT_STARTED" -> InputStarted
                "INPUT_UNAVAILABLE" -> InputUnavailable(
                    message = obj.optString("message", "Remote mouse is unavailable")
                )
                "BITRATE" -> Bitrate(kbps = obj.optInt("kbps", 0))
                "PONG" -> Pong(
                    t0Us = obj.optLong("t0Us", 0L),
                    t1Us = obj.optLong("t1Us", 0L),
                    t2Us = obj.optLong("t2Us", 0L)
                )
                else -> Unknown(type)
            }
        }
    }
}
