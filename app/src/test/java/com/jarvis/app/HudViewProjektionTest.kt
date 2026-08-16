package com.jarvis.app

import com.jarvis.app.HudView.Companion.Vektor3
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI

/**
 * Prueft die reinen Dreh-/Projektions-/Puls-Rechenfunktionen hinter der
 * HUD-Kern-Kugel (HudView.gedrehtUndProjiziert/pulsFaktor) - ohne
 * Android-Abhaengigkeit, gleicher Ansatz wie HudViewIkosaederTest.kt.
 */
class HudViewProjektionTest {

    @Test
    fun punktImUrsprungLandetAufDerBildschirmmitte() {
        val p = HudView.gedrehtUndProjiziert(
            Vektor3(0f, 0f, 0f), drehYRad = 0f, drehXRad = 0f,
            mitteX = 120f, mitteY = 120f, skala = 46f,
        )
        assertEquals(120f, p.x, 0.001f)
        assertEquals(120f, p.y, 0.001f)
    }

    @Test
    fun ungedrehterPunktWirdNurSkaliertUndVerschoben() {
        val p = HudView.gedrehtUndProjiziert(
            Vektor3(1f, 0f, 0f), drehYRad = 0f, drehXRad = 0f,
            mitteX = 120f, mitteY = 120f, skala = 46f,
        )
        assertEquals(166f, p.x, 0.001f)
        assertEquals(120f, p.y, 0.001f)
    }

    @Test
    fun volleUmdrehungLandetWiederAufDemStartpunkt() {
        val start = Vektor3(1f, 0.3f, -0.5f)
        val p = HudView.gedrehtUndProjiziert(
            start, drehYRad = (2.0 * PI).toFloat(), drehXRad = 0f,
            mitteX = 0f, mitteY = 0f, skala = 1f,
        )
        assertEquals(start.x, p.x, 0.001f)
        assertEquals(start.y, p.y, 0.001f)
    }

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
}
