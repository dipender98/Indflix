package Test

import com.multimovies.GdMirrorProtocol
import org.json.JSONObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Guards GDMirror's pure wire logic: embed/vars/api parsing, mirror mapping, packed-player unpack. */
class GdMirrorTest {

    @Test
    fun parseEmbed_movie() {
        val e = GdMirrorProtocol.parseEmbed(
            "https://streams.iqsmartgames.com/embed/movie/tt1375666?key=abc123"
        )
        assertNotNull(e)
        assertEquals("movie", e.kind)
        assertEquals("tt1375666", e.id)
        assertEquals("abc123", e.key)
        assertNull(e.season)
    }

    @Test
    fun parseEmbed_tv() {
        val e = GdMirrorProtocol.parseEmbed("https://streams.iqsmartgames.com/embed/tv/1396/1/1?key=k")
        assertNotNull(e)
        assertEquals("tv", e.kind)
        assertEquals("1396", e.id)
        assertEquals("1", e.season)
        assertEquals("1", e.episode)
    }

    @Test
    fun parseEmbed_rejectsNonEmbed() {
        assertNull(GdMirrorProtocol.parseEmbed("https://hanerix.com/e/xyz"))
    }

    @Test
    fun parseVars_prefersPageVars_fallsBackToUrl() {
        val page = """
            let FinalID = "tt1375666";
            let idType = "imdbid";
            let myKey = "pagekey";
            let player_base  = "https://pro.iqsmartgames.com";
            let api_url  = "https://streams.iqsmartgames.com";
        """.trimIndent()
        val embed = GdMirrorProtocol.parseEmbed("https://streams.iqsmartgames.com/embed/movie/tt1375666?key=urlkey")!!
        val v = GdMirrorProtocol.parseVars(page, embed)
        assertEquals("tt1375666", v.id)
        assertEquals("imdbid", v.idType)
        assertEquals("pagekey", v.key)
        assertEquals("https://streams.iqsmartgames.com", v.api)
    }

    @Test
    fun parseVars_emptyPage_usesUrlFallbacks() {
        val embed = GdMirrorProtocol.parseEmbed("https://streams.iqsmartgames.com/embed/tv/1396/2/3?key=k")!!
        val v = GdMirrorProtocol.parseVars("", embed)
        assertEquals("1396", v.id)
        assertEquals("tmdbid", v.idType)
        assertEquals("k", v.key)
        assertEquals("2", v.season)
        assertEquals("3", v.epname)
    }

    @Test
    fun apiUrl_movieAndSeries() {
        val embed = GdMirrorProtocol.parseEmbed("https://streams.iqsmartgames.com/embed/movie/tt1375666?key=k")!!
        val movie = GdMirrorProtocol.parseVars("", embed)
        assertEquals(
            "https://streams.iqsmartgames.com/mymovieapi?imdbid=tt1375666&key=k",
            GdMirrorProtocol.apiUrl(movie),
        )
        val tvEmbed = GdMirrorProtocol.parseEmbed("https://streams.iqsmartgames.com/embed/tv/1396/1/1?key=k")!!
        val tv = GdMirrorProtocol.parseVars("", tvEmbed)
        assertEquals(
            "https://streams.iqsmartgames.com/myseriesapi?tmdbid=1396&season=1&epname=1&key=k",
            GdMirrorProtocol.apiUrl(tv),
        )
    }

    @Test
    fun parseFiles_dropsBlankSlugs() {
        val json = JSONObject("""{"success":true,"data":[
            {"filename":"Inception 2010 Bluray 1080P Hindi English","fileslug":"abt92w4"},
            {"filename":"junk","fileslug":""},
            {"filename":"nojunk"}
        ]}""")
        val files = GdMirrorProtocol.parseFiles(json)
        assertEquals(1, files.size)
        assertEquals("abt92w4", files[0].slug)
        assertTrue(GdMirrorProtocol.isHindiName(files[0].fileName))
    }

    @Test
    fun parseMirrors_mapsInOrder() {
        val mresult = java.util.Base64.getEncoder().encodeToString(
            """{"smwh":"code1","flls":"code2"}""".toByteArray()
        )
        val body = """{"sources":{
            "smwh":{"siteUrl":"https://hanerix.com/e/","friendlyName":"streamhg"},
            "flls":{"siteUrl":"https://smoothpre.com/v/","friendlyName":"earnvids"},
            "dead":{"siteUrl":"https://x.example/"}
        },"mresult":"$mresult"}"""
        val mirrors = GdMirrorProtocol.parseMirrors(body)
        // dead has no code -> dropped; order preserved.
        assertEquals(listOf("streamhg", "earnvids"), mirrors.map { it.site })
        assertEquals("https://hanerix.com/e/code1", mirrors[0].url)
        assertEquals("https://hanerix.com/e/", mirrors[0].base)
    }

    @Test
    fun deanUnpack_tinySample() {
        val block = "eval(function(p,a,c,k,e,d){while(c--)if(k[c])p=p.replace(" +
            "new RegExp('\\\\b'+c.toString(a)+'\\\\b','g'),k[c]);return p}" +
            "('3 0={\"1\":\"2\"}',10,4,'links|hls2|https://cdn/x/master.m3u8|var'.split('|')))"
        assertEquals(
            "var links={\"hls2\":\"https://cdn/x/master.m3u8\"}",
            GdMirrorProtocol.deanUnpack(block),
        )
    }

    @Test
    fun deanUnpack_garbageIsNull() {
        assertNull(GdMirrorProtocol.deanUnpack("no packed code here"))
    }

    @Test
    fun extractHls_prefersHls4ThenHls2() {
        val unpacked = "var links={\"hls4\":\"/stream/a/master.m3u8\"," +
            "\"hls2\":\"https://cdn.example/h/master.m3u8\"};"
        // Bypass packing: feed already-unpacked-looking page via eval wrapper is overkill;
        // assert the regex stage through a minimal packed block instead.
        val block = "eval(function(p,a,c,k,e,d){while(c--)if(k[c])p=p.replace(" +
            "new RegExp('\\\\b'+c.toString(a)+'\\\\b','g'),k[c]);return p}" +
            "('0',10,1,'$unpacked'.split('|')))"
        val out = GdMirrorProtocol.extractHls("<html>$block</html>", "https://hanerix.com/e/xyz")
        assertEquals(
            listOf(
                "https://hanerix.com/stream/a/master.m3u8",
                "https://cdn.example/h/master.m3u8",
            ),
            out,
        )
    }

    @Test
    fun unescapeJs_hexAndSlash() {
        assertEquals("https://x/y", GdMirrorProtocol.unescapeJs("https:\\/\\/x\\/y"))
        assertEquals("A", GdMirrorProtocol.unescapeJs("\\x41"))
    }
}
