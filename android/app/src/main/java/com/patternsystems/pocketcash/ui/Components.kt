package com.patternsystems.pocketcash.ui

import android.os.Build
import android.view.HapticFeedbackConstants
import android.view.View
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Backspace
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.patternsystems.pocketcash.R
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

/** The light tick of the nav bar. */
fun View.tickHaptic(enabled: Boolean) {
    if (enabled) performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
}

/** Generous side margins, the same on every screen. */
val ScreenPadding = 20.dp

/** Room at the bottom of every list so the floating nav bar never covers the last row. */
val BarClearance = 120.dp

/** A big thumb-friendly button of the action pad. */
@Composable
fun RowScope.PadButton(label: String, container: Color, content: Color, onClick: () -> Unit) {
    Box(
        modifier = Modifier.weight(1f).height(58.dp).clip(RoundedCornerShape(16.dp)).background(container).clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, color = content, style = Tabular, fontSize = 18.sp, fontWeight = FontWeight.Medium, maxLines = 1)
    }
}

/** The one primary button of a screen or sheet: full width, emerald. */
@Composable
fun PrimaryButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true) {
    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.fillMaxWidth().height(54.dp),
        shape = RoundedCornerShape(16.dp),
        elevation = ButtonDefaults.buttonElevation(0.dp, 0.dp, 0.dp, 0.dp, 0.dp),
        contentPadding = PaddingValues(horizontal = 20.dp),
    ) { Text(text, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis) }
}

/** A hairline between rows of a group. */
@Composable
fun Hairline(modifier: Modifier = Modifier) {
    HorizontalDivider(modifier, thickness = 0.5.dp, color = Cash.colors.hairline)
}

/** "PRO": a quiet monochrome tag. */
@Composable
fun ProBadge(modifier: Modifier = Modifier) {
    Text(
        stringResource(R.string.pro_badge),
        modifier = modifier.border(0.75.dp, Cash.colors.subtle, RoundedCornerShape(5.dp)).padding(horizontal = 5.dp, vertical = 1.dp),
        color = Cash.colors.subtle,
        fontSize = 10.sp,
        fontWeight = FontWeight.SemiBold,
        letterSpacing = 0.6.sp,
    )
}

/** A small lock shown next to Pro features for Free. */
@Composable
fun LockIcon(modifier: Modifier = Modifier) {
    Icon(Icons.Outlined.Lock, contentDescription = stringResource(R.string.pro_feature), modifier = modifier.size(16.dp), tint = Cash.colors.subtle)
}

/** A thin emerald progress bar on a grey track (envelopes, categories). */
@Composable
fun ProgressLine(fraction: Float, modifier: Modifier = Modifier, height: Float = 4f) {
    Box(modifier.fillMaxWidth().height(height.dp).clip(CircleShape).background(Cash.colors.track)) {
        if (fraction > 0f) {
            Box(Modifier.fillMaxWidth(fraction.coerceIn(0.015f, 1f)).fillMaxHeight().clip(CircleShape).background(Cash.colors.cashIn))
        }
    }
}

/** A settings-style row: title, optional subtitle, value on the right, a lock for Free. */
@Composable
fun ListRow(
    title: String,
    modifier: Modifier = Modifier,
    value: String? = null,
    subtitle: String? = null,
    locked: Boolean = false,
    titleColor: Color = MaterialTheme.colorScheme.onBackground,
    onClick: (() -> Unit)? = null,
) {
    Row(
        modifier.fillMaxWidth().then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = ScreenPadding, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, color = titleColor, fontSize = 16.sp)
            if (subtitle != null) Text(subtitle, fontSize = 13.sp, color = Cash.colors.subtle, modifier = Modifier.padding(top = 2.dp))
        }
        if (value != null) {
            Spacer(Modifier.width(12.dp))
            Text(value, style = Tabular, color = Cash.colors.subtle, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 180.dp))
        }
        if (locked) {
            Spacer(Modifier.width(8.dp))
            LockIcon()
        }
    }
}

/** A row with a switch. A locked row shows the lock and opens the paywall instead of switching. */
@Composable
fun SwitchRow(
    title: String,
    subtitle: String?,
    checked: Boolean,
    locked: Boolean = false,
    enabled: Boolean = true,
    onChange: (Boolean) -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().clickable(enabled = enabled) { onChange(!checked) }.padding(horizontal = ScreenPadding, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, fontSize = 16.sp, color = if (enabled) MaterialTheme.colorScheme.onBackground else Cash.colors.subtle)
            if (subtitle != null) Text(subtitle, fontSize = 13.sp, color = Cash.colors.subtle, modifier = Modifier.padding(top = 2.dp))
        }
        Spacer(Modifier.width(12.dp))
        if (locked) {
            LockIcon(Modifier.padding(end = 8.dp))
        }
        Switch(
            checked = checked && !locked,
            onCheckedChange = if (enabled) onChange else null,
            enabled = enabled,
            colors = SwitchDefaults.colors(
                checkedTrackColor = MaterialTheme.colorScheme.onBackground,
                checkedThumbColor = MaterialTheme.colorScheme.background,
                uncheckedBorderColor = Cash.colors.hairline,
                uncheckedTrackColor = Cash.colors.spendContainer,
                uncheckedThumbColor = Cash.colors.subtle,
            ),
        )
    }
}

/** The numeric keypad of the amount sheets (1-9, decimal point when the currency has fractions, 0, delete). */
@Composable
fun AmountKeypad(decimals: Int, onKey: (String) -> Unit, modifier: Modifier = Modifier) {
    val rows = listOf(listOf("1", "2", "3"), listOf("4", "5", "6"), listOf("7", "8", "9"), listOf(if (decimals > 0) KeypadInput.POINT else "", "0", KeypadInput.DELETE))
    Column(modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        rows.forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                row.forEach { key ->
                    Box(
                        modifier = Modifier.weight(1f).height(52.dp).clip(RoundedCornerShape(14.dp))
                            .then(if (key.isEmpty()) Modifier else Modifier.background(Cash.colors.spendContainer).clickable { onKey(key) }),
                        contentAlignment = Alignment.Center,
                    ) {
                        when (key) {
                            "" -> Unit
                            KeypadInput.DELETE -> Icon(Icons.AutoMirrored.Outlined.Backspace, contentDescription = stringResource(R.string.keypad_delete), tint = Cash.colors.onSpendContainer)
                            else -> Text(key, fontSize = 23.sp, fontWeight = FontWeight.Normal, color = Cash.colors.onSpendContainer)
                        }
                    }
                }
            }
        }
    }
}

/** A dialog asking for an amount, used by Settings (budget, quick spend, reminder threshold, target). */
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
        confirmButton = { TextButton(onClick = { onSave(value) }, enabled = value != null) { Text(stringResource(R.string.action_save)) } },
        dismissButton = {
            Row {
                if (clearLabel != null && initial != null) TextButton(onClick = { onSave(null) }) { Text(clearLabel) }
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
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
        title = { Text(stringResource(R.string.currency_title)) },
        text = {
            Column {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    singleLine = true,
                    placeholder = { Text(stringResource(R.string.currency_search)) },
                    modifier = Modifier.fillMaxWidth(),
                )
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
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_close)) } },
    )
}

/** A small grey heading above a group. */
@Composable
fun SectionTitle(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        modifier = modifier.padding(start = ScreenPadding, end = ScreenPadding, top = 28.dp, bottom = 6.dp),
        style = MaterialTheme.typography.labelLarge,
        color = Cash.colors.subtle,
    )
}

@Composable
fun EmptyNote(text: String, modifier: Modifier = Modifier) {
    Text(text, modifier = modifier.fillMaxWidth().padding(horizontal = ScreenPadding, vertical = 20.dp), color = Cash.colors.subtle, textAlign = TextAlign.Center)
}
