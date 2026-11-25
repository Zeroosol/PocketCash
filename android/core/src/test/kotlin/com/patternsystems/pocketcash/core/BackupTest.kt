package com.patternsystems.pocketcash.core

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.double
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.io.File
import java.math.BigDecimal
import java.time.Instant

/** spec/backup/example-v1.json and the rules of docs/spec.md section 11, shared with the iPhone app. */
class BackupTest {
    private val example = File(System.getProperty("pocketcash.backup") ?: "../../spec/backup", "example-v1.json").readText()
    private fun d(s: String) = BigDecimal(s)
    private fun same(expected: String, actual: BigDecimal, what: String) =
        assertEquals(0, d(expected).compareTo(actual), "$what: expected $expected, got ${actual.toPlainString()}")

    @Test
    fun decodesTheSharedExampleAndMatchesItsExpectedTotals() {
        val expected = Json.parseToJsonElement(example).jsonObject["_expected"]!!.jsonObject
        val contents = Backup.decode(example)
        val data = contents.applyTo(AppData())

        assertEquals(expected["wallets"]!!.jsonPrimitive.int, data.wallets.size)
        assertEquals(expected["transactions"]!!.jsonPrimitive.int, data.transactions.size)
        val pairs = data.transactions.mapNotNull { it.transferId }.groupBy { it }.filterValues { it.size == 2 }
        assertEquals(expected["transferPairs"]!!.jsonPrimitive.int, pairs.size, "transfer halves match case-insensitively")

        for ((id, balance) in expected["balances"]!!.jsonObject) {
            val wallet = data.wallets.single { it.id.equals(id, ignoreCase = true) }
            same(balance.jsonPrimitive.content, data.balanceOf(wallet.id), "balance of ${wallet.name}")
        }

        val rent = data.wallets.single { it.name == "Rent" }
        val rentBalance = data.balanceOf(rent.id)
        assertEquals(expected["rentProgress"]!!.jsonPrimitive.double, Envelopes.progress(rentBalance, rent.target)!!, 1e-9)
        same(expected["rentShortBy"]!!.jsonPrimitive.content, Envelopes.shortBy(rentBalance, rent.target), "rent short by")

        // Dates with and without fractional seconds, exact decimals, and the settings that travel.
        val atm = data.transactions.single { it.kind == Kind.ATM }
        assertEquals(Instant.parse("2026-11-16T09:00:00.250Z").toEpochMilli(), atm.timestamp)
        same("-20.50", data.transactions.single { it.tag == "transit" && it.amount.signum() < 0 && it.amount.scale() > 0 }.amount, "fare")
        assertEquals(Instant.parse("2026-11-25T12:00:00Z"), contents.exportedAt)
        assertEquals("ios", contents.platform)
        same("10", contents.quickSpendAmount!!, "quick spend")
        assertNull(contents.quickSpendWalletId)
        assertEquals(listOf("Main Wallet", "Rent", "Japan trip"), data.sortedWallets.map { it.name })
        assertEquals("JPY", data.wallets.single { it.name == "Japan trip" }.currencyCode)
        assertTrue(data.settings.onboarded)
    }

    @Test
    fun restoreKeepsWhatABackupNeverHolds() {
        val before = AppData(
            proPurchased = true,
            settings = Settings(haptics = false, appLock = true, debugPro = true, selectedWalletId = "x", firstOpenAt = 5),
        )
        val after = Backup.decode(example).applyTo(before)
        assertTrue(after.proPurchased)
        assertFalse(after.settings.haptics)
        assertTrue(after.settings.appLock)
        assertTrue(after.settings.debugPro)
        assertEquals(5L, after.settings.firstOpenAt)
        assertNull(after.settings.selectedWalletId, "the default wallet is selected after a restore")
        assertNull(after.widgetUndo)
    }

    @Test
    fun encodeThenDecodeGivesBackTheSameData() {
        val t0 = Instant.parse("2026-11-20T08:00:00Z").toEpochMilli()
        var (data, main) = Ledger.createWallet(AppData(), "Main Wallet", "PHP", null, d("1500.25"), t0, startingNote = "Panimulang cash")
        val (withRent, rent) = Ledger.createWallet(data, "Rent", "PHP", "₱", BigDecimal.ZERO, t0 + 1, target = d("5000"))
        data = withRent
        data = Ledger.add(data, main.id, d("50"), Kind.SPEND, t0 + 1_234, tag = "food", note = "Lunch, \"spicy\"")!!.first
        data = Ledger.add(data, main.id, d("1000"), Kind.ATM, t0 + 60_000)!!.first
        data = Ledger.fill(data, main.id, mapOf(rent.id to d("700")), t0 + 120_000)
        data = data.copy(
            settings = data.settings.copy(privacyMode = true, quickSpendAmount = d("13"), quickSpendWalletId = rent.id, plusButtonsAreATM = false),
            wallets = data.wallets.map { if (it.id == main.id) it.copy(dailyBudget = d("300"), lowCashThreshold = d("200.5")) else it },
        )

        val text = Backup.encode(data, Instant.parse("2026-11-25T12:00:00.5Z"), appVersion = "1.0")
        val root = Json.parseToJsonElement(text).jsonObject
        assertEquals("pocketcash-backup", root["format"]!!.jsonPrimitive.content)
        assertEquals("android", root["app"]!!.jsonObject["platform"]!!.jsonPrimitive.content)
        assertTrue(text.contains("\"amount\": \"-50\""), "amounts are decimal strings")
        assertTrue(text.contains("\"exportedAt\": \"2026-11-25T12:00:00.500Z\""))

        val back = Backup.decode(text).applyTo(AppData())
        assertEquals(data.sortedWallets, back.sortedWallets)
        assertEquals(data.transactions.sortedBy { it.id }, back.transactions.sortedBy { it.id })
        assertEquals(data.settings.copy(onboarded = true, selectedWalletId = null), back.settings)
        same("1500.25", back.balanceOf(main.id) + d("700") - d("1000") + d("50"), "main balance")
    }

    @Test
    fun rejectsWhatTheSpecSaysToReject() {
        fun error(text: String) = assertThrows<BackupException> { Backup.decode(text) }.error

        assertEquals(BackupError.NotABackup, error("""{"format":"something-else","version":1,"exportedAt":"2026-11-25T12:00:00Z","wallets":[]}"""))
        assertEquals(BackupError.NotABackup, error("not json at all"))
        assertEquals(BackupError.NotABackup, error("""[1,2,3]"""))
        assertEquals(BackupError.TooNew(2), error(example.replace("\"version\": 1", "\"version\": 2")))

        val duplicate = example.replace("A0000000-0000-4000-8000-000000000007", "a0000000-0000-4000-8000-000000000006")
        assertEquals(Problem.DUPLICATE_ID, (error(duplicate) as BackupError.Invalid).problem, "IDs repeat even in another case")

        val badAmount = error(example.replace("\"amount\": \"-100\"", "\"amount\": \"1,000\""))
        assertInstanceOf(BackupError.Invalid::class.java, badAmount)
        assertEquals(BackupError.Invalid(Problem.BAD_AMOUNT, "1,000"), badAmount)
        assertEquals(Problem.BAD_AMOUNT, (error(example.replace("\"amount\": \"-100\"", "\"amount\": \"1e3\"")) as BackupError.Invalid).problem)
        assertEquals(Problem.BAD_KIND, (error(example.replace("\"kind\": \"in\"", "\"kind\": \"gift\"")) as BackupError.Invalid).problem)
        assertEquals(Problem.BAD_CURRENCY, (error(example.replace("\"currencyCode\": \"JPY\"", "\"currencyCode\": \"YEN!\"")) as BackupError.Invalid).problem)
        assertEquals(Problem.BAD_DATE, (error(example.replace("2026-11-22T08:00:00Z", "22/11/2026")) as BackupError.Invalid).problem)
        assertEquals(Problem.BAD_ID, (error(example.replace("A0000000-0000-4000-8000-000000000003", "not-a-uuid")) as BackupError.Invalid).problem)
    }
}
