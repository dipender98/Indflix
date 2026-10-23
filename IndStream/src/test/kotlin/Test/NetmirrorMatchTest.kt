package Test

import com.indstream.StreamEngine

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class NetmirrorMatchTest {

    @Test
    fun exact_titles_rank_best() {
        assertEquals(0, StreamEngine.newtvTitleRank("Inception", "Inception"))
        assertEquals(0, StreamEngine.newtvTitleRank("Kalki 2898 AD", "Kalki 2898 AD (Hindi)"))
        assertEquals(0, StreamEngine.newtvTitleRank("RRR", "RRR (Hindi)"))
        assertEquals(0, StreamEngine.newtvTitleRank("Stranger Things", "Stranger Things"))
    }

    @Test
    fun edition_suffixes_rank_by_extra_tokens() {
        assertEquals(2, StreamEngine.newtvTitleRank("Jawan", "Jawan: Extended Cut"))
        assertEquals(3, StreamEngine.newtvTitleRank("Jawan", "Jawan: Tamil Extended Cut"))
    }

    @Test
    fun unrelated_or_word_continuation_titles_are_rejected() {
        assertNull(StreamEngine.newtvTitleRank("Jawan", "Jawanum Mullappoovum"))
        assertNull(StreamEngine.newtvTitleRank("RRR", "Making of RRR"))
        assertNull(StreamEngine.newtvTitleRank("Jawan", "Hai Jawani Toh Ishq Hona Hai"))
        assertNull(StreamEngine.newtvTitleRank("Inception", "Inceptionism"))
    }

    @Test
    fun punctuation_and_case_do_not_matter() {
        assertEquals(0, StreamEngine.newtvTitleRank("Spider-Man: No Way Home", "spider man no way home"))
    }
}
