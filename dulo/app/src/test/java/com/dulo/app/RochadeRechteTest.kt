package com.dulo.app

import com.dulo.app.core.UltraRobustClassifier
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Prüft das Mitführen der Rochaderechte.
 *
 * Aus der reinen Figurenstellung sind sie nicht ablesbar: Ein König, der nach f1 und wieder
 * zurück nach e1 gegangen ist, steht wieder zu Hause, darf aber nie wieder rochieren. Wird das
 * geraten, schlägt die Engine eine Rochade vor, die das Spiel ablehnt - und im Auto-Betrieb
 * bleibt der getippte Zug dann wirkungslos.
 */
class RochadeRechteTest {

    @Test
    fun testKoenigszugNimmtBeideRechte() {
        assertEquals("kq", UltraRobustClassifier.updateCastlingRights("KQkq", "e1f1", 'K'))
        assertEquals("KQ", UltraRobustClassifier.updateCastlingRights("KQkq", "e8f8", 'k'))
    }

    @Test
    fun testRueckkehrDesKoenigsStelltDasRechtNichtWiederHer() {
        val nachHin = UltraRobustClassifier.updateCastlingRights("KQkq", "e1f1", 'K')
        val nachZurueck = UltraRobustClassifier.updateCastlingRights(nachHin, "f1e1", 'K')
        assertEquals("kq", nachZurueck)
    }

    @Test
    fun testTurmzugNimmtNurSeineEcke() {
        assertEquals("Qkq", UltraRobustClassifier.updateCastlingRights("KQkq", "h1g1", 'R'))
        assertEquals("Kkq", UltraRobustClassifier.updateCastlingRights("KQkq", "a1b1", 'R'))
        assertEquals("KQq", UltraRobustClassifier.updateCastlingRights("KQkq", "h8g8", 'r'))
        assertEquals("KQk", UltraRobustClassifier.updateCastlingRights("KQkq", "a8b8", 'r'))
    }

    @Test
    fun testSchlagAufDerTurmeckeNimmtDasRechtDerGegenseite() {
        // Ein schwarzer Läufer schlägt den Turm auf h1: Weiß verliert die kurze Rochade
        assertEquals("Qkq", UltraRobustClassifier.updateCastlingRights("KQkq", "c6h1", 'b'))
        assertEquals("KQk", UltraRobustClassifier.updateCastlingRights("KQkq", "c3a8", 'B'))
    }

    @Test
    fun testRochadeSelbstNimmtDieRechteDerZiehendenSeite() {
        assertEquals("kq", UltraRobustClassifier.updateCastlingRights("KQkq", "e1g1", 'K'))
    }

    @Test
    fun testOhneRechteBleibtEsBeimStrich() {
        assertEquals("-", UltraRobustClassifier.updateCastlingRights("-", "e2e4", 'P'))
        assertEquals("-", UltraRobustClassifier.updateCastlingRights("K", "e1f1", 'K'))
    }

    @Test
    fun testReihenfolgeBleibtWieImFen() {
        assertEquals("KQkq", UltraRobustClassifier.updateCastlingRights("qkQK", "e2e4", 'P'))
    }

    @Test
    fun testGewoehnlicherZugLaesstDieRechteStehen() {
        assertEquals("KQkq", UltraRobustClassifier.updateCastlingRights("KQkq", "g1f3", 'N'))
    }

    @Test
    fun testMitgefuehrteRechteSchlagenDieGerateneStellung() {
        // Der König steht wieder auf e1 und die Türme in den Ecken - aus der Stellung geraten
        // ergäbe das "KQ". Mitgeführt ist aber, dass Weiß seine Rechte verspielt hat.
        val brett = arrayOf(
            "....k...".toCharArray(),
            "........".toCharArray(),
            "........".toCharArray(),
            "........".toCharArray(),
            "........".toCharArray(),
            "........".toCharArray(),
            "........".toCharArray(),
            "R...K..R".toCharArray()
        )
        assertEquals("KQ", UltraRobustClassifier.computeCastlingRights(brett))

        val position = UltraRobustClassifier.buildFenFromStandardBoard(
            standardBoard = brett,
            activeIsWhite = true,
            castlingRights = "-"
        )
        assertEquals("4k3/8/8/8/8/8/8/R3K2R w - - 0 1", position.fullFen)
    }
}
