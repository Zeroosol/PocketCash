package com.patternsystems.pocketcash.ui.screens

import android.appwidget.AppWidgetManager
import android.content.ComponentName
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Undo
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Remove
import androidx.compose.material.icons.outlined.TouchApp
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.patternsystems.pocketcash.R
import com.patternsystems.pocketcash.core.AppData
import com.patternsystems.pocketcash.core.DefaultPresets
import com.patternsystems.pocketcash.core.MINUS
import com.patternsystems.pocketcash.core.MoneyFormat
import com.patternsystems.pocketcash.ui.AmountKeypad
import com.patternsystems.pocketcash.ui.CurrencyPickerDialog
import com.patternsystems.pocketcash.ui.KeypadInput
import com.patternsystems.pocketcash.ui.PrimaryButton
import com.patternsystems.pocketcash.ui.theme.Cash
import com.patternsystems.pocketcash.ui.theme.HeroStyle
import com.patternsystems.pocketcash.ui.theme.Tabular
import com.patternsystems.pocketcash.widget.MediumWidgetReceiver
import kotlinx.coroutines.launch
import java.math.BigDecimal

/** First run (docs/spec.md section 7): what PocketCash does, currency and starting cash, then the widget hint. */
@Composable
fun OnboardingScreen(app: AppScope, data: AppData) {
    var step by rememberSaveable { mutableIntStateOf(if (data.wallets.isEmpty()) 0 else 2) }
    val restore = rememberRestore(app)
    Column(Modifier.fillMaxSize().safeDrawingPadding().padding(horizontal = 24.dp, vertical = 16.dp)) {
        when (step) {
            0 -> Welcome(onRestore = restore) { step = 1 }
            1 -> StartingCash(app) { step = 2 }
            else -> WidgetHint(app)
        }
    }
}

@Composable
private fun Welcome(onRestore: () -> Unit, onNext: () -> Unit) {
    Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally) {
        Spacer(Modifier.weight(1f))
        Box(Modifier.size(132.dp).clip(RoundedCornerShape(32.dp)).background(Cash.colors.cashIn), contentAlignment = Alignment.Center) {
            Image(painterResource(R.drawable.ic_launcher_fg), contentDescription = null, modifier = Modifier.size(132.dp))
        }
        Spacer(Modifier.height(28.dp))
        Text(stringResource(R.string.onboarding_title), style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
        Spacer(Modifier.height(10.dp))
        Text(
            stringResource(R.string.onboarding_text),
            color = Cash.colors.subtle,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(28.dp))
        Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Point(Icons.Outlined.TouchApp, stringResource(R.string.onboarding_point_tap, "${MINUS}50"))
            Point(Icons.AutoMirrored.Outlined.Undo, stringResource(R.string.onboarding_point_undo))
            Point(Icons.Outlined.Tune, stringResource(R.string.onboarding_point_count))
        }
        Spacer(Modifier.weight(1f))
        PrimaryButton(stringResource(R.string.onboarding_start), onClick = onNext)
        TextButton(onClick = onRestore, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.onboarding_restore)) }
        Text(stringResource(R.string.onboarding_private), fontSize = 13.sp, color = Cash.colors.subtle, modifier = Modifier.padding(top = 2.dp))
    }
}

@Composable
private fun Point(icon: ImageVector, text: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, contentDescription = null, tint = Cash.colors.subtle, modifier = Modifier.size(22.dp))
        Spacer(Modifier.width(16.dp))
        Text(text)
    }
}

@Composable
private fun StartingCash(app: AppScope, onNext: () -> Unit) {
    var currency by rememberSaveable { mutableStateOf(MoneyFormat.localeCurrency()) }
    var picking by remember { mutableStateOf(false) }
    var byBills by rememberSaveable { mutableStateOf(false) }
    var entry by rememberSaveable { mutableStateOf("") }
    val counts = remember { mutableStateMapOf<BigDecimal, Int>() }
    val decimals = MoneyFormat.fractionDigits(currency)
    val denominations = DefaultPresets.forCurrency(currency).denominations
    val amount = if (byBills) counts.entries.fold(BigDecimal.ZERO) { s, (d, n) -> s + d.multiply(BigDecimal(n)) } else KeypadInput.value(entry)

    val mainWallet = stringResource(R.string.wallet_main)
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Text(stringResource(R.string.onboarding_cash_title), style = MaterialTheme.typography.headlineSmall)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.onboarding_currency, currency), color = Cash.colors.subtle, modifier = Modifier.weight(1f))
            TextButton(onClick = { picking = true }) { Text(stringResource(R.string.action_change)) }
        }
        Text(MoneyFormat.full(amount, currency), style = HeroStyle.copy(fontSize = 48.sp), maxLines = 1)
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
            SegmentedButton(selected = !byBills, onClick = { byBills = false }, shape = SegmentedButtonDefaults.itemShape(0, 2)) { Text(stringResource(R.string.onboarding_type_it)) }
            SegmentedButton(selected = byBills, onClick = { byBills = true }, shape = SegmentedButtonDefaults.itemShape(1, 2)) { Text(stringResource(R.string.count_bills)) }
        }
        Box(Modifier.weight(1f)) {
            if (byBills) {
                LazyColumn(Modifier.heightIn(max = 420.dp)) {
                    items(denominations, key = { it.toPlainString() }) { d ->
                        val n = counts[d] ?: 0
                        Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(MoneyFormat.full(d, currency), style = Tabular, modifier = Modifier.weight(1f))
                            OutlinedButton(onClick = { counts[d] = (n - 1).coerceAtLeast(0) }, enabled = n > 0) { Icon(Icons.Rounded.Remove, contentDescription = stringResource(R.string.count_fewer)) }
                            Text("$n", style = Tabular, modifier = Modifier.width(40.dp), textAlign = TextAlign.Center)
                            OutlinedButton(onClick = { counts[d] = n + 1 }) { Icon(Icons.Rounded.Add, contentDescription = stringResource(R.string.count_more_one)) }
                        }
                    }
                }
            } else {
                AmountKeypad(decimals = decimals, onKey = { entry = KeypadInput.press(entry, it, decimals) }, modifier = Modifier.align(Alignment.BottomCenter))
            }
        }
        PrimaryButton(
            stringResource(if (amount.signum() == 0) R.string.onboarding_start_empty else R.string.onboarding_start_tracking),
            onClick = {
                app.scope.launch {
                    app.store.createWallet(mainWallet, currency, null, amount)
                    onNext()
                }
            },
        )
    }
    if (picking) CurrencyPickerDialog(current = currency, onDismiss = { picking = false }, onPick = { currency = it; entry = ""; counts.clear(); picking = false })
}

@Composable
private fun WidgetHint(app: AppScope) {
    val context = LocalContext.current
    val manager = remember { AppWidgetManager.getInstance(context) }
    fun finish() = app.scope.launch { app.store.updateSettings { it.copy(onboarded = true, firstOpenAt = it.firstOpenAt ?: System.currentTimeMillis()) } }

    Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally) {
        Spacer(Modifier.weight(1f))
        WidgetMock()
        Spacer(Modifier.height(28.dp))
        Text(stringResource(R.string.onboarding_widget_title), style = MaterialTheme.typography.headlineSmall, textAlign = TextAlign.Center)
        Spacer(Modifier.height(10.dp))
        Text(
            stringResource(R.string.onboarding_widget_text),
            color = Cash.colors.subtle,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.weight(1f))
        if (manager.isRequestPinAppWidgetSupported) {
            PrimaryButton(
                stringResource(R.string.onboarding_add_widget),
                onClick = {
                    manager.requestPinAppWidget(ComponentName(context, MediumWidgetReceiver::class.java), null, null)
                    finish()
                },
            )
        }
        TextButton(onClick = { finish() }, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.action_done)) }
    }
}

/** A drawing of the medium widget, so people know what to look for. */
@Composable
private fun WidgetMock() {
    Column(
        Modifier.width(300.dp).clip(RoundedCornerShape(26.dp)).background(Cash.colors.card).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row {
            Text(stringResource(R.string.wallet_main), fontSize = 13.sp, color = Cash.colors.subtle, modifier = Modifier.weight(1f))
            Text(stringResource(R.string.widget_today, "${MINUS}170"), fontSize = 13.sp, color = Cash.colors.subtle)
        }
        Text("680", style = HeroStyle.copy(fontSize = 34.sp))
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf("+1k" to true, "${MINUS}100" to false, "${MINUS}50" to false, "${MINUS}20" to false).forEach { (label, plus) ->
                Box(
                    Modifier.weight(1f).height(40.dp).clip(RoundedCornerShape(14.dp))
                        .background(if (plus) Cash.colors.cashInContainer else Cash.colors.spendContainer),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(label, fontWeight = FontWeight.Medium, color = if (plus) Cash.colors.onCashInContainer else Cash.colors.onSpendContainer)
                }
            }
        }
    }
}
