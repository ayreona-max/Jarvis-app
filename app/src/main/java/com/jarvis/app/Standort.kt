package com.jarvis.app

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Geocoder
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import android.os.Looper
import androidx.core.content.ContextCompat
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/**
 * v0.31: Der aktuelle Aufenthaltsort wird als ADRESSTEXT mitgeschickt.
 *
 * Doreens Wunsch vom 04.08.2026: "Es ergeben sich unterwegs zeitliche Puffer,
 * die ich mit einem Restaurantbesuch fuelle - ich brauche ihn dann als
 * verlaessliche Info-Quelle, standortgenau, egal an welchem Ort ich bin."
 * Der Server laeuft auf dem Laptop zu Hause und kann das von sich aus nicht
 * wissen. Die zuerst gebaute Server-Regel "fehlt der Ort, nimm den Wohnort an"
 * wurde genau deshalb sofort zurueckgenommen: Sie haette Berliner Lokale
 * genannt, waehrend sie in Hamburg steht.
 *
 * ORTSNAME STATT KOORDINATEN - bewusst so:
 * Was den Laptop erreicht, ist "Bahrenfelder Straße 12, 22765 Hamburg-Ottensen",
 * keine Zahlenpaare. Die Umrechnung macht Androids eingebauter Geocoder auf dem
 * Handy.
 * EHRLICH DAZU: Dieser Geocoder rechnet nicht rein oertlich - er fragt im
 * Regelfall den Dienst des Geraeteherstellers bzw. Google. Der Gewinn ist
 * trotzdem echt, nur kleiner als "niemand sieht die Koordinaten": Es kommt
 * KEIN zusaetzlicher Anbieter hinzu, denn das Betriebssystem kennt ihre
 * Position ohnehin. Wuerden wir stattdessen Koordinaten an den Server schicken,
 * muesste DER sie bei einem Kartendienst aufloesen - also eine Stelle mehr, die
 * Doreens Aufenthaltsort erfaehrt.
 *
 * ZWEI SCHALTER, beide muessen an sein:
 *   1. die Android-Berechtigung (sie erteilt sie einmal), und
 *   2. der Schalter in der App - damit sie den Ort abstellen kann, ohne die
 *      Berechtigung zurueckzunehmen.
 * Fehlt einer von beiden, geht schlicht kein Feld mit, und der Server verhaelt
 * sich exakt wie vor v0.31 (er fragt dann bei ortsbezogenen Fragen nach).
 *
 * KEINE LATENZ IM GESPRAECH: Ein Positionsfix dauert Sekunden - die duerfen
 * nicht zu den ~5 s bis zum ersten gesprochenen Wort dazukommen. Deshalb wird
 * der Fix ANGESTOSSEN, sobald das Weckwort faellt, und laeuft, waehrend sie
 * ihre Frage spricht. Gesendet wird immer nur das, was zu diesem Zeitpunkt
 * schon im Zwischenspeicher liegt.
 */
object Standort {

    /** Adresstext des letzten Fixes und dessen Zeitpunkt. */
    private const val FELD_TEXT = "standort_text"
    private const val FELD_ZEIT = "standort_zeit"
    /** Der Schalter in der App (siehe oben, Punkt 2). */
    const val FELD_AN = "standort_an"

    /**
     * So alt darf ein Ort hoechstens sein, um noch mitgeschickt zu werden.
     * Zehn Minuten sind ein Kompromiss: Im Auto ist sie in der Zeit ein
     * gutes Stueck weiter, aber eine Empfehlung bezieht sich auf einen
     * Stadtteil, nicht auf eine Hausnummer. Und der Anstoss beim Weckwort
     * sorgt dafuer, dass der Wert im Normalfall Sekunden alt ist.
     */
    private const val HALTBAR_MS = 10 * 60 * 1000L

    /**
     * Juenger als das, wird gar nicht erst neu gemessen. Verhindert, dass
     * mehrere Zurufe hintereinander jeweils das GPS anwerfen.
     */
    private const val AUFFRISCHEN_AB_MS = 60 * 1000L

    /** Laenger wird auf einen frischen Fix nicht gewartet (Hintergrund-Thread,
     *  blockiert nichts) - drinnen ohne Sicht auf den Himmel kommt oft keiner. */
    private const val FIX_TIMEOUT_S = 20L

    @Volatile private var laeuft = false

    private fun prefs(ctx: Context) =
        ctx.getSharedPreferences("jarvis", Context.MODE_PRIVATE)

    /** Hat Android die Ortung erlaubt? Grob reicht - daraus wird immerhin
     *  noch der Stadtteil, und das ist besser als eine Rueckfrage. */
    fun erlaubt(ctx: Context): Boolean {
        fun ja(p: String) =
            ContextCompat.checkSelfPermission(ctx, p) == PackageManager.PERMISSION_GRANTED
        return ja(Manifest.permission.ACCESS_FINE_LOCATION) ||
               ja(Manifest.permission.ACCESS_COARSE_LOCATION)
    }

    /** Der Schalter in der App. Standard: an - sie hat die Ortung ausdruecklich
     *  gewuenscht, und ohne die Berechtigung passiert ohnehin nichts. */
    fun eingeschaltet(ctx: Context): Boolean =
        prefs(ctx).getBoolean(FELD_AN, true)

    fun schalte(ctx: Context, an: Boolean) {
        prefs(ctx).edit().putBoolean(FELD_AN, an).apply()
        if (!an) {
            // Beim Ausschalten den gespeicherten Ort mitentfernen - sonst
            // laege er noch bis zu zehn Minuten auf dem Geraet, obwohl sie
            // die Ortung gerade abgestellt hat.
            prefs(ctx).edit().remove(FELD_TEXT).remove(FELD_ZEIT).apply()
        }
    }

    /**
     * Der Ort, wie er mitgeschickt wird - oder "" , wenn es keinen
     * brauchbaren gibt. Nie blockierend.
     */
    fun text(ctx: Context): String {
        if (!erlaubt(ctx) || !eingeschaltet(ctx)) return ""
        val p = prefs(ctx)
        val zeit = p.getLong(FELD_ZEIT, 0L)
        if (zeit <= 0L) return ""
        if (System.currentTimeMillis() - zeit > HALTBAR_MS) return ""
        return p.getString(FELD_TEXT, "") ?: ""
    }

    /** Zeigt an, wie aktuell der gespeicherte Ort ist - fuer die Statuszeile. */
    fun alterMinuten(ctx: Context): Long {
        val zeit = prefs(ctx).getLong(FELD_ZEIT, 0L)
        if (zeit <= 0L) return -1
        return (System.currentTimeMillis() - zeit) / 60_000L
    }

    /**
     * Stoesst im Hintergrund einen frischen Fix an. Kehrt sofort zurueck.
     * Aufgerufen beim Weckwort (dann laeuft die Messung, waehrend sie
     * spricht), beim Oeffnen der App und vor dem Senden.
     */
    fun anstossen(ctx: Context) {
        if (!erlaubt(ctx) || !eingeschaltet(ctx)) return
        if (laeuft) return
        val alter = System.currentTimeMillis() - prefs(ctx).getLong(FELD_ZEIT, 0L)
        if (alter < AUFFRISCHEN_AB_MS) return
        laeuft = true
        val app = ctx.applicationContext
        thread {
            try {
                messen(app)
            } catch (_: Throwable) {
                // Ein fehlgeschlagener Fix darf nie etwas anderes stoeren -
                // dann geht eben kein Ort mit und Jarvis fragt nach.
            } finally {
                laeuft = false
            }
        }
    }

    private fun messen(ctx: Context) {
        val lm = ctx.getSystemService(Context.LOCATION_SERVICE) as? LocationManager ?: return

        // 1. Sofort da: der zuletzt bekannte Fix. Deckt den haeufigen Fall ab,
        //    dass eine Karten- oder Navigations-App gerade lief.
        letzterBekannter(lm)?.let { speichere(ctx, it) }

        // 2. Frisch messen. Ueberschreibt Schritt 1, wenn es klappt.
        frischerFix(lm)?.let { speichere(ctx, it) }
    }

    private fun letzterBekannter(lm: LocationManager): Location? {
        var best: Location? = null
        for (p in anbieter(lm)) {
            val l = try {
                lm.getLastKnownLocation(p)
            } catch (_: SecurityException) {
                null
            } catch (_: Exception) {
                null
            }
            if (l != null && (best == null || l.time > best!!.time)) best = l
        }
        // Ein uralter Fix ist schlechter als keiner: Er wuerde Jarvis in dem
        // Glauben lassen, er wisse, wo sie ist.
        if (best != null && System.currentTimeMillis() - best!!.time > HALTBAR_MS) return null
        return best
    }

    /**
     * Bittet ALLE verfuegbaren Anbieter gleichzeitig um eine Position und
     * nimmt die erste Antwort. Grund: GPS ist genau, kommt aber in Gebaeuden
     * und in der Stadt manchmal gar nicht; der Netz-Anbieter antwortet dafuer
     * fast sofort. Wer zuerst liefert, gewinnt.
     */
    private fun frischerFix(lm: LocationManager): Location? {
        val liste = anbieter(lm).filter {
            try { lm.isProviderEnabled(it) } catch (_: Exception) { false }
        }
        if (liste.isEmpty()) return null

        val fertig = CountDownLatch(1)
        // AtomicReference statt einer einfachen Variablen: Der Rueckruf kommt
        // auf dem Haupt-Thread an, gelesen wird hier im Hintergrund-Thread.
        // (@Volatile gibt es in Kotlin nur fuer Felder, nicht fuer lokale
        // Variablen - der erste Entwurf haette gar nicht uebersetzt.)
        val ergebnis = java.util.concurrent.atomic.AtomicReference<Location?>(null)
        val hoerer = object : LocationListener {
            override fun onLocationChanged(location: Location) {
                if (ergebnis.compareAndSet(null, location)) fertig.countDown()
            }
            // Auf aelteren Android-Versionen sind diese drei Pflicht.
            override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
            override fun onProviderEnabled(provider: String) {}
            override fun onProviderDisabled(provider: String) {}
        }

        return try {
            for (p in liste) {
                try {
                    lm.requestLocationUpdates(p, 0L, 0f, hoerer, Looper.getMainLooper())
                } catch (_: Exception) {
                }
            }
            fertig.await(FIX_TIMEOUT_S, TimeUnit.SECONDS)
            ergebnis.get()
        } catch (_: Exception) {
            null
        } finally {
            try { lm.removeUpdates(hoerer) } catch (_: Exception) {}
        }
    }

    /** Bevorzugte Reihenfolge: der zusammengefuehrte Anbieter (ab Android 12,
     *  waehlt selbst das Beste), sonst Netz und GPS. */
    private fun anbieter(lm: LocationManager): List<String> {
        val l = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= 31) l += LocationManager.FUSED_PROVIDER
        l += LocationManager.NETWORK_PROVIDER
        l += LocationManager.GPS_PROVIDER
        return l.filter { lm.allProviders.contains(it) }
    }

    private fun speichere(ctx: Context, loc: Location) {
        val adresse = adresse(ctx, loc) ?: return
        // Der ZEITPUNKT DES FIXES zaehlt, nicht der des Speicherns - sonst
        // saehe ein acht Minuten alter "zuletzt bekannter" Ort taufrisch aus.
        val jetzt = System.currentTimeMillis()
        val zeit = if (loc.time in 1..jetzt) loc.time else jetzt
        prefs(ctx).edit()
            .putString(FELD_TEXT, adresse)
            .putLong(FELD_ZEIT, zeit)
            .apply()
    }

    private fun adresse(ctx: Context, loc: Location): String? {
        if (!Geocoder.isPresent()) return null
        return try {
            @Suppress("DEPRECATION")
            val treffer = Geocoder(ctx, Locale.GERMANY)
                .getFromLocation(loc.latitude, loc.longitude, 1)
            val a = treffer?.firstOrNull() ?: return null
            formatiere(
                strasse = a.thoroughfare,
                hausnummer = a.subThoroughfare,
                plz = a.postalCode,
                ort = a.locality ?: a.subAdminArea ?: a.adminArea,
                ortsteil = a.subLocality,
            ).ifEmpty { null }
        } catch (_: Exception) {
            // Kein Netz, kein Geocoder-Dienst: dann geht kein Ort mit.
            // BEWUSST keine Koordinaten als Ersatz - der Server erwartet einen
            // Ortsnamen, und rohe Zahlen waeren genau das, was hier nicht
            // hinausgehen soll.
            null
        }
    }

    /**
     * Baut aus den Bestandteilen einer Adresse den Text, der an Jarvis geht.
     *
     * Bewusst eine REINE Funktion ohne Android-Bezug: Der Laptop hat keine
     * Java-Werkzeugkette, Kotlin laeuft nur im Cloud-Build - und dort kann
     * wenigstens dieser Teil vor dem Bauen geprueft werden
     * (StandortFormatTest, gleiche Idee wie beim Verschluesselungs-Format).
     *
     * Beispiele:
     *   "Am Rötepfuhl 35, 12349 Berlin-Buckow"
     *   "22765 Hamburg-Ottensen"       (Strasse unbekannt)
     *   "Hamburg"                      (nur die Stadt bekannt)
     *   ""                             (nichts Brauchbares)
     */
    fun formatiere(
        strasse: String?,
        hausnummer: String?,
        plz: String?,
        ort: String?,
        ortsteil: String?,
    ): String {
        fun sauber(s: String?): String = s?.trim()?.replace(Regex("\\s+"), " ") ?: ""

        val str = sauber(strasse)
        val nr = sauber(hausnummer)
        val postleitzahl = sauber(plz)
        val stadt = sauber(ort)
        val teil = sauber(ortsteil)

        // Eine Hausnummer ohne Strasse ist wertlos - dann lieber weglassen.
        val strassenteil =
            if (str.isEmpty()) "" else listOf(str, nr).filter { it.isNotEmpty() }.joinToString(" ")

        // "Berlin-Buckow" statt "Berlin, Buckow" - und niemals "Berlin-Berlin"
        // oder "Berlin-Buckow-Buckow", wenn der Geocoder denselben Namen
        // zweimal liefert.
        val stadtteil = when {
            stadt.isEmpty() -> teil
            teil.isEmpty() -> stadt
            stadt.contains(teil, ignoreCase = true) -> stadt
            else -> "$stadt-$teil"
        }

        val ortsteilMitPlz = listOf(postleitzahl, stadtteil).filter { it.isNotEmpty() }.joinToString(" ")

        return listOf(strassenteil, ortsteilMitPlz).filter { it.isNotEmpty() }.joinToString(", ")
    }
}
