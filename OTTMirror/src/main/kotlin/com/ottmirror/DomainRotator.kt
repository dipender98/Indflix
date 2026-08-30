package com.ottmirror

import java.util.concurrent.ConcurrentHashMap

enum class Role(val label: String) {
    MOBILE("mobile"),
    NEWTV("newtv"),
}

object DomainRotator {
    private const val DEAD_HOST_RECOVERY_MS = 5 * 60 * 1000L

    private class RoleState(hosts: List<String>) {
        val hosts = hosts
        var currentIndex = 0
        val dead = ConcurrentHashMap<String, Long>()

        fun current(): String? {
            recoverStale()
            for (offset in 0 until hosts.size) {
                val idx = (currentIndex + offset) % hosts.size
                val h = hosts[idx]
                if (!dead.containsKey(h)) return h
            }
            return null
        }

        fun advance() { currentIndex = (currentIndex + 1) % hosts.size }

        fun markDead(host: String) {
            dead[host] = System.currentTimeMillis()
        }

        fun liveCount(): Int {
            recoverStale()
            return hosts.count { !dead.containsKey(it) }
        }

        private fun recoverStale() {
            val now = System.currentTimeMillis()
            val iter = dead.entries.iterator()
            while (iter.hasNext()) {
                val (host, diedAt) = iter.next()
                if (now - diedAt > DEAD_HOST_RECOVERY_MS) {
                    iter.remove()
                    HostThrottler.recordSuccess(host)
                }
            }
        }
    }

    private val states = ConcurrentHashMap<Role, RoleState>()

    init { reset() }

    fun reset() {
        states[Role.MOBILE] = RoleState(VERIFY_HOSTS)
        states[Role.NEWTV] = RoleState(NEWTV_DOMAINS.map { decodeBase64(it).trimEnd('/') })
    }

    fun current(role: Role): String? = states[role]?.current()

    fun markFailed(role: Role, host: String) {
        val s = states[role] ?: return
        if (s.current() == host) s.advance()
        s.markDead(host)
        if (role == Role.NEWTV) NewTvBase.clear()
    }

    fun liveCount(role: Role): Int = states[role]?.liveCount() ?: 0
}