package com.indstream.foryou

import com.indstream.HttpKit
import com.indstream.Settings
import com.lagradost.cloudstream3.app
import java.net.URLEncoder
import java.util.Locale
import kotlinx.coroutines.CancellationException
import org.json.JSONArray
import org.json.JSONObject

object AiService {

    enum class Provider(
        val id: String,
        val label: String,
        val baseUrl: String,
        val defaultModel: String,
    ) {
        OPENROUTER("openrouter", "OpenRouter", "https://openrouter.ai/api/v1", "openrouter/free"),
        GEMINI("gemini", "Google Gemini", "https://generativelanguage.googleapis.com/v1beta", "gemini-2.5-flash"),
        OTHER("other", "Other (OpenAI-compatible)", "", "");

        companion object {
            fun from(id: String?): Provider = entries.firstOrNull { it.id == id } ?: OPENROUTER
        }
    }

    data class Taste(
        val genres: List<String> = emptyList(),
        val languages: List<String> = emptyList(),
        val era: Int? = null,
        val avoidGenres: List<String> = emptyList(),
    )

    data class Candidate(
        val id: Int,
        val title: String,
        val year: String?,
        val rating: Double,
        val genres: String,
        val language: String = "",
        val overview: String = "",
        val votes: Int = 0,
    )

    private const val SYSTEM =
        "You are a film and series recommendation engine. You judge tone, theme, era, language " +
            "and quality, not just popularity. You pick only titles that closely match the user's " +
            "taste. You answer only with a JSON array of integers."

    fun isConfigured(): Boolean = !Settings.aiApiKey().isNullOrBlank()

    suspend fun rank(
        watched: List<ProfileTitle>,
        candidates: List<Candidate>,
        count: Int,
        taste: Taste = Taste(),
    ): List<Int> {
        val provider = Provider.from(Settings.aiProviderId())
        val key = Settings.aiApiKey()?.takeIf { it.isNotBlank() } ?: return emptyList()
        val base = baseUrl(provider)
        val model = Settings.aiModel(provider.id).takeIf { it.isNotBlank() } ?: provider.defaultModel
        if (base.isBlank() || model.isBlank()) return emptyList()
        val prompt = buildPrompt(watched, candidates, count, taste)
        val body = post(provider, base, model, key, prompt) ?: return emptyList()
        return extractIds(extractText(body, provider == Provider.GEMINI) ?: return emptyList())
    }

    suspend fun testKey(
        provider: Provider,
        baseUrl: String,
        model: String,
        key: String,
    ): String {
        val useModel = model.trim()
        if (useModel.isBlank()) return "Select a model first"
        if (key.isBlank()) return "Enter a key first"
        val base = baseUrl.trim().trimEnd('/').ifBlank { provider.baseUrl }
        if (base.isBlank()) return "Enter the API base url first"
        val prompt = "Answer with the single word OK"
        val resp = HttpKit.attempt {
            app.post(
                endpoint(provider, base, useModel, key),
                timeout = 20,
                headers = headers(provider, key),
                json = body(provider, useModel, prompt),
            )
        } ?: return "Verify failed - no network?"
        return when {
            resp.code in 200..299 -> "Key works - provider connected"
            resp.code == 401 || resp.code == 403 -> "Key rejected (${resp.code}) - check the key"
            resp.code == 429 -> "Rate limited (429) - the key is probably valid"
            else -> "Provider error (${resp.code})"
        }
    }

    suspend fun fetchModels(
        provider: Provider,
        baseUrl: String,
        key: String,
    ): List<String> {
        val base = baseUrl.trim().trimEnd('/').ifBlank { provider.baseUrl }
        if (base.isBlank() || (provider == Provider.GEMINI && key.isBlank())) return emptyList()
        val requestHeaders = headers(provider, key).let {
            if (key.isBlank()) it - "Authorization" else it
        }
        val resp = HttpKit.attempt {
            app.get(
                modelsEndpoint(provider, base, key),
                timeout = 20,
                headers = requestHeaders,
            )
        } ?: return emptyList()
        if (resp.code !in 200..299) return emptyList()
        val free = freeModels(resp.text, provider == Provider.GEMINI)
        if (provider != Provider.OPENROUTER) return free
        return (listOf(Provider.OPENROUTER.defaultModel) + free).distinct().take(12)
    }

    internal fun freeModels(body: String, gemini: Boolean): List<String> = try {
        val root = JSONObject(body)
        val arr = root.optJSONArray("data") ?: root.optJSONArray("models") ?: JSONArray()
        if (gemini) {
            (0 until arr.length()).mapNotNull { i ->
                val o = arr.optJSONObject(i) ?: return@mapNotNull null
                val methods = o.optJSONArray("supportedGenerationMethods") ?: return@mapNotNull null
                if ((0 until methods.length()).none {
                        methods.optString(it).equals("generateContent", ignoreCase = true)
                    }
                ) return@mapNotNull null
                o.optString("name").removePrefix("models/").takeIf { it.isNotBlank() }
            }.distinct().take(5)
        } else {
            (0 until arr.length()).mapNotNull { i ->
                val o = arr.optJSONObject(i) ?: return@mapNotNull null
                val id = o.optString("id").takeIf { it.isNotBlank() } ?: return@mapNotNull null
                val pricing = o.optJSONObject("pricing")
                val free = id.endsWith(":free", ignoreCase = true) ||
                    (pricing != null && pricing.optString("prompt").toDoubleOrNull() == 0.0 &&
                        pricing.optString("completion").toDoubleOrNull() == 0.0)
                if (!free) return@mapNotNull null
                o.optLong("created", 0L) to id
            }.sortedByDescending { it.first }.map { it.second }.distinct().take(12)
        }
    } catch (e: Exception) {
        emptyList()
    }

    private fun modelsEndpoint(provider: Provider, base: String, key: String): String =
        if (provider == Provider.GEMINI) "$base/models?key=$key" else "$base/models"

    internal fun parseModels(body: String, gemini: Boolean): List<String> = try {
        val root = JSONObject(body)
        val arr = root.optJSONArray("data") ?: root.optJSONArray("models") ?: return emptyList()
        (0 until arr.length()).mapNotNull { i ->
            val o = arr.optJSONObject(i) ?: return@mapNotNull null
            val raw = o.optString("id").takeIf { it.isNotBlank() }
                ?: o.optString("name").takeIf { it.isNotBlank() }
                ?: return@mapNotNull null
            if (gemini) raw.removePrefix("models/") else raw
        }.distinct()
    } catch (e: Exception) {
        emptyList()
    }

    fun baseUrl(provider: Provider): String {
        val custom = Settings.aiBaseUrl(provider.id).trim().trimEnd('/')
        if (custom.isNotBlank()) return custom
        return provider.baseUrl.trimEnd('/')
    }

    internal fun buildPrompt(
        watched: List<ProfileTitle>,
        candidates: List<Candidate>,
        count: Int,
        taste: Taste = Taste(),
    ): String {
        val loved = watched.filter { it.rating >= 8.0 && it.weight > 0.0 }
            .sortedByDescending { it.rating }.take(10)
        val liked = watched.filter { it.rating >= 6.0 && it.rating < 8.0 && it.weight > 0.0 }
            .sortedByDescending { it.rating }.take(10)
        val disliked = watched.filter { it.weight < 0.0 }
            .sortedBy { if (it.rating > 0) it.rating else 5.0 }.take(8)
        val saved = watched.filter {
            !it.watched && it.weight > 0.0 &&
                it !in loved && it !in liked && it !in disliked
        }.sortedByDescending { it.weight }.take(8)
        val other = watched.filter {
            it !in loved && it !in liked && it !in disliked && it !in saved
        }.sortedByDescending { it.lastActivityAt }.take(8)
        val sections = listOf(
            "Strong positive signals (rated 8+):" to loved,
            "Positive signals (rated 6-7.9):" to liked,
            "Dropped or rated low - avoid this kind of title:" to disliked,
            "On the watchlist:" to saved,
            "Other library entries:" to other,
        ).filter { it.second.isNotEmpty() }
        val pool = candidates.take(45)
        return buildString {
            if (sections.isNotEmpty()) {
                appendLine("User library:")
                sections.forEach { (label, titles) ->
                    appendLine(label)
                    titles.forEach { appendLine(it.promptLine()) }
                }
                appendLine()
            }
            if (taste.genres.isNotEmpty() || taste.languages.isNotEmpty() ||
                taste.era != null || taste.avoidGenres.isNotEmpty()
            ) {
                appendLine("User taste summary:")
                if (taste.genres.isNotEmpty()) {
                    appendLine("- Preferred genres: ${taste.genres.joinToString(", ")}")
                }
                if (taste.languages.isNotEmpty()) {
                    appendLine("- Preferred original languages: ${taste.languages.joinToString(", ")}")
                }
                taste.era?.let { appendLine("- Preferred release era: around $it") }
                if (taste.avoidGenres.isNotEmpty()) {
                    appendLine("- Genres to avoid: ${taste.avoidGenres.joinToString(", ")}")
                }
                appendLine()
            }
            appendLine("Candidate titles:")
            pool.forEach { appendLine(it.promptLine()) }
            appendLine()
            appendLine("Pick up to $count candidates the user is most likely to enjoy, best first.")
            appendLine("Judge tone, theme, era, language and quality from the overview and metadata, not name recognition alone.")
            appendLine("Prefer strong taste matches over blockbusters; a lesser-known fit beats a famous mismatch.")
            appendLine("Skip anything only loosely related. Never include a title the user already watched, dropped or saved.")
            appendLine("Keep the shortlist varied: avoid near-identical genres, eras and languages unless the taste is very narrow.")
            appendLine("Only use ids from the candidate list and never repeat an id.")
            appendLine("Think briefly if needed, then answer with a JSON array of the tmdb ids only, for example [123, 456]. No other text.")
        }
    }

    private fun ProfileTitle.promptLine(): String {
        val status = when {
            dropped -> "dropped"
            watched -> "watched"
            else -> "watchlist"
        }
        val rating = if (rating > 0) "rated ${rating.toInt()}/10" else "unrated"
        return "- ${title.trim().take(48)} | ${year ?: "?"} | $rating | $status"
    }

    private fun Candidate.promptLine(): String {
        val rating = if (rating > 0) String.format(Locale.US, "%.1f", rating) else "?"
        val language = language.ifBlank { "?" }
        val genres = genres.ifBlank { "?" }.take(48)
        val synopsis = overview.replace(Regex("\\s+"), " ").trim().take(180)
        return "- $id | ${title.trim().take(48)} | ${year ?: "?"} | " +
            "$rating/10 (${votesLabel(votes)} votes) | $language | $genres | $synopsis"
    }

    private fun votesLabel(votes: Int): String = when {
        votes >= 1_000_000 -> "${votes / 1_000_000}M"
        votes >= 1_000 -> "${votes / 1_000}k"
        else -> votes.toString()
    }

    internal fun extractIds(text: String): List<Int> {
        val start = text.indexOf('[')
        val end = text.lastIndexOf(']')
        if (start >= 0 && end > start) {
            val whole = parseIds(text.substring(start, end + 1))
            if (whole.isNotEmpty()) return whole
        }
        val arrays = Regex("\\[[^\\[\\]]*]").findAll(text).map { it.value }
        var best = emptyList<Int>()
        for (raw in arrays) {
            val ids = parseIds(raw)
            if (ids.isNotEmpty() && ids.size >= best.size) best = ids
        }
        return best
    }

    private fun parseIds(raw: String): List<Int> = try {
        val arr = JSONArray(raw)
        (0 until arr.length()).mapNotNull { i ->
            when (val value = arr.opt(i)) {
                is Int -> value
                is Number -> value.toInt()
                is String -> value.trim().toIntOrNull()
                is JSONObject -> value.optInt("id", 0).takeIf { it > 0 }
                    ?: value.optString("id").toIntOrNull()
                    ?: value.optString("tmdb_id").toIntOrNull()
                    ?: value.optString("tmdbId").toIntOrNull()
                else -> null
            }
        }.filter { it > 0 }.distinct()
    } catch (e: Exception) {
        emptyList()
    }

    internal fun extractText(body: String, gemini: Boolean): String? {
        return try {
            val root = JSONObject(body)
            if (gemini) {
                val parts = root.optJSONArray("candidates")?.optJSONObject(0)
                    ?.optJSONObject("content")?.optJSONArray("parts")
                parts?.let { arr ->
                    (0 until arr.length()).mapNotNull { arr.optJSONObject(it)?.optString("text") }
                        .joinToString("")
                }?.takeIf { it.isNotBlank() }
            } else {
                val message = root.optJSONArray("choices")?.optJSONObject(0)
                    ?.optJSONObject("message")
                val content = message?.opt("content")
                val text = when (content) {
                    is String -> content.takeIf { it.isNotBlank() }
                    is JSONArray -> (0 until content.length())
                        .mapNotNull { content.optJSONObject(it)?.optString("text") }
                        .joinToString("").takeIf { it.isNotBlank() }
                    else -> null
                }
                text ?: message?.optString("reasoning")?.takeIf { it.isNotBlank() }
            }
        } catch (e: Exception) {
            null
        }
    }

    private fun endpoint(provider: Provider, base: String, model: String, key: String): String =
        if (provider == Provider.GEMINI) {
            "$base/models/${URLEncoder.encode(model, "UTF-8")}:generateContent?key=$key"
        } else {
            "$base/chat/completions"
        }

    private fun headers(provider: Provider, key: String): Map<String, String> {
        val base = mapOf(
            "Content-Type" to "application/json",
            "User-Agent" to HttpKit.userAgent,
        )
        return when (provider) {
            Provider.GEMINI -> base
            Provider.OPENROUTER -> base + mapOf(
                "Authorization" to "Bearer $key",
                "HTTP-Referer" to "https://github.com/dipender98/Indflix",
                "X-Title" to "IndStream",
            )
            else -> base + mapOf("Authorization" to "Bearer $key")
        }
    }

    private fun body(provider: Provider, model: String, prompt: String): Map<String, Any?> =
        if (provider == Provider.GEMINI) {
            mapOf(
                "contents" to listOf(
                    mapOf("role" to "user", "parts" to listOf(mapOf("text" to prompt))),
                ),
                "generationConfig" to mapOf(
                    "temperature" to 0.25,
                    "maxOutputTokens" to 1400,
                    "responseMimeType" to "application/json",
                ),
            )
        } else {
            mapOf(
                "model" to model,
                "messages" to listOf(
                    mapOf("role" to "system", "content" to SYSTEM),
                    mapOf("role" to "user", "content" to prompt),
                ),
                "temperature" to 0.25,
                "max_tokens" to 1400,
            )
        }

    private suspend fun post(
        provider: Provider,
        base: String,
        model: String,
        key: String,
        prompt: String,
    ): String? = try {
        app.post(
            endpoint(provider, base, model, key),
            timeout = 22,
            headers = headers(provider, key),
            json = body(provider, model, prompt),
        ).text
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        null
    }
}
