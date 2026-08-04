package com.jarvis.app

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Prueft die Adress-Formatierung fuer v0.31 (Standort mitschicken).
 *
 * Warum hier: Der Laptop hat keine Java-Werkzeugkette - Kotlin laeuft
 * ausschliesslich im Cloud-Build. Dieser Test liegt deshalb, genau wie
 * KryptoFormatTest, VOR dem Bauen im Workflow: Stimmt das Format nicht,
 * entsteht gar keine APK.
 *
 * Geprueft wird bewusst nur der Teil, der ohne Handy pruefbar IST - die
 * reine Umformung der Adressbestandteile in den Text, der an Jarvis geht.
 * Ob das Handy einen Fix bekommt und was der Geocoder liefert, zeigt erst
 * das echte Geraet.
 *
 * Der eigentliche Zweck: Was hier herauskommt, landet im System-Prompt und
 * bestimmt, WO Jarvis sucht. Ein "Berlin-Berlin" oder eine Hausnummer ohne
 * Strasse wuerde dort Unsinn anrichten.
 */
class StandortFormatTest {

    private fun f(
        strasse: String? = null,
        hausnummer: String? = null,
        plz: String? = null,
        ort: String? = null,
        ortsteil: String? = null,
    ) = Standort.formatiere(strasse, hausnummer, plz, ort, ortsteil)

    @Test
    fun vollstaendigeAdresse() {
        assertEquals(
            "Bahrenfelder Straße 12, 22765 Hamburg-Ottensen",
            f("Bahrenfelder Straße", "12", "22765", "Hamburg", "Ottensen")
        )
    }

    /** Doreens echte Adresse - der haeufigste Fall ueberhaupt. */
    @Test
    fun ihreWohnadresse() {
        assertEquals(
            "Am Rötepfuhl 35, 12349 Berlin-Buckow",
            f("Am Rötepfuhl", "35", "12349", "Berlin", "Buckow")
        )
    }

    @Test
    fun ohneHausnummer() {
        assertEquals(
            "Bahrenfelder Straße, 22765 Hamburg-Ottensen",
            f("Bahrenfelder Straße", null, "22765", "Hamburg", "Ottensen")
        )
    }

    /** Grobe Ortung (nur Funkzelle): Strasse fehlt, Stadtteil reicht. */
    @Test
    fun ohneStrasse() {
        assertEquals("22765 Hamburg-Ottensen", f(null, null, "22765", "Hamburg", "Ottensen"))
    }

    /** Eine Hausnummer ohne Strasse ist wertlos und darf nicht auftauchen. */
    @Test
    fun hausnummerOhneStrasseWirdVerworfen() {
        assertEquals("22765 Hamburg", f(null, "12", "22765", "Hamburg", null))
    }

    @Test
    fun ohneOrtsteil() {
        assertEquals("22765 Hamburg", f(null, null, "22765", "Hamburg", null))
    }

    /** Der Geocoder liefert oft denselben Namen zweimal - "Berlin-Berlin"
     *  waere im Prompt nicht nur haesslich, sondern verdaechtig. */
    @Test
    fun ortsteilGleichStadtWirdNichtVerdoppelt() {
        assertEquals("10115 Berlin", f(null, null, "10115", "Berlin", "Berlin"))
    }

    @Test
    fun ortsteilGleichStadtIgnoriertGrossschreibung() {
        assertEquals("10115 Berlin", f(null, null, "10115", "Berlin", "berlin"))
    }

    /** Steht der Stadtteil schon im Stadtnamen, wird er nicht angehaengt. */
    @Test
    fun ortsteilSchonEnthalten() {
        assertEquals("12349 Berlin-Buckow", f(null, null, "12349", "Berlin-Buckow", "Buckow"))
    }

    @Test
    fun ohnePlz() {
        assertEquals(
            "Bahrenfelder Straße 12, Hamburg-Ottensen",
            f("Bahrenfelder Straße", "12", null, "Hamburg", "Ottensen")
        )
    }

    @Test
    fun nurStadt() {
        assertEquals("Hamburg", f(null, null, null, "Hamburg", null))
    }

    @Test
    fun nurPlz() {
        assertEquals("22765", f(null, null, "22765", null, null))
    }

    /** Nur der Ortsteil bekannt - besser als nichts. */
    @Test
    fun nurOrtsteil() {
        assertEquals("Ottensen", f(null, null, null, null, "Ottensen"))
    }

    /** Nichts Brauchbares -> leerer Text, dann geht KEIN Feld mit und der
     *  Server verhaelt sich wie vor v0.31. */
    @Test
    fun garNichts() {
        assertEquals("", f(null, null, null, null, null))
    }

    @Test
    fun leerzeichenGeltenAlsLeer() {
        assertEquals("", f("  ", "", "   ", " ", ""))
    }

    /** Der Geocoder liefert gelegentlich Zeilenumbrueche und doppelte
     *  Leerzeichen mit - die duerfen nicht in den Prompt geraten. */
    @Test
    fun ueberfluessigeLeerzeichenWerdenZusammengezogen() {
        assertEquals(
            "Am Rötepfuhl 35, 12349 Berlin-Buckow",
            f("  Am   Rötepfuhl \n", " 35 ", " 12349 ", " Berlin ", " Buckow ")
        )
    }
}
