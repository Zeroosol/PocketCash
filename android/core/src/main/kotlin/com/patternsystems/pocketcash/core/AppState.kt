package com.patternsystems.pocketcash.core

import kotlinx.serialization.Serializable
import java.math.BigDecimal

/** The last amount a widget or the Quick Settings tile saved, for the widget's Undo chip (docs/spec.md section 8). */
@Serializable
data class LastAction(
    val walletId: String,
    val transactionId: String,
    val amount: Money,
    /** Epoch milliseconds. */
    val at: Long,
)

/** Everything PocketCash keeps on the phone, saved as one JSON file. */
@Serializable
data class AppData(
    val version: Int = 1,
    val wallets: List<Wallet> = emptyList(),
    val transactions: List<CashTransaction> = emptyList(),
    val settings: Settings = Settings(),
    /** Google Play's answer the last time it was asked whether PocketCash Pro is owned. */
    val proPurchased: Boolean = false,
    /** Shown as the widget's Undo chip until a short timer clears it. */
    val widgetUndo: LastAction? = null,
    /** Wallets already reminded about low cash; cleared once a wallet is back above its threshold. */
    val lowCashNotified: Set<String> = emptySet(),
) {
    val sortedWallets: List<Wallet> get() = wallets.sortedBy { it.sortOrder }

    /** The first wallet is the default. */
    val defaultWallet: Wallet? get() = sortedWallets.firstOrNull()

    fun wallet(id: String?): Wallet? = id?.let { wanted -> wallets.firstOrNull { it.id == wanted } }

    fun transactionsOf(walletId: String): List<CashTransaction> = transactions.filter { it.walletId == walletId }

    fun balanceOf(walletId: String): BigDecimal = Analytics.balance(transactionsOf(walletId))

    /** The wallet the app shows. Free shows the default wallet only. */
    fun selectedWallet(isPro: Boolean): Wallet? =
        (if (isPro) wallet(settings.selectedWalletId) else null) ?: defaultWallet

    /** The wallet the Quick spend tile and control spend from. */
    fun quickSpendWallet(isPro: Boolean): Wallet? =
        (if (isPro) wallet(settings.quickSpendWalletId) else null) ?: defaultWallet

    /** The Quick spend amount: the chosen one, else the wallet's smallest spend button (the "daily commute fare"). */
    fun quickSpendAmount(isPro: Boolean): BigDecimal? {
        settings.quickSpendAmount?.takeIf { it.signum() > 0 }?.let { return it }
        val wallet = quickSpendWallet(isPro) ?: return null
        return presetsOf(wallet, isPro).second.minOrNull()
    }

    /** (cash-in buttons, spend buttons). Free always uses the currency's defaults; Pro uses the wallet's own. */
    fun presetsOf(wallet: Wallet, isPro: Boolean): Pair<List<BigDecimal>, List<BigDecimal>> {
        if (!isPro) {
            val d = DefaultPresets.forCurrency(wallet.currencyCode)
            return d.add to d.spend
        }
        val d = DefaultPresets.forCurrency(wallet.currencyCode)
        return wallet.addPresets.ifEmpty { d.add } to wallet.spendPresets.ifEmpty { d.spend }
    }
}

/** Pure state changes. The app's store runs them under a lock and saves the result. */
object Ledger {
    /** Adds a transaction with its sign forced to match its kind. */
    fun add(
        data: AppData,
        walletId: String,
        magnitude: BigDecimal,
        kind: Kind,
        now: Long,
        tag: String? = null,
        note: String? = null,
    ): Pair<AppData, CashTransaction>? {
        if (data.wallet(walletId) == null || magnitude.signum() == 0) return null
        val abs = magnitude.abs()
        val amount = when (kind) {
            Kind.SPEND -> abs.negate()
            Kind.IN, Kind.ATM -> abs
            Kind.ADJUST, Kind.TRANSFER -> magnitude
        }
        val tx = CashTransaction(
            walletId = walletId,
            amount = amount,
            timestamp = now,
            kind = kind,
            tag = tag,
            note = note?.trim()?.takeIf { it.isNotEmpty() },
        )
        return data.copy(transactions = data.transactions + tx) to tx
    }

    /**
     * A 1-tap button: `+` saves an ATM withdrawal when "Count + buttons as ATM withdrawals" is on (else cash in), `−`
     * saves a spend. From a widget or the tile, it also arms the widget's Undo chip.
     */
    fun quickAdd(data: AppData, walletId: String, magnitude: BigDecimal, positive: Boolean, now: Long, fromWidget: Boolean): Pair<AppData, CashTransaction>? {
        val kind = when {
            !positive -> Kind.SPEND
            data.settings.plusButtonsAreATM -> Kind.ATM
            else -> Kind.IN
        }
        val (next, tx) = add(data, walletId, magnitude, kind, now) ?: return null
        val undo = if (fromWidget) LastAction(walletId, tx.id, tx.amount, now) else next.widgetUndo
        return next.copy(widgetUndo = undo) to tx
    }

    /** Deletes a transaction, and the other half when it's a transfer. */
    fun delete(data: AppData, transactionId: String): AppData {
        val tx = data.transactions.firstOrNull { it.id == transactionId } ?: return data
        val gone = data.transactions.filter { it.id == tx.id || (tx.transferId != null && it.transferId == tx.transferId) }.map { it.id }.toSet()
        return data.copy(
            transactions = data.transactions.filter { it.id !in gone },
            widgetUndo = data.widgetUndo?.takeIf { it.transactionId !in gone },
        )
    }

    /** The widget's Undo: only within 2 minutes of the tap. */
    fun undoWidget(data: AppData, transactionId: String, now: Long): AppData {
        val undo = data.widgetUndo ?: return data
        if (undo.transactionId != transactionId) return data
        if (now - undo.at > UNDO_LIMIT_MS) return data.copy(widgetUndo = null)
        return delete(data, transactionId)
    }

    /** Puts back a deleted transaction (the in-app Undo after a swipe). */
    fun restore(data: AppData, removed: List<CashTransaction>): AppData {
        val have = data.transactions.map { it.id }.toSet()
        val back = removed.filter { it.id !in have && data.wallet(it.walletId) != null }
        return data.copy(transactions = data.transactions + back)
    }

    /** "Count my cash": saves the difference between the counted cash and the balance as an adjustment. */
    fun reconcile(data: AppData, walletId: String, actual: BigDecimal, now: Long): Pair<AppData, CashTransaction?> {
        val diff = actual - data.balanceOf(walletId)
        if (diff.signum() == 0 || data.wallet(walletId) == null) return data to null
        val (next, tx) = add(data, walletId, diff, Kind.ADJUST, now) ?: return data to null
        return next to tx
    }

    /** Moves cash between wallets (Pro). [received] differs from [sent] when the currencies differ. */
    fun transfer(data: AppData, fromId: String, toId: String, sent: BigDecimal, received: BigDecimal, now: Long): AppData {
        val from = data.wallet(fromId) ?: return data
        val to = data.wallet(toId) ?: return data
        if (from.id == to.id || sent.signum() <= 0 || received.signum() <= 0) return data
        val link = newId()
        val out = CashTransaction(walletId = from.id, amount = sent.negate(), timestamp = now, kind = Kind.TRANSFER, note = "To ${to.name}", transferId = link)
        val inn = CashTransaction(walletId = to.id, amount = received, timestamp = now, kind = Kind.TRANSFER, note = "From ${from.name}", transferId = link)
        return data.copy(transactions = data.transactions + out + inn)
    }

    /** A new wallet with the currency's default buttons, and its starting cash as an adjustment. */
    fun createWallet(data: AppData, name: String, currencyCode: String, symbol: String?, startingCash: BigDecimal, now: Long): Pair<AppData, Wallet> {
        val order = (data.wallets.maxOfOrNull { it.sortOrder } ?: -1) + 1
        val wallet = Wallet.create(name.trim().ifEmpty { "Wallet" }, currencyCode, order, symbol).copy(createdAt = now)
        var next = data.copy(wallets = data.wallets + wallet)
        if (startingCash.signum() != 0) {
            next = add(next, wallet.id, startingCash, Kind.ADJUST, now, note = "Starting cash")?.first ?: next
        }
        return next to wallet
    }

    fun updateWallet(data: AppData, wallet: Wallet): AppData =
        data.copy(wallets = data.wallets.map { if (it.id == wallet.id) wallet else it })

    /** Deletes a wallet and its transactions. The last wallet can't be deleted. */
    fun deleteWallet(data: AppData, walletId: String): AppData {
        if (data.wallets.size <= 1) return data
        val links = data.transactions.filter { it.walletId == walletId }.mapNotNull { it.transferId }.toSet()
        val settings = data.settings.let {
            it.copy(
                selectedWalletId = it.selectedWalletId.takeUnless { id -> id == walletId },
                quickSpendWalletId = it.quickSpendWalletId.takeUnless { id -> id == walletId },
            )
        }
        return data.copy(
            wallets = data.wallets.filter { it.id != walletId },
            // The other half of a transfer stays, so the other wallet's balance doesn't change.
            transactions = data.transactions.filter { it.walletId != walletId }.map {
                if (it.transferId != null && it.transferId in links) it.copy(transferId = null) else it
            },
            settings = settings,
            widgetUndo = data.widgetUndo?.takeIf { it.walletId != walletId },
            lowCashNotified = data.lowCashNotified - walletId,
        )
    }

    /** Wallets that just dropped below their low-cash threshold, and the updated reminder bookkeeping. */
    fun lowCash(data: AppData): Pair<AppData, List<Wallet>> {
        if (!data.settings.lowCashAlerts) {
            return (if (data.lowCashNotified.isEmpty()) data else data.copy(lowCashNotified = emptySet())) to emptyList()
        }
        val below = data.wallets.filter { w -> w.lowCashThreshold?.let { data.balanceOf(w.id) < it } == true }
        val fresh = below.filter { it.id !in data.lowCashNotified }
        val notified = below.map { it.id }.toSet()
        return (if (notified == data.lowCashNotified) data else data.copy(lowCashNotified = notified)) to fresh
    }

    const val UNDO_LIMIT_MS = 2 * 60 * 1000L
}
