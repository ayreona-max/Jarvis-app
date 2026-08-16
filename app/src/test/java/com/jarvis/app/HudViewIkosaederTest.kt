package com.jarvis.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.sqrt

/**
 * Prueft die reine Ikosaeder-Geometrie hinter der HUD-Kern-Kugel
 * (HudView.ikosaederPunkte/-Flaechen/unterteile/kantenAusFlaechen) -
 * reine Rechenfunktionen ohne Android-Abhaengigkeit, genau wie
 * phaseVon() in HudViewPhaseTest.kt. Erwartete Zahlen (12/20/30 ->
 * 42/80/120 -> 162/320/480) stehen in PLAN-JARVIS-HUD-KERN-UND-RAND.md.
 */
class HudViewIkosaederTest {

    @Test
    fun ikosaederHatZwoelfPunkte() {
        assertEquals(12, HudView.ikosaederPunkte().size)
    }

    @Test
    fun ikosaederPunkteSindAufDieEinheitskugelNormiert() {
        for (p in HudView.ikosaederPunkte()) {
            val laenge = sqrt(p.x * p.x + p.y * p.y + p.z * p.z)
            assertEquals(1f, laenge, 0.0001f)
        }
    }

    @Test
    fun ikosaederHatZwanzigFlaechen() {
        assertEquals(20, HudView.ikosaederFlaechen().size)
    }

    @Test
    fun einmalUnterteiltErgibtZweiundvierzigPunkteUndAchtzigFlaechen() {
        val (punkte, flaechen) = HudView.unterteile(HudView.ikosaederPunkte(), HudView.ikosaederFlaechen())
        assertEquals(42, punkte.size)
        assertEquals(80, flaechen.size)
    }

    @Test
    fun zweimalUnterteiltErgibtEinhundertzweiundsechzigPunkte() {
        val erste = HudView.unterteile(HudView.ikosaederPunkte(), HudView.ikosaederFlaechen())
        val zweite = HudView.unterteile(erste.first, erste.second)
        assertEquals(162, zweite.first.size)
        assertEquals(320, zweite.second.size)
    }

    @Test
    fun unterteilungHaeltAllePunkteAufDerEinheitskugel() {
        val (punkte, _) = HudView.unterteile(HudView.ikosaederPunkte(), HudView.ikosaederFlaechen())
        for (p in punkte) {
            val laenge = sqrt(p.x * p.x + p.y * p.y + p.z * p.z)
            assertEquals(1f, laenge, 0.0001f)
        }
    }

    @Test
    fun kantenAusIkosaederFlaechenSindDreissigUndEindeutig() {
        val kanten = HudView.kantenAusFlaechen(HudView.ikosaederFlaechen())
        assertEquals(30, kanten.size)
        val schluessel = kanten.map { minOf(it[0], it[1]) to maxOf(it[0], it[1]) }
        assertEquals(schluessel.size, schluessel.toSet().size)
    }

    @Test
    fun kantenAusZweimalUnterteiltenFlaechenSindVierhundertachtzig() {
        val erste = HudView.unterteile(HudView.ikosaederPunkte(), HudView.ikosaederFlaechen())
        val zweite = HudView.unterteile(erste.first, erste.second)
        val kanten = HudView.kantenAusFlaechen(zweite.second)
        assertEquals(480, kanten.size)
    }

    @Test
    fun keineKanteVerbindetEinenPunktMitSichSelbst() {
        val kanten = HudView.kantenAusFlaechen(HudView.ikosaederFlaechen())
        for (k in kanten) {
            assertTrue(k[0] != k[1])
        }
    }
}
