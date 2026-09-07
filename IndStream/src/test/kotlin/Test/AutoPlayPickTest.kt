package Test

import com.indstream.StreamEngine.RawStream
import com.indstream.StreamEngine.pickAutoPlay
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * FILE: AutoPlayPickTest.kt — guards the live-probe auto-play selection
 * (user spec Sept 2026):
 *
 *  - Eligibility order (1080p-first): known ≥1080 (direct file or probed master
 *    bestHeight) beats ≥720, which beats adaptive-unknown (ABR climbing is
 *    unreliable — user report), which beats known sub-720; sub-720-only still
 *    returns the best available (never stalls).
 *  - Within a pool, lower live TTFB wins (0.40 weight).
 *  - Audio preference (0.05 weight) can never flip a big TTFB gap.
 *  - Empty input → null.
 *
 * pickAutoPlay is pure JVM logic (no android/network deps), so it runs in the
 * plain unit-test source set.
 */
class AutoPlayPickTest {

    private fun stream(
        serverId: String,
        url: String = "https://x.example/${serverId}.m3u8",
        isM3u8: Boolean = true,
        qualityHint: Int = 0,
        ttfbMs: Long? = null,
        resolveMs: Long? = null,
        kbps: Long? = null,
        audioPriority: Int = 0,
    ) = RawStream(
        serverId = serverId,
        serverName = serverId,
        url = url,
        isM3u8 = isM3u8,
        qualityHint = qualityHint,
        ttfbMs = ttfbMs,
        resolveMs = resolveMs,
        measuredKbps = kbps,
        audioPriority = audioPriority,
    )

    // ── eligibility order ────────────────────────────────────────

    @Test
    fun h720_beatsAdaptiveUnknown_onlyWhenNo1080() {
        // 720p direct file (in the ≥720 pool) beats an adaptive-unknown master
        // with a much better TTFB — but ONLY because no 1080p candidate exists.
        val direct = stream("720host", isM3u8 = false, qualityHint = 720, ttfbMs = 400)
        val adaptive = stream("fasthost", isM3u8 = true, qualityHint = 0, ttfbMs = 50)
        assertEquals("720host", pickAutoPlay(listOf(adaptive, direct))?.serverId)
    }

    @Test
    fun direct1080_beatsFaster720() {
        // 1080p-first: a 1080p file with a worse probe wins over a faster-TTFB
        // 720p file (the 1080 pool is evaluated before the 720 pool).
        val fast720 = stream("720host", isM3u8 = false, qualityHint = 720, ttfbMs = 50)
        val slow1080 = stream("1080host", isM3u8 = false, qualityHint = 1080, ttfbMs = 400)
        assertEquals("1080host", pickAutoPlay(listOf(fast720, slow1080))?.serverId)
    }

    @Test
    fun direct1080_beatsAdaptiveUnknown() {
        // 1080p direct file beats an adaptive-unknown master with a far better TTFB.
        val adaptive = stream("fasthost", isM3u8 = true, qualityHint = 0, ttfbMs = 30)
        val direct1080 = stream("1080host", isM3u8 = false, qualityHint = 1080, ttfbMs = 300)
        assertEquals("1080host", pickAutoPlay(listOf(adaptive, direct1080))?.serverId)
    }

    @Test
    fun probed1080Master_in1080Pool() {
        // Adaptive master whose probe stamped bestHeight=1080 (qualityHint filled
        // by probeCandidates) lands in the 1080 pool and beats a 720p file and
        // an adaptive-unknown master.
        val master1080 = stream("master", isM3u8 = true, qualityHint = 1080, ttfbMs = 200)
        val direct720 = stream("720host", isM3u8 = false, qualityHint = 720, ttfbMs = 50)
        val adaptive = stream("fasthost", isM3u8 = true, qualityHint = 0, ttfbMs = 30)
        assertEquals("master", pickAutoPlay(listOf(adaptive, direct720, master1080))?.serverId)
    }

    @Test
    fun adaptiveUnknown_fallbackWhenNoHd() {
        // No known ≥720 candidate → adaptive-unknown is eligible (pool 3).
        val adaptive = stream("adaptive", isM3u8 = true, qualityHint = 0, ttfbMs = 120)
        val sub720 = stream("low", isM3u8 = false, qualityHint = 480, ttfbMs = 80)
        val winner = pickAutoPlay(listOf(sub720, adaptive))
        assertEquals("adaptive", winner?.serverId)
    }

    @Test
    fun sub720Only_bestAvailableNeverNull() {
        // Sub-720 only → best live score wins, never stalls (pool 4).
        val a = stream("a", isM3u8 = false, qualityHint = 360, ttfbMs = 500)
        val b = stream("b", isM3u8 = false, qualityHint = 480, ttfbMs = 100)
        val winner = pickAutoPlay(listOf(a, b))
        assertEquals("b", winner?.serverId)
    }

    // ── live scoring inside a pool ───────────────────────────────

    @Test
    fun lowerTtfb_winsWithinSamePool() {
        val fast = stream("fast", qualityHint = 1080, ttfbMs = 40)
        val slow = stream("slow", qualityHint = 1080, ttfbMs = 700)
        val winner = pickAutoPlay(listOf(slow, fast))
        assertEquals("fast", winner?.serverId)
    }

    @Test
    fun resolveTime_tiebreakWhenTtfbMissing() {
        // Neither probed (ttfb null) → history prior is identical (no
        // HealthMonitor data in tests), so resolveMs decides.
        val late = stream("late", qualityHint = 1080, resolveMs = 3000)
        val early = stream("early", qualityHint = 1080, resolveMs = 300)
        val winner = pickAutoPlay(listOf(late, early))
        assertEquals("early", winner?.serverId)
    }

    @Test
    fun audioBonus_neverFlipsBigTtfbGap() {
        // Hindi source 400ms slower: the 0.05 audio weight must not beat the
        // 0.40 TTFB weight difference (user spec: soft preference only).
        val hindiSlow = stream("hindi", qualityHint = 1080, ttfbMs = 500, audioPriority = 4)
        val englishFast = stream("english", qualityHint = 1080, ttfbMs = 60)
        val winner = pickAutoPlay(listOf(hindiSlow, englishFast))
        assertEquals("english", winner?.serverId)
    }

    // ── edge cases ───────────────────────────────────────────────

    @Test
    fun emptyList_returnsNull() {
        assertNull(pickAutoPlay(emptyList()))
    }

    @Test
    fun blankUrls_excluded() {
        val subtitleOnly = RawStream(
            serverId = "subonly", serverName = "subonly", url = "", isM3u8 = false,
        )
        val real = stream("real", qualityHint = 1080, ttfbMs = 300)
        val winner = pickAutoPlay(listOf(subtitleOnly, real))
        assertEquals("real", winner?.serverId)
    }

    @Test
    fun allBlankUrls_returnsNull() {
        val subtitleOnly = RawStream(
            serverId = "subonly", serverName = "subonly", url = "", isM3u8 = false,
        )
        assertNull(pickAutoPlay(listOf(subtitleOnly)))
    }

    @Test
    fun singleCandidate_wins() {
        val only = stream("only", qualityHint = 720, ttfbMs = 250)
        val winner = pickAutoPlay(listOf(only))
        assertNotNull(winner)
        assertEquals("only", winner.serverId)
    }

    @Test
    fun hdPoolSize_reported() {
        // 1080p-first: a mixed farm with a 720p and a 1080p candidate must pick
        // the 1080p one, never the fast adaptive-unknown nor the 720p entry.
        val candidates = listOf(
            stream("adaptive", isM3u8 = true, qualityHint = 0, ttfbMs = 30),
            stream("hd1", isM3u8 = false, qualityHint = 720, ttfbMs = 300),
            stream("hd2", isM3u8 = false, qualityHint = 1080, ttfbMs = 310),
        )
        val winner = pickAutoPlay(candidates)
        assertEquals("hd2", winner?.serverId,
            "winner must come from the 1080p pool, got ${winner?.serverId}")
    }
}
