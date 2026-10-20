package Test

import com.indstream.TmdbService
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class GenreRowsTest {

    private val tvGenres = setOf(
        16, 35, 80, 99, 18, 10751, 10759, 10762, 9648, 10763, 10764, 10765, 10766, 10767, 10768, 37,
    )

    private val movieGenres = setOf(28, 12, 16, 35, 80, 99, 18, 10751, 14, 36, 27, 10402, 9648, 10749, 878, 10770, 53, 107, 37)

    @Test
    fun every_genre_id_is_valid_for_its_type() {
        TmdbService.genres.forEach { genre ->
            assertTrue(genre.movieId in movieGenres, "${genre.label} film id")
            assertTrue(genre.tvId == 0 || genre.tvId in tvGenres, "${genre.label} series id")
        }
    }

    @Test
    fun rows_cover_both_types_with_unique_keys() {
        val rows = TmdbService.genreRows()
        val keys = rows.map { it.first }
        assertEquals(keys.size, keys.toSet().size)
        assertTrue(rows.any { it.first.endsWith("|movie") })
        assertTrue(rows.any { it.first.endsWith("|tv") })
        assertTrue(rows.all { it.second.isNotBlank() })
    }

    @Test
    fun a_key_round_trips_through_the_parser() {
        TmdbService.genres.forEach { genre ->
            listOf("movie", "tv").filter { TmdbService.genreId(genre, it) > 0 }.forEach { type ->
                val parsed = TmdbService.parseGenreRow(TmdbService.genreRowKey(genre, type))
                assertNotNull(parsed)
                assertEquals(TmdbService.genreId(genre, type), parsed.first)
                assertEquals(type, parsed.second)
            }
        }
    }

    @Test
    fun other_row_keys_are_not_mistaken_for_genres() {
        assertNull(TmdbService.parseGenreRow("trending|movie"))
        assertNull(TmdbService.parseGenreRow("foryou|tv"))
        assertNull(TmdbService.parseGenreRow("genre|abc|movie"))
        assertNull(TmdbService.parseGenreRow("genre|0|movie"))
        assertNull(TmdbService.parseGenreRow(""))
    }
}
