package com.dulo.app

import com.dulo.app.core.UltraRobustClassifier
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests für die Erkennung der Perspektive, also der Frage, welche Figuren die eigenen sind.
 *
 * Fehlerbild: gelegentlich wurden die Figuren des Gegners statt der eigenen analysiert. Ursache war,
 * dass allein die Farbmehrheit der beiden untersten Bildschirmreihen entschied. Sobald der Gegner
 * dort eindrang oder die eigene Grundreihe leer war, kippte das Ergebnis und das FEN wurde gespiegelt.
 */
class PerspektivErkennungTest {

    /** Grundstellung aus Sicht von Weiß: Weiß unten (row 6/7), Schwarz oben (row 0/1) */
    private fun grundstellungWeissUnten() = arrayOf(
        charArrayOf('r', 'n', 'b', 'q', 'k', 'b', 'n', 'r'),
        charArrayOf('p', 'p', 'p', 'p', 'p', 'p', 'p', 'p'),
        charArrayOf('.', '.', '.', '.', '.', '.', '.', '.'),
        charArrayOf('.', '.', '.', '.', '.', '.', '.', '.'),
        charArrayOf('.', '.', '.', '.', '.', '.', '.', '.'),
        charArrayOf('.', '.', '.', '.', '.', '.', '.', '.'),
        charArrayOf('P', 'P', 'P', 'P', 'P', 'P', 'P', 'P'),
        charArrayOf('R', 'N', 'B', 'Q', 'K', 'B', 'N', 'R')
    )

    /** Dasselbe Brett um 180 Grad gedreht: der Spieler führt Schwarz und sitzt unten */
    private fun spiegeln(board: Array<CharArray>) =
        Array(8) { r -> CharArray(8) { c -> board[7 - r][7 - c] } }

    @Test
    fun testGrundstellungWeissUnten() {
        val verdict = UltraRobustClassifier.detectPerspective(grundstellungWeissUnten())
        assertTrue("Weiß steht unten, die Perspektive muss Weiß sein", verdict.isWhitePerspective)
        assertTrue("Alle Signale zeigen in dieselbe Richtung, die Confidence muss hoch sein", verdict.confidence > 0.9f)
    }

    @Test
    fun testGrundstellungSchwarzUnten() {
        val verdict = UltraRobustClassifier.detectPerspective(spiegeln(grundstellungWeissUnten()))
        assertFalse("Schwarz steht unten, die Perspektive muss Schwarz sein", verdict.isWhitePerspective)
        assertTrue(verdict.confidence > 0.9f)
    }

    @Test
    fun testEingedrungeneGegnerfigurenKippenDiePerspektiveNichtMehr() {
        // Endspiel aus Sicht von Weiß: die eigene Grundreihe ist geräumt, dort stehen ein
        // schwarzer Turm und ein schwarzer Springer, unten also 3 schwarze gegen 2 weiße Figuren.
        // Genau hier meldete die alte Regel (Mehrheit der beiden untersten Reihen) fälschlich Schwarz.
        val board = arrayOf(
            charArrayOf('.', '.', '.', '.', '.', 'k', '.', '.'),
            charArrayOf('.', '.', '.', '.', '.', '.', '.', '.'),
            charArrayOf('.', '.', '.', 'p', '.', '.', '.', '.'),
            charArrayOf('.', '.', '.', '.', 'p', '.', '.', '.'),
            charArrayOf('.', '.', '.', '.', '.', '.', '.', '.'),
            charArrayOf('.', '.', 'P', '.', '.', '.', '.', '.'),
            charArrayOf('.', '.', 'r', '.', '.', 'P', '.', '.'),
            charArrayOf('r', '.', '.', '.', 'K', '.', '.', 'n')
        )

        // Nachweis, dass die alte Heuristik hier tatsächlich kippt
        var botWhite = 0
        var botBlack = 0
        for (r in 6..7) {
            for (c in 0..7) {
                val sym = board[r][c]
                if (sym == '.') continue
                if (sym.isUpperCase()) botWhite++ else botBlack++
            }
        }
        assertTrue("Voraussetzung des Regressionsfalls: unten stehen mehr gegnerische Figuren", botBlack > botWhite)

        val verdict = UltraRobustClassifier.detectPerspective(board)
        assertTrue("Bauernrichtung und Königsstand belegen eindeutig Weiß unten", verdict.isWhitePerspective)
        assertTrue(verdict.confidence > 0.3f)
    }

    @Test
    fun testGeraeumteGrundreiheBeiSchwarzerPerspektive() {
        // Dieselbe Stellung gespiegelt: der Spieler führt Schwarz, oben stehen die weißen Figuren
        val board = spiegeln(
            arrayOf(
                charArrayOf('.', '.', '.', '.', '.', 'k', '.', '.'),
                charArrayOf('.', '.', '.', '.', '.', '.', '.', '.'),
                charArrayOf('.', '.', '.', 'p', '.', '.', '.', '.'),
                charArrayOf('.', '.', '.', '.', 'p', '.', '.', '.'),
                charArrayOf('.', '.', '.', '.', '.', '.', '.', '.'),
                charArrayOf('.', '.', 'P', '.', '.', '.', '.', '.'),
                charArrayOf('.', '.', 'r', '.', '.', 'P', '.', '.'),
                charArrayOf('r', '.', '.', '.', 'K', '.', '.', 'n')
            )
        )
        val verdict = UltraRobustClassifier.detectPerspective(board)
        assertFalse(verdict.isWhitePerspective)
    }

    @Test
    fun testBauernrichtungEntscheidetWennDieKoenigeNichtsHergeben() {
        // Beide Könige stehen auf derselben Bildschirmreihe, der Königsstand sagt hier also nichts aus.
        // Die Bauern können nicht zurückziehen: ihre Reihen bleiben auch bei wenig Material aussagekräftig.
        val board = arrayOf(
            charArrayOf('.', '.', '.', '.', '.', '.', '.', '.'),
            charArrayOf('.', '.', 'p', '.', '.', '.', '.', '.'),
            charArrayOf('.', '.', '.', '.', '.', '.', '.', '.'),
            charArrayOf('.', '.', '.', '.', '.', '.', '.', '.'),
            charArrayOf('.', 'K', '.', '.', '.', '.', 'k', '.'),
            charArrayOf('.', '.', '.', '.', '.', '.', '.', '.'),
            charArrayOf('.', '.', '.', '.', '.', 'P', '.', '.'),
            charArrayOf('.', '.', '.', '.', '.', '.', '.', '.')
        )
        val verdict = UltraRobustClassifier.detectPerspective(board)
        assertTrue(verdict.isWhitePerspective)
        assertEquals("Bauernrichtung", verdict.reason)
    }

    @Test
    fun testLeeresBrettFaelltAufDieAlteHeuristikZurueck() {
        val leer = Array(8) { CharArray(8) { '.' } }
        val verdict = UltraRobustClassifier.detectPerspective(leer)
        // Ohne jede Figur heben sich alle Signale auf: Confidence 0, Rückfall auf Weiß
        assertEquals(0.0f, verdict.confidence, 0.0001f)
        assertTrue(verdict.isWhitePerspective)
    }

    @Test
    fun testSperreIgnoriertNeukalibrierungBeiSchwacherPerspektive() {
        // Volles Brett, hohe Template-Ähnlichkeit, aber die Perspektivsignale widersprechen sich:
        // die bestehende Sperre darf davon nicht überschrieben werden.
        val lock = UltraRobustClassifier.resolvePerspectiveLock(
            currentLock = true,
            detectedPerspective = false,
            occupiedCount = 30,
            medianSim = 0.95f,
            perspectiveConfidence = 0.05f
        )
        assertEquals(true, lock)

        // Mit belastbarer Perspektive kalibriert dieselbe Stellung dagegen neu (neue Partie, Seitenwechsel)
        val neu = UltraRobustClassifier.resolvePerspectiveLock(
            currentLock = true,
            detectedPerspective = false,
            occupiedCount = 30,
            medianSim = 0.95f,
            perspectiveConfidence = 0.90f
        )
        assertEquals(false, neu)
    }

    @Test
    fun testErstsperreBrauchtBelastbarePerspektive() {
        val keineSperre = UltraRobustClassifier.resolvePerspectiveLock(
            currentLock = null,
            detectedPerspective = true,
            occupiedCount = 20,
            medianSim = 0.80f,
            perspectiveConfidence = 0.10f
        )
        assertEquals(null, keineSperre)
    }
}
