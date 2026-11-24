package com.patternsystems.pocketcash.core

import java.math.BigDecimal
import java.math.RoundingMode
import java.text.DecimalFormat
import java.text.NumberFormat
import java.util.Currency
import java.util.Locale

/** U+2212, the minus sign used in every amount PocketCash shows. */
const val MINUS = '−'

/** Display rules for amounts (docs/spec.md section 3). */
object MoneyFormat {
    private val THOUSAND = BigDecimal(1_000)
    private val MILLION = BigDecimal(1_000_000)

    /**
     * Button labels: `+1k`, `−100`, `+1.5k`, `−0.25`, `+1M`. At most one decimal for k and M, dropped when zero.
     * [amount] may be signed; [sign] decides the prefix (`+`, `−`, or none when null).
     */
    fun compact(amount: BigDecimal, sign: Char? = null): String {
        val v = amount.abs()
        val body = when {
            v >= MILLION -> short(v.divide(MILLION)) + "M"
            v >= THOUSAND -> short(v.divide(THOUSAND)) + "k"
            else -> v.stripTrailingZeros().toPlainString()
        }
        return if (sign == null) body else "$sign$body"
    }

    /** Signed compact label: `+1k` for cash in, `−50` for cash out. */
    fun compactSigned(amount: BigDecimal): String = compact(amount, if (amount.signum() < 0) MINUS else '+')

    private fun short(v: BigDecimal): String = v.setScale(1, RoundingMode.HALF_UP).stripTrailingZeros().toPlainString()

    /**
     * Full amount with the currency's symbol (or [symbol]) and grouping, showing fraction digits only when the amount
     * isn't whole: `₱680`, `$12.50`, `¥1,200`. Negative amounts start with U+2212.
     */
    fun full(amount: BigDecimal, currencyCode: String, symbol: String? = null, locale: Locale = Locale.getDefault()): String {
        val format = NumberFormat.getCurrencyInstance(locale)
        val currency = runCatching { Currency.getInstance(currencyCode.uppercase()) }.getOrNull()
        if (currency != null) format.currency = currency
        if (format is DecimalFormat) {
            val symbols = format.decimalFormatSymbols
            when {
                symbol != null -> symbols.currencySymbol = symbol
                currency == null -> symbols.currencySymbol = currencyCode.uppercase()
            }
            format.decimalFormatSymbols = symbols
        }
        val abs = amount.abs()
        val whole = abs.stripTrailingZeros().scale() <= 0
        val digits = if (whole) 0 else maxOf(currency?.defaultFractionDigits?.coerceAtLeast(0) ?: 2, abs.stripTrailingZeros().scale())
        format.minimumFractionDigits = digits
        format.maximumFractionDigits = digits
        val text = format.format(abs)
        return if (amount.signum() < 0) "$MINUS$text" else text
    }

    /** `+₱200` / `−₱50`: a transaction amount with an explicit sign. */
    fun signed(amount: BigDecimal, currencyCode: String, symbol: String? = null, locale: Locale = Locale.getDefault()): String {
        val text = full(amount.abs(), currencyCode, symbol, locale)
        return when (amount.signum()) {
            -1 -> "$MINUS$text"
            1 -> "+$text"
            else -> text
        }
    }

    /** Decimal places the currency uses (2 when unknown). */
    fun fractionDigits(currencyCode: String): Int =
        runCatching { Currency.getInstance(currencyCode.uppercase()).defaultFractionDigits }.getOrNull()?.coerceAtLeast(0) ?: 2

    /** The phone's currency, falling back to USD. */
    fun localeCurrency(locale: Locale = Locale.getDefault()): String =
        runCatching { Currency.getInstance(locale).currencyCode }.getOrNull() ?: "USD"
}
