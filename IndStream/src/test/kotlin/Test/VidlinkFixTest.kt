package Test

import com.indstream.StreamEngine
import com.lagradost.cloudstream3.app
import kotlinx.coroutines.runBlocking
import java.net.HttpURLConnection
import java.net.URL
import kotlin.test.Test

class VidlinkFixTest {

    private val browser = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
        "(KHTML, like Gecko) Chrome/138.0.0.0 Safari/537.36"

    private fun fetch(label: String, url: String, headers: Map<String, String>, range: Boolean = false) {
        try {
            val conn = URL(url).openConnection() as HttpURLConnection
            conn.instanceFollowRedirects = true
            conn.connectTimeout = 15000
            conn.readTimeout = 15000
            if (range) conn.setRequestProperty("Range", "bytes=0-2047")
            headers.forEach { (k, v) -> conn.setRequestProperty(k, v) }
            val code = conn.responseCode
            val type = conn.contentType.orEmpty()
            val disp = conn.getHeaderField("Content-Disposition").orEmpty()
            val body = runCatching {
                (if (code in 200..299) conn.inputStream else conn.errorStream)
                    ?.bufferedReader()?.readText()?.take(160)
            }.getOrDefault("").orEmpty()
            conn.disconnect()
            println("VL [$range] $code type=$type disp=$disp body=${body.replace("\n", " ").take(90)}")
        } catch (e: Exception) {
            println("VL ERR ${e.javaClass.simpleName}: ${e.message?.take(80)}")
        }
    }

    @Test
    fun vidlinkHeaderMatrix() = runBlocking {
        val api = StreamEngine.vidlinkMovieApiUrlAt("27205", StreamEngine.VIDLINK_TOKEN_OFFSETS.first())
        val resp = app.get(api, timeout = 10, headers = mapOf(
            "User-Agent" to browser,
            "Accept" to "*/*",
            "Origin" to "https://vidlink.pro",
            "Referer" to "https://vidlink.pro/movie/27205",
        ))
        println("API HTTP ${resp.code}")
        val root = runCatching { org.json.JSONObject(resp.text) }.getOrNull()
        val q = root?.optJSONObject("stream")?.optJSONObject("qualities")
        val names = q?.names()
        if (names == null) {
            println("API body=${resp.text.take(500)}")
            return@runBlocking
        }
        val key = names.optString(0)
        val q0 = (0 until names.length())
            .mapNotNull { i -> q.optJSONObject(names.optString(i)) }
            .firstOrNull()
        if (q0 == null) {
            println("no quality object")
            return@runBlocking
        }
        val url = q0.optString("url")
        println("URL=$url")
        println("Q-JSON=$q0")
        val apiH = StreamEngine.vidlinkPlaybackHeaders(q0)
        println("apiHeaders=$apiH")
        val combos = linkedMapOf(
            "browser+moviebox" to mapOf(
                "User-Agent" to browser,
                "Referer" to "https://movieboxonline.net/",
                "Origin" to "https://movieboxonline.net",
            ),
            "browser+filmboom" to mapOf(
                "User-Agent" to browser,
                "Referer" to "https://filmboom.top/",
                "Origin" to "https://filmboom.top",
            ),
            "exo+noref" to mapOf("User-Agent" to "ExoPlayer"),
        )
        combos.forEach { (name, h) ->
            println("-- $name")
            fetch(name, url, h, range = true)
        }
    }
}
