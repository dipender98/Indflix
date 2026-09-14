package com.indstream

import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.text.InputType
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

/** FILE: Settings.kt - plugin settings store + settings dialog (Wyzie Subs API key today). */
object Settings {

    /** Wyzie key redemption page (opened by the Get API chip). */
    const val KEY_LINK = "https://store.wyzie.io/redeem"

    /** TMDB API settings page (opened by the TMDB Get API chip). */
    const val TMDB_LINK = "https://www.themoviedb.org/settings/api"

    private const val PREFS = "indstream_settings"
    private const val KEY = "wyzie_api_key"
    private const val TMDB_KEY = "tmdb_api_key"

    @Volatile
    private var appContext: Context? = null

    @Volatile
    private var cached: String? = null

    @Volatile
    private var loaded = false

    /** Remember the app context so subtitle fetches can read the key without one. */
    fun init(context: Context) {
        appContext = context.applicationContext
        load()
    }

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

    /** Dark (not pitch-black) settings dialog: key field, redeem link, save/clear. */
    fun openSettings(context: Context) {
        val bgColor = Color.parseColor("#0F0F11")
        val cardColor = Color.parseColor("#141416")
        val palletColor = Color.parseColor("#19191C")
        val fieldColor = Color.parseColor("#232329")
        val borderColor = Color.parseColor("#3D3D46")
        val textColor = Color.parseColor("#EDEDF2")
        val hintColor = Color.parseColor("#A3A3AD")
        val accentColor = Color.parseColor("#2F7CF6")
        val chipYellow = Color.parseColor("#FFC107")
        val chipInk = Color.parseColor("#141414")
        val savedGreen = Color.parseColor("#3FB950")

        fun dp(v: Int): Int = (v * context.resources.displayMetrics.density).toInt()
        fun rounded(color: Int, radiusDp: Int, strokeDp: Int = 0, strokeColor: Int = borderColor): GradientDrawable =
            GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                setColor(color)
                cornerRadius = dp(radiusDp).toFloat()
                if (strokeDp > 0) setStroke(dp(strokeDp), strokeColor)
            }

        val sectionsStack = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(20), dp(20), dp(16))
            background = rounded(cardColor, 16)
        }
        fun addLabel(s: String, sizeSp: Float, color: Int, bold: Boolean = false): TextView =
            TextView(context).apply {
                text = s
                setTextSize(TypedValue.COMPLEX_UNIT_SP, sizeSp)
                setTextColor(color)
                if (bold) setTypeface(null, Typeface.BOLD)
                sectionsStack.addView(this)
            }

        addLabel("Settings", 18f, textColor, bold = true)
        addLabel("Keys are optional - built-ins apply when empty.", 12f, hintColor)

        // Pallet card: tappable header with state + chevron, collapsible body.
        fun settingsSection(title: String, stateText: String, stateOn: Boolean): LinearLayout {
            val card = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                background = rounded(palletColor, 12)
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                ).apply { topMargin = dp(12) }
            }
            val sectionBody = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(16), 0, dp(16), dp(14))
            }
            val chevron = TextView(context).apply {
                text = "\u25BE"
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
                setTextColor(hintColor)
                setPadding(dp(8), 0, 0, 0)
            }
            val header = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                isClickable = true
                isFocusable = true
                setPadding(dp(16), dp(12), dp(12), dp(12))
                setOnClickListener {
                    val open = sectionBody.visibility != View.VISIBLE
                    sectionBody.visibility = if (open) View.VISIBLE else View.GONE
                    chevron.text = if (open) "\u25BE" else "\u25B8"
                }
            }
            val titleView = TextView(context).apply {
                text = title
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
                setTextColor(textColor)
                setTypeface(null, Typeface.BOLD)
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            }
            val stateView = TextView(context).apply {
                text = stateText
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
                setTypeface(null, Typeface.BOLD)
                setTextColor(if (stateOn) savedGreen else hintColor)
            }
            header.addView(titleView)
            header.addView(stateView)
            header.addView(chevron)
            card.addView(header)
            card.addView(sectionBody)
            sectionsStack.addView(card)
            return sectionBody
        }

        // Small pill chip for header actions (Verify, Get API).
        fun actionChip(label: String, fill: Int, ink: Int, stroke: Int? = null): TextView =
            TextView(context).apply {
                text = label
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
                setTextColor(ink)
                setTypeface(null, Typeface.BOLD)
                background = rounded(fill, 14, if (stroke == null) 0 else 1, stroke ?: fill)
                setPadding(dp(12), dp(6), dp(12), dp(6))
                isClickable = true
                isFocusable = true
            }

        val wyzieSaved = apiKey() != null
        val wyzieSection = settingsSection(
            "Wyzie Subs API Key", if (wyzieSaved) "Saved" else "Not set", wyzieSaved,
        )
        val chipRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { bottomMargin = dp(6) }
        }
        val getApiChip = actionChip("Get API", Color.TRANSPARENT, textColor, borderColor)
        val verifyChip = actionChip("Verify", chipYellow, chipInk).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { leftMargin = dp(8) }
        }
        chipRow.addView(getApiChip)
        chipRow.addView(verifyChip)
        wyzieSection.addView(chipRow)
        val wyzieKeyInput = EditText(context).apply {
            setText(apiKey().orEmpty())
            setHint("wyzie-...")
            setHintTextColor(hintColor)
            setTextColor(textColor)
            setSingleLine(true)
            inputType = InputType.TYPE_CLASS_TEXT
            typeface = Typeface.MONOSPACE
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            background = rounded(fieldColor, 10, 1)
            setPadding(dp(12), dp(10), dp(12), dp(10))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
            )
            wyzieSection.addView(this)
        }

        val wyzieStatusText = TextView(context).apply {
            text = if (wyzieSaved) "Wyzie Subs active - your key will be used."
            else "No key saved - using built-in subtitles."
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            setTextColor(hintColor)
            setPadding(0, dp(10), 0, dp(12))
            wyzieSection.addView(this)
        }

        val wyzieButtonRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END
        }
        // ~25% smaller than the platform Button defaults (14sp, 88x48dp minimums).
        val clearBtn = Button(context).apply {
            text = "Clear"
            setTextColor(textColor)
            background = rounded(Color.TRANSPARENT, 10)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            minWidth = 0
            minHeight = 0
            setPadding(dp(16), dp(8), dp(16), dp(8))
        }
        val saveBtn = Button(context).apply {
            text = "Save"
            setTextColor(Color.WHITE)
            setTypeface(null, Typeface.BOLD)
            background = rounded(accentColor, 10)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            minWidth = 0
            minHeight = 0
            setPadding(dp(16), dp(8), dp(16), dp(8))
        }
        wyzieButtonRow.addView(clearBtn)
        wyzieButtonRow.addView(saveBtn)
        wyzieSection.addView(wyzieButtonRow)

        val tmdbSaved = tmdbApiKey() != null
        val tmdbSection = settingsSection(
            "TMDB API Key", if (tmdbSaved) "Saved" else "Not set", tmdbSaved,
        )
        val tmdbDesc = TextView(context).apply {
            text = "Paste the API Key (v3 auth) - the short 32-character key. Not the long Read Access Token."
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            setTextColor(hintColor)
            setPadding(0, 0, 0, dp(6))
            tmdbSection.addView(this)
        }
        val tmdbChipRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { bottomMargin = dp(6) }
        }
        val tmdbGetApiChip = actionChip("Get API", Color.TRANSPARENT, textColor, borderColor)
        val tmdbVerifyChip = actionChip("Verify", chipYellow, chipInk).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { leftMargin = dp(8) }
        }
        tmdbChipRow.addView(tmdbGetApiChip)
        tmdbChipRow.addView(tmdbVerifyChip)
        tmdbSection.addView(tmdbChipRow)
        val tmdbKeyInput = EditText(context).apply {
            setText(tmdbApiKey().orEmpty())
            setHint("API Key (v3), 32 characters...")
            setHintTextColor(hintColor)
            setTextColor(textColor)
            setSingleLine(true)
            inputType = InputType.TYPE_CLASS_TEXT
            typeface = Typeface.MONOSPACE
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            background = rounded(fieldColor, 10, 1)
            setPadding(dp(12), dp(10), dp(12), dp(10))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
            )
            tmdbSection.addView(this)
        }
        val tmdbStatusText = TextView(context).apply {
            text = if (tmdbSaved) "Personal TMDB key active - used first for metadata."
            else "No key saved - using built-in keys."
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            setTextColor(hintColor)
            setPadding(0, dp(10), 0, dp(12))
            tmdbSection.addView(this)
        }
        val tmdbButtonRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END
        }
        val tmdbClearBtn = Button(context).apply {
            text = "Clear"
            setTextColor(textColor)
            background = rounded(Color.TRANSPARENT, 10)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            minWidth = 0
            minHeight = 0
            setPadding(dp(16), dp(8), dp(16), dp(8))
        }
        val tmdbSaveBtn = Button(context).apply {
            text = "Save"
            setTextColor(Color.WHITE)
            setTypeface(null, Typeface.BOLD)
            background = rounded(accentColor, 10)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            minWidth = 0
            minHeight = 0
            setPadding(dp(16), dp(8), dp(16), dp(8))
        }
        tmdbButtonRow.addView(tmdbClearBtn)
        tmdbButtonRow.addView(tmdbSaveBtn)
        tmdbSection.addView(tmdbButtonRow)

        val scroll = ScrollView(context).apply {
            setBackgroundColor(bgColor)
            setPadding(dp(4), dp(4), dp(4), dp(4))
            // No fading-edge gradient or overscroll glow near the edges - flat only.
            isVerticalFadingEdgeEnabled = false
            isHorizontalFadingEdgeEnabled = false
            overScrollMode = View.OVER_SCROLL_NEVER
            addView(sectionsStack)
        }
        // AlertDialog with the Material dialog theme and a rounded
        // window background - no floating-theme edge gradient.
        val dialog = AlertDialog.Builder(context, android.R.style.Theme_Material_Dialog)
            .setView(scroll)
            .create()
        fun verifyNow(key: String) {
            if (!isValidKey(key)) {
                wyzieKeyInput.error = "Key looks too short"
                return
            }
            wyzieStatusText.text = "Verifying key..."
            Thread {
                val msg = runCatching {
                    kotlinx.coroutines.runBlocking { WyzieSubs.testKey(key) }.second
                }.getOrDefault("Verify failed - no network?")
                wyzieKeyInput.post { wyzieStatusText.text = msg }
            }.start()
        }
        verifyChip.setOnClickListener { verifyNow(wyzieKeyInput.text.toString()) }
        getApiChip.setOnClickListener {
            runCatching {
                context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(KEY_LINK)))
            }.onFailure {
                Toast.makeText(context, "No browser found", Toast.LENGTH_SHORT).show()
            }
        }
        clearBtn.setOnClickListener {
            clear(context)
            Toast.makeText(context, "Cleared - using built-in subtitles", Toast.LENGTH_SHORT).show()
            dialog.dismiss()
        }
        saveBtn.setOnClickListener {
            val typed = wyzieKeyInput.text.toString()
            if (save(context, typed)) {
                Toast.makeText(context, "Saved", Toast.LENGTH_SHORT).show()
                dialog.dismiss()
                // Verify on save: error toast when the key fails.
                Thread {
                    val msg = runCatching {
                        kotlinx.coroutines.runBlocking { WyzieSubs.testKey(typed) }.second
                    }.getOrDefault("Verify failed - no network?")
                    if (!msg.startsWith("Key works")) {
                        android.os.Handler(android.os.Looper.getMainLooper()).post {
                            Toast.makeText(context, "Wyzie: $msg", Toast.LENGTH_LONG).show()
                        }
                    }
                }.start()
            } else {
                wyzieKeyInput.error = "Key looks too short"
            }
        }
        fun tmdbVerifyNow(key: String) {
            tmdbKeyProblem(key)?.let { tmdbKeyInput.error = it; return }
            tmdbStatusText.text = "Verifying key..."
            Thread {
                val msg = runCatching {
                    kotlinx.coroutines.runBlocking { TmdbService.testTmdbKey(key) }
                }.getOrDefault("Verify failed - no network?")
                tmdbKeyInput.post { tmdbStatusText.text = msg }
            }.start()
        }
        tmdbVerifyChip.setOnClickListener { tmdbVerifyNow(tmdbKeyInput.text.toString()) }
        tmdbGetApiChip.setOnClickListener {
            runCatching {
                context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(TMDB_LINK)))
            }.onFailure {
                Toast.makeText(context, "No browser found", Toast.LENGTH_SHORT).show()
            }
        }
        tmdbClearBtn.setOnClickListener {
            clearTmdb(context)
            Toast.makeText(context, "Cleared - using built-in keys", Toast.LENGTH_SHORT).show()
            dialog.dismiss()
        }
        tmdbSaveBtn.setOnClickListener {
            val typed = tmdbKeyInput.text.toString()
            if (saveTmdb(context, typed)) {
                Toast.makeText(context, "Saved", Toast.LENGTH_SHORT).show()
                dialog.dismiss()
            } else {
                tmdbKeyInput.error = tmdbKeyProblem(typed) ?: "Key looks too short"
            }
        }
        dialog.window?.setBackgroundDrawable(rounded(bgColor, 20))
        dialog.show()
        dialog.window?.apply {
            setLayout(
                (context.resources.displayMetrics.widthPixels * 0.88).toInt(),
                ViewGroup.LayoutParams.WRAP_CONTENT,
            )
        }
    }
}
