package com.dulo.app

import com.dulo.app.core.UltraRobustClassifier
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Prüft den Brettvergleich, mit dem entschieden wird, wer gezogen hat.
 *
 * Das ist die Stelle, an der die frühere Zählweise über Feldmengen versagte: Sie sah beim
 * Schlagzug nur, dass ein Feld der eigenen Farbe verschwunden war, und konnte den Ziehenden
 * nicht benennen. Hier wird stattdessen die neu aufgetauchte Figur ausgewertet.
 */
class BrettVergleichTest {

    /** Baut ein Brett aus acht Zeichenreihen (Standardausrichtung, Weiß unten) */
    private fun board(vararg rows: String): Array<CharArray> {
        require(rows.size == 8) { "Ein Brett hat acht Reihen" }
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
    fun testUnveraendertesBrettMeldetKeinenZug() {
        val diff = UltraRobustClassifier.diffBoards(start, start)
        assertEquals(0, diff.changedSquares)
        assertNull(diff.moverIsWhite)
    }

    @Test
    fun testStillerZugVonWeiss() {
        // e2-e4: Startfeld wird leer, Zielfeld bekommt den weißen Bauern
        val after = board(
            "rnbqkbnr",
            "pppppppp",
            "........",
            "........",
            "....P...",
            "........",
            "PPPP.PPP",
            "RNBQKBNR"
        )
        val diff = UltraRobustClassifier.diffBoards(start, after)
        assertEquals(2, diff.changedSquares)
        assertEquals(true, diff.moverIsWhite)
    }

    @Test
    fun testStillerZugVonSchwarz() {
        val after = board(
            "rnbqkbnr",
            "pppp.ppp",
            "........",
            "....p...",
            "........",
            "........",
            "PPPPPPPP",
            "RNBQKBNR"
        )
        val diff = UltraRobustClassifier.diffBoards(start, after)
        assertEquals(2, diff.changedSquares)
        assertEquals(false, diff.moverIsWhite)
    }

    @Test
    fun testSchlagzugNenntDenSchlagenden() {
        // Genau der Fall, an dem die alte Zählweise scheiterte: Schwarz schlägt einen weißen
        // Bauern. Es verschwindet eine weiße Figur, gezogen hat aber Schwarz. Entscheidend ist,
        // welche Figur auf dem Zielfeld steht - nicht, welche Farbe ein Feld verloren hat.
        val before = board(
            "....k...",
            "........",
            "........",
            "...p....",
            "....P...",
            "........",
            "........",
            "....K..."
        )
        val after = board(
            "....k...",
            "........",
            "........",
            "........",
            "....p...",
            "........",
            "........",
            "....K..."
        )
        val diff = UltraRobustClassifier.diffBoards(before, after)
        assertEquals(2, diff.changedSquares)
        assertEquals(false, diff.moverIsWhite)
    }

    @Test
    fun testRochadeMitVierFeldernBleibtEindeutig() {
        val before = board(
            "....k...",
            "........",
            "........",
            "........",
            "........",
            "........",
            "PPPPPPPP",
            "R...K..R"
        )
        val after = board(
            "....k...",
            "........",
            "........",
            "........",
            "........",
            "........",
            "PPPPPPPP",
            "R....RK."
        )
        val diff = UltraRobustClassifier.diffBoards(before, after)
        assertEquals(4, diff.changedSquares)
        assertEquals(true, diff.moverIsWhite)
    }

    @Test
    fun testUmwandlungWirdDemZiehendenZugeordnet() {
        val before = board(
            "....k...",
            "..P.....",
            "........",
            "........",
            "........",
            "........",
            "........",
            "....K..."
        )
        val after = board(
            "..Q.k...",
            "........",
            "........",
            "........",
            "........",
            "........",
            "........",
            "....K..."
        )
        val diff = UltraRobustClassifier.diffBoards(before, after)
        assertEquals(2, diff.changedSquares)
        assertEquals(true, diff.moverIsWhite)
    }

    @Test
    fun testZuVieleAenderungenGeltenAlsUnklar() {
        // Mehr als eine Rochade an Veränderung kann kein einzelner Zug sein: dann hat die
        // Erkennung gepatzt und es darf gerade nichts entschieden werden.
        val after = board(
            "rnbqkbnr",
            "pp.ppppp",
            "........",
            "..p.....",
            "....P...",
            ".....N..",
            "PPPP.PPP",
            "RNBQKB.R"
        )
        val diff = UltraRobustClassifier.diffBoards(start, after)
        assertTrue(diff.changedSquares > 4)
        assertNull(diff.moverIsWhite)
    }

    @Test
    fun testWiderspruechlicheFarbenGeltenAlsUnklar() {
        // Auf beiden Seiten taucht etwas Neues auf: das passt zu keinem einzelnen Zug
        val before = board(
            "....k...",
            "........",
            "........",
            "........",
            "........",
            "........",
            "........",
            "....K..."
        )
        val after = board(
            "....k...",
            "...p....",
            "........",
            "........",
            "........",
            "........",
            "...P....",
            "....K..."
        )
        val diff = UltraRobustClassifier.diffBoards(before, after)
        assertEquals(2, diff.changedSquares)
        assertNull(diff.moverIsWhite)
    }

    @Test
    fun testGrundstellungWirdErkannt() {
        assertTrue(UltraRobustClassifier.isFreshStartPosition(start))
    }

    @Test
    fun testLaufendePartieIstKeineGrundstellung() {
        val running = board(
            "rnbqkbnr",
            "pppp.ppp",
            "........",
            "....p...",
            "....P...",
            "........",
            "PPPP.PPP",
            "RNBQKBNR"
        )
        assertFalse(UltraRobustClassifier.isFreshStartPosition(running))
    }

    @Test
    fun testEigeneFarbeAusGrundstellungBeiSchwarzUnten() {
        // Nutzer spielt Schwarz: unten stehen die dunklen Figuren
        val screen = board(
            "RNBQKBNR",
            "PPPPPPPP",
            "........",
            "........",
            "........",
            "........",
            "pppppppp",
            "rnbqkbnr"
        )
        assertEquals(false, UltraRobustClassifier.sideFromStartingRows(screen))
        assertTrue(UltraRobustClassifier.isFreshStartPosition(screen))
    }

    @Test
    fun testZugNachspielenVerschiebtDieFigur() {
        val nachher = UltraRobustClassifier.applyUciMove(start, "e2e4")
        requireNotNull(nachher)
        assertEquals('.', nachher[6][4])
        assertEquals('P', nachher[4][4])
        // Der Vergleich mit dem nachgespielten Brett meldet danach keine Veränderung mehr
        assertEquals(0, UltraRobustClassifier.diffBoards(nachher, nachher).changedSquares)
    }

    @Test
    fun testUmwandlungWirdNachgespielt() {
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
        val nachher = UltraRobustClassifier.applyUciMove(vorher, "c7c8q")
        requireNotNull(nachher)
        assertEquals('Q', nachher[0][2])
        assertEquals('.', nachher[1][2])
    }

    @Test
    fun testRochadeWirdMitTurmNachgespielt() {
        val vorher = board(
            "....k...",
            "........",
            "........",
            "........",
            "........",
            "........",
            "........",
            "R...K..R"
        )
        val kurz = UltraRobustClassifier.applyUciMove(vorher, "e1g1")
        requireNotNull(kurz)
        assertEquals('K', kurz[7][6])
        assertEquals('R', kurz[7][5])
        assertEquals('.', kurz[7][7])

        val lang = UltraRobustClassifier.applyUciMove(vorher, "e1c1")
        requireNotNull(lang)
        assertEquals('K', lang[7][2])
        assertEquals('R', lang[7][3])
        assertEquals('.', lang[7][0])
    }

    @Test
    fun testZugVonLeeremFeldWirdAbgelehnt() {
        assertNull(UltraRobustClassifier.applyUciMove(start, "e4e5"))
        assertNull(UltraRobustClassifier.applyUciMove(start, "xx"))
    }

    @Test
    fun testEigenerZugUndGegnerzugZusammenSindUnklar() {
        // Genau der Fall, den das Nachspielen verhindert: werden beide Züge zusammen verglichen,
        // taucht auf beiden Seiten etwas Neues auf und der Ziehende ist nicht mehr bestimmbar.
        val nachEigenem = requireNotNull(UltraRobustClassifier.applyUciMove(start, "e2e4"))
        val nachGegner = requireNotNull(UltraRobustClassifier.applyUciMove(nachEigenem, "e7e5"))

        assertNull(UltraRobustClassifier.diffBoards(start, nachGegner).moverIsWhite)
        // Mit nachgespieltem eigenem Zug ist der Gegnerzug dagegen eindeutig
        assertEquals(false, UltraRobustClassifier.diffBoards(nachEigenem, nachGegner).moverIsWhite)
    }

    @Test
    fun testVerwechselteFigurenartStoertDenVergleichNicht() {
        // Der Musterabgleich liest den Laeufer auf c8 als Springer und den Turm auf a8 als Dame -
        // beide Felder waren am Zug gar nicht beteiligt. Frueher zaehlte das als Veraenderung,
        // die Summe sprengte die Schwelle und der Vergleich meldete dauerhaft "unklar".
        val vorher = board(
            "rnbqkbnr",
            "pppppppp",
            "........",
            "........",
            "........",
            "........",
            "PPPPPPPP",
            "RNBQKBNR"
        )
        val nachher = board(
            "qnnqkbnr",
            "pppp.ppp",
            "........",
            "....p...",
            "........",
            "........",
            "PPPPPPPP",
            "RNBQKBNR"
        )
        val diff = UltraRobustClassifier.diffBoards(vorher, nachher)
        // Nur der tatsaechliche Zug e7-e5 zaehlt: zwei Felder, gezogen hat Schwarz
        assertEquals(2, diff.changedSquares)
        assertEquals(false, diff.moverIsWhite)
    }

    @Test
    fun testFarbklasseFasstFigurenartZusammen() {
        assertEquals('W', UltraRobustClassifier.colourClass('Q'))
        assertEquals('W', UltraRobustClassifier.colourClass('P'))
        assertEquals('B', UltraRobustClassifier.colourClass('q'))
        assertEquals('.', UltraRobustClassifier.colourClass('.'))
    }

    @Test
    fun testFarbwechselAufEinemFeldBleibtEinSchlagzug() {
        // Die groebere Betrachtung darf den Schlagzug nicht verschlucken: dort wechselt die Farbe
        val vorher = board(
            "....k...",
            "........",
            "........",
            "...p....",
            "....P...",
            "........",
            "........",
            "....K..."
        )
        val nachher = board(
            "....k...",
            "........",
            "........",
            "........",
            "....p...",
            "........",
            "........",
            "....K..."
        )
        val diff = UltraRobustClassifier.diffBoards(vorher, nachher)
        assertEquals(2, diff.changedSquares)
        assertEquals(false, diff.moverIsWhite)
    }
}
