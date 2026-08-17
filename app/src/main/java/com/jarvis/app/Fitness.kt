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
}
