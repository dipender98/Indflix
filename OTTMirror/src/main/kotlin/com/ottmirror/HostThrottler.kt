package com.ottmirror

import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.math.max
import kotlin.random.Random

/**
 * Global request gate for the NetMirror backend.
 *
 * The backend rate-limits by client IP + session, NOT by domain — so the old
 * per-host throttling + host rotation strategy only made things worse (every
 * 429 burned another mirror while the underlying IP ban kept ticking). This
 * gate replaces that with:
 *
 *  1. One global minimum spacing between ANY two requests to the backend.
 *  2. A server-driven cooldown: on 429 we honor `Retry-After` (falling back
 *     to exponential backoff) and EVERY caller waits it out on the same host
 *     instead of rotating and re-firing.
 */
object HostThrottler {
    const val MIN_GAP_MS = 2500L
    // On a saturated shared IP the first retry at 5 s almost never clears, so
    // the ladder starts at a floor that has a real chance: 15 s -> 30 s ->
    // 60 s -> 90 s cap.
    private const val MIN_BACKOFF_MS = 15_000L
    private const val MAX_BACKOFF_MS = 90_000L

    private val mutex = Mutex()
    private var lastRequestMs = 0L

    @Volatile private var cooldownUntilMs = 0L
    @Volatile private var lastBackoffMs = 0L

    /**
     * Suspend until both the global spacing and any active cooldown elapse.
     * The wait is computed under the mutex but slept OUTSIDE it: holding the
     * lock during a 90 s cooldown delay serialized every caller behind one
     * giant stall (the "keeps loading" report). Each caller now delays
     * independently and re-checks before firing.
     */
    suspend fun gate() {
        while (true) {
            val wait = mutex.withLock {
                val now = System.currentTimeMillis()
                val w = max(cooldownUntilMs - now, MIN_GAP_MS - (now - lastRequestMs))
                if (w <= 0) {
                    lastRequestMs = System.currentTimeMillis()
                    0L
                } else {
                    w
                }
            }
            if (wait <= 0L) return
            delay(wait + Random.nextLong(0, 300))
        }
    }

    /** Wait out whatever cooldown is currently active (used before a retry). */
    suspend fun awaitCooldown() {
        val wait = cooldownUntilMs - System.currentTimeMillis()
        if (wait > 0) delay(wait + Random.nextLong(200, 600))
    }

    fun cooldownSeconds(): Int =
        ((cooldownUntilMs - System.currentTimeMillis()) / 1000L).toInt().coerceAtLeast(0)

    fun isCoolingDown(): Boolean = System.currentTimeMillis() < cooldownUntilMs

    /**
     * Record a 429. Honors the server's Retry-After when present, otherwise
     * doubles the previous penalty (15s -> 30s -> 60s ... capped at 90s).
     */
    fun recordLimited(retryAfterHeader: String? = null) {
        val serverMs = parseRetryAfterSeconds(retryAfterHeader) * 1000L
        val base = when {
            serverMs > 0 -> serverMs
            lastBackoffMs <= 0 -> MIN_BACKOFF_MS
            else -> (lastBackoffMs * 2).coerceAtMost(MAX_BACKOFF_MS)
        }
        lastBackoffMs = base
        cooldownUntilMs = System.currentTimeMillis() + base
    }

    /** Any 2xx clears the backoff ladder so a healthy session stays fast. */
    fun recordSuccess(host: String = "") {
        lastBackoffMs = 0L
        cooldownUntilMs = 0L
    }

    // Kept for DomainRotator's stale-host recovery hook; hosts no longer carry
    // individual throttle state since the limiter is server-wide.
    fun recordBackoff(host: String = "") { /* superseded by recordLimited */ }

    fun reset() {
        lastRequestMs = 0L
        cooldownUntilMs = 0L
        lastBackoffMs = 0L
    }

    /** Parse a Retry-After header value (delta-seconds or HTTP date). */
    fun parseRetryAfterSeconds(value: String?): Int {
        val ra = value?.trim() ?: return 0
        ra.toIntOrNull()?.let { return it.coerceIn(1, 120) }
        return try {
            val serverTime = java.text.SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss z", java.util.Locale.US)
                .parse(ra)?.time ?: return 0
            (((serverTime - System.currentTimeMillis()) / 1000).toInt()).coerceIn(1, 120)
        } catch (_: Exception) {
            0
        }
    }
}
