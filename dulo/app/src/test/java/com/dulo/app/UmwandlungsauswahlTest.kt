package com.dulo.app

import com.dulo.app.core.UltraRobustClassifier
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * Prüft das Auffinden der Umwandlungsauswahl.
 *
 * Die Maße stammen aus einem echten Bildschirmfoto von Duolingo (1080x2400): Das Brett beginnt
 * bei y=848 mit 135 Bildpunkten je Feld, der Bauer kommt auf Spalte 6 der obersten Reihe an, und
 * die vier Symbole liegen bei x = 572, 704, 835 und 969 auf Höhe y ≈ 1212 - also gut zwei Felder
 * unter dem Umwandlungsfeld und im Abstand von je einem Feld.
 *
 * Bemerkenswert daran: Die Tafel ist nicht über dem Umwandlungsfeld zentriert (das läge bei
 * x = 877), sondern nach links gerückt, damit sie nicht über den Bildschirmrand hinausragt.
 * Genau deshalb wird sie gesucht und nicht berechnet.
 */
class UmwandlungsauswahlTest {

    private val screenWidth = 1080
    private val screenHeight = 2400
    private val boardTop = 848
    private val square = 135

    /** Symbolmitten und Höhe wie im echten Bildschirmfoto */
    private val symbolMitten = listOf(572, 704, 835, 969)
    private val symbolY = 1212
    private val symbolBreite = 80
    private val symbolHoehe = 95

    /**
     * Baut eine Helligkeitsfunktion: dunkles Brett, darauf die hellen Symbole der Auswahl.
     * @param mitten Mittelpunkte der Symbole; leer = keine Auswahl auf dem Bildschirm
     */
    private fun bildschirm(
        mitten: List<Int> = symbolMitten,
        zeile: Int = symbolY,
        breite: Int = symbolBreite
    ): (Int, Int) -> Float = { x, y ->
        val trifft = mitten.any { abs(x - it) <= breite / 2 } &&
            abs(y - zeile) <= symbolHoehe / 2
        if (trifft) 220f else 40f
    }

    @Test
    fun testDameWirdGefunden() {
        val treffer = UltraRobustClassifier.findPromotionChoice(
            luminance = bildschirm(),
            screenWidth = screenWidth,
            screenHeight = screenHeight,
            boardTop = boardTop,
            squareSize = square.toFloat(),
            promoCell = 6
        )
        assertNotNull("Die Auswahl muss gefunden werden", treffer)
        val (x, y) = treffer!!
        assertEquals("Die Dame ist das linke Symbol", 572, x)
        assertTrue("Der Punkt muss im Symbol liegen, war $y", abs(y - symbolY) <= symbolHoehe / 2)
    }

    @Test
    fun testOhneAuswahlWirdNichtGetippt() {
        // Blind auf eine vermutete Stelle zu tippen wäre schlimmer als ein unvollendeter Zug -
        // es könnte einen ganz anderen Zug auslösen.
        val treffer = UltraRobustClassifier.findPromotionChoice(
            luminance = bildschirm(mitten = emptyList()),
            screenWidth = screenWidth,
            screenHeight = screenHeight,
            boardTop = boardTop,
            squareSize = square.toFloat(),
            promoCell = 6
        )
        assertNull(treffer)
    }

    @Test
    fun testEinzelneFigurAufDemBrettIstKeineAuswahl() {
        // Ein normales Brett hat helle Figuren - aber nie vier nebeneinander im Feldabstand
        val treffer = UltraRobustClassifier.findPromotionChoice(
            luminance = bildschirm(mitten = listOf(572)),
            screenWidth = screenWidth,
            screenHeight = screenHeight,
            boardTop = boardTop,
            squareSize = square.toFloat(),
            promoCell = 6
        )
        assertNull(treffer)
    }

    @Test
    fun testFigurenreiheDesBrettsWirdNichtVerwechselt() {
        // Acht Figuren nebeneinander sind eine Grundreihe, keine Auswahl: Die Symbole der Tafel
        // sind schmaler als ein Feld, eine volle Reihe füllt die Felder dagegen fast aus.
        val vollreihe = (0..7).map { (it * square) + square / 2 }
        val treffer = UltraRobustClassifier.findPromotionChoice(
            luminance = bildschirm(mitten = vollreihe, breite = (square * 0.98f).toInt()),
            screenWidth = screenWidth,
            screenHeight = screenHeight,
            boardTop = boardTop,
            squareSize = square.toFloat(),
            promoCell = 6
        )
        assertNull(treffer)
    }

    @Test
    fun testAuswahlBeiUmwandlungAmUnterenRand() {
        // Spielt man Schwarz, wandelt man unten um - die Tafel liegt dann darüber
        val untenY = boardTop + 8 * square - square / 2
        val tafelY = untenY - (2.2f * square).toInt()
        val treffer = UltraRobustClassifier.findPromotionChoice(
            luminance = bildschirm(zeile = tafelY),
            screenWidth = screenWidth,
            screenHeight = screenHeight,
            boardTop = boardTop,
            squareSize = square.toFloat(),
            promoCell = 7 * 8 + 6
        )
        assertNotNull("Auch nach oben muss gesucht werden", treffer)
        assertEquals(572, treffer!!.first)
    }

    @Test
    fun testUnbrauchbareEingabenLiefernNull() {
        assertNull(
            UltraRobustClassifier.findPromotionChoice(
                luminance = bildschirm(),
                screenWidth = screenWidth,
                screenHeight = screenHeight,
                boardTop = boardTop,
            squareSize = square.toFloat(),
                promoCell = 64
            )
        )
        // Ein zu kleines Brett ist keine brauchbare Grundlage
        assertNull(
            UltraRobustClassifier.findPromotionChoice(
                luminance = bildschirm(),
                screenWidth = screenWidth,
                screenHeight = screenHeight,
                boardTop = boardTop,
                squareSize = 5f,
                promoCell = 6
            )
        )
    }
}
