package com.jarvis.app

import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.widget.Toast

/** Knoepfe "Senden" / "Verwerfen" der Vorschlags-Benachrichtigung. */
class WhatsAppAntwortEmpfaenger : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        val id = intent?.getIntExtra(WhatsAppListener.EXTRA_ID, 0) ?: return
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        when (intent.action) {
            WhatsAppListener.AKTION_SENDEN -> {
                if (WhatsAppListener.senden(context, id)) {
                    Toast.makeText(context, "Antwort gesendet", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(
                        context,
                        "Antwort nicht mehr möglich – bitte direkt in WhatsApp antworten",
                        Toast.LENGTH_LONG
                    ).show()
                }
                nm.cancel(id)
            }
            WhatsAppListener.AKTION_VERWERFEN -> {
                WhatsAppListener.offen.remove(id)
                nm.cancel(id)
            }
        }
    }
}
