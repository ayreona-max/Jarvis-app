package com.jarvis.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

/**
 * Prueft die reine Aggregations- und Payload-Logik ohne echtes Handy - wie
 * StandortFormatTest/KryptoFormatTest laeuft dieser Test ausschliesslich im
 * Cloud-Build (siehe build.gradle.kts, Kommentar bei testImplementation).
 */
class FitnessAggregationTest {

    @Test
    fun wochenStartMitten() {
        // Donnerstag, 20.08.2026 -> Montag derselben Woche
        assertEquals(LocalDate.of(2026, 8, 17), Fitness.wochenStart(LocalDate.of(2026, 8, 20)))
    }

    @Test
    fun wochenStartAmMontagSelbst() {
        assertEquals(LocalDate.of(2026, 8, 17), Fitness.wochenStart(LocalDate.of(2026, 8, 17)))
    }

    @Test
    fun wochenStartAmSonntag() {
        // Sonntag gehoert noch zur VORHERIGEN Kalenderwoche (Montag-Start)
        assertEquals(LocalDate.of(2026, 8, 17), Fitness.wochenStart(LocalDate.of(2026, 8, 23)))
    }

    @Test
    fun summiereWocheOhneFahrten() {
        val ergebnis = Fitness.summiereWoche(emptyList())
        assertEquals(0.0, ergebnis.distanzKm, 0.001)
        assertEquals(0, ergebnis.dauerMin)
        assertEquals(0, ergebnis.hoehenmeter)
        assertEquals(0, ergebnis.pulsAvg)
    }

    @Test
    fun summiereWocheEineFahrt() {
        val ergebnis = Fitness.summiereWoche(listOf(Fitness.Fahrradfahrt(20.0, 150, 60, 130)))
        assertEquals(20.0, ergebnis.distanzKm, 0.001)
        assertEquals(60, ergebnis.dauerMin)
        assertEquals(150, ergebnis.hoehenmeter)
        assertEquals(130, ergebnis.pulsAvg)
    }

    /** Puls-Durchschnitt ist nach Dauer gewichtet, nicht ein einfacher
     *  Fahrten-Durchschnitt - eine 10-Minuten-Fahrt darf den Wochenwert
     *  nicht so stark verzerren wie eine 2-Stunden-Fahrt. */
    @Test
    fun summiereWocheGewichtetPulsNachDauer() {
        val ergebnis = Fitness.summiereWoche(
            listOf(
                Fitness.Fahrradfahrt(distanzKm = 5.0, hoehenmeter = 10, dauerMin = 10, pulsAvg = 160),
                Fitness.Fahrradfahrt(distanzKm = 40.0, hoehenmeter = 300, dauerMin = 110, pulsAvg = 120),
            )
        )
        assertEquals(45.0, ergebnis.distanzKm, 0.001)
        assertEquals(120, ergebnis.dauerMin)
        assertEquals(310, ergebnis.hoehenmeter)
        // (160*10 + 120*110) / 120 = 123.33... -> 123
        assertEquals(123, ergebnis.pulsAvg)
    }

    @Test
    fun baueSyncPayloadRundetAufEineNachkommastelle() {
        val payload = Fitness.baueSyncPayload(
            schluessel = "geheim",
            kwStart = LocalDate.of(2026, 8, 17),
            woche = Fitness.Wochenaggregat(distanzKm = 42.34, dauerMin = 135, hoehenmeter = 380, pulsAvg = 128),
            tage = listOf(
                Fitness.Tagesaggregat(
                    datum = LocalDate.of(2026, 8, 17), schritte = 8400, schlafMin = 430,
                    ruhepuls = 54, pulsTagAvg = 72, gehstreckeKm = 5.16,
                )
            ),
        )
        assertEquals("geheim", payload.getString("key"))
        val woche = payload.getJSONObject("woche")
        assertEquals("2026-08-17", woche.getString("kw_start"))
        assertEquals(42.3, woche.getDouble("distanz_km"), 0.001)
        assertEquals(135, woche.getInt("dauer_min"))
        val tag = payload.getJSONArray("tage").getJSONObject(0)
        assertEquals("2026-08-17", tag.getString("datum"))
        assertEquals(5.2, tag.getDouble("gehstrecke_km"), 0.001)
    }

    @Test
    fun textFuerSyncErgebnisErfolg() {
        assertEquals(
            "Fitness-Sync erfolgreich.",
            Fitness.textFuerSyncErgebnis(Fitness.SyncErgebnis.ERFOLG),
        )
    }

    @Test
    fun textFuerSyncErgebnisNichtsZuTun() {
        assertEquals(
            "Sync nicht möglich (Server nicht eingerichtet, Health " +
                "Connect nicht verfügbar oder Berechtigung fehlt).",
            Fitness.textFuerSyncErgebnis(Fitness.SyncErgebnis.NICHTS_ZU_TUN),
        )
    }

    @Test
    fun textFuerSyncErgebnisFehlgeschlagen() {
        assertEquals(
            "Sync fehlgeschlagen – Server nicht erreichbar. Nächster " +
                "automatischer Versuch folgt.",
            Fitness.textFuerSyncErgebnis(Fitness.SyncErgebnis.SENDEN_FEHLGESCHLAGEN),
        )
    }

    /** Der Lesefehler-Text muss sich von ALLEN drei SyncErgebnis-Texten
     *  unterscheiden - genau das war der Fehler, den dieser Test verhindert:
     *  vorher benutzte der Klick-Handler bei einem Health-Connect-Lesefehler
     *  den SENDEN_FEHLGESCHLAGEN-Text mit und meldete damit "Server nicht
     *  erreichbar", obwohl der Server gar nicht beteiligt war. */
    @Test
    fun lesefehlerTextIstVonAllenAnderenVerschieden() {
        val andere = Fitness.SyncErgebnis.values()
            .map { Fitness.textFuerSyncErgebnis(it) }
        assertFalse(
            "TEXT_LESEFEHLER darf keinen der SyncErgebnis-Texte wiederverwenden",
            andere.contains(Fitness.TEXT_LESEFEHLER),
        )
    }

    /** Die Meldung muss die echte Ursache benennen (Gesundheitsdaten/Lesen)
     *  und darf NICHT dem Server die Schuld geben - "ehrliche Fehlanzeige"
     *  heisst auch: die richtige Ursache nennen. */
    @Test
    fun lesefehlerTextNenntDieRichtigeUrsache() {
        assertTrue(
            "sollte die Gesundheitsdaten als Ursache nennen",
            Fitness.TEXT_LESEFEHLER.contains("Gesundheitsdaten"),
        )
        assertFalse(
            "darf nicht faelschlich den Server als Ursache nennen",
            Fitness.TEXT_LESEFEHLER.contains("Server"),
        )
    }
}
