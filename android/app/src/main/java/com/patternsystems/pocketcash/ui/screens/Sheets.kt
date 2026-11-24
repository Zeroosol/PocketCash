package com.patternsystems.pocketcash.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Remove
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.patternsystems.pocketcash.core.CashTransaction
import com.patternsystems.pocketcash.core.Categories
import com.patternsystems.pocketcash.core.DefaultPresets
import com.patternsystems.pocketcash.core.Kind
import com.patternsystems.pocketcash.core.MINUS
import com.patternsystems.pocketcash.core.MoneyFormat
import com.patternsystems.pocketcash.core.Wallet
import com.patternsystems.pocketcash.ui.AmountKeypad
import com.patternsystems.pocketcash.ui.KeypadInput
import com.patternsystems.pocketcash.ui.LockIcon
import com.patternsystems.pocketcash.ui.categoryIcon
import com.patternsystems.pocketcash.ui.dayHeader
import com.patternsystems.pocketcash.ui.kindLabel
import com.patternsystems.pocketcash.ui.money
import com.patternsystems.pocketcash.ui.signed
import com.patternsystems.pocketcash.ui.theme.Cash
import com.patternsystems.pocketcash.ui.theme.HeroStyle
import com.patternsystems.pocketcash.ui.theme.Tabular
import com.patternsystems.pocketcash.ui.timeText
import com.patternsystems.pocketcash.ui.toLocalDate
import java.math.BigDecimal

/** Category chips (Pro). Free sees one locked chip that opens the paywall. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun TagPicker(isPro: Boolean, selected: String?, onSelect: (String?) -> Unit, onPaywall: () -> Unit) {
    if (!isPro) {
        FilterChip(selected = false, onClick = onPaywall, label = { Text("Add a category") }, trailingIcon = { LockIcon() })
        return
    }
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Categories.all.forEach { c ->
            FilterChip(
                selected = selected == c.key,
                onClick = { onSelect(if (selected == c.key) null else c.key) },
                label = { Text(c.label) },
                leadingIcon = { Icon(categoryIcon(c.key), contentDescription = null, modifier = Modifier.size(18.dp)) },
            )
        }
    }
}

/** The custom amount sheet (docs/spec.md section 7): opens with the keypad already active. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun KeypadSheet(
    wallet: Wallet,
    isPro: Boolean,
    startAdd: Boolean,
    fromAtmDefault: Boolean,
    onDismiss: () -> Unit,
    onPaywall: () -> Unit,
    onSave: (BigDecimal, Kind, String?, String?) -> Unit,
) {
    var add by remember { mutableStateOf(startAdd) }
    var entry by remember { mutableStateOf("") }
    var fromAtm by remember { mutableStateOf(fromAtmDefault) }
    var tag by remember { mutableStateOf<String?>(null) }
    var note by remember { mutableStateOf("") }
    val decimals = MoneyFormat.fractionDigits(wallet.currencyCode)
    val value = KeypadInput.value(entry)

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).navigationBarsPadding().imePadding(),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                SegmentedButton(selected = !add, onClick = { add = false }, shape = SegmentedButtonDefaults.itemShape(0, 2)) { Text("$MINUS Spend") }
                SegmentedButton(selected = add, onClick = { add = true }, shape = SegmentedButtonDefaults.itemShape(1, 2)) { Text("+ Add cash") }
            }
            Text(
                (if (add) "+" else "$MINUS") + wallet.money(value).let { if (entry.endsWith(".")) "$it." else it },
                style = HeroStyle.copy(fontSize = 48.sp),
                color = if (add) Cash.colors.cashIn else MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.fillMaxWidth(),
                maxLines = 1,
            )
            if (add) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("From ATM", fontWeight = FontWeight.Medium)
                        Text("Starts a new ATM cycle", fontSize = 12.sp, color = Cash.colors.subtle)
                    }
                    Switch(checked = fromAtm, onCheckedChange = { fromAtm = it })
                }
            } else {
                TagPicker(isPro, tag, { tag = it }, onPaywall)
            }
            OutlinedTextField(
                value = note,
                onValueChange = { note = it.replace("\n", "").take(80) },
                placeholder = { Text("Note (optional)") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                modifier = Modifier.fillMaxWidth(),
            )
            AmountKeypad(decimals = decimals, onKey = { entry = KeypadInput.press(entry, it, decimals) })
            Button(
                onClick = {
                    val kind = if (!add) Kind.SPEND else if (fromAtm) Kind.ATM else Kind.IN
                    onSave(value, kind, if (add) null else tag, note)
                },
                enabled = value.signum() > 0,
                modifier = Modifier.fillMaxWidth().height(56.dp),
                shape = RoundedCornerShape(18.dp),
            ) { Text("Save", fontSize = 17.sp, fontWeight = FontWeight.SemiBold) }
            Spacer(Modifier.height(8.dp))
        }
    }
}

/** "Count my cash" (docs/spec.md section 7): type the total, or count each bill and coin. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CountSheet(wallet: Wallet, balance: BigDecimal, onDismiss: () -> Unit, onSave: (BigDecimal) -> Unit) {
    var byBills by remember { mutableStateOf(false) }
    var entry by remember { mutableStateOf("") }
    val counts = remember { mutableStateMapOf<BigDecimal, Int>() }
    val denominations = remember(wallet.currencyCode) { DefaultPresets.forCurrency(wallet.currencyCode).denominations }
    val decimals = MoneyFormat.fractionDigits(wallet.currencyCode)
    val actual = if (byBills) counts.entries.fold(BigDecimal.ZERO) { s, (d, n) -> s + d.multiply(BigDecimal(n)) } else KeypadInput.value(entry)
    val touched = if (byBills) counts.values.any { it > 0 } else entry.isNotEmpty()
    val diff = actual - balance

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp).navigationBarsPadding(), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Text("How much is in your wallet?", style = MaterialTheme.typography.titleLarge)
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                SegmentedButton(selected = !byBills, onClick = { byBills = false }, shape = SegmentedButtonDefaults.itemShape(0, 2)) { Text("Type total") }
                SegmentedButton(selected = byBills, onClick = { byBills = true }, shape = SegmentedButtonDefaults.itemShape(1, 2)) { Text("Count bills") }
            }
            Text(wallet.money(actual), style = HeroStyle.copy(fontSize = 44.sp), maxLines = 1)
            if (byBills) {
                LazyColumn(Modifier.fillMaxWidth().heightIn(max = 300.dp)) {
                    items(denominations, key = { it.toPlainString() }) { d ->
                        val n = counts[d] ?: 0
                        Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(wallet.money(d), style = Tabular, fontWeight = FontWeight.Medium, modifier = Modifier.width(96.dp))
                            Stepper(n, onMinus = { counts[d] = (n - 1).coerceAtLeast(0) }, onPlus = { counts[d] = n + 1 })
                            Spacer(Modifier.weight(1f))
                            Text(if (n == 0) "" else wallet.money(d.multiply(BigDecimal(n))), style = Tabular, color = Cash.colors.subtle)
                        }
                    }
                }
            } else {
                AmountKeypad(decimals = decimals, onKey = { entry = KeypadInput.press(entry, it, decimals) })
            }
            val summary = when {
                !touched -> "PocketCash shows ${wallet.money(balance)}."
                diff.signum() == 0 -> "Spot on. Your balance is right."
                diff.signum() < 0 -> "${wallet.money(diff.negate())} less than PocketCash shows. It's saved as unrecorded spending."
                else -> "${wallet.money(diff)} more than PocketCash shows. It's saved as found cash."
            }
            Text(summary, color = Cash.colors.subtle)
            Button(
                onClick = { if (diff.signum() == 0) onDismiss() else onSave(actual) },
                enabled = touched,
                modifier = Modifier.fillMaxWidth().height(56.dp),
                shape = RoundedCornerShape(18.dp),
            ) { Text(if (touched && diff.signum() == 0) "Done" else "Update balance", fontSize = 17.sp, fontWeight = FontWeight.SemiBold) }
            Spacer(Modifier.height(8.dp))
        }
    }
}

@Composable
private fun Stepper(count: Int, onMinus: () -> Unit, onPlus: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        StepButton(Icons.Rounded.Remove, "Fewer", enabled = count > 0, onClick = onMinus)
        Text("$count", style = Tabular, fontWeight = FontWeight.SemiBold, modifier = Modifier.width(36.dp), textAlign = androidx.compose.ui.text.style.TextAlign.Center)
        StepButton(Icons.Rounded.Add, "More", enabled = true, onClick = onPlus)
    }
}

@Composable
private fun StepButton(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, enabled: Boolean, onClick: () -> Unit) {
    Box(
        Modifier.size(36.dp).clip(CircleShape).background(Cash.colors.spendContainer).clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = label, tint = if (enabled) Cash.colors.onSpendContainer else Cash.colors.subtle, modifier = Modifier.size(18.dp))
    }
}

/** Edit a feed row: amount, note and category (Pro), or delete it. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EditTransactionSheet(
    tx: CashTransaction,
    wallet: Wallet,
    isPro: Boolean,
    onDismiss: () -> Unit,
    onPaywall: () -> Unit,
    onSave: (CashTransaction) -> Unit,
    onDelete: () -> Unit,
) {
    var amountText by remember { mutableStateOf(tx.amount.abs().stripTrailingZeros().toPlainString()) }
    var note by remember { mutableStateOf(tx.note.orEmpty()) }
    var tag by remember { mutableStateOf(tx.tag) }
    val magnitude = amountText.replace(',', '.').toBigDecimalOrNull()?.takeIf { it.signum() > 0 }
    val editableAmount = tx.kind != Kind.TRANSFER

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).navigationBarsPadding().imePadding(),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Text(kindLabel(tx.copy(tag = null)), style = MaterialTheme.typography.titleLarge)
            Text("${dayHeader(tx.timestamp.toLocalDate())} · ${timeText(tx.timestamp)} · ${wallet.signed(tx.amount)}", color = Cash.colors.subtle, style = Tabular)
            if (editableAmount) {
                OutlinedTextField(
                    value = amountText,
                    onValueChange = { new -> amountText = new.filter { it.isDigit() || it == '.' || it == ',' }.take(12) },
                    label = { Text("Amount") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    textStyle = Tabular.copy(fontSize = 20.sp),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            OutlinedTextField(
                value = note,
                onValueChange = { note = it.replace("\n", "").take(80) },
                label = { Text("Note") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            if (tx.amount.signum() < 0 && tx.kind != Kind.TRANSFER) TagPicker(isPro, tag, { tag = it }, onPaywall)
            Button(
                onClick = {
                    val signed = when {
                        !editableAmount || magnitude == null -> tx.amount
                        tx.amount.signum() < 0 -> magnitude.negate()
                        else -> magnitude
                    }
                    onSave(tx.copy(amount = signed, note = note.trim().ifEmpty { null }, tag = tag))
                },
                enabled = !editableAmount || magnitude != null,
                modifier = Modifier.fillMaxWidth().height(56.dp),
                shape = RoundedCornerShape(18.dp),
            ) { Text("Save", fontSize = 17.sp, fontWeight = FontWeight.SemiBold) }
            TextButton(onClick = onDelete, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Rounded.Delete, contentDescription = null, tint = Cash.colors.over)
                Spacer(Modifier.width(6.dp))
                Text(if (tx.transferId != null) "Delete both sides of this move" else "Delete", color = Cash.colors.over)
            }
            Spacer(Modifier.height(8.dp))
        }
    }
}
