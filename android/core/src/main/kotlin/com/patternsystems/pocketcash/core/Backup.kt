package com.patternsystems.pocketcash.core

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull
import java.math.BigDecimal
import java.time.Instant
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException
import java.util.UUID

/**
 * The backup file (docs/spec.md section 11): one JSON file either app can read, so a backup made on an iPhone
 * restores on Android and the other way. Example: spec/backup/example-v1.json.
 *
 * Amounts are decimal strings, dates RFC 3339 in UTC (fractional seconds optional), IDs UUID strings compared
 * case-insensitively. Readers ignore unknown fields and reject a wrong `format`, a newer `version`, an invalid kind or
 * currency code, an amount that doesn't parse, or a repeated ID.
 */
object Backup {
    const val FORMAT = "pocketcash-backup"
    const val VERSION = 1
    const val MIME_TYPE = "application/json"

    private val reader = Json { ignoreUnknownKeys = true; isLenient = true }
    private val writer = Json { prettyPrint = true; prettyPrintIndent = "  "; encodeDefaults = true; explicitNulls = true }

    /** `PocketCash-backup-2026-11-25.json`. */
    fun fileName(date: java.time.LocalDate): String = "PocketCash-backup-$date.json"

    /** Everything worth keeping, pretty-printed. Settings outside the spec's list are never written. */
    fun encode(data: AppData, exportedAt: Instant, platform: String = "android", appVersion: String = "1.0"): String {
        val quickAmount = data.settings.quickSpendAmount?.takeIf { it.signum() > 0 } ?: data.quickSpendAmount(isPro = true)
        val file = FileDto(
            format = FORMAT,
            version = VERSION,
            exportedAt = date(exportedAt.toEpochMilli()),
            app = AppDto(platform, appVersion),
            settings = SettingsDto(
                plusButtonsAreATM = data.settings.plusButtonsAreATM,
                privacyMode = data.settings.privacyMode,
                lowCashAlerts = data.settings.lowCashAlerts,
                quickSpendAmount = quickAmount?.let(::decimal),
                quickSpendWalletId = data.settings.quickSpendWalletId?.takeIf { data.wallet(it) != null },
            ),
            wallets = data.sortedWallets.map { w ->
                WalletDto(
                    id = w.id,
                    name = w.name,
                    currencyCode = w.currencyCode,
                    symbol = w.symbol,
                    addPresets = w.addPresets.map(::decimal),
                    spendPresets = w.spendPresets.map(::decimal),
                    dailyBudget = w.dailyBudget?.let(::decimal),
                    lowCashThreshold = w.lowCashThreshold?.let(::decimal),
                    target = w.target?.let(::decimal),
                    sortOrder = w.sortOrder,
                    createdAt = date(w.createdAt),
                    transactions = data.transactionsOf(w.id).sortedBy { it.timestamp }.map { t ->
                        TransactionDto(
                            id = t.id,
                            amount = decimal(t.amount),
                            kind = KINDS.entries.first { it.value == t.kind }.key,
                            timestamp = date(t.timestamp),
                            tag = t.tag,
                            note = t.note,
                            transferId = t.transferId,
                        )
                    },
                )
            },
        )
        return writer.encodeToString(FileDto.serializer(), file) + "\n"
    }

    /** Reads and validates a backup. Throws [BackupException] saying why when the file can't be restored. */
    fun decode(text: String): BackupContents {
        val root = try {
            reader.parseToJsonElement(text.removePrefix("﻿")) as? JsonObject
        } catch (e: SerializationException) {
            null
        } ?: throw BackupException(BackupError.NotABackup)
        val format = (root["format"] as? JsonPrimitive)?.takeIf { it.isString }?.content
        if (format != FORMAT) throw BackupException(BackupError.NotABackup)
        val version = (root["version"] as? JsonPrimitive)?.intOrNull ?: throw BackupException(BackupError.NotABackup)
        if (version > VERSION) throw BackupException(BackupError.TooNew(version))
        if (version < 1) throw BackupException(BackupError.NotABackup)

        val file = try {
            reader.decodeFromJsonElement(FileDto.serializer(), root)
        } catch (e: SerializationException) {
            throw BackupException(BackupError.Invalid(Problem.MALFORMED, e.message?.lineSequence()?.firstOrNull()))
        } catch (e: IllegalArgumentException) {
            throw BackupException(BackupError.Invalid(Problem.MALFORMED, e.message?.lineSequence()?.firstOrNull()))
        }
        return validate(file)
    }

    private fun validate(file: FileDto): BackupContents {
        val exportedAt = parseDate(file.exportedAt)
        if (file.wallets.isEmpty()) invalid(Problem.NO_WALLETS, null)
        val walletIds = HashSet<String>()
        val transactionIds = HashSet<String>()
        val wallets = mutableListOf<Wallet>()
        val transactions = mutableListOf<CashTransaction>()

        // Keep the file's order of wallets with the same sortOrder, then number them 0, 1, 2…
        val ordered = file.wallets.withIndex().sortedWith(compareBy({ it.value.sortOrder }, { it.index })).map { it.value }
        ordered.forEachIndexed { order, w ->
            val id = parseId(w.id)
            if (!walletIds.add(id)) invalid(Problem.DUPLICATE_ID, w.id)
            val code = w.currencyCode.trim()
            if (!CURRENCY.matches(code)) invalid(Problem.BAD_CURRENCY, w.currencyCode)
            val defaults = DefaultPresets.forCurrency(code)
            fun presets(list: List<String>?, fallback: List<BigDecimal>) =
                list?.map { parseAmount(it).abs() }?.filter { it.signum() > 0 }?.takeIf { it.isNotEmpty() } ?: fallback
            wallets += Wallet(
                id = id,
                name = w.name.trim().ifEmpty { "Wallet" },
                currencyCode = code.uppercase(),
                symbol = w.symbol?.trim()?.takeIf { it.isNotEmpty() },
                addPresets = presets(w.addPresets, defaults.add).take(2),
                spendPresets = presets(w.spendPresets, defaults.spend).take(4),
                dailyBudget = w.dailyBudget?.let(::parseAmount)?.takeIf { it.signum() > 0 },
                lowCashThreshold = w.lowCashThreshold?.let(::parseAmount)?.takeIf { it.signum() > 0 },
                target = w.target?.let(::parseAmount)?.takeIf { it.signum() > 0 },
                sortOrder = order,
                createdAt = w.createdAt?.let { parseDate(it).toEpochMilli() } ?: exportedAt.toEpochMilli(),
            )
            for (t in w.transactions) {
                val txId = parseId(t.id)
                if (!transactionIds.add(txId)) invalid(Problem.DUPLICATE_ID, t.id)
                val kind = KINDS[t.kind] ?: invalid(Problem.BAD_KIND, t.kind)
                transactions += CashTransaction(
                    id = txId,
                    walletId = id,
                    amount = parseAmount(t.amount),
                    timestamp = parseDate(t.timestamp).toEpochMilli(),
                    kind = kind,
                    tag = t.tag?.trim()?.takeIf { it.isNotEmpty() },
                    note = t.note?.takeIf { it.isNotBlank() },
                    transferId = t.transferId?.let(::parseId),
                )
            }
        }

        val s = file.settings
        val quickWallet = s.quickSpendWalletId?.let(::parseId)?.takeIf { it in walletIds }
        return BackupContents(
            exportedAt = exportedAt,
            platform = file.app?.platform,
            appVersion = file.app?.version,
            wallets = wallets,
            transactions = transactions,
            plusButtonsAreATM = s.plusButtonsAreATM,
            privacyMode = s.privacyMode,
            lowCashAlerts = s.lowCashAlerts,
            quickSpendAmount = s.quickSpendAmount?.let(::parseAmount)?.takeIf { it.signum() > 0 },
            quickSpendWalletId = quickWallet,
        )
    }

    // MARK: Values

    /** `-12.5`: plain digits, `.` as the decimal point, no grouping, no exponent. */
    private val DECIMAL = Regex("""^[+-]?(\d+(\.\d*)?|\.\d+)$""")
    private val CURRENCY = Regex("^[A-Za-z]{3}$")

    private val KINDS = linkedMapOf(
        "spend" to Kind.SPEND,
        "in" to Kind.IN,
        "atm" to Kind.ATM,
        "adjust" to Kind.ADJUST,
        "transfer" to Kind.TRANSFER,
    )

    private fun decimal(value: BigDecimal): String = value.stripTrailingZeros().toPlainString().let { if (it == "-0") "0" else it }

    /** RFC 3339 in UTC; fractional seconds only when the time has milliseconds. */
    private fun date(millis: Long): String = Instant.ofEpochMilli(millis).toString()

    private fun parseAmount(text: String): BigDecimal {
        val t = text.trim()
        if (!DECIMAL.matches(t)) invalid(Problem.BAD_AMOUNT, text)
        return try {
            BigDecimal(t.removePrefix("+"))
        } catch (e: NumberFormatException) {
            invalid(Problem.BAD_AMOUNT, text)
        }
    }

    /** RFC 3339 with or without fractional seconds, `Z` or an offset (`t`/`z` accepted too). */
    private fun parseDate(text: String): Instant = try {
        OffsetDateTime.parse(text.trim().uppercase(), DateTimeFormatter.ISO_OFFSET_DATE_TIME).toInstant()
    } catch (e: DateTimeParseException) {
        invalid(Problem.BAD_DATE, text)
    }

    /** Lower-case canonical UUID, so IDs from either app compare equal whatever their case. */
    private fun parseId(text: String): String = try {
        val t = text.trim()
        require(t.length == 36)
        UUID.fromString(t).toString()
    } catch (e: IllegalArgumentException) {
        invalid(Problem.BAD_ID, text)
    }

    private fun invalid(problem: Problem, value: String?): Nothing = throw BackupException(BackupError.Invalid(problem, value))

    // MARK: File layout

    @Serializable
    private data class FileDto(
        val format: String,
        val version: Int,
        val exportedAt: String,
        val app: AppDto? = null,
        val settings: SettingsDto = SettingsDto(),
        val wallets: List<WalletDto> = emptyList(),
    )

    @Serializable
    private data class AppDto(val platform: String, val version: String)

    @Serializable
    private data class SettingsDto(
        val plusButtonsAreATM: Boolean = true,
        val privacyMode: Boolean = false,
        val lowCashAlerts: Boolean = false,
        val quickSpendAmount: String? = null,
        @SerialName("quickSpendWalletID") val quickSpendWalletId: String? = null,
    )

    @Serializable
    private data class WalletDto(
        val id: String,
        val name: String,
        val currencyCode: String,
        val symbol: String? = null,
        val addPresets: List<String>? = null,
        val spendPresets: List<String>? = null,
        val dailyBudget: String? = null,
        val lowCashThreshold: String? = null,
        val target: String? = null,
        val sortOrder: Int = 0,
        val createdAt: String? = null,
        val transactions: List<TransactionDto> = emptyList(),
    )

    @Serializable
    private data class TransactionDto(
        val id: String,
        val amount: String,
        val kind: String,
        val timestamp: String,
        val tag: String? = null,
        val note: String? = null,
        @SerialName("transferID") val transferId: String? = null,
    )
}

/** What a valid backup holds, ready to replace everything on the phone. */
data class BackupContents(
    val exportedAt: Instant,
    /** `ios` or `android`; null when the file doesn't say. */
    val platform: String?,
    val appVersion: String?,
    val wallets: List<Wallet>,
    val transactions: List<CashTransaction>,
    val plusButtonsAreATM: Boolean,
    val privacyMode: Boolean,
    val lowCashAlerts: Boolean,
    val quickSpendAmount: BigDecimal?,
    val quickSpendWalletId: String?,
) {
    /**
     * The phone's data after "Replace all data on this phone": the backup's wallets, transactions and settings. What a
     * backup never holds (Pro unlock, app lock, haptics, onboarding, the debug switch) stays as it was, and the app
     * shows the default wallet.
     */
    fun applyTo(current: AppData): AppData = current.copy(
        wallets = wallets,
        transactions = transactions,
        settings = current.settings.copy(
            plusButtonsAreATM = plusButtonsAreATM,
            privacyMode = privacyMode,
            lowCashAlerts = lowCashAlerts,
            quickSpendAmount = quickSpendAmount,
            quickSpendWalletId = quickSpendWalletId,
            selectedWalletId = null,
            onboarded = true,
        ),
        widgetUndo = null,
        lowCashNotified = emptySet(),
    )
}

/** Why a file can't be restored. Nothing on the phone changes. */
sealed interface BackupError {
    /** "This file isn't a PocketCash backup." */
    data object NotABackup : BackupError

    /** "This backup is from a newer version of PocketCash." */
    data class TooNew(val version: Int) : BackupError

    /** The file says it's a backup but something in it is wrong; [value] is the offending text when there is one. */
    data class Invalid(val problem: Problem, val value: String?) : BackupError
}

enum class Problem { MALFORMED, NO_WALLETS, BAD_AMOUNT, BAD_DATE, BAD_ID, BAD_KIND, BAD_CURRENCY, DUPLICATE_ID }

class BackupException(val error: BackupError) : Exception(error.toString())
