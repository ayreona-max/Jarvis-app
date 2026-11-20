package com.jarvis.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.RemoteInput
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log
import androidx.core.app.NotificationCompat
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/**
 * Liest eingehende WhatsApp-Einzelchat-Benachrichtigungen ausgewaehlter
 * Kontakte, holt einen Antwortvorschlag vom Server und zeigt ihn zur
 * Freigabe (siehe WhatsAppAssistent). Sendet NIE von selbst.
 */
class WhatsAppListener : NotificationListenerService() {

    /** Was zum Antworten noetig ist - nur im Speicher, nie auf der Platte. */
    class Offen(val aktion: Notification.Action, val eingabe: RemoteInput, val vorschlag: String)

    companion object {
        private const val TAG = "WhatsAppListener"
        private const val KANAL = "jarvis_whatsapp"
        const val AKTION_SENDEN = "com.jarvis.app.WA_SENDEN"
        const val AKTION_VERWERFEN = "com.jarvis.app.WA_VERWERFEN"
        const val EXTRA_ID = "wa_id"

        val offen = ConcurrentHashMap<Int, Offen>()
        private val zuletzt = ConcurrentHashMap<String, Long>()

        private val client = OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .build()

        /** null = abgeschickt, sonst der Grund, warum es nicht ging. */
        fun senden(ctx: Context, id: Int): String? {
            val o = offen.remove(id)
                ?: return "Die App wurde zwischendurch beendet - bitte direkt in WhatsApp antworten."
            return try {
                val intent = Intent()
                val daten = Bundle().apply { putCharSequence(o.eingabe.resultKey, o.vorschlag) }
                RemoteInput.addResultsToIntent(arrayOf(o.eingabe), intent, daten)
                if (Build.VERSION.SDK_INT >= 28) {
                    RemoteInput.setResultsSource(intent, RemoteInput.SOURCE_FREE_FORM_INPUT)
                }
                o.aktion.actionIntent.send(ctx, 0, intent)
                null
            } catch (e: PendingIntent.CanceledException) {
                Log.w(TAG, "Senden: Antwort-Knopf von WhatsApp nicht mehr gueltig: $e")
                "WhatsApp hat die Nachricht schon weggeraeumt (Chat geoeffnet?) - bitte direkt antworten."
            } catch (e: Exception) {
                Log.w(TAG, "Senden fehlgeschlagen: $e")
                "Senden ging nicht: ${e.javaClass.simpleName}"
            }
        }

        /** Rueckmeldung als Benachrichtigung statt Toast - Toasts aus dem
         *  Hintergrund unterdrueckt z. B. Xiaomi gern. */
        fun meldeErgebnis(ctx: Context, id: Int, text: String) {
            val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            if (Build.VERSION.SDK_INT >= 26) {
                nm.createNotificationChannel(
                    NotificationChannel(KANAL, "WhatsApp-Vorschläge", NotificationManager.IMPORTANCE_HIGH)
                )
            }
            val n = NotificationCompat.Builder(ctx, KANAL)
                .setSmallIcon(android.R.drawable.ic_dialog_email)
                .setContentTitle("Jarvis")
                .setContentText(text)
                .setStyle(NotificationCompat.BigTextStyle().bigText(text))
                .setTimeoutAfter(10_000)
                .setAutoCancel(true)
                .build()
            try { nm.notify(id, n) } catch (e: SecurityException) { }
        }
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        val s = sbn ?: return
        if (s.packageName != "com.whatsapp" && s.packageName != "com.whatsapp.w4b") return
        if (!WhatsAppAssistent.an(this)) { WhatsAppAssistent.status(this, "WhatsApp-Meldung gesehen, Schalter aus"); return }
        val n = s.notification ?: return
        if (n.flags and Notification.FLAG_GROUP_SUMMARY != 0) return

        // Gruppen sind ausgenommen: nur Einzelchats.
        val stil = NotificationCompat.MessagingStyle.extractMessagingStyleFromNotification(n)
        if (stil?.isGroupConversation == true) {
            WhatsAppAssistent.status(this, "Gruppen-Meldung ignoriert"); return
        }

        val extras = n.extras
        val absender = (extras.getCharSequence(Notification.EXTRA_TITLE) ?: "").toString().trim()
        val nachricht = (stil?.messages?.lastOrNull()?.text
            ?: extras.getCharSequence(Notification.EXTRA_TEXT) ?: "").toString().trim()
        if (absender.isEmpty() || nachricht.isEmpty()) return
        if (!WhatsAppAssistent.zugelassen(WhatsAppAssistent.kontakte(this), absender)) {
            WhatsAppAssistent.status(this, "Meldung von \"$absender\": nicht in der Kontaktliste"); return
        }

        // WhatsApp meldet dieselbe Nachricht gern mehrfach (Update der
        // Benachrichtigung): gleiche Nachricht binnen 30 s nur einmal.
        val schluessel = "$absender|$nachricht"
        val jetzt = System.currentTimeMillis()
        val vorher = zuletzt.put(schluessel, jetzt)
        if (vorher != null && jetzt - vorher < 30_000) return
        zuletzt.entries.removeIf { jetzt - it.value > 120_000 }

        // Schnellantwort suchen - ohne sie laesst sich nichts senden.
        val aktion = n.actions?.firstOrNull { !it.remoteInputs.isNullOrEmpty() }
        if (aktion == null) {
            WhatsAppAssistent.status(this, "\"$absender\": WhatsApp bietet keine Schnellantwort an"); return
        }
        WhatsAppAssistent.status(this, "\"$absender\": erkannt, hole Vorschlag ...")
        val eingabe = aktion.remoteInputs.first()

        val ctx = applicationContext
        thread {
            val vorschlag = try { holeVorschlag(ctx, absender, nachricht) } catch (e: Exception) {
                Log.w(TAG, "Vorschlag fehlgeschlagen: $e")
                WhatsAppAssistent.status(ctx, "Vorschlag-Abruf fehlgeschlagen: ${e.javaClass.simpleName}"); ""
            }
            if (vorschlag.isBlank()) {
                WhatsAppAssistent.status(ctx, "kein Vorschlag erhalten (Server/Schluessel/Adresse?)"); return@thread
            }
            WhatsAppAssistent.status(ctx, "Vorschlag da, Benachrichtigung gezeigt")
            val id = absender.lowercase().hashCode()
            offen[id] = Offen(aktion, eingabe, vorschlag)
            zeigeVorschlag(ctx, id, absender, nachricht, vorschlag)
        }
    }

    private fun holeVorschlag(ctx: Context, absender: String, nachricht: String): String {
        val prefs = ctx.getSharedPreferences("jarvis", Context.MODE_PRIVATE)
        val base = (prefs.getString("url", "") ?: "").trim().trimEnd('/')
        val key = prefs.getString("key", "") ?: ""
        if (base.isEmpty() || key.isEmpty()) return ""
        val e2e = Krypto.aktiv(ctx)
        val body = FormBody.Builder()
            .add("key", key)
            .add("absender", if (e2e) Krypto.verschluesselnText(ctx, absender) else absender)
            .add("text", if (e2e) Krypto.verschluesselnText(ctx, nachricht) else nachricht)
        if (e2e) body.add("e2e", "1")
        val req = Request.Builder()
            .url("$base/whatsapp-vorschlag")
            .addHeader("ngrok-skip-browser-warning", "true")
            .post(body.build())
            .build()
        client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) return ""
            val js = Krypto.auspacken(ctx, JSONObject(resp.body?.string() ?: return ""))
            return js.optString("vorschlag", "").trim()
        }
    }

    private fun zeigeVorschlag(ctx: Context, id: Int, absender: String, nachricht: String, vorschlag: String) {
        val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= 26) {
            nm.createNotificationChannel(
                NotificationChannel(KANAL, "WhatsApp-Vorschläge", NotificationManager.IMPORTANCE_HIGH)
            )
        }
        fun knopf(aktion: String, code: Int): PendingIntent = PendingIntent.getBroadcast(
            ctx, id + code,
            // Ausdruecklich an den Empfaenger adressieren (Komponente): Der
            // Empfaenger hat im Manifest keinen Intent-Filter, ein Broadcast
            // nur mit Aktion+Paket wuerde ihn nie erreichen - "Senden" blieb
            // deshalb lautlos wirkungslos (20.11.2026).
            Intent(ctx, WhatsAppAntwortEmpfaenger::class.java)
                .setAction(aktion).putExtra(EXTRA_ID, id),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val text = "$absender: $nachricht\n→ $vorschlag"
        val n = NotificationCompat.Builder(ctx, KANAL)
            .setSmallIcon(android.R.drawable.ic_dialog_email)
            .setContentTitle("Antwort an $absender senden?")
            .setContentText(vorschlag)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .addAction(0, "Senden", knopf(AKTION_SENDEN, 1))
            .addAction(0, "Verwerfen", knopf(AKTION_VERWERFEN, 2))
            .setAutoCancel(false)
            .build()
        try { nm.notify(id, n) } catch (e: SecurityException) { /* keine Benachrichtigungs-Erlaubnis */ }
    }
}
