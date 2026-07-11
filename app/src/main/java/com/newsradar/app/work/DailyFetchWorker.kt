package com.newsradar.app.work

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.newsradar.app.data.NewsRepository
import com.newsradar.app.util.Notifier

/** Runs the daily fetch, then posts a "your stories are ready" notification. */
class DailyFetchWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        return try {
            val count = NewsRepository.get(applicationContext).refresh()
            if (count > 0) {
                Notifier.showFeedReady(applicationContext, count)
            }
            Result.success()
        } catch (e: Exception) {
            Result.retry()
        }
    }
}
