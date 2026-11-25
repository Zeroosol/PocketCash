package com.patternsystems.pocketcash.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.patternsystems.pocketcash.R
import com.patternsystems.pocketcash.core.AppData
import com.patternsystems.pocketcash.core.Envelopes
import com.patternsystems.pocketcash.core.Wallet
import com.patternsystems.pocketcash.data.isPro
import com.patternsystems.pocketcash.ui.Hairline
import com.patternsystems.pocketcash.ui.PrimaryButton
import com.patternsystems.pocketcash.ui.ProgressLine
import com.patternsystems.pocketcash.ui.ScreenPadding
import com.patternsystems.pocketcash.ui.money
import com.patternsystems.pocketcash.ui.theme.Cash
import com.patternsystems.pocketcash.ui.theme.Tabular
import kotlinx.coroutines.launch
import java.math.BigDecimal

/**
 * Envelopes (Pro, docs/spec.md section 10): every wallet in order with its balance, and a progress bar toward its
 * target when it has one. Tapping a wallet shows it on Home; "Fill envelopes" splits cash from one wallet.
 */
@Composable
fun EnvelopesScreen(app: AppScope, data: AppData) {
    val wallets = data.sortedWallets
    val selected = data.selectedWallet(data.isPro)
    DestinationPage(
        title = stringResource(R.string.nav_envelopes),
        actionIcon = Icons.Outlined.Add,
        actionLabel = stringResource(R.string.envelopes_new),
        onAction = { if (data.isPro) app.open(Sheet.WalletEditor(null)) else app.paywall() },
    ) {
        item {
            Text(
                stringResource(R.string.envelopes_intro),
                color = Cash.colors.subtle,
                fontSize = 14.sp,
                modifier = Modifier.padding(start = ScreenPadding, end = ScreenPadding, bottom = 12.dp),
            )
        }
        items(wallets, key = { it.id }) { w ->
            EnvelopeRow(w, data.balanceOf(w.id), w.id == selected?.id) {
                app.scope.launch { app.store.updateSettings { it.copy(selectedWalletId = w.id) } }
                app.navigate(Destination.HOME)
            }
        }
        item {
            Column(Modifier.padding(horizontal = ScreenPadding, vertical = 24.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                PrimaryButton(
                    stringResource(R.string.fill_title),
                    onClick = { if (data.isPro) app.open(Sheet.Fill) else app.paywall() },
                    enabled = wallets.size > 1,
                )
                if (wallets.none { it.target != null }) {
                    Text(stringResource(R.string.envelopes_no_targets), color = Cash.colors.subtle, fontSize = 13.sp)
                }
            }
        }
    }
}

@Composable
private fun EnvelopeRow(wallet: Wallet, balance: BigDecimal, selected: Boolean, onClick: () -> Unit) {
    val target = wallet.target?.takeIf { it.signum() > 0 }
    Column(Modifier.fillMaxWidth().clickable(onClick = onClick)) {
        Column(Modifier.padding(horizontal = ScreenPadding, vertical = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                // The wallet Home shows is a little bolder.
                Text(wallet.name, fontSize = 16.sp, modifier = Modifier.weight(1f), fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal)
                Text(wallet.money(balance), style = Tabular, fontSize = 16.sp)
            }
            if (target != null) {
                ProgressLine(Envelopes.progress(balance, target)?.toFloat() ?: 0f)
                Text(
                    if (Envelopes.isReached(balance, target)) stringResource(R.string.envelopes_goal_reached)
                    else stringResource(R.string.envelopes_of_target, wallet.money(balance.max(BigDecimal.ZERO)), wallet.money(target)),
                    style = Tabular,
                    fontSize = 13.sp,
                    color = if (Envelopes.isReached(balance, target)) Cash.colors.cashIn else Cash.colors.subtle,
                )
            }
        }
        Hairline(Modifier.padding(start = ScreenPadding))
    }
}

/**
 * "Fill envelopes" (docs/spec.md section 10): pick a source, and each other wallet with the same currency starts with
 * the suggested fill. Save moves everything in one write.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FillSheet(app: AppScope, data: AppData, onDismiss: () -> Unit) {
    val wallets = data.sortedWallets
    var sourceId by remember { mutableStateOf(data.defaultWallet?.id ?: wallets.first().id) }
    val texts = remember(sourceId) {
        mutableStateMapOf<String, String>().apply {
            Envelopes.suggestedFill(data, sourceId).forEach { (id, amount) -> put(id, if (amount.signum() == 0) "" else amount.stripTrailingZeros().toPlainString()) }
        }
    }
    val source = data.wallet(sourceId) ?: return
    val targets = Envelopes.fillTargets(data, sourceId)
    val amounts = targets.associate { it.id to (texts[it.id]?.replace(',', '.')?.toBigDecimalOrNull()?.takeIf { v -> v.signum() > 0 } ?: BigDecimal.ZERO) }
    val total = amounts.values.fold(BigDecimal.ZERO, BigDecimal::add)
    val sourceBalance = data.balanceOf(source.id)
    val canSave = total.signum() > 0 && total <= sourceBalance

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).navigationBarsPadding().imePadding(),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Text(stringResource(R.string.fill_title), style = MaterialTheme.typography.titleLarge)
            Column {
                Text(stringResource(R.string.fill_from), fontSize = 13.sp, color = Cash.colors.subtle)
                Row(Modifier.padding(top = 6.dp).horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    wallets.forEach { w -> FilterChip(selected = w.id == sourceId, onClick = { sourceId = w.id }, label = { Text(w.name) }) }
                }
                Text(stringResource(R.string.fill_source_has, source.money(sourceBalance)), fontSize = 13.sp, color = Cash.colors.subtle, style = Tabular, modifier = Modifier.padding(top = 6.dp))
            }
            if (targets.isEmpty()) {
                Text(stringResource(R.string.fill_none, source.currencyCode), color = Cash.colors.subtle)
            }
            targets.forEach { w ->
                val balance = data.balanceOf(w.id)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(w.name)
                        val short = Envelopes.shortBy(balance, w.target)
                        Text(
                            when {
                                w.target == null -> stringResource(R.string.fill_no_target)
                                short.signum() == 0 -> stringResource(R.string.envelopes_goal_reached)
                                else -> stringResource(R.string.fill_short_by, w.money(short))
                            },
                            fontSize = 12.sp,
                            color = Cash.colors.subtle,
                            style = Tabular,
                        )
                    }
                    Spacer(Modifier.width(12.dp))
                    OutlinedTextField(
                        value = texts[w.id].orEmpty(),
                        onValueChange = { new -> texts[w.id] = new.filter { it.isDigit() || it == '.' || it == ',' }.take(12) },
                        singleLine = true,
                        placeholder = { Text("0", style = Tabular) },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        textStyle = Tabular,
                        modifier = Modifier.width(130.dp),
                    )
                }
            }
            val stays = sourceBalance - total
            Text(
                if (total > sourceBalance) stringResource(R.string.fill_too_much, source.money(sourceBalance), source.name)
                else stringResource(R.string.fill_footer, source.money(total), source.money(stays), source.name),
                color = if (total > sourceBalance) Cash.colors.over else Cash.colors.subtle,
                style = Tabular,
                fontSize = 14.sp,
            )
            PrimaryButton(
                stringResource(R.string.fill_save),
                onClick = {
                    app.scope.launch {
                        app.store.fill(source.id, amounts)
                        app.toast(app.str(R.string.fill_done, source.money(total)))
                    }
                    app.haptic()
                    onDismiss()
                },
                enabled = canSave,
            )
            Spacer(Modifier.height(8.dp))
        }
    }
}
