package Test

import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.multimovies.MultiSourcePuller
import com.multimovies.MultimoviesProvider
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * FILE: QualityFloorMmTest.kt — guards the Sept 2026 rewrite #2 NEUTRAL
 * behavior for Multimovies:
 *
 *  - The plugin NEVER ranks: AutoPlayPicker (1080p-first pools, TTFB scoring,
 *    Hindi tie-breaks) and MultiSourcePuller.sortLinks are GONE. Links push
 *    in ARRIVAL order; the user's CloudStream quality/source profile decides
 *    what auto-plays.
 *  - QUALITY FLOOR: non-adaptive (VIDEO) links with a KNOWN quality < 720 are
 *    dropped at emission; adaptive (M3U8) links and unknown heights (0)
 *    always pass. Pure function — the floor helper is unit-tested here.
 *
 * (A live "list keeps growing" integration test would need the CloudStream
 * `app` network client, which is not mockable in the plain JVM source set —
 * the fill-window logic is covered by the [MultimoviesProvider.LIVE_FILL_MS]
 * constant + pullSource's immediate push path.)
 */
class QualityFloorMmTest {

    private fun link(
        url: String,
        type: ExtractorLinkType,
        quality: Int,
    ) = ExtractorLink(
        source = "S",
        name = "S",
        url = url,
        referer = "",
        quality = quality,
        headers = emptyMap(),
        extractorData = null,
        type = type,
        audioTracks = emptyList(),
    )

    private fun floor(l: ExtractorLink): Boolean =
        MultimoviesProvider.passesQualityFloor(
            l.type == ExtractorLinkType.M3U8,
            l.quality,
        )

    @Test
    fun sub720Progressive_dropped() {
        assertFalse(floor(link("https://x/low360.mp4", ExtractorLinkType.VIDEO, 360)))
        assertFalse(floor(link("https://x/low480.mp4", ExtractorLinkType.VIDEO, 480)))
        assertFalse(floor(link("https://x/low719.mp4", ExtractorLinkType.VIDEO, 719)))
    }

    @Test
    fun hd720Plus_kept() {
        assertTrue(floor(link("https://x/hd720.mp4", ExtractorLinkType.VIDEO, 720)))
        assertTrue(floor(link("https://x/hd1080.mp4", ExtractorLinkType.VIDEO, 1080)))
        assertTrue(floor(link("https://x/uhd.mp4", ExtractorLinkType.VIDEO, 2160)))
    }

    @Test
    fun adaptive_alwaysPasses_evenKnownSub720() {
        // m3u8 masters ramp to their best rendition — never dropped, even
        // when their measured height reads low.
        assertTrue(floor(link("https://x/master360.m3u8", ExtractorLinkType.M3U8, 360)))
        assertTrue(floor(link("https://x/master480.m3u8", ExtractorLinkType.M3U8, 480)))
    }

    @Test
    fun unknownHeight_cannotBeProvenLow() {
        assertTrue(floor(link("https://x/unknown.mp4", ExtractorLinkType.VIDEO, 0)))
        assertTrue(floor(link("https://x/unknown.m3u8", ExtractorLinkType.M3U8, 0)))
    }

    @Test
    fun neutral_noRankingHelpersRemain() {
        // Structural guard for the rewrite: the ranking model is gone from the
        // pull engine (AutoPlayPicker/sortLinks/sourceKey deleted outright).
        val mmMethods = MultiSourcePuller::class.java.declaredMethods.map { it.name }.toSet()
        assertFalse("sortLinks" in mmMethods, "sortLinks (plugin-side ranking) must not exist")
        assertFalse("sourceKey" in mmMethods, "sourceKey (speed/priority key) must not exist")
        val providerMethods = MultimoviesProvider::class.java.declaredMethods.map { it.name }.toSet()
        assertFalse("pickAutoPlay" in providerMethods, "pickAutoPlay (auto-play ranking) must not exist")
    }
}
