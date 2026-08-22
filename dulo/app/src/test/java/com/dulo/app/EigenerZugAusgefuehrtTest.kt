package com.dulo.app

import com.dulo.app.core.UltraRobustClassifier
import com.dulo.app.engine.StockfishBridge
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Prüft die Abbruchbedingung für den Pfeil: Er bleibt stehen, bis genau der empfohlene Zug
 * ausgeführt ist - Startfeld leer, Zielfeld besetzt.
 */
class EigenerZugAusgefuehrtTest {

    /** Leeres Brett: alle Felder gleichmäßig hell, keine Streuung */
    private fun leereFelder(): Pair<FloatArray, FloatArray> =
        FloatArray(64) { 200f } to FloatArray(64) { 0.5f }

    /** Setzt eine Figur auf ein Feld: Streuung hoch, Helligkeit anders */
    private fun setzeFigur(means: FloatArray, stds: FloatArray, cell: Int, helligkeit: Float) {
        means[cell] = helligkeit
        stds[cell] = 45f
    }

    /** Nimmt eine Figur vom Feld: wieder gleichmäßige Fläche */
    private fun raeumeFeld(means: FloatArray, stds: FloatArray, cell: Int) {
        means[cell] = 200f
        stds[cell] = 0.5f
    }

    @Test
    fun testFeldnummerAusWeisserSicht() {
        // Weiß unten: e2 liegt in der vorletzten Bildschirmreihe (row 6), Linie e = Spalte 4
        assertEquals(6 * 8 + 4, UltraRobustClassifier.screenCellForSquare("e2", isWhitePerspective = true))
        assertEquals(4, UltraRobustClassifier.screenCellForSquare("e8", isWhitePerspective = true))
        assertEquals(0, UltraRobustClassifier.screenCellForSquare("a8", isWhitePerspective = true))
        assertEquals(63, UltraRobustClassifier.screenCellForSquare("h1", isWhitePerspective = true))
    }

    @Test
    fun testFeldnummerAusSchwarzerSicht() {
        // Schwarz unten: das Brett steht gedreht, a8 liegt unten rechts
        assertEquals(63, UltraRobustClassifier.screenCellForSquare("a8", isWhitePerspective = false))
        assertEquals(0, UltraRobustClassifier.screenCellForSquare("h1", isWhitePerspective = false))
        assertEquals(1 * 8 + 3, UltraRobustClassifier.screenCellForSquare("e2", isWhitePerspective = false))
    }

    @Test
    fun testUnbrauchbarerFeldnameLiefertNull() {
        assertNull(UltraRobustClassifier.screenCellForSquare("", true))
        assertNull(UltraRobustClassifier.screenCellForSquare("z2", true))
        assertNull(UltraRobustClassifier.screenCellForSquare("e9", true))
    }

    @Test
    fun testAusgefuehrterZugWirdErkannt() {
        val (vorherM, vorherS) = leereFelder()
        setzeFigur(vorherM, vorherS, 52, 90f) // Figur steht auf dem Startfeld

        val nachherM = vorherM.copyOf()
        val nachherS = vorherS.copyOf()
        raeumeFeld(nachherM, nachherS, 52)
        setzeFigur(nachherM, nachherS, 36, 90f) // und jetzt auf dem Zielfeld

        assertTrue(
            UltraRobustClassifier.moveWasPlayed(
                vorherM, vorherS, nachherM, nachherS, fromCell = 52, toCell = 36
            )
        )
    }

    @Test
    fun testUnveraendertesBrettGiltNichtAlsAusgefuehrt() {
        val (means, stds) = leereFelder()
        setzeFigur(means, stds, 52, 90f)
        assertFalse(
            UltraRobustClassifier.moveWasPlayed(
                means, stds, means.copyOf(), stds.copyOf(), fromCell = 52, toCell = 36
            )
        )
    }

    @Test
    fun testStartfeldGeraeumtAberZielLeerGiltNicht() {
        // Zwischenstand einer Zuganimation: die Figur ist unterwegs, aber noch nicht angekommen
        val (vorherM, vorherS) = leereFelder()
        setzeFigur(vorherM, vorherS, 52, 90f)

        val nachherM = vorherM.copyOf()
        val nachherS = vorherS.copyOf()
        raeumeFeld(nachherM, nachherS, 52)

        assertFalse(
            UltraRobustClassifier.moveWasPlayed(
                vorherM, vorherS, nachherM, nachherS, fromCell = 52, toCell = 36
            )
        )
    }

    @Test
    fun testAndererZugGiltNichtAlsAusgefuehrt() {
        // Der Nutzer spielt etwas anderes: das Startfeld des Pfeils bleibt besetzt
        val (vorherM, vorherS) = leereFelder()
        setzeFigur(vorherM, vorherS, 52, 90f)
        setzeFigur(vorherM, vorherS, 51, 90f)

        val nachherM = vorherM.copyOf()
        val nachherS = vorherS.copyOf()
        raeumeFeld(nachherM, nachherS, 51)
        setzeFigur(nachherM, nachherS, 35, 90f)

        assertFalse(
            UltraRobustClassifier.moveWasPlayed(
                vorherM, vorherS, nachherM, nachherS, fromCell = 52, toCell = 36
            )
        )
    }

    @Test
    fun testSchlagzugAufBesetztesZielfeldWirdErkannt() {
        // Zielfeld war schon besetzt (gegnerische Figur) - entscheidend ist, dass es sich ändert
        val (vorherM, vorherS) = leereFelder()
        setzeFigur(vorherM, vorherS, 52, 90f)  // eigene helle Figur
        setzeFigur(vorherM, vorherS, 36, 30f)  // gegnerische dunkle Figur

        val nachherM = vorherM.copyOf()
        val nachherS = vorherS.copyOf()
        raeumeFeld(nachherM, nachherS, 52)
        setzeFigur(nachherM, nachherS, 36, 90f) // jetzt steht dort die eigene helle Figur

        assertTrue(
            UltraRobustClassifier.moveWasPlayed(
                vorherM, vorherS, nachherM, nachherS, fromCell = 52, toCell = 36
            )
        )
    }

    @Test
    fun testUngueltigeFeldnummernSindHarmlos() {
        val (means, stds) = leereFelder()
        assertFalse(
            UltraRobustClassifier.moveWasPlayed(means, stds, means, stds, fromCell = -1, toCell = 36)
        )
        assertFalse(
            UltraRobustClassifier.moveWasPlayed(means, stds, means, stds, fromCell = 52, toCell = 64)
        )
    }

    @Test
    fun testSuchbefehlDeckeltTiefeUndZeit() {
        assertEquals("go depth 30 movetime 2000", StockfishBridge.buildGoCommand(2000L))
        assertEquals("go depth 12 movetime 500", StockfishBridge.buildGoCommand(500L, maxDepth = 12))
    }
}
