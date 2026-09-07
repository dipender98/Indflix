package Test

import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.multimovies.AutoPlayPicker
import com.multimovies.AutoPlayPicker.Probe
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * FILE: AutoPlayPickMmTest.kt — guards Multimovies' live-probe auto-play
 * selection (user spec Sept 2026, ported from IndStream):
 *
 *  - 1080p-first strict pools: effective height ≥1080 (direct file or probed
 *    master bestHeight) beats ≥720, which beats adaptive-unknown, which beats
 *    sub-720. A 1080p candidate ALWAYS wins when present — never a faster-TTFB
 *    720p or adaptive-unknown link.
 *  - Within a pool, lower live TTFB wins (0.40 weight); Hindi bonus (0.05)
 *    can never flip a big TTFB gap.
 *  - Empty input → null; blank urls excluded.
 *
 * AutoPlayPicker.pickAutoPlay is pure JVM logic (uses only SourceSpeedTracker,
 * also plain Kotlin), so it runs in the plain unit-test source set.
 */
class AutoPlayPickMmTest {

    private fun link(
        source: String,
        url: String = "https://x.example/${source}.m3u8",
        isM3u8: Boolean = true,
        quality: Int = 0,
    ) = ExtractorLink(
        source = source,
        name = source,
        url = url,
        referer = "",
        quality = quality,
        headers = emptyMap(),
        extractorData = null,
        type = if (isM3u8) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO,
        audioTracks = emptyList(),
    )

    // ── 1080p-first pools ────────────────────────────────────────

    @Test
    fun direct1080_beatsFaster720() {
        // A 1080p file with a worse probe wins over a faster-TTFB 720p file:
        // the 1080 pool is evaluated before the 720 pool (quality pools decide,
        // not the 0.10 quality weight).
        val fast720 = link("720host", isM3u8 = false, quality = 720)
        val slow1080 = link("1080host", isM3u8 = false, quality = 1080)
        val probes = mapOf(
            fast720.url to Probe(50, 3000),
            slow1080.url to Probe(400, 1500),
        )
        assertEquals("1080host", AutoPlayPicker.pickAutoPlay(listOf(fast720, slow1080), probes)?.source)
    }

    @Test
    fun direct1080_beatsAdaptiveUnknown() {
        // 1080p direct file beats an adaptive-unknown master with a far better TTFB.
        val adaptive = link("fasthost", isM3u8 = true, quality = 0)
        val direct1080 = link("1080host", isM3u8 = false, quality = 1080)
        val probes = mapOf(
            adaptive.url to Probe(30, 4000),
            direct1080.url to Probe(300, 1500),
        )
        assertEquals("1080host", AutoPlayPicker.pickAutoPlay(listOf(adaptive, direct1080), probes)?.source)
    }

    @Test
    fun probed1080Master_in1080Pool() {
        // Adaptive master whose probe stamped probedHeight=1080 (quality tag is
        // 0 — the height lives only in the Probe) lands in the 1080 pool and
        // beats a 720p file and an adaptive-unknown master.
        val master1080 = link("master", isM3u8 = true, quality = 0)
        val direct720 = link("720host", isM3u8 = false, quality = 720)
        val adaptive = link("fasthost", isM3u8 = true, quality = 0)
        val probes = mapOf(
            master1080.url to Probe(200, null, probedHeight = 1080),
            direct720.url to Probe(50, 2000),
            adaptive.url to Probe(30, 4000),
        )
        assertEquals("master", AutoPlayPicker.pickAutoPlay(listOf(adaptive, direct720, master1080), probes)?.source)
    }

    @Test
    fun h720_beatsAdaptiveUnknown_onlyWhenNo1080() {
        // 720p direct file beats an adaptive-unknown master with a much better
        // TTFB — but ONLY because no 1080p candidate is present.
        val direct = link("720host", isM3u8 = false, quality = 720)
        val adaptive = link("fasthost", isM3u8 = true, quality = 0)
        val probes = mapOf(
            direct.url to Probe(400, 1500),
            adaptive.url to Probe(50, 4000),
        )
        assertEquals("720host", AutoPlayPicker.pickAutoPlay(listOf(adaptive, direct), probes)?.source)
    }

    @Test
    fun adaptiveUnknown_fallbackWhenNoKnownHd() {
        // No known ≥720 candidate → adaptive-unknown (player starts ~720 and
        // ABR-climbs; user-accepted) is eligible.
        val adaptive = link("adaptive", isM3u8 = true, quality = 0)
        val sub720 = link("low", isM3u8 = false, quality = 480)
        val probes = mapOf(
            adaptive.url to Probe(120, 2000),
            sub720.url to Probe(80, 3000),
        )
        assertEquals("adaptive", AutoPlayPicker.pickAutoPlay(listOf(sub720, adaptive), probes)?.source)
    }

    @Test
    fun sub720Only_bestAvailableNeverNull() {
        // Sub-720 only → best live score wins, never stalls.
        val a = link("a", isM3u8 = false, quality = 360)
        val b = link("b", isM3u8 = false, quality = 480)
        val probes = mapOf(
            a.url to Probe(500, 800),
            b.url to Probe(100, 2000),
        )
        assertEquals("b", AutoPlayPicker.pickAutoPlay(listOf(a, b), probes)?.source)
    }

    // ── live scoring inside a pool ───────────────────────────────

    @Test
    fun lowerTtfb_winsWithinSamePool() {
        val fast = link("fast", isM3u8 = false, quality = 1080)
        val slow = link("slow", isM3u8 = false, quality = 1080)
        val probes = mapOf(
            fast.url to Probe(40, 4000),
            slow.url to Probe(700, 1000),
        )
        assertEquals("fast", AutoPlayPicker.pickAutoPlay(listOf(slow, fast), probes)?.source)
    }

    @Test
    fun resolveMs_tiebreakWhenUnprobed() {
        // Neither probed (no Probe entries) → identical history priors (no
        // SourceSpeedTracker data in tests), so resolveMs decides.
        val late = link("late", isM3u8 = false, quality = 1080)
        val early = link("early", isM3u8 = false, quality = 1080)
        val resolve = mapOf(late.url to 3000L, early.url to 300L)
        assertEquals("early", AutoPlayPicker.pickAutoPlay(listOf(late, early), emptyMap(), resolve)?.source)
    }

    @Test
    fun hindiBonus_neverFlipsBigTtfbGap() {
        // Hindi-labelled source 400ms slower: the 0.05 Hindi weight must not
        // beat the 0.40 TTFB weight difference (soft preference only).
        val hindiSlow = link("Nxsha Hindi", isM3u8 = false, quality = 1080)
        val englishFast = link("Cineverse", isM3u8 = false, quality = 1080)
        val probes = mapOf(
            hindiSlow.url to Probe(500, 1500),
            englishFast.url to Probe(60, 4000),
        )
        assertEquals("Cineverse", AutoPlayPicker.pickAutoPlay(listOf(hindiSlow, englishFast), probes)?.source)
    }

    // ── edge cases ───────────────────────────────────────────────

    @Test
    fun emptyList_returnsNull() {
        assertNull(AutoPlayPicker.pickAutoPlay(emptyList()))
    }

    @Test
    fun unprobedLinks_stillEligible() {
        // Probe timed out for everything: ranking falls back to resolveMs /
        // history priors; a winner must still be picked (never null when
        // candidates exist).
        val a = link("a", isM3u8 = false, quality = 1080)
        val b = link("b", isM3u8 = false, quality = 1080)
        val winner = AutoPlayPicker.pickAutoPlay(listOf(a, b), emptyMap())
        assertNotNull(winner)
        assertTrue(winner.source == "a" || winner.source == "b")
    }

    @Test
    fun singleCandidate_wins() {
        val only = link("only", isM3u8 = false, quality = 720)
        val winner = AutoPlayPicker.pickAutoPlay(listOf(only), emptyMap())
        assertNotNull(winner)
        assertEquals("only", winner.source)
    }

    // ── master height regex ──────────────────────────────────────

    @Test
    fun bestMasterHeight_parsesTallestVariant() {
        val master = """
            #EXTM3U
            #EXT-X-STREAM-INF:BANDWIDTH=800000,RESOLUTION=640x360
            v360.m3u8
            #EXT-X-STREAM-INF:BANDWIDTH=2800000,RESOLUTION=1920x1080
            v1080.m3u8
            #EXT-X-STREAM-INF:BANDWIDTH=1400000,RESOLUTION=1280x720
            v720.m3u8
        """.trimIndent()
        assertEquals(1080, AutoPlayPicker.bestMasterHeight(master))
    }

    @Test
    fun bestMasterHeight_nonMaster_returnsZero() {
        assertEquals(0, AutoPlayPicker.bestMasterHeight("#EXTM3U\n#EXTINF:10,seg1.ts"))
        assertEquals(0, AutoPlayPicker.bestMasterHeight(null))
    }
}
