package com.patternsystems.pocketcash.core

import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.Instant
import java.time.ZoneOffset
import java.util.Locale

class MoneyFormatTest {
    private fun d(s: String) = BigDecimal(s)

    @Test
    fun compactLabels() {
        assertEquals("+1k", MoneyFormat.compact(d("1000"), '+'))
        assertEquals("−100", MoneyFormat.compact(d("100"), MINUS))
        assertEquals("−50", MoneyFormat.compactSigned(d("-50")))
        assertEquals("+10k", MoneyFormat.compactSigned(d("10000")))
        assertEquals("+1.5k", MoneyFormat.compactSigned(d("1500")))
        assertEquals("−0.25", MoneyFormat.compactSigned(d("-0.25")))
        assertEquals("+1M", MoneyFormat.compactSigned(d("1000000")))
        assertEquals("+500k", MoneyFormat.compactSigned(d("500000")))
        assertEquals("2.75", MoneyFormat.compact(d("2.75")))
        assertEquals("1.3k", MoneyFormat.compact(d("1250")))
        assertEquals("20", MoneyFormat.compact(d("20.00")))
    }

    @Test
    fun fullAmountsHideFractionsWhenWhole() {
        val us = Locale.US
        assertEquals("₱680", MoneyFormat.full(d("680"), "PHP", "₱", us))
        assertEquals("$12.50", MoneyFormat.full(d("12.5"), "USD", null, us))
        assertEquals("$1,200", MoneyFormat.full(d("1200.00"), "USD", null, us))
        assertEquals("−$3", MoneyFormat.full(d("-3"), "USD", null, us))
        assertEquals("+€2.75", MoneyFormat.signed(d("2.75"), "EUR", "€", us))
        assertEquals("−₱50", MoneyFormat.signed(d("-50"), "PHP", "₱", us))
        assertTrue(MoneyFormat.full(d("1200"), "JPY", null, us).endsWith("1,200"))
    }

    @Test
    fun fractionDigits() {
        assertEquals(0, MoneyFormat.fractionDigits("JPY"))
        assertEquals(2, MoneyFormat.fractionDigits("USD"))
        assertEquals(2, MoneyFormat.fractionDigits("XYZ?"))
    }

    @Test
    fun presetsComeFromTheSharedTable() {
        val php = DefaultPresets.forCurrency("php")
        assertEquals(listOf(d("1000"), d("500")), php.add)
        assertEquals(listOf(d("100"), d("50"), d("20"), d("10")), php.spend)
        assertEquals(DefaultPresets.fallback, DefaultPresets.forCurrency("ZZZ"))
        val wallet = Wallet.create("Main Wallet", "usd")
        assertEquals("USD", wallet.currencyCode)
        assertEquals(DefaultPresets.forCurrency("USD").spend, wallet.spendPresets)
    }

    @Test
    fun modelsRoundTripWithExactDecimals() {
        val tx = CashTransaction(walletId = "w", amount = d("-2.75"), timestamp = 1, kind = Kind.SPEND, tag = "transit")
        val json = Json.encodeToString(CashTransaction.serializer(), tx)
        assertTrue(json.contains("\"amount\":\"-2.75\""), json)
        assertTrue(json.contains("\"kind\":\"spend\""), json)
        assertEquals(tx, Json.decodeFromString(CashTransaction.serializer(), json))
    }

    @Test
    fun csvExport() {
        val wallet = Wallet.create("Main, Wallet", "PHP")
        val at = Instant.parse("2026-11-25T08:05:00Z").toEpochMilli()
        val txs = listOf(
            CashTransaction(walletId = wallet.id, amount = d("-50"), timestamp = at, kind = Kind.SPEND, tag = "transit", note = "jeep \"fare\""),
            CashTransaction(walletId = wallet.id, amount = d("1000"), timestamp = at - 60_000, kind = Kind.ATM),
        )
        val csv = Csv.export(listOf(wallet), txs, ZoneOffset.UTC).lines()
        assertEquals("date,time,wallet,kind,amount,currency,category,note", csv[0])
        assertEquals("2026-11-25,08:04,\"Main, Wallet\",atm,1000,PHP,,", csv[1])
        assertEquals("2026-11-25,08:05,\"Main, Wallet\",spend,-50,PHP,transit,\"jeep \"\"fare\"\"\"", csv[2])
    }
}
