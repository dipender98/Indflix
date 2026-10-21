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
import android.text.method.PasswordTransformationMethod
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.AutoCompleteTextView
import android.widget.Button
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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
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

        fun chip(value: String): TextView = label(value, 12f, TEXT_DIM, medium = true).apply {
            gravity = Gravity.CENTER
            setPadding(dp(16), 0, dp(16), 0)
            background = ripple(rounded(Color.TRANSPARENT, 999, 1))
            isClickable = true
            isFocusable = true
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, control,
            ).apply { rightMargin = gap }
        }

        fun markChip(target: TextView, on: Boolean) {
            target.setTextColor(if (on) Color.WHITE else TEXT_DIM)
            target.background = ripple(
                rounded(if (on) ACCENT else Color.TRANSPARENT, 999, if (on) 0 else 1),
            )
        }

        fun action(value: String, primary: Boolean): Button = Button(context).apply {
            text = value
            isAllCaps = false
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            setTextColor(if (primary) Color.WHITE else TEXT)
            background = ripple(
                rounded(if (primary) ACCENT else Color.TRANSPARENT, 12, if (primary) 0 else 1),
            )
            minWidth = 0
            minHeight = 0
            stateListAnimator = null
            setPadding(dp(18), 0, dp(18), 0)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, control,
            ).apply { leftMargin = gap }
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
            views.lastOrNull()?.let { last ->
                (last.layoutParams as? LinearLayout.LayoutParams)?.let {
                    it.rightMargin = 0
                    last.layoutParams = it
                }
            }
            val row = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.END or Gravity.CENTER_VERTICAL
                views.forEach { addView(it) }
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
        heading.addView(label("Provider settings", 20f, TEXT, medium = true))
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
            refreshNow.isEnabled = false
            refreshNow.text = "Rebuilding..."
            ForYouEngine.invalidate(context)
            CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
                val total = ForYouEngine.row("movie", 1).size + ForYouEngine.row("tv", 1).size
                refreshNow.post {
                    refreshNow.text = "Refresh now"
                    refreshNow.isEnabled = true
                    setState(forYouCard, if (total > 0) "On" else "Off", total > 0)
                }
            }
        }


        val allServers = ServerFarm.allServers
        var offCount = allServers.count { !Settings.serverEnabled(it.id) }
        val catalogueCard = card(
            "Catalogue",
            "Shuffle rows and turn off servers that do not work",
            if (offCount == 0) "All on" else "$offCount off",
            offCount < allServers.size,
        )
        val shuffleOn = Settings.shuffleRows()
        val shuffleOpt = option(
            "Shuffle all rows",
            if (shuffleOn) "Every row is randomized" else "Rows keep their normal order",
        )
        paintOption(shuffleOpt, shuffleOn)
        shuffleOpt.root.setOnClickListener {
            val on = !Settings.shuffleRows()
            Settings.saveShuffleRows(context, on)
            paintOption(shuffleOpt, on)
            shuffleOpt.summary.text = if (on) "Every row is randomized" else "Rows keep their normal order"
        }
        add(catalogueCard, shuffleOpt.root)
        add(catalogueCard, sectionLabel("Servers", 18))
        val serverList = optionList()
        allServers.forEach { spec ->
            val opt = option(spec.name, if (Settings.serverEnabled(spec.id)) "On" else "Off")
            paintOption(opt, Settings.serverEnabled(spec.id))
            opt.root.setOnClickListener {
                val on = !Settings.serverEnabled(spec.id)
                Settings.saveServerEnabled(context, spec.id, on)
                opt.summary.text = if (on) "On" else "Off"
                paintOption(opt, on)
                offCount = allServers.count { !Settings.serverEnabled(it.id) }
                setState(
                    catalogueCard,
                    if (offCount == 0) "All on" else "$offCount off",
                    offCount < allServers.size,
                )
            }
            serverList.addView(opt.root)
        }
        add(catalogueCard, serverList)


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


        val showboxSaved = Settings.showboxToken() != null
        val showboxCard = card(
            "Extra server sign-in",
            "Optional - one more server becomes available",
            if (showboxSaved) "Saved" else "Not set",
            showboxSaved,
        )
        val showboxStatus = label(
            if (showboxSaved) "Signed in - the extra server is in use." else "The extra server stays off.",
            11.5f, TEXT_DIM,
        ).apply { setPadding(0, dp(10), 0, 0) }
        val showboxSecret = secret(Settings.showboxToken().orEmpty(), "session value...")
        val showboxKeyInput = showboxSecret.input
        add(showboxCard, sectionLabel("FebBox", 14))
        add(showboxCard, showboxSecret.root)
        add(showboxCard, showboxStatus)
        val showboxSignIn = chip("Sign in")
        val showboxClear = action("Clear", false)
        val showboxSave = action("Save", true)
        addRow(showboxCard, 12, showboxSignIn)
        addRow(showboxCard, 10, showboxClear, showboxSave)
        showboxSignIn.setOnClickListener {
            openLink(context, Settings.SHOWBOX_LINK)
            showboxStatus.text = "Sign in in the browser, then paste the session value here."
        }
        showboxClear.setOnClickListener {
            Settings.clearShowbox(context)
            showboxKeyInput.setText("")
            showboxStatus.text = "The extra server stays off."
            setState(showboxCard, "Not set", false)
        }
        showboxSave.setOnClickListener {
            Settings.saveShowbox(context, showboxKeyInput.text.toString())
            val on = Settings.showboxToken() != null
            showboxStatus.text =
                if (on) "Signed in - the extra server is in use." else "The extra server stays off."
            setState(showboxCard, if (on) "Saved" else "Not set", on)
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
