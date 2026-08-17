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

    /**
     * Meldet Result.retry() NUR, wenn ein Sync wirklich versucht wurde und am
     * Senden scheiterte - dann holt WorkManager es mit wachsendem Abstand von
     * selbst nach, ohne bis zum naechsten Tageslauf zu warten. Anlass war ein
     * echter Fall (17.08.2026): Der Server kannte /fitness-sync noch nicht
     * (Code war auf GitHub, aber nicht auf den Pi ausgerollt), der erste Sync
     * schlug fehl - und weil hier frueher IMMER Result.success() zurueckkam,
     * wurde er nie wiederholt.
     *
     * Bei NICHTS_ZU_TUN (nicht konfiguriert / Health Connect fehlt / Rechte
     * nicht erteilt) bewusst KEIN retry: das aendert sich nur durch Franks
     * Zutun, Wiederholen wuerde nur Akku kosten.
     *
     * Ein Absturz beim Lesen aus Health Connect (z.B. RemoteException
     * waehrend eines Modul-Updates) gilt als voruebergehend und wird ebenfalls
     * wiederholt - aber gedeckelt, damit ein dauerhaft kaputter Zustand nicht
     * endlos nachfeuert; danach greift wieder der normale Tagesrhythmus.
     */
    override suspend fun doWork(): Result = try {
        when (Fitness.synchronisiere(applicationContext, client)) {
            Fitness.SyncErgebnis.ERFOLG -> Result.success()
            Fitness.SyncErgebnis.NICHTS_ZU_TUN -> Result.success()
            Fitness.SyncErgebnis.SENDEN_FEHLGESCHLAGEN -> wiederholenOderAufgeben()
        }
    } catch (_: Throwable) {
        wiederholenOderAufgeben()
    }

    private fun wiederholenOderAufgeben(): Result =
        if (runAttemptCount < MAX_VERSUCHE) Result.retry() else Result.success()

    companion object {
        private const val ARBEITSNAME = "fitness_sync"

        /** Nach so vielen vergeblichen Anlaeufen wird bis zum naechsten
         *  Tageslauf gewartet, statt weiter nachzufeuern. */
        private const val MAX_VERSUCHE = 5

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
