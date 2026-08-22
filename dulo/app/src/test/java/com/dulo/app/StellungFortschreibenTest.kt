package com.dulo.app

import com.dulo.app.core.UltraRobustClassifier
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Prüft das Fortschreiben der Stellung - den Kern der Erkennung.
 *
 * Statt das Brett bei jedem Zug neu aus dem Bild abzuleiten (wobei sich Fehleinordnungen
 * ansammeln), wird der abgelesene Zug auf die bekannte Stellung angewandt. Die Stellung bleibt
 * damit so lange richtig, wie die Züge stimmen.
 */
class StellungFortschreibenTest {

    private fun board(vararg rows: String): Array<CharArray> {
        require(rows.size == 8)
        return Array(8) { r -> rows[r].toCharArray() }
    }

    private val start = board(
        "rnbqkbnr",
        "pppppppp",
        "........",
        "........",
        "........",
        "........",
        "PPPPPPPP",
        "RNBQKBNR"
    )

    @Test
    fun testFeldnameAusBildschirmfeldWeisseSicht() {
        assertEquals("e2", UltraRobustClassifier.squareForScreenCell(6 * 8 + 4, isWhitePerspective = true))
        assertEquals("a8", UltraRobustClassifier.squareForScreenCell(0, isWhitePerspective = true))
        assertEquals("h1", UltraRobustClassifier.squareForScreenCell(63, isWhitePerspective = true))
    }

    @Test
    fun testFeldnameAusBildschirmfeldSchwarzeSicht() {
        assertEquals("a8", UltraRobustClassifier.squareForScreenCell(63, isWhitePerspective = false))
        assertEquals("h1", UltraRobustClassifier.squareForScreenCell(0, isWhitePerspective = false))
    }

    @Test
    fun testHinUndRueckwegSindDeckungsgleich() {
        // Jede Feldnummer muss sich verlustfrei in einen Feldnamen und zurück umrechnen lassen -
        // aus beiden Blickrichtungen. Ein Fehler hier würde den Pfeil auf das falsche Feld setzen.
        for (perspective in listOf(true, false)) {
            for (cell in 0..63) {
                val square = UltraRobustClassifier.squareForScreenCell(cell, perspective)
                requireNotNull(square)
                assertEquals(cell, UltraRobustClassifier.screenCellForSquare(square, perspective))
            }
        }
    }

    @Test
    fun testZugAusBildschirmfeldern() {
        // e2-e4 aus weißer Sicht: Feld 52 nach Feld 36
        val uci = UltraRobustClassifier.uciFromScreenCells(
            fromCell = 52, toCell = 36, isWhitePerspective = true, standardBoard = start
        )
        assertEquals("e2e4", uci)
    }

    @Test
    fun testZugVonLeeremFeldLiefertNull() {
        assertNull(
            UltraRobustClassifier.uciFromScreenCells(
                fromCell = 36, toCell = 28, isWhitePerspective = true, standardBoard = start
            )
        )
    }

    @Test
    fun testBauernumwandlungWirdAngehaengt() {
        val vorher = board(
            "........",
            "..P.....",
            "........",
            "........",
            "........",
            "........",
            "........",
            "....K..k"
        )
        // c7 liegt in Bildschirmreihe 1, Spalte 2 -> Feld 10; c8 in Reihe 0 -> Feld 2
        val uci = UltraRobustClassifier.uciFromScreenCells(
            fromCell = 10, toCell = 2, isWhitePerspective = true, standardBoard = vorher
        )
        assertEquals("c7c8q", uci)
    }

    @Test
    fun testFenAusFortgeschriebenerStellung() {
        val nachher = requireNotNull(UltraRobustClassifier.applyUciMove(start, "e2e4"))
        val position = UltraRobustClassifier.buildFenFromStandardBoard(nachher, activeIsWhite = false)
        assertEquals("rnbqkbnr/pppppppp/8/8/4P3/8/PPPP1PPP/RNBQKBNR b KQkq - 0 1", position.fullFen)
        assertEquals("b", position.activeColor)
        assertEquals(32, position.occupiedCount)
    }

    @Test
    fun testFenBehaeltDieAmZugBefindlicheSeite() {
        // Die Seite am Zug kommt aus dem Spielverlauf, nicht aus der Blickrichtung: nach dem Zug
        // des Gegners bin ich am Zug, auch wenn ich Schwarz spiele.
        val position = UltraRobustClassifier.buildFenFromStandardBoard(start, activeIsWhite = false)
        assertTrue(position.fullFen.contains(" b "))
        assertEquals(false, position.isWhitePerspective)
        // Aus schwarzer Sicht steht das Brett gedreht: unten links liegt h1
        assertEquals('R', position.rawBoard[0][0])
    }

    @Test
    fun testMehrereZuegeHintereinanderBleibenStimmig() {
        var brett = start
        for (zug in listOf("e2e4", "e7e5", "g1f3", "b8c6", "f1c4")) {
            brett = requireNotNull(UltraRobustClassifier.applyUciMove(brett, zug))
        }
        val position = UltraRobustClassifier.buildFenFromStandardBoard(brett, activeIsWhite = false)
        assertEquals(
            "r1bqkbnr/pppp1ppp/2n5/4p3/2B1P3/5N2/PPPP1PPP/RNBQK2R b KQkq - 0 1",
            position.fullFen
        )
    }
}
