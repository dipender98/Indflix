package com.multimovies

import org.jsoup.Jsoup
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class CinemetaServiceTest {

    @Test
    fun `extractImdbId finds id from og meta tag`() {
        val doc = Jsoup.parse(
            """<html><head>
                <meta property="og:imdb_id" content="tt0944947">
            </head><body></body></html>"""
        )
        assertEquals("tt0944947", CinemetaService.extractImdbId(doc))
    }

    @Test
    fun `extractImdbId finds id from imdb link href`() {
        val doc = Jsoup.parse(
            """<html><body>
                <a href="https://www.imdb.com/title/tt0111417/">IMDb</a>
            </body></html>"""
        )
        assertEquals("tt0111417", CinemetaService.extractImdbId(doc))
    }

    @Test
    fun `extractImdbId finds id from dooplay imdb container`() {
        val doc = Jsoup.parse(
            """<html><body>
                <div class="imdb"><a href="/title/tt1234567/">8.5</a></div>
            </body></html>"""
        )
        assertEquals("tt1234567", CinemetaService.extractImdbId(doc))
    }

    @Test
    fun `extractImdbId finds id from imdb link class variations`() {
        val doc = Jsoup.parse(
            """<html><body>
                <span class="imdbRating"><a href="https://imdb.com/title/tt7654321/">9.0</a></span>
            </body></html>"""
        )
        assertEquals("tt7654321", CinemetaService.extractImdbId(doc))
    }

    @Test
    fun `extractImdbId finds id from data attribute`() {
        val doc = Jsoup.parse(
            """<html><body>
                <div data-imdb="tt1111111"></div>
            </body></html>"""
        )
        assertEquals("tt1111111", CinemetaService.extractImdbId(doc))
    }

    @Test
    fun `extractImdbId finds id from json-ld script`() {
        val doc = Jsoup.parse(
            """<html><head>
                <script type="application/ld+json">
                {"@context":"https://schema.org","@type":"TVSeries","@id":"https://imdb.com/title/tt2222222/"}
                </script>
            </head><body></body></html>"""
        )
        assertEquals("tt2222222", CinemetaService.extractImdbId(doc))
    }

    @Test
    fun `extractImdbId finds id from inline js variable`() {
        val doc = Jsoup.parse(
            """<html><body>
                <script>var imdb_id = "tt3333333";</script>
            </body></html>"""
        )
        assertEquals("tt3333333", CinemetaService.extractImdbId(doc))
    }

    @Test
    fun `extractImdbId finds id from single-quote json`() {
        val doc = Jsoup.parse(
            """<html><body>
                <script>{'imdb_id': 'tt4444444'}</script>
            </body></html>"""
        )
        assertEquals("tt4444444", CinemetaService.extractImdbId(doc))
    }

    @Test
    fun `pickBestImdbId matches exact title and year`() {
        val json = """
            {
              "metas": [
                {"id": "tt0944947", "name": "Game of Thrones", "year": "2011"},
                {"id": "tt1111111", "name": "Something Else", "year": "2015"}
              ]
            }
        """.trimIndent()
        assertEquals("tt0944947", CinemetaService.pickBestImdbId(json, "Game of Thrones", 2011))
    }

    @Test
    fun `pickBestImdbId falls back to first result`() {
        val json = """
            {
              "metas": [
                {"id": "tt5555555", "name": "Some Movie", "year": "2020"}
              ]
            }
        """.trimIndent()
        assertEquals("tt5555555", CinemetaService.pickBestImdbId(json, "Unrelated Query", null))
    }

    @Test
    fun `pickBestImdbId returns null for invalid json`() {
        assertNull(CinemetaService.pickBestImdbId("not json", "Game of Thrones", null))
        assertNull(CinemetaService.pickBestImdbId("", "Game of Thrones", null))
    }

    @Test
    fun `extractImdbId returns null when no imdb id present`() {
        val doc = Jsoup.parse("<html><body><h1>No links here</h1></body></html>")
        assertNull(CinemetaService.extractImdbId(doc))
    }

    @Test
    fun `parseMeta extracts episode descriptions and dates`() {
        val json = """
            {
              "meta": {
                "id": "tt0944947",
                "name": "Game of Thrones",
                "year": "2011",
                "description": "A story of conflict.",
                "imdbRating": "9.2",
                "genre": ["Drama", "Fantasy"],
                "cast": ["Peter Dinklage", "Emilia Clarke"],
                "videos": [
                  {"id": "tt0944947:1:1", "name": "Winter Is Coming", "overview": "An epic start.", "season": 1, "episode": 1, "released": "2011-04-17T00:00:00.000Z", "thumbnail": "https://episodes.metahub.space/tt0944947/1/1/poster.jpg"},
                  {"id": "tt0944947:1:2", "name": "The Kingsroad", "overview": "Second episode.", "season": 1, "episode": 2, "released": "2011-04-24T00:00:00.000Z"}
                ]
              }
            }
        """.trimIndent()

        val meta = CinemetaService.parseMeta(json)
        assertEquals("tt0944947", meta?.id)
        assertEquals("Game of Thrones", meta?.name)
        assertEquals("9.2", meta?.imdbRating)
        assertEquals(listOf("Drama", "Fantasy"), meta?.genre)
        assertEquals(listOf("Peter Dinklage", "Emilia Clarke"), meta?.cast)
        assertEquals(2, meta?.videos?.size)
        val ep1 = meta?.videos?.first()
        assertEquals("Winter Is Coming", ep1?.name)
        assertEquals("An epic start.", ep1?.overview)
        assertEquals("2011-04-17T00:00:00.000Z", ep1?.released)
        assertEquals("https://episodes.metahub.space/tt0944947/1/1/poster.jpg", ep1?.thumbnail)
    }

    @Test
    fun `parseMeta returns null for invalid json`() {
        assertNull(CinemetaService.parseMeta("not valid json"))
        assertNull(CinemetaService.parseMeta(""))
        assertNull(CinemetaService.parseMeta(null))
    }
}
