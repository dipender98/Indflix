package com.ottmirror

import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.ConcurrentHashMap
import kotlin.random.Random

object HostThrottler {
    const val BASE_INTERVAL_MS = 1000L
    const val MAX_BACKOFF_MS = 60_000L

    fun nextInterval(current: Long): Long =
        (if (current <= 0) 2000L else current * 2).coerceAtMost(MAX_BACKOFF_MS)

    private data class State(var intervalMs: Long = BASE_INTERVAL_MS, var lastRequestMs: Long = 0L)
    private val states = ConcurrentHashMap<String, State>()
    private val mutex = Mutex()

    suspend fun throttle(host: String): Long {
        mutex.withLock {
            val s = states.getOrPut(host) { State() }
            val now = System.currentTimeMillis()
            val wait = s.intervalMs - (now - s.lastRequestMs)
            if (wait > 0) {
                val jittered = (wait * (1.0 + (Random.nextDouble() - 0.5) * 0.4)).toLong().coerceAtLeast(0L)
                delay(jittered)
                s.lastRequestMs = System.currentTimeMillis()
                return jittered
            }
            s.lastRequestMs = now
            return 0L
        }
    }

    fun recordBackoff(host: String) {
        states.compute(host) { _, s -> (s ?: State()).also { it.intervalMs = nextInterval(s?.intervalMs ?: 0L) } }
    }

    fun recordSuccess(host: String) {
        states.compute(host) { _, s -> (s ?: State()).also { it.intervalMs = BASE_INTERVAL_MS } }
    }

    fun currentInterval(host: String): Long = states[host]?.intervalMs ?: BASE_INTERVAL_MS
    fun reset() { states.clear() }
}