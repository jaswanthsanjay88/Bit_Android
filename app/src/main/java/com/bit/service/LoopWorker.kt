package com.bit.service

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.bit.util.DebugLog

class LoopWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val conversationId = inputData.getString(EXTRA_CONVERSATION_ID) ?: return Result.failure()
        val scheduledAt = inputData.getLong(EXTRA_SCHEDULED_AT, 0L)
        DebugLog.d("LoopWorker", "Executing loop $conversationId scheduled at $scheduledAt")
        return Result.success()
    }

    companion object {
        const val EXTRA_CONVERSATION_ID = "conversation_id"
        const val EXTRA_SCHEDULED_AT = "scheduled_at"

        fun enqueue(context: Context, conversationId: String, scheduledAt: Long) {
            val data = Data.Builder()
                .putString(EXTRA_CONVERSATION_ID, conversationId)
                .putLong(EXTRA_SCHEDULED_AT, scheduledAt)
                .build()
            val request = OneTimeWorkRequestBuilder<LoopWorker>()
                .setInputData(data)
                .addTag("loop_$conversationId")
                .build()
            WorkManager.getInstance(context).enqueue(request)
        }

        fun cancel(context: Context, conversationId: String) {
            WorkManager.getInstance(context).cancelAllWorkByTag("loop_$conversationId")
        }
    }
}
