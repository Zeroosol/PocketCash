package com.patternsystems.pocketcash.core

import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.math.BigDecimal

class LedgerTest {
    private fun d(s: String) = BigDecimal(s)
    private val t0 = 1_800_000_000_000L

    private fun start(cash: String = "500"): Pair<AppData, Wallet> = Ledger.createWallet(AppData(), "Main Wallet", "PHP", null, d(cash), t0)

    @Test
    fun newWalletSavesStartingCashAsAnAdjustment() {
        val (data, wallet) = start()
        assertEquals(0, d("500").compareTo(data.balanceOf(wallet.id)))
        assertEquals(Kind.ADJUST, data.transactions.single().kind)
        assertEquals(wallet, data.defaultWallet)
    }

    @Test
    fun quickButtonsPickTheirKindAndArmTheWidgetUndo() {
        val (data, wallet) = start()
        val (afterSpend, spend) = Ledger.quickAdd(data, wallet.id, d("50"), positive = false, now = t0 + 1, fromWidget = true)!!
        assertEquals(Kind.SPEND, spend.kind)
        assertEquals(0, d("-50").compareTo(spend.amount))
        assertEquals(spend.id, afterSpend.widgetUndo?.transactionId)

        val (afterPlus, plus) = Ledger.quickAdd(afterSpend, wallet.id, d("1000"), positive = true, now = t0 + 2, fromWidget = false)!!
        assertEquals(Kind.ATM, plus.kind)
        assertEquals(spend.id, afterPlus.widgetUndo?.transactionId, "an in-app tap leaves the widget's chip alone")

        val noAtm = afterPlus.copy(settings = afterPlus.settings.copy(plusButtonsAreATM = false))
        assertEquals(Kind.IN, Ledger.quickAdd(noAtm, wallet.id, d("100"), true, t0 + 3, false)!!.second.kind)
        assertEquals(0, d("1450").compareTo(afterPlus.balanceOf(wallet.id)))
    }

    @Test
    fun widgetUndoOnlyWithinTwoMinutes() {
        val (data, wallet) = start()
        val (after, tx) = Ledger.quickAdd(data, wallet.id, d("20"), false, t0, true)!!
        val undone = Ledger.undoWidget(after, tx.id, t0 + 60_000)
        assertNull(undone.transactions.firstOrNull { it.id == tx.id })
        assertNull(undone.widgetUndo)
        val late = Ledger.undoWidget(after, tx.id, t0 + Ledger.UNDO_LIMIT_MS + 1)
        assertNotNull(late.transactions.firstOrNull { it.id == tx.id })
        assertNull(late.widgetUndo)
    }

    @Test
    fun reconcileSavesTheDifference() {
        val (data, wallet) = start()
        val (after, adj) = Ledger.reconcile(data, wallet.id, d("420"), t0 + 5)
        assertEquals(0, d("-80").compareTo(adj!!.amount))
        assertEquals(0, d("80").compareTo(Analytics.spending(adj)), "a negative count is unrecorded spending")
        assertNull(Ledger.reconcile(after, wallet.id, d("420"), t0 + 6).second)
    }

    @Test
    fun transfersMoveCashAndDeleteAsAPair() {
        val (one, main) = start()
        val (two, stash) = Ledger.createWallet(one, "Emergency Stash", "PHP", null, BigDecimal.ZERO, t0)
        val moved = Ledger.transfer(two, main.id, stash.id, d("300"), d("300"), t0 + 10)
        assertEquals(0, d("200").compareTo(moved.balanceOf(main.id)))
        assertEquals(0, d("300").compareTo(moved.balanceOf(stash.id)))
        val cycles = Analytics.cycles(moved.transactionsOf(main.id), java.time.Instant.ofEpochMilli(t0 + 20))
        assertEquals(0, BigDecimal.ZERO.compareTo(cycles.single().spent), "transfers are not spending")
        val half = moved.transactions.first { it.walletId == stash.id }
        val undone = Ledger.delete(moved, half.id)
        assertEquals(0, d("500").compareTo(undone.balanceOf(main.id)))
        assertEquals(0, BigDecimal.ZERO.compareTo(undone.balanceOf(stash.id)))
    }

    @Test
    fun deletingAWalletKeepsTheOtherHalfOfItsTransfers() {
        val (one, main) = start()
        val (two, stash) = Ledger.createWallet(one, "Stash", "PHP", null, BigDecimal.ZERO, t0)
        val moved = Ledger.transfer(two, main.id, stash.id, d("100"), d("100"), t0 + 1)
        val after = Ledger.deleteWallet(moved, stash.id)
        assertEquals(listOf(main.id), after.wallets.map { it.id })
        assertEquals(0, d("400").compareTo(after.balanceOf(main.id)))
        assertEquals(after, Ledger.deleteWallet(after, main.id), "the last wallet stays")
    }

    @Test
    fun freeUsesDefaultButtonsAndTheSmallestSpendForQuickSpend() {
        val (data, wallet) = start()
        val custom = Ledger.updateWallet(data, wallet.copy(spendPresets = listOf(d("13"), d("7"))))
        assertEquals(DefaultPresets.forCurrency("PHP").spend, custom.presetsOf(custom.wallet(wallet.id)!!, isPro = false).second)
        assertEquals(listOf(d("13"), d("7")), custom.presetsOf(custom.wallet(wallet.id)!!, isPro = true).second)
        assertEquals(0, d("10").compareTo(custom.quickSpendAmount(isPro = false)))
        assertEquals(0, d("7").compareTo(custom.quickSpendAmount(isPro = true)))
        val chosen = custom.copy(settings = custom.settings.copy(quickSpendAmount = d("13")))
        assertEquals(0, d("13").compareTo(chosen.quickSpendAmount(isPro = false)))
    }

    @Test
    fun lowCashRemindsOncePerDrop() {
        val (data, wallet) = start()
        var state = Ledger.updateWallet(data, wallet.copy(lowCashThreshold = d("200")))
            .let { it.copy(settings = it.settings.copy(lowCashAlerts = true)) }
        state = Ledger.add(state, wallet.id, d("350"), Kind.SPEND, t0 + 1)!!.first
        val (first, fresh) = Ledger.lowCash(state)
        assertEquals(listOf(wallet.id), fresh.map { it.id })
        val (again, none) = Ledger.lowCash(Ledger.add(first, wallet.id, d("10"), Kind.SPEND, t0 + 2)!!.first)
        assertTrue(none.isEmpty())
        val refilled = Ledger.add(again, wallet.id, d("1000"), Kind.ATM, t0 + 3)!!.first
        assertTrue(Ledger.lowCash(refilled).first.lowCashNotified.isEmpty())
    }

    @Test
    fun appDataSurvivesAJsonRoundTrip() {
        val (data, wallet) = start()
        val (after, _) = Ledger.quickAdd(data, wallet.id, d("2.75"), false, t0 + 1, true)!!
        val text = json.encodeToString(AppData.serializer(), after)
        assertEquals(after, json.decodeFromString(AppData.serializer(), text))
        // Older files without newer fields still load.
        assertEquals(AppData(), json.decodeFromString(AppData.serializer(), "{\"version\":1,\"future\":true}"))
    }

    /** The app's store uses the same settings (CashStore.kt). */
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
}
