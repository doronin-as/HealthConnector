package ru.doronin.healthconnector

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import java.util.concurrent.TimeUnit

object ManualSyncScheduler {
    const val UNIQUE_WORK_NAME = "health_connector_manual_sync"
    fun enqueue(context: Context) {
        val request=OneTimeWorkRequestBuilder<ManualSyncWorker>().setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()).setBackoffCriteria(BackoffPolicy.EXPONENTIAL,30,TimeUnit.SECONDS).build()
        WorkManager.getInstance(context.applicationContext).enqueueUniqueWork(UNIQUE_WORK_NAME,ExistingWorkPolicy.KEEP,request)
    }
}
