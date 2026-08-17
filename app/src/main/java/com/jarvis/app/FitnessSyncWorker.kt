package com.jarvis.app

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

/**
 * Synct einmal taeglich Fahrrad- und Alltagsdaten aus Health Connect zum
 * Server - eigener Job statt Anbau an PostfachSyncWorker (andere Kennzahl,
 * andere Fehlerbehandlung, siehe
 * docs/superpowers/specs/2026-08-17-fitness-dashboard-design.md).
 * CoroutineWorker statt Worker, weil Health-Connect-Aufrufe suspend-Funktionen
 * sind (siehe Fitness.synchronisiere).
 */
class FitnessSyncWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    override suspend fun doWork(): Result {
        try {
            Fitness.synchronisiere(applicationContext, client)
        } catch (t: Throwable) {
            // Ein Netz-/Health-Connect-Fehler darf den taeglichen Sync nicht
            // dauerhaft stoppen - der naechste Lauf morgen holt nach.
        }
        return Result.success()
    }

    companion object {
        private const val ARBEITSNAME = "fitness_sync"

        fun registriere(ctx: Context) {
            val anfrage = PeriodicWorkRequestBuilder<FitnessSyncWorker>(1, TimeUnit.DAYS)
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.CONNECTED)
                        .build()
                )
                .build()
            WorkManager.getInstance(ctx).enqueueUniquePeriodicWork(
                ARBEITSNAME, ExistingPeriodicWorkPolicy.KEEP, anfrage
            )
        }
    }
}
