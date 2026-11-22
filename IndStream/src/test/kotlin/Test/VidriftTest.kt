package Test

import com.indstream.StreamEngine
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class VidriftTest {

    @Test
    fun vidrift_streams_pinnedConfig() {
        val html = "<script>var embedMeta = {\"tmdbId\":27205,\"provider\":\"orion\"," +
            "\"orionStreams\":[" +
            "{\"index\":0,\"url\":\"/api/orion/abc/master.m3u8\",\"type\":\"hls\",\"provider\":\"Orion\",\"name\":\"Orion A\u2022 Original\"}," +
            "{\"index\":1,\"url\":\"/api/orion/def/master.m3u8\",\"name\":\"Orion A\u2022 Hindi\"}" +
            "]};</script>"
        val out = StreamEngine.vidriftStreams(html)
        assertEquals(2, out.size)
        assertEquals(Triple("/api/orion/abc/master.m3u8", "Original", 0), out[0])
        assertEquals(Triple("/api/orion/def/master.m3u8", "Hindi", 0), out[1])
        assertTrue(StreamEngine.vidriftStreams("<html>no config</html>").isEmpty())
    }
}
