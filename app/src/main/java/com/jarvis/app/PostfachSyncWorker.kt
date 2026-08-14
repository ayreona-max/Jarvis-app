package com.jarvis.app

import android.content.Context
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerParameters
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

/**
 * Holt das Postfach alle 15 Minuten ab, UNABHAENGIG davon, ob der
 * Weckwort-Dienst (WakeWordService) gerade laeuft. Grund: Frank aktiviert
 * "Hey Jarvis" nur bei Bedarf, laesst es nicht durchgehend laufen - ohne
 * diesen Worker kamen zeitgebundene Nachrichten (z. B. das 5:30-Uhr-
 * Wetter-Briefing) erst an, wenn er die App zufaellig als naechstes
 * geoeffnet hat (14.08.2026, siehe PLAN-POSTFACH-HINTERGRUNDABRUF.md).
 *
 * Bewusst kein Push-Dienst (siehe Postfach.kt) - 15 Minuten ist das
 * Android-Minimum fuer wiederkehrende Hintergrundarbeit und reicht fuer
 * den Anwendungsfall (Briefings, keine Sofortnachrichten).
 */
class PostfachSyncWorker(ctx: Context, params: WorkerParameters) : Worker(ctx, params) {

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    override fun doWork(): Result {
        val neue = try {
            Postfach.abholen(applicationContext, client, System.currentTimeMillis())
        } catch (t: Throwable) {
            // Ein Netz-/Serverfehler darf den periodischen Abruf nicht
            // dauerhaft stoppen - der naechste Lauf in 15 Minuten holt nach.
            emptyList()
        }
        neue.forEach { Postfach.benachrichtigen(applicationContext, it) }
        return Result.success()
    }

    companion object {
        private const val ARBEITSNAME = "postfach_sync"

        /**
         * Meldet den wiederkehrenden Abruf beim System an - sicher mehrfach
         * aufrufbar (bei jedem App-Start UND nach jedem Handy-Neustart):
         * KEEP sorgt dafuer, dass eine bereits laufende Planung erhalten
         * bleibt statt neu zu starten.
         */
        fun registriere(ctx: Context) {
            val anfrage = PeriodicWorkRequestBuilder<PostfachSyncWorker>(15, TimeUnit.MINUTES)
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
