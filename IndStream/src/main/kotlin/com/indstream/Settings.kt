package com.indstream

import android.content.Context
import android.widget.Toast
import com.indstream.foryou.AppSession
import com.indstream.foryou.ForYouRefresh

object Settings {

    const val KEY_LINK = "https://store.wyzie.io/redeem"

    const val TMDB_LINK = "https://www.themoviedb.org/settings/api"

    const val SHOWBOX_LINK = "https://www.febbox.com/"

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

    fun init(context: Context) {
        appContext = context.applicationContext
        AppSession.attach(context)
        load()
    }

    fun appContextOrNull(): Context? = appContext

    fun apiKey(): String? {
        if (!loaded) load()
        return cached?.takeIf { it.isNotBlank() }
    }

    fun isValidKey(raw: String?): Boolean = normalizeKey(raw).length >= 8

    fun normalizeKey(raw: String?): String = raw?.trim().orEmpty()

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

    fun clear(context: Context) {
        save(context, "")
    }

    fun tmdbApiKey(): String? {
        if (!tmdbLoaded) loadTmdb()
        return tmdbCached?.takeIf { it.isNotBlank() }
    }

    fun isValidTmdbKey(raw: String?): Boolean {
        val k = normalizeKey(raw)
        return k.length >= 32 && !k.startsWith("eyJ")
    }

    fun tmdbKeyProblem(raw: String?): String? {
        val k = normalizeKey(raw)
        if (k.isEmpty()) return null
        if (k.startsWith("eyJ")) return "That's the Read Access Token - paste the API Key (v3)"
        if (k.length < 32) return "Key looks too short (need the 32-char API Key)"
        return null
    }

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

    fun clearTmdb(context: Context) {
        saveTmdb(context, "")
    }

    fun showboxToken(): String? {
        if (!showboxLoaded) loadShowbox()
        return showboxCached?.takeIf { it.isNotBlank() }
    }

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

    fun clearShowbox(context: Context) {
        saveShowbox(context, "")
    }


    private val aiKeys = HashMap<String, String>()

    @Volatile
    private var aiModelCached: String? = null

    @Volatile
    private var aiBaseCached: String? = null

    @Volatile
    private var aiProviderCached: String? = null

    @Volatile
    private var aiLoaded = false

    fun aiApiKey(provider: String? = null): String? {
        if (!aiLoaded) loadAi()
        val id = provider?.trim()?.takeIf { it.isNotBlank() }
            ?: aiProviderCached?.takeIf { it.isNotBlank() } ?: "openrouter"
        return aiKeys[id]?.takeIf { it.isNotBlank() }
    }

    fun aiProviderId(): String {
        if (!aiLoaded) loadAi()
        return aiProviderCached?.takeIf { it.isNotBlank() } ?: "openrouter"
    }

    fun aiModel(): String {
        if (!aiLoaded) loadAi()
        return aiModelCached.orEmpty()
    }

    fun aiBaseUrl(): String {
        if (!aiLoaded) loadAi()
        return aiBaseCached.orEmpty()
    }

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

    fun forYouRefreshMode(): ForYouRefresh.Mode {
        if (!forYouModeLoaded) loadForYouMode()
        return ForYouRefresh.Mode.from(forYouModeCached)
    }

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
    fun openSettings(context: Context) = SettingsUi.open(context)
}
