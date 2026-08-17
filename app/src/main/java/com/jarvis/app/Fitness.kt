package com.jarvis.app

import android.content.Context
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.PermissionController
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.DistanceRecord
import androidx.health.connect.client.records.ElevationGainedRecord
import androidx.health.connect.client.records.ExerciseSessionRecord
import androidx.health.connect.client.records.HeartRateRecord
import androidx.health.connect.client.records.RestingHeartRateRecord
import androidx.health.connect.client.records.SleepSessionRecord
import androidx.health.connect.client.records.StepsRecord
import java.time.LocalDate
import java.time.temporal.WeekFields
import java.util.Locale
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

    fun benoetigteBerechtigungen(): Set<String> = BENOETIGTE_BERECHTIGUNGEN

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
}
