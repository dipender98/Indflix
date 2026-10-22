package Test

import com.indstream.SearchRank
import com.indstream.TmdbService
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SearchRelevanceTest {

    private fun item(
        id: Int,
        name: String,
        original: String? = null,
        language: String = "hi",
        votes: Int = 1000,
        year: String? = null,
        type: String = "movie",
        country: List<String> = listOf("IN"),
    ) = TmdbService.TmdbItem(
        tmdbId = id,
        imdbId = null,
        type = type,
        name = name,
        year = year,
        poster = "poster.jpg",
        rating = 7.0,
        popularity = votes / 100.0,
        voteCount = votes,
        originalLanguage = language,
        overview = "overview",
        originalName = original,
        originCountry = country,
    )

    private fun noise() = listOf(
        item(100, "Dune", language = "en", votes = 20000, year = "2021"),
        item(101, "Inception", language = "en", votes = 35000, year = "2010"),
        item(102, "Avatar", language = "en", votes = 30000, year = "2009"),
        item(103, "Boss", votes = 12000, year = "2013"),
        item(104, "Big", votes = 9000, year = "2022"),
        item(105, "Master", language = "ta", votes = 15000, year = "2021"),
        item(106, "Jawan", votes = 25000, year = "2023"),
        item(107, "Pathaan", votes = 24000, year = "2023"),
        item(108, "Animal", votes = 22000, year = "2023"),
    )

    private fun catalogue(): List<TmdbService.TmdbItem> = noise() + listOf(
        item(1, "Bigg Boss", original = "बिग बॉस", votes = 4000, year = "2006", type = "series"),
        item(2, "Bigg Boss Marathi", language = "mr", votes = 900, year = "2018", type = "series"),
        item(3, "Bigg Boss Telugu", language = "te", votes = 1100, year = "2017", type = "series"),
        item(4, "Bigg Boss OTT", votes = 700, year = "2021", type = "series"),
        item(5, "Bigg Boss Kannada", language = "kn", votes = 800, year = "2013", type = "series"),
        item(6, "Baahubali: The Beginning", original = "బాహుబలి", language = "te", votes = 3000, year = "2015"),
        item(7, "Baahubali 2: The Conclusion", language = "te", votes = 3500, year = "2017"),
        item(8, "Bindiya Ke Bahubali", votes = 50, year = "2025", type = "series"),
        item(9, "K.G.F: Chapter 1", language = "kn", votes = 2000, year = "2018"),
        item(10, "K.G.F: Chapter 2", language = "kn", votes = 2800, year = "2022"),
        item(11, "RRR", language = "te", votes = 3200, year = "2022"),
        item(12, "Pushpa: The Rise", language = "te", votes = 2600, year = "2021"),
        item(13, "Mirzapur", votes = 1800, year = "2018", type = "series"),
        item(14, "Panchayat", votes = 1700, year = "2020", type = "series"),
        item(15, "Scam 1992: The Harshad Mehta Story", votes = 1600, year = "2020", type = "series"),
        item(16, "Scam 2003: The Telgi Story", votes = 900, year = "2023", type = "series"),
        item(17, "Asur: Welcome to Your Dark Side", votes = 1200, year = "2020", type = "series"),
        item(18, "Kabhi Khushi Kabhie Gham", votes = 2500, year = "2001"),
        item(19, "3 Idiots", votes = 3500, year = "2009"),
        item(20, "Dangal", votes = 3300, year = "2016"),
        item(21, "PK", votes = 3000, year = "2014"),
        item(22, "Bajrangi Bhaijaan", votes = 3100, year = "2015"),
        item(23, "Dhoom 3", votes = 2200, year = "2013"),
        item(24, "Shershaah", votes = 1900, year = "2021"),
        item(25, "Uri: The Surgical Strike", votes = 2100, year = "2019"),
        item(26, "Article 15", votes = 1500, year = "2019"),
        item(27, "Andhadhun", votes = 2000, year = "2018"),
        item(28, "Stree", votes = 1800, year = "2018"),
        item(29, "Tumbbad", votes = 1400, year = "2018"),
        item(30, "Gully Boy", votes = 1900, year = "2019"),
        item(31, "Queen", votes = 1700, year = "2014"),
        item(32, "Barfi!", votes = 1600, year = "2012"),
        item(33, "Lagaan: Once Upon a Time in India", votes = 2200, year = "2001"),
        item(34, "Swades", votes = 1500, year = "2004"),
        item(35, "Chak De! India", votes = 1800, year = "2007"),
    )

    private val golden = listOf(
        "bigg boss" to "Bigg Boss",
        "big boss" to "Bigg Boss",
        "बिग बॉस" to "Bigg Boss",
        "bigboss" to "Bigg Boss",
        "bigg boss hindi" to "Bigg Boss",
        "bigg boss marathi" to "Bigg Boss Marathi",
        "bigg boss telugu" to "Bigg Boss Telugu",
        "bigg boss ott" to "Bigg Boss OTT",
        "bigg boss kannada" to "Bigg Boss Kannada",
        "baahubali" to "Baahubali: The Beginning",
        "bahubali" to "Baahubali: The Beginning",
        "बाहुबली" to "Baahubali: The Beginning",
        "baahubali 2" to "Baahubali 2: The Conclusion",
        "kgf chapter 1" to "K.G.F: Chapter 1",
        "kgf chapter 2" to "K.G.F: Chapter 2",
        "rrr" to "RRR",
        "pushpa" to "Pushpa: The Rise",
        "mirzapur" to "Mirzapur",
        "panchayat" to "Panchayat",
        "scam 1992" to "Scam 1992: The Harshad Mehta Story",
        "scam 2003" to "Scam 2003: The Telgi Story",
        "asur" to "Asur: Welcome to Your Dark Side",
        "kabhi khushi" to "Kabhi Khushi Kabhie Gham",
        "3 idiots" to "3 Idiots",
        "dangal" to "Dangal",
        "pk" to "PK",
        "bajrangi bhaijaan" to "Bajrangi Bhaijaan",
        "dhoom 3" to "Dhoom 3",
        "shershah" to "Shershaah",
        "uri" to "Uri: The Surgical Strike",
        "article 15" to "Article 15",
        "andhadhun" to "Andhadhun",
        "stree" to "Stree",
        "tumbbad" to "Tumbbad",
        "gully boy" to "Gully Boy",
        "queen" to "Queen",
        "barfi" to "Barfi!",
        "lagaan" to "Lagaan: Once Upon a Time in India",
        "swades" to "Swades",
        "chak de india" to "Chak De! India",
    )

    @Test
    fun normalizesScriptsAndSpelling() {
        assertEquals("big bos", SearchRank.normalizeTitle("Bigg Boss"))
        assertEquals("big bos", SearchRank.normalizeTitle("बिग बॉस"))
        assertEquals(1.0, SearchRank.relevance("बिग बॉस", "Bigg Boss"))
        assertEquals(1.0, SearchRank.relevance("big boss", "Bigg Boss"))
    }

    @Test
    fun buildsCrossScriptRecallQueries() {
        val latin = SearchRank.recallQueries("bigg boss", 3)
        assertTrue(latin.any { it == "big boss" }, latin.toString())
        assertTrue(latin.any { it == "बिग बॉस" }, latin.toString())

        val hindi = SearchRank.recallQueries("बिग बॉस", 3)
        assertTrue(hindi.any { it == "big bos" }, hindi.toString())
    }

    @Test
    fun exactMatchOutranksFranchiseVariants() {
        val main = SearchRank.score("bigg boss", catalogue().first { it.name == "Bigg Boss" })
        val marathi = SearchRank.score("bigg boss", catalogue().first { it.name == "Bigg Boss Marathi" })
        val ott = SearchRank.score("bigg boss", catalogue().first { it.name == "Bigg Boss OTT" })
        assertEquals(100.0, main.tier)
        assertTrue(main.total > marathi.total)
        assertTrue(main.total > ott.total)
        assertTrue(main.explain().contains("match:+100"))
    }

    @Test
    fun goldenQueriesPutExpectedTitleFirst() {
        val misses = golden.mapNotNull { (query, expected) ->
            val top = SearchRank.rank(query, catalogue()).firstOrNull()?.name
            if (top != expected) "$query -> $top (want $expected)" else null
        }
        assertTrue(misses.isEmpty(), misses.joinToString("\n"))
    }

    @Test
    fun goldenQueriesPutExpectedTitleInTopFive() {
        val misses = golden.mapNotNull { (query, expected) ->
            val top = SearchRank.rank(query, catalogue()).take(5).map { it.name }
            if (expected in top) null else "$query -> $top (want $expected)"
        }
        assertTrue(misses.isEmpty(), misses.joinToString("\n"))
    }
}
