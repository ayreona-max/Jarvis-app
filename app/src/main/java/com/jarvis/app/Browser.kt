package com.jarvis.app

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.core.app.NotificationCompat

/**
 * Oeffnet eine Webseite im Standardbrowser des Handys, ausgeloest vom Server
 * ueber das aktion-Feld der Assistant-Antwort (main.py, browser_seite_oeffnen
 * - server-seitig 07.09.2026 nach demselben Muster wie navigation_starten
 * ergaenzt, siehe docs/superpowers/specs/2026-08-24-auto-navigation-design.md).
 * Der Server liefert bereits eine fertige http(s)-URL (browser_utils.
 * ziel_url() - Adresse, nackte Domain oder Google-Suche), diese Klasse muss
 * also keine eigene Erkennung/Kodierung mehr vornehmen.
 *
 * BEWUSST eine normale Benachrichtigung, KEIN Vollbild-Intent (siehe
 * Navigation.kt fuer die ausfuehrliche Begruendung): Der urspruengliche
 * Versuch, komplett freihaendig aus dem Hintergrund zu starten, hat auf
 * Franks Xiaomi 14 Ultra nachweislich nicht funktioniert - nur Antippen der
 * Benachrichtigung oeffnete das Ziel. Fuers Browser-Oeffnen ist Antippen
 * ohnehin die naheliegende, unaufdringlichere Wahl (anders als bei der
 * Auto-Navigation gibt es hier kein "freihaendig im Auto"-Bedarf).
 */
object Browser {

    private const val KANAL_ID = "jarvis_browser"
    private const val BENACHRICHTIGUNG_ID = 3

    fun starten(ctx: Context, url: String) {
        if (url.isBlank()) return
        val intent = try {
            Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        } catch (_: Exception) {
            // Ungueltige URI vom Server - lieber nichts tun als abzustuerzen.
            return
        }
        zeigeBenachrichtigung(ctx, intent, url)
    }

    private fun zeigeBenachrichtigung(ctx: Context, zielIntent: Intent, url: String) {
        val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= 26) {
            nm.createNotificationChannel(
                NotificationChannel(KANAL_ID, "Browser", NotificationManager.IMPORTANCE_HIGH)
            )
        }
        val pending = PendingIntent.getActivity(
            ctx, 1, zielIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val n = NotificationCompat.Builder(ctx, KANAL_ID)
            .setContentTitle("Seite bereit")
            .setContentText("$url – antippen zum Öffnen")
            // Wiederverwendet aus WakeWordService.kt/Navigation.kt - dort
            // bereits im echten Cloud-Build bestaetigt vorhanden, kein
            // Risiko eines unbekannten Ressourcennamens (kein lokales SDK
            // zum Nachschlagen verfuegbar).
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_RECOMMENDATION)
            .setContentIntent(pending)
            .setAutoCancel(true)
            .build()
        nm.notify(BENACHRICHTIGUNG_ID, n)
    }
}
