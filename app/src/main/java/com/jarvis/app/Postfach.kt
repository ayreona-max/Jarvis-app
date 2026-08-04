package com.jarvis.app

import android.content.Context
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Postfach: Nachrichten, die Jarvis VON SICH AUS schickt (fertiger
 * Manus-Auftrag, Briefings) - seit v0.22 der Ort dafuer, an Telegrams Stelle.
 *
 * Bewusst OHNE Push-Dienst: Der Weckwort-Dienst laeuft ohnehin dauerhaft im
 * Vordergrund und fragt beim Server nach. Das erspart ein Firebase-Projekt,
 * neue Zugangsdaten und einen weiteren Dienst; die Verzoegerung von einer
 * Abfragerunde ist bei den Anlaessen (ein Manus-Auftrag laeuft Stunden, ein
 * Briefing kommt zu einer festen Zeit) ohne Bedeutung.
 *
 * Zustellung in ZWEI Phasen: Erst wenn eine Nachricht hier lokal gespeichert
 * ist, wird sie dem Server bestaetigt. Bricht der Abruf vorher ab, kommt sie
 * beim naechsten Mal erneut - es geht nichts verloren.
 */
object Postfach {

    private const val PREFS = "jarvis"
    private const val SCHLUESSEL = "postfach"
    /** So viele Nachrichten bleiben lokal liegen, aelteste fliegen raus. */
    private const val MAX_EINTRAEGE = 30

    data class Nachricht(
        val id: Long,
        val art: String,
        val titel: String,
        val text: String,
        val audioDatei: String?,
        val zeit: Long,
    )

    fun alle(ctx: Context): List<Nachricht> {
        val roh = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(SCHLUESSEL, "[]") ?: "[]"
        return try {
            val arr = JSONArray(roh)
            (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                Nachricht(
                    id = o.optLong("id"),
                    art = o.optString("art"),
                    titel = o.optString("titel"),
                    text = o.optString("text"),
                    audioDatei = if (o.isNull("audio")) null else o.optString("audio"),
                    zeit = o.optLong("zeit"),
                )
            }.sortedByDescending { it.zeit }
        } catch (e: Exception) {
            emptyList()
        }
    }

    private fun speichern(ctx: Context, liste: List<Nachricht>) {
        val arr = JSONArray()
        liste.sortedByDescending { it.zeit }.take(MAX_EINTRAEGE).forEach { n ->
            arr.put(JSONObject().apply {
                put("id", n.id)
                put("art", n.art)
                put("titel", n.titel)
                put("text", n.text)
                if (n.audioDatei == null) put("audio", JSONObject.NULL)
                else put("audio", n.audioDatei)
                put("zeit", n.zeit)
            })
        }
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(SCHLUESSEL, arr.toString()).apply()
    }

    /**
     * Loescht EINE Nachricht aus der Anzeige - samt ihrer lokal abgelegten
     * Tondatei, sonst blieben verwaiste MP3s im Cache liegen.
     *
     * WICHTIG: Das betrifft ausschliesslich die ANZEIGE auf dem Handy. Das
     * Gedaechtnis auf dem Rechner (transcripts.db + Chroma) bleibt unberuehrt
     * - sonst wuerde Jarvis genau das vergessen, wofuer er da ist. Auf dem
     * Server ist die Nachricht ohnehin schon erledigt: Sie wurde beim Abholen
     * bestaetigt, und ihre serverseitige Tondatei wurde dabei geloescht.
     * Es braucht hier also KEINEN Netzaufruf.
     */
    fun loeschen(ctx: Context, id: Long) {
        val liste = alle(ctx)
        liste.firstOrNull { it.id == id }?.audioDatei?.let { pfad ->
            try {
                File(pfad).delete()
            } catch (_: Exception) {
            }
        }
        speichern(ctx, liste.filter { it.id != id })
    }

    /** Leert die Anzeige komplett - ebenfalls nur lokal, siehe [loeschen]. */
    fun alleLoeschen(ctx: Context) {
        alle(ctx).forEach { n ->
            n.audioDatei?.let { pfad ->
                try {
                    File(pfad).delete()
                } catch (_: Exception) {
                }
            }
        }
        speichern(ctx, emptyList())
    }

    /**
     * Holt neue Nachrichten vom Server, speichert sie lokal und bestaetigt
     * sie erst DANACH. Gibt die neu hinzugekommenen zurueck (fuer die
     * Benachrichtigung); bei Netzfehlern eine leere Liste - der naechste
     * Durchgang holt es nach.
     */
    fun abholen(ctx: Context, client: OkHttpClient, jetzt: Long): List<Nachricht> {
        val prefs = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val basis = (prefs.getString("url", "") ?: "").trim().trimEnd('/')
        val key = prefs.getString("key", "") ?: ""
        if (basis.isEmpty() || key.isEmpty()) return emptyList()

        // Briefings enthalten Termine, Postfach und Depotstand - genau das,
        // was nicht blank durch einen fremden Tunnel laufen soll.
        val e2e = Krypto.aktiv(ctx)
        val anfrage = Request.Builder()
            .url("$basis/app-nachrichten?key=" + java.net.URLEncoder.encode(key, "UTF-8")
                 + (if (e2e) "&e2e=1" else ""))
            .addHeader("ngrok-skip-browser-warning", "true")
            .get()
            .build()

        val neue = mutableListOf<Nachricht>()
        try {
            client.newCall(anfrage).execute().use { antwort ->
                if (!antwort.isSuccessful) return emptyList()
                val json = Krypto.auspacken(ctx, JSONObject(antwort.body?.string() ?: "{}"))
                val arr = json.optJSONArray("nachrichten") ?: return emptyList()
                for (i in 0 until arr.length()) {
                    val o = arr.getJSONObject(i)
                    val id = o.optLong("id")
                    // Ton (z. B. ein Briefing) lokal ablegen, damit er auch
                    // ohne Verbindung abspielbar bleibt.
                    var audioPfad: String? = null
                    if (!o.isNull("audio_base64")) {
                        try {
                            val bytes = android.util.Base64.decode(
                                o.optString("audio_base64"), android.util.Base64.DEFAULT
                            )
                            val datei = File(ctx.cacheDir, "nachricht_$id.mp3")
                            datei.writeBytes(bytes)
                            audioPfad = datei.absolutePath
                        } catch (_: Exception) {
                        }
                    }
                    neue.add(
                        Nachricht(
                            id = id,
                            art = o.optString("art"),
                            titel = o.optString("titel"),
                            text = o.optString("text"),
                            audioDatei = audioPfad,
                            zeit = jetzt + i,   // Reihenfolge erhalten
                        )
                    )
                }
            }
        } catch (e: Exception) {
            return emptyList()
        }
        if (neue.isEmpty()) return emptyList()

        // ERST speichern ...
        val vorhandene = alle(ctx).filter { alt -> neue.none { it.id == alt.id } }
        speichern(ctx, vorhandene + neue)

        // ... DANN bestaetigen. Schlaegt das fehl, kommen sie noch einmal -
        // doppelt ist besser als verloren, und das Speichern oben wirft
        // Dubletten anhand der id ohnehin raus.
        try {
            val body = FormBody.Builder()
                .add("key", key)
                .add("ids", neue.joinToString(",") { it.id.toString() })
                .build()
            client.newCall(
                Request.Builder()
                    .url("$basis/app-nachrichten-bestaetigen")
                    .addHeader("ngrok-skip-browser-warning", "true")
                    .post(body)
                    .build()
            ).execute().close()
        } catch (_: Exception) {
        }
        return neue
    }
}
