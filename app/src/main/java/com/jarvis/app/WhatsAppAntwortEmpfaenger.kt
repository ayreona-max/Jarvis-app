package com.jarvis.app

import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** Knoepfe "Senden" / "Verwerfen" der Vorschlags-Benachrichtigung. */
class WhatsAppAntwortEmpfaenger : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        val id = intent?.getIntExtra(WhatsAppListener.EXTRA_ID, 0) ?: return
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        when (intent.action) {
            WhatsAppListener.AKTION_SENDEN -> {
                val fehler = WhatsAppListener.senden(context, id)
                WhatsAppListener.meldeErgebnis(
                    context, id,
                    if (fehler == null) "Antwort abgeschickt." else fehler
                )
            }
            WhatsAppListener.AKTION_VERWERFEN -> {
                WhatsAppListener.offen.remove(id)
                nm.cancel(id)
            }
        }
    }
}
