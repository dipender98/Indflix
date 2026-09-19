package Test

import com.multimovies.parseImdbEpisodes
import com.multimovies.parseImdbMeta
import com.multimovies.parseSuggest

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Guards the keyless IMDB parsers: suggest hits, Cinemeta detail/cast, and episode rows. */
class ImdbMetaTest {

    private val suggest = """
        {"d":[
          {"id":"tt4154796","l":"Avengers: Endgame","y":2019,"qid":"feature","rank":10,
           "i":{"imageUrl":"https://m.media-amazon.com/images/M/MV5B.jpg"}},
          {"id":"nm0000123","l":"Some Person","qid":"name"},
          {"id":"tt0123456","l":"Sample Game","y":2020,"qid":"videoGame"},
          {"id":"tt4574334","l":"Stranger Things","y":2016,"qid":"tvSeries","rank":20}]}
    """.trimIndent()

    private val meta = """
        {"meta":{"id":"tt4154796","type":"movie","name":"Avengers: Endgame",
          "poster":"https://image.tmdb.org/t/p/medium/x.jpg",
          "background":"https://image.tmdb.org/t/p/w1280/y.jpg",
          "releaseInfo":"2019","imdbRating":"8.4",
          "description":"After Thanos snaps.","genres":["Action","Adventure"],
          "cast":["Robert Downey Jr.","Chris Evans"]}}
    """.trimIndent()

    private val series = """
        {"meta":{"id":"tt4574334","type":"series","name":"Stranger Things",
          "videos":[
            {"season":1,"episode":1,"title":"Chapter One","overview":"Kids vanish.",
             "released":"2016-07-15","thumbnail":"https://img/thumb1.jpg"},
            {"season":1,"episode":2,"title":"","overview":"","released":"","thumbnail":""},
            {"title":"No season row"}]}}
    """.trimIndent()

    @Test
    fun suggestKeepsTitlesDropsNamesAndGames() {
        val hits = parseSuggest(suggest)

        assertEquals(listOf("tt4154796", "tt4574334"), hits.map { it.imdbId })
        assertEquals("movie", hits[0].type)
        assertEquals("2019", hits[0].year)
        assertEquals("series", hits[1].type)
    }

    @Test
    fun suggestBadInputYieldsEmpty() {
        assertTrue(parseSuggest(null).isEmpty())
        assertTrue(parseSuggest("").isEmpty())
        assertTrue(parseSuggest("{}").isEmpty())
        assertTrue(parseSuggest("nope").isEmpty())
    }

    @Test
    fun metaMapsDetailAndCast() {
        val d = parseImdbMeta(meta, "tt4154796")

        assertEquals("Avengers: Endgame", d?.name)
        assertEquals("2019", d?.year)
        assertEquals(8.4, d?.rating)
        assertEquals(listOf("Action", "Adventure"), d?.genres)
        assertEquals("After Thanos snaps.", d?.overview)
        assertTrue(d?.backdrop?.isNotBlank() == true)
        assertEquals(
            listOf("Robert Downey Jr.", "Chris Evans"),
            d?.cast?.map { it.actor?.name },
        )
    }

    @Test
    fun metaBadInputYieldsNull() {
        assertNull(parseImdbMeta(null, "tt1"))
        assertNull(parseImdbMeta("", "tt1"))
        assertNull(parseImdbMeta("{}", "tt1"))
    }

    @Test
    fun episodesSkipIncompleteRows() {
        val eps = parseImdbEpisodes(series)

        assertEquals(2, eps.size)
        assertEquals("Chapter One", eps[0].name)
        assertEquals("2016-07-15", eps[0].released)
        assertEquals("https://img/thumb1.jpg", eps[0].thumbnail)
        assertEquals(1 to 2, eps[1].season to eps[1].episode)
        assertNull(eps[1].name)
    }
}
