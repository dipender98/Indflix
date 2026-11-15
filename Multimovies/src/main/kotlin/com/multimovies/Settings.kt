package com.multimovies

import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.graphics.drawable.RippleDrawable
import android.graphics.drawable.StateListDrawable
import android.net.Uri
import android.text.InputType
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.animation.DecelerateInterpolator
import android.view.animation.PathInterpolator
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import kotlin.math.roundToInt

object Settings {

    const val TMDB_LINK = "https://www.themoviedb.org/settings/api"

    private const val PREFS = "multimovies_settings"
    private const val TMDB_KEY = "tmdb_api_key"

    @Volatile
    private var appContext: Context? = null

    fun init(context: Context) {
        appContext = context.applicationContext
    }

    private fun normalizeKey(raw: String?): String = raw?.trim().orEmpty()

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

    fun openSettings(context: Context) {
        fun dp(v: Number): Int = (v.toFloat() * context.resources.displayMetrics.density).roundToInt()

        val TEXT = Color.parseColor("#E3E4EA")
        val TEXT_DIM = Color.parseColor("#C1C6D1")
        val TEXT_MUTED = Color.parseColor("#AAB0BC")
        val TEXT_FAINT = Color.parseColor("#8B929E")
        val BORDER = Color.parseColor("#3B414B")
        val ACCENT = Color.parseColor("#A9C7FF")
        val ACCENT_PALE = Color.parseColor("#D7E3FF")
        val ACCENT_TOP = Color.parseColor("#C4D8FF")
        val ON_PRIMARY = Color.parseColor("#003063")
        val SECONDARY_CONTAINER = Color.parseColor("#2A3A4D")
        val ON_SECONDARY_CONTAINER = Color.parseColor("#D8E3F6")
        val TONE_TOP = Color.parseColor("#4E3766")
        val TONE_BOTTOM = Color.parseColor("#392946")
        val TONE_GLYPH = Color.parseColor("#F2DAFF")
        val TONE_BEAD = Color.parseColor("#D9BCE9")
        val GLASS_STROKE = Color.argb(36, 255, 255, 255)

        fun withAlpha(color: Int, alpha: Int): Int =
            Color.argb(alpha, Color.red(color), Color.green(color), Color.blue(color))

        fun rounded(
            color: Int,
            radius: Number,
            stroke: Number = 0,
            strokeColor: Int = GLASS_STROKE,
        ): GradientDrawable = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            setColor(color)
            cornerRadius = dp(radius).toFloat()
            if (stroke.toFloat() > 0f) setStroke(dp(stroke), strokeColor)
        }

        fun circle(color: Int, stroke: Number = 0, strokeColor: Int = GLASS_STROKE): GradientDrawable =
            GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(color)
                if (stroke.toFloat() > 0f) setStroke(dp(stroke), strokeColor)
            }

        fun glassPanel(radius: Number = 24): GradientDrawable =
            GradientDrawable(
                GradientDrawable.Orientation.TOP_BOTTOM,
                intArrayOf(Color.parseColor("#1D2026"), Color.parseColor("#14171D")),
            ).apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dp(radius).toFloat()
                setStroke(dp(1), BORDER)
            }

        fun sheetFill(): GradientDrawable =
            GradientDrawable(
                GradientDrawable.Orientation.TOP_BOTTOM,
                intArrayOf(Color.parseColor("#171A21"), Color.parseColor("#0E1116")),
            ).apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dp(32).toFloat()
                setStroke(dp(1), Color.argb(36, 255, 255, 255))
            }

        fun iconChip(radius: Number): Drawable {
            val base = GradientDrawable(
                GradientDrawable.Orientation.TOP_BOTTOM,
                intArrayOf(TONE_TOP, TONE_BOTTOM),
            ).apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dp(radius).toFloat()
                setStroke(dp(1), withAlpha(TONE_GLYPH, 36))
            }
            val aura = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                gradientType = GradientDrawable.RADIAL_GRADIENT
                gradientRadius = dp(30).toFloat()
                setGradientCenter(0.5f, 0.34f)
                setColors(intArrayOf(withAlpha(TONE_GLYPH, 30), Color.TRANSPARENT))
            }
            val sheen = GradientDrawable(
                GradientDrawable.Orientation.TOP_BOTTOM,
                intArrayOf(Color.argb(18, 255, 255, 255), Color.TRANSPARENT),
            ).apply { cornerRadius = dp(radius).toFloat() }
            return LayerDrawable(arrayOf(base, aura, sheen))
        }

        fun ripple(content: Drawable, color: Int = Color.argb(30, 255, 255, 255)): RippleDrawable =
            RippleDrawable(ColorStateList.valueOf(color), content, null)

        fun inputFill(focused: Boolean): GradientDrawable =
            GradientDrawable(
                GradientDrawable.Orientation.TOP_BOTTOM,
                intArrayOf(Color.argb(16, 255, 255, 255), Color.argb(8, 255, 255, 255)),
            ).apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dp(16).toFloat()
                if (focused) setStroke(dp(1.4f).coerceAtLeast(1), ACCENT)
                else setStroke(dp(1), GLASS_STROKE)
            }

        fun inputBackground(): StateListDrawable = StateListDrawable().apply {
            addState(intArrayOf(android.R.attr.state_focused), inputFill(true))
            addState(intArrayOf(), inputFill(false))
        }

        fun label(
            value: String,
            size: Float,
            color: Int,
            medium: Boolean = false,
            spacing: Float = 0f,
        ): TextView = TextView(context).apply {
            text = value
            setTextSize(TypedValue.COMPLEX_UNIT_SP, size)
            setTextColor(color)
            if (medium) typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            if (spacing > 0f) letterSpacing = spacing
            includeFontPadding = false
        }

        fun pill(value: String, style: String): TextView {
            val textColor = when (style) {
                "filled" -> ON_PRIMARY
                "tonal" -> ON_SECONDARY_CONTAINER
                else -> TEXT_DIM
            }
            return label(value, 12f, textColor, medium = true).apply {
                gravity = Gravity.CENTER
                setPadding(dp(16), 0, dp(16), 0)
                background = ripple(
                    when (style) {
                        "filled" -> rounded(ACCENT_TOP, 999)
                        "tonal" -> rounded(SECONDARY_CONTAINER, 999)
                        else -> rounded(Color.TRANSPARENT, 999, 1, BORDER)
                    },
                )
                isClickable = true
                isFocusable = true
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, dp(44),
                ).apply { rightMargin = dp(8) }
            }
        }

        fun icon(kind: String, color: Int, size: Int): Drawable = object : Drawable() {
            private val pen = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.STROKE
                strokeWidth = dp(1.7f).toFloat()
                strokeCap = Paint.Cap.ROUND
                strokeJoin = Paint.Join.ROUND
                this.color = color
            }

            override fun getIntrinsicWidth(): Int = dp(size)
            override fun getIntrinsicHeight(): Int = dp(size)

            override fun draw(canvas: Canvas) {
                val w = bounds.width().toFloat()
                val h = bounds.height().toFloat()
                fun line(x1: Float, y1: Float, x2: Float, y2: Float) {
                    canvas.drawLine(x1 * w, y1 * h, x2 * w, y2 * h, pen)
                }
                when (kind) {
                    "close" -> {
                        val d = 0.29f
                        line(0.5f - d, 0.5f - d, 0.5f + d, 0.5f + d)
                        line(0.5f - d, 0.5f + d, 0.5f + d, 0.5f - d)
                    }
                }
            }

            override fun setAlpha(alpha: Int) {
                pen.alpha = alpha
            }

            override fun setColorFilter(colorFilter: ColorFilter?) {
                pen.colorFilter = colorFilter
            }

            @Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
            override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
        }

        fun circleButton(description: String): FrameLayout {
            val root = FrameLayout(context).apply {
                background = ripple(circle(Color.parseColor("#1D2026"), 1, BORDER))
                isClickable = true
                isFocusable = true
                contentDescription = description
                layoutParams = LinearLayout.LayoutParams(dp(42), dp(42))
            }
            root.addView(
                ImageView(context).apply {
                    scaleType = ImageView.ScaleType.CENTER
                    setImageDrawable(icon("close", TEXT, 18))
                    layoutParams = FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT,
                    )
                },
            )
            return root
        }

        fun tmdbIcon(color: Int): Drawable = object : Drawable() {
            private val pen = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.STROKE
                strokeWidth = dp(1.7f).toFloat()
                strokeCap = Paint.Cap.ROUND
                strokeJoin = Paint.Join.ROUND
                this.color = color
            }

            override fun getIntrinsicWidth(): Int = dp(22)
            override fun getIntrinsicHeight(): Int = dp(22)

            override fun draw(canvas: Canvas) {
                val w = bounds.width().toFloat()
                val h = bounds.height().toFloat()
                canvas.drawCircle(w * 0.31f, h * 0.31f, w * 0.17f, pen)
                canvas.drawLine(w * 0.43f, h * 0.44f, w * 0.82f, h * 0.83f, pen)
                canvas.drawLine(w * 0.66f, h * 0.72f, w * 0.73f, h * 0.65f, pen)
                canvas.drawLine(w * 0.755f, h * 0.815f, w * 0.825f, h * 0.745f, pen)
            }

            override fun setAlpha(alpha: Int) {
                pen.alpha = alpha
            }

            override fun setColorFilter(colorFilter: ColorFilter?) {
                pen.colorFilter = colorFilter
            }

            @Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
            override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
        }

        val pages = FrameLayout(context).apply {
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            )
        }

        // faint aurora from the top corners: primary on one side, tertiary on the other
        fun glow(color: Int, radius: Int, size: Int): View = View(context).apply {
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                gradientType = GradientDrawable.RADIAL_GRADIENT
                gradientRadius = dp(radius).toFloat()
                setGradientCenter(0.5f, 0.5f)
                setColors(intArrayOf(color, Color.TRANSPARENT))
            }
            layoutParams = FrameLayout.LayoutParams(dp(size), dp(size))
        }
        val glowStart = glow(withAlpha(ACCENT, 26), 170, 340)
        glowStart.layoutParams = FrameLayout.LayoutParams(dp(340), dp(340)).apply {
            gravity = Gravity.TOP or Gravity.START
            leftMargin = dp(-130)
            topMargin = dp(-130)
        }
        pages.addView(glowStart)
        val glowEnd = glow(withAlpha(TONE_BEAD, 20), 160, 320)
        glowEnd.layoutParams = FrameLayout.LayoutParams(dp(320), dp(320)).apply {
            gravity = Gravity.TOP or Gravity.END
            rightMargin = dp(-140)
            topMargin = dp(-120)
        }
        pages.addView(glowEnd)

        val contentColumn = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
            )
        }
        val contentScroll = ScrollView(context).apply {
            overScrollMode = View.OVER_SCROLL_NEVER
            isVerticalFadingEdgeEnabled = false
            isFillViewport = false
            isHorizontalScrollBarEnabled = false
            isVerticalScrollBarEnabled = false
            addView(
                contentColumn,
                FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                ),
            )
        }

        val header = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(20), dp(14), dp(16), dp(10))
        }
        val heading = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        heading.addView(label("INDFLIX", 10f, TEXT_FAINT, medium = true, spacing = 0.26f))
        heading.addView(
            label("Settings", 24f, TEXT, medium = true).apply {
                setPadding(0, dp(3), 0, 0)
            },
        )
        header.addView(heading)
        val close = circleButton("Close")
        header.addView(close)
        contentColumn.addView(
            header,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
            ),
        )

        val card = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(14), dp(16), dp(16))
            background = glassPanel(24)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply {
                leftMargin = dp(16)
                rightMargin = dp(16)
                bottomMargin = dp(18)
            }
        }
        contentColumn.addView(card)

        val sectionHead = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val tile = FrameLayout(context).apply {
            background = iconChip(16)
            elevation = dp(2).toFloat()
            layoutParams = LinearLayout.LayoutParams(dp(46), dp(46)).apply { rightMargin = dp(12) }
        }
        tile.addView(
            ImageView(context).apply {
                scaleType = ImageView.ScaleType.CENTER_INSIDE
                setImageDrawable(tmdbIcon(TONE_GLYPH))
                setPadding(dp(12), dp(12), dp(12), dp(12))
                layoutParams = FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT,
                )
            },
        )
        sectionHead.addView(tile)
        val headTexts = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        headTexts.addView(label("TMDB API Key", 15f, TEXT, medium = true))
        val overline = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(2), 0, 0)
        }
        overline.addView(View(context).apply {
            background = circle(TONE_BEAD)
            layoutParams = LinearLayout.LayoutParams(dp(6), dp(6)).apply { rightMargin = dp(7) }
        })
        overline.addView(
            label("KEYS & SERVICES", 10f, TEXT_DIM, medium = true, spacing = 0.12f),
        )
        headTexts.addView(overline)
        sectionHead.addView(headTexts)

        val savedNow = tmdbApiKey() != null
        val pillDot = View(context).apply {
            layoutParams = LinearLayout.LayoutParams(dp(6), dp(6)).apply { rightMargin = dp(6) }
        }
        val pillText = label(
            if (savedNow) "Saved" else "Not set",
            11f, TEXT_MUTED, medium = true,
        )
        val statePill = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(11), dp(6), dp(11), dp(6))
            addView(pillDot)
            addView(pillText)
        }
        sectionHead.addView(statePill)
        card.addView(sectionHead)

        fun paintState(on: Boolean) {
            pillText.text = if (on) "Saved" else "Not set"
            pillText.setTextColor(if (on) ACCENT_PALE else TEXT_MUTED)
            statePill.background = if (on) {
                rounded(withAlpha(ACCENT, 41), 999)
            } else {
                rounded(Color.argb(14, 255, 255, 255), 999)
            }
            pillDot.background = if (on) circle(ACCENT) else circle(Color.TRANSPARENT, 1, TEXT_FAINT)
        }
        paintState(savedNow)

        val description = label(
            "Optional - built-ins apply when empty. Paste the API Key (v3), the short 32-character key, not the long Read Access Token.",
            12f, TEXT_DIM,
        ).apply { setPadding(0, dp(12), 0, 0) }
        card.addView(description)

        val tmdbKeyInput = EditText(context).apply {
            setText(tmdbApiKey().orEmpty())
            setHint("api key...")
            setHintTextColor(TEXT_FAINT)
            setTextColor(TEXT)
            setSingleLine(true)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            typeface = Typeface.MONOSPACE
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            setPadding(dp(14), dp(12), dp(14), dp(12))
            background = inputBackground()
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = dp(12) }
        }
        card.addView(tmdbKeyInput)

        val tmdbStatusText = label(
            if (savedNow) "Your key is used for metadata first." else "Built-in keys are in use.",
            12f, TEXT_DIM,
        ).apply { setPadding(0, dp(10), 0, 0) }
        card.addView(tmdbStatusText)

        val chipRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(14), 0, 0)
        }
        val getApi = pill("Get key", "outlined")
        getApi.layoutParams = LinearLayout.LayoutParams(0, dp(44), 1f).apply { rightMargin = dp(8) }
        val verify = pill("Verify", "tonal")
        verify.layoutParams = LinearLayout.LayoutParams(0, dp(44), 1f)
        chipRow.addView(getApi)
        chipRow.addView(verify)
        card.addView(chipRow)

        val buttonRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(10), 0, 0)
        }
        val clearBtn = pill("Clear", "outlined")
        clearBtn.layoutParams = LinearLayout.LayoutParams(0, dp(44), 1f).apply { rightMargin = dp(8) }
        val saveBtn = pill("Save", "filled")
        saveBtn.layoutParams = LinearLayout.LayoutParams(0, dp(44), 1f)
        buttonRow.addView(clearBtn)
        buttonRow.addView(saveBtn)
        card.addView(buttonRow)
        pages.addView(contentScroll)

        // specular highlight along the top edge of the glass sheet
        val specular = View(context).apply {
            background = GradientDrawable(
                GradientDrawable.Orientation.LEFT_RIGHT,
                intArrayOf(
                    Color.TRANSPARENT,
                    Color.argb(72, 255, 255, 255),
                    Color.argb(72, 255, 255, 255),
                    Color.TRANSPARENT,
                ),
            )
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(1),
            ).apply { topMargin = dp(1) }
        }
        pages.addView(specular)

        val sheet = FrameLayout(context).apply {
            background = sheetFill()
            clipToOutline = true
            elevation = dp(18).toFloat()
            addView(
                pages,
                FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ),
            )
        }

        val dialog = AlertDialog.Builder(context, android.R.style.Theme_Material_Dialog)
            .setView(sheet)
            .create()

        var closing = false
        fun dismissSheet() {
            if (closing) return
            closing = true
            sheet.animate().scaleX(0.97f).scaleY(0.97f).alpha(0f)
                .setDuration(170)
                .withEndAction { dialog.dismiss() }
                .start()
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
        verify.setOnClickListener { tmdbVerifyNow(tmdbKeyInput.text.toString()) }
        getApi.setOnClickListener {
            runCatching {
                context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(TMDB_LINK)))
            }.onFailure {
                Toast.makeText(context, "No browser found", Toast.LENGTH_SHORT).show()
            }
        }
        clearBtn.setOnClickListener {
            clearTmdb(context)
            paintState(false)
            Toast.makeText(context, "Cleared - using built-in keys", Toast.LENGTH_SHORT).show()
            dismissSheet()
        }
        saveBtn.setOnClickListener {
            val typed = tmdbKeyInput.text.toString()
            if (saveTmdb(context, typed)) {
                paintState(typed.trim().isNotEmpty())
                Toast.makeText(context, "Saved", Toast.LENGTH_SHORT).show()
                dismissSheet()
            } else {
                tmdbKeyInput.error = tmdbKeyProblem(typed) ?: "Key looks too short"
            }
        }
        close.setOnClickListener { dismissSheet() }
        dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        dialog.window?.setDimAmount(0.55f)
        dialog.show()
        val width = (context.resources.displayMetrics.widthPixels * 0.92f).roundToInt()
        dialog.window?.setLayout(minOf(width, dp(560)), ViewGroup.LayoutParams.WRAP_CONTENT)

        // expressive entrance: the sheet settles, content staggers up
        val enter = PathInterpolator(0.05f, 0.7f, 0.1f, 1f)
        sheet.scaleX = 0.96f
        sheet.scaleY = 0.96f
        sheet.alpha = 0f
        sheet.animate().scaleX(1f).scaleY(1f).alpha(1f)
            .setDuration(300)
            .setInterpolator(enter)
            .start()
        for (index in 0 until contentColumn.childCount) {
            val child = contentColumn.getChildAt(index)
            child.translationY = dp(14).toFloat()
            child.alpha = 0f
            child.animate().translationY(0f).alpha(1f)
                .setStartDelay((index * 60).toLong())
                .setDuration(260)
                .setInterpolator(DecelerateInterpolator(1.6f))
                .start()
        }
    }
}
