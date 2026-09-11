package com.cinevood

import com.lagradost.cloudstream3.app
import org.json.JSONArray
import org.json.JSONObject
import org.jsoup.Jsoup
import java.net.URLEncoder

/*
 * The wp-json REST client — this is the site's real search & catalog, so
 * results come back as compact JSON with no HTML round-trips. Every call
 * retries across mirrors before giving up; HTML search is a last resort.
 */

class PostListItem(
    val id: Int,
    val title: String,
    val url: String,
    val categories: List<Int>,
    val featuredMedia: Int,
    var poster: String? = null
)

class PostFull(
    val id: Int,
    val title: String,
    val url: String,
    val categories: List<Int>,
    val content: String,
    val excerpt: String,
    val poster: String?
)

class CategoryInfo(val id: Int, val slug: String, val name: String, val count: Int)

class SiteApi {

    @Volatile
    var base: String = DomainResolver.SEED_MIRRORS.first()

    private data class CachedPost(val at: Long, val post: PostFull)
    private val postCache = LinkedHashMap<String, CachedPost>()
    val allCategories = LinkedHashMap<String, CategoryInfo>()
    private val extraMirrors = LinkedHashSet<String>()

    // ---------------------------------------------------------------- core

    private suspend fun getText(path: String, referer: String? = null): Pair<Int, String>? =
        try {
            val res = app.get(
                base + path,
                headers = SharedServices.browserHeaders(referer),
                interceptor = CfHolder.killer,
                timeout = 15
            )
            res.code to res.text
        } catch (e: Exception) {
            null
        }

    /**
     * Run [call] against the current mirror; on failure/4xx rotate to the next
     * healthy mirror (including banner-discovered domains) and retry.
     */
    suspend fun <T> attempt(name: String = "call", call: suspend () -> T): T {
        ensureHealthy()
        var lastError: Exception? = null
        repeat(DomainResolver.SEED_MIRRORS.size + extraMirrors.size + 1) {
            try {
                return call()
            } catch (e: Exception) {
                lastError = e
                rotateMirror()
            }
        }
        throw lastError ?: Exception("CineVood: $name failed on all mirrors")
    }

    private fun rotateMirror() {
        val order = LinkedHashSet(DomainResolver.SEED_MIRRORS + extraMirrors)
        val it = order.iterator()
        var found = false
        while (it.hasNext()) {
            val next = it.next()
            if (!found) {
                if (next == base) found = true
                continue
            }
            base = next
            return
        }
        base = order.first()
    }

    /** Move to the next mirror seed (used by the provider's retry loop). */
    fun rotate() = rotateMirror()

    fun slugOf(id: Int): String? =
        allCategories.entries.firstOrNull { it.value.id == id }?.key

    suspend fun ensureHealthy() {
        if (probe(base)) return
        for (candidate in DomainResolver.SEED_MIRRORS + extraMirrors.toList()) {
            if (candidate != base && probe(candidate)) {
                SharedServices.diag("MIRROR failover $base -> $candidate")
                base = candidate
                return
            }
        }
        SharedServices.diag("MIRROR all seeds unhealthy, keeping $base")
    }

    private suspend fun probe(url: String): Boolean = try {
        val res = app.get(
            "$url/wp-json/",
            headers = SharedServices.browserHeaders(json = true),
            interceptor = CfHolder.killer,
            timeout = 8
        )
        res.code == 200
    } catch (e: Exception) {
        false
    }

    /** Discover new TLDs announced in the site banner (keeps seeds fresh). */
    suspend fun learnMirrors() {
        try {
            getText("/")?.second?.let { html ->
                DomainResolver.bannerDomains(html).forEach { extraMirrors.add(it) }
            }
        } catch (_: Exception) {
        }
    }

    private suspend fun getJson(path: String): JSONArray? {
        val (code, text) = getText(path) ?: throw java.io.IOException("CineVood fetch failed $path")
        if (code != 200) throw java.io.IOException("CineVood HTTP $code $path")
        return if (text.trimStart().startsWith("[")) JSONArray(text) else JSONArray().put(JSONObject(text))
    }

    // ---------------------------------------------------------------- posts

    suspend fun postsSearch(query: String, page: Int): List<PostListItem> {
        val q = URLEncoder.encode(query, "UTF-8").replace("+", "%20")
        return postsList(
            "/wp-json/wp/v2/posts?search=$q&page=$page&per_page=20&_fields=id,link,title,categories,featured_media"
        )
    }

    suspend fun postsCategory(catId: Int, page: Int): List<PostListItem> = postsList(
        "/wp-json/wp/v2/posts?categories=$catId&page=$page&per_page=20&_fields=id,link,title,categories,featured_media"
    )

    suspend fun postsLatest(page: Int): List<PostListItem> = postsList(
        "/wp-json/wp/v2/posts?page=$page&per_page=20&_fields=id,link,title,categories,featured_media"
    )

    private suspend fun postsList(path: String): List<PostListItem> {
        val arr = getJson(path) ?: return emptyList()
        val out = ArrayList<PostListItem>(arr.length())
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            val cats = ArrayList<Int>()
            o.optJSONArray("categories")?.let { for (j in 0 until it.length()) cats.add(it.getInt(j)) }
            out.add(
                PostListItem(
                    id = o.getInt("id"),
                    title = decode(o.getJSONObject("title").getString("rendered")),
                    url = o.getString("link"),
                    categories = cats,
                    featuredMedia = o.optInt("featured_media")
                )
            )
        }
        attachPosters(out)
        return out
    }

    /** One batched wp-json call for every list poster. */
    private suspend fun attachPosters(items: List<PostListItem>) {
        val ids = items.map { it.featuredMedia }.filter { it > 0 }.distinct()
        if (ids.isEmpty()) return
        try {
            val arr = getJson(
                "/wp-json/wp/v2/media?include=${ids.joinToString(",")}&per_page=${ids.size}&_fields=id,source_url"
            ) ?: return
            val map = LinkedHashMap<Int, String>()
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                map[o.getInt("id")] = o.optString("source_url")
            }
            items.forEach { it.poster = map[it.featuredMedia] }
        } catch (_: Exception) {
        }
    }

    suspend fun postByLink(postUrl: String): PostFull {
        val slug = postUrl.trimEnd('/').substringAfterLast('/')
        val cacheKey = "$slug@$base"
        postCache[cacheKey]?.let {
            if (System.currentTimeMillis() - it.at < POST_TTL_MS) return it.post
        }
        val arr = getJson(
            "/wp-json/wp/v2/posts?slug=$slug&_embed"
        )
        if (arr == null || arr.length() == 0) {
            throw java.io.IOException("CineVood: post not found ($slug)")
        }
        val o = arr.getJSONObject(0)
        val cats = ArrayList<Int>()
        o.optJSONArray("categories")?.let { for (j in 0 until it.length()) cats.add(it.getInt(j)) }
        var poster: String? = null
        o.optJSONObject("_embedded")?.optJSONArray("wp:featuredmedia")?.let { media ->
            if (media.length() > 0) poster = media.optJSONObject(0)?.optString("source_url")
                ?.takeIf { it.isNotBlank() }
        }
        val post = PostFull(
            id = o.getInt("id"),
            title = decode(o.getJSONObject("title").getString("rendered")),
            url = o.getString("link"),
            categories = cats,
            content = o.getJSONObject("content").getString("rendered"),
            excerpt = decode(o.getJSONObject("excerpt").optString("rendered")),
            poster = poster
        )
        if (postCache.size > 40) postCache.clear()
        postCache[cacheKey] = CachedPost(System.currentTimeMillis(), post)
        return post
    }

    // ---------------------------------------------------------- categories

    suspend fun ensureCategories(): Map<String, CategoryInfo> {
        if (allCategories.isNotEmpty()) return allCategories
        attempt("categories") {
            for (page in 1..3) {
                val arr = getJson(
                    "/wp-json/wp/v2/categories?per_page=100&page=$page" +
                        "&_fields=id,slug,name,count&orderby=count&order=desc"
                ) ?: break
                if (arr.length() == 0) break
                for (i in 0 until arr.length()) {
                    val o = arr.getJSONObject(i)
                    allCategories[o.getString("slug")] = CategoryInfo(
                        o.getInt("id"), o.getString("slug"), o.getString("name"), o.optInt("count")
                    )
                }
            }
            if (allCategories.isEmpty()) throw java.io.IOException("no categories")
            Unit
        }
        return allCategories
    }

    fun categoryIdBySlug(slug: String): Int? = allCategories[slug]?.id

    // ------------------------------------------------------------ fallback

    /** HTML search fallback (uses the site's real ?s= page) when JSON fails. */
    suspend fun searchHtmlFallback(query: String, page: Int): List<PostListItem> {
        val q = URLEncoder.encode(query, "UTF-8").replace("+", "%20")
        // WP search pagination is ?paged=N (page= is ignored by WP search)
        val paging = if (page > 1) "&paged=$page" else ""
        val (code, html) = getText("/?s=$q$paging") ?: throw java.io.IOException("no html search")
        if (code != 200) throw java.io.IOException("html search $code")
        val doc = Jsoup.parse(html)
        val out = LinkedHashMap<String, PostListItem>()
        for (a in doc.select("article a[href], h2 a[href], h3 a[href]")) {
            val href = a.absUrl("href").ifBlank { a.attr("href") }
            if (!href.startsWith(base) && !DomainResolver.isNetworkHost(href)) continue
            val path = href.substringAfter("://").substringAfter("/")
            val seg = path.trimEnd('/').split("/")
            if (seg.size > 2 || seg.firstOrNull() == "category" || path.isEmpty()) continue
            val title = a.text().trim()
            if (title.length < 4) continue
            out.putIfAbsent(href, PostListItem(title.hashCode(), title, href, emptyList(), 0))
        }
        return out.values.toList()
    }

    companion object {
        const val POST_TTL_MS = 15L * 60 * 1000

        fun decode(s: String): String = Jsoup.parse(s).text()
    }
}
