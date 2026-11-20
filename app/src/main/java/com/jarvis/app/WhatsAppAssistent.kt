package com.jarvis.app

import android.content.Context
import androidx.core.app.NotificationManagerCompat

/**
 * WhatsApp-Antwortvorschlaege fuer Franks PRIVATE Nummer (20.11.2026).
 *
 * Ablauf: Der WhatsAppListener liest die WhatsApp-Benachrichtigung eines
 * ausgewaehlten Kontakts, holt beim Server einen Antwortvorschlag und zeigt
 * eine EIGENE Benachrichtigung "Senden / Verwerfen". Nur nach "Senden" geht
 * etwas raus - ueber die Schnellantwort der WhatsApp-Benachrichtigung.
 * Nichts passiert automatisch, nichts ohne Freigabe.
 */
object WhatsAppAssistent {
    const val PREF_AN = "wa_vorschlag_an"
    const val PREF_KONTAKTE = "wa_kontakte"

    private fun prefs(ctx: Context) = ctx.getSharedPreferences("jarvis", Context.MODE_PRIVATE)

    fun an(ctx: Context): Boolean = prefs(ctx).getBoolean(PREF_AN, false)

    /** Kontaktnamen, getrennt durch Komma oder Zeilenumbruch - so, wie
     *  WhatsApp sie in der Benachrichtigung anzeigt. */
    fun kontakte(ctx: Context): List<String> =
        (prefs(ctx).getString(PREF_KONTAKTE, "") ?: "")
            .split(',', '\n', ';')
            .map { it.trim() }
            .filter { it.isNotEmpty() }

    /** Nur ausgewaehlte Kontakte: Gross-/Kleinschreibung egal, Name muss
     *  GANZ uebereinstimmen (kein "enthaelt" - "Max" soll nicht "Maxi" treffen). */
    fun zugelassen(liste: List<String>, name: String): Boolean =
        name.isNotBlank() && liste.any { it.equals(name.trim(), ignoreCase = true) }

    /** Letzte Ereignisse (neueste oben), damit man in der App sieht, an
     *  welchem Schritt es haengt - ohne Android-Log. Nur Namen/Status, nie
     *  der Nachrichtentext. */
    fun status(ctx: Context, text: String) {
        try {
            val zeit = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.GERMANY)
                .format(java.util.Date())
            val p = prefs(ctx)
            val alt = (p.getString("wa_status", "") ?: "").lines().filter { it.isNotBlank() }.take(7)
            p.edit().putString("wa_status", (listOf("$zeit $text") + alt).joinToString("\n")).apply()
        } catch (_: Exception) { }
    }

    fun statusText(ctx: Context): String = prefs(ctx).getString("wa_status", "") ?: ""

    fun zugriffErteilt(ctx: Context): Boolean =
        NotificationManagerCompat.getEnabledListenerPackages(ctx).contains(ctx.packageName)
}
