package Test

import com.indstream.GenreCatalog
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Guards the genre rows that the home catalog exposes. */
class GenreCatalogTest {

    /** Genre ids the catalog API accepts for series. */
    private val tvGenres = setOf(
        16, 35, 80, 99, 18, 10751, 10759, 10762, 9648, 10763, 10764, 10765, 10766, 10767, 10768, 37,
    )

    /** Genre ids the catalog API accepts for films. */
    private val movieGenres = setOf(28, 12, 16, 35, 80, 99, 18, 10751, 14, 36, 27, 10402, 9648, 10749, 878, 10770, 53, 107, 37)

    @Test
    fun every_genre_id_is_valid_for_its_type() {
        GenreCatalog.genres.forEach { genre ->
            assertTrue(genre.movieId in movieGenres, "${genre.label} film id")
            assertTrue(genre.tvId == 0 || genre.tvId in tvGenres, "${genre.label} series id")
        }
    }

    @Test
    fun rows_cover_both_types_with_unique_keys() {
        val rows = GenreCatalog.rows()
        val keys = rows.map { it.first }
        assertEquals(keys.size, keys.toSet().size)
        assertTrue(rows.any { it.first.endsWith("|movie") })
        assertTrue(rows.any { it.first.endsWith("|tv") })
        assertTrue(rows.all { it.second.isNotBlank() })
    }

    @Test
    fun a_key_round_trips_through_the_parser() {
        GenreCatalog.genres.forEach { genre ->
            listOf("movie", "tv").filter { GenreCatalog.idFor(genre, it) > 0 }.forEach { type ->
                val parsed = GenreCatalog.parseRow(GenreCatalog.rowData(genre, type))
                assertNotNull(parsed)
                assertEquals(GenreCatalog.idFor(genre, type), parsed.first)
                assertEquals(type, parsed.second)
            }
        }
    }

    @Test
    fun other_row_keys_are_not_mistaken_for_genres() {
        assertNull(GenreCatalog.parseRow("trending|movie"))
        assertNull(GenreCatalog.parseRow("foryou|tv"))
        assertNull(GenreCatalog.parseRow("genre|abc|movie"))
        assertNull(GenreCatalog.parseRow("genre|0|movie"))
        assertNull(GenreCatalog.parseRow(""))
    }
}
