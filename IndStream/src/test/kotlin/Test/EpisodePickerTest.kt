package Test

import com.indstream.StreamEngine
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class EpisodePickerTest {

    @Test
    fun movieboxSeasonEnd_rangeParsesToLastNumber() {
        assertEquals(4, StreamEngine.movieboxSeasonEnd("Reacher [Hindi] S1-S4"))
        assertEquals(16, StreamEngine.movieboxSeasonEnd("Naruto: Shippuden [Tamil] S1-S16"))
        assertEquals(2, StreamEngine.movieboxSeasonEnd("The Gentlemen S1-S2"))
        assertEquals(3, StreamEngine.movieboxSeasonEnd("Show S3"))
        assertNull(StreamEngine.movieboxSeasonEnd("The Batman (2024)"))
        assertNull(StreamEngine.movieboxSeasonEnd("Reacher"))
    }
}
