package com.patternsystems.pocketcash.ui

import android.content.Context
import android.text.format.DateFormat
import androidx.annotation.StringRes
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ReceiptLong
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.DirectionsBus
import androidx.compose.material.icons.outlined.LocalAtm
import androidx.compose.material.icons.outlined.LocalParking
import androidx.compose.material.icons.outlined.MoreHoriz
import androidx.compose.material.icons.outlined.Payments
import androidx.compose.material.icons.outlined.Restaurant
import androidx.compose.material.icons.outlined.Savings
import androidx.compose.material.icons.outlined.ShoppingBag
import androidx.compose.material.icons.outlined.ShoppingCart
import androidx.compose.material.icons.outlined.SwapHoriz
import androidx.compose.material.icons.outlined.ThumbUp
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.ui.graphics.vector.ImageVector
import com.patternsystems.pocketcash.R
import com.patternsystems.pocketcash.core.AppData
import com.patternsystems.pocketcash.core.CashTransaction
import com.patternsystems.pocketcash.core.Cycle
import com.patternsystems.pocketcash.core.Kind
import com.patternsystems.pocketcash.core.MoneyFormat
import com.patternsystems.pocketcash.core.Wallet
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale

fun Wallet.money(amount: BigDecimal): String = MoneyFormat.full(amount, currencyCode, symbol)
fun Wallet.signed(amount: BigDecimal): String = MoneyFormat.signed(amount, currencyCode, symbol)

val zone: ZoneId get() = ZoneId.systemDefault()

fun Long.toLocalDate(): LocalDate = Instant.ofEpochMilli(this).atZone(zone).toLocalDate()

// Dates follow the phone's region: the order and words of "Mon, Nov 23" come from the locale, not a fixed pattern.
private fun skeleton(skeleton: String): DateTimeFormatter {
    val locale = Locale.getDefault()
    return DateTimeFormatter.ofPattern(DateFormat.getBestDateTimePattern(locale, skeleton), locale)
}

private val timeFormat get() = DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT).withLocale(Locale.getDefault())

fun timeText(millis: Long): String = timeFormat.format(Instant.ofEpochMilli(millis).atZone(zone))

/** "Today", "Yesterday", "Mon, Nov 23". */
fun dayHeader(context: Context, day: LocalDate, today: LocalDate = LocalDate.now(zone)): String = when (day) {
    today -> context.getString(R.string.day_today)
    today.minusDays(1) -> context.getString(R.string.day_yesterday)
    else -> skeleton("EEEMMMd").format(day)
}

fun shortDate(millis: Long): String = skeleton("MMMd").format(Instant.ofEpochMilli(millis).atZone(zone))

/** "Thu, Dec 24". */
fun longDate(instant: Instant): String = skeleton("EEEMMMd").format(instant.atZone(zone))

/** "Nov 25, 2026". */
fun fullDate(instant: Instant): String =
    DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).withLocale(Locale.getDefault()).format(instant.atZone(zone))

fun oneDecimal(value: Double): String = String.format(Locale.getDefault(), "%.1f", value)

/** Feed header for a cycle (docs/spec.md section 4). */
fun cycleHeader(context: Context, cycle: Cycle, wallet: Wallet): String {
    val days = oneDecimal(cycle.days)
    val start = shortDate(cycle.start.toEpochMilli())
    return when {
        !cycle.isATM -> context.getString(R.string.cycle_before_first_atm)
        cycle.isOpen -> context.getString(R.string.cycle_header_open, start, wallet.money(cycle.withdrawn), days)
        else -> context.getString(R.string.cycle_header_closed, start, shortDate(cycle.end!!.toEpochMilli()), wallet.money(cycle.withdrawn), days)
    }
}

/** The other wallet of a transfer, for "To Rent" / "From Main Wallet". */
fun AppData.transferPartner(tx: CashTransaction): Wallet? {
    val link = tx.transferId ?: return null
    val other = transactions.firstOrNull { it.transferId == link && it.id != tx.id } ?: return null
    return wallet(other.walletId)
}

/** What a feed row says when there's no note. */
fun kindLabel(context: Context, tx: CashTransaction, partner: Wallet? = null): String = when (tx.kind) {
    Kind.SPEND -> tx.tag?.let { categoryLabel(context, it) } ?: context.getString(R.string.kind_spent)
    Kind.IN -> context.getString(R.string.kind_in)
    Kind.ATM -> context.getString(R.string.kind_atm)
    Kind.ADJUST -> context.getString(if (tx.amount.signum() < 0) R.string.kind_unrecorded else R.string.kind_found)
    Kind.TRANSFER -> when {
        partner != null && tx.amount.signum() < 0 -> context.getString(R.string.kind_transfer_to, partner.name)
        partner != null -> context.getString(R.string.kind_transfer_from, partner.name)
        tx.amount.signum() < 0 -> context.getString(R.string.kind_moved_out)
        else -> context.getString(R.string.kind_moved_in)
    }
}

/** "−₱50 spent", "+₱1,000 from the ATM", "Balance updated · −₱20" (the Undo banner). */
fun addedMessage(context: Context, tx: CashTransaction, signedAmount: String): String = when (tx.kind) {
    Kind.SPEND -> context.getString(R.string.added_spent, signedAmount)
    Kind.ATM -> context.getString(R.string.added_atm, signedAmount)
    Kind.ADJUST -> context.getString(R.string.added_adjust, signedAmount)
    else -> context.getString(R.string.added_in, signedAmount)
}

/** The category keys of docs/spec.md section 6, in order. */
val CATEGORY_KEYS = listOf("food", "transit", "parking", "groceries", "errand", "tip", "bills", "fun", "other")

@StringRes
fun categoryLabelRes(key: String?): Int = when (key) {
    "food" -> R.string.category_food
    "transit" -> R.string.category_transit
    "parking" -> R.string.category_parking
    "groceries" -> R.string.category_groceries
    "errand" -> R.string.category_errand
    "tip" -> R.string.category_tip
    "bills" -> R.string.category_bills
    "fun" -> R.string.category_fun
    "other" -> R.string.category_other
    else -> R.string.category_untagged
}

fun categoryLabel(context: Context, key: String?): String = context.getString(categoryLabelRes(key))

fun categoryIcon(key: String?): ImageVector = when (key) {
    "food" -> Icons.Outlined.Restaurant
    "transit" -> Icons.Outlined.DirectionsBus
    "parking" -> Icons.Outlined.LocalParking
    "groceries" -> Icons.Outlined.ShoppingCart
    "errand" -> Icons.Outlined.ShoppingBag
    "tip" -> Icons.Outlined.ThumbUp
    "bills" -> Icons.AutoMirrored.Outlined.ReceiptLong
    "fun" -> Icons.Outlined.AutoAwesome
    else -> Icons.Outlined.MoreHoriz
}

fun transactionIcon(tx: CashTransaction): ImageVector = when {
    tx.tag != null -> categoryIcon(tx.tag)
    tx.kind == Kind.ATM -> Icons.Outlined.LocalAtm
    tx.kind == Kind.IN -> Icons.Outlined.Savings
    tx.kind == Kind.ADJUST -> Icons.Outlined.Tune
    tx.kind == Kind.TRANSFER -> Icons.Outlined.SwapHoriz
    else -> Icons.Outlined.Payments
}

/** Keypad input: digits, one decimal point when the currency has fractions, and delete. */
object KeypadInput {
    const val DELETE = "del"
    const val POINT = "."

    fun press(entry: String, key: String, decimals: Int): String {
        if (key == DELETE) return entry.dropLast(1)
        if (key == POINT) {
            if (decimals == 0 || entry.contains('.')) return entry
            return (entry.ifEmpty { "0" }) + "."
        }
        val point = entry.indexOf('.')
        if (point >= 0 && entry.length - point - 1 >= decimals) return entry
        if (point < 0 && entry.length >= 9) return entry
        if (entry == "0") return key
        return entry + key
    }

    fun value(entry: String): BigDecimal = entry.trimEnd('.').toBigDecimalOrNull() ?: BigDecimal.ZERO

    /** The entry for an amount, e.g. a "Paid ₱1,000" chip. */
    fun of(amount: BigDecimal): String = amount.stripTrailingZeros().toPlainString()
}
