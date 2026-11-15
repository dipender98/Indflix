package com.multimovies

import android.app.Dialog
import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.graphics.drawable.StateListDrawable
import android.net.Uri
import android.text.InputType
import android.text.TextUtils
import android.text.method.PasswordTransformationMethod
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
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

        val SURFACE = Color.parseColor("#101319")
        val SURFACE_LOW = Color.parseColor("#161A21")
        val SURFACE_HIGH = Color.parseColor("#1E232B")
        val BORDER = Color.parseColor("#3B414B")
        val TEXT = Color.parseColor("#E3E4EA")
        val TEXT_DIM = Color.parseColor("#C1C6D1")
        val TEXT_MUTED = Color.parseColor("#AAB0BC")
        val TEXT_FAINT = Color.parseColor("#8B929E")
        val CONTROL = Color.parseColor("#DFDFDF")
        val ON_CONTROL = Color.parseColor("#14171C")
        val HAIRLINE = Color.argb(28, 255, 255, 255)
        val TONE_CONTAINER = Color.parseColor("#4E3766")
        val TONE_GLYPH = Color.parseColor("#F2DAFF")
        val TONE_BEAD = Color.parseColor("#D9BCE9")

        fun withAlpha(color: Int, alpha: Int): Int =
            Color.argb(alpha, Color.red(color), Color.green(color), Color.blue(color))

        fun rounded(
            color: Int,
            radius: Number,
            stroke: Number = 0,
            strokeColor: Int = HAIRLINE,
        ): GradientDrawable = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            setColor(color)
            cornerRadius = dp(radius).toFloat()
            if (stroke.toFloat() > 0f) setStroke(dp(stroke), strokeColor)
        }

        fun circle(color: Int, stroke: Number = 0, strokeColor: Int = HAIRLINE): GradientDrawable =
            GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(color)
                if (stroke.toFloat() > 0f) setStroke(dp(stroke), strokeColor)
            }

        fun iconChip(radius: Number): GradientDrawable = rounded(TONE_CONTAINER, radius)

        fun ripple(content: Drawable, color: Int = Color.argb(30, 255, 255, 255)): RippleDrawable =
            RippleDrawable(ColorStateList.valueOf(color), content, null)

        fun inputFill(focused: Boolean): GradientDrawable = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            setColor(SURFACE_HIGH)
            cornerRadius = dp(16).toFloat()
            if (focused) setStroke(dp(2), CONTROL) else setStroke(dp(1), HAIRLINE)
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

        fun pill(value: String, filled: Boolean = false): TextView =
            label(value, 12f, if (filled) ON_CONTROL else TEXT_DIM, medium = true).apply {
                gravity = Gravity.CENTER
                setPadding(dp(16), 0, dp(16), 0)
                background = ripple(
                    StateListDrawable().apply {
                        if (filled) {
                            addState(intArrayOf(android.R.attr.state_focused), rounded(CONTROL, 999, 2, ON_CONTROL))
                            addState(intArrayOf(), rounded(CONTROL, 999))
                        } else {
                            addState(intArrayOf(android.R.attr.state_focused), rounded(Color.TRANSPARENT, 999, 2, CONTROL))
                            addState(intArrayOf(), rounded(Color.TRANSPARENT, 999, 1, BORDER))
                        }
                    },
                )
                isClickable = true
                isFocusable = true
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, dp(48),
                )
            }

        fun eyeIcon(crossed: Boolean, color: Int): Drawable = object : Drawable() {
            private val pen = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.STROKE
                strokeWidth = dp(1.6f).toFloat()
                strokeCap = Paint.Cap.ROUND
                strokeJoin = Paint.Join.ROUND
                this.color = color
            }
            private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.FILL
                this.color = color
            }

            override fun getIntrinsicWidth(): Int = dp(20)

            override fun getIntrinsicHeight(): Int = dp(20)

            override fun draw(canvas: Canvas) {
                val w = bounds.width().toFloat()
                val h = bounds.height().toFloat()
                val cx = bounds.exactCenterX()
                val cy = bounds.exactCenterY()
                val halfW = w * 0.42f
                val halfH = h * 0.26f
                val eye = Path().apply {
                    moveTo(cx - halfW, cy)
                    cubicTo(cx - halfW * 0.45f, cy - halfH, cx + halfW * 0.45f, cy - halfH, cx + halfW, cy)
                    cubicTo(cx + halfW * 0.45f, cy + halfH, cx - halfW * 0.45f, cy + halfH, cx - halfW, cy)
                    close()
                }
                canvas.drawPath(eye, pen)
                canvas.drawCircle(cx, cy, h * 0.13f, fill)
                if (crossed) {
                    val d = w * 0.34f
                    canvas.drawLine(cx - d, cy + d, cx + d, cy - d, pen)
                }
            }

            override fun setAlpha(alpha: Int) {
                pen.alpha = alpha
                fill.alpha = alpha
            }

            override fun setColorFilter(colorFilter: ColorFilter?) {
                pen.colorFilter = colorFilter
                fill.colorFilter = colorFilter
            }

            @Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
            override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
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

        fun iconButton(description: String): FrameLayout {
            val root = FrameLayout(context).apply {
                background = ripple(
                    StateListDrawable().apply {
                        addState(intArrayOf(android.R.attr.state_focused), circle(SURFACE_HIGH, 2, CONTROL))
                        addState(intArrayOf(), circle(SURFACE_HIGH))
                    },
                )
                isClickable = true
                isFocusable = true
                contentDescription = description
                layoutParams = LinearLayout.LayoutParams(dp(44), dp(44))
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

        fun dividerRow(): View = View(context).apply {
            background = ColorDrawable(Color.argb(16, 255, 255, 255))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(1),
            )
        }

        val pages = FrameLayout(context).apply {
            setBackgroundColor(SURFACE)
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            )
        }

        val page = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            )
        }

        val header = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(20), dp(16), dp(14))
        }
        val titleRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        titleRow.addView(
            label("Multimovies", 22f, TEXT, medium = true),
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f),
        )
        val close = iconButton("Close")
        titleRow.addView(close)
        header.addView(titleRow)
        header.addView(
            label("Metadata and API keys", 13f, TEXT_DIM)
                .apply { setPadding(0, dp(6), 0, 0) },
        )
        page.addView(header)
        page.addView(dividerRow())

        val contentColumn = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(18), dp(20), dp(32))
        }
        val contentScroll = ScrollView(context).apply {
            overScrollMode = View.OVER_SCROLL_NEVER
            isVerticalFadingEdgeEnabled = false
            isFillViewport = false
            isHorizontalScrollBarEnabled = false
            isVerticalScrollBarEnabled = false
            addView(
                contentColumn,
                ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ),
            )
        }
        page.addView(
            contentScroll,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f,
            ),
        )
        pages.addView(page)

        val groupRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 0, 0, dp(10))
        }
        groupRow.addView(
            View(context).apply {
                background = circle(TONE_BEAD)
                layoutParams = LinearLayout.LayoutParams(dp(6), dp(6)).apply { rightMargin = dp(7) }
            },
        )
        groupRow.addView(
            label("KEYS & SERVICES", 11f, TEXT_DIM, medium = true, spacing = 0.14f),
        )
        groupRow.addView(
            View(context).apply {
                background = ColorDrawable(Color.argb(16, 255, 255, 255))
                layoutParams = LinearLayout.LayoutParams(0, dp(1), 1f).apply { leftMargin = dp(12) }
            },
        )
        contentColumn.addView(groupRow)

        val card = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(18), dp(18), dp(18))
            background = rounded(SURFACE_LOW, 24)
        }
        contentColumn.addView(card)

        val sectionHead = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val tile = FrameLayout(context).apply {
            background = iconChip(18)
            layoutParams = LinearLayout.LayoutParams(dp(52), dp(52)).apply { rightMargin = dp(14) }
        }
        tile.addView(
            ImageView(context).apply {
                scaleType = ImageView.ScaleType.CENTER_INSIDE
                setImageDrawable(tmdbIcon(TONE_GLYPH))
                setPadding(dp(13), dp(13), dp(13), dp(13))
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
        headTexts.addView(label("TMDB API Key", 16f, TEXT, medium = true).apply {
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        })
        headTexts.addView(
            label("Metadata and artwork", 13f, TEXT_FAINT)
                .apply { setPadding(0, dp(4), 0, 0) },
        )
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
            pillText.setTextColor(if (on) CONTROL else TEXT_MUTED)
            statePill.background = if (on) {
                rounded(withAlpha(CONTROL, 26), 999)
            } else {
                rounded(Color.argb(14, 255, 255, 255), 999)
            }
            pillDot.background = if (on) circle(CONTROL) else circle(Color.TRANSPARENT, 1, TEXT_FAINT)
        }
        paintState(savedNow)

        val description = label(
            "Optional - built-ins apply when empty. Paste the API Key (v3), the short 32-character key, not the long Read Access Token.",
            12f, TEXT_DIM,
        ).apply {
            setPadding(0, dp(16), 0, 0)
            setLineSpacing(dp(2).toFloat(), 1f)
        }
        card.addView(description)

        val tmdbKeyInput = EditText(context).apply {
            setText(tmdbApiKey().orEmpty())
            setHint("api key...")
            setHintTextColor(TEXT_FAINT)
            setTextColor(TEXT)
            setSingleLine(true)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            transformationMethod = PasswordTransformationMethod.getInstance()
            typeface = Typeface.MONOSPACE
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            setPadding(dp(14), dp(12), dp(10), dp(12))
            background = inputBackground()
            layoutParams = LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f,
            )
        }
        var keyVisible = false
        val eye = ImageView(context).apply {
            scaleType = ImageView.ScaleType.CENTER
            background = ripple(
                StateListDrawable().apply {
                    addState(intArrayOf(android.R.attr.state_focused), circle(SURFACE_HIGH, 2, CONTROL))
                    addState(intArrayOf(), circle(SURFACE_HIGH))
                },
            )
            isClickable = true
            isFocusable = true
            contentDescription = "Show key"
            setImageDrawable(eyeIcon(false, TEXT_DIM))
            layoutParams = LinearLayout.LayoutParams(dp(44), dp(44)).apply { leftMargin = dp(8) }
        }
        val keyRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = dp(14) }
            addView(tmdbKeyInput)
            addView(eye)
        }
        fun paintEye() {
            tmdbKeyInput.inputType = InputType.TYPE_CLASS_TEXT or
                if (keyVisible) {
                    InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
                } else {
                    InputType.TYPE_TEXT_VARIATION_PASSWORD
                }
            tmdbKeyInput.transformationMethod =
                if (keyVisible) null else PasswordTransformationMethod.getInstance()
            tmdbKeyInput.typeface = Typeface.MONOSPACE
            tmdbKeyInput.setSelection(tmdbKeyInput.text.length)
            eye.setImageDrawable(eyeIcon(keyVisible, if (keyVisible) TEXT else TEXT_DIM))
            eye.contentDescription = if (keyVisible) "Hide key" else "Show key"
        }
        eye.setOnClickListener {
            keyVisible = !keyVisible
            paintEye()
        }
        card.addView(keyRow)

        val tmdbStatusText = label(
            if (savedNow) "Your key is used for metadata first." else "Built-in keys are in use.",
            12f, TEXT_DIM,
        ).apply { setPadding(0, dp(10), 0, 0) }
        card.addView(tmdbStatusText)

        val chipRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = dp(16) }
        }
        val getApi = pill("Get key")
        getApi.layoutParams = LinearLayout.LayoutParams(0, dp(48), 1f).apply { rightMargin = dp(10) }
        val verify = pill("Verify")
        verify.layoutParams = LinearLayout.LayoutParams(0, dp(48), 1f)
        chipRow.addView(getApi)
        chipRow.addView(verify)
        card.addView(chipRow)

        val buttonRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = dp(10) }
        }
        val clearBtn = pill("Clear")
        clearBtn.layoutParams = LinearLayout.LayoutParams(0, dp(48), 1f).apply { rightMargin = dp(10) }
        val saveBtn = pill("Save", true)
        saveBtn.layoutParams = LinearLayout.LayoutParams(0, dp(48), 1f)
        buttonRow.addView(clearBtn)
        buttonRow.addView(saveBtn)
        card.addView(buttonRow)

        val sheet = FrameLayout(context).apply {
            background = ColorDrawable(SURFACE)
            addView(
                pages,
                FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT,
                ),
            )
        }

        val dialog = Dialog(context, android.R.style.Theme_Material_Dialog)
        dialog.setContentView(
            sheet,
            ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            ),
        )

        val enter = PathInterpolator(0.05f, 0.7f, 0.1f, 1f)
        val exit = PathInterpolator(0.4f, 0f, 1f, 1f)
        var closing = false
        fun dismissSheet() {
            if (closing) return
            closing = true
            pages.animate().alpha(0f)
                .setDuration(140)
                .setInterpolator(exit)
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
        dialog.window?.clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
        dialog.window?.setDimAmount(0f)
        dialog.window?.setWindowAnimations(0)
        dialog.window?.setLayout(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT,
        )
        dialog.show()

        // opaque entrance: the page surface is painted first, then content settles in
        pages.alpha = 0f
        pages.translationY = dp(12).toFloat()
        pages.animate().alpha(1f).translationY(0f)
            .setDuration(260)
            .setInterpolator(enter)
            .withLayer()
            .start()
    }
}
