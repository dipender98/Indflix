package com.indstream

import com.lagradost.cloudstream3.app
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap

object Domains {
    private const val PRIMARY = "https://raw.githubusercontent.com/phisher98/TVVVV/refs/heads/main/domains.json"
    private const val SECONDARY = "https://raw.githubusercontent.com/SaurabhKaperwan/Utils/refs/heads/main/urls.json"
    private const val TTL_MS = 60 * 60 * 1000L
    private const val RETRY_MS = 60_000L

    private val map = ConcurrentHashMap<String, String>()
    private val refreshLock = Mutex()
    @Volatile private var fetchedAt = 0L
    @Volatile private var failedAt = 0L

    suspend fun base(key: String): String? {
        refresh()
        return map[key.lowercase()]
    }

    private suspend fun refresh() {
        val now = System.currentTimeMillis()
        if (now - fetchedAt < TTL_MS) return
        if (now - failedAt < RETRY_MS) return
        refreshLock.withLock {
            val lockedAt = System.currentTimeMillis()
            if (lockedAt - fetchedAt < TTL_MS) return
            if (lockedAt - failedAt < RETRY_MS) return
            var added = 0
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
                    if (value.startsWith("http")) {
                        map[name.lowercase()] = value.trimEnd('/')
                        added++
                    }
                }
            }
            if (added > 0) {
                fetchedAt = System.currentTimeMillis()
                failedAt = 0L
            } else {
                failedAt = System.currentTimeMillis()
            }
        }
    }
}
