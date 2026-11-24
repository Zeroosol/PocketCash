package com.patternsystems.pocketcash.core

import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import java.math.BigDecimal
import java.util.UUID

/** Exact decimals saved as strings ("12.50"), so no amount ever passes through a Double (docs/spec.md section 3). */
object BigDecimalSerializer : KSerializer<BigDecimal> {
    override val descriptor = PrimitiveSerialDescriptor("PocketCash.Decimal", PrimitiveKind.STRING)
    override fun serialize(encoder: Encoder, value: BigDecimal) = encoder.encodeString(value.toPlainString())
    override fun deserialize(decoder: Decoder): BigDecimal = BigDecimal(decoder.decodeString())
}

/** A money amount: an exact decimal, saved as a string. */
typealias Money = @Serializable(with = BigDecimalSerializer::class) BigDecimal

/** What kind of cash movement a transaction is (docs/spec.md section 2). */
@Serializable
enum class Kind {
    /** Money left the wallet. Always negative. */
    @SerialName("spend") SPEND,
    /** Other cash received. Always positive. */
    @SerialName("in") IN,
    /** An ATM withdrawal. Always positive. Starts a new ATM cycle. */
    @SerialName("atm") ATM,
    /** A reconcile ("Count my cash") or a wallet's starting cash. Either sign. */
    @SerialName("adjust") ADJUST,
    /** Half of a move between two wallets. Never spending or income. */
    @SerialName("transfer") TRANSFER,
}

@Serializable
data class Wallet(
    val id: String = newId(),
    val name: String,
    /** ISO 4217. */
    val currencyCode: String,
    /** Custom symbol for travel wallets; null = the currency's own symbol. */
    val symbol: String? = null,
    /** Positive magnitudes of the cash-in buttons. */
    val addPresets: List<Money>,
    /** Positive magnitudes of the spend buttons, in widget order. */
    val spendPresets: List<Money>,
    val dailyBudget: Money? = null,
    val lowCashThreshold: Money? = null,
    val sortOrder: Int = 0,
    /** Epoch milliseconds. */
    val createdAt: Long = System.currentTimeMillis(),
) {
    companion object {
        /** A wallet with the currency's default buttons. */
        fun create(name: String, currencyCode: String, sortOrder: Int = 0, symbol: String? = null): Wallet {
            val presets = DefaultPresets.forCurrency(currencyCode)
            return Wallet(
                name = name,
                currencyCode = currencyCode.uppercase(),
                symbol = symbol?.takeIf { it.isNotBlank() },
                addPresets = presets.add,
                spendPresets = presets.spend,
                sortOrder = sortOrder,
            )
        }
    }
}

@Serializable
data class CashTransaction(
    val id: String = newId(),
    val walletId: String,
    /** Signed: positive = cash in, negative = cash out. */
    val amount: Money,
    /** Epoch milliseconds. */
    val timestamp: Long,
    val kind: Kind,
    /** Category key ([Categories]); null = untagged. */
    val tag: String? = null,
    val note: String? = null,
    /** Links the two halves of a transfer. */
    val transferId: String? = null,
)

/** Global settings (docs/spec.md section 2). */
@Serializable
data class Settings(
    /** Null = the default wallet's smallest spend preset (the "daily commute fare"). */
    val quickSpendAmount: Money? = null,
    /** Null = the default wallet. */
    val quickSpendWalletId: String? = null,
    val plusButtonsAreATM: Boolean = true,
    val privacyMode: Boolean = false,
    val lowCashAlerts: Boolean = false,
    val haptics: Boolean = true,
    val onboarded: Boolean = false,
    /** The wallet the app shows; null = the default wallet. */
    val selectedWalletId: String? = null,
    /** Epoch milliseconds of the first launch, for the one soft paywall nudge on day 3. */
    val firstOpenAt: Long? = null,
    val paywallNudgeShown: Boolean = false,
    /** "Unlock Pro (debug build)". Ignored in release builds. */
    val debugPro: Boolean = false,
)

fun newId(): String = UUID.randomUUID().toString()
