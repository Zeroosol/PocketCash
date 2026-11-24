package com.patternsystems.pocketcash.ui

import android.os.Build
import android.view.HapticFeedbackConstants
import android.view.View
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Backspace
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.patternsystems.pocketcash.core.DefaultPresets
import com.patternsystems.pocketcash.ui.theme.Cash
import com.patternsystems.pocketcash.ui.theme.Tabular
import java.math.BigDecimal
import java.util.Currency
import java.util.Locale

/** The confirmation tap the app's own buttons give (the widget and tile use the vibrator). */
fun View.confirmHaptic(enabled: Boolean) {
    if (!enabled) return
    performHapticFeedback(
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) HapticFeedbackConstants.CONFIRM else HapticFeedbackConstants.KEYBOARD_TAP,
    )
}

/** A big thumb-friendly button of the action pad. */
@Composable
fun RowScope.PadButton(label: String, container: Color, content: Color, onClick: () -> Unit) {
    Box(
        modifier = Modifier.weight(1f).height(60.dp).clip(RoundedCornerShape(18.dp)).background(container).clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, color = content, style = Tabular, fontSize = 19.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
    }
}

/** A rounded card on the screen background. */
@Composable
fun CashCard(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Surface(modifier = modifier.fillMaxWidth(), shape = RoundedCornerShape(20.dp), color = Cash.colors.card, content = content)
}

@Composable
fun ProBadge(modifier: Modifier = Modifier) {
    Text(
        "PRO",
        modifier = modifier.clip(RoundedCornerShape(6.dp)).background(MaterialTheme.colorScheme.primary).padding(horizontal = 6.dp, vertical = 2.dp),
        color = MaterialTheme.colorScheme.onPrimary,
        fontSize = 11.sp,
        fontWeight = FontWeight.Bold,
    )
}

/** A small lock shown next to Pro features for Free. */
@Composable
fun LockIcon() {
    Icon(Icons.Rounded.Lock, contentDescription = "Pro", modifier = Modifier.size(16.dp), tint = Cash.colors.subtle)
}

/** The numeric keypad of the amount sheets (1-9, decimal point when the currency has fractions, 0, delete). */
@Composable
fun AmountKeypad(decimals: Int, onKey: (String) -> Unit, modifier: Modifier = Modifier) {
    val rows = listOf(listOf("1", "2", "3"), listOf("4", "5", "6"), listOf("7", "8", "9"), listOf(if (decimals > 0) KeypadInput.POINT else "", "0", KeypadInput.DELETE))
    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        rows.forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                row.forEach { key ->
                    Box(
                        modifier = Modifier.weight(1f).height(54.dp).clip(RoundedCornerShape(14.dp))
                            .then(if (key.isEmpty()) Modifier else Modifier.background(Cash.colors.spendContainer).clickable { onKey(key) }),
                        contentAlignment = Alignment.Center,
                    ) {
                        when (key) {
                            "" -> Unit
                            KeypadInput.DELETE -> Icon(Icons.AutoMirrored.Rounded.Backspace, contentDescription = "Delete", tint = Cash.colors.onSpendContainer)
                            else -> Text(key, fontSize = 24.sp, fontWeight = FontWeight.Medium, color = Cash.colors.onSpendContainer)
                        }
                    }
                }
            }
        }
    }
}

/** A dialog asking for an amount, used by Settings (budget, quick spend, reminder threshold). */
@Composable
fun AmountDialog(
    title: String,
    message: String?,
    initial: BigDecimal?,
    onDismiss: () -> Unit,
    onSave: (BigDecimal?) -> Unit,
    clearLabel: String? = null,
) {
    var text by remember { mutableStateOf(initial?.stripTrailingZeros()?.toPlainString().orEmpty()) }
    val value = text.replace(',', '.').toBigDecimalOrNull()?.takeIf { it.signum() > 0 }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column {
                if (message != null) Text(message, color = Cash.colors.subtle, modifier = Modifier.padding(bottom = 12.dp))
                OutlinedTextField(
                    value = text,
                    onValueChange = { new -> text = new.filter { it.isDigit() || it == '.' || it == ',' }.take(12) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    textStyle = Tabular.copy(fontSize = 22.sp),
                )
            }
        },
        confirmButton = { TextButton(onClick = { onSave(value) }, enabled = value != null) { Text("Save") } },
        dismissButton = {
            Row {
                if (clearLabel != null && initial != null) TextButton(onClick = { onSave(null) }) { Text(clearLabel) }
                TextButton(onClick = onDismiss) { Text("Cancel") }
            }
        },
    )
}

/** Every ISO currency, with the ones PocketCash has default buttons for first. */
@Composable
fun CurrencyPickerDialog(current: String, onDismiss: () -> Unit, onPick: (String) -> Unit) {
    var query by remember { mutableStateOf("") }
    val all = remember {
        val known = DefaultPresets.table.keys
        Currency.getAvailableCurrencies().sortedWith(compareBy<Currency> { it.currencyCode !in known }.thenBy { it.currencyCode })
    }
    val shown = all.filter {
        query.isBlank() || it.currencyCode.contains(query, ignoreCase = true) || it.getDisplayName(Locale.getDefault()).contains(query, ignoreCase = true)
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Currency") },
        text = {
            Column {
                OutlinedTextField(value = query, onValueChange = { query = it }, singleLine = true, placeholder = { Text("Search") }, modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.height(8.dp))
                LazyColumn(Modifier.heightIn(max = 360.dp)) {
                    items(shown, key = { it.currencyCode }) { c ->
                        Row(
                            Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).clickable { onPick(c.currencyCode) }.padding(horizontal = 8.dp, vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(c.currencyCode, fontWeight = if (c.currencyCode == current) FontWeight.Bold else FontWeight.Medium, modifier = Modifier.width(52.dp))
                            Text(c.getDisplayName(Locale.getDefault()), color = Cash.colors.subtle, maxLines = 1)
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } },
    )
}

@Composable
fun SectionTitle(text: String, modifier: Modifier = Modifier) {
    Text(
        text.uppercase(),
        modifier = modifier.padding(start = 4.dp, top = 20.dp, bottom = 8.dp),
        style = MaterialTheme.typography.labelMedium,
        color = Cash.colors.subtle,
        letterSpacing = 0.8.sp,
    )
}

@Composable
fun EmptyNote(text: String, modifier: Modifier = Modifier) {
    Text(text, modifier = modifier.fillMaxWidth().padding(24.dp), color = Cash.colors.subtle, textAlign = TextAlign.Center)
}
