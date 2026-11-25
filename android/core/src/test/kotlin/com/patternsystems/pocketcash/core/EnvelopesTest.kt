package com.patternsystems.pocketcash.core

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.math.BigDecimal

/** Envelopes and savings goals (docs/spec.md section 10) and the change calculator (section 7.2). */
class EnvelopesTest {
    private fun d(s: String) = BigDecimal(s)
    private val t0 = 1_800_000_000_000L
    private fun same(expected: String, actual: BigDecimal?) = assertEquals(0, d(expected).compareTo(actual), "expected $expected, got $actual")

    @Test
    fun progressAndShortBy() {
        assertEquals(0.46, Envelopes.progress(d("2300"), d("5000"))!!, 1e-12)
        assertEquals(1.0, Envelopes.progress(d("6000"), d("5000"))!!)
        assertEquals(0.0, Envelopes.progress(d("-20"), d("5000"))!!)
        assertNull(Envelopes.progress(d("100"), null))
        assertNull(Envelopes.progress(d("100"), BigDecimal.ZERO))
        same("2700", Envelopes.shortBy(d("2300"), d("5000")))
        same("0", Envelopes.shortBy(d("6000"), d("5000")))
        same("0", Envelopes.shortBy(d("100"), null))
        assertTrue(Envelopes.isReached(d("5000"), d("5000.00")))
        assertFalse(Envelopes.isReached(d("4999.99"), d("5000")))
    }

    /** Main ₱5,200; Rent short by ₱3,000, Food (no target), Savings short by ₱1,000, Groceries short by ₱2,000, Yen. */
    private fun setup(): Triple<AppData, Wallet, List<Wallet>> {
        var (data, main) = Ledger.createWallet(AppData(), "Main Wallet", "PHP", null, d("5200"), t0)
        fun add(name: String, currency: String, cash: String, target: String?): Wallet {
            val (next, w) = Ledger.createWallet(data, name, currency, null, d(cash), t0, target = target?.let(::d))
            data = next
            return w
        }
        val rent = add("Rent", "PHP", "2000", "5000")
        val food = add("Food", "PHP", "0", null)
        val yen = add("Japan trip", "JPY", "0", "100000")
        val savings = add("Savings", "PHP", "500", "1500")
        val groceries = add("Groceries", "PHP", "0", "2000")
        return Triple(data, main, listOf(rent, food, yen, savings, groceries))
    }

    @Test
    fun suggestedFillWalksEnvelopesInOrderUntilTheSourceRunsOut() {
        val (data, main, w) = setup()
        val (rent, food, yen, savings, groceries) = w
        assertEquals(listOf(rent.id, food.id, savings.id, groceries.id), Envelopes.fillTargets(data, main.id).map { it.id }, "same currency only")
        val fill = Envelopes.suggestedFill(data, main.id)
        assertEquals(listOf(rent.id, food.id, savings.id, groceries.id), fill.keys.toList())
        same("3000", fill[rent.id])
        same("0", fill[food.id])
        same("1000", fill[savings.id])
        same("1200", fill[groceries.id]) // only ₱1,200 is left of ₱5,200
        assertFalse(yen.id in fill)
    }

    @Test
    fun fillWritesOneTransferPairPerEnvelopeInOneChange() {
        val (data, main, w) = setup()
        val (rent, food, _, savings, groceries) = w
        val amounts = Envelopes.suggestedFill(data, main.id)
        val after = Ledger.fill(data, main.id, amounts, t0 + 5)
        val added = after.transactions - data.transactions.toSet()
        assertEquals(6, added.size, "three envelopes with a non-zero amount, two halves each")
        assertTrue(added.all { it.kind == Kind.TRANSFER && it.timestamp == t0 + 5 })
        assertEquals(3, added.groupBy { it.transferId }.size)
        same("0", after.balanceOf(main.id))
        same("5000", after.balanceOf(rent.id))
        same("0", after.balanceOf(food.id))
        same("1500", after.balanceOf(savings.id))
        same("1200", after.balanceOf(groceries.id))
        assertEquals(1.0, Envelopes.progress(after.balanceOf(rent.id), rent.target)!!)

        // Nothing to move, more than the source holds, or another currency: nothing changes.
        assertSame(data, Ledger.fill(data, main.id, mapOf(rent.id to BigDecimal.ZERO), t0))
        assertSame(data, Ledger.fill(data, main.id, mapOf(rent.id to d("5200.01")), t0))
        assertSame(data, Ledger.fill(data, main.id, mapOf(w[2].id to d("10")), t0))
    }

    @Test
    fun changeCalculator() {
        val php = DefaultPresets.forCurrency("PHP")
        assertEquals(listOf("1000", "500", "200", "100"), ChangeMath.paidChips("PHP", php.spend).map { it.toPlainString() })
        // Fewer than four bills at least as large as the biggest spend button.
        assertEquals(listOf("10000", "5000", "1000"), ChangeMath.paidChips("JPY", listOf(d("1000"), d("50"))).map { it.toPlainString() })
        assertEquals(listOf("100000"), ChangeMath.paidChips("IDR", listOf(d("100000"))).map { it.toPlainString() })
        assertTrue(ChangeMath.paidChips("PHP", listOf(d("5000"))).isEmpty())

        same("370", ChangeMath.spend(d("500"), d("130")))
        same("500", ChangeMath.spend(d("500"), BigDecimal.ZERO))
        same("0.75", ChangeMath.spend(d("20"), d("19.25")))
        assertNull(ChangeMath.spend(d("500"), d("500")), "change must be less than what you paid")
        assertNull(ChangeMath.spend(d("500"), d("600")))
        assertNull(ChangeMath.spend(BigDecimal.ZERO, BigDecimal.ZERO))
        assertTrue(ChangeMath.changeTooBig(d("100"), d("100")))
        assertFalse(ChangeMath.changeTooBig(d("100"), BigDecimal.ZERO))
    }
}
