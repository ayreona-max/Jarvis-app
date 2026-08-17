package com.jarvis.app

import android.content.Context
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.PermissionController
import androidx.health.connect.client.aggregate.AggregateRequest
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.DistanceRecord
import androidx.health.connect.client.records.ElevationGainedRecord
import androidx.health.connect.client.records.ExerciseSessionRecord
import androidx.health.connect.client.records.HeartRateRecord
import androidx.health.connect.client.records.RestingHeartRateRecord
import androidx.health.connect.client.records.SleepSessionRecord
import androidx.health.connect.client.records.StepsRecord
import androidx.health.connect.client.request.ReadRecordsRequest
import androidx.health.connect.client.time.TimeRangeFilter
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import java.time.temporal.WeekFields
import java.util.Locale
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject

/**
 * Liest Fitness-Daten aus Android Health Connect (Garmin Connect schreibt
 * dorthin, sofern Frank die Schreibrechte in Garmin Connect aktiviert hat).
 * Bewusst nur Radfahren bei den Trainings-Aktivitaeten (Franks Entscheidung,
 * Brainstorming 17.08.2026) - siehe
 * docs/superpowers/specs/2026-08-17-fitness-dashboard-design.md.
 */
object Fitness {

    private val BENOETIGTE_BERECHTIGUNGEN = setOf(
        HealthPermission.getReadPermission(ExerciseSessionRecord::class),
        HealthPermission.getReadPermission(StepsRecord::class),
        HealthPermission.getReadPermission(SleepSessionRecord::class),
        HealthPermission.getReadPermission(HeartRateRecord::class),
        HealthPermission.getReadPermission(RestingHeartRateRecord::class),
        HealthPermission.getReadPermission(DistanceRecord::class),
        HealthPermission.getReadPermission(ElevationGainedRecord::class),
    )

    /** Nur fuers Anfragen im Zustimmungsdialog (opportunistisch), NICHT Teil
     *  von BENOETIGTE_BERECHTIGUNGEN: FitnessSyncWorker liest im Hintergrund,
     *  wo Health Connect auf manchen Geraeten/Versionen ohne dieses Recht mit
     *  einer SecurityException abbricht. Bewusst NICHT in die Pass/Fail-
     *  Pruefung (hatAlleBerechtigungen/synchronisiere) aufgenommen: manche
     *  Health-Connect-Versionen bieten dieses Recht gar nicht an - ein hartes
     *  .containsAll(...) darauf wuerde den Sync dort dauerhaft blockieren,
     *  obwohl alle sieben eigentlich noetigen Rechte erteilt sind. */
    const val HINTERGRUND_BERECHTIGUNG = "android.permission.health.READ_HEALTH_DATA_IN_BACKGROUND"

    fun benoetigteBerechtigungen(): Set<String> = BENOETIGTE_BERECHTIGUNGEN

    /** Die Menge, die dem Nutzer im Zustimmungsdialog angeboten wird: die
     *  sieben noetigen plus opportunistisch das Hintergrund-Leserecht. */
    fun erbetenBerechtigungen(): Set<String> = BENOETIGTE_BERECHTIGUNGEN + HINTERGRUND_BERECHTIGUNG

    fun berechtigungsVertrag() = PermissionController.createRequestPermissionResultContract()

    /** Android <14 ohne installierte Health-Connect-App: Feature bleibt
     *  einfach aus, wie ein nicht erteilter Standort (siehe Standort.kt). */
    fun verfuegbar(ctx: Context): Boolean =
        HealthConnectClient.getSdkStatus(ctx) == HealthConnectClient.SDK_AVAILABLE

    suspend fun hatAlleBerechtigungen(ctx: Context): Boolean {
        val client = HealthConnectClient.getOrCreate(ctx)
        val erteilt = client.permissionController.getGrantedPermissions()
        return erteilt.containsAll(BENOETIGTE_BERECHTIGUNGEN)
    }

    data class Fahrradfahrt(val distanzKm: Double, val hoehenmeter: Int, val dauerMin: Int, val pulsAvg: Int)
    data class Wochenaggregat(val distanzKm: Double, val dauerMin: Int, val hoehenmeter: Int, val pulsAvg: Int)
    data class Tagesaggregat(
        val datum: LocalDate, val schritte: Int, val schlafMin: Int,
        val ruhepuls: Int, val pulsTagAvg: Int, val gehstreckeKm: Double,
    )

    /** Montag der Kalenderwoche, zu der [datum] gehoert (deutsche Woche,
     *  Montag-Start) - Beginn des Aggregationsfensters fuer die Wochenkarte. */
    fun wochenStart(datum: LocalDate): LocalDate =
        datum.with(WeekFields.of(Locale.GERMANY).dayOfWeek(), 1)

    /** Fasst alle Radfahrten der Woche zusammen. Puls-Durchschnitt nach
     *  Fahrtdauer gewichtet (siehe FitnessAggregationTest,
     *  summiereWocheGewichtetPulsNachDauer). */
    fun summiereWoche(fahrten: List<Fahrradfahrt>): Wochenaggregat {
        if (fahrten.isEmpty()) return Wochenaggregat(0.0, 0, 0, 0)
        val distanz = fahrten.sumOf { it.distanzKm }
        val dauer = fahrten.sumOf { it.dauerMin }
        val hoehe = fahrten.sumOf { it.hoehenmeter }
        val pulsGewichtet = fahrten.sumOf { it.pulsAvg.toDouble() * it.dauerMin }
        val puls = if (dauer > 0) (pulsGewichtet / dauer).toInt() else 0
        return Wochenaggregat(distanz, dauer, hoehe, puls)
    }

    private fun rundeEineNachkommastelle(x: Double): Double = Math.round(x * 10.0) / 10.0

    /** Liest alle Radfahrten im Fenster [vonInclusive, bisExklusiv) aus
     *  Health Connect. Rueckgabewert: Paare aus (ExerciseSessionRecord, aggregierte
     *  Fahrradfahrt-Daten). Wird sowohl fuer die Wochen-Aggregation als auch fuer
     *  die Gehstrecke-Bereinigung genutzt (siehe leseWoche und synchronisiere). */
    private suspend fun leseRadfahrten(
        hc: HealthConnectClient, vonInclusive: Instant, bisExklusiv: Instant,
    ): List<Pair<ExerciseSessionRecord, Fahrradfahrt>> {
        val sessions = hc.readRecords(
            ReadRecordsRequest(
                ExerciseSessionRecord::class,
                TimeRangeFilter.between(vonInclusive, bisExklusiv),
            )
        ).records.filter { it.exerciseType == ExerciseSessionRecord.EXERCISE_TYPE_BIKING }

        return sessions.map { s ->
            val fenster = TimeRangeFilter.between(s.startTime, s.endTime)
            val agg = hc.aggregate(
                AggregateRequest(
                    metrics = setOf(
                        DistanceRecord.DISTANCE_TOTAL,
                        ElevationGainedRecord.ELEVATION_GAINED_TOTAL,
                        HeartRateRecord.BPM_AVG,
                    ),
                    timeRangeFilter = fenster,
                )
            )
            Pair(s, Fahrradfahrt(
                distanzKm = (agg[DistanceRecord.DISTANCE_TOTAL]?.inMeters ?: 0.0) / 1000.0,
                hoehenmeter = (agg[ElevationGainedRecord.ELEVATION_GAINED_TOTAL]?.inMeters ?: 0.0).toInt(),
                dauerMin = ChronoUnit.MINUTES.between(s.startTime, s.endTime).toInt(),
                pulsAvg = (agg[HeartRateRecord.BPM_AVG] ?: 0).toInt(),
            ))
        }
    }

    /** Baut den JSON-Koerper fuer POST /fitness-sync - Vertrag siehe
     *  docs/superpowers/specs/2026-08-17-fitness-dashboard-design.md. */
    fun baueSyncPayload(
        schluessel: String, kwStart: LocalDate, woche: Wochenaggregat, tage: List<Tagesaggregat>,
    ): JSONObject {
        val wocheJson = JSONObject().apply {
            put("kw_start", kwStart.toString())
            put("distanz_km", rundeEineNachkommastelle(woche.distanzKm))
            put("dauer_min", woche.dauerMin)
            put("hoehenmeter", woche.hoehenmeter)
            put("puls_avg", woche.pulsAvg)
        }
        val tageJson = JSONArray()
        tage.forEach { t ->
            tageJson.put(JSONObject().apply {
                put("datum", t.datum.toString())
                put("schritte", t.schritte)
                put("schlaf_min", t.schlafMin)
                put("ruhepuls", t.ruhepuls)
                put("puls_tag_avg", t.pulsTagAvg)
                put("gehstrecke_km", rundeEineNachkommastelle(t.gehstreckeKm))
            })
        }
        return JSONObject().apply {
            put("key", schluessel)
            put("woche", wocheJson)
            put("tage", tageJson)
        }
    }

    /** Ergebnis einer Wochen-Aggregation: das fertige Wochenaggregat PLUS die
     *  einzelnen Fahrten (mit Session, also Start-/Endzeit) - Letztere werden
     *  fuer die Gehstrecke-Bereinigung im tage-Loop gebraucht (siehe
     *  synchronisiere: eine Radfahrt darf nicht gleichzeitig als "Gehstrecke"
     *  auftauchen). */
    private data class WochenDaten(
        val woche: Wochenaggregat,
        val fahrtenMitSession: List<Pair<ExerciseSessionRecord, Fahrradfahrt>>,
    )

    /** Liest alle Radfahrten im Fenster [wochenStart, wochenEndeExklusiv) aus
     *  Health Connect und aggregiert sie. Fenstergrenzen als Instant statt
     *  LocalDate, damit sowohl die laufende (unvollstaendige, bis "jetzt"
     *  reichende) als auch eine abgeschlossene, vergangene Kalenderwoche
     *  (siehe Montags-Nachtrag in synchronisiere) damit beschrieben werden
     *  koennen. */
    private suspend fun leseWoche(
        hc: HealthConnectClient, wochenStart: Instant, wochenEndeExklusiv: Instant,
    ): WochenDaten {
        val fahrtenMitSession = leseRadfahrten(hc, wochenStart, wochenEndeExklusiv)
        val fahrten = fahrtenMitSession.map { it.second }
        return WochenDaten(summiereWoche(fahrten), fahrtenMitSession)
    }

    /** Schickt einen fertigen Sync-Payload an den Server. Gibt false zurueck
     *  (kein Absturz) bei Netzfehlern - siehe synchronisiere. */
    private fun poste(client: OkHttpClient, basis: String, payload: JSONObject): Boolean = try {
        val body = payload.toString().toRequestBody("application/json".toMediaType())
        client.newCall(
            Request.Builder()
                .url("$basis/fitness-sync")
                .addHeader("ngrok-skip-browser-warning", "true")
                .post(body)
                .build()
        ).execute().use { it.isSuccessful }
    } catch (_: Exception) {
        false
    }

    /** Liest die aktuelle Kalenderwoche (Rad) und heute+gestern (Alltag) aus
     *  Health Connect, baut den Sync-Payload und schickt ihn an den Server.
     *  Gibt false zurueck (kein Absturz) bei fehlender Konfiguration,
     *  fehlender Berechtigung oder Netzfehler - der naechste taegliche Lauf
     *  holt es nach, siehe FitnessSyncWorker.
     *
     *  Montags-Sonderfall: Weil jeder Lauf NUR die laufende Kalenderwoche
     *  postet, wuerde eine Fahrt am Sonntagabend sonst nie mehr uebertragen -
     *  der naechste Lauf (Montag) hat schon eine neue Woche im Blick. Faellt
     *  "heute" auf einen Montag, wird deshalb zusaetzlich die GERADE
     *  ABGESCHLOSSENE Vorwoche erneut aggregiert und gepostet (Upsert auf
     *  kw_start beim Server macht das gefahrlos wiederholbar). */
    suspend fun synchronisiere(ctx: Context, client: OkHttpClient): Boolean {
        val prefs = ctx.getSharedPreferences("jarvis", Context.MODE_PRIVATE)
        val basis = (prefs.getString("url", "") ?: "").trim().trimEnd('/')
        val key = prefs.getString("key", "") ?: ""
        if (basis.isEmpty() || key.isEmpty()) return false
        if (!verfuegbar(ctx)) return false

        val hc = HealthConnectClient.getOrCreate(ctx)
        if (!hc.permissionController.getGrantedPermissions().containsAll(BENOETIGTE_BERECHTIGUNGEN)) return false

        val zone = ZoneId.systemDefault()
        val heute = LocalDate.now(zone)
        val kwStart = wochenStart(heute)

        val aktuelleWoche = leseWoche(
            hc,
            kwStart.atStartOfDay(zone).toInstant(),
            heute.plusDays(1).atStartOfDay(zone).toInstant(),
        )

        // Fahrten fuer Gehstrecke-Bereinigung: unabhaengiges 2-Tage-Fenster,
        // damit die Berechnung nicht durch ISO-Wochengrenzenfaelle (z.B. Montag,
        // wo kwStart == heute ist) verfaelscht wird. Deckt gestern+heute IMMER
        // vollstaendig ab, ganz gleich wo die Wochengrenze liegt.
        val fahrtenFuerTage = leseRadfahrten(
            hc,
            heute.minusDays(1).atStartOfDay(zone).toInstant(),
            heute.plusDays(1).atStartOfDay(zone).toInstant(),
        )

        val tage = listOf(heute.minusDays(1), heute).map { tag ->
            val fenster = TimeRangeFilter.between(
                tag.atStartOfDay(zone).toInstant(),
                tag.plusDays(1).atStartOfDay(zone).toInstant(),
            )
            val agg = hc.aggregate(
                AggregateRequest(
                    metrics = setOf(
                        StepsRecord.COUNT_TOTAL,
                        HeartRateRecord.BPM_AVG,
                        DistanceRecord.DISTANCE_TOTAL,
                    ),
                    timeRangeFilter = fenster,
                )
            )
            // Schlaf braucht ein EIGENES Fenster: Mitternacht-zu-Mitternacht
            // zerschneidet eine typische Nacht (z.B. 23:00-07:00) in zwei
            // Kalendertage und liesse jeden Tag viel zu wenig Schlaf zeigen
            // (Garmin rechnet die ganze Nacht stattdessen dem Aufwach-Tag
            // zu). Vorabend 18:00 bis Mittag DES Tages deckt eine typische
            // Nacht komplett ab, ohne echte Schlafsitzungs-Grenzen erkennen
            // zu muessen.
            val schlafFenster = TimeRangeFilter.between(
                tag.minusDays(1).atTime(18, 0).atZone(zone).toInstant(),
                tag.atTime(12, 0).atZone(zone).toInstant(),
            )
            val schlafAgg = hc.aggregate(
                AggregateRequest(
                    metrics = setOf(SleepSessionRecord.SLEEP_DURATION_TOTAL),
                    timeRangeFilter = schlafFenster,
                )
            )
            val ruhepuls = hc.readRecords(
                ReadRecordsRequest(RestingHeartRateRecord::class, fenster)
            ).records.lastOrNull()?.beatsPerMinute?.toInt() ?: 0

            // Gehstrecke = Tages-Gesamtdistanz OHNE die Distanz, die
            // innerhalb einer Radfahrt-Session lag (Garmin schreibt
            // Fahrraddistanz als eigene DistanceRecords WAEHREND der
            // Session - die stecken sonst mit in der Tagessumme und ein
            // 40-km-Ritt liesse "Gehstrecke" faelschlich in die Hoehe
            // schnellen). Vereinfachte, akzeptierte Annaeherung: Fahrten,
            // die AN DIESEM TAG starten, werden mit ihrer vollen
            // Distanz abgezogen. Nutzt fahrtenFuerTage (eigenes 2-Tage-Fenster),
            // nicht aktuelleWoche.fahrtenMitSession (Wochenfenster), damit
            // auch am Montag noch der Sonntag der Vorwoche korrekt berechnet wird.
            val radDistanzAmTag = fahrtenFuerTage
                .filter { (s, _) -> s.startTime.atZone(zone).toLocalDate() == tag }
                .sumOf { (_, f) -> f.distanzKm }
            val gehstreckeKm = (
                (agg[DistanceRecord.DISTANCE_TOTAL]?.inMeters ?: 0.0) / 1000.0 - radDistanzAmTag
            ).coerceAtLeast(0.0)

            Tagesaggregat(
                datum = tag,
                schritte = (agg[StepsRecord.COUNT_TOTAL] ?: 0L).toInt(),
                schlafMin = (schlafAgg[SleepSessionRecord.SLEEP_DURATION_TOTAL]?.toMinutes() ?: 0L).toInt(),
                ruhepuls = ruhepuls,
                pulsTagAvg = (agg[HeartRateRecord.BPM_AVG] ?: 0).toInt(),
                gehstreckeKm = gehstreckeKm,
            )
        }

        val payload = baueSyncPayload(key, kwStart, aktuelleWoche.woche, tage)
        val erfolgAktuelleWoche = poste(client, basis, payload)

        var erfolgVorwoche = true
        if (heute.dayOfWeek == DayOfWeek.MONDAY) {
            val vorKwStart = kwStart.minusWeeks(1)
            val vorWoche = leseWoche(
                hc,
                vorKwStart.atStartOfDay(zone).toInstant(),
                kwStart.atStartOfDay(zone).toInstant(),
            )
            val vorPayload = baueSyncPayload(key, vorKwStart, vorWoche.woche, tage)
            erfolgVorwoche = poste(client, basis, vorPayload)
        }

        return erfolgAktuelleWoche && erfolgVorwoche
    }
}
