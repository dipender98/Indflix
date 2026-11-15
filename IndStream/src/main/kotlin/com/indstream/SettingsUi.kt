package com.indstream

import android.app.AlertDialog
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
import android.graphics.drawable.LayerDrawable
import android.graphics.drawable.RippleDrawable
import android.graphics.drawable.StateListDrawable
import android.net.Uri
import android.text.InputType
import android.text.TextUtils
import android.text.method.PasswordTransformationMethod
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.animation.DecelerateInterpolator
import android.view.animation.OvershootInterpolator
import android.view.animation.PathInterpolator
import android.widget.ArrayAdapter
import android.widget.AutoCompleteTextView
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.indstream.foryou.AiService
import com.indstream.foryou.ForYouEngine
import com.indstream.foryou.ForYouRefresh
import com.indstream.foryou.SimklHistory
import kotlinx.coroutines.Job
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

object SettingsUi {

    private val BORDER = Color.parseColor("#3B414B")
    private val TEXT = Color.parseColor("#E3E4EA")
    private val TEXT_DIM = Color.parseColor("#C1C6D1")
    private val TEXT_MUTED = Color.parseColor("#AAB0BC")
    private val TEXT_FAINT = Color.parseColor("#8B929E")
    private val ACCENT = Color.parseColor("#A9C7FF")
    private val ACCENT_PALE = Color.parseColor("#D7E3FF")
    private val ACCENT_TOP = Color.parseColor("#C4D8FF")
    private val ON_PRIMARY = Color.parseColor("#003063")
    private val SECONDARY_CONTAINER = Color.parseColor("#2A3A4D")
    private val ON_SECONDARY_CONTAINER = Color.parseColor("#D8E3F6")
    private val SUCCESS = Color.parseColor("#7FD9A0")
    private val WARNING = Color.parseColor("#FFB4AB")

    private val GLASS_STROKE = Color.argb(36, 255, 255, 255)
    private val GLASS_STROKE_SOFT = Color.argb(22, 255, 255, 255)

    private class Tone(
        val top: Int,
        val bottom: Int,
        val glyph: Int,
        val bead: Int,
    )

    private val TONE_PRIMARY = Tone(
        Color.parseColor("#2B4F86"),
        Color.parseColor("#1E3B64"),
        Color.parseColor("#D7E3FF"),
        Color.parseColor("#A9C7FF"),
    )
    private val TONE_SECONDARY = Tone(
        Color.parseColor("#374C64"),
        Color.parseColor("#26384A"),
        Color.parseColor("#D8E3F6"),
        Color.parseColor("#BCC7DA"),
    )
    private val TONE_TERTIARY = Tone(
        Color.parseColor("#4E3766"),
        Color.parseColor("#392946"),
        Color.parseColor("#F2DAFF"),
        Color.parseColor("#D9BCE9"),
    )

    private class SectionSpec(
        val id: String,
        val group: String,
        val title: String,
        val subtitle: String,
        val icon: String,
        val tone: Tone,
        val build: (LinearLayout) -> Unit,
    )

    private class StatePill(
        val root: LinearLayout,
        val dot: View,
        val text: TextView,
    )

    private class Option(
        val root: LinearLayout,
        val dot: View,
        val summary: TextView,
    )

    private class Secret(val root: LinearLayout, val input: EditText)

    private class RankedRow(val key: String, val title: String)

    private enum class ProviderProbeState { NOT_TESTED, TESTING, UP, DOWN }

    fun open(context: Context) {
        val density = context.resources.displayMetrics.density
        fun dp(v: Number): Int = (v.toFloat() * density).roundToInt()

        fun rounded(
            color: Int,
            radius: Number,
            stroke: Number = 0,
            strokeColor: Int = BORDER,
        ): GradientDrawable = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            setColor(color)
            cornerRadius = dp(radius).toFloat()
            if (stroke.toFloat() > 0f) setStroke(dp(stroke), strokeColor)
        }

        fun circle(color: Int, stroke: Number = 0, strokeColor: Int = BORDER): GradientDrawable =
            GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(color)
                if (stroke.toFloat() > 0f) setStroke(dp(stroke), strokeColor)
            }

        // frosted fill: bright top light falling off to a faint base, hairline rim
        fun glassFill(
            radius: Number = 22,
            topAlpha: Int = 26,
            bottomAlpha: Int = 10,
            stroke: Int = GLASS_STROKE,
        ): GradientDrawable =
            GradientDrawable(
                GradientDrawable.Orientation.TOP_BOTTOM,
                intArrayOf(Color.argb(topAlpha, 255, 255, 255), Color.argb(bottomAlpha, 255, 255, 255)),
            ).apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dp(radius).toFloat()
                setStroke(dp(1), stroke)
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

        fun glassPanel(radius: Number = 24): GradientDrawable =
            GradientDrawable(
                GradientDrawable.Orientation.TOP_BOTTOM,
                intArrayOf(Color.parseColor("#1D2026"), Color.parseColor("#14171D")),
            ).apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dp(radius).toFloat()
                setStroke(dp(1), BORDER)
            }

        fun withAlpha(color: Int, alpha: Int): Int =
            Color.argb(alpha, Color.red(color), Color.green(color), Color.blue(color))

        fun iconChip(tone: Tone, radius: Number): Drawable {
            val base = GradientDrawable(
                GradientDrawable.Orientation.TOP_BOTTOM,
                intArrayOf(tone.top, tone.bottom),
            ).apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dp(radius).toFloat()
                setStroke(dp(1), withAlpha(tone.glyph, 36))
            }
            val aura = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                gradientType = GradientDrawable.RADIAL_GRADIENT
                gradientRadius = dp(30).toFloat()
                setGradientCenter(0.5f, 0.34f)
                setColors(intArrayOf(withAlpha(tone.glyph, 30), Color.TRANSPARENT))
            }
            val sheen = GradientDrawable(
                GradientDrawable.Orientation.TOP_BOTTOM,
                intArrayOf(Color.argb(18, 255, 255, 255), Color.TRANSPARENT),
            ).apply { cornerRadius = dp(radius).toFloat() }
            return LayerDrawable(arrayOf(base, aura, sheen))
        }

        fun inputFill(focused: Boolean): GradientDrawable =
            GradientDrawable(
                GradientDrawable.Orientation.TOP_BOTTOM,
                if (focused) {
                    intArrayOf(Color.argb(34, 255, 255, 255), Color.argb(16, 255, 255, 255))
                } else {
                    intArrayOf(Color.argb(16, 255, 255, 255), Color.argb(8, 255, 255, 255))
                },
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

        fun ripple(content: Drawable, color: Int = Color.argb(30, 255, 255, 255)): RippleDrawable =
            RippleDrawable(ColorStateList.valueOf(color), content, null)

        fun glow(color: Int, radius: Int): View {
            val view = View(context)
            view.background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                gradientType = GradientDrawable.RADIAL_GRADIENT
                gradientRadius = dp(radius).toFloat()
                setGradientCenter(0.5f, 0.5f)
                setColors(intArrayOf(color, Color.TRANSPARENT))
            }
            return view
        }

        val control = dp(44)
        val gap = dp(8)

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

        fun icon(kind: String, color: Int, size: Int = 22): Drawable = object : Drawable() {
            private val pen = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.STROKE
                strokeWidth = dp(1.7f).toFloat()
                strokeCap = Paint.Cap.ROUND
                strokeJoin = Paint.Join.ROUND
                this.color = color
            }
            private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.FILL
                this.color = color
            }

            override fun getIntrinsicWidth(): Int = dp(size)
            override fun getIntrinsicHeight(): Int = dp(size)

            override fun draw(canvas: Canvas) {
                val w = bounds.width().toFloat()
                val h = bounds.height().toFloat()
                fun rr(left: Float, top: Float, right: Float, bottom: Float, radius: Float) {
                    canvas.drawRoundRect(left * w, top * h, right * w, bottom * h, dp(radius).toFloat(), dp(radius).toFloat(), pen)
                }
                fun line(x1: Float, y1: Float, x2: Float, y2: Float) {
                    canvas.drawLine(x1 * w, y1 * h, x2 * w, y2 * h, pen)
                }
                when (kind) {
                    "provider" -> {
                        canvas.drawCircle(w / 2f, h / 2f, w * 0.34f, pen)
                        canvas.drawOval(w * 0.30f, h * 0.16f, w * 0.70f, h * 0.84f, pen)
                        line(0.16f, 0.5f, 0.84f, 0.5f)
                    }
                    "catalogue" -> {
                        line(0.22f, 0.28f, 0.78f, 0.28f)
                        line(0.22f, 0.5f, 0.78f, 0.5f)
                        line(0.22f, 0.72f, 0.58f, 0.72f)
                    }
                    "foryou" -> {
                        val p = Path().apply {
                            moveTo(w * 0.5f, h * 0.08f)
                            lineTo(w * 0.62f, h * 0.38f)
                            lineTo(w * 0.92f, h * 0.5f)
                            lineTo(w * 0.62f, h * 0.62f)
                            lineTo(w * 0.5f, h * 0.92f)
                            lineTo(w * 0.38f, h * 0.62f)
                            lineTo(w * 0.08f, h * 0.5f)
                            lineTo(w * 0.38f, h * 0.38f)
                            close()
                        }
                        canvas.drawPath(p, pen)
                    }
                    "servers" -> {
                        rr(0.14f, 0.16f, 0.86f, 0.42f, 4f)
                        rr(0.14f, 0.58f, 0.86f, 0.84f, 4f)
                        canvas.drawCircle(w * 0.72f, h * 0.29f, w * 0.035f, fill)
                        canvas.drawCircle(w * 0.72f, h * 0.71f, w * 0.035f, fill)
                    }
                    "addons" -> {
                        rr(0.14f, 0.18f, 0.86f, 0.82f, 5f)
                        val p = Path().apply {
                            moveTo(w * 0.44f, h * 0.38f)
                            lineTo(w * 0.44f, h * 0.62f)
                            lineTo(w * 0.64f, h * 0.5f)
                            close()
                        }
                        canvas.drawPath(p, fill)
                    }
                    "m3u" -> {
                        line(0.2f, 0.26f, 0.58f, 0.26f)
                        line(0.2f, 0.44f, 0.58f, 0.44f)
                        line(0.2f, 0.62f, 0.46f, 0.62f)
                        canvas.drawCircle(w * 0.66f, h * 0.66f, w * 0.095f, fill)
                        line(0.75f, 0.3f, 0.75f, 0.66f)
                    }
                    "subs" -> {
                        rr(0.12f, 0.16f, 0.88f, 0.66f, 5f)
                        val tail = Path().apply {
                            moveTo(w * 0.3f, h * 0.66f)
                            lineTo(w * 0.3f, h * 0.86f)
                            lineTo(w * 0.48f, h * 0.66f)
                        }
                        canvas.drawPath(tail, pen)
                        line(0.28f, 0.33f, 0.72f, 0.33f)
                        line(0.28f, 0.5f, 0.6f, 0.5f)
                    }
                    "tmdb" -> {
                        canvas.drawCircle(w * 0.31f, h * 0.31f, w * 0.17f, pen)
                        line(0.43f, 0.44f, 0.82f, 0.83f)
                        line(0.66f, 0.72f, 0.73f, 0.65f)
                        line(0.755f, 0.815f, 0.825f, 0.745f)
                    }
                    "ai" -> {
                        rr(0.2f, 0.2f, 0.8f, 0.8f, 4f)
                        rr(0.38f, 0.38f, 0.62f, 0.62f, 2.5f)
                        line(0.34f, 0.08f, 0.34f, 0.2f)
                        line(0.5f, 0.08f, 0.5f, 0.2f)
                        line(0.66f, 0.08f, 0.66f, 0.2f)
                        line(0.34f, 0.8f, 0.34f, 0.92f)
                        line(0.5f, 0.8f, 0.5f, 0.92f)
                        line(0.66f, 0.8f, 0.66f, 0.92f)
                        line(0.08f, 0.34f, 0.2f, 0.34f)
                        line(0.08f, 0.5f, 0.2f, 0.5f)
                        line(0.08f, 0.66f, 0.2f, 0.66f)
                        line(0.8f, 0.34f, 0.92f, 0.34f)
                        line(0.8f, 0.5f, 0.92f, 0.5f)
                        line(0.8f, 0.66f, 0.92f, 0.66f)
                    }
                    "close" -> {
                        val d = 0.29f
                        line(0.5f - d, 0.5f - d, 0.5f + d, 0.5f + d)
                        line(0.5f - d, 0.5f + d, 0.5f + d, 0.5f - d)
                    }
                    "back" -> {
                        line(0.78f, 0.5f, 0.22f, 0.5f)
                        line(0.22f, 0.5f, 0.42f, 0.3f)
                        line(0.22f, 0.5f, 0.42f, 0.7f)
                    }
                    "chevron" -> {
                        line(0.42f, 0.3f, 0.6f, 0.5f)
                        line(0.42f, 0.7f, 0.6f, 0.5f)
                    }
                    "up" -> {
                        line(0.5f, 0.72f, 0.5f, 0.3f)
                        line(0.32f, 0.48f, 0.5f, 0.3f)
                        line(0.68f, 0.48f, 0.5f, 0.3f)
                    }
                    "down" -> {
                        line(0.5f, 0.28f, 0.5f, 0.7f)
                        line(0.32f, 0.52f, 0.5f, 0.7f)
                        line(0.68f, 0.52f, 0.5f, 0.7f)
                    }
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

        fun pill(value: String, primary: Boolean = false): TextView =
            label(value, 12f, if (primary) ON_PRIMARY else TEXT_DIM, medium = true).apply {
                gravity = Gravity.CENTER
                setPadding(dp(16), 0, dp(16), 0)
                background = ripple(
                    if (primary) {
                        rounded(ACCENT_TOP, 999)
                    } else {
                        rounded(Color.TRANSPARENT, 999, 1, BORDER)
                    },
                )
                isClickable = true
                isFocusable = true
                if (primary) elevation = dp(3).toFloat()
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, control,
                ).apply { rightMargin = gap }
            }

        fun chip(value: String): TextView = pill(value)

        fun textButton(value: String): TextView =
            label(value, 12f, ACCENT_PALE, medium = true).apply {
                gravity = Gravity.CENTER
                setPadding(dp(14), 0, dp(14), 0)
                background = ripple(rounded(Color.TRANSPARENT, 999))
                isClickable = true
                isFocusable = true
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, dp(36),
                )
            }

        fun markChip(target: TextView, on: Boolean) {
            target.setTextColor(if (on) ON_SECONDARY_CONTAINER else TEXT_DIM)
            target.background = ripple(
                if (on) rounded(SECONDARY_CONTAINER, 999) else rounded(Color.TRANSPARENT, 999, 1, BORDER),
            )
        }

        fun action(value: String, primary: Boolean): TextView = pill(value, primary).apply {
            setTextColor(if (primary) ON_PRIMARY else TEXT)
        }

        fun field(value: String, hint: String): EditText = EditText(context).apply {
            setText(value)
            setHint(hint)
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
            )
        }

        fun modelField(value: String, hint: String): AutoCompleteTextView =
            AutoCompleteTextView(context).apply {
                setText(value)
                setHint(hint)
                setHintTextColor(TEXT_FAINT)
                setTextColor(TEXT)
                setSingleLine(true)
                threshold = 0
                inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
                typeface = Typeface.MONOSPACE
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
                setPadding(dp(14), dp(12), dp(14), dp(12))
                dropDownWidth = (context.resources.displayMetrics.widthPixels * 0.82f).toInt()
                background = inputBackground()
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                )
            }

        fun secret(value: String, hint: String): Secret {
            val input = field(value, hint).apply {
                setPadding(dp(14), dp(12), dp(10), dp(12))
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            }
            val toggle = ImageView(context).apply {
                scaleType = ImageView.ScaleType.CENTER
                background = ripple(circle(Color.argb(16, 255, 255, 255), 1, GLASS_STROKE))
                isClickable = true
                isFocusable = true
                layoutParams = LinearLayout.LayoutParams(control, control).apply { leftMargin = gap }
            }
            val root = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                addView(input)
                addView(toggle)
            }
            fun paint(visible: Boolean) {
                input.inputType = if (visible) {
                    InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
                } else {
                    InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
                }
                input.transformationMethod =
                    if (visible) null else PasswordTransformationMethod.getInstance()
                input.typeface = Typeface.MONOSPACE
                input.setSelection(input.text.length)
                toggle.setImageDrawable(eyeIcon(visible, if (visible) ACCENT_PALE else TEXT_DIM))
                toggle.contentDescription = if (visible) "Hide key" else "Show key"
            }
            var visible = false
            paint(false)
            toggle.setOnClickListener {
                visible = !visible
                paint(visible)
            }
            return Secret(root, input)
        }

        fun dividerRow(): View = View(context).apply {
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1))
            setBackgroundColor(Color.argb(16, 255, 255, 255))
        }

        fun optionList(): LinearLayout = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
            )
        }

        fun option(title: String, summary: String): Option {
            val dot = View(context).apply {
                layoutParams = LinearLayout.LayoutParams(dp(18), dp(18)).apply { leftMargin = dp(12) }
            }
            val texts = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            }
            val titleView = label(title, 13f, TEXT, medium = true)
            val summaryView = label(summary, 11f, TEXT_DIM).apply {
                setPadding(0, dp(3), 0, 0)
            }
            texts.addView(titleView)
            texts.addView(summaryView)
            val root = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(14), dp(12), dp(14), dp(12))
                isClickable = true
                isFocusable = true
                addView(texts)
                addView(dot)
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                ).apply { bottomMargin = dp(8) }
            }
            return Option(root, dot, summaryView)
        }

        fun paintOption(opt: Option, on: Boolean) {
            opt.root.background = ripple(
                if (on) {
                    rounded(SECONDARY_CONTAINER, 16)
                } else {
                    rounded(Color.TRANSPARENT, 16, 1, BORDER)
                },
            )
            opt.dot.background = if (on) {
                LayerDrawable(arrayOf(circle(Color.TRANSPARENT, 2, ACCENT), circle(ACCENT))).apply {
                    setLayerInset(1, dp(5), dp(5), dp(5), dp(5))
                }
            } else {
                circle(Color.TRANSPARENT, 2, TEXT_FAINT)
            }
        }

        fun smallToggle(initial: Boolean, onChange: (Boolean) -> Unit): View {
            val root = FrameLayout(context).apply {
                layoutParams = LinearLayout.LayoutParams(dp(52), dp(32))
                isClickable = true
                isFocusable = true
                contentDescription = if (initial) "Disable" else "Enable"
            }
            val track = View(context).apply {
                layoutParams = FrameLayout.LayoutParams(dp(52), dp(32))
            }
            val thumb = View(context).apply {
                layoutParams = FrameLayout.LayoutParams(dp(24), dp(24), Gravity.START or Gravity.CENTER_VERTICAL)
                    .apply { leftMargin = dp(4) }
                translationX = if (initial) dp(20).toFloat() else 0f
            }
            root.addView(track)
            root.addView(thumb)
            var checked = initial
            fun paint() {
                track.background = if (checked) {
                    rounded(ACCENT, 999)
                } else {
                    rounded(Color.argb(20, 255, 255, 255), 999, 1, BORDER)
                }
                thumb.background = circle(if (checked) ON_PRIMARY else TEXT_FAINT)
                root.contentDescription = if (checked) "Disable" else "Enable"
            }
            paint()
            root.setOnClickListener {
                checked = !checked
                paint()
                thumb.animate().translationX(if (checked) dp(20).toFloat() else 0f)
                    .setDuration(220)
                    .setInterpolator(OvershootInterpolator(0.7f))
                    .start()
                onChange(checked)
            }
            return root
        }

        fun deleteIcon(description: String = "Delete", onClick: () -> Unit): FrameLayout {
            val root = FrameLayout(context).apply {
                background = ripple(circle(Color.argb(12, 255, 255, 255), 1, GLASS_STROKE_SOFT))
                isClickable = true
                isFocusable = true
                contentDescription = description
                setOnClickListener { onClick() }
                layoutParams = LinearLayout.LayoutParams(dp(30), dp(30))
            }
            root.addView(
                ImageView(context).apply {
                    scaleType = ImageView.ScaleType.CENTER
                    setImageDrawable(icon("close", TEXT_FAINT, 14))
                    layoutParams = FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT,
                    )
                },
            )
            return root
        }

        fun reportDialog(
            title: String,
            subtitle: String,
            rows: List<Triple<String, String, Int>> = emptyList(),
            accent: Int = ACCENT,
            actionLabel: String = "Done",
            onAction: (() -> Unit)? = null,
        ) {
            val panel = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(20), dp(18), dp(20), dp(16))
                background = glassPanel(24)
                elevation = dp(10).toFloat()
            }
            val head = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            head.addView(
                View(context).apply {
                    background = circle(accent)
                    layoutParams = LinearLayout.LayoutParams(dp(9), dp(9)).apply { rightMargin = dp(9) }
                },
            )
            head.addView(label(title, 18f, TEXT, medium = true))
            panel.addView(head)
            panel.addView(
                label(subtitle, 11f, TEXT_DIM).apply { setPadding(0, dp(6), 0, 0) },
            )
            if (rows.isNotEmpty()) {
                val list = LinearLayout(context).apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding(0, dp(14), 0, 0)
                }
                rows.forEachIndexed { index, (name, value, tone) ->
                    list.addView(
                        LinearLayout(context).apply {
                            orientation = LinearLayout.HORIZONTAL
                            gravity = Gravity.CENTER_VERTICAL
                            setPadding(dp(12), dp(11), dp(12), dp(11))
                            background = glassFill(12, 18, 9)
                            addView(
                                label(name, 12f, TEXT_DIM),
                                LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f),
                            )
                            addView(label(value, 13f, tone, medium = true))
                        },
                        LinearLayout.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                        ).apply { if (index > 0) topMargin = dp(8) },
                    )
                }
                panel.addView(list)
            }
            val done = action(actionLabel, primary = true).apply {
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, dp(44),
                ).apply { topMargin = dp(16) }
            }
            panel.addView(done)

            val report = AlertDialog.Builder(context, android.R.style.Theme_Material_Dialog)
                .setView(panel)
                .create()
            report.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            report.window?.setDimAmount(0.6f)
            done.setOnClickListener {
                report.dismiss()
                onAction?.invoke()
            }
            report.show()
            val width = (context.resources.displayMetrics.widthPixels * 0.86f).roundToInt()
            report.window?.setLayout(width, ViewGroup.LayoutParams.WRAP_CONTENT)
        }

        fun showRowOrderEditor() {
            val rows = Catalogue.homeRows().map { (key, title) ->
                RankedRow(key, title)
            }.toMutableList()

            if (rows.isEmpty()) {
                reportDialog(
                    title = "Home Row Order",
                    subtitle = "No catalogue rows are enabled. Enable rows first, then set their order.",
                    accent = WARNING,
                )
                return
            }

            val panel = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(18), dp(16), dp(18), dp(14))
                background = glassPanel(24)
                elevation = dp(10).toFloat()
            }
            panel.addView(label("Home Row Order", 18f, TEXT, medium = true))

            val list = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
            val listScroll = ScrollView(context).apply {
                overScrollMode = View.OVER_SCROLL_NEVER
                isFillViewport = false
                addView(list)
            }
            panel.addView(
                listScroll,
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                ).apply { topMargin = dp(10) },
            )

            val status = label("", 12f, TEXT_DIM, medium = true).apply {
                setPadding(0, dp(8), 0, 0)
                visibility = View.GONE
            }
            panel.addView(status)

            val cancel = action("Cancel", primary = false)
            val reset = action("Reset", primary = false)
            val save = action("Save", primary = true)
            val actions = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, dp(12), 0, 0)
            }
            listOf(cancel, reset, save).forEachIndexed { index, view ->
                view.layoutParams = LinearLayout.LayoutParams(0, control, 1f).apply {
                    if (index > 0) leftMargin = gap
                }
                actions.addView(view)
            }
            panel.addView(actions)

            val editor = AlertDialog.Builder(context, android.R.style.Theme_Material_Dialog)
                .setView(panel)
                .create()
            editor.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            editor.window?.setDimAmount(0.65f)

            fun savedKeys(): List<String> = Catalogue.homeRows().map { it.first }

            fun currentKeys(): List<String> = rows.map { it.key }

            fun paintStatus() {
                if (currentKeys() != savedKeys()) {
                    status.visibility = View.VISIBLE
                    status.setTextColor(ACCENT_PALE)
                    status.text = "Unsaved changes"
                } else {
                    status.visibility = View.GONE
                }
            }

            fun measureList() {
                val metrics = context.resources.displayMetrics
                val width = ((metrics.widthPixels * 0.9f).roundToInt() - dp(36)).coerceAtLeast(dp(180))
                list.measure(
                    View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
                )
                val maxHeight = (metrics.heightPixels * 0.52f).roundToInt()
                listScroll.layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    list.measuredHeight.coerceAtMost(maxHeight).coerceAtLeast(dp(80)),
                ).apply { topMargin = dp(10) }
            }

            fun render() {
                val scrollY = listScroll.scrollY
                list.removeAllViews()

                fun step(kind: String, description: String, enabled: Boolean, onClick: () -> Unit): FrameLayout {
                    val root = FrameLayout(context).apply {
                        background = ripple(glassFill(12, 18, 9))
                        layoutParams = LinearLayout.LayoutParams(dp(36), dp(36)).apply { leftMargin = dp(6) }
                        isClickable = enabled
                        isFocusable = enabled
                        alpha = if (enabled) 1f else 0.4f
                        contentDescription = description
                        setOnClickListener { if (enabled) onClick() }
                    }
                    root.addView(
                        ImageView(context).apply {
                            scaleType = ImageView.ScaleType.CENTER
                            setImageDrawable(icon(kind, if (enabled) TEXT else TEXT_FAINT, 14))
                            layoutParams = FrameLayout.LayoutParams(
                                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT,
                            )
                        },
                    )
                    return root
                }

                rows.forEachIndexed { index, row ->
                    val item = LinearLayout(context).apply {
                        orientation = LinearLayout.HORIZONTAL
                        gravity = Gravity.CENTER_VERTICAL
                        setPadding(dp(10), dp(8), dp(8), dp(8))
                        background = ripple(glassFill(12, 18, 9))
                        layoutParams = LinearLayout.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                        ).apply { bottomMargin = dp(6) }
                    }
                    val number = label(
                        "${index + 1}",
                        12f,
                        ACCENT_PALE,
                        medium = true,
                    ).apply {
                        gravity = Gravity.CENTER
                        background = rounded(Color.TRANSPARENT, 12, 1, GLASS_STROKE)
                        layoutParams = LinearLayout.LayoutParams(dp(32), dp(32))
                        contentDescription = "Position ${index + 1}"
                    }
                    val title = label(row.title, 13f, TEXT, medium = true).apply {
                        setPadding(dp(10), 0, dp(8), 0)
                        maxLines = 2
                        ellipsize = TextUtils.TruncateAt.END
                    }
                    val up = step("up", "Move ${row.title} up", index > 0) {
                        rows.removeAt(index)
                        rows.add(index - 1, row)
                        render()
                    }
                    val down = step("down", "Move ${row.title} down", index < rows.lastIndex) {
                        rows.removeAt(index)
                        rows.add(index + 1, row)
                        render()
                    }
                    item.addView(number)
                    item.addView(
                        title,
                        LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f),
                    )
                    item.addView(up)
                    item.addView(down)
                    list.addView(item)
                }
                paintStatus()
                measureList()
                listScroll.post { listScroll.scrollTo(0, scrollY) }
            }

            cancel.setOnClickListener { editor.dismiss() }
            reset.setOnClickListener {
                rows.clear()
                Catalogue.defaultHomeRows().forEach { (key, title) ->
                    rows += RankedRow(key, title)
                }
                render()
            }
            save.setOnClickListener {
                val visible = rows.map { it.key }
                val previous = Settings.catalogueOrder().ifEmpty {
                    Catalogue.forYouRowKeys + Catalogue.enabledRows().map { it.first }
                }
                val hidden = Catalogue.forYouRowKeys.toSet() - visible.toSet()
                Settings.saveCatalogueOrder(
                    context,
                    Catalogue.mergeOrder(visible, previous, hidden),
                )
                editor.dismiss()
                Toast.makeText(context, "Home row order saved", Toast.LENGTH_SHORT).show()
            }

            render()
            editor.show()
            val metrics = context.resources.displayMetrics
            editor.window?.setLayout(
                (metrics.widthPixels * 0.9f).roundToInt(),
                ViewGroup.LayoutParams.WRAP_CONTENT,
            )
        }

        var dialog: AlertDialog? = null
        var providerProbeJob: Job? = null

        // state bus: detail toolbars and home tiles both paint from this
        val stateCache = LinkedHashMap<String, Pair<String, Boolean>>()
        val stateListeners = mutableListOf<(String, String, Boolean) -> Unit>()

        fun setState(id: String, value: String, on: Boolean) {
            stateCache[id] = value to on
            stateListeners.forEach { it(id, value, on) }
        }

        fun toneForGroup(group: String): Tone = when (group) {
            "Sources" -> TONE_SECONDARY
            "Keys & Services" -> TONE_TERTIARY
            else -> TONE_PRIMARY
        }

        fun beadView(tone: Tone): View = View(context).apply {
            background = circle(tone.bead)
            layoutParams = LinearLayout.LayoutParams(dp(6), dp(6)).apply { rightMargin = dp(7) }
        }

        fun paintStatePill(pill: StatePill, value: String, on: Boolean) {
            pill.text.text = value
            pill.text.setTextColor(if (on) ACCENT_PALE else TEXT_MUTED)
            pill.root.background = if (on) {
                rounded(withAlpha(ACCENT, 41), 999)
            } else {
                rounded(Color.argb(14, 255, 255, 255), 999)
            }
            pill.dot.background =
                if (on) circle(ACCENT) else circle(Color.TRANSPARENT, 1, TEXT_FAINT)
        }

        fun statePill(): StatePill {
            val dot = View(context).apply {
                layoutParams = LinearLayout.LayoutParams(dp(6), dp(6)).apply { rightMargin = dp(6) }
            }
            val text = label("", 11f, TEXT_MUTED, medium = true)
            val root = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(11), dp(6), dp(11), dp(6))
            }
            root.addView(dot)
            root.addView(text)
            return StatePill(root, dot, text)
        }

        fun glassCircleButton(kind: String, description: String): FrameLayout {
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
                    setImageDrawable(icon(kind, TEXT, 18))
                    layoutParams = FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT,
                    )
                },
            )
            return root
        }

        fun add(target: LinearLayout, child: View, top: Int = 0) {
            child.layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = dp(top) }
            target.addView(child)
        }

        fun addRow(target: LinearLayout, top: Int, vararg views: View) {
            val row = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            views.forEachIndexed { index, view ->
                val lp = LinearLayout.LayoutParams(0, control, 1f)
                if (index > 0) lp.leftMargin = gap
                row.addView(view, lp)
            }
            add(target, row, top)
        }

        fun sectionLabel(value: String, top: Int): TextView =
            label(value, 10f, TEXT_FAINT, medium = true, spacing = 0.12f).apply {
                setPadding(0, dp(top), 0, dp(8))
                text = value.uppercase()
            }

        val sections = mutableListOf<SectionSpec>()
        var refreshForYouState: () -> Unit = {}

        // ---------- section pages ----------

        val providerBody: (LinearLayout) -> Unit = { content ->
            add(
                content,
                label("Pick one source at a time — selecting another turns off the current one.", 12f, TEXT_DIM),
                2,
            )
            val options = LinkedHashMap<String, Option>()
            val list = optionList()
            Catalogue.Provider.entries.forEach { provider ->
                val opt = option(
                    provider.label,
                    if (provider == Catalogue.Provider.TMDB) "Use Personal API Key" else "IMDB backed catalog",
                )
                options[provider.id] = opt
                list.addView(opt.root)
            }
            add(content, list, 12)

            fun paint() {
                val current = Catalogue.activeProvider()
                options.forEach { (id, opt) ->
                    paintOption(opt, id == current.id)
                }
                setState("provider", current.label, true)
            }
            paint()
            options.forEach { (id, opt) ->
                opt.root.setOnClickListener {
                    val picked = Catalogue.Provider.from(id)
                    if (picked != Catalogue.activeProvider()) {
                        Catalogue.setProvider(context, picked)
                        paint()
                    }
                }
            }
        }
        sections += SectionSpec(
            "provider", "Content", "Catalogue Provider",
            "Pick one source at a time",
            "provider", TONE_PRIMARY, providerBody,
        )

        val catalogueBody: (LinearLayout) -> Unit = { content ->
            var selected = Catalogue.Group.GLOBAL
            fun rowsOf(group: Catalogue.Group): List<Pair<String, String>> = Catalogue.rowsFor(group)
            fun enabledCount(): Int = Catalogue.enabledRows().size
            fun totalCount(): Int = Catalogue.allRows().size

            add(
                content,
                label("Toggle rows by group, or reorder every row at once.", 12f, TEXT_DIM),
                2,
            )
            val groupChips = LinkedHashMap<Catalogue.Group, TextView>()
            val chipRow = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            val groupEntries = Catalogue.Group.entries
            groupEntries.forEach { group ->
                val groupChip = chip(group.label)
                groupChips[group] = groupChip
                chipRow.addView(groupChip)
            }
            val sheetWidth = (context.resources.displayMetrics.widthPixels * 0.94f).roundToInt()
            val rowWidth = (sheetWidth - dp(18) * 2 - dp(16) * 2 - dp(2)).coerceAtLeast(0)
            val naturalWidths = groupEntries.map { group ->
                val chipView = groupChips.getValue(group)
                chipView.measure(
                    View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
                    View.MeasureSpec.makeMeasureSpec(control, View.MeasureSpec.EXACTLY),
                )
                chipView.measuredWidth
            }
            val naturalTotal = naturalWidths.sum() + gap * groupEntries.size
            val extra = ((rowWidth - naturalTotal) / groupEntries.size).coerceAtLeast(0)
            groupEntries.forEachIndexed { index, group ->
                groupChips.getValue(group).layoutParams = LinearLayout.LayoutParams(
                    naturalWidths[index] + extra, control,
                ).apply { rightMargin = gap }
            }
            val chipScroll = HorizontalScrollView(context).apply {
                isHorizontalScrollBarEnabled = false
                addView(chipRow)
            }
            add(content, chipScroll, 12)

            val reorder = action("Reorder home rows", primary = false)
            add(content, reorder, 10)
            reorder.layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(42),
            ).apply { topMargin = dp(10) }

            val enableAll = textButton("Enable all")
            val disableAll = textButton("Disable all")
            val quick = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.END or Gravity.CENTER_VERTICAL
            }
            listOf(enableAll, disableAll).forEachIndexed { index, view ->
                view.layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, dp(36),
                ).apply { if (index > 0) leftMargin = gap }
                quick.addView(view)
            }
            add(content, quick, 8)

            val list = optionList()
            add(content, list, 10)

            fun paintChips() {
                groupChips.forEach { (group, view) -> markChip(view, group == selected) }
            }

            fun refreshState() {
                val total = totalCount()
                val on = enabledCount()
                setState("catalogue", "$on of $total enabled", on > 0)
            }

            fun renderRows() {
                list.removeAllViews()
                val options = LinkedHashMap<String, Option>()
                rowsOf(selected).forEach { (key, title) ->
                    val opt = option(
                        title.substringAfter(" · ", title),
                        if (title.endsWith(" Movies")) "Movie row" else "Series row",
                    )
                    options[key] = opt
                    list.addView(opt.root)
                }
                fun paintRows() {
                    options.forEach { (key, opt) -> paintOption(opt, Settings.catalogueEnabled(key)) }
                    refreshState()
                }
                paintRows()
                options.forEach { (key, opt) ->
                    opt.root.setOnClickListener {
                        Settings.saveCatalogueEnabled(context, key, !Settings.catalogueEnabled(key))
                        paintRows()
                    }
                }
                paintChips()
                refreshState()
            }

            groupChips.forEach { (group, view) ->
                view.setOnClickListener {
                    selected = group
                    renderRows()
                }
            }
            reorder.setOnClickListener { showRowOrderEditor() }
            enableAll.setOnClickListener {
                Settings.saveAllCatalogueEnabled(context, rowsOf(selected).map { it.first }, true)
                renderRows()
            }
            disableAll.setOnClickListener {
                Settings.saveAllCatalogueEnabled(context, rowsOf(selected).map { it.first }, false)
                renderRows()
            }
            renderRows()
        }
        sections += SectionSpec(
            "catalogue", "Content", "Catalogue",
            "Rows and Home order",
            "catalogue", TONE_PRIMARY, catalogueBody,
        )

        val forYouBody: (LinearLayout) -> Unit = { content ->
            add(
                content,
                label("Rows built from your watch history.", 12f, TEXT_DIM),
                2,
            )

            val forYouRowOptions = LinkedHashMap<String, Option>()
            val forYouRowList = optionList()
            add(
                content,
                label("Show these rows on Home", 12f, TEXT_DIM)
                    .apply { setPadding(0, dp(14), 0, 0) },
            )
            add(content, forYouRowList, 8)

            fun doRefreshForYouState() {
                val enabled = Catalogue.forYouRowTitles().any { (key, _) -> Settings.catalogueEnabled(key) }
                setState("foryou", if (enabled) "On" else "Off", enabled)
                forYouRowOptions.forEach { (key, opt) ->
                    paintOption(opt, Settings.catalogueEnabled(key))
                }
            }
            refreshForYouState = { doRefreshForYouState() }

            Catalogue.forYouRowTitles().forEach { (key, title) ->
                val signedIn = SimklHistory.isSignedIn()
                val opt = option(
                    title,
                    if (signedIn) "Built from your Simkl watch history" else "Sign in to Simkl to show this row",
                )
                forYouRowOptions[key] = opt
                forYouRowList.addView(opt.root)
                paintOption(opt, Settings.catalogueEnabled(key) && signedIn)
                opt.root.isClickable = signedIn
                opt.root.alpha = if (signedIn) 1f else 0.5f
                opt.root.setOnClickListener {
                    if (!signedIn) return@setOnClickListener
                    val on = !Settings.catalogueEnabled(key)
                    Settings.saveCatalogueEnabled(context, key, on)
                    paintOption(opt, on)
                    doRefreshForYouState()
                }
            }
            doRefreshForYouState()
            val shuffleOn = Settings.shuffleRows()
            val shuffleOpt = option(
                "Shuffle all rows",
                if (shuffleOn) "Items in every row appear in random order" else "Rows keep their normal order",
            )
            paintOption(shuffleOpt, shuffleOn)
            shuffleOpt.root.setOnClickListener {
                val on = !Settings.shuffleRows()
                Settings.saveShuffleRows(context, on)
                paintOption(shuffleOpt, on)
                shuffleOpt.summary.text =
                    if (on) "Items in every row appear in random order" else "Rows keep their normal order"
            }
            add(content, shuffleOpt.root, 4)
            add(content, sectionLabel("Refresh schedule", 18))
            val modeOptions = LinkedHashMap<ForYouRefresh.Mode, Option>()
            val modeList = optionList()
            var selectedMode = Settings.forYouRefreshMode()
            ForYouRefresh.Mode.entries.forEach { mode ->
                val opt = option(mode.label, mode.summary)
                modeOptions[mode] = opt
                modeList.addView(opt.root)
            }
            add(content, modeList)
            fun paintModes() {
                modeOptions.forEach { (mode, opt) -> paintOption(opt, mode == selectedMode) }
            }
            paintModes()
            modeOptions.forEach { (mode, opt) ->
                opt.root.setOnClickListener {
                    if (selectedMode == mode) return@setOnClickListener
                    selectedMode = mode
                    Settings.saveForYouRefreshMode(context, mode)
                    paintModes()
                }
            }
            val refreshNow = action("Refresh now", primary = false).apply {
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, dp(42),
                )
            }
            add(content, refreshNow, 6)
            refreshNow.setOnClickListener {
                if (!SimklHistory.isSignedIn()) {
                    reportDialog(
                        title = "Connect Simkl",
                        subtitle = "Made for You builds from your Simkl watch history. Sign in under Accounts, then try again.",
                        accent = WARNING,
                        actionLabel = "Got it",
                    )
                    return@setOnClickListener
                }
                refreshNow.isEnabled = false
                refreshNow.text = "Rebuilding..."
                CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
                    var result: ForYouEngine.RebuildResult? = null
                    try {
                        result = ForYouEngine.rebuildAll()
                    } catch (c: kotlinx.coroutines.CancellationException) {
                        throw c
                    } catch (t: Throwable) {
                        android.util.Log.w("IndStream", "Made for You rebuild failed: ${t.message}")
                    } finally {
                        val rebuilt = result
                        refreshNow.post {
                            refreshNow.text = "Refresh now"
                            refreshNow.isEnabled = true
                            val movies = rebuilt?.movies ?: 0
                            val series = rebuilt?.series ?: 0
                            val total = movies + series
                            if (rebuilt?.rebuilt == true) setState("foryou", "On", true)
                            if (dialog?.isShowing == true) {
                                reportDialog(
                                    title = "Made for You",
                                    subtitle = when {
                                        rebuilt == null -> "Refresh failed - check your connection and try again."
                                        total > 0 -> "Recommendations rebuilt from your watch history."
                                        else -> "No picks were built. Watch a few titles, then try again."
                                    },
                                    rows = listOf(
                                        Triple("Movies", "$movies picks", if (movies > 0) SUCCESS else TEXT_DIM),
                                        Triple("Series", "$series picks", if (series > 0) SUCCESS else TEXT_DIM),
                                    ),
                                    accent = if (total > 0) SUCCESS else WARNING,
                                )
                            }
                        }
                    }
                }
            }
        }
        sections += SectionSpec(
            "foryou", "Content", "Made for You",
            "Rows built from your watch history",
            "foryou", TONE_PRIMARY, forYouBody,
        )

        val serversBody: (LinearLayout) -> Unit = { content ->
            val allServers = ServerFarm.allServers
            var enabledCount = allServers.count { Settings.serverEnabled(it.id) }
            fun providersStateText(): String = "$enabledCount of ${allServers.size} enabled"
            fun refreshProvidersState() {
                enabledCount = allServers.count { Settings.serverEnabled(it.id) }
                setState("servers", providersStateText(), enabledCount > 0)
            }
            refreshProvidersState()

            add(
                content,
                label("Choose which providers supply streams.", 12f, TEXT_DIM),
                2,
            )

            class ProviderTile(
                val spec: ServerSpec,
                val root: LinearLayout,
                val dot: View,
                val probeStatus: TextView,
            )

            val providerProbeStates = allServers.associate {
                it.id to ProviderProbeState.NOT_TESTED
            }.toMutableMap()

            fun providerTags(spec: ServerSpec): String {
                val tags = ArrayList<String>()
                if (spec.animeOnly) tags += "Anime"
                when (spec.speed) {
                    ServerSpeed.VERY_FAST -> tags += "Very fast"
                    ServerSpeed.FAST -> tags += "Fast"
                    ServerSpeed.SLOW -> tags += "Slow start"
                    ServerSpeed.NORMAL -> {}
                }
                if (spec.reliable) tags += "Reliable"
                tags += if (spec.downloadFriendly) "Download" else "Stream only"
                val multiAudio = spec.movieUrl.contains("multilang", true) ||
                    spec.tvUrl.contains("multilang", true) ||
                    spec.declaredLanguages.size >= 2
                if (multiAudio) tags += "Multi audio"
                return tags.take(4).joinToString(" · ")
            }

            fun providerTile(spec: ServerSpec): ProviderTile {
                val dot = View(context).apply {
                    layoutParams = LinearLayout.LayoutParams(dp(9), dp(9)).apply { leftMargin = dp(8) }
                }
                val name = label(spec.name, 13f, TEXT, medium = true).apply {
                    maxLines = 1
                    ellipsize = TextUtils.TruncateAt.END
                    layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                }
                val probeStatus = label("Not tested", 10f, TEXT_FAINT, medium = true).apply {
                    maxLines = 1
                    layoutParams = LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                    ).apply { leftMargin = dp(6) }
                }
                val titleRow = LinearLayout(context).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    addView(name)
                    addView(probeStatus)
                    addView(dot)
                }
                val tags = label(providerTags(spec), 11f, TEXT_FAINT).apply {
                    maxLines = 2
                    ellipsize = TextUtils.TruncateAt.END
                    setPadding(0, dp(4), 0, 0)
                }
                val root = LinearLayout(context).apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding(dp(12), dp(10), dp(12), dp(10))
                    isClickable = true
                    isFocusable = true
                    addView(titleRow)
                    addView(tags)
                }
                return ProviderTile(spec, root, dot, probeStatus)
            }

            fun paintProvider(tile: ProviderTile) {
                val on = Settings.serverEnabled(tile.spec.id)
                val probeState = providerProbeStates.getValue(tile.spec.id)
                tile.root.background = ripple(
                    if (on) {
                        rounded(Color.argb(12, 255, 255, 255), 16, 1, Color.argb(20, 255, 255, 255))
                    } else {
                        rounded(Color.TRANSPARENT, 16, 1, Color.argb(12, 255, 255, 255))
                    },
                )
                tile.root.alpha = if (on) 1f else 0.58f
                tile.dot.background = if (on) circle(SUCCESS) else circle(Color.TRANSPARENT, 2, GLASS_STROKE)
                tile.probeStatus.text = when (probeState) {
                    ProviderProbeState.NOT_TESTED -> "Not tested"
                    ProviderProbeState.TESTING -> "Testing"
                    ProviderProbeState.UP -> "Up"
                    ProviderProbeState.DOWN -> "Down"
                }
                tile.probeStatus.setTextColor(
                    when (probeState) {
                        ProviderProbeState.UP -> SUCCESS
                        ProviderProbeState.DOWN -> WARNING
                        ProviderProbeState.TESTING -> ACCENT_PALE
                        ProviderProbeState.NOT_TESTED -> TEXT_FAINT
                    },
                )
                tile.root.contentDescription =
                    "${tile.spec.name}, ${if (on) "enabled" else "disabled"}, ${tile.probeStatus.text}"
            }

            val providerTiles = allServers.map { spec ->
                providerTile(spec).also { tile ->
                    paintProvider(tile)
                    tile.root.setOnClickListener {
                        Settings.saveServerEnabled(context, spec.id, !Settings.serverEnabled(spec.id))
                        paintProvider(tile)
                        refreshProvidersState()
                    }
                }
            }
            fun paintServerTiles() {
                providerTiles.forEach(::paintProvider)
            }

            add(content, sectionLabel("All servers", 16))
            val enableAll = textButton("Enable all")
            val disableAll = textButton("Disable all")
            val quickRow = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.END or Gravity.CENTER_VERTICAL
            }
            listOf(enableAll, disableAll).forEachIndexed { index, view ->
                view.layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, dp(36),
                ).apply { if (index > 0) leftMargin = gap }
                quickRow.addView(view)
            }
            add(content, quickRow, 6)
            enableAll.setOnClickListener {
                Settings.saveAllServersEnabled(context, allServers.map { it.id }, true)
                paintServerTiles()
                refreshProvidersState()
            }
            disableAll.setOnClickListener {
                Settings.saveAllServersEnabled(context, allServers.map { it.id }, false)
                paintServerTiles()
                refreshProvidersState()
            }

            val testAllButton = chip("Test all servers")
            val testSummary = label(
                "Resolves a sample title on every provider, including disabled ones. Up means a stream was returned.",
                11f,
                TEXT_FAINT,
            ).apply {
                setPadding(0, dp(8), 0, 0)
            }
            val testAllRow = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                addView(
                    testAllButton,
                    LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(44)),
                )
                addView(
                    testSummary,
                    LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                    ),
                )
            }
            add(content, testAllRow, 10)
            testAllButton.setOnClickListener {
                if (providerProbeJob?.isActive == true) return@setOnClickListener

                testAllButton.isEnabled = false
                testAllButton.text = "Testing..."
                providerProbeStates.keys.forEach { providerProbeStates[it] = ProviderProbeState.TESTING }
                paintServerTiles()
                testSummary.text = "Testing 0 of ${allServers.size} providers..."

                providerProbeJob = CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
                    val completed = java.util.concurrent.atomic.AtomicInteger()
                    val results = coroutineScope {
                        val semaphore = Semaphore(6)
                        allServers.map { spec ->
                            async {
                                semaphore.withPermit {
                                    val isUp = StreamEngine.probeServer(spec)
                                    val tested = completed.incrementAndGet()
                                    withContext(Dispatchers.Main) {
                                        providerProbeStates[spec.id] =
                                            if (isUp) ProviderProbeState.UP else ProviderProbeState.DOWN
                                        providerTiles.firstOrNull { it.spec.id == spec.id }?.let(::paintProvider)
                                        testSummary.text =
                                            "Testing $tested of ${allServers.size} providers..."
                                    }
                                    isUp
                                }
                            }
                        }.awaitAll()
                    }
                    val upCount = results.count { it }
                    val downCount = results.size - upCount
                    withContext(Dispatchers.Main) {
                        testAllButton.isEnabled = true
                        testAllButton.text = "Test all servers"
                        testSummary.text = "Complete: $upCount up, $downCount down"
                        reportDialog(
                            title = "Server test complete",
                            subtitle = "Checked ${results.size} providers just now.",
                            rows = listOf(
                                Triple("Working", "$upCount", SUCCESS),
                                Triple("Down", "$downCount", if (downCount > 0) WARNING else TEXT_DIM),
                            ),
                            accent = if (downCount == 0) SUCCESS else WARNING,
                        )
                    }
                }
            }

            val providerGrid = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
            val providerRows = providerTiles.chunked(2)
            providerRows.forEachIndexed { rowIndex, rowTiles ->
                val row = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
                rowTiles.forEachIndexed { index, tile ->
                    val lp = LinearLayout.LayoutParams(0, dp(68), 1f)
                    if (index < rowTiles.lastIndex) lp.rightMargin = gap
                    row.addView(tile.root, lp)
                }
                if (rowTiles.size == 1) {
                    row.addView(
                        View(context),
                        LinearLayout.LayoutParams(0, 1, 1f).apply { leftMargin = gap },
                    )
                }
                providerGrid.addView(
                    row,
                    LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                    ).apply { if (rowIndex < providerRows.lastIndex) bottomMargin = gap },
                )
            }
            add(content, providerGrid, 10)
        }
        sections += SectionSpec(
            "servers", "Sources", "Servers / Providers",
            "Choose which providers supply streams",
            "servers", TONE_SECONDARY, serversBody,
        )

        val addonsBody: (LinearLayout) -> Unit = { content ->
            var addonUrls = StremioNuvioAddons.urls()
            val addonChecks = LinkedHashMap<String, String>()
            var draftVisible = false
            var draftEnabled = true
            var draftAdding = false
            var draftText = ""
            var draftField: EditText? = null

            fun addonEnabledCount(): Int = addonUrls.count { StremioNuvioAddons.enabled(it) }
            fun addonStateText(): String = when {
                addonUrls.isEmpty() -> "Not configured"
                else -> "${addonEnabledCount()} of ${addonUrls.size} enabled"
            }
            fun refreshAddonState() {
                setState("addons", addonStateText(), addonEnabledCount() > 0)
            }
            refreshAddonState()

            add(
                content,
                label("Load streams from addon manifests.", 12f, TEXT_DIM),
                2,
            )

            val addonStatus = label(
                "Only direct HTTP(S) streams are played. Torrent and external links are detected but skipped.",
                11f, TEXT_DIM,
            ).apply { setPadding(0, dp(10), 0, 0) }
            val addonMore = chip("Add More")
            val addonCheck = chip("Check")

            val addonList = optionList()
            add(content, addonList, 10)

            fun addonTitle(url: String): String =
                StremioNuvioAddons.cached(url)?.name ?: StremioNuvioAddons.hostOf(url)

            fun addonSummary(url: String): String {
                val status = addonChecks[url]
                return if (status.isNullOrBlank()) url else "$url\n$status"
            }

            var refreshAddonList: () -> Unit = {}

            fun addonRow(url: String): View {
                val opt = option(addonTitle(url), addonSummary(url))
                opt.dot.visibility = View.GONE
                opt.root.isClickable = false
                paintOption(opt, StremioNuvioAddons.enabled(url))
                val remove = deleteIcon {
                    StremioNuvioAddons.remove(context, url)
                    addonUrls = StremioNuvioAddons.urls()
                    addonChecks.remove(url)
                    refreshAddonList()
                }
                val enabled = smallToggle(StremioNuvioAddons.enabled(url)) { on ->
                    StremioNuvioAddons.setEnabled(context, url, on)
                    paintOption(opt, on)
                    refreshAddonState()
                }
                opt.root.addView(
                    remove,
                    LinearLayout.LayoutParams(dp(32), dp(32)).apply { leftMargin = dp(4) },
                )
                opt.root.addView(
                    enabled,
                    LinearLayout.LayoutParams(dp(52), dp(32)).apply { leftMargin = dp(6) },
                )
                return opt.root
            }

            fun commitDraft(input: EditText, afterAdd: (() -> Unit)? = null) {
                if (draftAdding) return
                val normalized = StremioNuvioAddons.normalizeUrl(input.text.toString())
                if (normalized == null) {
                    input.error = "Enter a valid addon URL"
                    return
                }
                if (addonUrls.any { it.equals(normalized, ignoreCase = true) }) {
                    input.error = "Addon already added"
                    return
                }
                input.error = null
                draftAdding = true
                addonCheck.isEnabled = false
                input.isEnabled = false
                addonStatus.text = "Checking manifest..."
                CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
                    val manifest = StremioNuvioAddons.manifest(normalized, force = true)
                    val added = if (manifest == null) null else StremioNuvioAddons.add(context, normalized)
                    input.post {
                        draftAdding = false
                        addonCheck.isEnabled = true
                        if (manifest == null) {
                            input.isEnabled = true
                            input.error = "Manifest not reachable"
                            addonStatus.text = "Manifest could not be loaded."
                        } else if (added == null) {
                            input.isEnabled = true
                            input.error = "Addon already added"
                            addonStatus.text = "That addon is already in the list."
                        } else {
                            StremioNuvioAddons.setEnabled(context, added, draftEnabled)
                            addonUrls = StremioNuvioAddons.urls()
                            addonChecks[added] = "Manifest OK"
                            draftVisible = false
                            draftField = null
                            draftText = ""
                            draftEnabled = true
                            addonStatus.text = "Added ${manifest.name}."
                            refreshAddonList()
                            afterAdd?.invoke()
                        }
                    }
                }
            }

            fun renderAddons() {
                if (!draftVisible) {
                    draftField = null
                    draftText = ""
                } else {
                    draftField?.let { draftText = it.text.toString() }
                }
                addonList.removeAllViews()
                addonUrls.forEach { url -> addonList.addView(addonRow(url)) }
                if (draftVisible) {
                    val input = field(draftText, "Paste addon URL, then press Done").apply {
                        layoutParams = LinearLayout.LayoutParams(
                            0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f,
                        )
                        imeOptions = EditorInfo.IME_ACTION_DONE
                    }
                    draftField = input
                    input.setSelection(input.text.length)
                    val remove = deleteIcon {
                        draftVisible = false
                        draftField = null
                        draftText = ""
                        renderAddons()
                    }
                    val enabled = smallToggle(draftEnabled) { on -> draftEnabled = on }
                    val row = LinearLayout(context).apply {
                        orientation = LinearLayout.HORIZONTAL
                        gravity = Gravity.CENTER_VERTICAL
                        setPadding(dp(12), dp(10), dp(10), dp(10))
                        background = glassFill(16, 20, 12)
                        addView(input)
                        addView(
                            remove,
                            LinearLayout.LayoutParams(dp(32), dp(32)).apply { leftMargin = dp(4) },
                        )
                        addView(
                            enabled,
                            LinearLayout.LayoutParams(dp(52), dp(32)).apply { leftMargin = dp(6) },
                        )
                        layoutParams = LinearLayout.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                        ).apply { bottomMargin = dp(8) }
                    }
                    input.setOnEditorActionListener { _, actionId, _ ->
                        if (actionId == EditorInfo.IME_ACTION_DONE) {
                            commitDraft(input)
                            true
                        } else false
                    }
                    addonList.addView(row)
                    input.post { input.requestFocus() }
                }
                if (addonUrls.isEmpty() && !draftVisible) {
                    addonList.addView(label("No addons configured.", 12f, TEXT_DIM))
                }
                refreshAddonState()
            }
            refreshAddonList = { renderAddons() }

            fun runAddonCheck() {
                val targets = addonUrls.toList()
                if (targets.isEmpty()) {
                    addonStatus.text = "Add an addon first."
                    return
                }
                addonCheck.isEnabled = false
                addonCheck.text = "Checking..."
                addonStatus.text = "Checking ${targets.size} addon(s)..."
                CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
                    val results = coroutineScope {
                        targets.map { url ->
                            async { url to StremioNuvioAddons.check(url) }
                        }.awaitAll()
                    }
                    addonCheck.post {
                        results.forEach { (url, result) ->
                            addonChecks[url] =
                                if (result.ok) result.detail else "Check failed - ${result.detail}"
                        }
                        addonCheck.isEnabled = true
                        addonCheck.text = "Check"
                        val reachable = results.count { it.second.ok }
                        addonStatus.text = "$reachable of ${results.size} reachable"
                        renderAddons()
                    }
                }
            }

            addRow(content, 12, addonMore, addonCheck)
            add(content, addonStatus)

            addonMore.setOnClickListener {
                if (draftVisible) {
                    draftField?.requestFocus()
                } else {
                    draftVisible = true
                    renderAddons()
                }
            }

            addonCheck.setOnClickListener {
                if (draftVisible) {
                    val input = draftField
                    if (input != null && input.text.isNotBlank()) {
                        commitDraft(input) { runAddonCheck() }
                        return@setOnClickListener
                    }
                    draftVisible = false
                    draftField = null
                    draftText = ""
                    renderAddons()
                }
                runAddonCheck()
            }

            renderAddons()
        }
        sections += SectionSpec(
            "addons", "Sources", "Stremio / Nuvio",
            "Streams from addon manifests",
            "addons", TONE_SECONDARY, addonsBody,
        )

        val m3uBody: (LinearLayout) -> Unit = { content ->
            var m3uEnabled = Settings.m3uEnabled()
            var m3uSources = Settings.m3uSources().toMutableList()

            fun m3uStateText(): String = when {
                !m3uEnabled -> "Off"
                m3uSources.isEmpty() -> "No playlists"
                m3uSources.size == 1 -> "1 playlist"
                else -> "${m3uSources.size} playlists"
            }

            fun refreshM3uState() {
                setState("m3u", m3uStateText(), m3uEnabled && m3uSources.isNotEmpty())
            }
            refreshM3uState()

            add(
                content,
                label("Home rows from playlists - live, film or series.", 12f, TEXT_DIM),
                2,
            )

            val m3uStatus = label(
                "Items keep the quality their name or link states, so pick what your connection handles.",
                11f, TEXT_DIM,
            ).apply { setPadding(0, dp(10), 0, 0) }

            val m3uMore = chip("Add Playlist")
            val m3uDefault = chip("Use Default")
            val m3uList = optionList()

            var m3uDraftVisible = false
            var m3uDraftText = ""
            var m3uDraftField: EditText? = null
            var refreshM3u: () -> Unit = {}

            fun commitM3uDraft(input: EditText) {
                val normalized = M3uPlaylists.normalizeSource(input.text.toString())
                if (normalized == null) {
                    input.error = "Enter a valid playlist URL"
                    return
                }
                if (m3uSources.any { it.equals(normalized, ignoreCase = true) }) {
                    input.error = "Playlist already added"
                    return
                }
                if (m3uSources.size >= M3uPlaylists.MAX_SOURCES) {
                    input.error = "Playlist limit reached"
                    return
                }
                input.error = null
                m3uSources = (m3uSources + normalized).toMutableList()
                Settings.saveM3uSources(context, m3uSources)
                m3uDraftVisible = false
                m3uDraftField = null
                m3uDraftText = ""
                refreshM3u()
            }

            fun m3uRow(url: String): View {
                val primary = M3uPlaylists.primarySource(m3uSources)?.equals(url, ignoreCase = true) == true
                val opt = option(
                    M3uPlaylists.sourceLabel(url),
                    if (primary) "$url\nCategory rows" else url,
                )
                opt.dot.visibility = View.GONE
                opt.root.isClickable = false
                paintOption(opt, true)
                val remove = deleteIcon("Delete playlist") {
                    m3uSources = m3uSources.filterNot { it.equals(url, ignoreCase = true) }.toMutableList()
                    Settings.saveM3uSources(context, m3uSources)
                    refreshM3u()
                }
                opt.root.addView(
                    remove,
                    LinearLayout.LayoutParams(dp(32), dp(32)).apply { leftMargin = dp(4) },
                )
                return opt.root
            }

            fun renderM3u() {
                if (!m3uDraftVisible) {
                    m3uDraftField = null
                    m3uDraftText = ""
                } else {
                    m3uDraftField?.let { m3uDraftText = it.text.toString() }
                }
                m3uList.removeAllViews()
                m3uSources.forEach { url -> m3uList.addView(m3uRow(url)) }
                if (m3uDraftVisible) {
                    val input = field(m3uDraftText, "Paste playlist URL, then press Done").apply {
                        layoutParams = LinearLayout.LayoutParams(
                            0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f,
                        )
                        imeOptions = EditorInfo.IME_ACTION_DONE
                    }
                    m3uDraftField = input
                    input.setSelection(input.text.length)
                    val cancel = deleteIcon("Cancel") {
                        m3uDraftVisible = false
                        m3uDraftField = null
                        m3uDraftText = ""
                        refreshM3u()
                    }
                    val row = LinearLayout(context).apply {
                        orientation = LinearLayout.HORIZONTAL
                        gravity = Gravity.CENTER_VERTICAL
                        setPadding(dp(12), dp(10), dp(10), dp(10))
                        background = glassFill(16, 20, 12)
                        addView(input)
                        addView(
                            cancel,
                            LinearLayout.LayoutParams(dp(32), dp(32)).apply { leftMargin = dp(4) },
                        )
                        layoutParams = LinearLayout.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                        ).apply { bottomMargin = dp(8) }
                    }
                    input.setOnEditorActionListener { _, actionId, _ ->
                        if (actionId == EditorInfo.IME_ACTION_DONE) {
                            commitM3uDraft(input)
                            true
                        } else false
                    }
                    m3uList.addView(row)
                    input.post { input.requestFocus() }
                }
                if (m3uSources.isEmpty() && !m3uDraftVisible) {
                    m3uList.addView(label("No playlists added.", 12f, TEXT_DIM))
                }
                refreshM3uState()
            }

            refreshM3u = { renderM3u() }

            val m3uToggleOption = option("Enable M3U", "Add playlist rows to Home")
            m3uToggleOption.dot.visibility = View.GONE
            m3uToggleOption.root.isClickable = false
            paintOption(m3uToggleOption, m3uEnabled)
            val m3uToggle = smallToggle(m3uEnabled) { on ->
                m3uEnabled = on
                if (on && m3uSources.isEmpty()) {
                    m3uSources = M3uPlaylists.DEFAULT_SOURCES.toMutableList()
                    Settings.saveM3uSources(context, m3uSources)
                    refreshM3u()
                }
                Settings.saveM3uEnabled(context, on)
                paintOption(m3uToggleOption, on)
                refreshM3uState()
            }
            m3uToggleOption.root.addView(
                m3uToggle,
                LinearLayout.LayoutParams(dp(52), dp(32)).apply { leftMargin = dp(6) },
            )

            val m3uToggleList = optionList().apply { addView(m3uToggleOption.root) }
            add(content, sectionLabel("Playlists", 14))
            add(content, m3uToggleList, 8)
            add(content, m3uStatus)
            addRow(content, 10, m3uMore, m3uDefault)
            add(content, m3uList, 8)

            m3uMore.setOnClickListener {
                if (m3uDraftVisible) return@setOnClickListener
                m3uDraftVisible = true
                m3uDraftText = ""
                refreshM3u()
            }
            m3uDefault.setOnClickListener {
                val target = M3uPlaylists.DEFAULT_SOURCE
                if (m3uSources.none { it.equals(target, ignoreCase = true) }) {
                    m3uSources = (listOf(target) + m3uSources).toMutableList()
                    Settings.saveM3uSources(context, m3uSources)
                }
                refreshM3u()
            }

            renderM3u()
        }
        sections += SectionSpec(
            "m3u", "Sources", "M3U Playlists",
            "Home rows from playlists",
            "m3u", TONE_SECONDARY, m3uBody,
        )

        val wyzieBody: (LinearLayout) -> Unit = { content ->
            val wyzieSaved = Settings.apiKey() != null
            setState("subtitles", if (wyzieSaved) "Saved" else "Not set", wyzieSaved)

            add(
                content,
                label("Optional - built-in subtitles work without it.", 12f, TEXT_DIM),
                2,
            )
            val wyzieStatus = label(
                if (wyzieSaved) "Your key is used for subtitle lookups." else "Built-in subtitles are in use.",
                11f, TEXT_DIM,
            ).apply { setPadding(0, dp(10), 0, 0) }
            val wyzieSecret = secret(Settings.apiKey().orEmpty(), "api key...")
            val wyzieKeyInput = wyzieSecret.input
            add(content, sectionLabel("Wyzie", 16))
            add(content, wyzieSecret.root)
            add(content, wyzieStatus)
            val wyzieGetChip = chip("Get key")
            val wyzieVerifyChip = chip("Verify")
            val wyzieClear = action("Clear", false)
            val wyzieSave = action("Save", true)
            addRow(content, 12, wyzieGetChip, wyzieVerifyChip)
            addRow(content, 10, wyzieClear, wyzieSave)
            wyzieGetChip.setOnClickListener { openLink(context, Settings.KEY_LINK) }
            wyzieVerifyChip.setOnClickListener {
                val typed = wyzieKeyInput.text.toString()
                if (!Settings.isValidKey(typed)) {
                    wyzieKeyInput.error = "Enter the key first"
                    return@setOnClickListener
                }
                wyzieStatus.text = "Verifying key..."
                Thread {
                    val msg = runCatching {
                        kotlinx.coroutines.runBlocking { WyzieSubs.testKey(typed) }.second
                    }.getOrDefault("Verify failed - no network?")
                    wyzieKeyInput.post { wyzieStatus.text = msg }
                }.start()
            }
            wyzieClear.setOnClickListener {
                Settings.clear(context)
                wyzieKeyInput.setText("")
                wyzieKeyInput.error = null
                wyzieStatus.text = "Built-in subtitles are in use."
                setState("subtitles", "Not set", false)
            }
            wyzieSave.setOnClickListener {
                val typed = wyzieKeyInput.text.toString()
                if (!Settings.save(context, typed)) {
                    wyzieKeyInput.error = "That key looks too short"
                    return@setOnClickListener
                }
                val on = typed.trim().isNotEmpty()
                wyzieKeyInput.error = null
                wyzieStatus.text =
                    if (on) "Your key is used for subtitle lookups." else "Built-in subtitles are in use."
                setState("subtitles", if (on) "Saved" else "Not set", on)
            }
        }
        sections += SectionSpec(
            "subtitles", "Keys & Services", "Subtitles Key",
            "Wyzie key - optional",
            "subs", TONE_TERTIARY, wyzieBody,
        )

        val tmdbBody: (LinearLayout) -> Unit = { content ->
            val tmdbSaved = Settings.tmdbApiKey() != null
            setState("tmdb", if (tmdbSaved) "Saved" else "Not set", tmdbSaved)

            add(
                content,
                label("Paste the short API key, not the long access token.", 12f, TEXT_DIM),
                2,
            )
            val tmdbStatus = label(
                if (tmdbSaved) "Your key is used for metadata first." else "Built-in keys are in use.",
                11f, TEXT_DIM,
            ).apply { setPadding(0, dp(10), 0, 0) }
            val tmdbSecret = secret(Settings.tmdbApiKey().orEmpty(), "api key...")
            val tmdbKeyInput = tmdbSecret.input
            add(content, tmdbSecret.root, 10)
            add(content, tmdbStatus)
            val tmdbGetChip = chip("Get key")
            val tmdbVerifyChip = chip("Verify")
            val tmdbClear = action("Clear", false)
            val tmdbSave = action("Save", true)
            addRow(content, 12, tmdbGetChip, tmdbVerifyChip)
            addRow(content, 10, tmdbClear, tmdbSave)
            tmdbGetChip.setOnClickListener { openLink(context, Settings.TMDB_LINK) }
            tmdbVerifyChip.setOnClickListener {
                val typed = tmdbKeyInput.text.toString()
                val problem = Settings.tmdbKeyProblem(typed)
                if (problem != null) {
                    tmdbKeyInput.error = problem
                    return@setOnClickListener
                }
                tmdbStatus.text = "Verifying key..."
                Thread {
                    val msg = runCatching {
                        kotlinx.coroutines.runBlocking { TmdbService.testTmdbKey(typed) }
                    }.getOrDefault("Verify failed - no network?")
                    tmdbKeyInput.post { tmdbStatus.text = msg }
                }.start()
            }
            tmdbClear.setOnClickListener {
                Settings.clearTmdb(context)
                tmdbKeyInput.setText("")
                tmdbKeyInput.error = null
                tmdbStatus.text = "Built-in keys are in use."
                setState("tmdb", "Not set", false)
            }
            tmdbSave.setOnClickListener {
                val typed = tmdbKeyInput.text.toString()
                if (!Settings.saveTmdb(context, typed)) {
                    tmdbKeyInput.error = Settings.tmdbKeyProblem(typed) ?: "That key looks too short"
                    return@setOnClickListener
                }
                val on = Settings.tmdbApiKey() != null
                tmdbKeyInput.error = null
                tmdbStatus.text =
                    if (on) "Your key is used for metadata first." else "Built-in keys are in use."
                setState("tmdb", if (on) "Saved" else "Not set", on)
            }
        }
        sections += SectionSpec(
            "tmdb", "Keys & Services", "TMDB API Key",
            "Your own metadata key",
            "tmdb", TONE_TERTIARY, tmdbBody,
        )

        val aiBody: (LinearLayout) -> Unit = { content ->
            val aiSaved = Settings.aiApiKey() != null
            setState("ai", if (aiSaved) "Saved" else "Not set", aiSaved)

            add(
                content,
                label("Optional - reorders the rows you already match.", 12f, TEXT_DIM),
                2,
            )
            var selectedProvider = Settings.aiProviderId()
            val providerChips = LinkedHashMap<String, TextView>()
            val providerRow = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                )
            }
            AiService.Provider.entries.forEach { provider ->
                val target = chip(provider.label)
                providerChips[provider.id] = target
                providerRow.addView(target)
            }
            add(
                content,
                HorizontalScrollView(context).apply {
                    overScrollMode = View.OVER_SCROLL_NEVER
                    isFillViewport = false
                    isHorizontalFadingEdgeEnabled = false
                    isHorizontalScrollBarEnabled = false
                    isVerticalScrollBarEnabled = false
                    setPadding(0, 0, 0, 0)
                    addView(providerRow)
                },
                12,
            )
            val aiKeySecret = secret(Settings.aiApiKey().orEmpty(), "provider api key...")
            val aiKeyInput = aiKeySecret.input
            val aiModelInput = modelField(
                Settings.aiModel(selectedProvider).ifBlank {
                    AiService.Provider.from(selectedProvider).defaultModel
                },
                "model id...",
            )
            val aiBaseInput = field(Settings.aiBaseUrl(selectedProvider), "https://host/v1")
            aiBaseInput.visibility = if (selectedProvider == "other") View.VISIBLE else View.GONE
            aiModelInput.setOnClickListener {
                if (aiModelInput.adapter != null) aiModelInput.showDropDown()
            }
            val aiStatus = label("", 12f, TEXT_DIM).apply {
                setPadding(0, dp(10), 0, 0)
                visibility = View.GONE
            }
            add(content, aiKeySecret.root, 10)
            add(content, aiModelInput, 8)
            add(content, aiBaseInput, 8)
            add(content, aiStatus)
            val aiVerifyChip = chip("Verify")
            val aiModelsChip = chip("Models")
            val aiClear = action("Clear", false)
            val aiSave = action("Save", true)
            addRow(content, 12, aiVerifyChip, aiModelsChip)
            addRow(content, 10, aiClear, aiSave)
            fun paintProviders() {
                providerChips.forEach { (id, target) -> markChip(target, id == selectedProvider) }
            }
            paintProviders()
            providerChips.forEach { (id, target) ->
                target.setOnClickListener {
                    selectedProvider = id
                    aiKeyInput.setText(Settings.aiApiKey(id).orEmpty())
                    aiModelInput.setAdapter(null)
                    aiModelInput.setText(
                        Settings.aiModel(id).ifBlank { AiService.Provider.from(id).defaultModel },
                    )
                    aiBaseInput.setText(Settings.aiBaseUrl(id))
                    aiBaseInput.visibility = if (id == "other") View.VISIBLE else View.GONE
                    val saved = Settings.aiApiKey(id) != null
                    setState("ai", if (saved) "Saved" else "Not set", saved)
                    paintProviders()
                }
            }
            aiVerifyChip.setOnClickListener {
                val provider = AiService.Provider.from(selectedProvider)
                if (aiKeyInput.text.toString().isBlank()) {
                    aiKeyInput.error = "Enter a key first"
                    aiStatus.visibility = View.VISIBLE
                    aiStatus.text = "Enter a key, select a model, then verify and save."
                    return@setOnClickListener
                }
                if (aiModelInput.text.toString().isBlank()) {
                    aiModelInput.error = "Select a model first"
                    aiStatus.visibility = View.VISIBLE
                    aiStatus.text = "Select a model first, then verify and save."
                    return@setOnClickListener
                }
                aiKeyInput.error = null
                aiModelInput.error = null
                aiStatus.visibility = View.VISIBLE
                aiStatus.text = "Verifying key..."
                Thread {
                    val msg = runCatching {
                        kotlinx.coroutines.runBlocking {
                            AiService.testKey(
                                provider,
                                aiBaseInput.text.toString(),
                                aiModelInput.text.toString(),
                                aiKeyInput.text.toString(),
                            )
                        }
                    }.getOrDefault("Verify failed - no network?")
                    aiKeyInput.post {
                        aiStatus.visibility = View.VISIBLE
                        aiStatus.text = msg
                    }
                }.start()
            }
            aiModelsChip.setOnClickListener {
                val provider = AiService.Provider.from(selectedProvider)
                aiStatus.visibility = View.VISIBLE
                aiStatus.text = "Fetching free models..."
                Thread {
                    val models = runCatching {
                        kotlinx.coroutines.runBlocking {
                            AiService.fetchModels(
                                provider,
                                aiBaseInput.text.toString(),
                                aiKeyInput.text.toString(),
                            )
                        }
                    }.getOrDefault(emptyList())
                    aiKeyInput.post {
                        aiStatus.visibility = View.VISIBLE
                        if (models.isEmpty()) {
                            aiStatus.text = "No free models found - check the key or base url"
                        } else {
                            aiModelInput.setAdapter(
                                ArrayAdapter(
                                    context,
                                    android.R.layout.simple_dropdown_item_1line,
                                    models,
                                ),
                            )
                            aiModelInput.showDropDown()
                            aiStatus.text = "${models.size} free models - pick one"
                        }
                    }
                }.start()
            }
            aiClear.setOnClickListener {
                Settings.clearAi(context, selectedProvider)
                aiKeyInput.setText("")
                aiModelInput.setAdapter(null)
                aiModelInput.setText(AiService.Provider.from(selectedProvider).defaultModel)
                aiBaseInput.setText("")
                aiStatus.visibility = View.GONE
                setState("ai", "Not set", false)
            }
            aiSave.setOnClickListener {
                Settings.saveAi(
                    context, selectedProvider,
                    aiKeyInput.text.toString(), aiModelInput.text.toString(), aiBaseInput.text.toString(),
                )
                val on = Settings.aiApiKey(selectedProvider) != null
                aiStatus.visibility = View.VISIBLE
                aiStatus.text = if (on) "Saved - the model reorders your rows." else "Cleared - rows use the local score."
                setState("ai", if (on) "Saved" else "Not set", on)
            }
        }
        sections += SectionSpec(
            "ai", "Keys & Services", "AI API Keys",
            "Reorders rows with your own model",
            "ai", TONE_TERTIARY, aiBody,
        )

        // ---------- pages scaffold ----------

        val pages = FrameLayout(context).apply {
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            )
        }

        // faint aurora from the top corners: primary on one side, tertiary on the other
        val glowStart = glow(withAlpha(ACCENT, 26), 190)
        glowStart.layoutParams = FrameLayout.LayoutParams(dp(380), dp(380)).apply {
            gravity = Gravity.TOP or Gravity.START
            leftMargin = dp(-140)
            topMargin = dp(-140)
        }
        pages.addView(glowStart)
        val glowEnd = glow(withAlpha(TONE_TERTIARY.bead, 20), 190)
        glowEnd.layoutParams = FrameLayout.LayoutParams(dp(380), dp(380)).apply {
            gravity = Gravity.TOP or Gravity.END
            rightMargin = dp(-150)
            topMargin = dp(-120)
        }
        pages.addView(glowEnd)

        val homePage = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            )
        }

        val homeHeader = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(14), dp(14), dp(4))
        }
        homeHeader.addView(label("INDFLIX", 10f, TEXT_FAINT, medium = true, spacing = 0.26f))
        val homeTitleRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = dp(2) }
        }
        homeTitleRow.addView(
            label("Settings", 30f, TEXT, medium = true),
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f),
        )
        val close = glassCircleButton("close", "Close")
        homeTitleRow.addView(close)
        homeHeader.addView(homeTitleRow)
        homeHeader.addView(
            label("Catalogue, sources, keys and playback", 12f, TEXT_FAINT)
                .apply { setPadding(0, dp(5), 0, 0) },
        )
        homePage.addView(homeHeader)

        val homeContent = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(2), dp(16), dp(30))
        }
        val homeScroll = ScrollView(context).apply {
            overScrollMode = View.OVER_SCROLL_NEVER
            isFillViewport = false
            isVerticalFadingEdgeEnabled = false
            isHorizontalScrollBarEnabled = false
            isVerticalScrollBarEnabled = false
            addView(
                homeContent,
                ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ),
            )
        }
        homePage.addView(
            homeScroll,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f,
            ),
        )
        pages.addView(homePage)

        val detailPages = LinkedHashMap<String, View>()
        var currentDetail: View? = null
        // shared-axis motion: emphasized decel in, symmetric ease-out back
        val enterInterpolator = PathInterpolator(0.05f, 0.7f, 0.1f, 1.0f)
        val exitInterpolator = PathInterpolator(0.3f, 0.0f, 0.8f, 1.0f)

        fun openSection(id: String) {
            val page = detailPages[id] ?: return
            if (currentDetail != null) return
            currentDetail = page
            val full = (pages.width.takeIf { it > 0 }
                ?: (context.resources.displayMetrics.widthPixels * 0.94f).roundToInt()).toFloat()
            page.translationX = full
            page.alpha = 0.05f
            page.scaleX = 0.98f
            page.scaleY = 0.98f
            page.visibility = View.VISIBLE
            page.animate().translationX(0f).alpha(1f).scaleX(1f).scaleY(1f)
                .setDuration(360)
                .setInterpolator(enterInterpolator)
                .start()
            homePage.animate().translationX(-full * 0.22f).alpha(0.3f)
                .setDuration(360)
                .setInterpolator(enterInterpolator)
                .withEndAction { if (currentDetail != null) homePage.visibility = View.GONE }
                .start()
        }

        fun navigateBack(): Boolean {
            val page = currentDetail ?: return false
            currentDetail = null
            val full = (pages.width.takeIf { it > 0 }
                ?: (context.resources.displayMetrics.widthPixels * 0.94f).roundToInt()).toFloat()
            homePage.visibility = View.VISIBLE
            homePage.translationX = -full * 0.22f
            homePage.alpha = 0.3f
            homePage.animate().translationX(0f).alpha(1f)
                .setDuration(320)
                .setInterpolator(exitInterpolator)
                .start()
            page.animate().translationX(full).alpha(0.05f)
                .setDuration(320)
                .setInterpolator(exitInterpolator)
                .withEndAction {
                    page.visibility = View.GONE
                    page.translationX = 0f
                    page.alpha = 1f
                    page.scaleX = 1f
                    page.scaleY = 1f
                }
                .start()
            return true
        }

        sections.forEach { spec ->
            val page = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                visibility = View.GONE
                layoutParams = FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT,
                )
            }
            val toolbar = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(10), dp(10), dp(14), dp(10))
            }
            val back = glassCircleButton("back", "Back")
            back.setOnClickListener { navigateBack() }
            toolbar.addView(back)
            val miniTile = FrameLayout(context).apply {
                layoutParams = LinearLayout.LayoutParams(dp(40), dp(40)).apply { leftMargin = dp(12) }
                background = iconChip(spec.tone, 12)
                elevation = dp(1).toFloat()
            }
            miniTile.addView(
                ImageView(context).apply {
                    scaleType = ImageView.ScaleType.CENTER_INSIDE
                    setImageDrawable(icon(spec.icon, spec.tone.glyph, 20))
                    setPadding(dp(10), dp(10), dp(10), dp(10))
                    layoutParams = FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT,
                    )
                },
            )
            toolbar.addView(miniTile)
            val titleWrap = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(10), 0, dp(8), 0)
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            }
            titleWrap.addView(label(spec.title, 18f, TEXT, medium = true))
            val overline = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, dp(2), 0, 0)
            }
            overline.addView(beadView(spec.tone))
            overline.addView(
                label(spec.group.uppercase(), 10f, TEXT_DIM, medium = true, spacing = 0.12f),
            )
            titleWrap.addView(overline)
            toolbar.addView(titleWrap)
            val pill = statePill()
            toolbar.addView(pill.root)
            stateListeners += { id, value, on ->
                if (id == spec.id) paintStatePill(pill, value, on)
            }
            stateCache[spec.id]?.let { (value, on) -> paintStatePill(pill, value, on) }
            page.addView(toolbar)
            page.addView(dividerRow())
            val body = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(18), dp(4), dp(18), dp(30))
            }
            val pageScroll = ScrollView(context).apply {
                overScrollMode = View.OVER_SCROLL_NEVER
                isFillViewport = false
                isVerticalFadingEdgeEnabled = false
                isVerticalScrollBarEnabled = false
                addView(
                    body,
                    ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                    ),
                )
            }
            page.addView(
                pageScroll,
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f,
                ),
            )
            spec.build(body)
            detailPages[spec.id] = page
            pages.addView(page)
        }

        // soft bottom edge so content dissolves instead of being cut
        val bottomScrim = View(context).apply {
            background = GradientDrawable(
                GradientDrawable.Orientation.TOP_BOTTOM,
                intArrayOf(Color.TRANSPARENT, Color.argb(235, 0x0E, 0x11, 0x16)),
            )
            isClickable = false
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(30), Gravity.BOTTOM,
            )
        }
        pages.addView(bottomScrim)

        fun iconTile(spec: SectionSpec): View {
            val holder = FrameLayout(context).apply {
                layoutParams = LinearLayout.LayoutParams(dp(48), dp(48)).apply { rightMargin = dp(12) }
                background = iconChip(spec.tone, 16)
                elevation = dp(2).toFloat()
            }
            holder.addView(
                ImageView(context).apply {
                    scaleType = ImageView.ScaleType.CENTER_INSIDE
                    setImageDrawable(icon(spec.icon, spec.tone.glyph, 24))
                    setPadding(dp(12), dp(12), dp(12), dp(12))
                    layoutParams = FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT,
                    )
                },
            )
            return holder
        }

        fun sectionTile(spec: SectionSpec): View {
            val cached = stateCache[spec.id]
            val tile = iconTile(spec)
            val texts = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            }
            texts.addView(
                label(spec.title, 15f, TEXT, medium = true).apply {
                    maxLines = 1
                    ellipsize = TextUtils.TruncateAt.END
                },
            )
            texts.addView(
                label(spec.subtitle, 12f, TEXT_FAINT).apply {
                    setPadding(0, dp(3), 0, 0)
                    maxLines = 1
                    ellipsize = TextUtils.TruncateAt.END
                },
            )
            val pill = statePill()
            cached?.let { (value, on) -> paintStatePill(pill, value, on) }
            val chevron = ImageView(context).apply {
                scaleType = ImageView.ScaleType.CENTER
                setImageDrawable(icon("chevron", TEXT_FAINT, 18))
                layoutParams = LinearLayout.LayoutParams(dp(18), dp(18)).apply { leftMargin = dp(8) }
            }
            val root = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(12), dp(12), dp(12), dp(12))
                isClickable = true
                isFocusable = true
                background = ripple(rounded(Color.argb(12, 255, 255, 255), 24, 1, Color.argb(20, 255, 255, 255)))
                addView(tile)
                addView(texts)
                addView(pill.root)
                addView(chevron)
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                ).apply { bottomMargin = dp(8) }
                contentDescription = spec.title
            }
            stateListeners += { id, value, on ->
                if (id == spec.id) paintStatePill(pill, value, on)
            }
            root.setOnClickListener { openSection(spec.id) }
            return root
        }

        val groupOrder = listOf("Content", "Sources", "Keys & Services")
        groupOrder.forEach { group ->
            val groupRow = LinearLayout(context).apply {
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(8), dp(14), 0, dp(8))
            }
            groupRow.addView(beadView(toneForGroup(group)))
            groupRow.addView(
                label(group.uppercase(), 10f, TEXT_DIM, medium = true, spacing = 0.12f),
            )
            homeContent.addView(groupRow)
            sections.filter { it.group == group }.forEach { spec ->
                homeContent.addView(sectionTile(spec))
            }
        }
        homeContent.addView(
            label("Settings are stored on this device", 10f, TEXT_FAINT).apply {
                gravity = Gravity.CENTER
                setPadding(0, dp(12), 0, dp(2))
            },
        )

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
            isClickable = false
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
                    ViewGroup.LayoutParams.MATCH_PARENT,
                ),
            )
        }

        val built = AlertDialog.Builder(context, android.R.style.Theme_Material_Dialog)
            .setView(sheet)
            .create()
        dialog = built
        built.setOnDismissListener { providerProbeJob?.cancel() }
        var closing = false
        fun dismissSheet() {
            if (closing) return
            closing = true
            sheet.animate().scaleX(0.97f).scaleY(0.97f).alpha(0f)
                .setDuration(170)
                .withEndAction { built.dismiss() }
                .start()
        }
        close.setOnClickListener { dismissSheet() }
        built.setOnKeyListener { _, keyCode, event ->
            if (keyCode == KeyEvent.KEYCODE_BACK && event.action == KeyEvent.ACTION_UP) {
                if (!navigateBack()) dismissSheet()
                true
            } else {
                false
            }
        }
        built.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        built.window?.setDimAmount(0.55f)
        built.show()
        val metrics = context.resources.displayMetrics
        built.window?.setLayout(
            minOf((metrics.widthPixels * 0.94f).roundToInt(), dp(720)),
            minOf((metrics.heightPixels * 0.92f).roundToInt(), dp(880)),
        )

        // expressive entrance: the sheet settles, home content staggers up
        sheet.scaleX = 0.96f
        sheet.scaleY = 0.96f
        sheet.alpha = 0f
        sheet.animate().scaleX(1f).scaleY(1f).alpha(1f)
            .setDuration(300)
            .setInterpolator(enterInterpolator)
            .start()
        for (index in 0 until homeContent.childCount) {
            val child = homeContent.getChildAt(index)
            child.translationY = dp(14).toFloat()
            child.alpha = 0f
            child.animate().translationY(0f).alpha(1f)
                .setStartDelay((index * 24).toLong())
                .setDuration(260)
                .setInterpolator(DecelerateInterpolator(1.6f))
                .start()
        }

        Thread {
            SimklHistory.refresh()
            pages.post { refreshForYouState() }
        }.start()
    }

    private fun openLink(context: Context, url: String) {
        runCatching {
            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        }.onFailure {
            Toast.makeText(context, "No browser found", Toast.LENGTH_SHORT).show()
        }
    }
}
