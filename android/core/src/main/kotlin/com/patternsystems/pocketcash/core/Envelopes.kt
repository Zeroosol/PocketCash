package com.patternsystems.pocketcash.core

import java.math.BigDecimal
import java.math.MathContext

/**
 * Envelopes and savings goals (Pro, docs/spec.md section 10). An envelope is an ordinary wallet with a `target`;
 * nothing else about wallets changes.
 */
object Envelopes {
    /** `clamp(balance / target, 0, 1)` when the target is positive, else null (a plain wallet). */
    fun progress(balance: BigDecimal, target: BigDecimal?): Double? {
        if (target == null || target.signum() <= 0) return null
        val ratio = balance.divide(target, MathContext.DECIMAL64).toDouble()
        return ratio.coerceIn(0.0, 1.0)
    }

    /** `max(target − balance, 0)`; zero for a wallet without a target. */
    fun shortBy(balance: BigDecimal, target: BigDecimal?): BigDecimal {
        if (target == null || target.signum() <= 0) return BigDecimal.ZERO
        return (target - balance).max(BigDecimal.ZERO)
    }

    fun isReached(balance: BigDecimal, target: BigDecimal?): Boolean =
        target != null && target.signum() > 0 && balance >= target

    /** The wallets the fill sheet lists for [sourceId]: every other wallet with the same currency, in sortOrder. */
    fun fillTargets(data: AppData, sourceId: String): List<Wallet> {
        val source = data.wallet(sourceId) ?: return emptyList()
        return data.sortedWallets.filter { it.id != source.id && it.currencyCode == source.currencyCode }
    }

    /**
     * The suggested fill: walk the envelopes in sortOrder and give each `min(short by, cash still left in the source)`.
     * Wallets without a target get 0. Every listed wallet has an entry, in sortOrder.
     */
    fun suggestedFill(data: AppData, sourceId: String): LinkedHashMap<String, BigDecimal> {
        val result = LinkedHashMap<String, BigDecimal>()
        var left = data.balanceOf(sourceId).max(BigDecimal.ZERO)
        for (wallet in fillTargets(data, sourceId)) {
            val give = shortBy(data.balanceOf(wallet.id), wallet.target).min(left)
            result[wallet.id] = give
            left -= give
        }
        return result
    }
}

/**
 * The keypad's change calculator (docs/spec.md section 7.2): "Paid ₱500, got ₱130 back" saves one ₱370 spend.
 */
object ChangeMath {
    /**
     * The quick "Paid" chips: the currency's four largest denominations from spec/presets.json that are at least the
     * largest spend button (fewer when the currency has fewer), largest first.
     */
    fun paidChips(currencyCode: String, spendPresets: List<BigDecimal>): List<BigDecimal> {
        val floor = spendPresets.maxOrNull() ?: BigDecimal.ZERO
        return DefaultPresets.forCurrency(currencyCode).denominations
            .filter { it >= floor }
            .sortedDescending()
            .take(4)
    }

    /** What the spend is: `paid − change`, or null while change ≥ paid (Save stays disabled). */
    fun spend(paid: BigDecimal, change: BigDecimal): BigDecimal? {
        if (paid.signum() <= 0 || change.signum() < 0) return null
        if (change >= paid) return null
        return paid - change
    }

    /** True when there's change to check and it isn't less than what was paid. */
    fun changeTooBig(paid: BigDecimal, change: BigDecimal): Boolean = change.signum() > 0 && change >= paid
}
