package com.jarvis.app

import android.content.Context
import android.media.MediaPlayer
import android.util.Base64
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.File
import java.util.UUID
import java.util.concurrent.CountDownLatch

/**
 * Satzweises Antworten ("erst reden, dann zu Ende denken").
 *
 * Der Server (/assistant-stream) schickt die Antwort nicht mehr als EIN
 * fertiges Paket, sondern als Strom von JSON-Zeilen: sobald der erste Satz
 * gedacht UND vertont ist, geht er los, der Rest folgt waehrend des Hoerens.
 * Server-seitig gemessen: erster Ton nach ~4,9 s statt ~9,2 s.
 *
 * Zeilen-Typen: transcript | chunk | done | error
 *
 * Faellt irgendetwas aus, BEVOR der erste Block da war, meldet ask() false –
 * der Aufrufer nutzt dann den bewaehrten /assistant-Weg. Das Streaming kann
 * also nie zu "gar keiner Antwort" fuehren.
 */
object StreamClient {

    /** Spielt eintreffende Audio-Bloecke LUECKENLOS nacheinander ab. */
    class AudioQueue(private val cacheDir: File) {
        private val warteschlange = ArrayDeque<File>()
        private var spieler: MediaPlayer? = null
        private var laeuft = false
        private var zaehler = 0
        private var stromFertig = false
        private val fertig = CountDownLatch(1)

        @Synchronized
        fun hinzufuegen(bytes: ByteArray) {
            val datei = File(cacheDir, "stream_${zaehler++}.mp3")
            datei.writeBytes(bytes)
            warteschlange.addLast(datei)
            if (!laeuft) starteNaechsten()
        }

        @Synchronized
        private fun starteNaechsten() {
            val datei = warteschlange.removeFirstOrNull()
            if (datei == null) {
                laeuft = false
                // Nichts mehr in der Warteschlange UND der Server ist fertig
                // -> die Antwort ist vollstaendig gesprochen.
                if (stromFertig) fertig.countDown()
                return
            }
            laeuft = true
            try {
                val mp = MediaPlayer()
                mp.setDataSource(datei.absolutePath)
                mp.setOnCompletionListener {
                    it.release()
                    datei.delete()
                    starteNaechsten()
                }
                mp.prepare()
                mp.start()
                spieler = mp
            } catch (e: Exception) {
                // Ein kaputter Block darf den Rest nicht aufhalten.
                datei.delete()
                starteNaechsten()
            }
        }

        /** Der Server hat alles geschickt – ab jetzt darf die Warteschlange
         *  leerlaufen und awaitEnde() zurueckkehren. */
        @Synchronized
        fun stromBeendet() {
            stromFertig = true
            if (!laeuft && warteschlange.isEmpty()) fertig.countDown()
        }

        /** Blockiert, bis alles abgespielt ist (fuer den Weckwort-Dienst,
         *  der erst danach weiterlauschen darf). */
        fun awaitEnde(maxMillis: Long = 180_000) {
            fertig.await(maxMillis, java.util.concurrent.TimeUnit.MILLISECONDS)
        }

        @Synchronized
        fun abbrechen() {
            try { spieler?.release() } catch (_: Exception) {}
            spieler = null
            warteschlange.forEach { it.delete() }
            warteschlange.clear()
            laeuft = false
            fertig.countDown()
        }
    }

    /**
     * Schickt Text ODER Sprachaufnahme an /assistant-stream und spielt die
     * Antwort satzweise ab.
     *
     * @return true, wenn mindestens ein Block ankam (Erfolg). false heisst:
     *         der Aufrufer soll auf den klassischen /assistant-Weg zurueckfallen.
     */
    fun ask(
        ctx: Context,
        client: OkHttpClient,
        base: String,
        key: String,
        text: String?,
        audio: File?,
        cacheDir: File,
        blockiereBisGesprochen: Boolean,
        image: File? = null,
        /** true, wenn die Aufnahme aus dem Nachfass-Fenster stammt: Dann wird
         *  ein "nichts verstanden" stillschweigend verworfen, statt es
         *  vorzulesen - sie hat Jarvis in dem Moment ja nicht gerufen. */
        stillBeiUnverstanden: Boolean = false,
        /** Aufenthaltsort als Adresstext (v0.31), leer = wie bisher. Der
         *  Server nimmt dann ortsbezogene Fragen auf DIESEN Ort statt
         *  nachzufragen. */
        standort: String = "",
        onTranscript: (String) -> Unit = {},
        onText: (String) -> Unit = {},
    ): Boolean {
        val requestId = UUID.randomUUID().toString()
        // Verschluesselt wird nur, wenn ein Schluessel hinterlegt ist. Ohne
        // ihn laeuft alles wie bisher - so legt ein vergessener Schluessel
        // den Assistenten nicht lahm.
        val e2e = Krypto.aktiv(ctx)
        val body = MultipartBody.Builder().setType(MultipartBody.FORM)
            .addFormDataPart("key", key)
            .addFormDataPart("request_id", requestId)
        if (e2e) body.addFormDataPart("e2e", "1")
        if (!text.isNullOrEmpty()) {
            body.addFormDataPart("text", if (e2e) Krypto.verschluesselnText(ctx, text) else text)
        }
        // Der Ort ist so schuetzenswert wie das gesprochene Wort und laeuft
        // deshalb durch dieselbe Verschluesselung.
        if (standort.isNotEmpty()) {
            body.addFormDataPart(
                "standort",
                if (e2e) Krypto.verschluesselnText(ctx, standort) else standort
            )
        }
        // Dateiname/Typ folgen der ENDUNG: Der Sprechen-Knopf liefert .m4a
        // (MediaRecorder), der Weckwort-Dienst seit v0.19 .wav (direkt aus
        // dem Mikrofonstrom). Der Server leitet das Format aus dem Namen ab -
        // ein fest verdrahtetes ".m4a" wuerde die WAV-Datei falsch benennen.
        // Der Name bleibt auch verschluesselt lesbar; er ist Metadatum, kein
        // Inhalt (siehe Krypto.kt).
        if (audio != null) {
            val wav = audio.name.endsWith(".wav", ignoreCase = true)
            val typ = (if (wav) "audio/wav" else "audio/mp4").toMediaType()
            body.addFormDataPart(
                "audio",
                if (wav) "aufnahme.wav" else "aufnahme.m4a",
                if (e2e) Krypto.verschluesselnRoh(ctx, audio.readBytes()).toRequestBody(typ)
                else audio.asRequestBody(typ)
            )
        }
        // Ein Foto laeuft serverseitig NICHT als Wort-fuer-Wort-Strom (Vision
        // braucht die komplette Auswertung inkl. Werkzeugen), aber die
        // fertige Antwort wird blockweise vertont – der Ton startet dadurch
        // deutlich frueher als bei der Vertonung des ganzen Textes.
        if (image != null) body.addFormDataPart(
            "image", "foto.jpg",
            if (e2e) Krypto.verschluesselnRoh(ctx, image.readBytes())
                .toRequestBody("image/jpeg".toMediaType())
            else image.asRequestBody("image/jpeg".toMediaType())
        )

        val request = Request.Builder()
            .url("$base/assistant-stream")
            .addHeader("ngrok-skip-browser-warning", "true")
            .post(body.build())
            .build()

        val queue = AudioQueue(cacheDir)
        var bloecke = 0
        val gesamttext = StringBuilder()

        try {
            client.newCall(request).execute().use { resp ->
                if (!resp.isSuccessful) return false
                val quelle = resp.body?.source() ?: return false
                while (true) {
                    val zeile = quelle.readUtf8Line() ?: break
                    if (zeile.isBlank()) continue
                    // Jede Zeile wird EINZELN ausgepackt - so bleibt das
                    // satzweise Sprechen erhalten. Eine Zeile, die sich nicht
                    // entschluesseln laesst, wird uebersprungen statt den
                    // ganzen Strom abzubrechen.
                    val ev = try { Krypto.auspacken(ctx, JSONObject(zeile)) }
                             catch (e: Exception) { continue }
                    when (ev.optString("type")) {
                        "transcript" -> onTranscript(ev.optString("text", ""))
                        "chunk" -> {
                            // Hat Whisper nichts verstanden, sagt der Server das
                            // mit "anlass": "nichts_verstanden". Kam die Aufnahme
                            // aus dem NACHFASS-Fenster, hat Doreen Jarvis gar
                            // nicht gerufen - das Fenster stand nur offen und hat
                            // ein Geraeusch aufgeschnappt. Dann bleibt er still
                            // und lauscht weiter; sonst wuerde er jedes Rascheln
                            // im Raum kommentieren.
                            // WICHTIG: bloecke wird trotzdem hochgezaehlt, damit
                            // der Aufrufer NICHT auf /assistant zurueckfaellt -
                            // sonst liefe dieselbe Aufnahme ein zweites Mal durch
                            // Whisper (am 29.07.2026 als Ursache fuer spuerbar
                            // spaete Antworten nachgewiesen).
                            val unverstanden = ev.optString("anlass") == "nichts_verstanden"
                            if (unverstanden && stillBeiUnverstanden) {
                                bloecke++
                                continue
                            }
                            val stueck = ev.optString("text", "")
                            if (stueck.isNotEmpty()) {
                                if (gesamttext.isNotEmpty()) gesamttext.append(" ")
                                gesamttext.append(stueck)
                                onText(gesamttext.toString())
                            }
                            val b64 = if (ev.isNull("audio_base64")) null
                                      else ev.optString("audio_base64", null)
                            if (!b64.isNullOrEmpty()) {
                                queue.hinzufuegen(Base64.decode(b64, Base64.DEFAULT))
                            }
                            bloecke++
                        }
                        "done" -> {
                            val voll = ev.optString("text", "")
                            if (voll.isNotEmpty()) onText(voll)
                        }
                        "error" -> {
                            // Nur wenn noch NICHTS ankam, lohnt der Rueckfall.
                            if (bloecke == 0) { queue.abbrechen(); return false }
                        }
                    }
                }
            }
        } catch (e: Exception) {
            if (bloecke == 0) { queue.abbrechen(); return false }
            // Mitten im Strom abgebrochen: das bereits Gesprochene bleibt.
        }

        queue.stromBeendet()
        if (bloecke == 0) return false
        if (blockiereBisGesprochen) queue.awaitEnde()
        return true
    }
}
