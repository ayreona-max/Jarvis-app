package com.jarvis.app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat

/**
 * Startet das "Hey Jarvis"-Lauschen von selbst wieder, nachdem Android den
 * Dienst beendet hat – ohne dass die App geoeffnet werden muss.
 *
 * Zwei Faelle, die beide real aufgetreten sind bzw. auftreten:
 *  - BOOT_COMPLETED: Handy neu gestartet.
 *  - MY_PACKAGE_REPLACED: die App wurde aktualisiert. Genau das beendete am
 *    25.07.2026 stillschweigend den Dienst – der Schalter in der App stand
 *    weiter auf "aktiv", aber auf "Hey Jarvis" kam nichts mehr, und beim
 *    Server ging keine einzige Anfrage ein.
 *
 * Nur wenn Doreen das Lauschen vorher eingeschaltet hatte (Merker
 * "wake_aktiv"), wird neu gestartet – ein bewusst gestopptes Lauschen
 * bleibt gestoppt.
 */
class NeustartEmpfaenger : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent?) {
        val aktion = intent?.action ?: return
        if (aktion != Intent.ACTION_BOOT_COMPLETED &&
            aktion != Intent.ACTION_MY_PACKAGE_REPLACED
        ) return

        val prefs = context.getSharedPreferences("jarvis", Context.MODE_PRIVATE)
        if (!prefs.getBoolean("wake_aktiv", false)) return

        try {
            ContextCompat.startForegroundService(
                context, Intent(context, WakeWordService::class.java)
            )
        } catch (_: Exception) {
            // Startet Android den Dienst in dieser Situation nicht (z. B.
            // Herstellerbeschraenkung), repariert es spaetestens das
            // Oeffnen der App (siehe MainActivity.onResume).
        }
    }
}
