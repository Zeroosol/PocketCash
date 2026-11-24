package com.patternsystems.pocketcash.widget

import android.content.ComponentName
import android.content.Context
import android.service.quicksettings.TileService
import androidx.glance.appwidget.updateAll
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.patternsystems.pocketcash.data.CashStore
import com.patternsystems.pocketcash.tile.QuickSpendTileService
import java.time.Duration
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.concurrent.TimeUnit

/** Redraws every PocketCash widget and the Quick Settings tile. The store calls it after each save. */
object Widgets {
    suspend fun refresh(context: Context) {
        runCatching { SmallCashWidget().updateAll(context) }
        runCatching { MediumCashWidget().updateAll(context) }
        runCatching { TileService.requestListeningState(context, ComponentName(context, QuickSpendTileService::class.java)) }
    }

    /** Redraws the widgets just after midnight, when "today" starts again at zero. */
    fun scheduleMidnightRefresh(context: Context) {
        val zone = ZoneId.systemDefault()
        val next = ZonedDateTime.of(LocalDate.now(zone).plusDays(1).atStartOfDay(), zone).plusMinutes(1)
        val delay = Duration.between(ZonedDateTime.now(zone), next).toMillis().coerceAtLeast(60_000)
        val request = OneTimeWorkRequestBuilder<MidnightWorker>().setInitialDelay(delay, TimeUnit.MILLISECONDS).build()
        WorkManager.getInstance(context).enqueueUniqueWork("midnight-refresh", ExistingWorkPolicy.REPLACE, request)
    }
}

class MidnightWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        Widgets.refresh(applicationContext)
        Widgets.scheduleMidnightRefresh(applicationContext)
        return Result.success()
    }
}

/** Hides the widget's Undo chip a few seconds after a tap (docs/spec.md section 8). */
class UndoChipWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val id = inputData.getString(KEY_ID) ?: return Result.success()
        CashStore.get(applicationContext).clearWidgetUndo(id)
        return Result.success()
    }

    companion object {
        private const val KEY_ID = "transactionId"
        /** How long the chip stays. Undo itself still works for 2 minutes from the app. */
        private const val SHOW_SECONDS = 8L

        fun schedule(context: Context, transactionId: String) {
            val request = OneTimeWorkRequestBuilder<UndoChipWorker>()
                .setInitialDelay(SHOW_SECONDS, TimeUnit.SECONDS)
                .setInputData(workDataOf(KEY_ID to transactionId))
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork("widget-undo", ExistingWorkPolicy.REPLACE, request)
        }
    }
}
