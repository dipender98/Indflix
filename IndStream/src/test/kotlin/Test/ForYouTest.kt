package Test

import com.indstream.TmdbService
import com.indstream.foryou.AiService
import com.indstream.foryou.ForYouEngine
import com.indstream.foryou.ProfileTitle
import com.indstream.foryou.SimklParser
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ForYouTest {


    private val showJson = """{
      "shows": [{
        "last_watched_at": "2024-06-01T10:00:00Z",
        "user_rating": 7,
        "status": "completed",
        "watched_episodes_count": 10,
        "total_episodes_count": 10,
        "show": {"title": "Seen Series", "year": 2019,
          "ids": {"tmdb": "5114", "imdb": "tt111"}}
      }],
      "anime": [{
        "status": "watching",
        "show": {"title": "Some Anime", "ids": {"tmdb": "600"}}
      }],
      "movies": [{
        "last_watched_at": "2024-07-02T08:30:00Z",
        "status": "completed",
        "movie": {"title": "Seen Movie", "year": 2001, "ids": {"tmdb": "42", "imdb": "tt222"}}
      }]
    }"""

    @Test
    fun parse_reads_ids_status_type_and_time() {
        val out = SimklParser.parse(showJson)
        assertEquals(3, out.size)
        val show = out.first { it.title == "Seen Series" }
        assertEquals("tv", show.type)
        assertEquals(5114, show.tmdbId)
        assertEquals("tt111", show.imdbId)
        assertEquals(2019, show.year)
        assertTrue(show.watched)
        assertEquals(7.0, show.rating)
        assertEquals("completed", show.status)
        assertTrue(show.lastActivityAt > 0)
        assertEquals("tv", out.first { it.title == "Some Anime" }.type)
        val movie = out.first { it.title == "Seen Movie" }
        assertEquals("movie", movie.type)
        assertEquals(42, movie.tmdbId)
        assertTrue(movie.watched)
    }

    @Test
    fun rated_list_entries_are_hints_without_playback() {
        val json = """{"movies": [{"user_rating": 9, "status": "plantowatch",
            "movie": {"title": "Wishlisted", "ids": {}}}]}"""
        val out = SimklParser.parse(json)
        assertEquals(1, out.size)
        val item = out.first()
        assertTrue(!item.watched)
        assertEquals(9.0, item.rating)
    }

    @Test
    fun silent_unrated_entries_are_skipped() {
        assertEquals(0, SimklParser.parse("""{"shows": [{"movie": {"title": "Lonely"}}]}""").size)
        assertEquals(0, SimklParser.parse("""{"movies": [{"status": "completed"}]}""").size)
        assertEquals(0, SimklParser.parse("").size)
        assertEquals(0, SimklParser.parse("{broken").size)
    }

    @Test
    fun parse_time_only_accepts_iso_utc() {
        assertTrue(SimklParser.parseTime("2024-06-01T10:00:00Z") > 0)
        assertEquals(0L, SimklParser.parseTime(null))
        assertEquals(0L, SimklParser.parseTime(""))
        assertEquals(0L, SimklParser.parseTime("not-a-date"))
    }

    @Test
    fun dropped_and_low_rated_titles_carry_negative_weight() {
        val now = System.currentTimeMillis()
        val dropped = ProfileTitle(1, null, "Dropped", 2020, "movie", false, 8.0, now, "dropped")
        val low = ProfileTitle(2, null, "Low", 2020, "movie", true, 2.0, now)
        val loved = ProfileTitle(3, null, "Loved", 2020, "movie", true, 9.0, now)
        val saved = ProfileTitle(4, null, "Saved", 2020, "movie", false, 0.0, 0L)
        assertTrue(dropped.weight < 0.0)
        assertTrue(low.weight < 0.0)
        assertTrue(loved.weight > 1.0)
        assertTrue(saved.weight > 0.0)
    }

    @Test
    fun parser_preserves_dropped_status() {
        val json = """{"movies": [{"status": "dropped", "user_rating": 8,
            "movie": {"title": "Nope", "ids": {"tmdb": "7"}}}]}"""
        val item = SimklParser.parse(json).single()
        assertTrue(!item.watched)
        assertTrue(item.dropped)
        assertTrue(item.weight < 0.0)
    }


    private fun candidate(id: Int, title: String, genres: String = "Action") =
        AiService.Candidate(id, title, "2020", 7.5, genres)

    @Test
    fun prompt_lists_watch_history_and_candidates() {
        val watched = listOf(
            ProfileTitle(10, "tt1", "Old Title", 1999, "movie", true, 8.0, 100L),
            ProfileTitle(null, null, "List Title", null, "tv", false, 0.0, 0L),
        )
        val prompt = AiService.buildPrompt(watched, listOf(candidate(99, "Pool Title")), 7)
        assertTrue(prompt.contains("Old Title"))
        assertTrue(prompt.contains("Pool Title"))
        assertTrue(prompt.contains("99"))
        assertTrue(prompt.contains("up to 7"))
        assertTrue(prompt.contains("JSON array"))
        assertTrue(prompt.contains("[123, 456]"))
    }

    @Test
    fun prompt_caps_watched_and_candidate_lines() {
        val watched = (1..100).map { ProfileTitle(it, null, "w$it", null, "movie", true, 0.0, 0L) }
        val pool = (1..100).map { candidate(it, "c$it") }
        val prompt = AiService.buildPrompt(watched, pool, 20)
        assertTrue(prompt.lines().count { it.startsWith("- ") } <= 100)
    }

    @Test
    fun prompt_adds_taste_metadata_and_candidate_overviews() {
        val watched = listOf(
            ProfileTitle(1, null, "Loved", 2019, "movie", true, 9.0, System.currentTimeMillis()),
            ProfileTitle(2, null, "Dropped", 2021, "movie", false, 2.0, System.currentTimeMillis(), "dropped"),
        )
        val pool = listOf(
            AiService.Candidate(
                9, "Pool Title", "2024", 8.1, "Drama, Thriller", "hi",
                "A slow-burn family drama set in Mumbai.", 1200,
            ),
        )
        val taste = AiService.Taste(
            genres = listOf("Drama", "Thriller"),
            languages = listOf("hi"),
            era = 2019,
            avoidGenres = listOf("Horror"),
        )
        val prompt = AiService.buildPrompt(watched, pool, 10, taste)
        assertTrue(prompt.contains("A slow-burn family drama"))
        assertTrue(prompt.contains("Preferred genres: Drama, Thriller"))
        assertTrue(prompt.contains("Preferred original languages: hi"))
        assertTrue(prompt.contains("around 2019"))
        assertTrue(prompt.contains("Genres to avoid: Horror"))
        assertTrue(prompt.contains("dropped"))
        assertTrue(prompt.contains("up to 10"))
    }

    @Test
    fun extract_ids_reads_json_array_from_prose() {
        assertEquals(listOf(5, 9), AiService.extractIds("Sure: [5, 9] as requested"))
        assertEquals(listOf(3, 4, 7), AiService.extractIds("[3, 4, \"7\", 3]"))
        assertEquals(
            listOf(5, 9),
            AiService.extractIds("""The best matches are [{"id":5},{"id":9}] for this profile."""),
        )
        assertEquals(
            listOf(5),
            AiService.extractIds("""[{"id":5,"genres":[1,2]}]"""),
        )
        assertTrue(AiService.extractIds("no array here").isEmpty())
        assertTrue(AiService.extractIds("[").isEmpty())
        assertTrue(AiService.extractIds("[0, -2]").isEmpty())
    }


    @Test
    fun extract_text_reads_both_provider_shapes() {
        val openAi = """{"choices":[{"message":{"content":"[1,2]"}}]}"""
        assertEquals("[1,2]", AiService.extractText(openAi, gemini = false))
        val parts = """{"choices":[{"message":{"content":[{"text":"[7]"}]}}]}"""
        assertEquals("[7]", AiService.extractText(parts, gemini = false))
        val gemini = """{"candidates":[{"content":{"parts":[{"text":"[{\"id\":8}]"}]}}]}"""
        assertEquals("[{\"id\":8}]", AiService.extractText(gemini, gemini = true))
        val reasoning = """{"choices":[{"message":{"content":null,"reasoning":"[4]"}}]}"""
        assertEquals("[4]", AiService.extractText(reasoning, gemini = false))
        assertNull(AiService.extractText("""{"choices":[]}""", gemini = false))
        assertNull(AiService.extractText("junk", gemini = true))
    }

    @Test
    fun model_lists_parse_in_both_shapes() {
        val openAi = """{"data":[{"id":"m-a"},{"id":"m-b"},{"id":"m-a"}]}"""
        assertEquals(listOf("m-a", "m-b"), AiService.parseModels(openAi, gemini = false))
        val gemini = """{"models":[{"name":"models/gemini-x"},{"name":"models/gemini-y"}]}"""
        assertEquals(listOf("gemini-x", "gemini-y"), AiService.parseModels(gemini, gemini = true))
        assertTrue(AiService.parseModels("""{"other":[]}""", gemini = false).isEmpty())
        assertTrue(AiService.parseModels("junk", gemini = true).isEmpty())
    }


    private fun item(id: Int, genreIds: List<Int>, rating: Double, popularity: Double) =
        TmdbService.TmdbItem(id, null, "movie", "t$id", "2020", null, rating, genreIds, popularity)

    @Test
    fun genre_fit_and_seed_hits_beata_bare_high_rating() {
        val weights = mapOf(28 to 3.0)
        val fitted = item(1, listOf(28), 0.0, 0.0)
        val rated = item(2, emptyList(), 9.0, 0.0)
        assertTrue(ForYouEngine.score(fitted, weights, 0) > ForYouEngine.score(rated, weights, 0))
        val pushed = item(3, emptyList(), 0.0, 0.0)
        assertTrue(ForYouEngine.score(pushed, weights, 1) > ForYouEngine.score(rated, weights, 0))
        assertTrue(ForYouEngine.score(item(4, emptyList(), 0.0, 300.0), weights, 0) >
            ForYouEngine.score(item(5, emptyList(), 0.0, 0.0), weights, 0))
    }

    @Test
    fun unknown_genres_contribute_nothing() {
        assertEquals(
            ForYouEngine.score(item(6, listOf(999), 5.0, 0.0), mapOf(28 to 5.0), 0),
            ForYouEngine.score(item(6, emptyList(), 5.0, 0.0), mapOf(28 to 5.0), 0),
        )
    }

    @Test
    fun disliked_genres_and_languages_change_the_score() {
        val plain = ForYouEngine.Taste(genres = mapOf(28 to 2.0))
        val disliked = ForYouEngine.Taste(genres = mapOf(28 to 2.0), dislikes = mapOf(27 to 1.0))
        val horror = item(10, listOf(27), 8.0, 10.0)
        val action = item(11, listOf(28), 8.0, 10.0)
        assertTrue(ForYouEngine.score(horror, plain, 0) > ForYouEngine.score(horror, disliked, 0))
        assertEquals(ForYouEngine.score(action, plain, 0), ForYouEngine.score(action, disliked, 0))

        val taste = ForYouEngine.Taste(languages = mapOf("hi" to 1.0), era = 2020)
        val hindi = item(12, emptyList(), 7.0, 0.0).copy(originalLanguage = "hi", year = "2020")
        val english = item(13, emptyList(), 7.0, 0.0).copy(originalLanguage = "en", year = "1995")
        assertTrue(ForYouEngine.score(hindi, taste, 0) > ForYouEngine.score(english, taste, 0))
    }

    @Test
    fun diversify_breaks_up_near_duplicates() {
        val first = item(20, listOf(28), 8.0, 0.0).copy(originalLanguage = "en", year = "2020")
        val duplicate = item(21, listOf(28), 8.0, 0.0).copy(originalLanguage = "en", year = "2020")
        val different = item(22, listOf(35), 8.0, 0.0).copy(originalLanguage = "hi", year = "1995")
        val picked = ForYouEngine.diversify(
            listOf(first, duplicate, different),
            2,
            mapOf(20 to 1.0, 21 to 0.5, 22 to 0.45),
        )
        assertEquals(listOf(20, 22), picked.map { it.tmdbId })
    }
}
