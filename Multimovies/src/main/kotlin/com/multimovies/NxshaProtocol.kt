package com.multimovies

import org.json.JSONArray
import org.json.JSONObject
import java.security.SecureRandom

/** One entry of the decrypted /api/servers list (subset of fields we use). */
internal data class NxshaServer(
    val name: String,
    val scraper: String,
    val position: Int,
    val highPriority: Int,
)

/**
 * Pure Nxsha wire-protocol logic: envelope crypto, id parsing, server rules.
 *
 * Kept free of CloudStream imports (and isolated from [NxshaExtractor]) so it
 * runs on the plain JVM unit-test classpath — same pattern as the screenscape
 * result types. See [NxshaExtractor]'s doc for the endpoint flow.
 */
internal object NxshaProtocol {

    /** AES passphrase of the API envelopes. Extracted Aug 2026 from the
     *  player bundle (chunk 0fo9av_cihir0.js, module 41159,
     *  String.fromCharCode(83,56,120,33,74,107,52,90,80,49,117,71,56,36,109,121)).
     *  If extraction starts returning zero sources, re-extract from
     *  https://nxsha.space/_next/static/chunks/0fo9av_cihir0.js (file tail) or
     *  via a debugger on web.nxsha.app/embed/movie/550. */
    internal const val PASSPHRASE = "S8x!Jk4ZP1uG8\$my"

    /** Random ~10-char [a-z0-9] salt mimicking Math.random().toString(36).substring(2,12). */
    fun randomSalt(): String {
        val alphabet = "abcdefghijklmnopqrstuvwxyz0123456789"
        val sb = StringBuilder(10)
        repeat(10) { sb.append(alphabet[SecureRandom().nextInt(alphabet.length)]) }
        return sb.toString()
    }

    /** Build the base64url(no padding) encrypted `q` parameter value. Mirrors
     *  the player's encodeData(): payload + _req_ts + _req_salt -> JSON ->
     *  CryptoJS AES -> base64url without '=' padding. */
    fun encodeData(payload: Map<String, Any?>): String {
        val json = JSONObject()
        payload.forEach { (k, v) -> json.put(k, v ?: JSONObject.NULL) }
        json.put("_req_ts", System.currentTimeMillis())
        json.put("_req_salt", randomSalt())
        return CryptoJs.aesEncryptCryptoJs(json.toString(), PASSPHRASE)
            .replace("+", "-").replace("/", "_").replace("=", "")
    }

    /** Decrypt a response `_hash` envelope to its JSON payload (the player's
     *  decodeData()). Returns null on malformed/undecryptable input. */
    fun decodeData(hash: String?): JSONObject? {
        if (hash.isNullOrBlank()) return null
        val std = hash.replace("-", "+").replace("_", "/")
        val padded = std + "=".repeat((4 - std.length % 4) % 4)
        val plain = CryptoJs.aesDecryptCryptoJs(padded, PASSPHRASE) ?: return null
        return runCatching { JSONObject(plain) }.getOrNull()
    }

    data class ParsedIds(
        val tmdbId: String?,
        val imdbId: String?,
        val type: String?,
        val season: Int?,
        val episode: Int?,
    )

    /** Extract ids/type/season/episode from an Nxsha embed-style URL. Handles
     *  path form `/embed/movie/{tmdb}` & `/embed/tv/{tmdb}/{s}/{e}`, query form
     *  `?tmdb=&type=&s=&e=`, and IMDB-keyed forms `?imdb=tt...`. */
    fun parseIdsFromUrl(url: String): ParsedIds {
        val pathMatch = Regex("""/embed/(movie|tv)/(\d{2,10})(?:/(\d{1,4}))?(?:/(\d{1,4}))?""").find(url)
        var tmdb: String? = pathMatch?.groupValues?.getOrNull(2)
        var type: String? = pathMatch?.groupValues?.getOrNull(1)?.let { if (it == "movie") "movie" else "tv" }
        var season: Int? = pathMatch?.groupValues?.getOrNull(3)?.toIntOrNull()?.takeIf { type == "tv" }
        var episode: Int? = pathMatch?.groupValues?.getOrNull(4)?.toIntOrNull()?.takeIf { type == "tv" }

        fun queryValue(vararg names: String): String? {
            val parts = url.split('?', '&')
            for (part in parts.drop(1)) {
                val idx = part.indexOf('=')
                if (idx <= 0) continue
                val k = part.substring(0, idx).lowercase()
                if (names.any { k == it }) return part.substring(idx + 1)
            }
            return null
        }

        queryValue("tmdb", "tmdbid")?.takeIf { it.matches(Regex("""\d{2,10}""")) }?.let { tmdb = it }
        queryValue("type")?.let { t ->
            when {
                t.equals("movie", true) -> type = "movie"
                t.equals("tv", true) || t.equals("series", true) || t.equals("show", true) -> type = "tv"
            }
        }
        queryValue("s", "season")?.toIntOrNull()?.let { season = it }
        queryValue("e", "episode", "ep")?.toIntOrNull()?.let { episode = it }
        val imdb = queryValue("imdb", "imdb_id", "imdbid")?.takeIf { it.matches(Regex("""tt\d{6,10}""")) }
        // season/episode are NOT filtered on type here: query-form embeds such as
        // ?imdb=tt...&s=1&e=1 (no type=) must keep them so extract() can infer tv.
        // (Path-form already only kept s/e for /embed/tv/..., so this is safe.)
        return ParsedIds(tmdb, imdb, type, season, episode)
    }

    /** Server ordering: Nitro first (verified fastest), then high_priority asc,
     *  then listed position asc. Stable sort keeps API order for ties. */
    fun orderServers(servers: List<NxshaServer>): List<NxshaServer> =
        servers.sortedWith(
            compareByDescending<NxshaServer> {
                it.scraper.contains("nitro", ignoreCase = true) || it.name.contains("nitro", ignoreCase = true)
            }.thenBy { it.highPriority }
                .thenBy { it.position }
        )

    /** Filter the raw servers array to usable entries: web_support, not
     *  disabled, serves [type] (missing `types` = compatible), then ordered. */
    fun parseServers(arr: JSONArray?, type: String): List<NxshaServer> {
        if (arr == null) return emptyList()
        val out = mutableListOf<NxshaServer>()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            if (!o.optBoolean("web_support", true)) continue
            if (o.optBoolean("isDisable", false)) continue
            val servesTypes = o.optJSONArray("types")
            if (servesTypes != null && servesTypes.length() > 0 &&
                !(0 until servesTypes.length()).any { servesTypes.optString(it) == type }
            ) continue
            out.add(
                NxshaServer(
                    name = o.optString("name").ifBlank { o.optString("scraper") },
                    scraper = o.optString("scraper"),
                    position = o.optInt("position", Int.MAX_VALUE),
                    highPriority = o.optInt("high_priority", Int.MAX_VALUE),
                )
            )
        }
        return orderServers(out)
    }

    /** Short display label: "Nitro - [Multi-Lang]" -> "Nitro". */
    fun shortServerName(name: String): String =
        name.substringBefore('-').trim().ifEmpty { name.trim() }

    /** True when a quality string marks a Hindi audio track ("Hindi dub : 1080",
     *  "720p | Hindi", "[Hindi, English] - 720P"). */
    fun isHindiQuality(quality: String?): Boolean =
        quality.orEmpty().contains("hindi", ignoreCase = true)

    /** Prefer the origin of an nxsha.* embed URL (the dooplayer may hand out a
     *  different host than the canonical base); else the canonical base.
     *  Only nxsha.space and web.nxsha.app host the player API — nxsha.cc,
     *  nxsha.app, nxsha.xyz are landing/status sites (404/503). */
    fun baseUrlFor(url: String, fallback: String): String {
        val schemeHost = Regex("""^(https?)://[^/]+""").find(url)?.value ?: return fallback
        val host = schemeHost.substringAfter("://").lowercase()
        val playerHost = host == "nxsha.space" || host == "web.nxsha.app" ||
            host.endsWith(".nxsha.space") || host.endsWith(".web.nxsha.app")
        return if (playerHost) schemeHost else fallback
    }
}
