package Test

import com.indstream.TmdbService
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Guards the pure key-fallback decision logic in TMDB metadata engine. */
class TmdbKeyFallbackGuards {

    @Test
    fun auth_and_rate_limit_http_trigger_fallback() {
        assertTrue(TmdbService.shouldTryNextKey(401, null))
        assertTrue(TmdbService.shouldTryNextKey(403, null))
        assertTrue(TmdbService.shouldTryNextKey(429, null)) // rate limit
        assertTrue(TmdbService.shouldTryNextKey(500, null))
    }

    @Test
    fun resource_level_errors_do_not_trigger_auth_fallback() {
        assertFalse(TmdbService.shouldTryNextKey(400, null)) // bad request (e.g. duplicate param)
        assertFalse(TmdbService.shouldTryNextKey(404, null)) // id not found
    }

    @Test
    fun trip_codes_identify_key_failures_within_200_bodies() {
        val body7 = """{"success":false,"status_code":7}"""
        val body10 = """{"success":false,"status_code":10}"""
        val body30 = """{"success":false,"status_code":30,"status_message":"Rate limit exceeded."}"""
        val validTitle = """{"id":278,"title":"The Shawshank Redemption"}"""
        assertEquals(7, TmdbService.tmdbKeyTripCode(body7))
        assertEquals(10, TmdbService.tmdbKeyTripCode(body10))
        assertEquals(30, TmdbService.tmdbKeyTripCode(body30))
        assertNull(TmdbService.tmdbKeyTripCode(validTitle))
    }

    @Test
    fun rate_limit_retries_same_key_once() {
        assertEquals(1500L, TmdbService.rateLimitRetryDelayMs(429, 0))
        assertNull(TmdbService.rateLimitRetryDelayMs(429, 1))
        assertNull(TmdbService.rateLimitRetryDelayMs(500, 0))
        assertNull(TmdbService.rateLimitRetryDelayMs(200, 0))
        assertNull(TmdbService.rateLimitRetryDelayMs(404, 0))
    }

    @Test
    fun key_trip_200_bodies_fallback_via_shouldTryNextKey() {
        assertTrue(TmdbService.shouldTryNextKey(200, TmdbService.tmdbKeyTripCode("""{"success":false,"status_code":7}""")))
        assertTrue(TmdbService.shouldTryNextKey(200, TmdbService.tmdbKeyTripCode("""{"success":false,"status_code":30}""")))
        assertFalse(TmdbService.shouldTryNextKey(200, TmdbService.tmdbKeyTripCode("")))
    }
}
