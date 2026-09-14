package com.indstream

import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.text.InputType
import android.text.method.LinkMovementMethod
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
        val fieldColor = Color.parseColor("#19191C")
        val textColor = Color.parseColor("#EDEDF2")
        val hintColor = Color.parseColor("#A3A3AD")
        val accentColor = Color.parseColor("#2F7CF6")
        val linkColor = Color.parseColor("#7FA9F5")

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

        val keyHeader = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val keyLabel = TextView(context).apply {
            text = "Wyzie Subs API Key"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            setTextColor(textColor)
            setTypeface(null, Typeface.BOLD)
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        val verifyLink = TextView(context).apply {
            text = "Verify"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            setTextColor(linkColor)
            setPadding(dp(8), dp(4), dp(8), dp(4))
            isClickable = true
            isFocusable = true
        }
        keyHeader.addView(keyLabel)
        keyHeader.addView(verifyLink)
        body.addView(keyHeader)
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
            // No fading-edge gradient or overscroll glow near the edges - flat only.
            isVerticalFadingEdgeEnabled = false
            isHorizontalFadingEdgeEnabled = false
            overScrollMode = View.OVER_SCROLL_NEVER
            addView(body)
        }
        // CSX pattern: AlertDialog with the Material dialog theme and a rounded
        // window background - no floating-theme edge gradient.
        val dialog = AlertDialog.Builder(context, android.R.style.Theme_Material_Dialog)
            .setView(scroll)
            .create()
        fun verifyNow(key: String) {
            if (!isValidKey(key)) {
                input.error = "Key looks too short"
                return
            }
            status.text = "Verifying key..."
            Thread {
                val msg = runCatching {
                    kotlinx.coroutines.runBlocking { WyzieSubs.testKey(key) }.second
                }.getOrDefault("Verify failed - no network?")
                input.post { status.text = msg }
            }.start()
        }
        verifyLink.setOnClickListener { verifyNow(input.text.toString()) }
        clearBtn.setOnClickListener {
            clear(context)
            Toast.makeText(context, "Cleared - using built-in subtitles", Toast.LENGTH_SHORT).show()
            dialog.dismiss()
        }
        saveBtn.setOnClickListener {
            val typed = input.text.toString()
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
                input.error = "Key looks too short"
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
