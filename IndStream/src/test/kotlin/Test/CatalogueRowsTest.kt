package Test

import com.indstream.Catalogue
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CatalogueRowsTest {

    private val tvGenres = setOf(
        16, 35, 80, 99, 18, 10751, 10759, 10762, 9648, 10763, 10764, 10765, 10766, 10767, 10768, 37, 10749,
    )

    private val movieGenres = setOf(
        28, 12, 16, 35, 80, 99, 18, 10751, 14, 36, 27, 10402, 9648, 10749, 878, 10770, 53, 107, 37,
    )

    @Test
    fun every_genre_id_is_valid_for_its_type() {
        Catalogue.categories.forEach { category ->
            val source = category.source as? Catalogue.Source.Genre ?: return@forEach
            assertTrue(
                source.movieIds.isNotEmpty() || source.movieKeywords.isNotEmpty(),
                "${category.label} film filter",
            )
            assertTrue(
                source.tvIds.isNotEmpty() || source.tvKeywords.isNotEmpty(),
                "${category.label} series filter",
            )
            source.movieIds.forEach { assertTrue(it in movieGenres, "${category.label} film id $it") }
            source.tvIds.forEach { assertTrue(it in tvGenres, "${category.label} series id $it") }
            (source.movieKeywords + source.tvKeywords).forEach {
                assertTrue(it > 0, "${category.label} keyword $it")
            }
        }
    }

    @Test
    fun groups_have_the_expected_number_of_categories() {
        Catalogue.Group.entries.forEach { group ->
            val categories = Catalogue.categories.filter { it.group == group }
            assertEquals(7, categories.size, group.label)
            assertEquals(categories.size, categories.map { it.id }.toSet().size, group.label)
            assertEquals(14, Catalogue.rowsFor(group).size, group.label)
        }
        assertEquals(21, Catalogue.categories.size)
    }

    @Test
    fun every_category_has_a_movie_and_series_row_with_a_unique_key() {
        val rows = Catalogue.allRows()
        assertEquals(Catalogue.categories.size * 2, rows.size)
        assertEquals(rows.size, rows.map { it.first }.toSet().size)
        assertTrue(rows.all { it.first.contains("|movie") || it.first.contains("|tv") })
        assertTrue(rows.all { it.second.isNotBlank() })
    }

    @Test
    fun rows_are_grouped_in_the_row_title() {
        val rows = Catalogue.allRows().map { it.second }
        assertEquals(14, rows.count { it.startsWith("Global: ") })
        assertEquals(14, rows.count { it.startsWith("Pan India: ") })
        listOf("Hindi", "Tamil", "Telugu", "Malayalam", "Kannada", "Gujarati", "Bengali").forEach { language ->
            assertTrue(rows.any { it.startsWith("$language: Trending ") }, language)
        }
    }

    @Test
    fun a_key_round_trips_through_the_parser() {
        Catalogue.categories.forEach { category ->
            listOf("movie", "tv").forEach { type ->
                val parsed = Catalogue.parseRow(Catalogue.rowKey(category, type))
                assertNotNull(parsed)
                assertEquals(category, parsed.first)
                assertEquals(type, parsed.second)
            }
        }
    }

    @Test
    fun enabled_rows_default_to_all_rows() {
        assertEquals(Catalogue.allRows(), Catalogue.enabledRows())
    }

    @Test
    fun a_saved_order_moves_rows_to_the_front_and_keeps_the_rest_stable() {
        val rows = Catalogue.allRows()
        val first = rows[5].first
        val second = rows[9].first
        val arranged = Catalogue.arrange(rows, listOf(second, first))
        assertEquals(second, arranged[0].first)
        assertEquals(first, arranged[1].first)
        val untouched = rows.filterNot { it.first == first || it.first == second }.map { it.first }
        assertEquals(untouched, arranged.drop(2).map { it.first })
    }

    @Test
    fun a_saved_order_ignores_duplicates_and_unknown_keys() {
        val rows = Catalogue.allRows()
        val key = rows[3].first
        val arranged = Catalogue.arrange(rows, listOf(key, key, "cat|missing|movie"))
        assertEquals(rows.size, arranged.size)
        assertEquals(rows.map { it.first }.toSet(), arranged.map { it.first }.toSet())
        assertEquals(key, arranged.first().first)
    }

    @Test
    fun an_empty_saved_order_keeps_the_default_order() {
        val rows = Catalogue.allRows()
        assertEquals(rows, Catalogue.arrange(rows, emptyList()))
        assertEquals(rows, Catalogue.orderedRows())
    }

    @Test
    fun hidden_rows_keep_their_previous_slots_when_the_visible_order_changes() {
        val movie = Catalogue.forYouRowKeys[0]
        val series = Catalogue.forYouRowKeys[1]
        assertEquals(
            listOf(movie, series, "b", "a"),
            Catalogue.mergeOrder(
                visible = listOf("b", "a"),
                previous = listOf(movie, series, "a", "b"),
                hidden = setOf(movie, series),
            ),
        )
        assertEquals(
            listOf("b", movie, "a", series),
            Catalogue.mergeOrder(
                visible = listOf("b", "a"),
                previous = listOf("a", movie, "b", series),
                hidden = setOf(movie, series),
            ),
        )
        assertEquals(
            listOf("b", "a"),
            Catalogue.mergeOrder(listOf("b", "a"), emptyList(), emptySet()),
        )
    }

    @Test
    fun other_row_keys_are_not_mistaken_for_catalogue_rows() {
        assertNull(Catalogue.parseRow("trending|movie"))
        assertNull(Catalogue.parseRow("foryou|tv"))
        assertNull(Catalogue.parseRow("cat|missing|movie"))
        assertNull(Catalogue.parseRow("cat|global.trending|episode"))
        assertNull(Catalogue.parseRow(""))
    }

    @Test
    fun providers_have_distinct_ids_and_default_to_tmdb() {
        val ids = Catalogue.Provider.entries.map { it.id }
        assertEquals(ids.size, ids.toSet().size)
        assertEquals(Catalogue.Provider.PREFERRED, Catalogue.Provider.TMDB.id)
        Catalogue.Provider.entries.forEach { provider ->
            assertEquals(provider, Catalogue.Provider.from(provider.id))
            assertEquals(provider, Catalogue.Provider.from(" ${provider.id.uppercase()} "))
        }
        assertEquals(Catalogue.Provider.TMDB, Catalogue.Provider.from(null))
        assertEquals(Catalogue.Provider.TMDB, Catalogue.Provider.from(""))
        assertEquals(Catalogue.Provider.TMDB, Catalogue.Provider.from("unknown"))
    }

    @Test
    fun genre_rows_expose_a_cinemeta_genre() {
        Catalogue.categories.forEach { category ->
            if (category.source is Catalogue.Source.Genre) {
                assertNotNull(category.cinemetaGenre, category.label)
            }
        }
    }
}
