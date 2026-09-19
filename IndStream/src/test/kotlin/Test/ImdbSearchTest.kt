package Test

import com.indstream.ImdbService
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Guards the keyless suggest + metadata parsers backing the TMDB race. */
class ImdbSearchTest {

    @Test
    fun parseSuggest_mapsHitsAndDropsNames() {
        val raw = """{"d":[
            {"id":"tt2631186","l":"Baahubali: The Beginning","y":2015,"qid":"movie",
             "rank":5816,"i":{"imageUrl":"https://example.com/a.jpg"}},
            {"id":"tt37787357","l":"Bindiya Ke Bahubali","y":2025,"qid":"tvSeries","rank":41870},
            {"id":"nm12064020","l":"Bahubali","s":"Director"}
        ]}"""
        val hits = ImdbService.parseSuggest(raw)
        assertEquals(2, hits.size)
        assertEquals("tt2631186", hits[0].imdbId)
        assertEquals("movie", hits[0].type)
        assertEquals("https://example.com/a.jpg", hits[0].poster)
        assertEquals("tv", hits[1].type)
        assertEquals(2025, hits[1].year)
    }

    @Test
    fun parseSuggest_dropsGamesAndBlank() {
        assertTrue(ImdbService.parseSuggest("""{"d":[{"id":"tt1","l":"X","qid":"videoGame"}]}""").isEmpty())
        assertTrue(ImdbService.parseSuggest(null).isEmpty())
        assertTrue(ImdbService.parseSuggest("nope").isEmpty())
    }

    @Test
    fun parseCinemeta_mapsDetail() {
        val raw = """{"meta":{"name":"Dune","poster":"https://p.jpg","background":"https://b.jpg",
            "releaseInfo":"2021","imdbRating":"8.0","description":"Desert saga.",
            "genres":["Sci-Fi"],"cast":["Timothee"]}}"""
        val d = ImdbService.parseCinemeta(raw, "tt1160419")
        assertEquals("Dune", d?.name)
        assertEquals("https://p.jpg", d?.poster)
        assertEquals("https://b.jpg", d?.backdrop)
        assertEquals(8.0, d?.rating)
        assertEquals(listOf("Sci-Fi"), d?.genres)
        assertEquals(1, d?.cast?.size)
    }

    @Test
    fun parseCinemetaEpisodes_mapsRows() {
        val raw = """{"meta":{"videos":[
            {"season":1,"episode":2,"title":"Ep2","overview":"O","released":"2020-01-01","thumbnail":"https://t.jpg"},
            {"season":0,"episode":1,"title":"Special"}
        ]}}"""
        val eps = ImdbService.parseCinemetaEpisodes(raw)
        assertEquals(1, eps.size)
        assertEquals(1, eps[0].seasonNumber)
        assertEquals(2, eps[0].episodeNumber)
        assertEquals("Ep2", eps[0].name)
        assertNull(ImdbService.parseCinemeta(null, "tt1")?.name)
    }
}
