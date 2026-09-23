package com.localstream.client.net

/**
 * Watches decode-to-surface latency around a refresh-recovery gap skip (§3.1).
 *
 * Some TV decoders (seen on a TCL/MediaTek set) conceal a skipped reference correctly but then
 * leave their low-latency output mode and keep 2-3 decoded pictures queued for the rest of the
 * session: p95 went from 12-19 ms to 47-56 ms at 60 fps and to 100-370 ms on a mostly static
 * desktop, and never came back because refresh recovery never sends another IDR. Only a real
 * IDR resets that decoder state.
 *
 * So: remember the latency baseline before a skip; once the intra-refresh wave has had time to
 * finish, compare the median of the next samples against it. If the decoder is clearly slower
 * (both +[MIN_EXTRA_MS] and [SLOWDOWN_FACTOR]x), ask for exactly one IDR. Healthy decoders never
 * trigger it, so ordinary losses keep the burst-free refresh path.
 *
 * Thread-safe: samples arrive on the codec thread, skips on the UDP receive thread.
 */
internal class ConcealmentLatencyGuard(private val nowMs: () -> Long) {
    private val recent = IntArray(BASELINE_SAMPLES)
    private var recentCount = 0
    private var recentNext = 0

    private var baselineMs = -1
    private var watchStartedAtMs = -1L
    private val post = IntArray(CHECK_SAMPLES)
    private var postCount = 0
    private var lastTriggerAtMs = Long.MIN_VALUE / 2

    /** A gap was skipped without an IDR. Starts a check unless one is already running (its
     * baseline predates this skip) or an IDR was requested recently. */
    @Synchronized
    fun onGapSkipped() {
        val now = nowMs()
        if (watchStartedAtMs >= 0L) {
            watchStartedAtMs = now // extend the settle window; keep the pre-skip baseline
            postCount = 0
            return
        }
        if (now - lastTriggerAtMs < TRIGGER_COOLDOWN_MS || recentCount < MIN_BASELINE_SAMPLES) return
        baselineMs = median(recent, recentCount)
        watchStartedAtMs = now
        postCount = 0
    }

    /** Records one decode-to-surface sample. Returns true when an IDR should be requested. */
    @Synchronized
    fun onFrameDecoded(latencyMs: Int): Boolean {
        if (latencyMs < 0) return false
        if (watchStartedAtMs < 0L) {
            recent[recentNext] = latencyMs
            recentNext = (recentNext + 1) % recent.size
            if (recentCount < recent.size) recentCount++
            return false
        }
        val now = nowMs()
        if (now - watchStartedAtMs < SETTLE_MS) return false
        post[postCount++] = latencyMs
        if (postCount < post.size) return false

        val afterMs = median(post, postCount)
        val slower = afterMs >= baselineMs + MIN_EXTRA_MS && afterMs >= baselineMs * SLOWDOWN_FACTOR
        watchStartedAtMs = -1L
        postCount = 0
        if (!slower) return false
        lastTriggerAtMs = now
        // The IDR resets the decoder; rebuild the baseline from fresh samples afterwards.
        recentCount = 0
        recentNext = 0
        return true
    }

    private fun median(values: IntArray, count: Int): Int {
        val sorted = values.copyOf(count)
        sorted.sort()
        return sorted[count / 2]
    }

    companion object {
        const val BASELINE_SAMPLES = 60
        const val MIN_BASELINE_SAMPLES = 10
        const val CHECK_SAMPLES = 30
        /** Longer than one refresh wave (~1/6 s) plus decoder settling. */
        const val SETTLE_MS = 600L
        const val MIN_EXTRA_MS = 20
        const val SLOWDOWN_FACTOR = 2
        const val TRIGGER_COOLDOWN_MS = 10_000L
    }
}
