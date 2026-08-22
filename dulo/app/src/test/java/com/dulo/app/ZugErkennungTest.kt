package com.dulo.app

import com.dulo.app.core.UltraRobustClassifier
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Tests für die neue Erkennungsmethode: Der gespielte Zug wird direkt aus den zwei veränderten
 * Feldern abgelesen, statt das ganze Brett erneut zu erkennen.
 *
 * Streuung je Feld: ein leeres Feld ist eine gleichmäßige Fläche (kleiner Wert), eine Figur bringt
 * Kanten und damit Streuung mit sich (großer Wert).
 */
class ZugErkennungTest {

    private val leer = 4f
    private val figur = 40f

    /** Alle Felder leer, außer den angegebenen */
    private fun brett(besetzt: Map<Int, Float>): Pair<FloatArray, FloatArray> {
        val means = FloatArray(64) { 120f }
        val stds = FloatArray(64) { leer }
        for ((feld, streuung) in besetzt) {
            stds[feld] = streuung
            means[feld] = 90f
        }
        return means to stds
    }

    @Test
    fun testGewoehnlicherZugWirdAbgelesen() {
        val (vorherM, vorherS) = brett(mapOf(52 to figur))
        val (nachherM, nachherS) = brett(mapOf(36 to figur))

        val zug = UltraRobustClassifier.detectMove(vorherM, vorherS, nachherM, nachherS)
        assertEquals(UltraRobustClassifier.DetectedMove(52, 36), zug)
    }

    @Test
    fun testSchlagfallWirdAbgelesen() {
        // Auf Feld 36 stand bereits eine Figur; sie wird geschlagen, das Feld bleibt besetzt,
        // sein Inhalt ändert sich aber deutlich
        val (vorherM, vorherS) = brett(mapOf(52 to figur, 36 to figur))
        val (nachherM, nachherS) = brett(mapOf(36 to figur))
        nachherM[36] = 200f // andere Figurenfarbe auf dem Zielfeld

        val zug = UltraRobustClassifier.detectMove(vorherM, vorherS, nachherM, nachherS)
        assertEquals(UltraRobustClassifier.DetectedMove(52, 36), zug)
    }

    @Test
    fun testUnveraendertesBrettErgibtKeinenZug() {
        val (means, stds) = brett(mapOf(52 to figur, 12 to figur))
        assertNull(UltraRobustClassifier.detectMove(means, stds, means.copyOf(), stds.copyOf()))
    }

    @Test
    fun testRochadeIstNichtEindeutigUndFaelltDurch() {
        // Vier veränderte Felder: König und Turm ziehen gleichzeitig
        val (vorherM, vorherS) = brett(mapOf(60 to figur, 63 to figur))
        val (nachherM, nachherS) = brett(mapOf(62 to figur, 61 to figur))

        // Kein eindeutiges Muster: die vollständige Erkennung muss übernehmen
        assertNull(UltraRobustClassifier.detectMove(vorherM, vorherS, nachherM, nachherS))
    }

    @Test
    fun testHervorhebungAufLeeremFeldStoertNicht() {
        // Duolingo hebt Start- und Zielfeld des letzten Zuges farbig hervor und nimmt die
        // vorherige Hervorhebung wieder weg. Ein leeres Feld ändert dabei seine Helligkeit,
        // ohne dass dort etwas stünde.
        //
        // Früher brach die Erkennung genau daran ab - und weil nach jedem Zug eine solche
        // Hervorhebung auf dem Brett liegt, fiel praktisch jeder Zug durch. Entscheidend ist
        // allein die Streuung: eine Farbfläche bringt keine Kanten mit, eine Figur schon.
        val (vorherM, vorherS) = brett(mapOf(52 to figur))
        val (nachherM, nachherS) = brett(mapOf(36 to figur))
        nachherM[20] = 240f

        assertEquals(
            UltraRobustClassifier.DetectedMove(52, 36),
            UltraRobustClassifier.detectMove(vorherM, vorherS, nachherM, nachherS)
        )
    }

    @Test
    fun testZugWirdAufDasBrettAngewendet() {
        val brett = Array(8) { CharArray(8) { '.' } }
        brett[6][4] = 'P'

        val nachher = UltraRobustClassifier.applyMoveToScreenBoard(
            brett,
            UltraRobustClassifier.DetectedMove(fromCell = 6 * 8 + 4, toCell = 4 * 8 + 4)
        )
        assertEquals('.', nachher[6][4])
        assertEquals('P', nachher[4][4])
        // Das ursprüngliche Brett bleibt unangetastet
        assertEquals('P', brett[6][4])
    }

    @Test
    fun testSchlagfallUeberschreibtDieGeschlageneFigur() {
        val brett = Array(8) { CharArray(8) { '.' } }
        brett[4][4] = 'P'
        brett[3][3] = 'p'

        val nachher = UltraRobustClassifier.applyMoveToScreenBoard(
            brett,
            UltraRobustClassifier.DetectedMove(fromCell = 4 * 8 + 4, toCell = 3 * 8 + 3)
        )
        assertEquals('.', nachher[4][4])
        assertEquals('P', nachher[3][3])
    }
}
