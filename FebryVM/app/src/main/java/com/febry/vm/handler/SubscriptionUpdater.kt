package com.febry.vm.handler

import android.annotation.SuppressLint
import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkerParameters
import androidx.work.multiprocess.RemoteWorkManager
import androidx.work.workDataOf
import com.febry.vm.AngApplication
import com.febry.vm.AppConfig
import com.febry.vm.R
import com.febry.vm.dto.entities.SubscriptionCache
import com.febry.vm.enums.NotificationChannelType
import com.febry.vm.util.LogUtil
import com.febry.vm.util.NotificationHelper
import java.util.concurrent.TimeUnit

object SubscriptionUpdater {

    // -------------------------------------------------------------------------
    // Public API — the only methods external callers should ever use
    // -------------------------------------------------------------------------

    /**
     * Sync all subscription tasks with current settings.
     *
     * Startup/boot callers should use the default mode so existing periodic work is kept.
     * Use forceReschedule=true only when the next run time needs to be recalculated from
     * the latest persisted subscription state (for example after a manual refresh).
     * Call from: MainActivity.onCreate(), BootReceiver.onReceive().
     */
    fun sync(
        context: Context = AngApplication.application,
        forceReschedule: Boolean = false
    ) {
        val existingWorkPolicy =
            if (forceReschedule) {
                ExistingPeriodicWorkPolicy.REPLACE
            } else {
                ExistingPeriodicWorkPolicy.KEEP
            }

        MmkvManager.decodeSubscriptions().forEach { sub ->
            scheduleOne(
                context = context,
                subId = sub.guid,
                shouldRun = sub.subscription.autoUpdate,
                existingWorkPolicy = existingWorkPolicy
            )
        }
        LogUtil.i(
            AppConfig.TAG,
            "SubscriptionUpdater: sync complete forceReschedule=$forceReschedule"
        )
    }

    /**
     * Sync a single subscription's task.
     * Call from: SubEditActivity after saving, after a manual update (to reset the timer).
     */
    fun syncOne(context: Context = AngApplication.application, subId: String) {
        val subItem = MmkvManager.decodeSubscription(subId) ?: return
        scheduleOne(
            context = context,
            subId = subId,
            shouldRun = subItem.autoUpdate,
            existingWorkPolicy = ExistingPeriodicWorkPolicy.REPLACE
        )
    }

    /**
     * Cancel the auto-update task for a single subscription.
     * Call from: when a subscription is deleted.
     */
    fun cancelOne(context: Context = AngApplication.application, subId: String) {
        RemoteWorkManager.getInstance(context)
            .cancelUniqueWork(taskName(subId))
    }

    /**
     * Schedule or cancel a global periodic update for ALL subscriptions at once.
     * Controlled by PREF_SCHEDULED_SUB_UPDATE and PREF_SCHEDULED_SUB_UPDATE_INTERVAL.
     */
    fun syncGlobalSchedule(context: Context = AngApplication.application) {
        val enabled = MmkvManager.decodeSettingsBool(AppConfig.PREF_SCHEDULED_SUB_UPDATE, false)
        val rw = RemoteWorkManager.getInstance(context)
        if (!enabled) {
            rw.cancelUniqueWork(AppConfig.SCHEDULED_SUB_UPDATE_TASK)
            return
        }
        val intervalMinutes = maxOf(
            AppConfig.SUBSCRIPTION_MIN_INTERVAL_MINUTES,
            MmkvManager.decodeSettingsString(AppConfig.PREF_SCHEDULED_SUB_UPDATE_INTERVAL, "60")
                ?.toLongOrNull() ?: 60L
        )
        val request = PeriodicWorkRequestBuilder<GlobalUpdateTask>(intervalMinutes, TimeUnit.MINUTES)
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .addTag(AppConfig.SCHEDULED_SUB_UPDATE_TASK)
            .build()
        rw.enqueueUniquePeriodicWork(
            AppConfig.SCHEDULED_SUB_UPDATE_TASK,
            ExistingPeriodicWorkPolicy.KEEP,
            request
        )
    }

    // -------------------------------------------------------------------------
    // Internal scheduling logic
    // -------------------------------------------------------------------------

    private fun taskName(subId: String) = "${AppConfig.SUBSCRIPTION_UPDATE_TASK_NAME}_$subId"

    private fun scheduleOne(
        context: Context,
        subId: String,
        shouldRun: Boolean,
        existingWorkPolicy: ExistingPeriodicWorkPolicy
    ) {
        val rw = RemoteWorkManager.getInstance(context)
        if (!shouldRun) {
            rw.cancelUniqueWork(taskName(subId))
            LogUtil.d(AppConfig.TAG, "SubscriptionUpdater: cancelled task for $subId")
            return
        }

        val subItem = MmkvManager.decodeSubscription(subId) ?: return

        val intervalMinutes = maxOf(
            AppConfig.SUBSCRIPTION_MIN_INTERVAL_MINUTES,
            subItem.updateInterval
        )

        // Base initial delay on the last successful update time persisted in subscription.
        val lastUpdated = subItem.lastUpdated
        val intervalMillis = intervalMinutes * 60 * 1000L
        val now = System.currentTimeMillis()
        val initialDelayMillis = if (lastUpdated <= 0L) {
            0L
        } else {
            maxOf(0L, lastUpdated + intervalMillis - now)
        }

        val request = PeriodicWorkRequestBuilder<UpdateTask>(intervalMinutes, TimeUnit.MINUTES)
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .build()
            )
            .setInputData(workDataOf(KEY_SUB_ID to subId))
            .setInitialDelay(initialDelayMillis, TimeUnit.MILLISECONDS)
            .addTag(AppConfig.SUBSCRIPTION_UPDATE_TASK_NAME)
            .build()

        rw.enqueueUniquePeriodicWork(
            taskName(subId),
            existingWorkPolicy,
            request
        )

        LogUtil.i(
            AppConfig.TAG,
            "SubscriptionUpdater: scheduled [$subId] interval=${intervalMinutes}min " +
                    "initialDelay=${initialDelayMillis / 1000}s policy=$existingWorkPolicy"
        )
    }

    // -------------------------------------------------------------------------
    // Worker
    // -------------------------------------------------------------------------

    private const val KEY_SUB_ID = "subId"

    class UpdateTask(context: Context, params: WorkerParameters) :
        CoroutineWorker(context, params) {

        @SuppressLint("MissingPermission")
        override suspend fun doWork(): Result {
            val subId = inputData.getString(KEY_SUB_ID)
            LogUtil.i(AppConfig.TAG, "SubscriptionUpdater automatic update starting: $subId")

            if (subId.isNullOrEmpty()) {
                LogUtil.w(AppConfig.TAG, "SubscriptionUpdater: missing subId in worker input")
                return Result.success()
            }

            val subItem = MmkvManager.decodeSubscription(subId)
            if (subItem == null) {
                LogUtil.w(AppConfig.TAG, "SubscriptionUpdater: no subscription found for $subId")
                return Result.success()
            }

            if (!subItem.autoUpdate) {
                LogUtil.i(AppConfig.TAG, "SubscriptionUpdater: auto-update disabled for $subId, skip")
                return Result.success()
            }

            val sub = SubscriptionCache(subId, subItem)

            // Notify about update start
            NotificationHelper.notify(
                NotificationChannelType.SUBSCRIPTION_UPDATE,
                applicationContext,
                applicationContext.getString(R.string.title_pref_auto_update_subscription),
                "Updating ${sub.subscription.remarks}"
            )

            LogUtil.i(AppConfig.TAG, "SubscriptionUpdater automatic update: ---${sub.subscription.remarks}")
            AngConfigManager.updateConfigViaSub(sub)

            // Clear notification
            NotificationHelper.cancel(NotificationChannelType.SUBSCRIPTION_UPDATE, applicationContext)

            return Result.success()
        }
    }

    /** Worker that updates ALL subscriptions at once (global scheduled update). */
    class GlobalUpdateTask(context: Context, params: WorkerParameters) :
        CoroutineWorker(context, params) {
        override suspend fun doWork(): Result {
            LogUtil.i(AppConfig.TAG, "SubscriptionUpdater: global scheduled update starting")
            MmkvManager.decodeSubscriptions().forEach { sub ->
                AngConfigManager.updateConfigViaSub(SubscriptionCache(sub.guid, sub.subscription))
            }
            return Result.success()
        }
    }
}