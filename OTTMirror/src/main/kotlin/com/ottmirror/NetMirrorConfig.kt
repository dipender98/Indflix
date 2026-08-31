package com.ottmirror

import java.util.concurrent.ConcurrentHashMap

enum class OttService(
    val id: String,
    val ottCookie: String,
    val mobilePrefix: String,
) {
    NETFLIX("netflix", "nf", ""),
    HOTSTAR("hotstar", "hs", "/hs"),
    PRIME("prime", "pv", "/pv"),
    DISNEY("disney", "dp", "/hs"),   // reference repo: ott=dp, Hotstar namespace
}

internal val VERIFY_HOSTS = listOf(
    "https://net52.cc",
    "https://net77.cc",
    "https://net22.cc",
    "https://net27.cc",
    "https://netmirror.gg",
)

internal val NEWTV_DOMAINS = listOf(
    "aHR0cHM6Ly9tb2JpbGVkZXRlY3RzLmNvbQ==",
    "aHR0cHM6Ly9tb2JpbGVkZXRlY3QuYXBw",
    "aHR0cHM6Ly9tb2JpZGV0ZWN0LmFydA==",
    "aHR0cHM6Ly9tb2JpZGV0ZWN0LmNj",
    "aHR0cHM6Ly9tb2JpZGV0ZWN0LmNsaWNr",
    "aHR0cHM6Ly9tb2JpZGV0ZWN0Lmluaw==",
    "aHR0cHM6Ly9tb2JpZGV0ZWN0LmxpdmU=",
    "aHR0cHM6Ly9tb2JpZGV0ZWN0LnBybw==",
    "aHR0cHM6Ly9tb2JpZGV0ZWN0LnNob3A=",
    "aHR0cHM6Ly9tb2JpZGV0ZWN0LnNpdGU=",
    "aHR0cHM6Ly9tb2JpZGV0ZWN0LnNwYWNl",
    "aHR0cHM6Ly9tb2JpZGV0ZWN0LnN0b3Jl",
    "aHR0cHM6Ly9tb2JpZGV0ZWN0LnZpcA==",
    "aHR0cHM6Ly9tb2JpZGV0ZWN0Lndpa2k=",
    "aHR0cHM6Ly9tb2JpZGV0ZWN0Lnh5eg==",
    "aHR0cHM6Ly9tb2JpZGV0ZWN0cy5hcnQ=",
    "aHR0cHM6Ly9tb2JpZGV0ZWN0cy5jYw==",
    "aHR0cHM6Ly9tb2JpZGV0ZWN0cy5pbmZv",
    "aHR0cHM6Ly9tb2JpZGV0ZWN0cy5pbms=",
    "aHR0cHM6Ly9tb2JpZGV0ZWN0cy5saXZl",
    "aHR0cHM6Ly9tb2JpZGV0ZWN0cy5wcm8=",
    "aHR0cHM6Ly9tb2JpZGV0ZWN0cy5zdG9yZQ==",
    "aHR0cHM6Ly9tb2JpZGV0ZWN0cy50b3A=",
    "aHR0cHM6Ly9tb2JpZGV0ZWN0cy54eXo=",
)

internal object NewTvBase {
    @Volatile var value: String = ""
        private set
    fun set(base: String) { value = base.trimEnd('/') }
    fun clear() { value = "" }
}

internal object CookieBox {
    // t_hash_t is issued by the NetMirror backend itself, not by a specific
    // mirror domain — the same cookie works on every mirror. Treating it as
    // host-bound forced a full re-verify (a request burst) after every
    // rotation, which is what tripped the IP rate limiter.
    //
    // Live probing (Aug 2026) shows the SERVER side of the session lives
    // only ~4-5 minutes from issue: a cookie verified at T worked at T+210s
    // and answered {"status":"n","error":"Invalid User"} at T+290s. The old
    // 60-minute TTL served a dead cookie on virtually every request, which
    // is the actual root cause of the "Too many request in short.."-family
    // failures. 3 minutes stays safely under the observed death window; the
    // re-verify is one request, collapsed by verify()'s singleflight.
    private const val SESSION_TTL_MS = 3 * 60 * 1000L
    @Volatile var tHashT: String = ""
        private set
    @Volatile var issuedHost: String = ""
        private set
    @Volatile var expiresAt: Long = 0L
    fun put(value: String, host: String) {
        tHashT = value; issuedHost = host
        expiresAt = System.currentTimeMillis() + SESSION_TTL_MS
    }
    fun fresh(): Boolean = tHashT.isNotBlank() && System.currentTimeMillis() < expiresAt
    fun clear() { tHashT = ""; issuedHost = ""; expiresAt = 0L }
}


// Persistent t_hash_t, kept ONLY as a bootstrapping hint: the server kills
// its side of the session in ~4-5 minutes, so a persisted cookie is almost
// always dead on arrival. verify() therefore re-verifies proactively and
// falls back to this store just once — when the verify infrastructure
// itself is unreachable — rather than pretending the cookie is still valid.
// Callers detect the server's "Invalid User" body and re-verify.
internal object NetMirrorCookieStore {
    private const val PREF_NAME = "OTTMirrorPrefs"
    private const val KEY_COOKIE = "t_hash_t"
    private const val KEY_HOST = "t_hash_t_host"
    private const val KEY_TS = "t_hash_t_ts"
    private const val TTL_MS = 15 * 60 * 60 * 1000L

    @Volatile private var prefs: android.content.SharedPreferences? = null

    fun init(context: android.content.Context) {
        prefs = context.applicationContext.getSharedPreferences(PREF_NAME, android.content.Context.MODE_PRIVATE)
    }

    fun load(): Triple<String, String, Long>? {
        val p = prefs ?: return null
        val cookie = p.getString(KEY_COOKIE, null)?.takeIf { it.isNotBlank() } ?: return null
        val host = p.getString(KEY_HOST, "") ?: ""
        val ts = p.getLong(KEY_TS, 0L)
        if (ts <= 0 || System.currentTimeMillis() - ts > TTL_MS) { clear(); return null }
        return Triple(cookie, host, ts)
    }

    fun save(cookie: String, host: String) {
        prefs?.edit()?.putString(KEY_COOKIE, cookie)?.putString(KEY_HOST, host)
            ?.putLong(KEY_TS, System.currentTimeMillis())?.apply()
    }

    fun clear() {
        prefs?.edit()?.remove(KEY_COOKIE)?.remove(KEY_HOST)?.remove(KEY_TS)?.apply()
    }
}

internal fun decodeBase64(value: String): String = Base64Decode.decodeUtf8(value).orEmpty()

/**
 * Short-lived response caches. Each entry absorbs UI-driven repeat calls
 * (detail refresh, back-and-forth between seasons) that would otherwise
 * hit the per-IP limiter for data it already served seconds ago.
 */
internal object NetMirrorResponseCache {
    private const val TTL_MS = 10 * 60 * 1000L
    private const val MAX_SIZE = 64
    private data class Entry(val value: Any, val expiresAt: Long)
    private val map = ConcurrentHashMap<String, Entry>()

    @Suppress("UNCHECKED_CAST")
    fun <T : Any> get(key: String): T? {
        if (key.isBlank()) return null
        val e = map[key] ?: return null
        if (System.currentTimeMillis() > e.expiresAt) { map.remove(key); return null }
        return e.value as? T
    }

    fun put(key: String, value: Any) {
        if (key.isBlank()) return
        if (map.size >= MAX_SIZE) map.entries.minByOrNull { it.value.expiresAt }?.key?.let { map.remove(it) }
        map[key] = Entry(value, System.currentTimeMillis() + TTL_MS)
    }
}

internal val NEWTV_HEADERS = mapOf(
    "Cache-Control" to "no-cache, no-store, must-revalidate",
    "Pragma" to "no-cache",
    "Expires" to "0",
    "X-Requested-With" to "NetmirrorNewTV v1.0",
    "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:136.0) Gecko/20100101 Firefox/136.0 /OS.GatuNewTV v1.0",
    "Accept" to "application/json, text/plain, */*",
)

internal val MOBILE_UA = "Mozilla/5.0 (Linux; Android 12; RMX2117 Build/SP1A.210812.016; wv) AppleWebKit/537.36 (KHTML, like Gecko) Version/4.0 Chrome/147.0.7727.55 Mobile Safari/537.36 /OS.Gatu v3.0"