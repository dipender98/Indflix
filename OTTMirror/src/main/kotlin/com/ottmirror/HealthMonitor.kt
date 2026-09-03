package com.ottmirror

import kotlin.math.min

/**
 * Per-server health tracking with EMA success rate, latency, and circuit breaker.
 * Thread-safe (backed by concurrent maps).
 */
object HealthMonitor {

    private data class ServerHealth(
        val successCount: Int = 0,
        val failCount: Int = 0,
        val totalLatencyMs: Long = 0L,
        val measuredThroughput: Long = 0L, // KB/s
        val throughputCount: Int = 0,
        val trippedUntil: Long = 0L, // System.currentTimeMillis() when tripped
    )

    private val healthMap = java.util.concurrent.ConcurrentHashMap<String, ServerHealth>()
    private val lock = Any()

    /** Max consecutive failures before tripping a server. */
    private const val MAX_CONSECUTIVE_FAILURES = 3
    /** Trip duration in ms (15 min). */
    private const val TRIP_DURATION_MS = 15 * 60 * 1000L
    /** EMA decay factor (0.0–1.0, higher = faster decay). */
    private const val ALPHA = 0.3

    /** Record a successful resolution from a server. */
    fun recordSuccess(serverId: String, latencyMs: Long, throughputKbps: Long? = null) {
        synchronized(lock) {
            val h = healthMap[serverId] ?: ServerHealth()
            healthMap[serverId] = h.copy(
                successCount = h.successCount + 1,
                totalLatencyMs = h.totalLatencyMs + latencyMs,
                measuredThroughput = if (throughputKbps != null) {
                    if (h.throughputCount == 0) throughputKbps
                    else ((1.0 - ALPHA) * h.measuredThroughput + ALPHA * throughputKbps).toLong()
                } else h.measuredThroughput,
                throughputCount = if (throughputKbps != null) h.throughputCount + 1 else h.throughputCount,
                // Reset fail count on success
                failCount = 0,
            )
        }
    }

    /** Record a failure from a server. */
    fun recordFailure(serverId: String) {
        synchronized(lock) {
            val h = healthMap[serverId] ?: ServerHealth()
            val newFailCount = h.failCount + 1
            healthMap[serverId] = h.copy(
                failCount = newFailCount,
                successCount = h.successCount,
                // Trip if consecutive failures exceed threshold
                trippedUntil = if (newFailCount >= MAX_CONSECUTIVE_FAILURES)
                    System.currentTimeMillis() + TRIP_DURATION_MS
                else h.trippedUntil,
            )
        }
    }

    /** Whether a server is currently healthy (not tripped and not failed too often). */
    fun isHealthy(serverId: String): Boolean {
        val h = healthMap[serverId] ?: return true // unknown = healthy (first probe)
        if (h.failCount >= MAX_CONSECUTIVE_FAILURES) {
            if (System.currentTimeMillis() < h.trippedUntil) return false
            // Trip expired — allow re-probe, reset fail count
            synchronized(lock) {
                healthMap[serverId] = h.copy(failCount = 0)
            }
            return true
        }
        return true
    }

    /** Get the average latency for a server, or null if no data. */
    fun averageLatency(serverId: String): Long? {
        val h = healthMap[serverId] ?: return null
        val total = h.successCount + h.failCount
        if (total == 0) return null
        return h.totalLatencyMs / total
    }

    /** Get the average measured throughput (KB/s) for a server, or null. */
    fun averageThroughput(serverId: String): Long? {
        val h = healthMap[serverId] ?: return null
        if (h.throughputCount == 0) return null
        return h.measuredThroughput
    }

    /** Get total attempts (success + failure) for a server. */
    fun totalAttempts(serverId: String): Int {
        val h = healthMap[serverId] ?: return 0
        return h.successCount + h.failCount
    }

    /** Reset health for a server (e.g., when re-probing). */
    fun reset(serverId: String) {
        synchronized(lock) { healthMap.remove(serverId) }
    }

    /** Reset all health. */
    fun resetAll() {
        synchronized(lock) { healthMap.clear() }
    }

    /** EMA-based speed score: higher is better. Factors in throughput and latency. */
    fun speedScore(serverId: String): Double {
        val h = healthMap[serverId] ?: return 0.0
        val throughput = if (h.throughputCount > 0) h.measuredThroughput.toDouble() else 0.0
        val avgLat = if (h.successCount > 0) h.totalLatencyMs.toDouble() / h.successCount else 2000.0
        // Score = normalized throughput / latency. Higher throughput = better, lower latency = better.
        // Base: 1.0 for unknown; known servers get score relative to 5000 KB/s ideal.
        val throughputScore = if (throughput > 0) min(1.0, throughput / 5000.0) else 0.5
        val latencyScore = if (avgLat > 0) min(1.0, 1000.0 / avgLat) else 0.5
        return throughputScore * 0.7 + latencyScore * 0.3
    }
}