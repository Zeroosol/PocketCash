package com.patternsystems.pocketcash.ui.screens

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Dialpad
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.Insights
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.SwapHoriz
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.patternsystems.pocketcash.core.Analytics
import com.patternsystems.pocketcash.core.AppData
import com.patternsystems.pocketcash.core.CashTransaction
import com.patternsystems.pocketcash.core.MINUS
import com.patternsystems.pocketcash.core.MoneyFormat
import com.patternsystems.pocketcash.core.Wallet
import com.patternsystems.pocketcash.data.isPro
import com.patternsystems.pocketcash.ui.CashCard
import com.patternsystems.pocketcash.ui.LockIcon
import com.patternsystems.pocketcash.ui.PadButton
import com.patternsystems.pocketcash.ui.ProBadge
import com.patternsystems.pocketcash.ui.cycleHeader
import com.patternsystems.pocketcash.ui.dayHeader
import com.patternsystems.pocketcash.ui.kindLabel
import com.patternsystems.pocketcash.ui.longDate
import com.patternsystems.pocketcash.ui.money
import com.patternsystems.pocketcash.ui.signed
import com.patternsystems.pocketcash.ui.theme.Cash
import com.patternsystems.pocketcash.ui.theme.HeroStyle
import com.patternsystems.pocketcash.ui.theme.Tabular
import com.patternsystems.pocketcash.ui.timeText
import com.patternsystems.pocketcash.ui.toLocalDate
import com.patternsystems.pocketcash.ui.transactionIcon
import com.patternsystems.pocketcash.ui.zone
import kotlinx.coroutines.launch
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import kotlin.math.roundToLong

/** The single-canvas Home (docs/spec.md section 7): hero balance, action pad, insight card and feed. */
@Composable
fun HomeScreen(app: AppScope, data: AppData, onSettings: () -> Unit, onInsights: () -> Unit) {
    val pro = data.isPro
    val wallet = data.selectedWallet(pro) ?: return
    val txs = remember(data.transactions, wallet.id) { data.transactionsOf(wallet.id) }
    val now = Instant.now()
    val balance = Analytics.balance(txs)
    val today = Analytics.todaySpent(txs, now, zone)
    val (addPresets, spendPresets) = data.presetsOf(wallet, pro)

    // Pull the list down past the top to open "Count my cash".
    val threshold = with(LocalDensity.current) { 96.dp.toPx() }
    val pull = remember {
        object : NestedScrollConnection {
            var pulled = 0f
            override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset {
                if (source == NestedScrollSource.UserInput && available.y > 0f) pulled += available.y
                else if (consumed.y < 0f) pulled = 0f
                return Offset.Zero
            }

            override suspend fun onPreFling(available: Velocity): Velocity {
                if (pulled > threshold) app.open(Sheet.Count)
                pulled = 0f
                return Velocity.Zero
            }
        }
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize().nestedScroll(pull),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 120.dp),
    ) {
        item { Header(app, data, wallet, pro, onSettings) }
        item { Hero(wallet, balance, today) { app.open(Sheet.Count) } }
        item {
            ActionPad(
                wallet = wallet,
                add = addPresets,
                spend = spendPresets,
                onQuick = { amount, positive ->
                    app.scope.launch {
                        app.store.quickAdd(wallet.id, amount, positive, fromWidget = false)
                            ?.let { app.announce(it, addedMessage(it, wallet.signed(it.amount))) }
                    }
                },
                onCustom = { app.open(Sheet.Keypad(add = false)) },
            )
        }
        item { InsightCard(wallet, txs, balance, pro, now, onInsights) }
        if (pro) cycleFeed(app, wallet, txs, now) else weekFeed(app, wallet, txs)
    }
}

@Composable
private fun Header(app: AppScope, data: AppData, wallet: Wallet, pro: Boolean, onSettings: () -> Unit) {
    var menu by remember { mutableStateOf(false) }
    Row(Modifier.fillMaxWidth().statusBarsPadding().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Box {
            TextButton(onClick = { menu = true }) {
                Text(wallet.name, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onBackground, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Icon(Icons.Rounded.ExpandMore, contentDescription = "Switch wallet", tint = MaterialTheme.colorScheme.onBackground)
            }
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                data.sortedWallets.forEach { w ->
                    DropdownMenuItem(
                        text = { Text(w.name) },
                        trailingIcon = { if (w.id == wallet.id) Icon(Icons.Rounded.Check, contentDescription = null) },
                        onClick = {
                            menu = false
                            if (w.id == wallet.id) return@DropdownMenuItem
                            if (!pro) app.paywall() else app.scope.launch { app.store.updateSettings { it.copy(selectedWalletId = w.id) } }
                        },
                    )
                }
                HorizontalDivider()
                DropdownMenuItem(
                    text = { Text("New wallet") },
                    leadingIcon = { Icon(Icons.Rounded.Add, contentDescription = null) },
                    trailingIcon = { if (!pro) LockIcon() },
                    onClick = { menu = false; if (pro) app.open(Sheet.WalletEditor(null)) else app.paywall() },
                )
                if (data.wallets.size > 1) {
                    DropdownMenuItem(
                        text = { Text("Move cash between wallets") },
                        leadingIcon = { Icon(Icons.Rounded.SwapHoriz, contentDescription = null) },
                        trailingIcon = { if (!pro) LockIcon() },
                        onClick = { menu = false; if (pro) app.open(Sheet.Transfer) else app.paywall() },
                    )
                }
            }
        }
        Spacer(Modifier.weight(1f))
        if (pro) ProBadge(Modifier.padding(end = 4.dp))
        IconButton(onClick = onSettings) { Icon(Icons.Rounded.Settings, contentDescription = "Settings") }
    }
}

@Composable
private fun Hero(wallet: Wallet, balance: BigDecimal, today: BigDecimal, onCount: () -> Unit) {
    val text = wallet.money(balance)
    val size = when {
        text.length > 13 -> 38.sp
        text.length > 10 -> 48.sp
        else -> 60.sp
    }
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(24.dp)).clickable(onClick = onCount).padding(vertical = 20.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text,
            style = HeroStyle.copy(fontSize = size),
            color = if (balance.signum() < 0) Cash.colors.over else MaterialTheme.colorScheme.onBackground,
            maxLines = 1,
        )
        Spacer(Modifier.height(6.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                if (today.signum() == 0) "Nothing spent today" else "Today $MINUS${wallet.money(today)}",
                style = Tabular,
                color = Cash.colors.subtle,
            )
            val budget = wallet.dailyBudget
            if (budget != null && budget.signum() > 0) {
                val left = budget - today
                Text("  ·  ", color = Cash.colors.subtle)
                if (left.signum() >= 0) Text("${wallet.money(left)} left today", style = Tabular, color = Cash.colors.cashIn, fontWeight = FontWeight.Medium)
                else Text("Over by ${wallet.money(left.negate())}", style = Tabular, color = Cash.colors.over, fontWeight = FontWeight.Medium)
            }
        }
        Text("Tap or pull down to count your cash", fontSize = 12.sp, color = Cash.colors.subtle.copy(alpha = 0.7f), modifier = Modifier.padding(top = 4.dp))
    }
}

@Composable
private fun ActionPad(wallet: Wallet, add: List<BigDecimal>, spend: List<BigDecimal>, onQuick: (BigDecimal, Boolean) -> Unit, onCustom: () -> Unit) {
    val colors = Cash.colors
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            add.forEach { amount ->
                PadButton(MoneyFormat.compact(amount, '+'), colors.cashInContainer, colors.onCashInContainer) { onQuick(amount, true) }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            spend.forEach { amount ->
                PadButton(MoneyFormat.compact(amount, MINUS), colors.spendContainer, colors.onSpendContainer) { onQuick(amount, false) }
            }
        }
        OutlinedButton(onClick = onCustom, modifier = Modifier.fillMaxWidth().height(52.dp), shape = RoundedCornerShape(18.dp)) {
            Icon(Icons.Rounded.Dialpad, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text("Custom amount", fontWeight = FontWeight.SemiBold)
        }
        Text(
            "${wallet.currencyCode} · buttons save instantly, with Undo",
            fontSize = 12.sp,
            color = Cash.colors.subtle,
            modifier = Modifier.fillMaxWidth().padding(top = 2.dp),
        )
    }
}

@Composable
private fun InsightCard(wallet: Wallet, txs: List<CashTransaction>, balance: BigDecimal, pro: Boolean, now: Instant, onInsights: () -> Unit) {
    CashCard(Modifier.padding(top = 18.dp).clip(RoundedCornerShape(20.dp)).clickable(onClick = onInsights)) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(40.dp).clip(CircleShape).background(Cash.colors.cashInContainer), contentAlignment = Alignment.Center) {
                Icon(Icons.Rounded.Insights, contentDescription = null, tint = Cash.colors.onCashInContainer)
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                if (pro) {
                    val burn = Analytics.burnRatePerDay(txs, now)
                    val runway = Analytics.runwayDays(balance, burn)
                    if (burn == null || burn <= 0.0) {
                        Text("Burn rate", fontWeight = FontWeight.SemiBold)
                        Text("Spend a few times to see how long your cash lasts.", color = Cash.colors.subtle, fontSize = 13.sp)
                    } else {
                        val perDay = wallet.money(BigDecimal.valueOf(burn.roundToLong()))
                        Text("$perDay/day", fontWeight = FontWeight.SemiBold, style = Tabular)
                        val until = runway?.let { longDate(now.plusSeconds((it * 86_400).toLong())) }
                        Text(if (until != null) "Cash lasts until about $until" else "Top up to see how long cash lasts", color = Cash.colors.subtle, fontSize = 13.sp)
                    }
                } else {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("How long will your cash last?", fontWeight = FontWeight.SemiBold)
                        Spacer(Modifier.width(6.dp))
                        ProBadge()
                    }
                    Text("Burn rate, ATM cycles and categories.", color = Cash.colors.subtle, fontSize = 13.sp)
                }
            }
            Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, contentDescription = null, tint = Cash.colors.subtle)
        }
    }
}

// MARK: Feed

private fun LazyListScope.feedTitle(text: String) {
    item {
        Text(
            text,
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(top = 24.dp, bottom = 4.dp, start = 4.dp),
        )
    }
}

/** Free: the last 7 days, grouped by day. */
private fun LazyListScope.weekFeed(app: AppScope, wallet: Wallet, txs: List<CashTransaction>) {
    val today = LocalDate.now(zone)
    val since = today.minusDays(6)
    val recent = txs.filter { !it.timestamp.toLocalDate().isBefore(since) }.sortedByDescending { it.timestamp }
    val older = txs.size - recent.size
    feedTitle("Last 7 days")
    if (recent.isEmpty()) {
        item { Text("Nothing yet. Tap a button above when cash leaves your wallet.", color = Cash.colors.subtle, modifier = Modifier.padding(4.dp)) }
    }
    recent.groupBy { it.timestamp.toLocalDate() }.forEach { (day, list) ->
        item(key = "day-$day") { GroupHeader(dayHeader(day, today), null, null) }
        items(list, key = { it.id }) { tx -> FeedRow(app, wallet, tx) }
    }
    if (older > 0) {
        item(key = "older") {
            CashCard(Modifier.padding(top = 16.dp).clip(RoundedCornerShape(20.dp)).clickable { app.paywall() }) {
                Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("$older older ${if (older == 1) "entry" else "entries"} · full history and ATM cycles", modifier = Modifier.weight(1f), color = Cash.colors.subtle)
                    ProBadge()
                }
            }
        }
    }
}

/** Pro: full history, grouped by ATM cycle with collapsible headers. */
private fun LazyListScope.cycleFeed(app: AppScope, wallet: Wallet, txs: List<CashTransaction>, now: Instant) {
    val cycles = Analytics.cycles(txs, now).reversed()
    feedTitle("ATM cycles")
    if (cycles.isEmpty()) {
        item { Text("Nothing yet. Tap a button above when cash leaves your wallet.", color = Cash.colors.subtle, modifier = Modifier.padding(4.dp)) }
    }
    cycles.forEachIndexed { index, cycle ->
        val key = cycle.start.toEpochMilli()
        item(key = "cycle-$key") {
            val expanded = CycleState.isExpanded(key, index)
            GroupHeader(
                title = cycleHeader(cycle, wallet),
                trailing = "$MINUS${wallet.money(cycle.spent)}",
                expanded = expanded,
                onToggle = { CycleState.toggle(key, index) },
            )
        }
        if (CycleState.isExpanded(key, index)) {
            items(cycle.transactions.sortedByDescending { it.timestamp }, key = { it.id }) { tx -> FeedRow(app, wallet, tx, showDay = true) }
        }
    }
}

/** Which cycle headers are open. The newest two start open. */
private object CycleState {
    private val state = mutableStateMapOf<Long, Boolean>()
    fun isExpanded(key: Long, index: Int): Boolean = state[key] ?: (index < 2)
    fun toggle(key: Long, index: Int) { state[key] = !isExpanded(key, index) }
}

@Composable
private fun GroupHeader(title: String, trailing: String?, expanded: Boolean?, onToggle: (() -> Unit)? = null) {
    Row(
        Modifier.fillMaxWidth().padding(top = 14.dp).clip(RoundedCornerShape(10.dp))
            .then(if (onToggle != null) Modifier.clickable(onClick = onToggle) else Modifier)
            .padding(horizontal = 4.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(title, style = MaterialTheme.typography.labelLarge, color = Cash.colors.subtle, modifier = Modifier.weight(1f))
        if (trailing != null) Text(trailing, style = Tabular, fontSize = 13.sp, color = Cash.colors.subtle)
        if (expanded != null) Icon(if (expanded) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore, contentDescription = if (expanded) "Collapse" else "Expand", tint = Cash.colors.subtle)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FeedRow(app: AppScope, wallet: Wallet, tx: CashTransaction, showDay: Boolean = false) {
    val state = rememberSwipeToDismissBoxState()
    LaunchedEffect(state.currentValue) {
        if (state.currentValue == SwipeToDismissBoxValue.EndToStart) app.deleteWithUndo(tx)
    }
    SwipeToDismissBox(
        state = state,
        enableDismissFromStartToEnd = false,
        modifier = Modifier.padding(vertical = 3.dp).animateContentSize(),
        backgroundContent = {
            Box(
                Modifier.fillMaxSize().clip(RoundedCornerShape(16.dp)).background(Cash.colors.over).padding(horizontal = 20.dp),
                contentAlignment = Alignment.CenterEnd,
            ) {
                Icon(Icons.Rounded.Delete, contentDescription = "Delete", tint = Color.White)
            }
        },
    ) {
        Row(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(Cash.colors.card)
                .clickable { app.open(Sheet.Edit(tx.id)) }.padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            val positive = tx.amount.signum() > 0
            Box(
                Modifier.size(36.dp).clip(CircleShape).background(if (positive) Cash.colors.cashInContainer else Cash.colors.spendContainer),
                contentAlignment = Alignment.Center,
            ) {
                Icon(transactionIcon(tx), contentDescription = null, modifier = Modifier.size(20.dp), tint = if (positive) Cash.colors.onCashInContainer else Cash.colors.onSpendContainer)
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(tx.note ?: kindLabel(tx), fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                val time = timeText(tx.timestamp)
                Text(if (showDay) "${dayHeader(tx.timestamp.toLocalDate())} · $time" else time, fontSize = 12.sp, color = Cash.colors.subtle)
            }
            Text(
                wallet.signed(tx.amount),
                style = Tabular,
                fontWeight = FontWeight.SemiBold,
                color = if (positive) Cash.colors.cashIn else MaterialTheme.colorScheme.onBackground,
            )
        }
    }
}
