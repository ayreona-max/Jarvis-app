package com.jarvis.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Prueft die reinen Rechenfunktionen hinter dem HUD-Kern-Puls-Bogen, der
 * seit 23.08.2026 die Ikosaeder-Kugel ersetzt (siehe
 * PLAN-JARVIS-APP-REDESIGN.md). pulsFaktor() ist unveraendert aus der
 * fruehreren Kugel-Fassung uebernommen (vorher in
 * HudViewProjektionTest.kt geprueft) - bogenSweepGrad() ist neu.
 */
class HudViewBogenTest {

    @Test
    fun pulsFaktorAmStartIstEins() {
        assertEquals(1f, HudView.pulsFaktor(0L, pulsTempo = 1.6f, pulsStaerke = 0.1f), 0.0001f)
    }

    @Test
    fun pulsFaktorBleibtInnerhalbDerStaerkeGrenzen() {
        val staerke = 0.13f
        var t = 0L
        while (t < 5000L) {
            val puls = HudView.pulsFaktor(t, pulsTempo = 2.0f, pulsStaerke = staerke)
            assertTrue(
                "puls($t) = $puls ausserhalb [1-$staerke, 1+$staerke]",
                puls >= 1f - staerke - 0.0001f && puls <= 1f + staerke + 0.0001f
            )
            t += 23L
        }
    }

    @Test
    fun bogenSweepGradMultipliziertBasisMitPuls() {
        assertEquals(110f, HudView.bogenSweepGrad(100f, 1.1f), 0.001f)
        assertEquals(90f, HudView.bogenSweepGrad(100f, 0.9f), 0.001f)
    }

    @Test
    fun bogenSweepGradIstAufEineVolleUmdrehungBegrenzt() {
        assertEquals(360f, HudView.bogenSweepGrad(350f, 1.5f), 0.001f)
    }

    @Test
    fun bogenSweepGradWirdNieNegativ() {
        assertEquals(0f, HudView.bogenSweepGrad(0f, 1f), 0.001f)
    }
}
