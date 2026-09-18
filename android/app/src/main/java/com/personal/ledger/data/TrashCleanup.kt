package com.personal.ledger.data

import android.content.Context
import androidx.work.*
import java.util.concurrent.TimeUnit

class TrashCleanup(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val name = inputData.getString("database") ?: return Result.failure()
        val owner = inputData.getString("owner") ?: return Result.failure()
        if (name.contains('/') || name.contains('\\') || !applicationContext.getDatabasePath(name).exists()) return Result.success()
        val db = LedgerDatabases.acquire(applicationContext, name)
        return try {
            LedgerRepository(db, owner).purgeExpired()
            Result.success()
        } catch (_: Exception) { Result.retry() }
        finally { LedgerDatabases.release(name) }
    }
    companion object {
        fun schedule(context: Context, name: String, owner: String) {
            val work = PeriodicWorkRequestBuilder<TrashCleanup>(12, TimeUnit.HOURS)
                .setInputData(workDataOf("database" to name, "owner" to owner)).build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork("trash-$name-$owner", ExistingPeriodicWorkPolicy.KEEP, work)
        }
    }
}
