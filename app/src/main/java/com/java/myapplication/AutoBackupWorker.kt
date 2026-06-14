package com.java.myapplication

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import java.util.concurrent.TimeUnit

class AutoBackupWorker(
    private val appContext: Context,
    workerParameters: WorkerParameters,
) : CoroutineWorker(appContext, workerParameters) {
    override suspend fun doWork(): Result = runCatching {
        val preferences = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val config = BackupConfig(
            endpoint = preferences.getString(KEY_ENDPOINT, "").orEmpty(),
            username = preferences.getString(KEY_USERNAME, "").orEmpty(),
            password = preferences.getString(KEY_PASSWORD, "").orEmpty(),
        )
        BackupEngine.backupCachedSelection(appContext, config)
    }.fold(
        onSuccess = { Result.success() },
        onFailure = { Result.retry() },
    )

    companion object {
        private const val WORK_NAME = "webdav-auto-backup"

        fun schedule(context: Context, intervalHours: Int) {
            val request = PeriodicWorkRequestBuilder<AutoBackupWorker>(
                intervalHours.coerceAtLeast(1).toLong(),
                TimeUnit.HOURS,
            ).build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                WORK_NAME,
                ExistingPeriodicWorkPolicy.UPDATE,
                request,
            )
        }

        fun cancel(context: Context) {
            WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME)
        }
    }
}

const val PREFS_NAME = "webdav_backup_settings"
const val KEY_ENDPOINT = "endpoint"
const val KEY_USERNAME = "username"
const val KEY_PASSWORD = "password"
const val KEY_AUTO_ENABLED = "auto_enabled"
const val KEY_INTERVAL_HOURS = "interval_hours"
