package com.jarvis.app

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.core.app.NotificationCompat
import java.net.URLEncoder

/**
 * Startet Google-Maps-Navigation zu einem gesprochenen Ziel, ausgeloest vom
 * Server ueber das aktion-Feld der Assistant-Antwort (siehe
 * docs/superpowers/specs/2026-08-24-auto-navigation-design.md).
 *
 * Der eigentliche startActivity()-Aufruf passiert NICHT direkt aus dem
 * Hintergrund-Dienst heraus - Android blockiert das seit Version 10 meist.
 * Stattdessen eine Vollbild-Benachrichtigung: der Standardweg, mit dem eine
 * App sich auch aus dem Hintergrund selbst in den Vordergrund holen darf
 * (wie bei einem eingehenden Anruf). Der normale Benachrichtigungsinhalt
 * (Antippen = derselbe Intent) bleibt als Ruckfall bestehen, falls der
 * Vollbild-Start vom System/MIUI unterdrueckt wird.
 */
object Navigation {

    private const val KANAL_ID = "jarvis_navigation"
    private const val BENACHRICHTIGUNG_ID = 2

    /** URL-Kodierung wie android.net.Uri.encode() sie fuer einen
     *  Query-Teil liefern wuerde (Leerzeichen als %20, nicht als "+") -
     *  java.net.URLEncoder kodiert Leerzeichen sonst als "+", das ist die
     *  Formular-Konvention, nicht die URI-Konvention. */
    private fun kodiere(text: String): String =
        URLEncoder.encode(text, "UTF-8").replace("+", "%20")

    /** Reine URI-Zeichenkette fuer den Google-Maps-Navigations-Intent -
     *  ohne jede Android-Abhaengigkeit, deshalb im JVM-Unit-Test pruefbar. */
    fun mapsUri(ziel: String): String = "google.navigation:q=" + kodiere(ziel)

    /** Reine URI-Zeichenkette fuer den generischen Karten-Rueckfall, falls
     *  Google Maps nicht installiert ist. */
    fun geoUri(ziel: String): String = "geo:0,0?q=" + kodiere(ziel)

    fun starten(ctx: Context, ziel: String) {
        if (ziel.isBlank()) return
        val mapsIntent = Intent(Intent.ACTION_VIEW, Uri.parse(mapsUri(ziel)))
            .setPackage("com.google.android.apps.maps")
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val kannMaps = try {
            mapsIntent.resolveActivity(ctx.packageManager) != null
        } catch (_: Exception) {
            false
        }
        val zielIntent = if (kannMaps) mapsIntent else
            Intent(Intent.ACTION_VIEW, Uri.parse(geoUri(ziel)))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        zeigeVollbildBenachrichtigung(ctx, zielIntent, ziel)
    }

    private fun zeigeVollbildBenachrichtigung(ctx: Context, zielIntent: Intent, ziel: String) {
        val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= 26) {
            nm.createNotificationChannel(
                NotificationChannel(KANAL_ID, "Navigation", NotificationManager.IMPORTANCE_HIGH)
            )
        }
        val pending = PendingIntent.getActivity(
            ctx, 0, zielIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val n = NotificationCompat.Builder(ctx, KANAL_ID)
            .setContentTitle("Navigation wird gestartet")
            .setContentText(ziel)
            // Wiederverwendet aus WakeWordService.kt - dort bereits im
            // echten Cloud-Build bestaetigt vorhanden, kein Risiko eines
            // unbekannten Ressourcennamens (kein lokales SDK zum
            // Nachschlagen verfuegbar).
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_NAVIGATION)
            .setFullScreenIntent(pending, true)
            .setContentIntent(pending)
            .setAutoCancel(true)
            .build()
        nm.notify(BENACHRICHTIGUNG_ID, n)
    }
}
