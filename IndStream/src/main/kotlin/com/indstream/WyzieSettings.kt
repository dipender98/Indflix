package com.indstream

import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.text.InputType
import android.text.method.LinkMovementMethod
import android.util.TypedValue
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

/** FILE: WyzieSettings.kt - optional user Wyzie Subs API key store + settings dialog. */
object WyzieSettings {

    /** Where users redeem a free key (shown under the field in small font). */
    const val KEY_LINK = "https://store.wyzie.io/redeem"

    private const val PREFS = "indstream_settings"
    private const val KEY = "wyzie_api_key"

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

    private fun load() {
        val ctx = appContext ?: return
        cached = runCatching {
            ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, "").orEmpty()
        }.getOrDefault("")
        loaded = true
    }

    /** Dark (not pitch-black) settings dialog: key field, redeem link, save/clear. */
    fun openSettings(context: Context) {
        val bgColor = Color.parseColor("#23232F")
        val cardColor = Color.parseColor("#2D2D40")
        val fieldColor = Color.parseColor("#3B3B54")
        val textColor = Color.parseColor("#EDEDF2")
        val hintColor = Color.parseColor("#9A9AB0")
        val accentColor = Color.parseColor("#7C6CF5")
        val linkColor = Color.parseColor("#8FA8FF")

        fun dp(v: Int): Int = (v * context.resources.displayMetrics.density).toInt()
        fun rounded(color: Int, radiusDp: Int): GradientDrawable =
            GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                setColor(color)
                cornerRadius = dp(radiusDp).toFloat()
            }

        val body = LinearLayout(context).apply {
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
                body.addView(this)
            }

        addLabel("Subtitles", 18f, textColor, bold = true)
        addLabel("Optional - without a key the built-in subtitles are used.", 12f, hintColor)
        val spacer1 = TextView(context).apply { text = "" }
        body.addView(spacer1)

        addLabel("Wyzie Subs API Key", 14f, textColor, bold = true)
        val input = EditText(context).apply {
            setText(apiKey().orEmpty())
            setHint("wyzie-...")
            setHintTextColor(hintColor)
            setTextColor(textColor)
            setSingleLine(true)
            inputType = InputType.TYPE_CLASS_TEXT
            typeface = Typeface.MONOSPACE
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            background = rounded(fieldColor, 10)
            setPadding(dp(12), dp(10), dp(12), dp(10))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = dp(6) }
        }
        body.addView(input)

        val linkView = TextView(context).apply {
            text = "Get a free key at store.wyzie.io/redeem"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            setTextColor(linkColor)
            movementMethod = LinkMovementMethod.getInstance()
            setPadding(0, dp(6), 0, 0)
            setOnClickListener {
                runCatching {
                    context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(KEY_LINK)))
                }.onFailure {
                    Toast.makeText(context, "No browser found", Toast.LENGTH_SHORT).show()
                }
            }
        }
        body.addView(linkView)

        val status = TextView(context).apply {
            text = if (apiKey() != null) "Wyzie Subs active - your key will be used."
            else "No key saved - using built-in subtitles."
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            setTextColor(hintColor)
            setPadding(0, dp(10), 0, dp(12))
        }
        body.addView(status)

        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END
        }
        val clearBtn = Button(context).apply {
            text = "Clear"
            setTextColor(textColor)
            background = rounded(Color.TRANSPARENT, 10)
        }
        val saveBtn = Button(context).apply {
            text = "Save"
            setTextColor(Color.WHITE)
            setTypeface(null, Typeface.BOLD)
            background = rounded(accentColor, 10)
        }
        row.addView(clearBtn)
        row.addView(saveBtn)
        body.addView(row)

        val scroll = ScrollView(context).apply {
            setBackgroundColor(bgColor)
            setPadding(dp(4), dp(4), dp(4), dp(4))
            addView(body)
        }
        val dialog = AlertDialog.Builder(context).setView(scroll).create()
        clearBtn.setOnClickListener {
            clear(context)
            Toast.makeText(context, "Cleared - using built-in subtitles", Toast.LENGTH_SHORT).show()
            dialog.dismiss()
        }
        saveBtn.setOnClickListener {
            if (save(context, input.text.toString())) {
                Toast.makeText(context, "Saved", Toast.LENGTH_SHORT).show()
                dialog.dismiss()
            } else {
                input.error = "Key looks too short"
            }
        }
        dialog.show()
        dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
    }
}
