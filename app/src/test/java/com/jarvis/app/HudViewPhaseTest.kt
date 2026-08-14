package com.jarvis.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Prueft HudView.phaseVon() - die reine Rechenfunktion hinter der
 * Animationsphase jedes HUD-Zustands (RUHT/HOERT_ZU/DENKT_NACH/ANTWORTET).
 *
 * Warum hier: Der Laptop hat keine Java-Werkzeugkette - Kotlin laeuft
 * ausschliesslich im Cloud-Build. Dieser Test liegt deshalb, genau wie
 * StandortFormatTest und KryptoFormatTest, VOR dem Bauen im Workflow.
 *
 * phaseVon() ist bewusst von SystemClock/Choreographer getrennt - genau
 * DESHALB ist sie hier ohne Android-Geraet pruefbar. Was NICHT geprueft
 * wird: der Choreographer-Neuzeichnen-Takt selbst, onVisibilityAggregated()
 * und alles, was echtes Canvas-Zeichnen braucht - das zeigt erst das
 * echte Geraet bzw. der manuelle Test aus PLAN-JARVIS-HUD-GESICHT.md.
 */
class HudViewPhaseTest {

    @Test
    fun startPhaseIstNull() {
        assertEquals(0f, HudView.phaseVon(0L, 1000L), 0.0001f)
    }

    @Test
    fun phaseAnPeriodengrenzeSpringtZurueckAufNull() {
        assertEquals(0f, HudView.phaseVon(1000L, 1000L), 0.0001f)
        assertEquals(0f, HudView.phaseVon(3000L, 1000L), 0.0001f)
    }

    @Test
    fun phaseInPeriodenmitteIstEinHalb() {
        assertEquals(0.5f, HudView.phaseVon(500L, 1000L), 0.0001f)
        assertEquals(0.5f, HudView.phaseVon(1500L, 1000L), 0.0001f)
    }

    /** Egal wie weit die verstrichene Zeit die Periode ueberschreitet -
     *  die Phase bleibt im halboffenen Intervall [0, 1). Ein Wert von
     *  genau 1 wuerde im Zeichnen als sichtbarer Sprung auffallen. */
    @Test
    fun phaseBleibtImmerInDemBereichNullBisEins() {
        val periodeMs = 600L
        var vergangenMs = 0L
        while (vergangenMs < 10_000L) {
            val p = HudView.phaseVon(vergangenMs, periodeMs)
            assertTrue("phase($vergangenMs, $periodeMs) = $p ausserhalb [0, 1)", p >= 0f && p < 1f)
            vergangenMs += 37L
        }
    }

    /** Zwei Zustaende mit unterschiedlicher Periode duerfen zur selben
     *  Uhrzeit nicht dieselbe Phase zeigen - sonst waeren sie im Zeichnen
     *  nicht unterscheidbar animiert. */
    @Test
    fun unterschiedlichePeriodenErgebenUnterschiedlichePhaseBeiGleicherZeit() {
        val vergangenMs = 700L
        val phaseKurz = HudView.phaseVon(vergangenMs, PERIODE_HOERT_ZU_MS)
        val phaseLang = HudView.phaseVon(vergangenMs, PERIODE_DENKT_NACH_MS)
        assertTrue(phaseKurz != phaseLang)
    }

    companion object {
        // Gleiche Werte wie in HudView, hier dupliziert, weil die
        // Original-Konstanten private im companion object von HudView
        // liegen - der Test soll die realen Zustands-Perioden verwenden,
        // nicht willkuerliche Zahlen.
        private const val PERIODE_HOERT_ZU_MS = 500L
        private const val PERIODE_DENKT_NACH_MS = 1200L
    }
}
