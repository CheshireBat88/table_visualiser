package com.groupfund.app.notifications

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters

/**
 * Ежедневная фоновая проверка дней рождений.
 */
class BirthdayWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        runCatching { BirthdayCheck.run(applicationContext) }
        return Result.success()
    }
}