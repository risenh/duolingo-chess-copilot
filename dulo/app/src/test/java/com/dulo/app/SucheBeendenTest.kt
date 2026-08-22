package com.dulo.app

import com.dulo.app.engine.StockfishBridge
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Prüft den vorzeitigen Abbruch der Suche.
 *
 * Die Bedenkzeit ist eine Obergrenze, kein Soll. Bleibt die Engine über mehrere Tiefen beim
 * selben Zug, ändert weitere Rechenzeit das Ergebnis so gut wie nie - sie kostet nur Wartezeit
 * vor dem Zug. In scharfen Stellungen, in denen der beste Zug noch wechselt, greift der Abbruch
 * bewusst nicht.
 */
class SucheBeendenTest {

    @Test
    fun testHauptvarianteWirdGelesen() {
        assertEquals(
            "e2e4",
            StockfishBridge.parsePvMove("info depth 20 seldepth 28 score cp 31 nodes 1234 pv e2e4 e7e5 g1f3")
        )
        assertEquals(
            "a7a8q",
            StockfishBridge.parsePvMove("info depth 12 score mate 3 pv a7a8q b1c1")
        )
    }

    @Test
    fun testZeilenOhneHauptvarianteLiefernNull() {
        assertNull(StockfishBridge.parsePvMove("info depth 5 score cp 12 nodes 900"))
        assertNull(StockfishBridge.parsePvMove("bestmove e2e4 ponder e7e5"))
        assertNull(StockfishBridge.parsePvMove("info string NNUE evaluation using nn-5af11540bbfe.nnue"))
        // Unbrauchbare Zugangabe wird nicht als Zug ausgegeben
        assertNull(StockfishBridge.parsePvMove("info depth 8 pv (none)"))
    }

    @Test
    fun testStabilerZugBeendetDieSuche() {
        assertTrue(
            StockfishBridge.searchIsSettled(
                stableDepths = StockfishBridge.STABLE_DEPTHS_REQUIRED,
                depth = StockfishBridge.MIN_SETTLED_DEPTH,
                isMate = false,
                elapsedMs = 1000L,
                moveTimeMs = 2000L
            )
        )
    }

    @Test
    fun testZuFrueheTiefeBeendetNicht() {
        // Auch ein lange stabiler Zug zählt in geringer Tiefe nicht: die Aussage ist zu jung
        assertFalse(
            StockfishBridge.searchIsSettled(
                stableDepths = 20,
                depth = StockfishBridge.MIN_SETTLED_DEPTH - 1,
                isMate = false,
                elapsedMs = 1900L,
                moveTimeMs = 2000L
            )
        )
    }

    @Test
    fun testZuFrueheZeitBeendetNicht() {
        // Selbst bei tiefer und stabiler Suche wird nicht nach einem Wimpernschlag abgebrochen:
        // Spielstärke geht vor einer gesparten Sekunde.
        assertFalse(
            StockfishBridge.searchIsSettled(
                stableDepths = 30,
                depth = 30,
                isMate = false,
                elapsedMs = 200L,
                moveTimeMs = 2000L
            )
        )
    }

    @Test
    fun testWechselnderZugBeendetNicht() {
        // Genau der Fall, in dem die volle Bedenkzeit gebraucht wird: eine scharfe Stellung
        assertFalse(
            StockfishBridge.searchIsSettled(
                stableDepths = 1,
                depth = 25,
                isMate = false,
                elapsedMs = 1900L,
                moveTimeMs = 2000L
            )
        )
    }

    @Test
    fun testMattBeendetSofort() {
        // Ein gefundenes Matt ist das Ende der Fahnenstange, Weiterrechnen ändert nichts mehr -
        // und dafür gilt die Zeitschranke nicht.
        assertTrue(
            StockfishBridge.searchIsSettled(
                stableDepths = 1,
                depth = StockfishBridge.MATE_SETTLED_DEPTH,
                isMate = true,
                elapsedMs = 50L,
                moveTimeMs = 2000L
            )
        )
        // Aber auch das erst ab einer belastbaren Tiefe
        assertFalse(
            StockfishBridge.searchIsSettled(
                stableDepths = 1, depth = 3, isMate = true, elapsedMs = 50L, moveTimeMs = 2000L
            )
        )
    }

    @Test
    fun testZwischenstaendeAusDerFenstersucheZaehlenNicht() {
        // Zeilen mit lowerbound/upperbound sind Zwischenstände einer fehlgeschlagenen
        // Fenstersuche: ihre Hauptvariante kann in die Irre führen und darf den Abbruch
        // nicht auslösen.
        assertNull(StockfishBridge.parsePvMove("info depth 22 score cp 45 lowerbound pv d2d4"))
        assertNull(StockfishBridge.parsePvMove("info depth 22 score cp 45 upperbound pv d2d4"))
    }

    @Test
    fun testSuchbefehlBleibtDieObergrenze() {
        // Der Abbruch verkürzt nur; die Obergrenze steht weiterhin im Suchbefehl
        assertEquals("go depth 30 movetime 2000", StockfishBridge.buildGoCommand(2000L))
    }
}
