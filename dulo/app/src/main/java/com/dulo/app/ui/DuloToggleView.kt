package com.dulo.app.ui

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.view.View

/**
 * Schalter im Stil der Systemkacheln: dunkle Kachel mit abgerundeten Ecken, darin eine
 * Pillenspur mit weißem Knopf und darunter die Beschriftung "Off" bzw. "On".
 * Ein Tippen schiebt den Knopf animiert auf die andere Seite und meldet den neuen Zustand.
 */
class DuloToggleView(context: Context) : View(context) {

    var isOn: Boolean = false
        private set

    /** Wird nach jedem Umschalten mit dem neuen Zustand aufgerufen */
    var onSwitched: ((Boolean) -> Unit)? = null

    /** Überschrift der Kachel, etwa "Auto". Ohne Angabe bleibt die Kachel unbeschriftet. */
    var title: String? = null
        set(value) {
            field = value
            invalidate()
        }

    private val density = resources.displayMetrics.density
    private val tilePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(28, 28, 30) }
    private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val knobPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = 11f * density
        isFakeBoldText = true
    }
    private val titlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(160, 160, 165)
        textSize = 10f * density
        isFakeBoldText = true
    }

    // 0 = aus, 1 = an; dazwischen liegt die laufende Animation
    private var progress = 0f
    private var animator: ValueAnimator? = null

    private val tileRadius = 16f * density
    private val trackWidth = 44f * density
    private val trackHeight = 22f * density
    private val knobRadius = 8f * density

    init {
        isClickable = true
        setOnClickListener { toggle() }
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        // Genauso groß wie die Blase daneben
        val side = (SIDE_DP * density).toInt()
        setMeasuredDimension(side, side)
    }

    fun toggle() {
        setOn(!isOn, animate = true)
        onSwitched?.invoke(isOn)
    }

    /** Zustand setzen; bei animate=false springt der Knopf ohne Bewegung an seinen Platz */
    fun setOn(on: Boolean, animate: Boolean) {
        isOn = on
        val target = if (on) 1f else 0f
        animator?.cancel()
        if (!animate) {
            progress = target
            invalidate()
            return
        }
        animator = ValueAnimator.ofFloat(progress, target).apply {
            duration = 180
            addUpdateListener { running ->
                progress = running.animatedValue as Float
                invalidate()
            }
            start()
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()

        // Kachel
        canvas.drawRoundRect(RectF(0f, 0f, w, h), tileRadius, tileRadius, tilePaint)

        // Überschrift, falls die Kachel eine trägt
        val heading = title
        if (heading != null) {
            val headingWidth = titlePaint.measureText(heading)
            canvas.drawText(heading, (w - headingWidth) / 2f, 13f * density, titlePaint)
        }

        // Spur: grau im Aus-Zustand, grün im An-Zustand, dazwischen weich überblendet
        val trackLeft = (w - trackWidth) / 2f
        val trackTop = if (heading != null) h * 0.36f else h * 0.28f
        trackPaint.color = blend(Color.rgb(99, 99, 102), Color.rgb(0, 230, 118), progress)
        canvas.drawRoundRect(
            RectF(trackLeft, trackTop, trackLeft + trackWidth, trackTop + trackHeight),
            trackHeight / 2f,
            trackHeight / 2f,
            trackPaint
        )

        // Knopf wandert von links nach rechts
        val inset = (trackHeight / 2f) - knobRadius + 3f * density
        val knobLeftX = trackLeft + inset + knobRadius
        val knobRightX = trackLeft + trackWidth - inset - knobRadius
        val knobX = knobLeftX + (knobRightX - knobLeftX) * progress
        canvas.drawCircle(knobX, trackTop + trackHeight / 2f, knobRadius, knobPaint)

        // Beschriftung
        val label = if (isOn) "On" else "Off"
        val labelWidth = labelPaint.measureText(label)
        canvas.drawText(label, (w - labelWidth) / 2f, h - 9f * density, labelPaint)
    }

    private companion object {
        // Kantenlänge in dp, passend zur Blase (FloatingBubbleService.BUBBLE_SIZE_DP)
        const val SIDE_DP = 64f
    }

    /** Zwei Farben anteilig mischen (0 = erste Farbe, 1 = zweite Farbe) */
    private fun blend(from: Int, to: Int, ratio: Float): Int {
        val r = ratio.coerceIn(0f, 1f)
        val red = (Color.red(from) + (Color.red(to) - Color.red(from)) * r).toInt()
        val green = (Color.green(from) + (Color.green(to) - Color.green(from)) * r).toInt()
        val blue = (Color.blue(from) + (Color.blue(to) - Color.blue(from)) * r).toInt()
        return Color.rgb(red, green, blue)
    }
}
