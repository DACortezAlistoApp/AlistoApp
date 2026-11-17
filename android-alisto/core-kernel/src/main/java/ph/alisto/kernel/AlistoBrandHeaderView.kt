package ph.alisto.kernel

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.*
import android.view.View
import android.view.animation.LinearInterpolator

class AlistoBrandHeaderView(
    context: Context,
    private val appTitle: String,
    private val appSubtitle: String
) : View(context) {

    private var pulsePhase = 0f
    private val density = resources.displayMetrics.density

    private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#0F172A")
        style = Paint.Style.FILL
    }
    private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#1E293B")
        style = Paint.Style.STROKE
        strokeWidth = 2f * density
    }
    private val pinStrokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#F1F5F9")
        style = Paint.Style.STROKE
        strokeWidth = 5.5f * density
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#34D399")
        style = Paint.Style.STROKE
        strokeWidth = 2f * density
    }
    private val titlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = 16f * density
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
    }
    private val amberTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#FBBF24")
        textSize = 11f * density
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
    }
    private val subPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#38BDF8")
        textSize = 11.5f * density
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
    }
    private val shieldOuterPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#00F2FE")
        style = Paint.Style.STROKE
        strokeWidth = 2f * density
    }
    private val shieldFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#0284C7")
        style = Paint.Style.FILL
    }
    private val amberCorePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#FBBF24")
        style = Paint.Style.FILL
    }

    private val animator = ValueAnimator.ofFloat(0f, 1f).apply {
        duration = 2800L
        repeatCount = ValueAnimator.INFINITE
        interpolator = LinearInterpolator()
        addUpdateListener {
            pulsePhase = it.animatedValue as Float
            invalidate()
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        animator.start()
    }

    override fun onDetachedFromWindow() {
        animator.cancel()
        super.onDetachedFromWindow()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec)
        val h = (96 * density).toInt()
        setMeasuredDimension(w, h)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        val rect = RectF(2f, 2f, w - 2f, h - 2f)
        canvas.drawRoundRect(rect, 12f * density, 12f * density, bgPaint)
        canvas.drawRoundRect(rect, 12f * density, 12f * density, borderPaint)

        // 1. LEFT: ALISTO CONCEPT LOGO (PROJECT NEXUS) — Inverted 'A' Pin + Emerald Ripple Rings
        val cx = 44f * density
        val cy = 44f * density
        val maxR = 32f * density

        val r1 = 6f * density + pulsePhase * maxR
        ringPaint.alpha = ((1f - pulsePhase) * 230).toInt().coerceIn(0, 255)
        canvas.drawCircle(cx, cy, r1, ringPaint)

        val phase2 = (pulsePhase + 0.5f) % 1f
        val r2 = 6f * density + phase2 * maxR
        ringPaint.alpha = ((1f - phase2) * 230).toInt().coerceIn(0, 255)
        canvas.drawCircle(cx, cy, r2, ringPaint)

        val pinPath = Path().apply {
            moveTo(cx - 18f * density, cy - 18f * density)
            lineTo(cx + 18f * density, cy - 18f * density)
            lineTo(cx, cy + 20f * density)
            close()
            moveTo(cx - 9f * density, cy - 4f * density)
            lineTo(cx + 9f * density, cy - 4f * density)
        }
        canvas.drawPath(pinPath, pinStrokePaint)

        // Amber "ALISTO" Logotype under the Pin
        val logoLabel = "ALISTO"
        val lw = amberTextPaint.measureText(logoLabel)
        canvas.drawText(logoLabel, cx - lw / 2f, h - 10f * density, amberTextPaint)

        // 2. CENTER: APP TITLE & ALISTO NETWORK PH CORPORATE ATTRIBUTION
        val textLeft = 92f * density
        canvas.drawText(appTitle, textLeft, 34f * density, titlePaint)
        canvas.drawText(appSubtitle, textLeft, 54f * density, subPaint)
        canvas.drawText("Alisto Network PH • Architect: D. A. Cortez", textLeft, 74f * density, amberTextPaint)

        // 3. RIGHT: ALISTO NETWORK PH SHIELD EMBLEM
        val sx = w - 34f * density
        val sy = h / 2f
        val shieldPath = Path().apply {
            moveTo(sx, sy - 22f * density)
            lineTo(sx + 18f * density, sy - 12f * density)
            lineTo(sx + 14f * density, sy + 14f * density)
            lineTo(sx, sy + 24f * density)
            lineTo(sx - 14f * density, sy + 14f * density)
            lineTo(sx - 18f * density, sy - 12f * density)
            close()
        }
        canvas.drawPath(shieldPath, shieldFillPaint)
        canvas.drawPath(shieldPath, shieldOuterPaint)
        canvas.drawCircle(sx, sy + 2f * density, 5.5f * density, amberCorePaint)
    }
}
