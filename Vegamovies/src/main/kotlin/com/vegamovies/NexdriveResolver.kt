package com.vegamovies

import com.lagradost.cloudstream3.app
import java.net.URLDecoder
import java.util.concurrent.ConcurrentHashMap

internal data class Concrete(val kind: String, val url: String, val idx: Int)

internal data class GatewayExpansion(
    val links: List<Concrete>,
    val pageTitle: String?,
) {
    companion object {
        val EMPTY = GatewayExpansion(emptyList(), null)
    }

    val episodeCount: Int
        get() = links.groupingBy { it.kind }.eachCount().values.maxOrNull() ?: 0
}

internal object NexdriveResolver {

    val DIRECT_REGEX = Regex("""https://video-downloads\.googleusercontent\.com/\S+""")

    val FASTDL_REGEX = Regex(
        """https?://fastdl\.[a-z]{2,10}/embed(?:\.php)?\?download=[A-Za-z0-9_\-]+""",
        RegexOption.IGNORE_CASE,
    )

    val VCLOUD_REGEX = Regex(
        """https?://vcloud\.[a-z]{2,10}/[A-Za-z0-9_\-]{6,}""",
    )

    internal val REURL_REGEX = Regex(
        """https?://fastdl\.[a-z]{2,10}/dl\.php\?link=(https?://[^\s"'<>)\\]+)""",
        RegexOption.IGNORE_CASE,
    )

    internal val REURL_PARAM = Regex("""dl\.php\?link=([^"'<>\s\\]+)""", RegexOption.IGNORE_CASE)

    suspend fun expand(
        gatewayUrl: String,
        pageReferer: String,
        headers: Map<String, String>,
    ): GatewayExpansion {
        cache[gatewayUrl]?.let { return it }
        var html: String? = null
        for (attempt in 1..2) {
            html = runCatching {
                app.get(
                    gatewayUrl,
                    timeout = if (attempt == 1) 15 else 25,
                    headers = headers + mapOf(
                        "Referer" to pageReferer,
                        "Accept" to "text/html,application/xhtml+xml",
                    ),
                ).text
            }.getOrNull()
            if (!html.isNullOrBlank()) break
        }
        if (html.isNullOrBlank()) return GatewayExpansion.EMPTY

        val title = Regex("""<h1[^>]*>([^<]{1,260})""", RegexOption.IGNORE_CASE)
            .find(html)?.groupValues?.get(1)?.trim()

        val links = ArrayList<Concrete>(16)
        FASTDL_REGEX.findAll(html).forEachIndexed { i, m -> links += Concrete(Servers.GDRIVE, m.value, i) }
        VCLOUD_REGEX.findAll(html).forEachIndexed { i, m -> links += Concrete(Servers.VCLOUD, m.value, i) }
        val result = GatewayExpansion(links, title)
        if (result.links.isNotEmpty() && cache.size < 96) cache[gatewayUrl] = result
        return result
    }

    suspend fun resolveEmbed(
        embedUrl: String,
        referer: String,
        headers: Map<String, String>,
    ): String? {
        directCache[embedUrl]?.let { return it.ifEmpty { null } }
        var body: String? = null
        for (attempt in 1..2) {
            body = runCatching {
                app.get(
                    embedUrl,
                    timeout = if (attempt == 1) 12 else 20,
                    headers = headers + mapOf(
                        "Referer" to referer,
                        "Accept" to "text/html,application/xhtml+xml",
                    ),
                ).text
            }.getOrNull()
            if (body?.let { extractDirect(it) } != null) break
        }
        val direct = body?.let { extractDirect(it) }
        if (directCache.size < 96) directCache[embedUrl] = direct ?: ""
        return direct
    }

    internal fun extractDirect(body: String): String? {
        REURL_REGEX.find(body)?.groupValues?.get(1)?.let { cand ->
            DIRECT_REGEX.find(cand)?.value?.let { return it }
        }
        REURL_PARAM.findAll(body).forEach { m ->
            val raw = m.groupValues[1]
            DIRECT_REGEX.find(raw)?.value?.let { return it }
            runCatching { URLDecoder.decode(raw, "UTF-8") }.getOrNull()
                ?.let { DIRECT_REGEX.find(it)?.value }?.let { return it }
        }
        return DIRECT_REGEX.find(body)?.value
    }

    private val cache = java.util.concurrent.ConcurrentHashMap<String, GatewayExpansion>()

    private val directCache = java.util.concurrent.ConcurrentHashMap<String, String>()
}
