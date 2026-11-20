package ph.alisto.kernel

import android.app.Activity
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView

object AlistoUiKit {
    const val BG_DEEP = "#070A12"
    const val BG_SURFACE = "#0D1424"
    const val BG_CARD = "#111827"
    const val BG_INPUT = "#0B1120"
    const val BORDER = "#1E293B"
    const val BORDER_LIGHT = "#334155"

    const val CYAN = "#00F2FE"
    const val AMBER = "#FBBF24"
    const val EMERALD = "#10B981"
    const val ROSE = "#F87171"
    const val TEXT_PRIMARY = "#F8FAFC"
    const val TEXT_MUTED = "#94A3B8"
    const val TEXT_DIM = "#64748B"

    fun Context.dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    fun applyDarkSystemBars(activity: Activity) {
        @Suppress("DEPRECATION")
        activity.window.statusBarColor = Color.parseColor(BG_DEEP)
        @Suppress("DEPRECATION")
        activity.window.navigationBarColor = Color.parseColor(BG_SURFACE)
    }

    fun roundedBg(
        context: Context,
        fillHex: String,
        radiusDp: Int = 14,
        strokeHex: String? = null,
        strokeWidthDp: Int = 1
    ): GradientDrawable = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        setColor(Color.parseColor(fillHex))
        cornerRadius = context.dp(radiusDp).toFloat()
        if (strokeHex != null) setStroke(context.dp(strokeWidthDp), Color.parseColor(strokeHex))
    }

    fun topSheetBg(
        context: Context,
        fillHex: String = BG_SURFACE,
        radiusDp: Int = 24,
        strokeHex: String = BORDER
    ): GradientDrawable {
        val r = context.dp(radiusDp).toFloat()
        return GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            setColor(Color.parseColor(fillHex))
            cornerRadii = floatArrayOf(r, r, r, r, 0f, 0f, 0f, 0f)
            setStroke(context.dp(1), Color.parseColor(strokeHex))
        }
    }

    fun dragHandle(context: Context): View = View(context).apply {
        background = roundedBg(context, "#475569", radiusDp = 4)
        layoutParams = LinearLayout.LayoutParams(context.dp(42), context.dp(4)).apply {
            gravity = Gravity.CENTER_HORIZONTAL
            topMargin = context.dp(10)
            bottomMargin = context.dp(10)
        }
    }

    fun pillButton(
        context: Context,
        label: String,
        fillHex: String,
        textHex: String = BG_DEEP,
        strokeHex: String? = null,
        heightDp: Int = 48,
        textSizeSp: Float = 14f,
        onClick: () -> Unit
    ): Button = Button(context).apply {
        text = label
        isAllCaps = false
        textSize = textSizeSp
        setTypeface(null, Typeface.BOLD)
        setTextColor(Color.parseColor(textHex))
        background = roundedBg(context, fillHex, radiusDp = 24, strokeHex = strokeHex)
        minHeight = context.dp(heightDp)
        minimumHeight = context.dp(heightDp)
        setPadding(context.dp(18), context.dp(10), context.dp(18), context.dp(10))
        elevation = context.dp(2).toFloat()
        setOnClickListener { onClick() }
    }

    fun chipButton(
        context: Context,
        label: String,
        active: Boolean,
        activeFillHex: String = CYAN,
        activeTextHex: String = BG_DEEP,
        onClick: () -> Unit
    ): TextView = TextView(context).apply {
        text = label
        gravity = Gravity.CENTER
        textSize = 12.5f
        setTypeface(null, if (active) Typeface.BOLD else Typeface.NORMAL)
        setTextColor(Color.parseColor(if (active) activeTextHex else TEXT_MUTED))
        background = roundedBg(
            context,
            fillHex = if (active) activeFillHex else BG_CARD,
            radiusDp = 20,
            strokeHex = if (active) activeFillHex else BORDER
        )
        setPadding(context.dp(14), context.dp(9), context.dp(14), context.dp(9))
        setOnClickListener { onClick() }
    }

    fun badgePill(
        context: Context,
        label: String,
        fillHex: String = "#1E293B",
        textHex: String = CYAN
    ): TextView = TextView(context).apply {
        text = label
        textSize = 11f
        setTypeface(null, Typeface.BOLD)
        setTextColor(Color.parseColor(textHex))
        background = roundedBg(context, fillHex, radiusDp = 99)
        setPadding(context.dp(10), context.dp(4), context.dp(10), context.dp(4))
    }

    fun inputField(
        context: Context,
        hintText: String,
        inputTypeFlags: Int = InputType.TYPE_CLASS_TEXT,
        initialValue: String = ""
    ): EditText = EditText(context).apply {
        hint = hintText
        if (initialValue.isNotEmpty()) setText(initialValue)
        inputType = inputTypeFlags
        textSize = 14f
        setHintTextColor(Color.parseColor(TEXT_DIM))
        setTextColor(Color.parseColor(TEXT_PRIMARY))
        background = roundedBg(context, BG_INPUT, radiusDp = 12, strokeHex = BORDER_LIGHT)
        minHeight = context.dp(46)
        setPadding(context.dp(14), context.dp(12), context.dp(14), context.dp(12))
    }

    fun labeledField(context: Context, label: String, field: View, bottomMarginDp: Int = 12): View =
        LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = context.dp(bottomMarginDp) }
            addView(TextView(context).apply {
                text = label
                textSize = 11.5f
                setTypeface(null, Typeface.BOLD)
                setTextColor(Color.parseColor(TEXT_MUTED))
                setPadding(context.dp(2), 0, 0, context.dp(5))
            })
            addView(field)
        }

    fun surfaceCard(context: Context, fillHex: String = BG_CARD, strokeHex: String = BORDER, padDp: Int = 16): LinearLayout =
        LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            background = roundedBg(context, fillHex, radiusDp = 16, strokeHex = strokeHex)
            setPadding(context.dp(padDp), context.dp(padDp), context.dp(padDp), context.dp(padDp))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = context.dp(12) }
        }

    fun authBrandHero(context: Context, title: String, subtitle: String, accentHex: String): View =
        LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(0, context.dp(12), 0, context.dp(20))

            val appIconRes = context.applicationInfo.icon
            if (appIconRes != 0) {
                addView(android.widget.ImageView(context).apply {
                    setImageResource(appIconRes)
                    scaleType = android.widget.ImageView.ScaleType.FIT_CENTER
                    background = roundedBg(context, BG_CARD, radiusDp = 20, strokeHex = accentHex)
                    clipToOutline = true
                    setPadding(context.dp(4), context.dp(4), context.dp(4), context.dp(4))
                    layoutParams = LinearLayout.LayoutParams(context.dp(74), context.dp(74)).apply { bottomMargin = context.dp(12) }
                })
            } else {
                addView(TextView(context).apply {
                    text = "A"
                    gravity = Gravity.CENTER
                    textSize = 26f
                    setTypeface(null, Typeface.BOLD)
                    setTextColor(Color.parseColor(BG_DEEP))
                    background = roundedBg(context, accentHex, radiusDp = 18)
                    layoutParams = LinearLayout.LayoutParams(context.dp(56), context.dp(56)).apply { bottomMargin = context.dp(12) }
                })
            }
            addView(TextView(context).apply {
                text = title
                textSize = 22f
                setTypeface(null, Typeface.BOLD)
                setTextColor(Color.parseColor(TEXT_PRIMARY))
                gravity = Gravity.CENTER
            })
            addView(TextView(context).apply {
                text = subtitle
                textSize = 13f
                setTextColor(Color.parseColor(TEXT_MUTED))
                gravity = Gravity.CENTER
                setPadding(context.dp(12), context.dp(4), context.dp(12), 0)
            })
        }

    fun bottomNavBar(
        context: Context,
        tabs: List<Triple<String, String, String>>,
        activeKey: String,
        accentHex: String,
        onSelect: (String) -> Unit
    ): LinearLayout = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        setBackgroundColor(Color.parseColor(BG_SURFACE))
        elevation = context.dp(8).toFloat()
        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, context.dp(62))

        tabs.forEach { (key, glyph, label) ->
            val selected = key == activeKey
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f)
                setOnClickListener { if (!selected) onSelect(key) }

                addView(View(context).apply {
                    setBackgroundColor(if (selected) Color.parseColor(accentHex) else Color.TRANSPARENT)
                    layoutParams = LinearLayout.LayoutParams(context.dp(36), context.dp(3)).apply { bottomMargin = context.dp(4) }
                })
                addView(TextView(context).apply {
                    text = glyph
                    textSize = 16f
                    gravity = Gravity.CENTER
                    setTextColor(Color.parseColor(if (selected) accentHex else TEXT_DIM))
                })
                addView(TextView(context).apply {
                    text = label
                    textSize = 11f
                    gravity = Gravity.CENTER
                    setTypeface(null, if (selected) Typeface.BOLD else Typeface.NORMAL)
                    setTextColor(Color.parseColor(if (selected) TEXT_PRIMARY else TEXT_DIM))
                })
            })
        }
    }

    fun statusColors(status: String): Pair<String, String> = when (status) {
        "COMPLETED", "ACTIVE", "PAID" -> "#064E3B" to "#34D399"
        "ON_THE_WAY", "ARRIVED", "ONGOING", "IN_PROGRESS", "ACCEPTED" -> "#0C4A6E" to "#38BDF8"
        "CANCELLED", "EXPIRED", "SUSPENDED", "REJECTED" -> "#450A0A" to "#FCA5A5"
        else -> "#451A03" to "#FBBF24"
    }
}
