package com.indstream

import android.content.Context
import android.widget.Toast
import com.indstream.foryou.AppSession
import com.indstream.foryou.ForYouRefresh

/** FILE: Settings.kt - plugin settings store (keys, AI provider, row refresh mode). */
object Settings {

    /** Wyzie key redemption page (opened by the Get API chip). */
    const val KEY_LINK = "https://store.wyzie.io/redeem"

    /** TMDB API settings page (opened by the TMDB Get API chip). */
    const val TMDB_LINK = "https://www.themoviedb.org/settings/api"

    /** FebBox sign-in page loaded by the ShowBox Get Token chip. */
    const val SHOWBOX_LINK = "https://www.febbox.com/"

    /** Host whose session cookie carries the ShowBox token. */
    const val SHOWBOX_HOST = "https://www.febbox.com"

    private const val PREFS = "indstream_settings"
    private const val KEY = "wyzie_api_key"
    private const val TMDB_KEY = "tmdb_api_key"
    private const val SHOWBOX_KEY = "showbox_token"
    private const val AI_KEY = "ai_api_key"
    private const val AI_KEY_PREFIX = "ai_api_key_"
    private const val AI_MODEL = "ai_model"
    private const val AI_BASE = "ai_base_url"
    private const val AI_PROVIDER = "ai_provider"
    private const val FORYOU_MODE = "foryou_refresh_mode"

    @Volatile
    private var appContext: Context? = null

    @Volatile
    private var cached: String? = null

    @Volatile
    private var loaded = false

    /** Remember the app context so subtitle fetches can read the key without one. */
    fun init(context: Context) {
        appContext = context.applicationContext
        AppSession.attach(context)
        load()
    }

    /** App context, when the plugin has been loaded. */
    fun appContextOrNull(): Context? = appContext

    /** Saved key, or null when the user never set one (built-in subtitles stay in use). */
    fun apiKey(): String? {
        if (!loaded) load()
        return cached?.takeIf { it.isNotBlank() }
    }

    /** True when the raw input is usable as a key (blank = "not set", handled by callers). */
    fun isValidKey(raw: String?): Boolean = normalizeKey(raw).length >= 8

    /** Trimmed key, never null. */
    fun normalizeKey(raw: String?): String = raw?.trim().orEmpty()

    /** Persist the key (blank clears it). False when the input is neither blank nor valid. */
    fun save(context: Context, raw: String): Boolean {
        val key = normalizeKey(raw)
        if (key.isNotEmpty() && !isValidKey(key)) return false
        runCatching {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit().putString(KEY, key).apply()
        }
        cached = key
        loaded = true
        appContext = context.applicationContext
        return true
    }

    /** Drop the saved key; the built-in subtitle stack takes over again. */
    fun clear(context: Context) {
        save(context, "")
    }

    /** Saved TMDB key, or null when never set (built-in keys stay in use). */
    fun tmdbApiKey(): String? {
        if (!tmdbLoaded) loadTmdb()
        return tmdbCached?.takeIf { it.isNotBlank() }
    }

    // TMDB v3 keys are short (32 hex chars); the long eyJ... string is the
    // Read Access Token (v4) and does not work as ?api_key=.
    fun isValidTmdbKey(raw: String?): Boolean {
        val k = normalizeKey(raw)
        return k.length >= 32 && !k.startsWith("eyJ")
    }

    /** Null when the input is usable; else the exact problem to show. */
    fun tmdbKeyProblem(raw: String?): String? {
        val k = normalizeKey(raw)
        if (k.isEmpty()) return null
        if (k.startsWith("eyJ")) return "That's the Read Access Token - paste the API Key (v3)"
        if (k.length < 32) return "Key looks too short (need the 32-char API Key)"
        return null
    }

    /** Persist the TMDB key (blank clears it). False when non-blank but invalid. */
    fun saveTmdb(context: Context, raw: String): Boolean {
        val key = normalizeKey(raw)
        if (key.isNotEmpty() && !isValidTmdbKey(key)) return false
        runCatching {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit().putString(TMDB_KEY, key).apply()
        }
        tmdbCached = key
        tmdbLoaded = true
        appContext = context.applicationContext
        return true
    }

    /** Drop the saved TMDB key; the built-in keys take over again. */
    fun clearTmdb(context: Context) {
        saveTmdb(context, "")
    }

    /** Saved FebBox token, or null when never set (the ShowBox server stays off). */
    fun showboxToken(): String? {
        if (!showboxLoaded) loadShowbox()
        return showboxCached?.takeIf { it.isNotBlank() }
    }

    /** Persist the FebBox token (blank clears it). */
    fun saveShowbox(context: Context, raw: String) {
        val token = raw.trim()
        runCatching {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit().putString(SHOWBOX_KEY, token).apply()
        }
        showboxCached = token
        showboxLoaded = true
        appContext = context.applicationContext
    }

    /** Drop the saved token; the ShowBox server goes dormant again. */
    fun clearShowbox(context: Context) {
        saveShowbox(context, "")
    }

    // AI provider settings, used by the recommendation reranker.

    /** Keys are per provider; the legacy single key migrates to the saved provider. */
    private val aiKeys = HashMap<String, String>()

    @Volatile
    private var aiModelCached: String? = null

    @Volatile
    private var aiBaseCached: String? = null

    @Volatile
    private var aiProviderCached: String? = null

    @Volatile
    private var aiLoaded = false

    /** Saved key for the selected provider (or the given one), null when unset. */
    fun aiApiKey(provider: String? = null): String? {
        if (!aiLoaded) loadAi()
        val id = provider?.trim()?.takeIf { it.isNotBlank() }
            ?: aiProviderCached?.takeIf { it.isNotBlank() } ?: "openrouter"
        return aiKeys[id]?.takeIf { it.isNotBlank() }
    }

    /** Selected provider id; empty when nothing was chosen. */
    fun aiProviderId(): String {
        if (!aiLoaded) loadAi()
        return aiProviderCached?.takeIf { it.isNotBlank() } ?: "openrouter"
    }

    /** Model id for the selected provider. */
    fun aiModel(): String {
        if (!aiLoaded) loadAi()
        return aiModelCached.orEmpty()
    }

    /** Base url override; empty means the provider preset is used. */
    fun aiBaseUrl(): String {
        if (!aiLoaded) loadAi()
        return aiBaseCached.orEmpty()
    }

    /** Persist the AI settings for one provider. A blank key clears that provider's key. */
    fun saveAi(context: Context, provider: String, rawKey: String, model: String, baseUrl: String) {
        val key = normalizeKey(rawKey)
        val id = provider.trim().ifBlank { "openrouter" }
        val cleanModel = model.trim()
        val cleanBase = baseUrl.trim().trimEnd('/')
        runCatching {
            val edit = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit()
                .putString(AI_MODEL, cleanModel)
                .putString(AI_BASE, cleanBase)
                .putString(AI_PROVIDER, id)
                .remove(AI_KEY)
            if (key.isBlank()) edit.remove(AI_KEY_PREFIX + id) else edit.putString(AI_KEY_PREFIX + id, key)
            edit.apply()
        }
        if (key.isBlank()) aiKeys.remove(id) else aiKeys[id] = key
        aiModelCached = cleanModel
        aiBaseCached = cleanBase
        aiProviderCached = id
        aiLoaded = true
        appContext = context.applicationContext
    }

    /** Drop the saved key/model for one provider. */
    fun clearAi(context: Context, provider: String? = null) {
        if (!aiLoaded) loadAi()
        val id = provider?.trim()?.takeIf { it.isNotBlank() }
            ?: aiProviderCached?.takeIf { it.isNotBlank() } ?: "openrouter"
        runCatching {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit()
                .remove(AI_KEY_PREFIX + id)
                .remove(AI_KEY)
                .putString(AI_MODEL, "")
                .putString(AI_BASE, "")
                .apply()
        }
        aiKeys.remove(id)
        aiModelCached = ""
        aiBaseCached = ""
        aiLoaded = true
        appContext = context.applicationContext
    }

    @Volatile
    private var forYouModeCached: String? = null

    @Volatile
    private var forYouModeLoaded = false

    /** How often the personalised rows are rebuilt. */
    fun forYouRefreshMode(): ForYouRefresh.Mode {
        if (!forYouModeLoaded) loadForYouMode()
        return ForYouRefresh.Mode.from(forYouModeCached)
    }

    /** Persist how often the personalised rows are rebuilt. */
    fun saveForYouRefreshMode(context: Context, mode: ForYouRefresh.Mode) {
        runCatching {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit().putString(FORYOU_MODE, mode.id).apply()
        }
        forYouModeCached = mode.id
        forYouModeLoaded = true
        appContext = context.applicationContext
    }

    private fun loadForYouMode() {
        val ctx = appContext ?: return
        forYouModeCached = runCatching {
            ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(FORYOU_MODE, "").orEmpty()
        }.getOrDefault("")
        forYouModeLoaded = true
    }

    private fun loadAi() {
        val ctx = appContext ?: return
        runCatching {
            val prefs = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            aiModelCached = prefs.getString(AI_MODEL, "").orEmpty()
            aiBaseCached = prefs.getString(AI_BASE, "").orEmpty()
            aiProviderCached = prefs.getString(AI_PROVIDER, "").orEmpty()
            aiKeys.clear()
            prefs.all.forEach { (name, value) ->
                if (name.startsWith(AI_KEY_PREFIX) && value is String && value.isNotBlank()) {
                    aiKeys[name.removePrefix(AI_KEY_PREFIX)] = value
                }
            }
            // Older installs stored one key for every provider; keep it under the saved provider.
            val legacy = prefs.getString(AI_KEY, "").orEmpty()
            if (legacy.isNotBlank()) {
                val owner = aiProviderCached?.takeIf { it.isNotBlank() } ?: "openrouter"
                aiKeys.putIfAbsent(owner, legacy)
            }
        }
        aiLoaded = true
    }

    @Volatile
    private var showboxCached: String? = null

    @Volatile
    private var showboxLoaded = false

    private fun loadShowbox() {
        val ctx = appContext ?: return
        showboxCached = runCatching {
            ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(SHOWBOX_KEY, "").orEmpty()
        }.getOrDefault("")
        showboxLoaded = true
    }

    /** Pull the `ui` cookie value out of a raw cookie string. Pure. */
    internal fun uiTokenFromCookie(cookie: String?): String? =
        cookie?.split(';')
            ?.asSequence()
            ?.map { it.trim() }
            ?.firstOrNull { it.startsWith("ui=", ignoreCase = true) }
            ?.substringAfter('=')
            ?.trim()
            ?.takeIf { it.isNotEmpty() }

    @Volatile
    private var tmdbCached: String? = null

    @Volatile
    private var tmdbLoaded = false

    private fun loadTmdb() {
        val ctx = appContext ?: return
        tmdbCached = runCatching {
            ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(TMDB_KEY, "").orEmpty()
        }.getOrDefault("")
        tmdbLoaded = true
    }

    @Volatile
    private var wyzieFailureNotified = false

    /** One-time notice that the saved key failed (callers fall back to built-in). */
    fun notifyWyzieFailed(reason: String) {
        val ctx = appContext ?: return
        synchronized(this) {
            if (wyzieFailureNotified) return
            wyzieFailureNotified = true
        }
        runCatching {
            android.os.Handler(android.os.Looper.getMainLooper()).post {
                runCatching {
                    Toast.makeText(ctx, "Wyzie subtitles failed ($reason) - using built-in", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun load() {
        val ctx = appContext ?: return
        cached = runCatching {
            ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, "").orEmpty()
        }.getOrDefault("")
        loaded = true
    }
    /** The provider settings sheet. */
    fun openSettings(context: Context) = SettingsUi.open(context)
}
