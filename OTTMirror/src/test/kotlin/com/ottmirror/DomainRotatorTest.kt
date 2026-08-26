package com.ottmirror

import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class DomainRotatorTest {

    @BeforeEach
    fun setUp() {
        DomainRotator.reset()
        NewTvBase.clear()
    }

    @Test
    fun mobile_currentStartsAtFirstLiveHost() {
        val current = DomainRotator.current(Role.MOBILE)
        assertNotNull(current)
        assertEquals(VERIFY_HOSTS.first(), current)
    }

    @Test
    fun markFailed_advancesAndPinsDead() {
        val first = assertNotNull(DomainRotator.current(Role.MOBILE))
        DomainRotator.markFailed(Role.MOBILE, first)
        val second = assertNotNull(DomainRotator.current(Role.MOBILE))
        assert(second != first)
        DomainRotator.markFailed(Role.MOBILE, second)
        assertEquals(VERIFY_HOSTS[2], DomainRotator.current(Role.MOBILE))
        assertEquals(VERIFY_HOSTS.size - 2, DomainRotator.liveCount(Role.MOBILE))
    }

    @Test
    fun killAll_mobileReturnsNull() {
        val hosts = VERIFY_HOSTS.toList()
        repeat(hosts.size) {
            DomainRotator.current(Role.MOBILE)?.let { DomainRotator.markFailed(Role.MOBILE, it) }
        }
        assertNull(DomainRotator.current(Role.MOBILE))
        assertEquals(0, DomainRotator.liveCount(Role.MOBILE))
    }

    @Test
    fun newtv_currentDecodesToHttpHost() {
        val current = assertNotNull(DomainRotator.current(Role.NEWTV))
        assert(current.startsWith("https://"))
    }

    @Test
    fun newtv_markFailed_clearsResolvedBase() {
        NewTvBase.set("https://tv.imgcdn.kim")
        assertEquals("https://tv.imgcdn.kim", NewTvBase.value)
        val host = assertNotNull(DomainRotator.current(Role.NEWTV))
        DomainRotator.markFailed(Role.NEWTV, host)
        assertEquals("", NewTvBase.value)
    }

    @Test
    fun markFailed_unknownHost_doesNotBreakRotation() {
        DomainRotator.markFailed(Role.MOBILE, "https://does-not-exist.invalid")
        assertEquals(VERIFY_HOSTS.first(), DomainRotator.current(Role.MOBILE))
    }
}