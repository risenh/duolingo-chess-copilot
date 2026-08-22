package com.dulo.app.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.os.Build
import android.util.Log
import android.view.accessibility.AccessibilityEvent

/**
 * Führt den empfohlenen Zug selbst aus, indem er auf Start- und Zielfeld tippt.
 *
 * Warum ein Bedienungshilfen-Dienst: Eine gewöhnliche App darf keine Berührungen in eine fremde
 * App schicken - das lässt Android aus gutem Grund nicht zu. Der einzige vorgesehene Weg ist
 * [AccessibilityService.dispatchGesture]. Dafür muss der Nutzer DuLo einmalig in den
 * Systemeinstellungen unter "Bedienungshilfen" freigeben; ohne diese Freigabe bleibt der
 * Auto-Zug wirkungslos und der Dienst meldet das.
 *
 * Der Dienst hört bewusst auf keine Ereignisse und liest keine Bildschirminhalte aus; er wird
 * ausschließlich gebraucht, um zwei Tippgesten abzuschicken.
 */
class DuloAutoMoveService : AccessibilityService() {

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        Log.i(TAG, "Auto-Zug-Dienst verbunden")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // Es werden keine Ereignisse ausgewertet
    }

    override fun onInterrupt() {
        // Nichts zu unterbrechen: der Dienst hält keinen Zustand
    }

    override fun onUnbind(intent: android.content.Intent?): Boolean {
        instance = null
        Log.i(TAG, "Auto-Zug-Dienst getrennt")
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        instance = null
        super.onDestroy()
    }

    /**
     * Schickt eine einzelne Tippgeste an die Stelle (x, y) des Bildschirms.
     *
     * @param onFinished wird aufgerufen, sobald die Geste durch ist (true) oder abgebrochen wurde (false)
     */
    private fun tap(x: Float, y: Float, onFinished: (Boolean) -> Unit) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) {
            // dispatchGesture gibt es erst ab Android 7
            onFinished(false)
            return
        }
        val path = Path().apply { moveTo(x, y) }
        val stroke = GestureDescription.StrokeDescription(path, 0L, TAP_DURATION_MS)
        val gesture = GestureDescription.Builder().addStroke(stroke).build()

        val ok = dispatchGesture(
            gesture,
            object : GestureResultCallback() {
                override fun onCompleted(description: GestureDescription?) {
                    onFinished(true)
                }

                override fun onCancelled(description: GestureDescription?) {
                    Log.w(TAG, "Tippgeste bei ($x, $y) wurde abgebrochen")
                    onFinished(false)
                }
            },
            null
        )
        if (!ok) {
            Log.w(TAG, "Tippgeste konnte nicht abgeschickt werden")
            onFinished(false)
        }
    }

    companion object {
        private const val TAG = "DuloAutoMoveService"

        /** Dauer einer Berührung; kurz genug für ein Tippen, lang genug zum Erkennen */
        private const val TAP_DURATION_MS = 60L

        /**
         * Laufende Instanz, sobald der Nutzer den Dienst in den Bedienungshilfen freigegeben hat.
         * Ist sie null, ist der Auto-Zug nicht verfügbar.
         */
        @Volatile
        var instance: DuloAutoMoveService? = null
            private set

        /** Ist der Auto-Zug einsatzbereit? */
        val isAvailable: Boolean
            get() = instance != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.N

        /**
         * Tippt nacheinander auf die übergebenen Punkte.
         *
         * @param points   Bildschirmpunkte in der Reihenfolge, in der getippt werden soll
         * @param delayMs  Pause nach jedem Tippen
         * @param onDone   true, wenn alle Gesten durchkamen
         */
        fun tapSequence(
            points: List<Pair<Float, Float>>,
            delayMs: Long,
            onDone: (Boolean) -> Unit
        ) {
            val service = instance
            if (service == null || points.isEmpty()) {
                onDone(false)
                return
            }

            // Rekursiv statt in einer Schleife: jede Geste muss abgeschlossen sein, bevor die
            // nächste losgeht - sonst verwirft Android die zweite.
            fun step(index: Int) {
                val (x, y) = points[index]
                service.tap(x, y) { ok ->
                    if (!ok) {
                        onDone(false)
                        return@tap
                    }
                    // Nach der letzten Berührung gibt es nichts mehr abzuwarten - die Pause gehört
                    // zwischen zwei Berührungen, nicht ans Ende.
                    if (index + 1 >= points.size) {
                        onDone(true)
                        return@tap
                    }
                    service.mainHandler.postDelayed({ step(index + 1) }, delayMs)
                }
            }
            step(0)
        }
    }

    private val mainHandler = android.os.Handler(android.os.Looper.getMainLooper())
}
