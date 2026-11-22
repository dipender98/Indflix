package Test

import com.lagradost.cloudstream3.app
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import java.io.File

class VidriftLiveTest {
    @Test
    fun liveProbe() = runBlocking {
        val out = StringBuilder()
        val base = "https://embed.vidrift.net"
        val pageHeaders = mapOf("Accept" to "*/*", "User-Agent" to "")

        val page = app.get("$base/embed/movie/27205", timeout = 12, headers = pageHeaders)
        val html = page.text
        out.append("page status=${page.code} len=${html.length} meta=${html.contains("var embedMeta")}\n")

        val mi = html.indexOf("var embedMeta")
        val brace = html.indexOf('{', mi)
        var depth = 0
        var end = -1
        for (i in brace until html.length) {
            when (html[i]) {
                '{' -> depth++
                '}' -> { depth--; if (depth == 0) { end = i + 1; break } }
            }
        }
        val token = org.json.JSONObject(html.substring(brace, end)).optString("playbackToken")
        out.append("token=${token.isNotBlank()} len=${token.length}\n")

        val api = app.get(
            "$base/api/source/movie/27205?token=${java.net.URLEncoder.encode(token, "UTF-8")}&provider=titan&parent=",
            timeout = 12,
            headers = mapOf("Accept" to "application/json", "User-Agent" to ""),
        )
        val apiText = api.text
        out.append("api status=${api.code} json=${apiText.trim().startsWith("{")} len=${apiText.length}\n")
        if (apiText.trim().startsWith("{")) {
            val jo = org.json.JSONObject(apiText)
            out.append("streams=${jo.optJSONArray("streams")?.length() ?: -1}\n")
            val s0 = jo.optJSONArray("streams")?.optJSONObject(0)
            out.append("first=${s0?.optString("url")?.take(70) ?: "none"}\n")
        }

        val dest = File("F:/Project/Indflix/build-logs/vidrift_live.txt")
        dest.parentFile.mkdirs()
        dest.writeText(out.toString())
        check(html.contains("var embedMeta") && token.isNotBlank())
    }
}