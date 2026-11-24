package com.patternsystems.pocketcash.data

import android.annotation.SuppressLint
import android.content.Context
import android.util.Log
import androidx.core.util.AtomicFile
import com.patternsystems.pocketcash.BuildConfig
import com.patternsystems.pocketcash.core.AppData
import com.patternsystems.pocketcash.core.CashTransaction
import com.patternsystems.pocketcash.core.Kind
import com.patternsystems.pocketcash.core.Ledger
import com.patternsystems.pocketcash.core.Settings
import com.patternsystems.pocketcash.core.Wallet
import com.patternsystems.pocketcash.widget.UndoChipWorker
import com.patternsystems.pocketcash.widget.Widgets
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import java.io.File
import java.math.BigDecimal

/** Pro is owned, or switched on with the debug-build switch. */
val AppData.isPro: Boolean get() = proPurchased || (BuildConfig.DEBUG && settings.debugPro)

/**
 * The one place PocketCash's data lives: a JSON file in app storage. Every writer (the screens, widget buttons, the
 * Quick Settings tile, Play Billing) goes through [update], which saves under a lock and then refreshes the widgets,
 * the tile and the low-cash reminders, so nothing ever shows a stale balance (docs/spec.md section 8).
 */
class CashStore private constructor(context: Context) {
    private val app = context.applicationContext
    private val file = AtomicFile(File(app.filesDir, FILE_NAME))
    private val mutex = Mutex()
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    /** Outlives screens, so a save started by a tap always finishes. */
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val _data = MutableStateFlow(load())
    val data: StateFlow<AppData> = _data.asStateFlow()

    private fun load(): AppData = try {
        if (file.baseFile.exists()) json.decodeFromString(AppData.serializer(), String(file.readFully(), Charsets.UTF_8)) else AppData()
    } catch (e: Exception) {
        // Never lose the old file to a bad read: keep a copy beside it and start empty.
        Log.e(TAG, "Couldn't read $FILE_NAME", e)
        runCatching { file.baseFile.copyTo(File(app.filesDir, "$FILE_NAME.broken"), overwrite = true) }
        AppData()
    }

    /** Applies [change], saves, and refreshes widgets, the tile and reminders. Returns the new data. */
    suspend fun update(change: (AppData) -> AppData): AppData = mutex.withLock {
        val before = _data.value
        val changed = change(before)
        if (changed == before) return@withLock before
        val (next, lowCash) = Ledger.lowCash(changed)
        withContext(Dispatchers.IO) { save(next) }
        _data.value = next
        LowCashNotifier.notify(app, next, lowCash)
        scope.launch { Widgets.refresh(app) }
        next
    }

    private fun save(data: AppData) {
        val out = file.startWrite()
        try {
            out.write(json.encodeToString(AppData.serializer(), data).toByteArray(Charsets.UTF_8))
            file.finishWrite(out)
        } catch (e: Exception) {
            file.failWrite(out)
            throw e
        }
    }

    // MARK: Actions

    private fun now() = System.currentTimeMillis()

    /** A transaction from the keypad, with its sign forced by [kind]. */
    suspend fun add(walletId: String, magnitude: BigDecimal, kind: Kind, tag: String? = null, note: String? = null): CashTransaction? {
        var created: CashTransaction? = null
        update { data -> Ledger.add(data, walletId, magnitude, kind, now(), tag, note)?.also { created = it.second }?.first ?: data }
        return created
    }

    /** A 1-tap button. From a widget or the tile it also shows the widget's Undo chip for a few seconds. */
    suspend fun quickAdd(walletId: String, magnitude: BigDecimal, positive: Boolean, fromWidget: Boolean): CashTransaction? {
        var created: CashTransaction? = null
        update { data -> Ledger.quickAdd(data, walletId, magnitude, positive, now(), fromWidget)?.also { created = it.second }?.first ?: data }
        if (fromWidget) created?.let { UndoChipWorker.schedule(app, it.id) }
        return created
    }

    /** The Quick spend tile and shortcut: spends the Quick spend amount from its wallet. */
    suspend fun quickSpend(): CashTransaction? {
        val data = _data.value
        val wallet = data.quickSpendWallet(data.isPro) ?: return null
        val amount = data.quickSpendAmount(data.isPro) ?: return null
        return quickAdd(wallet.id, amount, positive = false, fromWidget = true)
    }

    /** Deletes a transaction (and its transfer partner). Returns what was removed, for Undo. */
    suspend fun delete(transactionId: String): List<CashTransaction> {
        var removed = emptyList<CashTransaction>()
        update { data ->
            val next = Ledger.delete(data, transactionId)
            removed = data.transactions.filter { tx -> next.transactions.none { it.id == tx.id } }
            next
        }
        return removed
    }

    suspend fun restore(removed: List<CashTransaction>) = update { Ledger.restore(it, removed) }

    suspend fun undoWidget(transactionId: String) = update { Ledger.undoWidget(it, transactionId, now()) }

    suspend fun clearWidgetUndo(transactionId: String) = update { data ->
        if (data.widgetUndo?.transactionId == transactionId) data.copy(widgetUndo = null) else data
    }

    suspend fun updateTransaction(tx: CashTransaction) = update { data ->
        data.copy(transactions = data.transactions.map { if (it.id == tx.id) tx else it })
    }

    suspend fun reconcile(walletId: String, actual: BigDecimal): CashTransaction? {
        var created: CashTransaction? = null
        update { data -> Ledger.reconcile(data, walletId, actual, now()).also { created = it.second }.first }
        return created
    }

    suspend fun transfer(fromId: String, toId: String, sent: BigDecimal, received: BigDecimal) =
        update { Ledger.transfer(it, fromId, toId, sent, received, now()) }

    suspend fun createWallet(name: String, currencyCode: String, symbol: String?, startingCash: BigDecimal): Wallet? {
        var created: Wallet? = null
        update { data -> Ledger.createWallet(data, name, currencyCode, symbol, startingCash, now()).also { created = it.second }.first }
        return created
    }

    suspend fun updateWallet(wallet: Wallet) = update { Ledger.updateWallet(it, wallet) }

    suspend fun deleteWallet(walletId: String) = update { Ledger.deleteWallet(it, walletId) }

    suspend fun updateSettings(change: (Settings) -> Settings) = update { it.copy(settings = change(it.settings)) }

    suspend fun setProPurchased(owned: Boolean) = update { if (it.proPurchased == owned) it else it.copy(proPurchased = owned) }

    companion object {
        const val FILE_NAME = "pocketcash.json"
        private const val TAG = "CashStore"

        // Holds only the application context, which lives as long as the process.
        @SuppressLint("StaticFieldLeak")
        @Volatile private var instance: CashStore? = null

        fun get(context: Context): CashStore =
            instance ?: synchronized(this) { instance ?: CashStore(context).also { instance = it } }
    }
}
