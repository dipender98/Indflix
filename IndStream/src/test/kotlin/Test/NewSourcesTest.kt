package Test

import com.indstream.ServerFarm
import com.indstream.ServerIdType
import com.indstream.StreamEngine
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class NewSourcesTest {

    @Test
    fun farm_presentAndTmdbKeyed() {
        for (id in listOf("vidrift", "movy", "vidy", "vidbolt", "vidly")) {
            val s = ServerFarm.allServers.firstOrNull { it.id == id }
            assertTrue(s != null, "$id must be in the farm")
            assertEquals(ServerIdType.TMDB, s!!.idType)
        }
    }

    @Test
    fun downloadFarm_presentAndTmdbKeyed() {
        for (id in listOf("hindmoviez", "hdhub4u", "moviesdrive")) {
            val s = ServerFarm.allServers.firstOrNull { it.id == id }
            assertTrue(s != null, "$id must be in the farm")
            assertEquals(ServerIdType.TMDB, s!!.idType)
        }
    }

    @Test
    fun titleHits_parsesSearchMarkup() {
        val html = "<h2 class=\"entry-title\"><a href=\"/jawan-2023/\">Jawan 2023</a></h2>" +
            "<a href=\"/2026/07/31/film/\"><p>Film (2026)</p></a>" +
            "<h3 class=\"simple-grid-grid-post-title\"><a href=\"/dude-film/\">Dude Film</a></h3>"
        val hits = StreamEngine.titleHits(html, "https://site.example")
        assertEquals(3, hits.size)
        assertTrue(hits.any { it.first == "https://site.example/jawan-2023/" })
        assertTrue(hits.any { it.second == "Film (2026)" })
    }

    @Test
    fun ariaHits_parsesSearchMarkup() {
        val html = "<a href=\"/jawan-2023-hindi-webrip-full-movie/\" " +
            "aria-label=\"Jawan (2023) WEB-DL Hindi | Full Movie\">x</a>" +
            "<a href=\"/category/action/\" aria-label=\"Action\">x</a>"
        val hits = StreamEngine.ariaHits(html, "https://site.example")
        assertEquals(1, hits.size)
        assertEquals("https://site.example/jawan-2023-hindi-webrip-full-movie/", hits[0].first)
    }

    @Test
    fun posterHits_parsesSearchMarkup() {
        val html = "<a href=\"https://site.example/jawan-2023/\" data-wpel-link=\"internal\">" +
            "<div class=\"poster-card\"><p class=\"poster-title\">Download Jawan (2023) Hindi 720p 1080p</p></div></a>" +
            "<a href=\"https://site.example/search/\" data-wpel-link=\"internal\">" +
            "<p class=\"poster-title\">Search</p></a>" +
            "<a href=\"https://site.example/latest/\" data-wpel-link=\"internal\"><div class=\"poster-card\"></div></a>"
        val hits = StreamEngine.posterHits(html, "https://site.example")
        assertEquals(1, hits.size)
        assertEquals("https://site.example/jawan-2023/", hits[0].first)
        assertTrue(hits[0].second.contains("Jawan"))
    }

    @Test
    fun headingAnchorHits_usesNearestHeading() {
        val html = "<h3>Jawan 2023 720p [1.4GB]</h3>" +
            "<p><a href=\"https://links.example/1\">Download Links</a></p>"
        val hits = StreamEngine.headingAnchorHits(html, "https://site.example", Regex("links\\.example"))
        assertEquals(1, hits.size)
        assertEquals("Jawan 2023 720p [1.4GB]", hits[0].second)
    }

    @Test
    fun headingAnchorHits_mergesEpisodeHeadingWithFileLabel() {
        val html = "<h5>Show S01E02 720p [1GB]</h5>" +
            "<h5><a href=\"https://hubcloud.ist/drive/search-recover.php?from_ac=x\">720p 10bit [1GB]</a></h5>"
        val hits = StreamEngine.headingAnchorHits(html, "https://site.example", Regex("search-recover"))
        assertEquals(1, hits.size)
        assertTrue(hits[0].second.contains("S01E02"))
        assertTrue(hits[0].second.contains("720p"))
    }

    @Test
    fun fileHostLinks_keepsFilesAndSkipsGatewayNoise() {
        val html = "<a href=\"https://hubcloud.ist/drive/x\">Cloud</a>" +
            "<a href=\"https://share.example/0:/film.mkv\">Direct</a>" +
            "<a href=\"https://hubcloud.ist/tg/go?id=x\">Telegram</a>" +
            "<a href=\"https://pixel.hubcloud.ist/x\">Pixel</a>" +
            "<a href=\"https://pixeldrain.dev/u/AbC123\">PixelDrain</a>" +
            "<a href=\"https://host.example/hubcloud.php?token=x\">Token</a>"
        val hits = StreamEngine.fileHostLinks(html, "https://site.example")
        assertEquals(3, hits.size)
        assertTrue(hits.any { it.first.endsWith(".mkv") })
        assertTrue(hits.any { it.first.contains("pixeldrain") })
    }

    @Test
    fun pixeldrainDirect_preservesHostAndConvertsPageUrls() {
        assertEquals(
            "https://pixeldrain.dev/api/file/AbC123",
            StreamEngine.pixeldrainDirect("https://pixeldrain.dev/u/AbC123"),
        )
        assertEquals(
            "https://pixeldrain.com/api/file/AbC123",
            StreamEngine.pixeldrainDirect("https://pixeldrain.com/api/file/AbC123"),
        )
        assertNull(StreamEngine.pixeldrainDirect("https://share.example/u/AbC123"))
    }

    @Test
    fun qualityText_and_fileNameMatches() {
        assertEquals("720p", StreamEngine.qualityText("720p 10Bit HEVC [1GB]"))
        assertEquals("4K", StreamEngine.qualityText("4K [2160p SDR]"))
        assertTrue(StreamEngine.fileNameMatches("Jawan", "2023", "Jawan.Extended.Cut.2023.720p.mkv"))
        assertTrue(!StreamEngine.fileNameMatches("Jawan", "2023", "Kuriyan.Jawan.Bapu.2025.720p.mkv"))
        assertTrue(StreamEngine.fileNameMatches("Dune Part Two", "2024", "Dune.Part.Two.2024.1080p.mkv"))
    }

    @Test
    fun movy_decrypt_pinnedVector() {
        // Captured live from the upstream API (2026); multi-quality movie sources.
        val seed = "59844493.b9mTUnMgSYVBwNFuYi9e_X"
        val cipher = "RMiP6gpv9YAZr2X7FBWLD37vRV6PAZXvNEvq9R1ME4ZPoVGUx6HppJik9hLRwkbyGSlo39t36zFp-8IgRulQq-_GbBF0ke2mn7pu9ocaNigK0smrhduSPzPwNAexHyvXt_3wwXpteask9fmrRf3tc-nPsA4DlUVkXcjLGnuoGZ5G9l3XWgvH_kplDDD6e2pGN99zS9B4Al_1DwGk3mIHs-VONO3l8oIb4GDVHgm4iKuhv5qeKS0kUoBCNIs66FWd6yLwf-Am5qEZMEXzeEq2n-SegpwoUlfyVa3EWCmW0GRLfi6HxurF0M9zAtz1p21EqECVRFvelMp-kxTJElNz6GqpqNSJ0pJ4dgnAPBE3fU7Qdo288-JoWadcCNcOU-Ne2WzhpOA5w0gBzAynBuHWCAD-pohSR2KheTVx7kO7CxcIe34KuP5gJ4NtGVSVYss8e6qg8iCHj5BXVJYSNPFgWRgooxuAnYw41tJZIG6c_AEc8xqiX5o6GZZ-MA4K2usBgXe1GB72CIZw1pzTd2jrOfvN6TUT9d46yIThfg7pNr42hweCtyDHWShgAal3Q6IAXdBkHd4-HVKv2TqZXo07ZvXT9rpKKB7_cD4LgKaRjXJud0vV6HVzsqvh0AVHUBd2Jwz6V5Frg_HvFEsuWlTvsK5relp_pw6-rYlO-j2tVTnbXF8D6VEtFL4fKGsEgEEDl5FmPM1h8w5w_evKROp8gXMoZW5pspO0-xiy2b-PFy8estJ0yoFDf64e8Welgp8moyAHdQYBYMfOLPS0Km-jQMD5H6HLaCBKQ2izTmGoi8u9r9mCNbL9rzPNxt4EtLE3_v-fghqyXlDp48uSU56jwtp3A8eJB8Aa3UtSdJOBj_DQplW0oBFUWuIOfssHiOZV0wGAUKfHxAYGTUEk6-lEp1oKsXIaRw2OOr_TfrXeoIZuoMCW9rpEhBZ05P7yhBGTJJruZRUAtASpLWhyKFDonrve9URes18QW9G-F76HAxumgm5MfaTu1UbCgRVY3bzvPlxsKj7v_e5hBhCz1PX38Yksgoz_FD27VCl3jwtWpgGAR7cjPiBaaGcUi3vHdxdFomF2MU4GsMxV9i2FAyPDV0jhDdENUwsUmZIg1LoW8hanesW5OaxCJLFUgz9lCA15keoDOU8aUl0KyxR8lW-6kLekXFp0lqomhc6acuNFmKVCsoe5ShWKusW-k1RE7oY5zqEI_dy8Wy25F4K0PGt7pILovM2LMQ31mrGnHayrjFJHDsEvKUcuvalhebhZQI5kQ0eI_qhuti66k552XHj404xKn7DJthGg73qAYIGSsf_dYs5GnyivxjTUdX-3662Q8SW7M7LsKr5ZM487Flvp9dP94z8wyc5hOIgcIa0OX-4AldLYKCIxbPLtM_rbtLWIcz6v0wlwPs_re1v-uh-X5f6ydUkCphcun3p_q9as_XBgAEYA1mfcWsGQ39FUELcK354KaOyRfmP3XE9a28pN7EXydC58CoFSX6kQDMpbpBIgSuChIWC5ZpOh6qGZaBbpy-fTUSZN-VDD33Ld5f4v9sdPC1Zx6-smG2SYjNAFLC0fOWWoJ7QQxWFVNEclByRrrdmTeBQaTG4HyhPa1nkeBP24"
        val expected = """{"sources":[{"quality":"1080p","url":"https://moon.goldenkite.top/vd/clRqbWpXTGRKRmlZb3dhZ05jNk4zZzpvWUpyN3FOTWZOWFY4VldUYlpaaFZOMXRQNFhHaVNoWWk3ZVl5djVmUm1z/index-s1080p-v1-a1.m3u8?s=s1.1795356394.4.j-rzTZnC-jjv4BzfGSQenZ"},{"quality":"720p","url":"https://moon.goldenkite.top/vd/clRqbWpXTGRKRmlZb3dhZ05jNk4zZzpvWUpyN3FOTWZOWFY4VldUYlpaaFZOMXRQNFhHaVNoWWk3ZVl5djVmUm1z/index-s720p-v1-a1.m3u8?s=s1.1795356394.4.j-rzTZnC-jjv4BzfGSQenZ"},{"quality":"480p","url":"https://moon.goldenkite.top/vd/clRqbWpXTGRKRmlZb3dhZ05jNk4zZzpvWUpyN3FOTWZOWFY4VldUYlpaaFZOMXRQNFhHaVNoWWk3ZVl5djVmUm1z/index-s480p-v1-a1.m3u8?s=s1.1795356394.4.j-rzTZnC-jjv4BzfGSQenZ"},{"quality":"2160p","url":"https://moon.goldenkite.top/vd/clRqbWpXTGRKRmlZb3dhZ05jNk4zZzpvWUpyN3FOTWZOWFY4VldUYlpaaFZOMXRQNFhHaVNoWWk3ZVl5djVmUm1z/index-s2160p-v1-a1.m3u8?s=s1.1795356394.4.j-rzTZnC-jjv4BzfGSQenZ"}],"subtitles":[],"thumbnail":"https://moon.goldenkite.top/vd/clRqbWpXTGRKRmlZb3dhZ05jNk4zZzpvWUpyN3FOTWZOWFY4VldUYlpaaFZOMXRQNFhHaVNoWWk3ZVl5djVmUm1z/thumbs/thumbs.vtt","playlist":"https://moon.goldenkite.top/vd/clRqbWpXTGRKRmlZb3dhZ05jNk4zZzpvWUpyN3FOTWZOWFY4VldUYlpaaFZOMXRQNFhHaVNoWWk3ZVl5djVmUm1z/master.m3u8?s=s1.1795356394.4.j-rzTZnC-jjv4BzfGSQenZ"}"""
        assertEquals(expected, StreamEngine.movyDecrypt(cipher, seed, 872906))
        assertNull(StreamEngine.movyDecrypt(cipher, "wrong-seed", 872906))
    }

    @Test
    fun movy_parseSources() {
        val out = StreamEngine.movySources("""{"sources":[{"quality":"Hindi","url":"https://x.example/h.m3u8"}],"subtitles":[]}""")
        assertEquals(1, out.size)
        assertEquals("Hindi", out[0].quality)
        assertTrue(StreamEngine.movySources("nope").isEmpty())
    }

    @Test
    fun vidrift_streams_pinnedConfig() {
        val html = "<script>var embedMeta = {\"tmdbId\":27205,\"provider\":\"orion\"," +
            "\"orionStreams\":[" +
            "{\"index\":0,\"url\":\"/api/orion/abc/master.m3u8\",\"type\":\"hls\",\"provider\":\"Orion\",\"name\":\"Orion A\u2022 Original\"}," +
            "{\"index\":1,\"url\":\"/api/orion/def/master.m3u8\",\"name\":\"Orion A\u2022 Hindi\"}" +
            "]};</script>"
        val out = StreamEngine.vidriftStreams(html)
        assertEquals(2, out.size)
        assertEquals(Triple("/api/orion/abc/master.m3u8", "Original", 0), out[0])
        assertEquals(Triple("/api/orion/def/master.m3u8", "Hindi", 0), out[1])
        assertTrue(StreamEngine.vidriftStreams("<html>no config</html>").isEmpty())
    }

    @Test
    fun vidbolt_usesJsonResolverRoute() {
        val s = ServerFarm.allServers.first { it.id == "vidbolt" }
        assertEquals("VidBolt", s.name)
        assertEquals(ServerIdType.TMDB, s.idType)
        assertTrue(s.isJsonApi)
        assertTrue(s.hasSubtitles)
        assertEquals(
            "https://vidbolt.xyz/api/resolve?type=movie&id=27205",
            ServerFarm.buildMovieUrl(s, "27205"),
        )
        assertEquals(
            "https://vidbolt.xyz/api/resolve?type=tv&id=1399&season=1&episode=1",
            ServerFarm.buildTvUrl(s, "1399", 1, 1),
        )
    }

    @Test
    fun vidly_usesEmbedRoutes() {
        val s = ServerFarm.allServers.first { it.id == "vidly" }
        assertEquals("Vidly", s.name)
        assertEquals(ServerIdType.TMDB, s.idType)
        assertTrue(s.hasSubtitles)
        assertEquals(
            "https://vidly.pro/embed/movie/27205",
            ServerFarm.buildMovieUrl(s, "27205"),
        )
        assertEquals(
            "https://vidly.pro/embed/tv/1399/1/1",
            ServerFarm.buildTvUrl(s, "1399", 1, 1),
        )
    }

    @Test
    fun vidboltSources_parsesSources() {
        val json = """
            {"stream":{"server":"orion","sources":[
              {"url":"https://cdn.example/master.m3u8","kind":"hls","quality":"1080p",
               "language":"Hindi","master":"#EXTM3U\n#EXT-X-STREAM-INF:RESOLUTION=1920x1080\nv.m3u8"},
              {"url":"not-a-url","quality":"720p"},
              {"url":"https://cdn.example/2160.mp4","kind":"mp4","quality":"4K","label":"UHD"}
            ]}}
        """.trimIndent()
        val src = StreamEngine.vidboltSources(json)
        assertEquals(2, src.size)
        assertEquals("https://cdn.example/master.m3u8", src[0].url)
        assertEquals("hls", src[0].kind)
        assertEquals("1080p", src[0].quality)
        assertEquals("Hindi", src[0].language)
        assertTrue(src[0].master.contains("#EXTM3U"))
        assertEquals("4K", src[1].quality)
        assertEquals("UHD", src[1].label)
        assertTrue(StreamEngine.vidboltSources("""{"stream":{"sources":[]}}""").isEmpty())
        assertTrue(StreamEngine.vidboltSources("nope").isEmpty())
    }

    @Test
    fun vidboltSources_keepsInlineDashManifest() {
        val json = """
            {"stream":{"server":"orion","sources":[
              {"url":"https://cdn.example/dash.mpd","kind":"dash","quality":"1080p",
               "master":"<MPD><Period><AdaptationSet mimeType=\"audio/mp4\" xml:lang=\"hi\"><Representation codecs=\"mp4a.40.2\"/></AdaptationSet></Period></MPD>"}
            ]}}
        """.trimIndent()
        val src = StreamEngine.vidboltSources(json)
        assertEquals(1, src.size)
        assertEquals("dash", src[0].kind)
        assertTrue(src[0].master.contains("<MPD"))
        assertEquals("Hindi", StreamEngine.manifestAudioLabel(src[0].master))
    }

    @Test
    fun vidlyStreams_unescapesAndDedupes() {
        val html = """
            <link rel="preload" href="https://img.example/media/a/index.m3u8?api_key=x">
            <script>self.__next_f.push([1,"https:\/\/img.example\/media\/a\/index.m3u8?api_key=x"])</script>
            <script>self.__next_f.push([1,"https:\/\/img.example\/media\/b\/master.m3u8"])</script>
        """.trimIndent()
        assertEquals(
            listOf(
                "https://img.example/media/a/index.m3u8?api_key=x",
                "https://img.example/media/b/master.m3u8",
            ),
            StreamEngine.vidlyStreams(html),
        )
        assertTrue(StreamEngine.vidlyStreams("<html>no stream</html>").isEmpty())
    }
}
