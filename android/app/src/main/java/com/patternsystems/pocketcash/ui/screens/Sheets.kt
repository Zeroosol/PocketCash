package com.patternsystems.pocketcash.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Remove
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.patternsystems.pocketcash.R
import com.patternsystems.pocketcash.core.AppData
import com.patternsystems.pocketcash.core.CashTransaction
import com.patternsystems.pocketcash.core.ChangeMath
import com.patternsystems.pocketcash.core.DefaultPresets
import com.patternsystems.pocketcash.core.Kind
import com.patternsystems.pocketcash.core.MINUS
import com.patternsystems.pocketcash.core.MoneyFormat
import com.patternsystems.pocketcash.core.Wallet
import com.patternsystems.pocketcash.ui.AmountKeypad
import com.patternsystems.pocketcash.ui.CATEGORY_KEYS
import com.patternsystems.pocketcash.ui.KeypadInput
import com.patternsystems.pocketcash.ui.LockIcon
import com.patternsystems.pocketcash.ui.PrimaryButton
import com.patternsystems.pocketcash.ui.categoryIcon
import com.patternsystems.pocketcash.ui.categoryLabelRes
import com.patternsystems.pocketcash.ui.dayHeader
import com.patternsystems.pocketcash.ui.kindLabel
import com.patternsystems.pocketcash.ui.money
import com.patternsystems.pocketcash.ui.signed
import com.patternsystems.pocketcash.ui.theme.Cash
import com.patternsystems.pocketcash.ui.theme.HeroStyle
import com.patternsystems.pocketcash.ui.theme.Tabular
import com.patternsystems.pocketcash.ui.timeText
import com.patternsystems.pocketcash.ui.toLocalDate
import com.patternsystems.pocketcash.ui.transferPartner
import java.math.BigDecimal

/** Category chips (Pro). Free sees one locked chip that opens the paywall. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun TagPicker(isPro: Boolean, selected: String?, onSelect: (String?) -> Unit, onPaywall: () -> Unit) {
    if (!isPro) {
        FilterChip(selected = false, onClick = onPaywall, label = { Text(stringResource(R.string.tag_add)) }, trailingIcon = { LockIcon() })
        return
    }
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        CATEGORY_KEYS.forEach { key ->
            FilterChip(
                selected = selected == key,
                onClick = { onSelect(if (selected == key) null else key) },
                label = { Text(stringResource(categoryLabelRes(key))) },
                leadingIcon = { Icon(categoryIcon(key), contentDescription = null, modifier = Modifier.size(18.dp)) },
            )
        }
    }
}

private enum class Field { PAID, CHANGE }

/**
 * The custom amount sheet (docs/spec.md section 7.2): opens with the keypad already active. In Spend mode, "Got
 * change?" (Pro) splits the entry into Paid and Change and saves one spend of paid − change.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun KeypadSheet(
    wallet: Wallet,
    isPro: Boolean,
    spendPresets: List<BigDecimal>,
    startAdd: Boolean,
    fromAtmDefault: Boolean,
    onDismiss: () -> Unit,
    onPaywall: () -> Unit,
    onSave: (BigDecimal, Kind, String?, String?) -> Unit,
) {
    var add by remember { mutableStateOf(startAdd) }
    var entry by remember { mutableStateOf("") }
    var changeMode by remember { mutableStateOf(false) }
    var changeEntry by remember { mutableStateOf("") }
    var field by remember { mutableStateOf(Field.PAID) }
    var fromAtm by remember { mutableStateOf(fromAtmDefault) }
    var tag by remember { mutableStateOf<String?>(null) }
    var note by remember { mutableStateOf("") }
    val decimals = MoneyFormat.fractionDigits(wallet.currencyCode)
    val paid = KeypadInput.value(entry)
    val change = KeypadInput.value(changeEntry)
    val withChange = !add && changeMode && change.signum() > 0
    val tooMuchChange = withChange && ChangeMath.changeTooBig(paid, change)
    val value = if (withChange) ChangeMath.spend(paid, change) ?: BigDecimal.ZERO else paid
    val chips = remember(wallet.currencyCode, spendPresets) { ChangeMath.paidChips(wallet.currencyCode, spendPresets) }
    fun shown(text: String, amount: BigDecimal) = wallet.money(amount).let { if (text.endsWith(".")) "$it." else it }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).navigationBarsPadding().imePadding(),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                SegmentedButton(selected = !add, onClick = { add = false }, shape = SegmentedButtonDefaults.itemShape(0, 2)) { Text(stringResource(R.string.keypad_spend)) }
                SegmentedButton(selected = add, onClick = { add = true }, shape = SegmentedButtonDefaults.itemShape(1, 2)) { Text(stringResource(R.string.keypad_add)) }
            }
            Text(
                (if (add) "+" else "$MINUS") + if (withChange) wallet.money(value) else shown(entry, paid),
                style = HeroStyle.copy(fontSize = 46.sp),
                color = if (add) Cash.colors.cashIn else MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.fillMaxWidth(),
                maxLines = 1,
            )
            if (!add) {
                if (changeMode) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        AmountField(stringResource(R.string.change_paid), shown(entry, paid), field == Field.PAID, Modifier.weight(1f)) { field = Field.PAID }
                        AmountField(stringResource(R.string.change_change), shown(changeEntry, change), field == Field.CHANGE, Modifier.weight(1f)) { field = Field.CHANGE }
                    }
                    if (chips.isNotEmpty()) {
                        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            chips.forEach { bill ->
                                FilterChip(
                                    selected = paid.compareTo(bill) == 0,
                                    onClick = { entry = KeypadInput.of(bill); field = Field.CHANGE },
                                    label = { Text(stringResource(R.string.change_paid_chip, wallet.money(bill)), style = Tabular) },
                                )
                            }
                        }
                    }
                    Text(
                        when {
                            tooMuchChange -> stringResource(R.string.change_too_big)
                            withChange -> stringResource(R.string.change_equation, wallet.money(paid), wallet.money(change), wallet.money(value))
                            else -> stringResource(R.string.change_hint)
                        },
                        color = if (tooMuchChange) Cash.colors.over else Cash.colors.subtle,
                        fontSize = 14.sp,
                        style = Tabular,
                    )
                } else {
                    TextButton(
                        onClick = {
                            if (!isPro) onPaywall() else {
                                changeMode = true
                                field = Field.CHANGE
                            }
                        },
                    ) {
                        Text(stringResource(R.string.change_button))
                        if (!isPro) {
                            Spacer(Modifier.width(6.dp))
                            LockIcon()
                        }
                    }
                }
            }
            if (add) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(stringResource(R.string.keypad_from_atm))
                        Text(stringResource(R.string.keypad_from_atm_hint), fontSize = 12.sp, color = Cash.colors.subtle)
                    }
                    Switch(checked = fromAtm, onCheckedChange = { fromAtm = it })
                }
            } else {
                TagPicker(isPro, tag, { tag = it }, onPaywall)
            }
            OutlinedTextField(
                value = note,
                onValueChange = { note = it.replace("\n", "").take(80) },
                placeholder = { Text(stringResource(R.string.keypad_note)) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                modifier = Modifier.fillMaxWidth(),
            )
            AmountKeypad(
                decimals = decimals,
                onKey = { key ->
                    if (!add && changeMode && field == Field.CHANGE) changeEntry = KeypadInput.press(changeEntry, key, decimals)
                    else entry = KeypadInput.press(entry, key, decimals)
                },
            )
            PrimaryButton(
                stringResource(R.string.action_save),
                onClick = {
                    val kind = if (!add) Kind.SPEND else if (fromAtm) Kind.ATM else Kind.IN
                    onSave(value, kind, if (add) null else tag, note)
                },
                enabled = value.signum() > 0 && !tooMuchChange,
            )
            Spacer(Modifier.height(8.dp))
        }
    }
}

/** One of the change calculator's two fields; the keypad types into the selected one. */
@Composable
private fun AmountField(label: String, amount: String, selected: Boolean, modifier: Modifier, onClick: () -> Unit) {
    Column(
        modifier.clip(RoundedCornerShape(14.dp))
            .border(if (selected) 1.5.dp else 0.5.dp, if (selected) MaterialTheme.colorScheme.onBackground else Cash.colors.hairline, RoundedCornerShape(14.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 10.dp),
    ) {
        Text(label, fontSize = 12.sp, color = Cash.colors.subtle)
        Text(amount, style = Tabular, fontSize = 20.sp, maxLines = 1)
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
            Text(stringResource(R.string.count_title), style = MaterialTheme.typography.titleLarge)
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                SegmentedButton(selected = !byBills, onClick = { byBills = false }, shape = SegmentedButtonDefaults.itemShape(0, 2)) { Text(stringResource(R.string.count_type_total)) }
                SegmentedButton(selected = byBills, onClick = { byBills = true }, shape = SegmentedButtonDefaults.itemShape(1, 2)) { Text(stringResource(R.string.count_bills)) }
            }
            Text(wallet.money(actual), style = HeroStyle.copy(fontSize = 44.sp), maxLines = 1)
            if (byBills) {
                LazyColumn(Modifier.fillMaxWidth().heightIn(max = 300.dp)) {
                    items(denominations, key = { it.toPlainString() }) { d ->
                        val n = counts[d] ?: 0
                        Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(wallet.money(d), style = Tabular, modifier = Modifier.width(96.dp))
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
                !touched -> stringResource(R.string.count_shows, wallet.money(balance))
                diff.signum() == 0 -> stringResource(R.string.count_spot_on)
                diff.signum() < 0 -> stringResource(R.string.count_less, wallet.money(diff.negate()))
                else -> stringResource(R.string.count_more, wallet.money(diff))
            }
            Text(summary, color = Cash.colors.subtle)
            PrimaryButton(
                stringResource(if (touched && diff.signum() == 0) R.string.action_done else R.string.count_update),
                onClick = { if (diff.signum() == 0) onDismiss() else onSave(actual) },
                enabled = touched,
            )
            Spacer(Modifier.height(8.dp))
        }
    }
}

@Composable
private fun Stepper(count: Int, onMinus: () -> Unit, onPlus: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        StepButton(Icons.Rounded.Remove, stringResource(R.string.count_fewer), enabled = count > 0, onClick = onMinus)
        Text("$count", style = Tabular, modifier = Modifier.width(36.dp), textAlign = TextAlign.Center)
        StepButton(Icons.Rounded.Add, stringResource(R.string.count_more_one), enabled = true, onClick = onPlus)
    }
}

@Composable
private fun StepButton(icon: ImageVector, label: String, enabled: Boolean, onClick: () -> Unit) {
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
    data: AppData,
    tx: CashTransaction,
    wallet: Wallet,
    isPro: Boolean,
    onDismiss: () -> Unit,
    onPaywall: () -> Unit,
    onSave: (CashTransaction) -> Unit,
    onDelete: () -> Unit,
) {
    val context = LocalContext.current
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
            Text(kindLabel(context, tx.copy(tag = null), data.transferPartner(tx)), style = MaterialTheme.typography.titleLarge)
            Text("${dayHeader(context, tx.timestamp.toLocalDate())} · ${timeText(tx.timestamp)} · ${wallet.signed(tx.amount)}", color = Cash.colors.subtle, style = Tabular)
            if (editableAmount) {
                OutlinedTextField(
                    value = amountText,
                    onValueChange = { new -> amountText = new.filter { it.isDigit() || it == '.' || it == ',' }.take(12) },
                    label = { Text(stringResource(R.string.edit_amount)) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    textStyle = Tabular.copy(fontSize = 20.sp),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            OutlinedTextField(
                value = note,
                onValueChange = { note = it.replace("\n", "").take(80) },
                label = { Text(stringResource(R.string.edit_note)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            if (tx.amount.signum() < 0 && tx.kind != Kind.TRANSFER) TagPicker(isPro, tag, { tag = it }, onPaywall)
            PrimaryButton(
                stringResource(R.string.action_save),
                onClick = {
                    val signed = when {
                        !editableAmount || magnitude == null -> tx.amount
                        tx.amount.signum() < 0 -> magnitude.negate()
                        else -> magnitude
                    }
                    onSave(tx.copy(amount = signed, note = note.trim().ifEmpty { null }, tag = tag))
                },
                enabled = !editableAmount || magnitude != null,
            )
            TextButton(onClick = onDelete, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Outlined.Delete, contentDescription = null, tint = Cash.colors.over)
                Spacer(Modifier.width(6.dp))
                Text(stringResource(if (tx.transferId != null) R.string.edit_delete_both else R.string.action_delete), color = Cash.colors.over)
            }
            Spacer(Modifier.height(8.dp))
        }
    }
}
