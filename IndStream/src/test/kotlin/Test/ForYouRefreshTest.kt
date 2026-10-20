package Test

import com.indstream.foryou.ForYouRefresh
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ForYouRefreshTest {

    private val hour = 60L * 60 * 1000

    private fun stale(
        mode: ForYouRefresh.Mode,
        now: Long,
        builtAt: Long,
        builtSession: Long = 1,
        currentSession: Long = 1,
        builds: Int = 0,
    ) = ForYouRefresh.isStale(mode, now, builtAt, builtSession, currentSession, builds)

    @Test
    fun unknown_mode_falls_back_to_the_first_one() {
        assertEquals(ForYouRefresh.Mode.EVERY_OPEN, ForYouRefresh.Mode.from(null))
        assertEquals(ForYouRefresh.Mode.EVERY_OPEN, ForYouRefresh.Mode.from("nope"))
        assertEquals(ForYouRefresh.Mode.TWICE_DAILY, ForYouRefresh.Mode.from("twice"))
        assertEquals(ForYouRefresh.Mode.EVERY_SIX_HOURS, ForYouRefresh.Mode.from("six"))
    }

    @Test
    fun a_missing_row_is_always_stale() {
        ForYouRefresh.Mode.entries.forEach { mode ->
            assertTrue(stale(mode, now = 1000, builtAt = 0))
        }
    }

    @Test
    fun every_open_rebuilds_only_after_a_new_session() {
        assertFalse(stale(ForYouRefresh.Mode.EVERY_OPEN, now = 1000, builtAt = 1000))
        assertTrue(
            stale(
                ForYouRefresh.Mode.EVERY_OPEN, now = 1000, builtAt = 1000,
                builtSession = 1, currentSession = 2,
            ),
        )
    }

    @Test
    fun twice_daily_allows_two_builds_per_day() {
        val now = 100L * hour
        assertTrue(stale(ForYouRefresh.Mode.TWICE_DAILY, now = now, builtAt = 0))
        assertFalse(stale(ForYouRefresh.Mode.TWICE_DAILY, now = now, builtAt = now - hour, builds = 1))
        assertTrue(
            stale(ForYouRefresh.Mode.TWICE_DAILY, now = now, builtAt = now - 13 * hour, builds = 1),
        )
        assertFalse(
            stale(ForYouRefresh.Mode.TWICE_DAILY, now = now, builtAt = now - 13 * hour, builds = 2),
        )
    }

    @Test
    fun six_hour_mode_expires_the_row() {
        val now = 100L * hour
        assertFalse(stale(ForYouRefresh.Mode.EVERY_SIX_HOURS, now = now, builtAt = now - 5 * hour))
        assertTrue(stale(ForYouRefresh.Mode.EVERY_SIX_HOURS, now = now, builtAt = now - 7 * hour))
    }
}
