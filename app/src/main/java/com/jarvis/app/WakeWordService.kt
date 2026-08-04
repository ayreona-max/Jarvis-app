package com.jarvis.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.MediaPlayer
import android.media.MediaRecorder
import android.media.ToneGenerator
import android.os.Build
import android.os.IBinder
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.util.Base64
import androidx.core.app.NotificationCompat
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/**
 * Etappe 3: "Hey Jarvis" im Hintergrund - seit v0.6 ueber openWakeWord
 * statt Porcupine (Picovoice hat sein kostenloses Konto zum 30.06.2026
 * abgeschafft; openWakeWord braucht weder Konto noch AccessKey).
 *
 * Dauerhafter Vordergrund-Dienst (sichtbare Benachrichtigung), der lokal
 * auf "Hey Jarvis" lauscht. Kein Ton verlaesst das Handy, bis das Weckwort
 * faellt. Ablauf danach:
 *   Piep -> Frage aufnehmen (Stille-Erkennung, max. 10 s) -> Bestaetigungs-
 *   Piep -> Upload an /assistant (mit Retry/Idempotenz wie in der App) ->
 *   Jarvis' Stimmantwort abspielen -> weiterlauschen.
 * Waehrend Aufnahme und Antwort ist das Lauschen PAUSIERT (Mikrofon wird
 * fuer die Aufnahme gebraucht, und Jarvis' eigene Stimme darf das Weckwort
 * nicht erneut ausloesen); danach werden die Erkennungs-Puffer geleert.
 */
class WakeWordService : Service() {

    companion object {
        private const val SAMPLE_RATE = 16000
        // Ab diesem Score gilt das Weckwort als erkannt (openWakeWord-
        // Standard 0,5; hoeher = weniger Fehlalarme, dafuer muss man
        // deutlicher sprechen).
        // Am 26.07.2026 von 0,5 auf 0,65 angehoben: An diesem Tag loeste das
        // Weckwort NEUNMAL aus, waehrend Doreen sich mit anderen Menschen
        // unterhielt - Jarvis nahm Gespraechsfetzen auf und antwortete darauf
        // ("Wenn du vertretest, hast du ja also deinem Friseur"). Alle neun
        // Faelle sind im Transkript des Orchestrators belegt.
        // Am 29.07.2026 auf 0,55 zurueckgenommen: 0,65 war zu streng. Doreen
        // musste "Hey Jarvis" mehrfach wiederholen, und einmal loeste es erst
        // MITTEN in ihrem naechsten Satz aus - im Transkript kam nur noch
        // "DEKRA, Hauptuntersuchung, Hyundai Kona, Parqet," an, ein Fetzen
        // ohne Anfang. Das sah wie ein Fehlalarm aus, war aber ein VERSPAETETER
        // Treffer: dasselbe Problem wie das Nicht-Ansprechen, nicht das
        // Gegenteil davon.
        //
        // BESTAETIGUNGEN bleibt bei 2 - das ist ohnehin der wirksamere Filter
        // (ein Fehlalarm ist ein einzelner Zufallstreffer, ein echtes
        // "Hey Jarvis" haelt den Wert ueber mehrere Bloecke hoch). 0,55 mit
        // zwei Bestaetigungen ist strenger als die urspruenglichen 0,5 ohne,
        // mit denen die neun Fehlalarme vom 26.07. entstanden.
        private const val SCHWELLE = 0.55f
        // Zusaetzlich muss der Wert in MEHREREN AUFEINANDERFOLGENDEN Bloecken
        // ueber der Schwelle liegen. Ein einzelner Ausschlag reicht nicht -
        // genau so sehen Fehlalarme aus (ein Laut im Gespraech trifft zufaellig
        // das Muster). Ein echtes "Hey Jarvis" haelt den Wert dagegen ueber
        // mehrere Bloecke hoch: Die Laptop-Verifikation vor der Kotlin-
        // Portierung zeigte 0,999 durchgehend, nicht als einzelne Spitze.
        // Ein Block sind 80 ms, zwei Bloecke also 160 ms - fuer den Zuruf
        // unmerklich, fuer einen Zufallstreffer eine hohe Huerde.
        private const val BESTAETIGUNGEN = 2
        // Spuerbare Rueckmeldung beim Zuruf (Vibration). Auf false gesetzt,
        // siehe Begruendung an ton().
        private const val RUECKMELDUNG = false
        // VORLAUF (v0.19): So viel Ton VOR dem Weckwort-Treffer wird der
        // Aufnahme vorangestellt - 1,0 s bei 16 kHz.
        //
        // Grund: Bis v0.18 wurde nach dem Weckwort das Mikrofon FREIGEGEBEN
        // und ein zweiter Rekorder (MediaRecorder) neu aufgebaut. Dieser
        // Wechsel dauert auf dem Galaxy ein halbe bis eine ganze Sekunde, und
        // in dieser Zeit wurde nichts aufgezeichnet - Doreens Satzanfang fiel
        // heraus. Belegt im Transkript vom 27.07.2026: "Wann haben wir den
        // Termin mit Bettina?" kam als "Wir haben den Termin mit Bettina." an,
        // "Was ist mit dem EU-AI-Akt?" als "Mit dem EU-AI-Akt." Die Folge war
        // nicht nur ein fehlendes Wort: Der Router stuft das Bruchstueck als
        // "keine Quelle" ein, Jarvis bekommt also gar keine Kalenderdaten und
        // MUSS zurueckfragen. Solange es den Piep gab, hat sie unbewusst
        // darauf gewartet; seit dem Abschalten (v0.18) faellt es voll durch.
        //
        // Seit v0.19 wird die Frage aus DEMSELBEN, weiterlaufenden Strom
        // aufgenommen (kein Rekorderwechsel, keine Luecke), und der Vorlauf
        // deckt zusaetzlich ab, dass "Hey Jarvis" und die Frage in einem Zug
        // gesprochen werden. Der Vorlauf enthaelt dabei das Weckwort selbst -
        // das entfernt der Server aus dem Transkript.
        private const val VORLAUF_SAMPLES = SAMPLE_RATE  // 1,0 s
        // Ab diesem Ausschlag gilt ein Block als "gesprochen" (Skala 0..32767,
        // derselbe Wert wie zuvor bei MediaRecorder.maxAmplitude).
        private const val SPRACH_PEGEL = 1500
        // Nach so viel Stille IN FOLGE endet die Aufnahme (nur, wenn vorher
        // ueberhaupt gesprochen wurde), Obergrenze darunter.
        private const val STILLE_ENDE_MS = 1300
        // Obergrenze der Aufnahme. Am 27.07.2026 von 10 s auf 30 s erhoeht:
        // Doreens Zuruf "Lege einen neuen Ordner in Gmail an, DEKRA, und
        // verschiebe die beiden E-Mails im Posteingang in den ..." brach
        // mitten im Satz ab. Nicht wegen einer Sprechpause - im Log stand
        // eine Aufnahmedauer von exakt 11,000 s (1 s Vorlauf + 10 s Deckel),
        // dreimal an diesem Nachmittag. Ihre Saetze sind laenger geworden,
        // seit Jarvis mehrstufige Auftraege annimmt.
        // Die Stille-Erkennung beendet die Aufnahme ohnehin 1,3 s nach dem
        // letzten Wort; dieser Deckel greift nur, wenn wirklich
        // durchgesprochen wird. Ein Fehlalarm im Raumgespraech kostet damit
        // im schlimmsten Fall 30 s Aufnahme statt 10 - vertretbar, seit
        // Schwelle 0,65 und zwei Bestaetigungsbloecke die Fehlalarme
        // praktisch abgestellt haben.
        private const val AUFNAHME_MAX_MS = 30_000
        // NACHFASS-FENSTER (v0.21): So lange bleibt das Mikrofon nach der
        // Antwort offen, damit Doreen ohne erneutes "Hey Jarvis" weiterreden
        // kann. 7 s ist lang genug zum Nachdenken und kurz genug, dass das
        // Mikrofon nicht gefuehlt dauernd offen ist.
        private const val NACHFASS_FENSTER_MS = 7_000
        // So viele Bloecke (a 80 ms) muessen IN FOLGE ueber dem Sprachpegel
        // liegen, damit das Fenster als "sie spricht" gilt. Drei Bloecke =
        // 240 ms - ein Klappern oder Huesteln reicht damit nicht. Gleiche
        // Idee wie BESTAETIGUNGEN beim Weckwort.
        private const val NACHFASS_ONSET_BLOECKE = 3
        // Kuerzer als das hier (WAV-Kopf + ~1 s Ton) wird gar nicht erst
        // gesendet - dann war es doch nur ein Geraeusch.
        private const val NACHFASS_MIN_BYTES = 44 + SAMPLE_RATE * 2
        // Wie oft beim Server nachgesehen wird, ob Jarvis etwas gemeldet hat.
        // 60 s: Die Anlaesse sind ein fertiger Manus-Auftrag (laeuft Stunden)
        // und die Briefings (feste Uhrzeit) - schneller braucht es nicht, und
        // eine winzige Anfrage pro Minute faellt neben dem dauerhaft offenen
        // Mikrofon nicht ins Gewicht.
        private const val NACHSEHEN_INTERVALL_MS = 60_000L
    }

    @Volatile private var aktiv = false
    private var lauschThread: Thread? = null
    private var herzschlagThread: Thread? = null
    private var postfachThread: Thread? = null
    private var audioRecord: AudioRecord? = null
    private var engine: OpenWakeWord? = null
    private var wakeLock: android.os.PowerManager.WakeLock? = null

    /**
     * Haelt die CPU wach, solange gelauscht wird.
     *
     * ENTDECKT AM 31.07.2026 (Doreen: "reagiert zu haeufig nicht, gerade da
     * ich ihn ja auch aufrufen können will, wenn der Bildschirm aus ist").
     * Ein Vordergrunddienst schuetzt den PROZESS davor, beendet zu werden -
     * er garantiert aber NICHT, dass die CPU laeuft. Bei ausgeschaltetem
     * Bildschirm darf das System den Anwendungsprozessor schlafen legen;
     * unsere Erkennung braucht aber alle 80 ms einen Rechendurchlauf durch
     * drei tflite-Modelle. Faellt der aus, hoert das Handy zwar noch zu,
     * erkennt das Weckwort aber unzuverlaessig - genau das beschriebene
     * Bild "reagiert manchmal, manchmal nicht".
     *
     * PARTIAL_WAKE_LOCK heisst: CPU an, Bildschirm und Tastaturlicht bleiben
     * aus. Das kostet Akku - fuer einen Dienst, dessen ganzer Zweck das
     * ununterbrochene Zuhoeren ist, ist das der ehrliche Preis. Freigegeben
     * wird er in onDestroy, also sobald das Lauschen gestoppt wird.
     */
    private fun halteCpuWach() {
        if (wakeLock != null) return
        try {
            val pm = getSystemService(Context.POWER_SERVICE) as android.os.PowerManager
            wakeLock = pm.newWakeLock(
                android.os.PowerManager.PARTIAL_WAKE_LOCK,
                "jarvis:weckwort"      // Format "app:tag" ist Pflicht
            ).apply {
                setReferenceCounted(false)
                acquire()
            }
        } catch (t: Throwable) {
            // Kein Grund, das Lauschen deswegen aufzugeben - ohne Wake Lock
            // laeuft es wie bisher, nur unzuverlaessiger bei Bildschirm aus.
            meldeStatus("Hinweis: CPU-Wachhalten nicht moeglich ($t)")
        }
    }

    private fun gibCpuFrei() {
        try {
            if (wakeLock?.isHeld == true) wakeLock?.release()
        } catch (_: Exception) {}
        wakeLock = null
    }

    /** Ringpuffer der zuletzt gehoerten Sekunde (siehe VORLAUF_SAMPLES).
     *  Laeuft waehrend des Lauschens dauerhaft mit und wird beim Weckwort
     *  der Aufnahme vorangestellt. Verlaesst das Handy nur dann - beim
     *  blossen Lauschen wird er fortlaufend ueberschrieben. */
    private val vorlauf = ShortArray(VORLAUF_SAMPLES)
    private var vorlaufPos = 0
    private var vorlaufGefuellt = 0

    private fun vorlaufLeeren() {
        vorlaufPos = 0
        vorlaufGefuellt = 0
    }

    private fun vorlaufSchreiben(block: ShortArray) {
        for (s in block) {
            vorlauf[vorlaufPos] = s
            vorlaufPos = (vorlaufPos + 1) % vorlauf.size
            if (vorlaufGefuellt < vorlauf.size) vorlaufGefuellt++
        }
    }

    /** Gibt den Vorlauf in zeitlicher Reihenfolge zurueck (aeltestes zuerst). */
    private fun vorlaufLesen(): ShortArray {
        val out = ShortArray(vorlaufGefuellt)
        val start = if (vorlaufGefuellt < vorlauf.size) 0
                    else vorlaufPos
        for (i in 0 until vorlaufGefuellt) {
            out[i] = vorlauf[(start + i) % vorlauf.size]
        }
        return out
    }

    /** Sichtbare Diagnose: Der Dienst meldet seinen Zustand in die
     *  SharedPreferences, die MainActivity zeigt ihn live an. Auf dem
     *  Handy gibt es kein lesbares Log - Fehler duerfen deshalb NIE
     *  stumm verschluckt werden (Lektion aus dem ersten v0.6-Test:
     *  "kein Piep, Benachrichtigung nie da" war ohne Diagnose nicht
     *  eingrenzbar). */
    private fun meldeStatus(text: String, ueberschreibeFehler: Boolean = true) {
        try {
            val prefs = getSharedPreferences("jarvis", Context.MODE_PRIVATE)
            if (!ueberschreibeFehler) {
                // Eine FEHLER-Meldung muss stehen bleiben, bis Doreen sie
                // gelesen hat - "Dienst beendet." darf sie nicht verdraengen
                // (genau das passierte beim ersten v0.7-Test: die Meldung
                // "ploppte nur kurz auf").
                val aktuell = prefs.getString("wake_status", "") ?: ""
                if (aktuell.startsWith("FEHLER")) {
                    // Trotzdem ein Lebenszeichen setzen, sonst sieht ein
                    // laufender Dienst nach dem Fehlerfall "tot" aus.
                    prefs.edit().putLong("wake_heartbeat", System.currentTimeMillis()).apply()
                    return
                }
            }
            prefs.edit()
                .putString("wake_status", text)
                // LEBENSZEICHEN: Ohne Zeitstempel zeigte die App weiter die
                // letzte Meldung an, auch wenn der Dienst laengst beendet war
                // (ein App-Update beendet ihn stillschweigend, 25.07.2026) –
                // es sah dann so aus, als lausche er noch. Die MainActivity
                // erkennt daran, ob der Dienst wirklich lebt.
                .putLong("wake_heartbeat", System.currentTimeMillis())
                .apply()
        } catch (_: Exception) {}
    }

    /**
     * Schreibt regelmaessig ein Lebenszeichen – UNABHAENGIG davon, was der
     * Dienst gerade tut.
     *
     * Wichtig: Frueher kam das Lebenszeichen nur aus der Lausch-Schleife
     * (alle ~2,5 s). Waehrend einer Frage-Antwort-Runde lauscht der Dienst
     * aber NICHT (aufnehmen, senden, Antwort abspielen dauert leicht
     * ueber 30 s) – die App meldete dann faelschlich "DIENST LAEUFT NICHT",
     * obwohl gerade alles korrekt lief (Doreen, 25.07.2026). Der Herzschlag
     * bedeutet daher "der Dienst lebt", nicht "er lauscht gerade".
     */
    private fun starteHerzschlag() {
        if (herzschlagThread != null) return
        herzschlagThread = thread {
            while (aktiv) {
                try {
                    getSharedPreferences("jarvis", Context.MODE_PRIVATE).edit()
                        .putLong("wake_heartbeat", System.currentTimeMillis())
                        .apply()
                    Thread.sleep(5000)
                } catch (_: InterruptedException) {
                    return@thread
                } catch (_: Exception) {
                    // Nicht kritisch – naechster Durchlauf versucht es erneut.
                }
            }
        }
    }

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .readTimeout(180, TimeUnit.SECONDS)
        .build()

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        starteVordergrund()
        starteLauschen()
        return START_STICKY
    }

    private fun starteVordergrund() {
        val kanalId = "jarvis_wake"
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= 26) {
            nm.createNotificationChannel(
                NotificationChannel(kanalId, "Hey Jarvis", NotificationManager.IMPORTANCE_LOW)
            )
        }
        val n: Notification = NotificationCompat.Builder(this, kanalId)
            .setContentTitle("Jarvis lauscht")
            .setContentText("Sagen Sie „Hey Jarvis“, um zu sprechen.")
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setOngoing(true)
            .build()
        if (Build.VERSION.SDK_INT >= 30) {
            // ORTUNGS-TYP NUR, WENN DIE ERLAUBNIS AUCH VORLIEGT (v0.31).
            // Ab Android 14 stuerzt startForeground mit einer SecurityException
            // ab, wenn ein Dienst den Typ "location" angibt, ohne die
            // Berechtigung zu haben - das wuerde das Weckwort komplett
            // lahmlegen, nur weil sie die Ortung abgelehnt hat. Deshalb wird
            // der Typ hier zur Laufzeit zusammengesetzt statt fest verdrahtet.
            var typ = ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
            if (Standort.erlaubt(this)) {
                typ = typ or ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
            }
            startForeground(1, n, typ)
        } else {
            startForeground(1, n)
        }
    }

    private fun starteLauschen() {
        if (aktiv) return
        meldeStatus("Dienst gestartet, lade Erkennungsmodelle …")
        try {
            engine = OpenWakeWord(applicationContext)
        } catch (t: Throwable) {
            // Throwable, nicht nur Exception: ein UnsatisfiedLinkError der
            // TensorFlow-Lite-Bibliothek waere sonst ein stummer Absturz.
            meldeStatus("FEHLER beim Laden der Erkennung: $t")
            stopSelf()
            return
        }
        meldeStatus("Modelle geladen, starte Selbsttest …")
        aktiv = true
        halteCpuWach()
        starteHerzschlag()
        starteNachsehen()
        lauschThread = thread {
            try {
                selbsttest()
                while (aktiv) {
                    if (!lauscheBisWeckwort()) {
                        // Mikrofon-Aussetzer (z. B. andere App hatte es kurz):
                        // kurz warten und erneut versuchen statt still
                        // aufzugeben, waehrend die Benachrichtigung weiter
                        // "Jarvis lauscht" behauptet.
                        if (aktiv) Thread.sleep(3000)
                        continue
                    }
                    // Weckwort erkannt: Mikrofon ist freigegeben, dann der
                    // bewaehrte Frage-/Antwort-Ablauf (unveraendert aus der
                    // Porcupine-Zeit).
                    meldeStatus("Weckwort erkannt – ich höre Ihre Frage …")
                    // JETZT den Ort bestimmen, nicht erst beim Senden (v0.31):
                    // Der Vorgang laeuft im Hintergrund, waehrend sie spricht -
                    // ihre Frage dauert typischerweise drei bis acht Sekunden,
                    // in denen ein Fix meist laengst da ist. Beim Senden wird
                    // nur noch abgelesen, was im Zwischenspeicher liegt; die
                    // muehsam erarbeiteten ~5 s bis zum ersten gesprochenen
                    // Wort bleiben damit unangetastet.
                    Standort.anstossen(this)
                    ton(ToneGenerator.TONE_PROP_BEEP)
                    var frage = nimmFrageAufAusStrom()
                    if (frage == null || frage.length() <= 0) {
                        meldeStatus("Keine Frage gehört – ich lausche weiter.")
                    }
                    // Frage -> Antwort -> Nachfass-Fenster -> ggf. naechste
                    // Frage, ohne erneutes Weckwort (v0.21). Das Fenster
                    // schliesst sich von selbst, wenn sie nichts mehr sagt.
                    // Die ERSTE Frage folgt auf ihr "Hey Jarvis" - sie hat ihn
                    // also bewusst gerufen. Alles Weitere stammt aus dem
                    // Nachfass-Fenster und ist spekulativ: Dort darf ein
                    // unverstaendlicher Fetzen nicht kommentiert werden.
                    // HOECHSTENS EINE Nachfrage pro Weckwort (v0.30, 02.08.2026).
                    //
                    // Vorher lief hier eine offene Kette: Nach JEDER Antwort
                    // ging das Fenster erneut auf. Am 02.08. sagte Doreen ihr
                    // Weckwort versehentlich, waehrend sie jemandem VON Jarvis
                    // erzaehlte - danach hielt jedes Wort im Raum, das
                    // innerhalb von 7 Sekunden fiel, die Kette am Laufen: 17
                    // mitgeschnittene Wortwechsel ueber 17 Minuten, mitten in
                    // einem fremden Gespraech (Transkript 12:14 bis 12:31).
                    //
                    // Die schnelle Rueckfrage bleibt erhalten - genau dafuer
                    // wollte sie das Fenster -, aber die Kette ist an der
                    // Wurzel gekappt: Danach braucht es wieder "Hey Jarvis".
                    var ausNachfass = false
                    while (aktiv && frage != null && frage.length() > 0) {
                        ton(ToneGenerator.TONE_PROP_ACK)
                        meldeStatus("Frage aufgenommen, sende an Jarvis …")
                        frageJarvis(frage, ausNachfass)
                        frage = if (aktiv && !ausNachfass) nachfassFenster() else null
                        ausNachfass = true
                    }
                    // Puffer leeren, damit die eigene Aufnahme/Stimme keinen
                    // Fehlalarm hinterlaesst; danach lauscht die Schleife weiter.
                    engine?.reset()
                }
            } finally {
                gibMikrofonFrei()
            }
        }
    }

    /** Ergebnis des Selbsttests - wird in jeder Lauscht-Statuszeile
     *  mit angezeigt, damit Erkennungs- und Mikrofonprobleme sauber
     *  auseinanderzuhalten sind. */
    @Volatile private var selbsttestErgebnis = "-"

    /**
     * Spielt das eingebaute, am Laptop verifizierte "Hey Jarvis"-Testaudio
     * OHNE Mikrofon durch die Erkennung. Erwartung ~0,99. Kommt hier ~0
     * heraus, rechnet die Erkennung auf dem Handy falsch; kommt ~0,99
     * heraus und der Live-Wert bleibt trotzdem 0, liefert das MIKROFON
     * keine brauchbaren Daten.
     */
    private fun selbsttest() {
        try {
            val bytes = assets.open("selftest_hey_jarvis.pcm").use { it.readBytes() }
            val samples = ShortArray(bytes.size / 2)
            for (i in samples.indices) {
                samples[i] = ((bytes[2 * i].toInt() and 0xFF) or
                              (bytes[2 * i + 1].toInt() shl 8)).toShort()
            }
            val eng = engine ?: return
            var max = 0f
            val block = ShortArray(OpenWakeWord.BLOCK_SAMPLES)
            var pos = 0
            while (pos + block.size <= samples.size) {
                System.arraycopy(samples, pos, block, 0, block.size)
                max = maxOf(max, eng.verarbeite(block))
                pos += block.size
            }
            eng.reset()
            selbsttestErgebnis = String.format("%.2f", max)
            meldeStatus("Selbsttest: $selbsttestErgebnis (erwartet ~0,99) – Lauschen beginnt …")
        } catch (t: Throwable) {
            selbsttestErgebnis = "FEHLER"
            meldeStatus("FEHLER im Selbsttest: $t")
        }
    }

    /**
     * Lauscht blockierend, bis das Weckwort faellt (true) oder der Dienst
     * gestoppt wird bzw. das Mikrofon nicht verfuegbar ist (false).
     */
    private fun lauscheBisWeckwort(): Boolean {
        val eng = engine ?: return false
        val minPuffer = AudioRecord.getMinBufferSize(
            SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT
        )
        val rec = try {
            AudioRecord(
                MediaRecorder.AudioSource.MIC, SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT,
                maxOf(minPuffer, OpenWakeWord.BLOCK_SAMPLES * 2 * 4)
            )
        } catch (e: Exception) {
            null
        }
        if (rec == null || rec.state != AudioRecord.STATE_INITIALIZED) {
            try { rec?.release() } catch (_: Exception) {}
            // Ohne Mikrofon-Berechtigung o. ae. beendet sich der Dienst.
            meldeStatus("FEHLER: Mikrofon nicht verfügbar (Berechtigung? Andere App?)")
            aktiv = false
            stopSelf()
            return false
        }
        audioRecord = rec
        rec.startRecording()

        val block = ShortArray(OpenWakeWord.BLOCK_SAMPLES)
        // Diagnose: hoechster Erkennungswert UND hoechster Mikrofon-Pegel
        // der letzten ~2,5 Sekunden - Pegel ~0 beim Sprechen = Mikrofon
        // liefert Stille; Pegel hoch + Wert 0 = Erkennungsproblem.
        var maxScore = 0f
        var maxPegel = 0
        var bloecke = 0
        // Zaehlt, wie viele Bloecke IN FOLGE ueber der Schwelle lagen.
        var ueberSchwelle = 0
        // Beim Treffer bleibt das Mikrofon OFFEN, damit die Frage ohne
        // Rekorderwechsel aus demselben Strom weiterlaufen kann (v0.19).
        var erkannt = false
        vorlaufLeeren()
        try {
            while (aktiv) {
                var gelesen = 0
                while (aktiv && gelesen < block.size) {
                    val n = rec.read(block, gelesen, block.size - gelesen)
                    if (n <= 0) {
                        meldeStatus("FEHLER: Mikrofon liefert keine Daten (Code $n) – neuer Versuch in 3 s")
                        return false
                    }
                    gelesen += n
                }
                if (!aktiv) return false
                // Mitschreiben, BEVOR bewertet wird: Faellt das Weckwort in
                // diesem Block, gehoert er selbst schon zum Vorlauf.
                vorlaufSchreiben(block)
                val score = try {
                    eng.verarbeite(block)
                } catch (t: Throwable) {
                    meldeStatus("FEHLER in der Erkennung: $t")
                    return false
                }
                if (score > SCHWELLE) {
                    // Erst nach BESTAETIGUNGEN Bloecken in Folge gilt das
                    // Weckwort als gesprochen. Ein einzelner Ausschlag im
                    // Raumgespraech laeuft hier ins Leere.
                    if (++ueberSchwelle >= BESTAETIGUNGEN) {
                        erkannt = true
                        return true
                    }
                } else {
                    ueberSchwelle = 0
                }
                maxScore = maxOf(maxScore, score)
                for (s in block) {
                    val a = if (s >= 0) s.toInt() else -s.toInt()
                    if (a > maxPegel) maxPegel = a
                }
                if (++bloecke >= 32) {  // ~2,5 s
                    meldeStatus("Lauscht … Wert " + String.format("%.2f", maxScore) +
                        " | Mikro-Pegel $maxPegel | Selbsttest $selbsttestErgebnis")
                    maxScore = 0f
                    maxPegel = 0
                    bloecke = 0
                }
            }
            return false
        } finally {
            // Beim Treffer NICHT freigeben - nimmFrageAufAusStrom() liest
            // denselben Strom weiter und gibt danach frei.
            if (!erkannt) gibMikrofonFrei()
        }
    }

    /**
     * Sieht regelmaessig nach, ob Jarvis von sich aus etwas gemeldet hat
     * (fertiger Manus-Auftrag, Briefing) - seit v0.22 an Telegrams Stelle.
     *
     * Laeuft als eigener Thread neben dem Lauschen. Kein Push-Dienst noetig,
     * weil dieser Dienst ohnehin dauerhaft laeuft; ein Firebase-Projekt,
     * weitere Zugangsdaten und ein zusaetzlicher Anbieter bleiben Doreen
     * damit erspart.
     *
     * WICHTIG - die Benachrichtigung zeigt NUR den Titel, nie den Inhalt:
     * Sie erscheint auf dem gesperrten Bildschirm, bevor irgendeine
     * App-Sperre greift. Ein Depotstand oder eine Terminliste hat dort
     * nichts verloren.
     */
    private fun starteNachsehen() {
        postfachThread = thread {
            // Kurz warten, damit der Dienststart nicht mit einem Netzaufruf
            // konkurriert (Modelle laden zuerst).
            try { Thread.sleep(10_000) } catch (_: InterruptedException) { return@thread }
            while (aktiv) {
                try {
                    val neue = Postfach.abholen(applicationContext, client, System.currentTimeMillis())
                    neue.forEach { melde(it) }
                } catch (t: Throwable) {
                    // Ein Netzfehler darf das Lauschen niemals stoeren.
                }
                try { Thread.sleep(NACHSEHEN_INTERVALL_MS) } catch (_: InterruptedException) { return@thread }
            }
        }
    }

    private fun melde(n: Postfach.Nachricht) {
        try {
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            val kanalId = "jarvis_nachrichten"
            if (Build.VERSION.SDK_INT >= 26) {
                nm.createNotificationChannel(
                    NotificationChannel(kanalId, "Jarvis meldet sich", NotificationManager.IMPORTANCE_DEFAULT)
                )
            }
            val oeffnen = android.app.PendingIntent.getActivity(
                this, 0,
                Intent(this, MainActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
                android.app.PendingIntent.FLAG_IMMUTABLE or android.app.PendingIntent.FLAG_UPDATE_CURRENT
            )
            val bau = NotificationCompat.Builder(this, kanalId)
                .setContentTitle(n.titel)
                // BEWUSST kein Inhalt - siehe Begruendung oben.
                .setContentText("In der App öffnen")
                .setSmallIcon(android.R.drawable.ic_dialog_email)
                .setContentIntent(oeffnen)
                .setAutoCancel(true)
                .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            nm.notify(n.id.toInt().coerceAtLeast(2), bau.build())
        } catch (t: Throwable) {
            meldeStatus("FEHLER bei der Benachrichtigung: $t")
        }
    }

    private fun gibMikrofonFrei() {
        val rec = audioRecord ?: return
        audioRecord = null
        try { rec.stop() } catch (_: Exception) {}
        try { rec.release() } catch (_: Exception) {}
    }

    /**
     * Nimmt die Frage nach dem Weckwort auf - aus DEMSELBEN, noch laufenden
     * AudioRecord-Strom (v0.19). Vorangestellt wird der Ringpuffer der letzten
     * Sekunde, sodass ein in einem Zug gesprochenes "Hey Jarvis, wann ..."
     * vollstaendig erhalten bleibt. Ergebnis ist eine WAV-Datei (16 kHz, mono,
     * PCM16) - der Server leitet das Format aus dem Dateinamen ab.
     *
     * Frueher (bis v0.18) wurde hier ein MediaRecorder neu aufgebaut. Dessen
     * Anlaufzeit war die Ursache fuer die abgeschnittenen Satzanfaenge, siehe
     * Begruendung an VORLAUF_SAMPLES.
     *
     * Stille-Erkennung unveraendert: Ende, sobald nach gesprochenen Worten
     * ~1,3 s Ruhe herrscht; Obergrenze 10 s. Wurde gar nicht gesprochen, wird
     * nichts gesendet.
     *
     * WICHTIG: Der Vorlauf zaehlt bewusst NICHT als "gesprochen" - sonst
     * wuerde das Weckwort selbst die Stille-Uhr starten und die Aufnahme
     * abbrechen, waehrend Doreen nach dem Zuruf noch ueberlegt.
     */
    private fun nimmFrageAufAusStrom(): File? = aufnehmenAusStrom(vorlaufLesen())

    /**
     * Der eigentliche Aufnahme-Vorgang auf dem schon offenen Mikrofon.
     * `vorlauf` wird der Aufnahme vorangestellt (beim Weckwort der Ringpuffer,
     * beim Nachfass-Fenster die schon gehoerten ersten Worte). Gibt das
     * Mikrofon am Ende frei.
     */
    private fun aufnehmenAusStrom(vorlauf: ShortArray): File? {
        val rec = audioRecord ?: return null
        val datei = File(cacheDir, "wake_frage.wav")
        return try {
            val daten = java.io.ByteArrayOutputStream()
            fun schreibe(samples: ShortArray, anzahl: Int) {
                for (i in 0 until anzahl) {
                    val s = samples[i].toInt()
                    daten.write(s and 0xFF)
                    daten.write((s shr 8) and 0xFF)
                }
            }

            schreibe(vorlauf, vorlauf.size)

            val block = ShortArray(OpenWakeWord.BLOCK_SAMPLES)  // 80 ms
            var laufzeitMs = 0
            var stilleMs = 0
            var gesprochen = false
            while (aktiv && laufzeitMs < AUFNAHME_MAX_MS) {
                var gelesen = 0
                while (aktiv && gelesen < block.size) {
                    val n = rec.read(block, gelesen, block.size - gelesen)
                    if (n <= 0) return null
                    gelesen += n
                }
                if (!aktiv) return null
                schreibe(block, block.size)
                laufzeitMs += 80

                var pegel = 0
                for (s in block) {
                    val a = if (s >= 0) s.toInt() else -s.toInt()
                    if (a > pegel) pegel = a
                }
                if (pegel > SPRACH_PEGEL) {
                    gesprochen = true
                    stilleMs = 0
                } else if (gesprochen) {
                    stilleMs += 80
                    if (stilleMs >= STILLE_ENDE_MS) break
                }
            }
            if (!gesprochen) return null
            datei.writeBytes(alsWav(daten.toByteArray()))
            datei
        } catch (e: Exception) {
            meldeStatus("FEHLER bei der Aufnahme: $e")
            null
        } finally {
            // Ab hier wird das Mikrofon nicht mehr gebraucht: Die Antwort
            // wird abgespielt, und Jarvis' eigene Stimme darf nicht in die
            // Erkennung zurueckwandern.
            gibMikrofonFrei()
        }
    }

    /**
     * NACHFASS-FENSTER (v0.21, auf Doreens Wunsch): Nach Jarvis' Antwort
     * bleibt das Mikrofon einige Sekunden offen. Spricht sie in dieser Zeit
     * weiter, gilt das als naechste Frage – OHNE erneutes "Hey Jarvis".
     * So wird aus Zuruf/Antwort ein Gespraech.
     *
     * Rueckgabe: die Aufnahme der Anschlussfrage, oder null, wenn im Fenster
     * nichts gesprochen wurde (dann geht es zurueck ins normale Lauschen).
     *
     * Gegen Fehlstarts durch Umgebungsgeraeusche:
     *   - Es muessen NACHFASS_ONSET_BLOECKE Bloecke IN FOLGE ueber dem
     *     Sprachpegel liegen (ein Tellerklappern reicht nicht).
     *   - Der Ringpuffer laeuft mit, damit das erste Wort erhalten bleibt.
     *   - Kommt am Ende zu wenig Ton zusammen, wird gar nichts gesendet
     *     (siehe NACHFASS_MIN_BYTES) – lieber schweigen als auf ein
     *     Geraeusch antworten.
     */
    private fun nachfassFenster(): File? {
        val minPuffer = AudioRecord.getMinBufferSize(
            SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT
        )
        val rec = try {
            AudioRecord(
                MediaRecorder.AudioSource.MIC, SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT,
                maxOf(minPuffer, OpenWakeWord.BLOCK_SAMPLES * 2 * 4)
            )
        } catch (e: Exception) {
            null
        }
        if (rec == null || rec.state != AudioRecord.STATE_INITIALIZED) {
            try { rec?.release() } catch (_: Exception) {}
            return null
        }
        audioRecord = rec
        rec.startRecording()
        meldeStatus("Ich höre noch – sprechen Sie einfach weiter.")

        vorlaufLeeren()
        val block = ShortArray(OpenWakeWord.BLOCK_SAMPLES)
        var wartezeitMs = 0
        var ueberPegel = 0
        try {
            while (aktiv && wartezeitMs < NACHFASS_FENSTER_MS) {
                var gelesen = 0
                while (aktiv && gelesen < block.size) {
                    val n = rec.read(block, gelesen, block.size - gelesen)
                    if (n <= 0) { gibMikrofonFrei(); return null }
                    gelesen += n
                }
                if (!aktiv) { gibMikrofonFrei(); return null }
                vorlaufSchreiben(block)
                wartezeitMs += 80

                var pegel = 0
                for (s in block) {
                    val a = if (s >= 0) s.toInt() else -s.toInt()
                    if (a > pegel) pegel = a
                }
                if (pegel > SPRACH_PEGEL) {
                    if (++ueberPegel >= NACHFASS_ONSET_BLOECKE) {
                        // Sie spricht: ab hier normale Aufnahme, der Vorlauf
                        // enthaelt die schon gehoerten ersten Silben.
                        val aufnahme = aufnehmenAusStrom(vorlaufLesen())
                        if (aufnahme != null && aufnahme.length() < NACHFASS_MIN_BYTES) {
                            // Zu kurz - war vermutlich ein Geraeusch.
                            return null
                        }
                        return aufnahme
                    }
                } else {
                    ueberPegel = 0
                }
            }
        } catch (e: Exception) {
            meldeStatus("FEHLER im Nachfass-Fenster: $e")
        }
        gibMikrofonFrei()
        return null
    }

    /** Setzt den 44-Byte-WAV-Kopf (PCM16, mono, SAMPLE_RATE) vor die Rohdaten. */
    private fun alsWav(pcm: ByteArray): ByteArray {
        val kopf = java.nio.ByteBuffer.allocate(44)
            .order(java.nio.ByteOrder.LITTLE_ENDIAN)
        val byteRate = SAMPLE_RATE * 2  // mono * 16 bit
        kopf.put("RIFF".toByteArray())
        kopf.putInt(36 + pcm.size)
        kopf.put("WAVE".toByteArray())
        kopf.put("fmt ".toByteArray())
        kopf.putInt(16)             // Groesse des fmt-Blocks
        kopf.putShort(1)            // PCM, unkomprimiert
        kopf.putShort(1)            // ein Kanal
        kopf.putInt(SAMPLE_RATE)
        kopf.putInt(byteRate)
        kopf.putShort(2)            // Blockausrichtung
        kopf.putShort(16)           // Bits pro Sample
        kopf.put("data".toByteArray())
        kopf.putInt(pcm.size)
        return kopf.array() + pcm
    }

    /** Schickt die Aufnahme an /assistant - gleiche Retry-/Idempotenz-Logik
     *  wie der Sprechen-Knopf in der App. */
    private fun frageJarvis(audio: File, ausNachfass: Boolean = false) {
        val prefs = getSharedPreferences("jarvis", Context.MODE_PRIVATE)
        val base = (prefs.getString("url", "") ?: "").trim().trimEnd('/')
        val key = prefs.getString("key", "") ?: ""
        if (base.isEmpty() || key.isEmpty()) return

        // Satzweise Antwort (siehe StreamClient): Jarvis spricht los, sobald
        // der erste Satz steht. blockiereBisGesprochen=true, weil erst NACH
        // der Antwort weitergelauscht werden darf – sonst wuerde seine eigene
        // Stimme das Weckwort ausloesen. Klappt der Strom nicht, laeuft
        // unten der bewaehrte /assistant-Weg.
        // Was beim Weckwort angestossen wurde, ist jetzt meist fertig. Ist es
        // das nicht (oder ist die Ortung aus), bleibt der Text leer und der
        // Server verhaelt sich wie vor v0.31.
        val ort = Standort.text(this)

        meldeStatus("Frage gesendet – Antwort läuft …")
        try {
            val gestreamt = StreamClient.ask(
                ctx = this, client = client, base = base, key = key,
                text = null, audio = audio, cacheDir = cacheDir,
                blockiereBisGesprochen = true,
                stillBeiUnverstanden = ausNachfass,
                standort = ort,
            )
            if (gestreamt) return
        } catch (t: Throwable) {
            meldeStatus("Stream fehlgeschlagen, versuche klassisch …")
        }

        val requestId = UUID.randomUUID().toString()
        for (versuch in 1..3) {
            try {
                val e2e = Krypto.aktiv(this)
                val typ = (if (audio.name.endsWith(".wav", ignoreCase = true))
                    "audio/wav" else "audio/mp4").toMediaType()
                val bauer = MultipartBody.Builder().setType(MultipartBody.FORM)
                    .addFormDataPart("key", key)
                    .addFormDataPart("request_id", requestId)
                if (e2e) bauer.addFormDataPart("e2e", "1")
                // Auch der Rueckfall-Weg schickt den Ort mit - sonst waeren die
                // Empfehlungen ausgerechnet dann ortlos, wenn der Strom klemmt.
                if (ort.isNotEmpty()) bauer.addFormDataPart(
                    "standort", if (e2e) Krypto.verschluesselnText(this, ort) else ort
                )
                val body = bauer.addFormDataPart(
                        // Endung mitfuehren (seit v0.19 .wav, siehe StreamClient).
                        "audio", audio.name,
                        if (e2e) Krypto.verschluesselnRoh(this, audio.readBytes())
                            .toRequestBody(typ)
                        else audio.asRequestBody(typ)
                    )
                    .build()
                val request = Request.Builder()
                    .url("$base/assistant")
                    .addHeader("ngrok-skip-browser-warning", "true")
                    .post(body)
                    .build()
                client.newCall(request).execute().use { resp ->
                    if (!resp.isSuccessful) {
                        ton(ToneGenerator.TONE_SUP_ERROR)
                        return
                    }
                    val json = Krypto.auspacken(this, JSONObject(resp.body?.string() ?: "{}"))
                    // Gleiche Regel wie im Strom: Ein "nichts verstanden" aus
                    // dem Nachfass-Fenster bleibt still.
                    if (json.optString("anlass") == "nichts_verstanden" && ausNachfass) {
                        meldeStatus("Nichts verstanden (Nachfass) – ich lausche weiter.")
                        return
                    }
                    val audioB64 = if (json.isNull("audio_base64")) null
                                   else json.optString("audio_base64", null)
                    if (audioB64 != null) spieleAntwort(audioB64)
                }
                return
            } catch (e: IOException) {
                // Kurzer Netz-/VPN-Aussetzer: warten und mit derselben
                // request_id erneut - der Server liefert dann die schon
                // fertige Antwort aus dem Idempotenz-Cache.
                if (versuch >= 3) ton(ToneGenerator.TONE_SUP_ERROR)
                else Thread.sleep(2500)
            } catch (e: Exception) {
                ton(ToneGenerator.TONE_SUP_ERROR)
                return
            }
        }
    }

    /** Spielt die Antwort ab und BLOCKIERT bis zum Ende - erst danach wird
     *  das Lauschen fortgesetzt (sonst hoert die Erkennung Jarvis' Stimme). */
    private fun spieleAntwort(b64: String) {
        try {
            val bytes = Base64.decode(b64, Base64.DEFAULT)
            val tmp = File(cacheDir, "wake_antwort.mp3")
            tmp.writeBytes(bytes)
            val fertig = Object()
            val mp = MediaPlayer()
            mp.setDataSource(tmp.absolutePath)
            mp.setOnCompletionListener {
                synchronized(fertig) { fertig.notifyAll() }
                it.release()
            }
            mp.prepare()
            mp.start()
            synchronized(fertig) { fertig.wait(120_000) }
        } catch (_: Exception) {
        }
    }

    /**
     * Rueckmeldung an Doreen, dass der Zuruf angekommen ist - per VIBRATION,
     * nicht per Ton.
     *
     * Weg dahin: Urspruenglich lief der Piep ueber STREAM_NOTIFICATION und war
     * unhoerbar, weil ihr Handy dauerhaft stumm ist. Am 26.07.2026 auf
     * STREAM_MUSIC umgestellt - ihr Befund danach: "den Piep hoere ich nur,
     * wenn der Ton an ist". Stimmt, der Medienkanal ist bei stummem Geraet
     * ebenfalls still. Vibration ist die einzige Rueckmeldung, die bei einem
     * dauerhaft stummen Handy zuverlaessig ankommt.
     *
     * Die beiden Signale bleiben unterscheidbar: Das Weckwort bestaetigt EIN
     * laengerer Impuls, die fertige Aufnahme ZWEI kurze.
     */
    private fun ton(art: Int) {
        // AUS auf Doreens Wunsch (26.07.2026): "Der Piep ist insgesamt eher
        // irritierend, das war vorher besser." Sie will gar keine
        // Rueckmeldung - sie merkt am Sprechen, dass er zuhoert.
        // Bewusster Preis: Ohne den ersten Impuls ist nicht erkennbar, AB WANN
        // aufgenommen wird. Faellt ihr das auf, reicht RUECKMELDUNG = true und
        // ein Weglassen des ACK-Zweigs - dann kommt nur das Weckwort-Signal
        // zurueck. Deshalb bleibt der Code samt VIBRATE-Berechtigung stehen.
        if (!RUECKMELDUNG) return
        try {
            val vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                (getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager)
                    .defaultVibrator
            } else {
                @Suppress("DEPRECATION")
                getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
            }
            val muster = if (art == ToneGenerator.TONE_PROP_ACK) {
                // Aufnahme fertig, geht raus: zwei kurze Impulse.
                longArrayOf(0, 60, 90, 60)
            } else {
                // Weckwort erkannt, ich hoere zu: ein laengerer Impuls.
                longArrayOf(0, 160)
            }
            vibrator.vibrate(VibrationEffect.createWaveform(muster, -1))
            Thread.sleep(muster.sum() + 80)
        } catch (_: Exception) {
        }
    }

    override fun onDestroy() {
        aktiv = false
        meldeStatus("Dienst beendet.", ueberschreibeFehler = false)
        gibCpuFrei()
        gibMikrofonFrei()
        try { lauschThread?.join(1000) } catch (_: Exception) {}
        lauschThread = null
        try { herzschlagThread?.interrupt() } catch (_: Exception) {}
        herzschlagThread = null
        try { engine?.schliessen() } catch (_: Exception) {}
        engine = null
        super.onDestroy()
    }
}
