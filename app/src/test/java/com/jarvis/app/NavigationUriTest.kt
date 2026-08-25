package com.jarvis.app

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Prueft nur die reine URI-Erzeugung - kein android.net.Uri/Intent (die
 * sind im JVM-Unit-Test nur Stubs, siehe StandortFormatTest.kt fuer
 * dasselbe Muster). Der eigentliche Intent-/Benachrichtigungsaufbau in
 * Navigation.starten() ist nur am echten Geraet pruefbar (siehe
 * docs/superpowers/plans/2026-08-24-auto-navigation.md, Task 7).
 */
class NavigationUriTest {

    @Test
    fun adresseMitUmlautUndKomma() {
        assertEquals(
            "google.navigation:q=Musterstra%C3%9Fe%205%2C%20Berlin",
            Navigation.mapsUri("Musterstraße 5, Berlin")
        )
    }

    @Test
    fun geschaeftsname() {
        assertEquals(
            "google.navigation:q=Rewe%20Karlstra%C3%9Fe",
            Navigation.mapsUri("Rewe Karlstraße")
        )
    }

    @Test
    fun geoRueckfallOhneMaps() {
        assertEquals("geo:0,0?q=Hauptbahnhof", Navigation.geoUri("Hauptbahnhof"))
    }
}
