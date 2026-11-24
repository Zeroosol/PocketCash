package com.patternsystems.pocketcash.core

import java.math.BigDecimal
import java.time.Instant
import java.time.ZoneId

private const val DAY_MS = 86_400_000.0

/** One ATM cycle (docs/spec.md section 4). Pinned by spec/vectors/cycles.json. */
data class Cycle(
    /** False for the starting cycle (transactions before the first ATM withdrawal). */
    val isATM: Boolean,
    val start: Instant,
    /** The next ATM's timestamp; null while the cycle is open. */
    val end: Instant?,
    val withdrawn: BigDecimal,
    val spent: BigDecimal,
    /** Cash in during the cycle, not counting the cycle's own ATM withdrawal. */
    val inflow: BigDecimal,
    val days: Double,
    val avgDaily: Double,
    /** The spend amount that drained the wallet most; null without spends. */
    val topAmount: BigDecimal?,
    val topAmountCount: Int,
    val topAmountTotal: BigDecimal,
    val topTag: String?,
    /** The cycle's transactions, oldest first. */
    val transactions: List<CashTransaction>,
) {
    val isOpen: Boolean get() = end == null
}

object Analytics {
    /** Money that left the wallet: spends and negative reconciles (docs/spec.md section 3). */
    fun spending(tx: CashTransaction): BigDecimal = when {
        tx.kind == Kind.SPEND -> tx.amount.negate()
        tx.kind == Kind.ADJUST && tx.amount.signum() < 0 -> tx.amount.negate()
        else -> BigDecimal.ZERO
    }

    /** Cash that came in: ATM, other cash in, and positive reconciles. Transfers never count. */
    fun inflow(tx: CashTransaction): BigDecimal = when {
        tx.kind == Kind.IN || tx.kind == Kind.ATM -> tx.amount
        tx.kind == Kind.ADJUST && tx.amount.signum() > 0 -> tx.amount
        else -> BigDecimal.ZERO
    }

    /** A wallet's balance is always the sum of its transactions. */
    fun balance(txs: List<CashTransaction>): BigDecimal = txs.fold(BigDecimal.ZERO) { sum, tx -> sum + tx.amount }

    /** Σ spending from the start of today until the start of tomorrow, in [zone]. */
    fun todaySpent(txs: List<CashTransaction>, now: Instant, zone: ZoneId): BigDecimal {
        val day = now.atZone(zone).toLocalDate()
        val from = day.atStartOfDay(zone).toInstant().toEpochMilli()
        val to = day.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
        return txs.filter { it.timestamp in from until to }.fold(BigDecimal.ZERO) { s, tx -> s + spending(tx) }
    }

    /** Spending since a moment (inclusive) until now. */
    fun spentSince(txs: List<CashTransaction>, fromMillis: Long, now: Instant): BigDecimal =
        txs.filter { it.timestamp >= fromMillis && it.timestamp <= now.toEpochMilli() }
            .fold(BigDecimal.ZERO) { s, tx -> s + spending(tx) }

    /** ATM cycles, oldest first. */
    fun cycles(txs: List<CashTransaction>, now: Instant): List<Cycle> {
        val sorted = txs.sortedBy { it.timestamp }
        if (sorted.isEmpty()) return emptyList()
        val groups = mutableListOf<MutableList<CashTransaction>>()
        for (tx in sorted) {
            if (tx.kind == Kind.ATM || groups.isEmpty()) groups.add(mutableListOf(tx)) else groups.last().add(tx)
        }
        return groups.mapIndexed { i, group ->
            val first = group.first()
            val isATM = first.kind == Kind.ATM
            val start = Instant.ofEpochMilli(first.timestamp)
            val end = groups.getOrNull(i + 1)?.first()?.let { Instant.ofEpochMilli(it.timestamp) }
            val days = ((end ?: now).toEpochMilli() - start.toEpochMilli()) / DAY_MS
            val spent = group.fold(BigDecimal.ZERO) { s, tx -> s + spending(tx) }
            val inflow = group.filter { !(isATM && it === first) }.fold(BigDecimal.ZERO) { s, tx -> s + inflow(tx) }

            // "Which denomination drains the wallet fastest": biggest total, then most taps, then the larger amount.
            val byAmount = group.filter { it.kind == Kind.SPEND }
                .groupBy { it.amount.abs().stripTrailingZeros() }
                .map { (amount, list) -> Triple(amount, list.size, amount.multiply(BigDecimal(list.size))) }
            val top = byAmount.sortedWith(
                compareByDescending<Triple<BigDecimal, Int, BigDecimal>> { it.third }
                    .thenByDescending { it.second }
                    .thenByDescending { it.first },
            ).firstOrNull()

            val byTag = group.filter { it.tag != null }
                .groupBy { it.tag!! }
                .mapValues { (_, list) -> list.fold(BigDecimal.ZERO) { s, tx -> s + spending(tx) } }
                .filterValues { it.signum() > 0 }
            val topTag = byTag.entries.sortedWith(compareByDescending<Map.Entry<String, BigDecimal>> { it.value }.thenBy { it.key })
                .firstOrNull()?.key

            Cycle(
                isATM = isATM,
                start = start,
                end = end,
                withdrawn = if (isATM) first.amount else BigDecimal.ZERO,
                spent = spent,
                inflow = inflow,
                days = days,
                avgDaily = spent.toDouble() / maxOf(days, 1.0),
                topAmount = top?.first,
                topAmountCount = top?.second ?: 0,
                topAmountTotal = top?.third ?: BigDecimal.ZERO,
                topTag = topTag,
                transactions = group,
            )
        }
    }

    /** Spending per day over the last 14 days (fewer for a new wallet). Null without transactions. */
    fun burnRatePerDay(txs: List<CashTransaction>, now: Instant): Double? {
        val first = txs.minOfOrNull { it.timestamp } ?: return null
        val nowMs = now.toEpochMilli()
        val windowStart = nowMs - 14 * DAY_MS.toLong()
        val spent = txs.filter { it.timestamp > windowStart && it.timestamp <= nowMs }
            .fold(BigDecimal.ZERO) { s, tx -> s + spending(tx) }
        val days = ((nowMs - first) / DAY_MS).coerceIn(1.0, 14.0)
        return spent.toDouble() / days
    }

    /** Days the cash lasts at [burnRate]; null unless both are positive. */
    fun runwayDays(balance: BigDecimal, burnRate: Double?): Double? {
        if (burnRate == null || burnRate <= 0 || balance.signum() <= 0) return null
        return balance.toDouble() / burnRate
    }

    /** Spending per category since [fromMillis], biggest first. Untagged spending is reported under null. */
    fun spendingByTag(txs: List<CashTransaction>, fromMillis: Long, now: Instant): List<Pair<String?, BigDecimal>> =
        txs.filter { it.timestamp >= fromMillis && it.timestamp <= now.toEpochMilli() }
            .groupBy { it.tag }
            .mapValues { (_, list) -> list.fold(BigDecimal.ZERO) { s, tx -> s + spending(tx) } }
            .filterValues { it.signum() > 0 }
            .toList()
            .sortedByDescending { it.second }
}
