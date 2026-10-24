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
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
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

    private val BG = Color.parseColor("#0B0B10")
    private val SURFACE = Color.parseColor("#14141A")
    private val SURFACE_2 = Color.parseColor("#1C1C24")
    private val BORDER = Color.parseColor("#2B2B35")
    private val DIVIDER = Color.parseColor("#23232B")
    private val TEXT = Color.parseColor("#F4F4F8")
    private val TEXT_DIM = Color.parseColor("#9A9AAB")
    private val TEXT_FAINT = Color.parseColor("#6E6E7E")
    private val ACCENT = Color.parseColor("#4C8DFF")
    private val SUCCESS = Color.parseColor("#3FD37A")
    private val SUCCESS_SOFT = Color.parseColor("#122E1F")
    private val WARNING = Color.parseColor("#FF7A6B")

    private const val GLYPH_CHEVRON = "\u203A"
    private const val GLYPH_CLOSE = "\u2715"

    private class Card(
        val root: LinearLayout,
        val shell: LinearLayout,
        val content: LinearLayout,
        val state: TextView,
        val chevron: TextView,
        val bar: View,
    )

    private class Option(
        val root: LinearLayout,
        val dot: View,
        val title: TextView,
        val summary: TextView,
    )

    private class Secret(val root: LinearLayout, val input: EditText, val toggle: View)

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

        fun ripple(content: Drawable, color: Int = Color.argb(34, 255, 255, 255)): RippleDrawable =
            RippleDrawable(ColorStateList.valueOf(color), content, null)

        val control = dp(42)
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
            label(value, 12f, if (primary) Color.WHITE else TEXT_DIM, medium = true).apply {
                gravity = Gravity.CENTER
                setPadding(dp(16), 0, dp(16), 0)
                background = ripple(
                    rounded(
                        if (primary) ACCENT else Color.TRANSPARENT,
                        12,
                        if (primary) 0 else 1,
                        if (primary) ACCENT else BORDER,
                    ),
                )
                isClickable = true
                isFocusable = true
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, control,
                ).apply { rightMargin = gap }
            }

        fun chip(value: String): TextView = pill(value)

        fun markChip(target: TextView, on: Boolean) {
            target.setTextColor(if (on) Color.WHITE else TEXT_DIM)
            target.background = ripple(
                rounded(if (on) ACCENT else Color.TRANSPARENT, 12, if (on) 0 else 1),
            )
        }

        fun action(value: String, primary: Boolean): TextView = pill(value, primary).apply {
            setTextColor(if (primary) Color.WHITE else TEXT)
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
            background = StateListDrawable().apply {
                addState(intArrayOf(android.R.attr.state_focused), rounded(SURFACE_2, 12, 1, ACCENT))
                addState(intArrayOf(), rounded(SURFACE_2, 12, 1))
            }
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
                background = StateListDrawable().apply {
                    addState(intArrayOf(android.R.attr.state_focused), rounded(SURFACE_2, 12, 1, ACCENT))
                    addState(intArrayOf(), rounded(SURFACE_2, 12, 1))
                }
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
                background = ripple(rounded(Color.TRANSPARENT, 999, 1))
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
                toggle.setImageDrawable(eyeIcon(visible, if (visible) ACCENT else TEXT_DIM))
                toggle.contentDescription = if (visible) "Hide key" else "Show key"
            }
            var visible = false
            paint(false)
            toggle.setOnClickListener {
                visible = !visible
                paint(visible)
            }
            return Secret(root, input, toggle)
        }

        fun dividerRow(): View = View(context).apply {
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1))
            setBackgroundColor(DIVIDER)
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
            val titleView = label(title, 13.5f, TEXT, medium = true)
            val summaryView = label(summary, 11.5f, TEXT_DIM).apply {
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
            return Option(root, dot, titleView, summaryView)
        }

        fun paintOption(opt: Option, on: Boolean) {
            opt.root.background = ripple(
                rounded(if (on) SURFACE_2 else Color.TRANSPARENT, 12, 1, if (on) ACCENT else BORDER),
            )
            opt.dot.background = if (on) {
                LayerDrawable(arrayOf(circle(ACCENT), circle(BG))).apply {
                    setLayerInset(1, dp(5), dp(5), dp(5), dp(5))
                }
            } else {
                circle(Color.TRANSPARENT, 2, BORDER)
            }
        }

        fun smallToggle(initial: Boolean, onChange: (Boolean) -> Unit): View {
            val root = FrameLayout(context).apply {
                layoutParams = LinearLayout.LayoutParams(dp(46), dp(28))
                isClickable = true
                isFocusable = true
                contentDescription = if (initial) "Disable" else "Enable"
            }
            val track = View(context).apply {
                layoutParams = FrameLayout.LayoutParams(dp(46), dp(28))
            }
            val thumb = View(context).apply {
                layoutParams = FrameLayout.LayoutParams(dp(22), dp(22), Gravity.START or Gravity.CENTER_VERTICAL)
                    .apply { leftMargin = dp(3) }
            }
            root.addView(track)
            root.addView(thumb)
            var checked = initial
            fun paint() {
                track.background = rounded(
                    if (checked) ACCENT else SURFACE_2, 999, 1, if (checked) ACCENT else BORDER,
                )
                thumb.background = circle(if (checked) Color.WHITE else TEXT_DIM)
                thumb.translationX = if (checked) dp(18).toFloat() else 0f
                root.contentDescription = if (checked) "Disable" else "Enable"
            }
            paint()
            root.setOnClickListener {
                checked = !checked
                paint()
                onChange(checked)
            }
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
                background = rounded(BG, 24)
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
            head.addView(label(title, 17f, TEXT, medium = true))
            panel.addView(head)
            panel.addView(
                label(subtitle, 11.5f, TEXT_DIM).apply { setPadding(0, dp(6), 0, 0) },
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
                            background = rounded(SURFACE_2, 12, 1, BORDER)
                            addView(
                                label(name, 12.5f, TEXT_DIM),
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
                background = rounded(BG, 24)
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

            val status = label("", 11.5f, TEXT_DIM, medium = true).apply {
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
                    status.setTextColor(ACCENT)
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

                fun step(glyph: String, description: String, enabled: Boolean, onClick: () -> Unit): TextView =
                    label(glyph, 13f, if (enabled) TEXT else TEXT_FAINT, medium = true).apply {
                        gravity = Gravity.CENTER
                        background = ripple(rounded(SURFACE_2, 10, 1, if (enabled) BORDER else DIVIDER))
                        layoutParams = LinearLayout.LayoutParams(dp(36), dp(36)).apply { leftMargin = dp(6) }
                        isClickable = enabled
                        isFocusable = enabled
                        alpha = if (enabled) 1f else 0.4f
                        contentDescription = description
                        setOnClickListener { if (enabled) onClick() }
                    }

                rows.forEachIndexed { index, row ->
                    val item = LinearLayout(context).apply {
                        orientation = LinearLayout.HORIZONTAL
                        gravity = Gravity.CENTER_VERTICAL
                        setPadding(dp(10), dp(8), dp(8), dp(8))
                        background = ripple(rounded(SURFACE_2, 12, 1, BORDER))
                        layoutParams = LinearLayout.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                        ).apply { bottomMargin = dp(6) }
                    }
                    val number = label(
                        "${index + 1}",
                        12.5f,
                        ACCENT,
                        medium = true,
                    ).apply {
                        gravity = Gravity.CENTER
                        background = rounded(Color.TRANSPARENT, 9, 1, BORDER)
                        layoutParams = LinearLayout.LayoutParams(dp(32), dp(32))
                        contentDescription = "Position ${index + 1}"
                    }
                    val title = label(row.title, 13f, TEXT, medium = true).apply {
                        setPadding(dp(10), 0, dp(8), 0)
                        maxLines = 2
                        ellipsize = TextUtils.TruncateAt.END
                    }
                    val up = step("\u25B2", "Move ${row.title} up", index > 0) {
                        rows.removeAt(index)
                        rows.add(index - 1, row)
                        render()
                    }
                    val down = step("\u25BC", "Move ${row.title} down", index < rows.lastIndex) {
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

        val content = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(18), dp(18), dp(12))
        }

        val scroll = ScrollView(context).apply {
            overScrollMode = View.OVER_SCROLL_NEVER
            isVerticalFadingEdgeEnabled = false
            isFillViewport = false
            addView(
                content,
                ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ),
            )
        }

        val sheet = FrameLayout(context).apply {
            background = rounded(BG, 24)
            clipToOutline = true
            addView(
                scroll,
                FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT,
                ),
            )
        }

        var dialog: AlertDialog? = null
        var providerProbeJob: Job? = null

        fun fitHeight() {
            val win = dialog?.window ?: return
            val metrics = context.resources.displayMetrics
            val width = (metrics.widthPixels * 0.92f).roundToInt()
            val maxHeight = (metrics.heightPixels * 0.86f).roundToInt()
            sheet.measure(
                View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
            )
            win.setLayout(width, sheet.measuredHeight.coerceAtMost(maxHeight))
        }

        fun setState(card: Card, value: String, on: Boolean) {
            card.state.text = value
            card.state.setTextColor(if (on) SUCCESS else TEXT_DIM)
            card.state.background = rounded(if (on) SUCCESS_SOFT else SURFACE_2, 999)
            card.bar.setBackgroundColor(if (on) SUCCESS else BORDER)
        }

        fun toggle(card: Card) {
            if (card.shell.visibility == View.VISIBLE) {
                card.shell.animate().alpha(0f).setDuration(120).withEndAction {
                    card.shell.visibility = View.GONE
                    fitHeight()
                }.start()
                card.chevron.animate().rotation(0f).setDuration(170).start()
            } else {
                card.shell.visibility = View.VISIBLE
                card.shell.alpha = 0f
                card.shell.animate().alpha(1f).setDuration(150).start()
                card.chevron.animate().rotation(90f).setDuration(170).start()
                card.shell.post { fitHeight() }
            }
        }

        fun add(card: Card, child: View, top: Int = 0) {
            child.layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = dp(top) }
            card.content.addView(child)
        }

        fun addRow(card: Card, top: Int, vararg views: View) {
            val row = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            views.forEachIndexed { index, view ->
                val lp = LinearLayout.LayoutParams(0, control, 1f)
                if (index > 0) lp.leftMargin = gap
                row.addView(view, lp)
            }
            add(card, row, top)
        }

        fun card(title: String, subtitle: String, stateText: String, stateOn: Boolean): Card {
            val bar = View(context).apply {
                layoutParams = LinearLayout.LayoutParams(dp(3), dp(18)).apply { rightMargin = dp(12) }
                setBackgroundColor(if (stateOn) SUCCESS else BORDER)
            }
            val titles = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            }
            titles.addView(label(title, 15f, TEXT, medium = true))
            titles.addView(
                label(subtitle, 11.5f, TEXT_DIM).apply { setPadding(0, dp(3), 0, 0) },
            )
            val state = label(stateText, 10f, if (stateOn) SUCCESS else TEXT_DIM, medium = true).apply {
                setPadding(dp(9), dp(4), dp(9), dp(4))
                background = rounded(if (stateOn) SUCCESS_SOFT else SURFACE_2, 999)
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                ).apply { rightMargin = dp(8) }
            }
            val chevron = label(GLYPH_CHEVRON, 20f, TEXT_FAINT).apply {
                gravity = Gravity.CENTER
                layoutParams = LinearLayout.LayoutParams(dp(24), dp(24))
            }
            val header = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(14), dp(14), dp(10), dp(14))
                isClickable = true
                isFocusable = true
                background = ripple(rounded(Color.TRANSPARENT, 16))
                addView(bar)
                addView(titles)
                addView(state)
                addView(chevron)
            }
            val inner = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(16), dp(0), dp(16), dp(16))
            }
            val shell = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                visibility = View.GONE
                addView(dividerRow())
                addView(inner)
            }
            val root = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                background = rounded(SURFACE, 16, 1)
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                ).apply { bottomMargin = dp(10) }
                addView(header)
                addView(shell)
            }
            val built = Card(root, shell, inner, state, chevron, bar)
            header.setOnClickListener { toggle(built) }
            content.addView(root)
            return built
        }

        fun sectionLabel(value: String, top: Int): TextView =
            label(value, 10f, TEXT_FAINT, medium = true, spacing = 0.08f).apply {
                setPadding(0, dp(top), 0, dp(8))
                text = value.uppercase()
            }


        val close = label(GLYPH_CLOSE, 14f, TEXT_DIM).apply {
            gravity = Gravity.CENTER
            setPadding(dp(11), dp(9), dp(11), dp(9))
            background = ripple(rounded(Color.TRANSPARENT, 999))
            isClickable = true
            isFocusable = true
            contentDescription = "Close"
        }
        val heading = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        heading.addView(label("IndStream Settings", 20f, TEXT, medium = true))
        heading.addView(
            label("Keys are optional - built-ins apply while a field is empty.", 11.5f, TEXT_DIM)
                .apply { setPadding(0, dp(5), 0, 0) },
        )
        content.addView(
            LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(4), dp(0), dp(0), dp(16))
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                )
                addView(heading)
                addView(close)
            },
        )


        fun buildProviderCard(): Card {
            val title = "Catalogue Provider"
            val subtitle = "Pick one source at a time — selecting another turns off the current one."
            val groupCard = card(title, subtitle, Catalogue.activeProvider().label, true)
            val options = LinkedHashMap<String, Option>()
            val list = optionList()
            Catalogue.Provider.entries.forEach { provider ->
                val opt = option(
                    provider.label,
                    if (provider == Catalogue.Provider.TMDB) "Preferred" else "IMDB backed catalog",
                )
                options[provider.id] = opt
                list.addView(opt.root)
            }
            add(groupCard, list, 8)

            fun paint() {
                val current = Catalogue.activeProvider()
                options.forEach { (id, opt) ->
                    paintOption(opt, id == current.id)
                }
                setState(groupCard, current.label, true)
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
            return groupCard
        }

        fun buildCatalogueCard(): Card {
            var selected = Catalogue.Group.GLOBAL
            fun rowsOf(group: Catalogue.Group): List<Pair<String, String>> = Catalogue.rowsFor(group)
            // the card reports totals across every group, not just the selected one
            fun enabledCount(): Int = Catalogue.enabledRows().size
            fun totalCount(): Int = Catalogue.allRows().size

            val groupCard = card(
                "Catalogue",
                "Toggle rows by group, or reorder every row at once",
                "${enabledCount()} of ${totalCount()} enabled",
                enabledCount() > 0,
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
            // pills share the card width evenly; natural width is the floor on narrow screens
            val sheetWidth = (context.resources.displayMetrics.widthPixels * 0.92f).roundToInt()
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
            add(groupCard, chipScroll, 6)

            val reorder = action("Reorder home rows", primary = false)
            add(groupCard, reorder, 8)
            reorder.layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(42),
            ).apply { topMargin = dp(8) }

            val enableAll = chip("Enable all")
            val disableAll = chip("Disable all")
            val quick = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.END or Gravity.CENTER_VERTICAL
            }
            listOf(enableAll, disableAll).forEachIndexed { index, view ->
                view.setPadding(dp(14), 0, dp(14), 0)
                view.layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, dp(36),
                ).apply { if (index > 0) leftMargin = gap }
                quick.addView(view)
            }
            add(groupCard, quick, 6)

            val list = optionList()
            add(groupCard, list, 8)

            fun paintChips() {
                groupChips.forEach { (group, view) -> markChip(view, group == selected) }
            }

            fun refreshState() {
                val total = totalCount()
                val on = enabledCount()
                setState(groupCard, "$on of $total enabled", on > 0)
            }

            fun renderRows() {
                list.removeAllViews()
                val options = LinkedHashMap<String, Option>()
                rowsOf(selected).forEach { (key, title) ->
                    val opt = option(
                        title.substringAfter(" \u00B7 ", title),
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
                fitHeight()
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
            return groupCard
        }

        content.addView(sectionLabel("Catalogue", 0))
        buildProviderCard()
        buildCatalogueCard()


        val forYouCard = card(
            "Made for You - Recommendation",
            "Rows built from your watch history",
            "Checking",
            false,
        )
        add(
            forYouCard,
            label("Enabling \"Made for You - Recommendation\" catalogue", 12f, TEXT_DIM)
                .apply { setPadding(0, dp(12), 0, 0) },
        )
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
        add(forYouCard, shuffleOpt.root, 4)
        add(forYouCard, sectionLabel("Refresh schedule", 18))
        val modeOptions = LinkedHashMap<ForYouRefresh.Mode, Option>()
        val modeList = optionList()
        var selectedMode = Settings.forYouRefreshMode()
        ForYouRefresh.Mode.entries.forEach { mode ->
            val opt = option(mode.label, mode.summary)
            modeOptions[mode] = opt
            modeList.addView(opt.root)
        }
        add(forYouCard, modeList)
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
            ).apply { topMargin = dp(6) }
        }
        forYouCard.content.addView(refreshNow)
        refreshNow.setOnClickListener {
            if (!AiService.isConfigured()) {
                reportDialog(
                    title = "Connect an AI provider",
                    subtitle = "Made for You needs a key under AI API Keys, then pick a model and verify.",
                    rows = listOf(
                        Triple("AI API key", "Not connected", WARNING),
                    ),
                    accent = WARNING,
                    actionLabel = "Got it",
                )
                return@setOnClickListener
            }
            refreshNow.isEnabled = false
            refreshNow.text = "Rebuilding..."
            ForYouEngine.invalidate(context)
            CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
                val movieCount = ForYouEngine.row("movie", 1).size
                val tvCount = ForYouEngine.row("tv", 1).size
                val total = movieCount + tvCount
                refreshNow.post {
                    refreshNow.text = "Refresh now"
                    refreshNow.isEnabled = true
                    setState(forYouCard, if (total > 0) "On" else "Off", total > 0)
                    if (dialog?.isShowing == true) {
                        reportDialog(
                            title = "Made for You",
                            subtitle = if (total > 0) {
                                "Recommendations rebuilt from your watch history."
                            } else {
                                "No picks were built. Watch a few titles, then try again."
                            },
                            rows = listOf(
                                Triple("Movies", "$movieCount picks", if (movieCount > 0) SUCCESS else TEXT_DIM),
                                Triple("Series", "$tvCount picks", if (tvCount > 0) SUCCESS else TEXT_DIM),
                            ),
                            accent = if (total > 0) SUCCESS else WARNING,
                        )
                    }
                }
            }
        }


        val allServers = ServerFarm.allServers
        var enabledCount = allServers.count { Settings.serverEnabled(it.id) }
        fun providersStateText(): String = "$enabledCount of ${allServers.size} enabled"
        val providersCard = card(
            "Servers/Providers",
            "Choose which providers supply streams",
            providersStateText(),
            enabledCount > 0,
        )
        fun refreshProvidersState() {
            enabledCount = allServers.count { Settings.serverEnabled(it.id) }
            setState(
                providersCard,
                providersStateText(),
                enabledCount > 0,
            )
        }

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
            return tags.take(4).joinToString(" \u00B7 ")
        }

        fun providerTile(spec: ServerSpec): ProviderTile {
            val dot = View(context).apply {
                layoutParams = LinearLayout.LayoutParams(dp(9), dp(9)).apply { leftMargin = dp(8) }
            }
            val name = label(spec.name, 12.5f, TEXT, medium = true).apply {
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            }
            val probeStatus = label("Not tested", 8.5f, TEXT_FAINT, medium = true).apply {
                maxLines = 1
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                ).apply { leftMargin = dp(4) }
            }
            val titleRow = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                addView(name)
                addView(probeStatus)
                addView(dot)
            }
            val tags = label(providerTags(spec), 9.5f, TEXT_FAINT).apply {
                maxLines = 2
                ellipsize = TextUtils.TruncateAt.END
                setPadding(0, dp(4), 0, 0)
            }
            val root = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(11), dp(9), dp(11), dp(9))
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
                rounded(if (on) SURFACE_2 else Color.TRANSPARENT, 12, 1, if (on) ACCENT else BORDER),
            )
            tile.dot.background = if (on) circle(SUCCESS) else circle(Color.TRANSPARENT, 2, BORDER)
            tile.probeStatus.text = when (probeState) {
                ProviderProbeState.NOT_TESTED -> "Not tested"
                ProviderProbeState.TESTING -> "Testing"
                ProviderProbeState.UP -> "Up"
                ProviderProbeState.DOWN -> "Down"
            }
            tile.probeStatus.setTextColor(
                when (probeState) {
                    ProviderProbeState.UP -> SUCCESS
                    ProviderProbeState.DOWN -> Color.parseColor("#FF6B6B")
                    ProviderProbeState.TESTING -> ACCENT
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

        add(providersCard, sectionLabel("All servers", 18))
        val enableAll = chip("Enable all")
        val disableAll = chip("Disable all")
        val quickRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END or Gravity.CENTER_VERTICAL
        }
        listOf(enableAll, disableAll).forEachIndexed { index, view ->
            view.setPadding(dp(14), 0, dp(14), 0)
            view.layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, dp(36),
            ).apply { if (index > 0) leftMargin = gap }
            quickRow.addView(view)
        }
        add(providersCard, quickRow, 6)
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
            10.5f,
            TEXT_DIM,
        ).apply {
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(10), 0, 0, 0)
        }
        val testAllRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(
                testAllButton,
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(38)),
            )
            addView(
                testSummary,
                LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f),
            )
        }
        add(providersCard, testAllRow, 10)
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
                val lp = LinearLayout.LayoutParams(0, dp(60), 1f)
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
        add(providersCard, providerGrid, 10)


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
        val addonCard = card(
            "Stremio/Nuvio Extension",
            "Load streams from addon manifests",
            addonStateText(),
            addonEnabledCount() > 0,
        )
        fun refreshAddonState() {
            setState(addonCard, addonStateText(), addonEnabledCount() > 0)
        }

        val addonStatus = label(
            "Only direct HTTP(S) streams are played. Torrent and external links are detected but skipped.",
            11.5f, TEXT_DIM,
        ).apply { setPadding(0, dp(10), 0, 0) }
        val addonMore = chip("Add More")
        val addonCheck = chip("Check")

        val addonList = optionList()
        add(addonCard, addonList, 8)

        fun addonTitle(url: String): String =
            StremioNuvioAddons.cached(url)?.name ?: StremioNuvioAddons.hostOf(url)

        fun addonSummary(url: String): String {
            val status = addonChecks[url]
            return if (status.isNullOrBlank()) url else "$url\n$status"
        }

        fun deleteIcon(onClick: () -> Unit): TextView =
            label(GLYPH_CLOSE, 12f, TEXT_FAINT).apply {
                gravity = Gravity.CENTER
                setPadding(dp(8), dp(8), dp(8), dp(8))
                background = ripple(rounded(Color.TRANSPARENT, 999))
                isClickable = true
                isFocusable = true
                contentDescription = "Delete addon"
                setOnClickListener { onClick() }
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
                fitHeight()
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
                LinearLayout.LayoutParams(dp(46), dp(28)).apply { leftMargin = dp(6) },
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
                        fitHeight()
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
                    fitHeight()
                }
                val enabled = smallToggle(draftEnabled) { on -> draftEnabled = on }
                val row = LinearLayout(context).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    setPadding(dp(12), dp(10), dp(10), dp(10))
                    background = rounded(SURFACE_2, 12, 1, BORDER)
                    addView(input)
                    addView(
                        remove,
                        LinearLayout.LayoutParams(dp(32), dp(32)).apply { leftMargin = dp(4) },
                    )
                    addView(
                        enabled,
                        LinearLayout.LayoutParams(dp(46), dp(28)).apply { leftMargin = dp(6) },
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
                    fitHeight()
                }
            }
        }

        addRow(addonCard, 12, addonMore, addonCheck)
        add(addonCard, addonStatus)

        addonMore.setOnClickListener {
            if (draftVisible) {
                draftField?.requestFocus()
            } else {
                draftVisible = true
                renderAddons()
                fitHeight()
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


        val wyzieSaved = Settings.apiKey() != null
        val wyzieCard = card(
            "Subtitles Key",
            "Optional - built-in subtitles work without it",
            if (wyzieSaved) "Saved" else "Not set",
            wyzieSaved,
        )
        val wyzieStatus = label(
            if (wyzieSaved) "Your key is used for subtitle lookups." else "Built-in subtitles are in use.",
            11.5f, TEXT_DIM,
        ).apply { setPadding(0, dp(10), 0, 0) }
        val wyzieSecret = secret(Settings.apiKey().orEmpty(), "api key...")
        val wyzieKeyInput = wyzieSecret.input
        add(wyzieCard, sectionLabel("Wyzie", 14))
        add(wyzieCard, wyzieSecret.root)
        add(wyzieCard, wyzieStatus)
        val wyzieGetChip = chip("Get key")
        val wyzieVerifyChip = chip("Verify")
        val wyzieClear = action("Clear", false)
        val wyzieSave = action("Save", true)
        addRow(wyzieCard, 12, wyzieGetChip, wyzieVerifyChip)
        addRow(wyzieCard, 10, wyzieClear, wyzieSave)
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
            setState(wyzieCard, "Not set", false)
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
            setState(wyzieCard, if (on) "Saved" else "Not set", on)
        }


        val tmdbSaved = Settings.tmdbApiKey() != null
        val tmdbCard = card(
            "TMDB API Key",
            "Optional - built-in keys work without it",
            if (tmdbSaved) "Saved" else "Not set",
            tmdbSaved,
        )
        val tmdbStatus = label(
            if (tmdbSaved) "Your key is used for metadata first." else "Built-in keys are in use.",
            11.5f, TEXT_DIM,
        ).apply { setPadding(0, dp(10), 0, 0) }
        val tmdbSecret = secret(Settings.tmdbApiKey().orEmpty(), "api key...")
        val tmdbKeyInput = tmdbSecret.input
        add(
            tmdbCard,
            label("Paste the short API key, not the long access token.", 11.5f, TEXT_DIM),
            12,
        )
        add(tmdbCard, tmdbSecret.root, 8)
        add(tmdbCard, tmdbStatus)
        val tmdbGetChip = chip("Get key")
        val tmdbVerifyChip = chip("Verify")
        val tmdbClear = action("Clear", false)
        val tmdbSave = action("Save", true)
        addRow(tmdbCard, 12, tmdbGetChip, tmdbVerifyChip)
        addRow(tmdbCard, 10, tmdbClear, tmdbSave)
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
            setState(tmdbCard, "Not set", false)
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
            setState(tmdbCard, if (on) "Saved" else "Not set", on)
        }


        val aiSaved = Settings.aiApiKey() != null
        val aiCard = card(
            "AI API Keys",
            "Optional - reorders the rows you already match",
            if (aiSaved) "Saved" else "Not set",
            aiSaved,
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
            aiCard,
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
        val aiStatus = label("", 11.5f, TEXT_DIM).apply {
            setPadding(0, dp(10), 0, 0)
            visibility = View.GONE
        }
        add(aiCard, aiKeySecret.root, 10)
        add(aiCard, aiModelInput, 8)
        add(aiCard, aiBaseInput, 8)
        add(aiCard, aiStatus)
        val aiVerifyChip = chip("Verify")
        val aiModelsChip = chip("Models")
        val aiClear = action("Clear", false)
        val aiSave = action("Save", true)
        addRow(aiCard, 12, aiVerifyChip, aiModelsChip)
        addRow(aiCard, 10, aiClear, aiSave)
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
                setState(aiCard, if (saved) "Saved" else "Not set", saved)
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
            setState(aiCard, "Not set", false)
        }
        aiSave.setOnClickListener {
            Settings.saveAi(
                context, selectedProvider,
                aiKeyInput.text.toString(), aiModelInput.text.toString(), aiBaseInput.text.toString(),
            )
            val on = Settings.aiApiKey(selectedProvider) != null
            aiStatus.visibility = View.VISIBLE
            aiStatus.text = if (on) "Saved - the model reorders your rows." else "Cleared - rows use the local score."
            setState(aiCard, if (on) "Saved" else "Not set", on)
        }


        val built = AlertDialog.Builder(context, android.R.style.Theme_Material_Dialog)
            .setView(sheet)
            .create()
        dialog = built
        built.setOnDismissListener { providerProbeJob?.cancel() }
        close.setOnClickListener { built.dismiss() }
        built.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        built.window?.setDimAmount(0.6f)
        built.show()
        fitHeight()

        Thread {
            val on = SimklHistory.refresh()
            forYouCard.root.post { setState(forYouCard, if (on) "On" else "Off", on) }
        }.start()
    }

    private fun openLink(context: Context, url: String) {
        runCatching {
            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        }.onFailure {
            Toast.makeText(context, "No browser found", Toast.LENGTH_SHORT).show()
        }
    }

    private fun dp(context: Context, v: Number): Int =
        (v.toFloat() * context.resources.displayMetrics.density).roundToInt()
}
