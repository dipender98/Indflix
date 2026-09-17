package Test

import com.indstream.VideasySource

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Guards VideasySource's mvm1 cipher and pure parsers. Round-trip + anchor tests so refactors can't silently break it. */
class VideasySourceTest {

    @Test
    fun mix_deterministicAndZeroAnchored() {
        assertEquals(0, VideasySource.mixForTest(0L))
        assertEquals(VideasySource.mixForTest(0xFFFFFFFFL), VideasySource.mixForTest(0xFFFFFFFFL))
        assertTrue(VideasySource.mixForTest(1L) != 0)
    }

    @Test
    fun fnv1a_deterministic() {
        assertTrue(VideasySource.fnv1aForTest("") != 0)
        assertEquals(VideasySource.fnv1aForTest("abc"), VideasySource.fnv1aForTest("abc"))
    }

    @Test
    fun decrypt_roundTrip() {
        // Encrypt == decrypt for a stream XOR cipher: run the keystream over a known plaintext, then decrypt back.
        val seed = "59622076.V3iLVcZwtMl1EabSU9UqBf"
        val mediaId = 385687
        val plaintext = "mvm1{\"ok\":true,\"sources\":[]}"
        val pt = plaintext.toByteArray(Charsets.UTF_8)

        val state = VideasySource.stateForTest(seed, mediaId)
        val cipher = ByteArray(pt.size)
        var i = 0; var counter = 0
        while (i < pt.size) {
            val v = VideasySource.keystreamForTest(state, counter).toLong() and 0xFFFFFFFFL
            counter++
            for (shift in intArrayOf(0, 8, 16, 24)) {
                if (i >= pt.size) break
                cipher[i] = (pt[i].toInt() xor ((v ushr shift) and 0xFFL).toInt()).toByte()
                i++
            }
        }
        val b64 = java.util.Base64.getEncoder().withoutPadding().encodeToString(cipher)
        assertEquals(plaintext.substring(4), VideasySource.decryptForTest(b64, seed, mediaId))
    }

    @Test
    fun decrypt_rejectsBadHeader() {
        // All-zeros ciphertext decrypts to garbage: the header check must throw.
        val b64 = java.util.Base64.getEncoder().withoutPadding().encodeToString(ByteArray(16))
        var threw = false
        try {
            VideasySource.decryptForTest(b64, "1.abc", 42)
        } catch (e: IllegalStateException) {
            threw = true
        }
        assertTrue(threw, "bad header must throw IllegalStateException")
    }

    @Test
    fun decrypt_urlSafeAlphabet() {
        // '-'/'_' must decode as '+'/'/' (player payloads are base64url).
        val seed = "abc.def"
        val mediaId = 550
        val pt = "mvm1{}".toByteArray(Charsets.UTF_8)
        val state = VideasySource.stateForTest(seed, mediaId)
        val cipher = ByteArray(pt.size)
        var i = 0; var counter = 0
        while (i < pt.size) {
            val v = VideasySource.keystreamForTest(state, counter).toLong() and 0xFFFFFFFFL
            counter++
            for (shift in intArrayOf(0, 8, 16, 24)) {
                if (i >= pt.size) break
                cipher[i] = (pt[i].toInt() xor ((v ushr shift) and 0xFFL).toInt()).toByte()
                i++
            }
        }
        val std = java.util.Base64.getEncoder().withoutPadding().encodeToString(cipher)
        val urlSafe = std.replace('+', '-').replace('/', '_')
        assertEquals("{}", VideasySource.decryptForTest(urlSafe, seed, mediaId))
    }

    @Test
    fun decrypt_liveVector() {
        // Captured against the live API (Fight Club, cdn route): the Kotlin cipher must yield the 4-rung ladder incl. 2160p.
        val seed = "59649679.oKTMB7xxSA_59Ci1A2gJIK"
        val payload = "rA_MuWHTkNIEL0s3fJPpESYY_5hCjwBeHE4die_jCPqbJHRsReRBRNr-9T1q9RCR80lqH2qupb9crIxeMsGEBNS7lWVzjO254MXGPW3S7fx29yyb28IDefEcxqkoiCsHnwFpf16UxZZpHQ93H8Bi61oGjaDCbm8IDyXAXoLyzil6DefU2X1TY3u4Ar5bjJEHO8_JufvTC3i9mbcNBziZUJeiuASIHuhh7Q7hqOKMq-Gz5_M_MZrlSH9w1SIcEqYOinuMCK6gt5rGbcsk6ldxFtrsByStkSh3bdkNA0Knb0pSX3MgL_pdfqt4uBJO9bCQ5UseLUoFUwFKnuPQ4XyuqlWNEXg-EAR_nUCbCw6sZ5QPIyRQRUnrkb_k7SAU1pMwBr8XFheD281jgfeAot2hfBn9RV7-de2MC56jubJqV2b8Ejp7evNJEaQMfwAq87e4tqhbwrgMMfaHIpvE7Mo8TtJZ6yS6Cui1v34GVOm_XPrDCYNul4diE2ZH7gMh_KxabwE7rNrD-TQG2LEUDYUdmjrWXyrNuu0D5SqUqJVul2N5skmw_fIqyPFgoPUCdJ_58infv-ZVAgGBhbDoJFo6zqR0z5X0x5skAghnjx0Nj4D8h24J3CpDjZSjcUQvyvgWGRtdaAT6mTCtHYamx376WuE1u2P8Zyu2c8jCF1eTLqd5BQoYEqTsxnKiG7MxUgOBMtBg24_LVaeqW333hQaBc3M-daKtT0ZC7e3v7_a0L6GZ2e6KDQL1OeveIPu2-8eq_u6VgLNDd0J8hRM6HwD98f9X-cEA8aZa"
        val plain = VideasySource.decryptForTest(payload, seed, 550)
        val r = VideasySource.parseResult(plain, "cdn")
        assertEquals(listOf("1080p", "720p", "480p", "2160p"), r.sources.map { it.quality })
        assertTrue(r.sources.all { it.url.endsWith(".m3u8") })
        assertEquals(2160, VideasySource.heightOf(r.sources.last().quality))
    }

    @Test
    fun heightOf_ladder() {
        assertEquals(2160, VideasySource.heightOf("2160p"))
        assertEquals(2160, VideasySource.heightOf("4K"))
        assertEquals(2160, VideasySource.heightOf("UHD"))
        assertEquals(1080, VideasySource.heightOf("1080p"))
        assertEquals(720, VideasySource.heightOf("720p"))
        assertEquals(480, VideasySource.heightOf("480p"))
        assertEquals(0, VideasySource.heightOf("Auto HLS"))
        assertEquals(0, VideasySource.heightOf("Hindi"))
        assertEquals(0, VideasySource.heightOf("Vimeos"))
        assertEquals(0, VideasySource.heightOf(null))
        assertEquals(0, VideasySource.heightOf(""))
    }

    @Test
    fun languageOf_labels() {
        assertEquals("Hindi", VideasySource.languageOf("Hindi"))
        assertEquals("English", VideasySource.languageOf("English"))
        assertEquals("English", VideasySource.languageOf("EN"))
        assertEquals("Multi", VideasySource.languageOf("Dual Audio"))
        assertEquals("Tamil", VideasySource.languageOf("Tamil"))
        assertEquals("", VideasySource.languageOf("1080p"))
        assertEquals("", VideasySource.languageOf("Vimeos"))
        assertEquals("", VideasySource.languageOf(null))
    }

    @Test
    fun isHls_directFilesAreNotAdaptive() {
        assertTrue(VideasySource.isHls("https://x.example/a/index.m3u8"))
        assertTrue(VideasySource.isHls("https://x.example/a/master.m3u8?t=abc"))
        assertTrue(VideasySource.isHls("https://x.example/stream"))
        assertFalse(VideasySource.isHls("https://x.example/a.mp4"))
        assertFalse(VideasySource.isHls("https://x.example/a.mkv"))
        assertFalse(VideasySource.isHls("https://x.example/a.webm"))
    }

    @Test
    fun routes_pinnedPathsAndCasing() {
        val byPath = VideasySource.ROUTES.associateBy { it.path }
        assertTrue(byPath.containsKey("cdn"), "CDN route (4K ladder) must stay")
        assertTrue(!byPath.containsKey("hdmovie"), "hdmovie 500s upstream")
        assertEquals("Movie", byPath.getValue("cdn").movieType)
        assertEquals("TV Series", byPath.getValue("cdn").tvType)
        assertEquals("movie", byPath.getValue("m4uhd").movieType)
        assertEquals("TV Series", byPath.getValue("m4uhd").tvType)
        assertTrue(byPath.getValue("lamovie").moviesOnly, "lamovie answers movies only")
        assertFalse(byPath.getValue("cdn").moviesOnly)
    }

    @Test
    fun routeUrl_shapes() {
        val cdn = VideasySource.ROUTES.first { it.path == "cdn" }
        val movie = VideasySource.routeUrl(cdn, "Fight Club", 1999, 550, "tt0137523", "movie", -1, -1, "s.eed")
        assertTrue(movie.startsWith("https://api.speedracelight.com/cdn/sources-with-title?"))
        assertTrue(movie.contains("title=Fight%20Club"))
        assertTrue(movie.contains("mediaType=Movie"))
        assertTrue(movie.contains("tmdbId=550") && movie.contains("enc=2"))
        val tv = VideasySource.routeUrl(cdn, "Show", 2011, 1399, null, "tv", 1, 2, "s.eed")
        assertTrue(tv.contains("mediaType=TV%20Series") || tv.contains("mediaType=TV+Series"))
        assertTrue(tv.contains("seasonId=1") && tv.contains("episodeId=2"))
        val flipped = VideasySource.routeUrl(cdn, "Show", 2011, 1399, null, "movie", -1, -1, "s", flipCasing = true)
        assertTrue(flipped.contains("mediaType=movie"))
    }

    @Test
    fun playbackHeaders_browserUaWithPlayerReferer() {
        // Segments 403 without the player Referer; Origin stays off.
        val h = VideasySource.playbackHeaders()
        assertTrue((h["User-Agent"] ?: "").contains("Mozilla"), "playback needs a browser UA")
        assertEquals("https://player.videasy.to/", h["Referer"], "segments need the player Referer")
        assertFalse(h.containsKey("Origin"), "no Origin on playback")
    }

    @Test
    fun needsCdnBackfill_onlyWhenSourcesLackCdn() {
        val without = VideasySource.Result(listOf(VideasySource.Source("1080p", "u1", "m4uhd")), emptyList(), true)
        assertTrue(VideasySource.needsCdnBackfill(without))
        val with = VideasySource.Result(
            listOf(VideasySource.Source("1080p", "u1", "m4uhd"), VideasySource.Source("2160p", "u2", "cdn")),
            emptyList(), true,
        )
        assertFalse(VideasySource.needsCdnBackfill(with))
        assertFalse(VideasySource.needsCdnBackfill(VideasySource.Result(emptyList(), emptyList(), true)))
    }

    @Test
    fun mergePatch_dedupesByUrlAndKeepsBase() {
        val base = VideasySource.Result(listOf(VideasySource.Source("1080p", "u1", "m4uhd")), listOf("EN" to "s1"), true)
        val patch = VideasySource.Result(
            listOf(VideasySource.Source("1080p", "u1", "cdn"), VideasySource.Source("2160p", "u2", "cdn")),
            listOf("EN" to "s1"), true,
        )
        val merged = VideasySource.mergePatch(base, patch)
        assertEquals(listOf("u1", "u2"), merged.sources.map { it.url })
        assertEquals("m4uhd", merged.sources[0].route)
        assertTrue(merged.httpOk)
        assertEquals(base, VideasySource.mergePatch(base, VideasySource.Result(emptyList(), emptyList(), true)))
    }

    @Test
    fun parseResult_sourcesAndSubtitles() {
        val json = """{"sources":[{"quality":"1080p","url":"https://x.example/a.m3u8"},
            {"quality":"Hindi","url":"https://x.example/b.m3u8"}],
            "subtitles":[{"language":"EN","url":"https://x.example/e.vtt"}]}"""
        val r = VideasySource.parseResult(json, "cdn")
        assertTrue(r.httpOk)
        assertEquals(2, r.sources.size)
        assertEquals("1080p", r.sources[0].quality)
        assertEquals("cdn", r.sources[0].route)
        assertEquals(1, r.subtitles.size)
        assertEquals("EN", r.subtitles[0].first)
        val empty = VideasySource.parseResult("""{"sources":[],"subtitles":[]}""", "cdn")
        assertTrue(empty.sources.isEmpty())
    }
}
