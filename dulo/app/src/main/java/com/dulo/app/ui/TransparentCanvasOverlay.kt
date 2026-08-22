package com.dulo.app.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.RectF
import android.os.Build
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Durchsichtiges Overlay für Störungsmeldungen.
 *
 * Seit DuLo den Zug selbst ausführt, gibt es nichts mehr auf das Brett zu zeichnen - der Pfeil
 * gehörte zum Knopf "Bester Zug", den es nicht mehr gibt. Übrig bleibt eine ruhige Kachel in der
 * Bildschirmmitte, die weich ein- und wieder ausblendet.
 *
 * Das Fenster reicht Berührungen durch (FLAG_NOT_TOUCHABLE) und trägt FLAG_SECURE, erscheint also
 * nicht in der Bildschirmaufnahme und verfälscht die Erkennung nicht.
 */
class TransparentCanvasOverlay(private val context: Context) {

    private companion object {
        const val TAG = "DuLoOverlay"
        // Einheitlicher Text für alle Störungen: fehlgeschlagene Erkennung, abgewiesene Rahmen, Ausnahmen
        const val ERROR_TEXT = "Something went wrong :("

        // Störungsmeldung: einblenden, fünf Sekunden stehen lassen, ausblenden
        const val FADE_IN_MS = 220L
        const val STATUS_HOLD_MS = 5000L
        const val FADE_OUT_MS = 450L
    }

    private val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private var overlayView: StatusView? = null
    private var isShowing = false
    private val scope = CoroutineScope(Dispatchers.Main)
    private var errorJob: Job? = null

    private class StatusView(context: Context) : View(context) {
        /** Gesetzt, solange eine Störung angezeigt wird */
        var errorMessage: String? = null

        /** Deckkraft der Meldung zwischen 0 (unsichtbar) und 1 (voll sichtbar) */
        var statusAlpha: Float = 1f

        private val textBgPaint = Paint().apply {
            color = Color.argb(230, 20, 24, 30)
            style = Paint.Style.FILL
            isAntiAlias = true
        }

        private val textPaint = Paint().apply {
            color = Color.WHITE
            textSize = 34f
            isAntiAlias = true
            isFakeBoldText = true
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            if (errorMessage == null) return

            val alpha = statusAlpha.coerceIn(0f, 1f)
            if (alpha <= 0.01f) return

            val cx = width / 2f
            val cy = height / 2f
            val textWidth = textPaint.measureText(ERROR_TEXT)
            val pillW = (textWidth + 72f).coerceAtMost(width - 48f)
            val pillH = 108f

            val bgAlpha = textBgPaint.alpha
            val textAlpha = textPaint.alpha
            textBgPaint.alpha = (bgAlpha * alpha).toInt()
            textPaint.alpha = (textAlpha * alpha).toInt()
            canvas.drawRoundRect(
                RectF(cx - pillW / 2f, cy - pillH / 2f, cx + pillW / 2f, cy + pillH / 2f),
                28f, 28f, textBgPaint
            )
            canvas.drawText(ERROR_TEXT, cx - textWidth / 2f, cy + 12f, textPaint)
            textBgPaint.alpha = bgAlpha
            textPaint.alpha = textAlpha
        }
    }

    /**
     * Störung anzeigen: die Meldung blendet sich weich ein, steht fünf Sekunden und blendet sich
     * wieder aus. Auf dem Bildschirm steht immer derselbe kurze Satz; der übergebene Grund dient
     * nur dem Protokoll, damit die Anzeige ruhig bleibt.
     */
    fun showError(reason: String) {
        Log.i(TAG, "Overlay meldet Störung: $reason")
        if (overlayView == null) initOverlayView()
        val view = overlayView ?: return

        view.errorMessage = reason
        view.statusAlpha = 0f
        view.visibility = View.VISIBLE
        view.postInvalidate()

        errorJob?.cancel()
        errorJob = scope.launch {
            animateStatusAlpha(view, from = 0f, to = 1f, durationMs = FADE_IN_MS)
            delay(STATUS_HOLD_MS)
            animateStatusAlpha(view, from = 1f, to = 0f, durationMs = FADE_OUT_MS)
            view.errorMessage = null
            view.statusAlpha = 1f
            view.postInvalidate()
        }
    }

    /**
     * Alles wegnehmen und jede laufende Animation abbrechen.
     *
     * Beim Ausschalten und beim Beenden: eine Meldung, die noch in ihrer Haltezeit steht, darf
     * danach nicht weiterlaufen.
     */
    fun dismissAll() {
        errorJob?.cancel()
        errorJob = null
        overlayView?.apply {
            errorMessage = null
            statusAlpha = 1f
            postInvalidate()
        }
    }

    /** Deckkraft der Meldung schrittweise verändern (rund 60 Bilder je Sekunde) */
    private suspend fun animateStatusAlpha(view: StatusView, from: Float, to: Float, durationMs: Long) {
        val frameMs = 16L
        val steps = (durationMs / frameMs).toInt().coerceAtLeast(1)
        for (step in 0..steps) {
            view.statusAlpha = from + (to - from) * (step / steps.toFloat())
            view.postInvalidate()
            delay(frameMs)
        }
        view.statusAlpha = to
        view.postInvalidate()
    }

    private fun initOverlayView() {
        overlayView = StatusView(context)
        val layoutType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            layoutType,
            WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    // Nicht mit aufnehmen: die Meldung darf das erkannte Bild nicht verfälschen
                    WindowManager.LayoutParams.FLAG_SECURE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 0
            y = 0
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
        }
        windowManager.addView(overlayView, params)
        isShowing = true
    }

    /** Endgültig abräumen: Fenster entfernen und den eigenen Coroutine-Bereich beenden */
    fun release() {
        hide()
        scope.cancel()
    }

    fun hide() {
        errorJob?.cancel()
        errorJob = null
        overlayView?.let {
            if (isShowing) {
                try { windowManager.removeView(it) } catch (_: Exception) {}
                isShowing = false
            }
        }
        overlayView = null
    }
}
