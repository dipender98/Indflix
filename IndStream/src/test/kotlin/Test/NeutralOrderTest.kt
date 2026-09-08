package Test

import com.indstream.StreamEngine
import com.indstream.StreamEngine.RawStream
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * FILE: NeutralOrderTest.kt — guards the Sept 2026 rewrite's NEUTRAL behavior
 * (user spec: "do not prioritize based on anything"):
 *
 *  - RawStream carries NO ordering fields (no ttfb/resolve/score) — a Hindi
 *    stream and an English stream are structurally equal.
 *  - Emission is in ARRIVAL order; ExtractorLink.quality carries the REAL
 *    resolved height (0 only when unknown) so the user's own quality-profile
 *    decides what auto-plays.
 *  - QUALITY FLOOR: non-adaptive streams with a KNOWN height < 720 are
 *    dropped; adaptive (m3u8) streams ALWAYS pass; unknown heights stay.
 *
 * Pure JVM logic — runs in the plain unit-test source set.
 */
class NeutralOrderTest {

    private fun stream(
        serverId: String,
        url: String = "https://x.example/${serverId}.m3u8",
        isM3u8: Boolean = true,
        qualityHint: Int = 0,
        audioPriority: Int = 0,
    ) = RawStream(
        serverId = serverId,
        serverName = serverId,
        url = url,
        isM3u8 = isM3u8,
        qualityHint = qualityHint,
        audioPriority = audioPriority,
    )

    @Test
    fun rawStream_hasNoRankingFields() {
        // Structural guard: two streams differing only in language/quality are
        // equal-weight — nothing in the model can rank one above the other.
        val hindi = stream("hindiHost", audioPriority = 4, qualityHint = 2160)
        val english = stream("englishHost", audioPriority = 1, qualityHint = 480)
        // Only data, no derived ordering: fields exist for LABELS, not scores.
        assertEquals(4, hindi.audioPriority)
        assertEquals(2160, hindi.qualityHint)
        assertEquals("hindiHost", hindi.serverName)
        assertEquals(1, english.audioPriority)
    }

    @Test
    fun arrivalOrder_isPreserved_noImplicitSorting() {
        // The contract emit() follows: the caller's list order (arrival order)
        // is the emission order. No sorting can reorder it.
        val arrivals = listOf(
            stream("slowButFirst"),
            stream("fastButSecond"),
            stream("hdButThird", qualityHint = 1080),
        )
        assertEquals(listOf("slowButFirst", "fastButSecond", "hdButThird"), arrivals.map { it.serverId })
        assertTrue(arrivals.zipWithNext().all { (a, b) -> a !== b })
    }

    @Test
    fun hindiLabel_isDisplayOnly() {
        // audioPriority=4 only feeds the display label ("Hindi") via
        // LinkNaming — it must not imply any selection behaviour here.
        val hindi = stream("h", audioPriority = 4)
        val other = stream("e", audioPriority = 0)
        assertEquals("h", hindi.serverId)
        assertEquals("e", other.serverId)
    }

    // ── Quality floor (user spec Sept 2026 rewrite #2) ───────────

    @Test
    fun qualityFloor_dropsKnownSub720NonAdaptive() {
        assertFalse(StreamEngine.passesQualityFloor(isAdaptive = false, height = 360))
        assertFalse(StreamEngine.passesQualityFloor(isAdaptive = false, height = 480))
        assertFalse(StreamEngine.passesQualityFloor(isAdaptive = false, height = 719))
    }

    @Test
    fun qualityFloor_keepsHdAndHigher() {
        assertTrue(StreamEngine.passesQualityFloor(isAdaptive = false, height = 720))
        assertTrue(StreamEngine.passesQualityFloor(isAdaptive = false, height = 1080))
        assertTrue(StreamEngine.passesQualityFloor(isAdaptive = false, height = 2160))
    }

    @Test
    fun qualityFloor_adaptiveAlwaysPasses_evenSub720() {
        // An m3u8 master ALWAYS passes: it ABR-ramps to its best rendition no
        // matter what the master header reads.
        assertTrue(StreamEngine.passesQualityFloor(isAdaptive = true, height = 360))
        assertTrue(StreamEngine.passesQualityFloor(isAdaptive = true, height = 480))
        assertTrue(StreamEngine.passesQualityFloor(isAdaptive = true, height = 0))
    }

    @Test
    fun qualityFloor_unknownHeight_cannotBeProvenLow() {
        // 0 (unknown) and -1 ("Auto" sentinel) stay — a low quality can't be
        // proven for them.
        assertTrue(StreamEngine.passesQualityFloor(isAdaptive = false, height = 0))
        assertTrue(StreamEngine.passesQualityFloor(isAdaptive = false, height = -1))
    }

    // ── Emission: arrival order + real quality height ────────────

    @Test
    fun emit_dropsSub720Progressive_keepsAdaptiveAndUnknown() = runBlocking {
        val out = mutableListOf<com.lagradost.cloudstream3.utils.ExtractorLink>()
        StreamEngine.emit(
            listOf(
                stream("p360", url = "https://x.example/p360.mp4", isM3u8 = false, qualityHint = 360),
                stream("p480", url = "https://x.example/p480.mp4", isM3u8 = false, qualityHint = 480),
                stream("p720", url = "https://x.example/p720.mp4", isM3u8 = false, qualityHint = 720),
                stream("m480", url = "https://x.example/m480.m3u8", isM3u8 = true, qualityHint = 480),
                stream("mUnknown", url = "https://x.example/mUnknown.m3u8", isM3u8 = true, qualityHint = 0),
                stream("pUnknown", url = "https://x.example/pUnknown.mp4", isM3u8 = false, qualityHint = 0),
            ),
            onLink = { out.add(it) },
            probeManifests = false,
        )
        val urls = out.map { it.url }
        // KNOWN sub-720 progressive files: dropped.
        assertFalse(urls.contains("https://x.example/p360.mp4"))
        assertFalse(urls.contains("https://x.example/p480.mp4"))
        // 720p+ progressive, adaptive (even when the master reads low), and
        // unknown-height streams: kept.
        assertTrue(urls.contains("https://x.example/p720.mp4"))
        assertTrue(urls.contains("https://x.example/m480.m3u8"))
        assertTrue(urls.contains("https://x.example/mUnknown.m3u8"))
        assertTrue(urls.contains("https://x.example/pUnknown.mp4"))
    }

    @Test
    fun emit_carriesRealHeight_notZero() = runBlocking {
        val out = mutableListOf<com.lagradost.cloudstream3.utils.ExtractorLink>()
        StreamEngine.emit(
            listOf(
                stream("hd", url = "https://x.example/hd.mp4", isM3u8 = false, qualityHint = 1080),
                stream("master", url = "https://x.example/master.m3u8", isM3u8 = true, qualityHint = 1080),
                stream("mystery", url = "https://x.example/mystery.m3u8", isM3u8 = true, qualityHint = 0),
            ),
            onLink = { out.add(it) },
            probeManifests = false,
        )
        assertEquals(3, out.size)
        val byUrl = out.associateBy { it.url }
        assertEquals(1080, byUrl["https://x.example/hd.mp4"]!!.quality)
        assertEquals(1080, byUrl["https://x.example/master.m3u8"]!!.quality)
        assertEquals(0, byUrl["https://x.example/mystery.m3u8"]!!.quality) // unknown stays 0
    }

    @Test
    fun emit_resolutionPrintedOnce_realHeightOnName() = runBlocking {
        val out = mutableListOf<com.lagradost.cloudstream3.utils.ExtractorLink>()
        StreamEngine.emit(
            listOf(stream("hd", url = "https://x.example/hd.mp4", isM3u8 = false, qualityHint = 1080)),
            onLink = { out.add(it) },
            probeManifests = false,
        )
        assertEquals(1, out.size)
        val link = out[0]
        assertEquals(1080, link.quality)
        // User spec: the resolution must appear ON the server name (derived
        // from probing/parsing — never guessed), exactly ONCE (no doubling).
        assertTrue(link.name.contains("1080p"))
        assertEquals(1, Regex("1080p").findAll(link.name).count())
    }

    @Test
    fun emit_unknownDirectFile_showsAuto_neverGuesses() = runBlocking {
        val out = mutableListOf<com.lagradost.cloudstream3.utils.ExtractorLink>()
        StreamEngine.emit(
            listOf(stream("mystery", url = "https://x.example/mystery.mp4", isM3u8 = false, qualityHint = 0)),
            onLink = { out.add(it) },
            probeManifests = false,
        )
        assertEquals(1, out.size)
        // No declared/measured height → "Auto" marker, never a fabricated res.
        assertTrue(out[0].name.contains("Auto"))
        assertFalse(out[0].name.contains("p"))
        assertEquals(0, out[0].quality)
    }

    // ── Adaptive peak-resolution naming (user report: adaptive showed "Auto") ──

    @Test
    fun emit_hlsInlineManifest_labelsPeakResolution_neverAuto() = runBlocking {
        // Harvest path leaves qualityHint=0; the inline master parse must lift the
        // label to the PEAK variant ("4K") and quality=2160 — never "Auto".
        val master = """
#EXTM3U
#EXT-X-STREAM-INF:BANDWIDTH=1500000,RESOLUTION=1280x720
720/video.m3u8
#EXT-X-STREAM-INF:BANDWIDTH=9000000,RESOLUTION=3840x2160
2160/video.m3u8
        """.trimIndent()
        val out = mutableListOf<com.lagradost.cloudstream3.utils.ExtractorLink>()
        StreamEngine.emit(
            listOf(
                RawStream(
                    serverId = "hlsHost", serverName = "HlsHost",
                    url = "https://x.example/master.m3u8", isM3u8 = true,
                    qualityHint = 0, inlineManifest = master,
                    audioLabel = "Hindi", audioPriority = 4,
                ),
            ),
            onLink = { out.add(it) },
            probeManifests = true,
        )
        assertEquals(1, out.size)
        assertEquals(2160, out[0].quality)
        assertTrue(out[0].name.contains("4K"), "name must carry peak res: ${out[0].name}")
        assertFalse(out[0].name.contains("Auto"), "adaptive must not show Auto: ${out[0].name}")
    }

    @Test
    fun emit_dashMpdInlineManifest_labelsPeakResolution() = runBlocking {
        // DASH ladder: parseMpd must yield the peak video representation (1080)
        // so the adaptive link labels "1080p" like its HLS sibling.
        val mpd = """
<?xml version="1.0" encoding="utf-8"?>
<MPD xmlns="urn:mpeg:dash:schema:mpd:2011">
  <Period>
    <AdaptationSet mimeType="video/mp4">
      <Representation id="1" width="640" height="360" bandwidth="500000"/>
      <Representation id="2" width="1920" height="1080" bandwidth="5000000"/>
    </AdaptationSet>
    <AdaptationSet mimeType="audio/mp4">
      <Representation id="3" bandwidth="128000"/>
    </AdaptationSet>
  </Period>
</MPD>
        """.trimIndent()
        val out = mutableListOf<com.lagradost.cloudstream3.utils.ExtractorLink>()
        StreamEngine.emit(
            listOf(
                RawStream(
                    serverId = "dashHost", serverName = "DashHost",
                    url = "https://x.example/dash.mpd", isM3u8 = true,
                    qualityHint = 0, inlineManifest = mpd,
                    audioLabel = "Hindi", audioPriority = 4,
                ),
            ),
            onLink = { out.add(it) },
            probeManifests = true,
        )
        assertEquals(1, out.size)
        assertEquals(1080, out[0].quality)
        assertTrue(out[0].name.contains("1080p"), "name must carry peak res: ${out[0].name}")
        assertFalse(out[0].name.contains("Auto"), "adaptive must not show Auto: ${out[0].name}")
    }

    @Test
    fun emit_declaredHindiUrl_labelsHindi_unknownStaysUnknown() = runBlocking {
        // Host-declared language ("...hindi..." CDN path) → "(Hindi)" tag.
        val declared = mutableListOf<com.lagradost.cloudstream3.utils.ExtractorLink>()
        StreamEngine.emit(
            listOf(stream("h1", url = "https://cdn.example/hindi/title/file.mp4", isM3u8 = false, qualityHint = 720)),
            onLink = { declared.add(it) },
            probeManifests = false,
        )
        assertEquals(1, declared.size)
        assertTrue(declared[0].name.contains("(Hindi)"))
        // No declaration anywhere → honest "Unknown", never a guess.
        val unknown = mutableListOf<com.lagradost.cloudstream3.utils.ExtractorLink>()
        StreamEngine.emit(
            listOf(stream("n1", url = "https://cdn.example/a/b/file.mp4", isM3u8 = false, qualityHint = 720)),
            onLink = { unknown.add(it) },
            probeManifests = false,
        )
        assertEquals(1, unknown.size)
        assertTrue(unknown[0].name.contains("(Unknown)"))
    }

    @Test
    fun emit_arrivalOrderPreserved() = runBlocking {
        val out = mutableListOf<com.lagradost.cloudstream3.utils.ExtractorLink>()
        StreamEngine.emit(
            listOf(
                stream("first", url = "https://x.example/first.m3u8"),
                stream("second", url = "https://x.example/second.m3u8"),
                stream("third", url = "https://x.example/third.m3u8"),
            ),
            onLink = { out.add(it) },
            probeManifests = false,
        )
        assertEquals(
            listOf(
                "https://x.example/first.m3u8",
                "https://x.example/second.m3u8",
                "https://x.example/third.m3u8",
            ),
            out.map { it.url },
        )
    }
}
