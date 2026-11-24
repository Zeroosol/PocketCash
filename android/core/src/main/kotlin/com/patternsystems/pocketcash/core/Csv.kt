package com.patternsystems.pocketcash.core

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** CSV export (docs/spec.md section 9): `date,time,wallet,kind,amount,currency,category,note`. */
object Csv {
    private val DATE = DateTimeFormatter.ISO_LOCAL_DATE
    private val TIME = DateTimeFormatter.ofPattern("HH:mm")

    /** Every transaction of [wallets], oldest first, with times in [zone]. Amounts use a plain `-` for cash out. */
    fun export(wallets: List<Wallet>, transactions: List<CashTransaction>, zone: ZoneId): String {
        val byId = wallets.associateBy { it.id }
        val sb = StringBuilder("date,time,wallet,kind,amount,currency,category,note\n")
        for (tx in transactions.sortedBy { it.timestamp }) {
            val wallet = byId[tx.walletId] ?: continue
            val at = Instant.ofEpochMilli(tx.timestamp).atZone(zone)
            val row = listOf(
                DATE.format(at),
                TIME.format(at),
                wallet.name,
                tx.kind.name.lowercase(),
                tx.amount.stripTrailingZeros().toPlainString(),
                wallet.currencyCode,
                tx.tag.orEmpty(),
                tx.note.orEmpty(),
            )
            sb.append(row.joinToString(",") { field(it) }).append('\n')
        }
        return sb.toString()
    }

    private fun field(value: String): String =
        if (value.any { it == ',' || it == '"' || it == '\n' || it == '\r' }) "\"" + value.replace("\"", "\"\"") + "\"" else value
}
