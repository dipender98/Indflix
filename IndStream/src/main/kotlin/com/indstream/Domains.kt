package com.indstream

import com.lagradost.cloudstream3.app
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap

object Domains {
    private const val PRIMARY = "https://raw.githubusercontent.com/phisher98/TVVVV/refs/heads/main/domains.json"
    private const val SECONDARY = "https://raw.githubusercontent.com/SaurabhKaperwan/Utils/refs/heads/main/urls.json"
    private const val TTL_MS = 60 * 60 * 1000L

    private val map = ConcurrentHashMap<String, String>()
    @Volatile private var fetchedAt = 0L

    suspend fun base(key: String): String? {
        refresh()
        return map[key.lowercase()]
    }

    private suspend fun refresh() {
        val now = System.currentTimeMillis()
        if (map.isNotEmpty() && now - fetchedAt < TTL_MS) return
        var loaded = false
        for (url in listOf(PRIMARY, SECONDARY)) {
            val body = try {
                app.get(url, timeout = 10, headers = HttpKit.commonHeaders).text
            } catch (c: kotlinx.coroutines.CancellationException) {
                throw c
            } catch (t: Throwable) {
                null
            } ?: continue
            val root = try {
                JSONObject(body)
            } catch (t: Throwable) {
                null
            } ?: continue
            val keys = root.keys()
            while (keys.hasNext()) {
                val name = keys.next()
                val value = root.optString(name).trim()
                if (value.startsWith("http")) map[name.lowercase()] = value.trimEnd('/')
            }
            loaded = true
        }
        if (loaded) fetchedAt = System.currentTimeMillis()
    }
}
