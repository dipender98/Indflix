package com.ottmirror

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class NetMirrorParsersEmbedTest {

    // Real embed-tmdb shape (live-probed 2026-08-31, Jackpot! movie).
    private val movieOk = """
        {"ok":true,"tmdbId":1094138,"title":"Jackpot!","year":"2024","imdb":"tt26940324",
         "type":"movie","exp":1788215061,"sig":"cfcb","mode":"proxy",
         "mp4":"https://bcdnxw.hakunaymatata.com/resource/c87.mp4?sign=5c01&t=1788185753",
         "resolution":"720",
         "streams":[
           {"url":"https://bcdnxw.hakunaymatata.com/bt/c20f.mp4?sign=3aed&t=1","resolution":360,"size":316322803},
           {"url":"https://bcdnxw.hakunaymatata.com/resource/2466.mp4?sign=bf20&t=1","resolution":480,"size":395440405},
           {"url":"https://bcdnxw.hakunaymatata.com/resource/c873.mp4?sign=5c01&t=1","resolution":720,"size":837811275}],
         "direct":false,"cdn":"bcdnxw.hakunaymatata.com","source":"primary","match":"exact",
         "captions":[
           {"lang":"hi","name":"हिन्दी","url":"/api/proxy/video?url=https%3A%2F%2Fcacdn.hakunaymatata.com%2Fmsubt%2Fhi.srt"},
           {"lang":"en","name":"English","url":"https://cacdn.hakunaymatata.com/subtitle/en.srt?Policy=x"}],
         "fallbackHls":"/api/loffe/tt26940324"}
    """.trimIndent()

    @Test
    fun parseEmbedTmdb_movie_withStreamsAndCaptions() {
        val r = NetMirrorParsers.parseEmbedTmdb(movieOk)!!
        assertFalse(r.noSource)
        assertEquals("movie", r.type)
        assertEquals(3, r.streams.size)
        assertEquals(720, r.streams.maxOf { it.resolution })
        // Relative caption absolutized to net27.cc; absolute caption untouched.
        assertEquals(2, r.captions.size)
        assertTrue(r.captions[0].url.startsWith("https://net27.cc/api/proxy/video"))
        assertTrue(r.captions[1].url.startsWith("https://cacdn.hakunaymatata.com/"))
    }

    @Test
    fun pickEmbedStream_prefersHighestUnder1080_thenLargerSize() {
        val r = NetMirrorParsers.parseEmbedTmdb(movieOk)!!
        assertEquals(720, NetMirrorParsers.pickEmbedStream(r.streams)!!.resolution)

        val with1080 = r.streams + EmbedTmdbStream("https://x/1080.mp4", 1080, 100L)
        assertEquals(1080, NetMirrorParsers.pickEmbedStream(with1080)!!.resolution)

        // Same resolution twice -> larger size wins.
        val tie = listOf(
            EmbedTmdbStream("https://x/a.mp4", 720, 100L),
            EmbedTmdbStream("https://x/b.mp4", 720, 900L),
        )
        assertEquals("https://x/b.mp4", NetMirrorParsers.pickEmbedStream(tie)!!.url)
    }

    @Test
    fun pickEmbedStream_rejectsZeroAndOver1080() {
        val streams = listOf(
            EmbedTmdbStream("https://x/0.mp4", 0, null),
            EmbedTmdbStream("https://x/2160.mp4", 2160, null),
            EmbedTmdbStream("https://x/480.mp4", 480, null),
        )
        assertEquals(480, NetMirrorParsers.pickEmbedStream(streams)!!.resolution)
        assertNull(NetMirrorParsers.pickEmbedStream(emptyList()))
    }

    @Test
    fun parseEmbedTmdb_noSource_isCleanNegative() {
        val raw = """
            {"ok":true,"tmdbId":106148,"title":"A Flat","year":"2010","imdb":"tt0988655",
             "type":"movie","currentSeason":1,"currentEpisode":1,"exp":1788215942,"sig":"8476",
             "mode":"none","noSource":true,
             "error":"We couldn't find this title on NetMirror yet. Try a different one."}
        """.trimIndent()
        val r = NetMirrorParsers.parseEmbedTmdb(raw)!!
        assertTrue(r.noSource)
        assertTrue(r.streams.isEmpty())
    }

    @Test
    fun parseEmbedTmdb_tv_episodeShape() {
        val raw = """
            {"ok":true,"tmdbId":94605,"title":"Arcane","type":"tv","currentSeason":1,"currentEpisode":1,
             "mode":"proxy","mp4":"https://bcdnxw.hakunaymatata.com/resource/73ba.mp4?sign=c254&t=1",
             "resolution":"720",
             "streams":[{"url":"https://bcdnxw.hakunaymatata.com/resource/1a12.mp4?sign=e013&t=1","resolution":1080,"size":420495514}],
             "fallbackHls":"/api/loffe/tt11126994"}
        """.trimIndent()
        val r = NetMirrorParsers.parseEmbedTmdb(raw)!!
        assertEquals("tv", r.type)
        assertEquals(1080, NetMirrorParsers.pickEmbedStream(r.streams)!!.resolution)
    }

    @Test
    fun parseEmbedTmdb_mp4OnlyFallback() {
        val raw = """{"ok":true,"type":"movie","mp4":"https://cdn/x.mp4?sign=1","resolution":"480"}"""
        val r = NetMirrorParsers.parseEmbedTmdb(raw)!!
        assertEquals(1, r.streams.size)
        assertEquals(480, r.streams[0].resolution)
    }

    @Test
    fun parseEmbedTmdb_garbageAndNotOk() {
        assertNull(NetMirrorParsers.parseEmbedTmdb(null))
        assertNull(NetMirrorParsers.parseEmbedTmdb(""))
        assertNull(NetMirrorParsers.parseEmbedTmdb("not json"))
        assertNull(NetMirrorParsers.parseEmbedTmdb("""{"ok":false}"""))
        // Limited body must not parse as a result.
        assertNull(NetMirrorParsers.parseEmbedTmdb("Too many request in short.."))
    }

    @Test
    fun embedResolved_keepsEveryRenditionUnder1080() {
        val streams = listOf(
            EmbedTmdbStream("https://cdn/360.mp4", 360, 100L),
            EmbedTmdbStream("https://cdn/720.mp4", 720, 500L),
            EmbedTmdbStream("https://cdn/1080.mp4", 1080, 900L),
            EmbedTmdbStream("https://cdn/2160.mp4", 2160, 9999L),
            EmbedTmdbStream("https://cdn/0.mp4", 0, null),
        )
        val r = EmbedTmdb.Resolved(streams, emptyList())
        // bestQuality skips 2160 and 0; the picker is for the UI label.
        assertEquals(1080, r.bestQuality)
        // loadLinks filters the actual emission list; here we just assert the
        // helper exposes the streams for the multi-emit path.
        assertEquals(5, r.streams.size)
    }

    // ------------------------------------------------------------------
    // NewTV master validation
    // ------------------------------------------------------------------

    // Verbatim sessionless player.php master (probed pv/106148 + nf/934152):
    // identical dead template for every id — "in=unknown::ni" variants 404,
    // audio group URI has an empty host.
    private val deadMaster = """
        #EXTM3U
        #EXT-X-VERSION:3
        #EXT-X-MEDIA:TYPE=AUDIO,GROUP-ID="aac",LANGUAGE="und",NAME="[1] Unknown",DEFAULT=NO,URI="https:///files/106148/a/0/0.m3u8"
        #EXT-X-STREAM-INF:BANDWIDTH=1000000,AUDIO="aac",RESOLUTION=1920x1080,CLOSED-CAPTIONS=NONE
        https://s21.freecdn4.top/files/220884/1080p/1080p.m3u8?in=unknown::ni
        #EXT-X-STREAM-INF:BANDWIDTH=600000,AUDIO="aac",DEFAULT=YES,RESOLUTION=1280x720,CLOSED-CAPTIONS=NONE
        https://s21.freecdn4.top/files/220884/720p/720p.m3u8?in=unknown::ni
    """.trimIndent()

    // A master with a real resource key (the Flutter clone's hardcoded key
    // shape) and a host-bearing audio URI.
    private val liveMaster = """
        #EXTM3U
        #EXT-X-VERSION:3
        #EXT-X-MEDIA:TYPE=AUDIO,GROUP-ID="aac",LANGUAGE="und",NAME="[1] Unknown",DEFAULT=NO,URI="https://s21.freecdn4.top/files/106148/a/0/0.m3u8"
        #EXT-X-STREAM-INF:BANDWIDTH=600000,AUDIO="aac",DEFAULT=YES,RESOLUTION=1280x720,CLOSED-CAPTIONS=NONE
        https://s21.freecdn4.top/files/220884/720p/720p.m3u8?in=59a05b117809dbe6e0879acb3cac14c3::cb742acc402bbeeeaffbbb5ce48cb86e::1734859034::ni
    """.trimIndent()

    @Test
    fun newTvMasterPlayable_acceptsKeyedMaster_rejectsStub() {
        // The old newTvMasterIsDead() wrongly gated in=unknown masters that
        // the player actually plays (CDN validates per client context). The
        // replacement structural gate accepts keyed/in=unknown-variant
        // masters as long as variants + host-bearing audio exist.
        assertTrue(NetMirrorParsers.newTvMasterPlayable(liveMaster))
        // deadMaster carries the empty-host audio URI — the broken stub
        // shape (collection-id / unrecognised-context response) is rejected
        // by the structural gate, NOT by the in=unknown key.
        assertFalse(NetMirrorParsers.newTvMasterPlayable(deadMaster))
    }

    @Test
    fun newTvMasterPlayable_rejectsBlankAndNonPlaylist() {
        assertFalse(NetMirrorParsers.newTvMasterPlayable(null))
        assertFalse(NetMirrorParsers.newTvMasterPlayable(""))
        assertFalse(NetMirrorParsers.newTvMasterPlayable("<html>error</html>"))
        assertFalse(NetMirrorParsers.newTvMasterPlayable("Too many request in short.."))
    }

    // ------------------------------------------------------------------
    // Master resolution probe
    // ------------------------------------------------------------------

    @Test
    fun parseMasterResolution_usesDefaultVariant() {
        // deadMaster has a non-default 1920x1080 variant and a DEFAULT=YES
        // 1280x720 one — the default must win, matching what the player picks.
        assertEquals(1280 to 720, NetMirrorParsers.parseMasterResolution(deadMaster))
    }

    @Test
    fun parseMasterResolution_singleOrNoDefaultFallsBackToLowestBandwidth() {
        assertEquals(1280 to 720, NetMirrorParsers.parseMasterResolution(liveMaster))

        // No DEFAULT=YES anywhere: lowest bandwidth variant wins (720p).
        val noDefault = """
            #EXTM3U
            #EXT-X-STREAM-INF:BANDWIDTH=3000000,RESOLUTION=1920x1080
            https://cdn/1080p.m3u8
            #EXT-X-STREAM-INF:BANDWIDTH=1000000,RESOLUTION=1280x720
            https://cdn/720p.m3u8
        """.trimIndent()
        assertEquals(1280 to 720, NetMirrorParsers.parseMasterResolution(noDefault))
    }

    @Test
    fun parseMasterResolution_rejectsBlankAndMediaPlaylists() {
        assertNull(NetMirrorParsers.parseMasterResolution(null))
        assertNull(NetMirrorParsers.parseMasterResolution(""))
        assertNull(NetMirrorParsers.parseMasterResolution("Too many request in short.."))
        // A media playlist (no STREAM-INF) carries no resolution to probe.
        assertNull(NetMirrorParsers.parseMasterResolution("#EXTM3U\n#EXTINF:5,\nseg0.ts"))
    }

    // ------------------------------------------------------------------
    // Master variant enumeration (Fix 1b)
    // ------------------------------------------------------------------

    @Test
    fun parseMasterVariants_returnsAllResolutions() {
        val master = """
            #EXTM3U
            #EXT-X-STREAM-INF:BANDWIDTH=1000000,RESOLUTION=1920x1080
            https://cdn/1080p.m3u8
            #EXT-X-STREAM-INF:BANDWIDTH=600000,RESOLUTION=1280x720
            https://cdn/720p.m3u8
            #EXT-X-STREAM-INF:BANDWIDTH=300000,RESOLUTION=640x360
            https://cdn/360p.m3u8
        """.trimIndent()
        val masterUrl = "https://cdn/master.m3u8"
        val variants = NetMirrorParsers.parseMasterVariants(master, masterUrl)
        assertEquals(3, variants.size)
        assertEquals(1920 to 1080, variants[0].first to variants[0].second)
        assertEquals(1280 to 720, variants[1].first to variants[1].second)
        assertEquals(640 to 360, variants[2].first to variants[2].second)
        // Absolute URIs pass through unchanged.
        assertEquals("https://cdn/1080p.m3u8", variants[0].third)
    }

    @Test
    fun parseMasterVariants_resolvesRelativeAgainstMaster() {
        val master = """
            #EXTM3U
            #EXT-X-STREAM-INF:BANDWIDTH=600000,RESOLUTION=1280x720
            720p/index.m3u8
        """.trimIndent()
        val variants = NetMirrorParsers.parseMasterVariants(master, "https://cdn/path/master.m3u8")
        assertEquals(1, variants.size)
        assertEquals("https://cdn/path/720p/index.m3u8", variants[0].third)
    }

    @Test
    fun parseMasterVariants_rejectsBlankAndMediaPlaylists() {
        val masterUrl = "https://cdn/master.m3u8"
        assertEquals(emptyList(), NetMirrorParsers.parseMasterVariants(null, masterUrl))
        assertEquals(emptyList(), NetMirrorParsers.parseMasterVariants("", masterUrl))
        assertEquals(emptyList(), NetMirrorParsers.parseMasterVariants("Too many request in short..", masterUrl))
        // Media playlist (no STREAM-INF).
        assertEquals(emptyList(), NetMirrorParsers.parseMasterVariants("#EXTM3U\n#EXTINF:5,\nseg0.ts", masterUrl))
    }

    @Test
    fun parseMasterVariants_skipsVariantsWithoutResolution() {
        val master = """
            #EXTM3U
            #EXT-X-STREAM-INF:BANDWIDTH=600000
            https://cdn/720p.m3u8
            #EXT-X-STREAM-INF:BANDWIDTH=300000,RESOLUTION=640x360
            https://cdn/360p.m3u8
        """.trimIndent()
        val variants = NetMirrorParsers.parseMasterVariants(master, "https://cdn/master.m3u8")
        assertEquals(1, variants.size)
        assertEquals(360, variants[0].second)
    }

    // ------------------------------------------------------------------
    // Multi-audio delivery (the dual-audio fix)
    // ------------------------------------------------------------------

    // Verbatim live master from the Aug 2026 probe log (The Batman,
    // id=81500601): the VIDEO variants carry the dead in=unknown template,
    // yet the AUDIO renditions are host-bearing and structurally alive —
    // audio lives on a separate URL space (…/files/<id>/a/N/N.m3u8) than the
    // gated variants. This is the exact shape that forced always-extract-
    // audio + per-URL pre-flight instead of trusting the master deadness.
    private val dualAudioDeadVariantsMaster = """
        #EXTM3U
        #EXT-X-VERSION:3
        #EXT-X-MEDIA:TYPE=SUBTITLES,GROUP-ID="subs",NAME="English",DEFAULT=YES,AUTOSELECT=YES,FORCED=NO,LANGUAGE="en",URI="https://subscdn.top/subs/81500601/en.m3u8"
        #EXT-X-MEDIA:TYPE=AUDIO,GROUP-ID="aac",LANGUAGE="eng",NAME="1. English",DEFAULT=YES,URI="https://s21.freecdn4.top/files/81500601/a/0/0.m3u8"
        #EXT-X-MEDIA:TYPE=AUDIO,GROUP-ID="aac",LANGUAGE="hin",NAME="2. Hindi",DEFAULT=NO,URI="https://s21.freecdn4.top/files/81500601/a/1/1.m3u8"
        #EXT-X-STREAM-INF:BANDWIDTH=1000000,AUDIO="aac",RESOLUTION=1920x1080,SUBTITLES="subs",CLOSED-CAPTIONS=NONE
        https://s21.freecdn4.top/files/220884/1080p/1080p.m3u8?in=unknown::ni
        #EXT-X-STREAM-INF:BANDWIDTH=600000,AUDIO="aac",DEFAULT=YES,RESOLUTION=1280x720,SUBTITLES="subs",CLOSED-CAPTIONS=NONE
        https://s21.freecdn4.top/files/220884/720p/720p.m3u8?in=unknown::ni
        #EXT-X-STREAM-INF:BANDWIDTH=400000,AUDIO="aac",RESOLUTION=854x480,SUBTITLES="subs",CLOSED-CAPTIONS=NONE
        https://s21.freecdn4.top/files/220884/480p/480p.m3u8?in=unknown::ni
    """.trimIndent()

    @Test
    fun parseMasterAudioTracks_extractsDubsEvenWhenVariantsAreDead() {
        // The old probeMaster bailed on dead-variant masters BEFORE extracting
        // audio — losing the eng/hin renditions on exactly the masters that
        // carry them. Extraction must be deadness-independent; the master is
        // emitted verbatim and the player fetches audio in its own context.
        // Both masters here carry host-bearing dubs, so both are playable.
        assertTrue(NetMirrorParsers.newTvMasterPlayable(dualAudioDeadVariantsMaster))
        val audio = NetMirrorParsers.parseMasterAudioTracks(dualAudioDeadVariantsMaster)
        assertEquals(2, audio.size)
        assertEquals("eng", audio[0].first)
        assertEquals("1. English", audio[0].second)
        assertEquals("https://s21.freecdn4.top/files/81500601/a/0/0.m3u8", audio[0].third)
        assertEquals("hin", audio[1].first)
        assertEquals("2. Hindi", audio[1].second)
        assertEquals("https://s21.freecdn4.top/files/81500601/a/1/1.m3u8", audio[1].third)
    }

    @Test
    fun parseMasterAudioTracks_skipsSubtitleEntriesAndBrokenUris() {
        // SUBTITLES entries must not leak into the audio list; the empty-host
        // URI (dead template artifact) is what makes newTvMasterPlayable
        // reject the stub — the parser keeps it (non-blank) so the gate can
        // see it.
        val audio = NetMirrorParsers.parseMasterAudioTracks(dualAudioDeadVariantsMaster)
        assertTrue(audio.none { it.third.contains("subscdn") })
        val deadOnly = NetMirrorParsers.parseMasterAudioTracks(deadMaster)
        assertEquals(1, deadOnly.size)
        assertTrue(deadOnly[0].third.startsWith("https:///"))
    }

    // ------------------------------------------------------------------
    // newTvMasterPlayable (the reference-architecture master gate)
    // ------------------------------------------------------------------

    // Verbatim live master (Breaking Bad, nf/70236412): 10 host-bearing audio
    // renditions + in=unknown variants — the exact shape the user confirmed
    // PLAYING with the audio picker. The in=unknown key is NOT a defect:
    // every reference implementation emits it verbatim and the CDN validates
    // per client context (GatuNewTV UA + hd=on + Usertoken).
    private val dualAudioMaster = """
        #EXTM3U
        #EXT-X-VERSION:3
        #EXT-X-MEDIA:TYPE=SUBTITLES,GROUP-ID="subs",NAME="English",DEFAULT=YES,LANGUAGE="en",URI="https://subscdn.top/subs/70236412/en.m3u8"
        #EXT-X-MEDIA:TYPE=AUDIO,GROUP-ID="aac",LANGUAGE="eng",NAME="2. English",DEFAULT=YES,URI="https://s24.freecdn3.top/files/70236412/a/1/1.m3u8"
        #EXT-X-MEDIA:TYPE=AUDIO,GROUP-ID="aac",LANGUAGE="hin",NAME="9. Hindi",DEFAULT=NO,URI="https://s24.freecdn3.top/files/70236412/a/8/8.m3u8"
        #EXT-X-STREAM-INF:BANDWIDTH=1000000,AUDIO="aac",RESOLUTION=1920x1080,SUBTITLES="subs"
        https://s21.freecdn4.top/files/220884/1080p/1080p.m3u8?in=unknown::ni
        #EXT-X-STREAM-INF:BANDWIDTH=600000,AUDIO="aac",DEFAULT=YES,RESOLUTION=1280x720,SUBTITLES="subs"
        https://s21.freecdn4.top/files/220884/720p/720p.m3u8?in=unknown::ni
    """.trimIndent()

    // The broken stub served to unrecognised contexts: no STREAM-INF and an
    // empty-host audio URI ("https:///files/..") — Media3 throws the
    // unexpected-runtime-error (1004) / burns into 1003 at prepare.
    private val brokenStubMaster = """
        #EXTM3U
        #EXT-X-VERSION:3
        #EXT-X-MEDIA:TYPE=AUDIO,GROUP-ID="aac",LANGUAGE="und",NAME="[1] Unknown",DEFAULT=NO,URI="https:///files/70236412/a/0/0.m3u8"
    """.trimIndent()

    @Test
    fun newTvMasterPlayable_acceptsVerbatimMasterWithInUnknown() {
        // The critical regression guard: in=unknown variants + host-bearing
        // audio = the playable dual-audio master. Never gate this.
        assertTrue(NetMirrorParsers.newTvMasterPlayable(dualAudioMaster))
    }

    @Test
    fun newTvMasterPlayable_acceptsMonoMasterWithHostBearingAudio() {
        val mono = """
            #EXTM3U
            #EXT-X-MEDIA:TYPE=AUDIO,GROUP-ID="aac",LANGUAGE="eng",NAME="1. English",DEFAULT=YES,URI="https://s21.freecdn4.top/files/81500601/a/0/0.m3u8"
            #EXT-X-STREAM-INF:BANDWIDTH=600000,AUDIO="aac",DEFAULT=YES,RESOLUTION=1280x720
            https://s21.freecdn4.top/files/220884/720p/720p.m3u8?in=unknown::ni
        """.trimIndent()
        assertTrue(NetMirrorParsers.newTvMasterPlayable(mono))
    }

    @Test
    fun newTvMasterPlayable_rejectsBrokenStub() {
        // No #EXT-X-STREAM-INF (audio-only skeleton) + empty-host audio URI.
        assertFalse(NetMirrorParsers.newTvMasterPlayable(brokenStubMaster))
    }

    @Test
    fun newTvMasterPlayable_rejectsHostlessAudioEvenWithVariants() {
        val hostlessAudio = """
            #EXTM3U
            #EXT-X-MEDIA:TYPE=AUDIO,GROUP-ID="aac",LANGUAGE="und",NAME="[1] Unknown",DEFAULT=NO,URI="https:///files/70236412/a/0/0.m3u8"
            #EXT-X-STREAM-INF:BANDWIDTH=600000,AUDIO="aac",DEFAULT=YES,RESOLUTION=1280x720
            https://s21.freecdn4.top/files/220884/720p/720p.m3u8?in=unknown::ni
        """.trimIndent()
        assertFalse(NetMirrorParsers.newTvMasterPlayable(hostlessAudio))
    }

    @Test
    fun newTvMasterPlayable_rejectsGarbageAndVariantlessBodies() {
        assertFalse(NetMirrorParsers.newTvMasterPlayable(null))
        assertFalse(NetMirrorParsers.newTvMasterPlayable(""))
        assertFalse(NetMirrorParsers.newTvMasterPlayable("<html>404</html>"))
        assertFalse(NetMirrorParsers.newTvMasterPlayable("Too many request in short.."))
        // No #EXT-X-STREAM-INF = audio-only skeleton, never playable as video.
        assertFalse(NetMirrorParsers.newTvMasterPlayable("#EXTM3U\n#EXT-X-MEDIA:TYPE=AUDIO,GROUP-ID=\"aac\",LANGUAGE=\"eng\",NAME=\"1. English\",URI=\"https://s24.freecdn3.top/files/1/a/0/0.m3u8\""))
    }
}

class LoadDataCodecTest {

    @Test
    fun roundTrip_full() {
        val d = LoadData("160649", "Jackpot!", "1094138", 2, 7)
        val decoded = decodeLoadData(encodeLoadData(d))!!
        assertEquals("160649", decoded.id)
        assertEquals("Jackpot!", decoded.title)
        assertEquals("1094138", decoded.tmdbId)
        assertEquals(2, decoded.season)
        assertEquals(7, decoded.episode)
    }

    @Test
    fun roundTrip_movie_noSeasonEpisode() {
        val d = LoadData("160649", "Jackpot!", "1094138")
        val decoded = decodeLoadData(encodeLoadData(d))!!
        assertNull(decoded.season)
        assertNull(decoded.episode)
    }

    @Test
    fun roundTrip_legacyPayload_withoutTmdb() {
        // Old cached payloads / home-page taps carry only id+title.
        val decoded = decodeLoadData("""{"id":"123","title":"X"}""")!!
        assertEquals("123", decoded.id)
        assertNull(decoded.tmdbId)
    }

    @Test
    fun invalidInputs() {
        assertNull(decodeLoadData("not json"))
        assertNull(decodeLoadData("""{"title":"x"}"""))
        assertNull(decodeLoadData(""))
    }
}
