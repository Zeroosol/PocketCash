package com.patternsystems.pocketcash.ui.screens

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings as AndroidSettings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.fragment.app.FragmentActivity
import com.patternsystems.pocketcash.BuildConfig
import com.patternsystems.pocketcash.R
import com.patternsystems.pocketcash.core.AppData
import com.patternsystems.pocketcash.core.DefaultPresets
import com.patternsystems.pocketcash.core.MINUS
import com.patternsystems.pocketcash.core.MoneyFormat
import com.patternsystems.pocketcash.core.Settings
import com.patternsystems.pocketcash.core.Wallet
import com.patternsystems.pocketcash.data.AppLock
import com.patternsystems.pocketcash.data.Export
import com.patternsystems.pocketcash.data.LowCashNotifier
import com.patternsystems.pocketcash.data.ProBilling
import com.patternsystems.pocketcash.data.appLockOn
import com.patternsystems.pocketcash.data.isPro
import com.patternsystems.pocketcash.data.privacyOn
import com.patternsystems.pocketcash.ui.AmountDialog
import com.patternsystems.pocketcash.ui.CurrencyPickerDialog
import com.patternsystems.pocketcash.ui.Hairline
import com.patternsystems.pocketcash.ui.ListRow
import com.patternsystems.pocketcash.ui.LockIcon
import com.patternsystems.pocketcash.ui.PrimaryButton
import com.patternsystems.pocketcash.ui.ScreenPadding
import com.patternsystems.pocketcash.ui.SectionTitle
import com.patternsystems.pocketcash.ui.SwitchRow
import com.patternsystems.pocketcash.ui.money
import com.patternsystems.pocketcash.ui.theme.Cash
import com.patternsystems.pocketcash.ui.theme.Tabular
import kotlinx.coroutines.launch
import java.math.BigDecimal
import java.util.Locale

private enum class AmountField { QUICK, BUDGET, THRESHOLD }

/** Settings (docs/spec.md section 7): a destination of the nav bar, grouped by hairlines instead of cards. */
@Composable
fun SettingsScreen(app: AppScope, data: AppData) {
    val context = LocalContext.current
    val pro = data.isPro
    val wallet = data.selectedWallet(pro) ?: return
    val settings = data.settings
    var editing by remember { mutableStateOf<AmountField?>(null) }
    var pickQuickWallet by remember { mutableStateOf(false) }
    val billing = remember { ProBilling.get(context) }
    val export = rememberBackupExport(app)
    val restore = rememberRestore(app)
    val lockMethod = AppLock.method(context)

    val notificationPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        app.scope.launch { app.store.updateSettings { it.copy(lowCashAlerts = granted) } }
    }
    fun update(change: (Settings) -> Settings) {
        app.scope.launch { app.store.updateSettings(change) }
    }
    /** Pro features stay visible with a lock; using one opens the paywall (docs/spec.md section 5). */
    fun proOnly(action: () -> Unit) = if (pro) action() else app.paywall()

    DestinationPage(title = stringResource(R.string.nav_settings)) {
        item {
            Column(Modifier.padding(horizontal = ScreenPadding, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(stringResource(R.string.pro_name), style = MaterialTheme.typography.titleMedium)
                if (pro) {
                    Text(stringResource(R.string.settings_pro_thanks), color = Cash.colors.subtle)
                } else {
                    Text(stringResource(R.string.settings_pro_pitch), color = Cash.colors.subtle)
                    Spacer(Modifier.height(6.dp))
                    PrimaryButton(stringResource(R.string.pro_unlock), onClick = app.paywall)
                    TextButton(onClick = { billing.restore() }, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.paywall_restore)) }
                }
            }
        }

        item { SectionTitle(stringResource(R.string.settings_wallets)) }
        data.sortedWallets.forEach { w ->
            item(key = "wallet-${w.id}") {
                ListRow(
                    title = w.name,
                    subtitle = w.currencyCode + (w.symbol?.let { " · $it" } ?: ""),
                    value = w.money(data.balanceOf(w.id)),
                ) { app.open(Sheet.WalletEditor(w.id)) }
                Hairline(Modifier.padding(start = ScreenPadding))
            }
        }
        item {
            ListRow(stringResource(R.string.wallet_add), locked = !pro, titleColor = MaterialTheme.colorScheme.onBackground) {
                proOnly { app.open(Sheet.WalletEditor(null)) }
            }
            if (data.wallets.size > 1) {
                Hairline(Modifier.padding(start = ScreenPadding))
                ListRow(stringResource(R.string.transfer_title_long), locked = !pro) { proOnly { app.open(Sheet.Transfer) } }
            }
        }

        item { SectionTitle(wallet.name) }
        item {
            val (add, spend) = data.presetsOf(wallet, pro)
            ListRow(
                stringResource(R.string.settings_buttons),
                value = (add.map { MoneyFormat.compact(it, '+') } + spend.map { MoneyFormat.compact(it, MINUS) }).joinToString("  "),
                locked = !pro,
            ) { proOnly { app.open(Sheet.Presets) } }
            Hairline(Modifier.padding(start = ScreenPadding))
            ListRow(
                stringResource(R.string.settings_budget),
                value = wallet.dailyBudget?.takeIf { pro }?.let { wallet.money(it) } ?: stringResource(R.string.value_off),
                locked = !pro,
            ) { proOnly { editing = AmountField.BUDGET } }
            Hairline(Modifier.padding(start = ScreenPadding))
            ListRow(
                stringResource(R.string.settings_threshold),
                value = wallet.lowCashThreshold?.takeIf { pro }?.let { wallet.money(it) } ?: stringResource(R.string.value_not_set),
                locked = !pro,
            ) { proOnly { editing = AmountField.THRESHOLD } }
        }

        item { SectionTitle(stringResource(R.string.tile_label)) }
        item {
            val quickWallet = data.quickSpendWallet(pro)
            val amount = data.quickSpendAmount(pro)
            ListRow(
                stringResource(R.string.settings_quick_amount),
                value = if (quickWallet != null && amount != null) MINUS + quickWallet.money(amount) else stringResource(R.string.value_not_set),
                subtitle = stringResource(R.string.settings_quick_hint),
                locked = !pro,
            ) { proOnly { editing = AmountField.QUICK } }
            if (pro && data.wallets.size > 1) {
                Hairline(Modifier.padding(start = ScreenPadding))
                ListRow(stringResource(R.string.settings_quick_wallet), value = quickWallet?.name ?: "") { pickQuickWallet = true }
            }
        }

        item { SectionTitle(stringResource(R.string.settings_options)) }
        item {
            SwitchRow(stringResource(R.string.settings_low_cash), stringResource(R.string.settings_low_cash_hint), settings.lowCashAlerts && pro, locked = !pro) { on ->
                when {
                    !pro -> app.paywall()
                    on && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && !LowCashNotifier.canNotify(context) -> {
                        AppLock.expectReturn()
                        notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
                    }
                    else -> update { it.copy(lowCashAlerts = on) }
                }
            }
            Hairline(Modifier.padding(start = ScreenPadding))
            SwitchRow(stringResource(R.string.settings_plus_atm), stringResource(R.string.settings_plus_atm_hint), settings.plusButtonsAreATM) { on ->
                update { it.copy(plusButtonsAreATM = on) }
            }
            Hairline(Modifier.padding(start = ScreenPadding))
            SwitchRow(stringResource(R.string.settings_privacy), stringResource(R.string.settings_privacy_hint), data.privacyOn, locked = !pro) { on ->
                proOnly { update { it.copy(privacyMode = on) } }
            }
            Hairline(Modifier.padding(start = ScreenPadding))
            SwitchRow(stringResource(R.string.settings_haptics), stringResource(R.string.settings_haptics_hint), settings.haptics) { on ->
                update { it.copy(haptics = on) }
            }
        }

        item { SectionTitle(stringResource(R.string.lock_section)) }
        item {
            val title = stringResource(
                when (lockMethod) {
                    AppLock.Method.FINGERPRINT -> R.string.lock_with_fingerprint
                    AppLock.Method.FACE -> R.string.lock_with_face
                    else -> R.string.lock_with_passcode
                },
            )
            val unavailable = lockMethod == AppLock.Method.NONE && !data.appLockOn
            SwitchRow(
                title = title,
                subtitle = stringResource(if (unavailable) R.string.lock_unavailable else R.string.lock_hint),
                checked = data.appLockOn,
                locked = !pro,
                enabled = !unavailable || !pro,
            ) { on ->
                when {
                    !pro -> app.paywall()
                    !on -> {
                        update { it.copy(appLock = false) }
                        AppLock.disabled()
                    }
                    else -> {
                        val activity = context.findActivity() as? FragmentActivity ?: return@SwitchRow
                        AppLock.confirmEnable(activity) { ok -> if (ok) update { it.copy(appLock = true) } }
                    }
                }
            }
            if (data.appLockOn && !data.privacyOn) {
                Text(
                    stringResource(R.string.lock_widgets_note),
                    fontSize = 13.sp,
                    color = Cash.colors.subtle,
                    modifier = Modifier.padding(horizontal = ScreenPadding).padding(bottom = 8.dp),
                )
            }
        }

        item { SectionTitle(stringResource(R.string.settings_data)) }
        item {
            ListRow(stringResource(R.string.backup_export), subtitle = stringResource(R.string.backup_export_hint), onClick = export)
            Hairline(Modifier.padding(start = ScreenPadding))
            ListRow(stringResource(R.string.backup_restore), subtitle = stringResource(R.string.backup_restore_hint), onClick = restore)
            Hairline(Modifier.padding(start = ScreenPadding))
            ListRow(stringResource(R.string.csv_export), subtitle = stringResource(R.string.csv_export_hint), locked = !pro) {
                proOnly {
                    AppLock.expectReturn()
                    Export.shareCsv(context, data)
                }
            }
        }

        item { SectionTitle(stringResource(R.string.settings_language)) }
        item {
            val language = Locale.getDefault().let { it.getDisplayLanguage(it).replaceFirstChar { c -> c.titlecase(it) } }
            ListRow(
                stringResource(R.string.settings_language),
                value = language,
                subtitle = stringResource(R.string.settings_language_hint),
            ) {
                val intent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    Intent(AndroidSettings.ACTION_APP_LOCALE_SETTINGS, Uri.fromParts("package", context.packageName, null))
                } else {
                    Intent(AndroidSettings.ACTION_LOCALE_SETTINGS)
                }
                AppLock.expectReturn()
                try {
                    context.startActivity(intent)
                } catch (e: ActivityNotFoundException) {
                    runCatching { context.startActivity(Intent(AndroidSettings.ACTION_LOCALE_SETTINGS)) }
                }
            }
        }

        if (BuildConfig.DEBUG) {
            item { SectionTitle(stringResource(R.string.settings_debug)) }
            item {
                SwitchRow(stringResource(R.string.settings_debug_pro), stringResource(R.string.settings_debug_pro_hint), settings.debugPro) { on ->
                    update { it.copy(debugPro = on) }
                }
            }
        }

        item {
            Text(
                stringResource(R.string.settings_about, BuildConfig.VERSION_NAME),
                color = Cash.colors.subtle,
                fontSize = 13.sp,
                modifier = Modifier.fillMaxWidth().padding(horizontal = ScreenPadding, vertical = 32.dp),
            )
        }
    }

    when (editing) {
        AmountField.QUICK -> AmountDialog(
            title = stringResource(R.string.settings_quick_title),
            message = stringResource(R.string.settings_quick_message),
            initial = data.quickSpendAmount(pro),
            onDismiss = { editing = null },
            onSave = { v -> editing = null; update { it.copy(quickSpendAmount = v) } },
            clearLabel = stringResource(R.string.action_default),
        )
        AmountField.BUDGET -> AmountDialog(
            title = stringResource(R.string.settings_budget),
            message = stringResource(R.string.settings_budget_message, wallet.name),
            initial = wallet.dailyBudget,
            onDismiss = { editing = null },
            onSave = { v -> editing = null; app.scope.launch { app.store.updateWallet(wallet.copy(dailyBudget = v)) } },
            clearLabel = stringResource(R.string.action_turn_off),
        )
        AmountField.THRESHOLD -> AmountDialog(
            title = stringResource(R.string.settings_threshold_title),
            message = stringResource(R.string.settings_threshold_message, wallet.name),
            initial = wallet.lowCashThreshold,
            onDismiss = { editing = null },
            onSave = { v -> editing = null; app.scope.launch { app.store.updateWallet(wallet.copy(lowCashThreshold = v)) } },
            clearLabel = stringResource(R.string.action_remove),
        )
        null -> Unit
    }

    if (pickQuickWallet) {
        AlertDialog(
            onDismissRequest = { pickQuickWallet = false },
            title = { Text(stringResource(R.string.settings_quick_wallet)) },
            text = {
                Column {
                    data.sortedWallets.forEach { w ->
                        Text(
                            w.name,
                            modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).clickable {
                                pickQuickWallet = false
                                update { it.copy(quickSpendWalletId = w.id, quickSpendAmount = null) }
                            }.padding(vertical = 14.dp, horizontal = 8.dp),
                        )
                    }
                }
            },
            confirmButton = { TextButton(onClick = { pickQuickWallet = false }) { Text(stringResource(R.string.action_cancel)) } },
        )
    }
}

/** Add or edit a wallet: name, currency, custom symbol, starting cash (new wallets), target (Pro), delete. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WalletEditorSheet(app: AppScope, data: AppData, wallet: Wallet?, onDismiss: () -> Unit) {
    val pro = data.isPro
    var name by remember { mutableStateOf(wallet?.name ?: "") }
    var currency by remember { mutableStateOf(wallet?.currencyCode ?: data.defaultWallet?.currencyCode ?: MoneyFormat.localeCurrency()) }
    var symbol by remember { mutableStateOf(wallet?.symbol.orEmpty()) }
    var start by remember { mutableStateOf("") }
    var target by remember { mutableStateOf(wallet?.target?.stripTrailingZeros()?.toPlainString().orEmpty()) }
    var picking by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    val walletFallback = stringResource(R.string.wallet_default_name)
    fun decimal(text: String) = text.replace(',', '.').toBigDecimalOrNull()

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).navigationBarsPadding().imePadding(),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Text(stringResource(if (wallet == null) R.string.wallet_new else R.string.wallet_edit), style = MaterialTheme.typography.titleLarge)
            OutlinedTextField(
                value = name,
                onValueChange = { name = it.take(40) },
                label = { Text(stringResource(R.string.wallet_name)) },
                placeholder = { Text(stringResource(R.string.wallet_name_hint)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(stringResource(R.string.currency_title))
                    Text(currency, color = Cash.colors.subtle)
                }
                OutlinedButton(onClick = { picking = true }) { Text(stringResource(R.string.action_change)) }
            }
            OutlinedTextField(
                value = symbol,
                onValueChange = { symbol = it.take(4) },
                label = { Text(stringResource(R.string.wallet_symbol)) },
                placeholder = { Text(MoneyFormat.full(BigDecimal.ONE, currency).filterNot { it.isDigit() || it == '.' || it == ',' }.trim()) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            if (wallet == null) {
                OutlinedTextField(
                    value = start,
                    onValueChange = { new -> start = new.filter { it.isDigit() || it == '.' || it == ',' }.take(12) },
                    label = { Text(stringResource(R.string.wallet_cash_now)) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            if (pro) {
                OutlinedTextField(
                    value = target,
                    onValueChange = { new -> target = new.filter { it.isDigit() || it == '.' || it == ',' }.take(12) },
                    label = { Text(stringResource(R.string.wallet_target)) },
                    supportingText = { Text(stringResource(R.string.wallet_target_hint)) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    textStyle = Tabular,
                    modifier = Modifier.fillMaxWidth(),
                )
            } else {
                Row(Modifier.fillMaxWidth().clickable { app.paywall() }.padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(stringResource(R.string.wallet_target))
                        Text(stringResource(R.string.wallet_target_hint), fontSize = 13.sp, color = Cash.colors.subtle)
                    }
                    LockIcon()
                }
            }
            PrimaryButton(
                stringResource(R.string.action_save),
                onClick = {
                    val cleanSymbol = symbol.trim().ifEmpty { null }
                    // Free keeps whatever target is stored; only Pro edits it.
                    val newTarget = if (pro) decimal(target)?.takeIf { it.signum() > 0 } else wallet?.target
                    app.scope.launch {
                        if (wallet == null) {
                            val cash = decimal(start) ?: BigDecimal.ZERO
                            val created = app.store.createWallet(name.ifBlank { walletFallback }, currency, cleanSymbol, cash, newTarget)
                            if (created != null) app.store.updateSettings { it.copy(selectedWalletId = created.id) }
                        } else {
                            val oldDefaults = DefaultPresets.forCurrency(wallet.currencyCode)
                            val newDefaults = DefaultPresets.forCurrency(currency)
                            // Buttons still on the old currency's defaults follow the new currency.
                            val keepAdd = wallet.addPresets != oldDefaults.add
                            val keepSpend = wallet.spendPresets != oldDefaults.spend
                            app.store.updateWallet(
                                wallet.copy(
                                    name = name.ifBlank { wallet.name },
                                    currencyCode = currency,
                                    symbol = cleanSymbol,
                                    target = newTarget,
                                    addPresets = if (keepAdd) wallet.addPresets else newDefaults.add,
                                    spendPresets = if (keepSpend) wallet.spendPresets else newDefaults.spend,
                                ),
                            )
                        }
                    }
                    onDismiss()
                },
            )
            if (wallet != null && data.wallets.size > 1) {
                TextButton(onClick = { confirmDelete = true }, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.wallet_delete), color = Cash.colors.over)
                }
            }
            Spacer(Modifier.height(8.dp))
        }
    }

    if (picking) CurrencyPickerDialog(current = currency, onDismiss = { picking = false }, onPick = { currency = it; picking = false })
    if (confirmDelete && wallet != null) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text(stringResource(R.string.wallet_delete_title, wallet.name)) },
            text = { Text(stringResource(R.string.wallet_delete_text)) },
            confirmButton = {
                TextButton(onClick = {
                    confirmDelete = false
                    app.scope.launch { app.store.deleteWallet(wallet.id) }
                    onDismiss()
                }) { Text(stringResource(R.string.action_delete), color = Cash.colors.over) }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text(stringResource(R.string.action_cancel)) } },
        )
    }
}

/** Move cash between two wallets (Pro). Different currencies ask what arrives. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TransferSheet(app: AppScope, data: AppData, onDismiss: () -> Unit) {
    val wallets = data.sortedWallets
    var fromId by remember { mutableStateOf(data.selectedWallet(true)?.id ?: wallets.first().id) }
    var toId by remember { mutableStateOf(wallets.firstOrNull { it.id != fromId }?.id ?: fromId) }
    var sent by remember { mutableStateOf("") }
    var received by remember { mutableStateOf("") }
    val from = data.wallet(fromId) ?: return
    val to = data.wallet(toId) ?: return
    val sameCurrency = from.currencyCode == to.currencyCode
    val sentValue = sent.replace(',', '.').toBigDecimalOrNull()?.takeIf { it.signum() > 0 }
    val receivedValue = if (sameCurrency) sentValue else received.replace(',', '.').toBigDecimalOrNull()?.takeIf { it.signum() > 0 }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp).navigationBarsPadding().imePadding(), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Text(stringResource(R.string.transfer_title), style = MaterialTheme.typography.titleLarge)
            WalletChooser(stringResource(R.string.transfer_from), wallets, fromId) { id -> fromId = id; if (toId == id) toId = wallets.first { it.id != id }.id }
            WalletChooser(stringResource(R.string.transfer_to), wallets.filter { it.id != fromId }, toId) { toId = it }
            OutlinedTextField(
                value = sent,
                onValueChange = { new -> sent = new.filter { it.isDigit() || it == '.' || it == ',' }.take(12) },
                label = { Text(stringResource(R.string.transfer_amount, from.currencyCode)) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                modifier = Modifier.fillMaxWidth(),
            )
            if (!sameCurrency) {
                OutlinedTextField(
                    value = received,
                    onValueChange = { new -> received = new.filter { it.isDigit() || it == '.' || it == ',' }.take(12) },
                    label = { Text(stringResource(R.string.transfer_arrives, to.currencyCode)) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            PrimaryButton(
                stringResource(R.string.transfer_move),
                onClick = {
                    val s = sentValue ?: return@PrimaryButton
                    val r = receivedValue ?: return@PrimaryButton
                    app.scope.launch { app.store.transfer(from.id, to.id, s, r) }
                    app.haptic()
                    onDismiss()
                },
                enabled = sentValue != null && receivedValue != null && from.id != to.id,
            )
            Spacer(Modifier.height(8.dp))
        }
    }
}

@Composable
private fun WalletChooser(label: String, wallets: List<Wallet>, selected: String, onPick: (String) -> Unit) {
    Column {
        Text(label, fontSize = 13.sp, color = Cash.colors.subtle)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 6.dp).horizontalScroll(rememberScrollState())) {
            wallets.forEach { w ->
                FilterChip(selected = w.id == selected, onClick = { onPick(w.id) }, label = { Text(w.name) })
            }
        }
    }
}

/** Edit a wallet's 1-tap buttons (Pro): 1-2 cash-in and 1-4 spend amounts. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PresetEditorSheet(app: AppScope, wallet: Wallet, onDismiss: () -> Unit) {
    fun text(list: List<BigDecimal>, i: Int) = list.getOrNull(i)?.stripTrailingZeros()?.toPlainString().orEmpty()
    var add by remember { mutableStateOf(List(2) { text(wallet.addPresets, it) }) }
    var spend by remember { mutableStateOf(List(4) { text(wallet.spendPresets, it) }) }
    fun parse(list: List<String>) = list.mapNotNull { it.replace(',', '.').toBigDecimalOrNull()?.takeIf { v -> v.signum() > 0 } }
    val addValues = parse(add)
    val spendValues = parse(spend)

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).navigationBarsPadding().imePadding(),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(stringResource(R.string.presets_title, wallet.name), style = MaterialTheme.typography.titleLarge)
            Text(stringResource(R.string.presets_hint), color = Cash.colors.subtle)
            Text(stringResource(R.string.presets_cash_in))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                add.forEachIndexed { i, v -> PresetField("+", v, Modifier.weight(1f)) { new -> add = add.toMutableList().also { it[i] = new } } }
            }
            Text(stringResource(R.string.presets_spend))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                spend.forEachIndexed { i, v -> PresetField(MINUS.toString(), v, Modifier.weight(1f)) { new -> spend = spend.toMutableList().also { it[i] = new } } }
            }
            PrimaryButton(
                stringResource(R.string.action_save),
                onClick = {
                    app.scope.launch { app.store.updateWallet(wallet.copy(addPresets = addValues, spendPresets = spendValues)) }
                    onDismiss()
                },
                enabled = addValues.isNotEmpty() && spendValues.isNotEmpty(),
            )
            TextButton(
                onClick = {
                    val d = DefaultPresets.forCurrency(wallet.currencyCode)
                    app.scope.launch { app.store.updateWallet(wallet.copy(addPresets = d.add, spendPresets = d.spend)) }
                    onDismiss()
                },
                modifier = Modifier.fillMaxWidth(),
            ) { Text(stringResource(R.string.presets_reset, wallet.currencyCode)) }
            Spacer(Modifier.height(8.dp))
        }
    }
}

@Composable
private fun PresetField(prefix: String, value: String, modifier: Modifier, onChange: (String) -> Unit) {
    OutlinedTextField(
        value = value,
        onValueChange = { new -> onChange(new.filter { it.isDigit() || it == '.' || it == ',' }.take(9)) },
        prefix = { Text(prefix) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
        textStyle = Tabular,
        modifier = modifier,
    )
}
