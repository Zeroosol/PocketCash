package com.patternsystems.pocketcash.ui.screens

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.patternsystems.pocketcash.BuildConfig
import com.patternsystems.pocketcash.core.AppData
import com.patternsystems.pocketcash.core.DefaultPresets
import com.patternsystems.pocketcash.core.MINUS
import com.patternsystems.pocketcash.core.MoneyFormat
import com.patternsystems.pocketcash.core.Wallet
import com.patternsystems.pocketcash.data.Export
import com.patternsystems.pocketcash.data.LowCashNotifier
import com.patternsystems.pocketcash.data.ProBilling
import com.patternsystems.pocketcash.data.isPro
import com.patternsystems.pocketcash.ui.AmountDialog
import com.patternsystems.pocketcash.ui.CashCard
import com.patternsystems.pocketcash.ui.CurrencyPickerDialog
import com.patternsystems.pocketcash.ui.LockIcon
import com.patternsystems.pocketcash.ui.ProBadge
import com.patternsystems.pocketcash.ui.SectionTitle
import com.patternsystems.pocketcash.ui.money
import com.patternsystems.pocketcash.ui.theme.Cash
import com.patternsystems.pocketcash.ui.theme.Tabular
import kotlinx.coroutines.launch
import java.math.BigDecimal

private enum class AmountField { QUICK, BUDGET, THRESHOLD }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(app: AppScope, data: AppData, onBack: () -> Unit) {
    val context = LocalContext.current
    val pro = data.isPro
    val wallet = data.selectedWallet(pro) ?: return
    val settings = data.settings
    var editing by remember { mutableStateOf<AmountField?>(null) }
    var pickQuickWallet by remember { mutableStateOf(false) }
    val billing = remember { ProBilling.get(context) }

    val notificationPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        app.scope.launch { app.store.updateSettings { it.copy(lowCashAlerts = granted) } }
    }
    fun update(change: (com.patternsystems.pocketcash.core.Settings) -> com.patternsystems.pocketcash.core.Settings) {
        app.scope.launch { app.store.updateSettings(change) }
    }

    Column(Modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text("Settings") },
            navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Back") } },
            colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
        )
        LazyColumn(contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 48.dp)) {
            item {
                CashCard {
                    Column(Modifier.padding(16.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("PocketCash Pro", fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                            if (pro) ProBadge()
                        }
                        if (pro) {
                            Text("Unlocked. Thank you for supporting PocketCash!", color = Cash.colors.subtle)
                        } else {
                            Text("Custom buttons, more wallets, ATM cycle insights, categories, full history and CSV export. One payment, yours forever.", color = Cash.colors.subtle)
                            Spacer(Modifier.height(12.dp))
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Button(onClick = app.paywall) { Text("Unlock Pro") }
                                OutlinedButton(onClick = { billing.restore() }) { Text("Restore") }
                            }
                        }
                    }
                }
            }

            item { SectionTitle("Wallets") }
            item {
                CashCard {
                    Column {
                        data.sortedWallets.forEachIndexed { i, w ->
                            if (i > 0) HorizontalDivider()
                            Row(
                                Modifier.fillMaxWidth().clickable { app.open(Sheet.WalletEditor(w.id)) }.padding(16.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Column(Modifier.weight(1f)) {
                                    Text(w.name, fontWeight = FontWeight.Medium)
                                    Text(w.currencyCode + (w.symbol?.let { " · $it" } ?: ""), fontSize = 13.sp, color = Cash.colors.subtle)
                                }
                                Text(w.money(data.balanceOf(w.id)), style = Tabular, color = Cash.colors.subtle)
                                Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, contentDescription = null, tint = Cash.colors.subtle)
                            }
                        }
                        HorizontalDivider()
                        Row(
                            Modifier.fillMaxWidth().clickable { if (pro) app.open(Sheet.WalletEditor(null)) else app.paywall() }.padding(16.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text("Add a wallet", color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
                            if (!pro) LockIcon()
                        }
                        if (data.wallets.size > 1) {
                            HorizontalDivider()
                            Row(
                                Modifier.fillMaxWidth().clickable { if (pro) app.open(Sheet.Transfer) else app.paywall() }.padding(16.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text("Move cash between wallets", color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
                                if (!pro) LockIcon()
                            }
                        }
                    }
                }
            }

            item { SectionTitle(wallet.name) }
            item {
                CashCard {
                    Column {
                        val (add, spend) = data.presetsOf(wallet, pro)
                        SettingRow(
                            title = "Buttons",
                            value = (add.map { MoneyFormat.compact(it, '+') } + spend.map { MoneyFormat.compact(it, MINUS) }).joinToString("  "),
                            locked = !pro,
                        ) { if (pro) app.open(Sheet.Presets) else app.paywall() }
                        HorizontalDivider()
                        SettingRow("Daily budget", wallet.dailyBudget?.let { wallet.money(it) } ?: "Off") { editing = AmountField.BUDGET }
                        HorizontalDivider()
                        SettingRow("Low-cash reminder below", wallet.lowCashThreshold?.let { wallet.money(it) } ?: "Not set") { editing = AmountField.THRESHOLD }
                    }
                }
            }

            item { SectionTitle("Quick spend") }
            item {
                CashCard {
                    Column {
                        val quickWallet = data.quickSpendWallet(pro)
                        val amount = data.quickSpendAmount(pro)
                        SettingRow(
                            "Amount",
                            if (quickWallet != null && amount != null) MINUS + quickWallet.money(amount) else "Not set",
                            subtitle = "The Quick Settings tile spends this in one tap. Add it from your Quick Settings edit screen.",
                        ) { editing = AmountField.QUICK }
                        if (pro && data.wallets.size > 1) {
                            HorizontalDivider()
                            SettingRow("From wallet", quickWallet?.name ?: "") { pickQuickWallet = true }
                        }
                    }
                }
            }

            item { SectionTitle("Options") }
            item {
                CashCard {
                    Column {
                        SwitchRow("Low-cash reminders", "A notification when a wallet drops below its reminder amount", settings.lowCashAlerts) { on ->
                            if (on && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && !LowCashNotifier.canNotify(context)) {
                                notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
                            } else {
                                update { it.copy(lowCashAlerts = on) }
                            }
                        }
                        HorizontalDivider()
                        SwitchRow("Count + buttons as ATM withdrawals", "Each + tap starts a new ATM cycle", settings.plusButtonsAreATM) { on -> update { it.copy(plusButtonsAreATM = on) } }
                        HorizontalDivider()
                        SwitchRow("Privacy mode", "Hide amounts on widgets and Quick Settings", settings.privacyMode) { on -> update { it.copy(privacyMode = on) } }
                        HorizontalDivider()
                        SwitchRow("Haptics", "A short tap when a button saves", settings.haptics) { on -> update { it.copy(haptics = on) } }
                    }
                }
            }

            item { SectionTitle("Your data") }
            item {
                CashCard {
                    Column {
                        SettingRow("Export CSV", "", subtitle = "Every transaction, for a spreadsheet", locked = !pro) {
                            if (pro) Export.shareCsv(context, data) else app.paywall()
                        }
                    }
                }
            }

            if (BuildConfig.DEBUG) {
                item { SectionTitle("Debug") }
                item {
                    CashCard {
                        SwitchRow("Unlock Pro (debug build)", "For testing without Google Play. Release builds ignore it.", settings.debugPro) { on -> update { it.copy(debugPro = on) } }
                    }
                }
            }

            item {
                Text(
                    "PocketCash ${BuildConfig.VERSION_NAME}\nEverything stays on this phone: no account, no internet access, no tracking.",
                    color = Cash.colors.subtle,
                    fontSize = 13.sp,
                    modifier = Modifier.fillMaxWidth().padding(top = 28.dp),
                )
            }
        }
    }

    when (editing) {
        AmountField.QUICK -> AmountDialog(
            title = "Quick spend amount",
            message = "Your most common cash expense, like a daily commute fare.",
            initial = data.quickSpendAmount(pro),
            onDismiss = { editing = null },
            onSave = { v -> editing = null; update { it.copy(quickSpendAmount = v) } },
            clearLabel = "Default",
        )
        AmountField.BUDGET -> AmountDialog(
            title = "Daily budget",
            message = "How much cash you plan to spend per day from ${wallet.name}.",
            initial = wallet.dailyBudget,
            onDismiss = { editing = null },
            onSave = { v -> editing = null; app.scope.launch { app.store.updateWallet(wallet.copy(dailyBudget = v)) } },
            clearLabel = "Turn off",
        )
        AmountField.THRESHOLD -> AmountDialog(
            title = "Low-cash reminder",
            message = "Remind me when ${wallet.name} drops below this amount. Switch on Low-cash reminders below.",
            initial = wallet.lowCashThreshold,
            onDismiss = { editing = null },
            onSave = { v -> editing = null; app.scope.launch { app.store.updateWallet(wallet.copy(lowCashThreshold = v)) } },
            clearLabel = "Remove",
        )
        null -> Unit
    }

    if (pickQuickWallet) {
        AlertDialog(
            onDismissRequest = { pickQuickWallet = false },
            title = { Text("Quick spend from") },
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
            confirmButton = { TextButton(onClick = { pickQuickWallet = false }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun SettingRow(title: String, value: String, subtitle: String? = null, locked: Boolean = false, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, fontWeight = FontWeight.Medium)
            if (subtitle != null) Text(subtitle, fontSize = 13.sp, color = Cash.colors.subtle)
        }
        Spacer(Modifier.width(12.dp))
        Text(value, style = Tabular, color = Cash.colors.subtle, maxLines = 1)
        if (locked) {
            Spacer(Modifier.width(6.dp))
            LockIcon()
        }
    }
}

@Composable
private fun SwitchRow(title: String, subtitle: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().clickable { onChange(!checked) }.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, fontWeight = FontWeight.Medium)
            Text(subtitle, fontSize = 13.sp, color = Cash.colors.subtle)
        }
        Spacer(Modifier.width(12.dp))
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

/** Add or edit a wallet: name, currency, custom symbol, starting cash (new wallets), delete. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WalletEditorSheet(app: AppScope, data: AppData, wallet: Wallet?, onDismiss: () -> Unit) {
    var name by remember { mutableStateOf(wallet?.name ?: "") }
    var currency by remember { mutableStateOf(wallet?.currencyCode ?: data.defaultWallet?.currencyCode ?: MoneyFormat.localeCurrency()) }
    var symbol by remember { mutableStateOf(wallet?.symbol.orEmpty()) }
    var start by remember { mutableStateOf("") }
    var picking by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).navigationBarsPadding(),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Text(if (wallet == null) "New wallet" else "Edit wallet", style = MaterialTheme.typography.titleLarge)
            OutlinedTextField(
                value = name,
                onValueChange = { name = it.take(40) },
                label = { Text("Name") },
                placeholder = { Text("Coin Pouch, Emergency Stash, Japan trip…") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Currency", fontWeight = FontWeight.Medium)
                    Text(currency, color = Cash.colors.subtle)
                }
                OutlinedButton(onClick = { picking = true }) { Text("Change") }
            }
            OutlinedTextField(
                value = symbol,
                onValueChange = { symbol = it.take(4) },
                label = { Text("Custom symbol (optional)") },
                placeholder = { Text(MoneyFormat.full(BigDecimal.ONE, currency).filterNot { it.isDigit() || it == '.' || it == ',' }.trim()) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            if (wallet == null) {
                OutlinedTextField(
                    value = start,
                    onValueChange = { new -> start = new.filter { it.isDigit() || it == '.' || it == ',' }.take(12) },
                    label = { Text("Cash in it now") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            Button(
                onClick = {
                    val cleanSymbol = symbol.trim().ifEmpty { null }
                    app.scope.launch {
                        if (wallet == null) {
                            val cash = start.replace(',', '.').toBigDecimalOrNull() ?: BigDecimal.ZERO
                            val created = app.store.createWallet(name.ifBlank { "Wallet" }, currency, cleanSymbol, cash)
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
                                    addPresets = if (keepAdd) wallet.addPresets else newDefaults.add,
                                    spendPresets = if (keepSpend) wallet.spendPresets else newDefaults.spend,
                                ),
                            )
                        }
                    }
                    onDismiss()
                },
                modifier = Modifier.fillMaxWidth().height(52.dp),
                shape = RoundedCornerShape(18.dp),
            ) { Text("Save") }
            if (wallet != null && data.wallets.size > 1) {
                TextButton(onClick = { confirmDelete = true }, modifier = Modifier.fillMaxWidth()) {
                    Text("Delete wallet", color = Cash.colors.over)
                }
            }
            Spacer(Modifier.height(8.dp))
        }
    }

    if (picking) CurrencyPickerDialog(current = currency, onDismiss = { picking = false }, onPick = { currency = it; picking = false })
    if (confirmDelete && wallet != null) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Delete ${wallet.name}?") },
            text = { Text("Its transactions are deleted too. This can't be undone.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmDelete = false
                    app.scope.launch { app.store.deleteWallet(wallet.id) }
                    onDismiss()
                }) { Text("Delete", color = Cash.colors.over) }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Cancel") } },
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
        Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp).navigationBarsPadding(), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Text("Move cash", style = MaterialTheme.typography.titleLarge)
            WalletChooser("From", wallets, fromId) { id -> fromId = id; if (toId == id) toId = wallets.first { it.id != id }.id }
            WalletChooser("To", wallets.filter { it.id != fromId }, toId) { toId = it }
            OutlinedTextField(
                value = sent,
                onValueChange = { new -> sent = new.filter { it.isDigit() || it == '.' || it == ',' }.take(12) },
                label = { Text("Amount (${from.currencyCode})") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                modifier = Modifier.fillMaxWidth(),
            )
            if (!sameCurrency) {
                OutlinedTextField(
                    value = received,
                    onValueChange = { new -> received = new.filter { it.isDigit() || it == '.' || it == ',' }.take(12) },
                    label = { Text("Arrives as (${to.currencyCode})") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            Button(
                onClick = {
                    val s = sentValue ?: return@Button
                    val r = receivedValue ?: return@Button
                    app.scope.launch { app.store.transfer(from.id, to.id, s, r) }
                    app.haptic()
                    onDismiss()
                },
                enabled = sentValue != null && receivedValue != null && from.id != to.id,
                modifier = Modifier.fillMaxWidth().height(52.dp),
                shape = RoundedCornerShape(18.dp),
            ) { Text("Move") }
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
                androidx.compose.material3.FilterChip(selected = w.id == selected, onClick = { onPick(w.id) }, label = { Text(w.name) })
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
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).navigationBarsPadding(),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("Buttons for ${wallet.name}", style = MaterialTheme.typography.titleLarge)
            Text("Match your real bills and fares. Leave a box empty to drop that button.", color = Cash.colors.subtle)
            Text("Cash in", fontWeight = FontWeight.Medium)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                add.forEachIndexed { i, v -> PresetField("+", v, Modifier.weight(1f)) { new -> add = add.toMutableList().also { it[i] = new } } }
            }
            Text("Spend", fontWeight = FontWeight.Medium)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                spend.forEachIndexed { i, v -> PresetField(MINUS.toString(), v, Modifier.weight(1f)) { new -> spend = spend.toMutableList().also { it[i] = new } } }
            }
            Button(
                onClick = {
                    app.scope.launch { app.store.updateWallet(wallet.copy(addPresets = addValues, spendPresets = spendValues)) }
                    onDismiss()
                },
                enabled = addValues.isNotEmpty() && spendValues.isNotEmpty(),
                modifier = Modifier.fillMaxWidth().height(52.dp),
                shape = RoundedCornerShape(18.dp),
            ) { Text("Save") }
            TextButton(
                onClick = {
                    val d = DefaultPresets.forCurrency(wallet.currencyCode)
                    app.scope.launch { app.store.updateWallet(wallet.copy(addPresets = d.add, spendPresets = d.spend)) }
                    onDismiss()
                },
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Reset to ${wallet.currencyCode} defaults") }
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
