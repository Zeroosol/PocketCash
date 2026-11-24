package com.patternsystems.pocketcash.ui

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ReceiptLong
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.DirectionsBus
import androidx.compose.material.icons.rounded.LocalAtm
import androidx.compose.material.icons.rounded.LocalParking
import androidx.compose.material.icons.rounded.MoreHoriz
import androidx.compose.material.icons.rounded.Payments
import androidx.compose.material.icons.rounded.Restaurant
import androidx.compose.material.icons.rounded.Savings
import androidx.compose.material.icons.rounded.ShoppingBag
import androidx.compose.material.icons.rounded.ShoppingCart
import androidx.compose.material.icons.rounded.SwapHoriz
import androidx.compose.material.icons.rounded.ThumbUp
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.ui.graphics.vector.ImageVector
import com.patternsystems.pocketcash.core.CashTransaction
import com.patternsystems.pocketcash.core.Categories
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

private val timeFormat get() = DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT).withLocale(Locale.getDefault())
private val dayFormat get() = DateTimeFormatter.ofPattern("EEE, MMM d", Locale.getDefault())
private val shortDay get() = DateTimeFormatter.ofPattern("MMM d", Locale.getDefault())

fun timeText(millis: Long): String = timeFormat.format(Instant.ofEpochMilli(millis).atZone(zone))

/** "Today", "Yesterday", "Mon, Nov 23". */
fun dayHeader(day: LocalDate, today: LocalDate = LocalDate.now(zone)): String = when (day) {
    today -> "Today"
    today.minusDays(1) -> "Yesterday"
    else -> dayFormat.format(day)
}

fun shortDate(millis: Long): String = shortDay.format(Instant.ofEpochMilli(millis).atZone(zone))
fun longDate(instant: Instant): String = dayFormat.format(instant.atZone(zone))

fun oneDecimal(value: Double): String = String.format(Locale.getDefault(), "%.1f", value)

/** Feed header for a cycle (docs/spec.md section 4). */
fun cycleHeader(cycle: Cycle, wallet: Wallet): String {
    val days = oneDecimal(cycle.days)
    return when {
        !cycle.isATM -> "Before first ATM"
        cycle.isOpen -> "Current cycle · since ${shortDate(cycle.start.toEpochMilli())} · ${wallet.money(cycle.withdrawn)} · $days days so far"
        else -> "ATM Cycle · ${shortDate(cycle.start.toEpochMilli())} – ${shortDate(cycle.end!!.toEpochMilli())} · " +
            "${wallet.money(cycle.withdrawn)} lasted $days days"
    }
}

/** What a feed row says when there's no note. */
fun kindLabel(tx: CashTransaction): String = when (tx.kind) {
    Kind.SPEND -> tx.tag?.let { Categories.label(it) } ?: "Spent"
    Kind.IN -> "Cash in"
    Kind.ATM -> "ATM withdrawal"
    Kind.ADJUST -> if (tx.amount.signum() < 0) "Unrecorded spending" else "Found cash"
    Kind.TRANSFER -> if (tx.amount.signum() < 0) "Moved out" else "Moved in"
}

fun categoryIcon(key: String?): ImageVector = when (key) {
    "food" -> Icons.Rounded.Restaurant
    "transit" -> Icons.Rounded.DirectionsBus
    "parking" -> Icons.Rounded.LocalParking
    "groceries" -> Icons.Rounded.ShoppingCart
    "errand" -> Icons.Rounded.ShoppingBag
    "tip" -> Icons.Rounded.ThumbUp
    "bills" -> Icons.AutoMirrored.Rounded.ReceiptLong
    "fun" -> Icons.Rounded.AutoAwesome
    else -> Icons.Rounded.MoreHoriz
}

fun transactionIcon(tx: CashTransaction): ImageVector = when {
    tx.tag != null -> categoryIcon(tx.tag)
    tx.kind == Kind.ATM -> Icons.Rounded.LocalAtm
    tx.kind == Kind.IN -> Icons.Rounded.Savings
    tx.kind == Kind.ADJUST -> Icons.Rounded.Tune
    tx.kind == Kind.TRANSFER -> Icons.Rounded.SwapHoriz
    else -> Icons.Rounded.Payments
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
}
