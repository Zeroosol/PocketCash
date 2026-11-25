package com.patternsystems.pocketcash.ui.screens

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.ContentTransform
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
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
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Dialpad
import androidx.compose.material.icons.outlined.SwapHoriz
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.patternsystems.pocketcash.R
import com.patternsystems.pocketcash.core.Analytics
import com.patternsystems.pocketcash.core.AppData
import com.patternsystems.pocketcash.core.CashTransaction
import com.patternsystems.pocketcash.core.MINUS
import com.patternsystems.pocketcash.core.MoneyFormat
import com.patternsystems.pocketcash.core.Wallet
import com.patternsystems.pocketcash.data.budgetOf
import com.patternsystems.pocketcash.data.isPro
import com.patternsystems.pocketcash.ui.BarClearance
import com.patternsystems.pocketcash.ui.Hairline
import com.patternsystems.pocketcash.ui.LockIcon
import com.patternsystems.pocketcash.ui.PadButton
import com.patternsystems.pocketcash.ui.ProBadge
import com.patternsystems.pocketcash.ui.ScreenPadding
import com.patternsystems.pocketcash.ui.addedMessage
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
import com.patternsystems.pocketcash.ui.transferPartner
import com.patternsystems.pocketcash.ui.zone
import kotlinx.coroutines.launch
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import kotlin.math.roundToLong

/** Home (docs/spec.md section 7): wallet switcher, hero balance, action pad, insight line and feed. */
@Composable
fun HomeScreen(app: AppScope, data: AppData) {
    val pro = data.isPro
    val wallet = data.selectedWallet(pro) ?: return
    val txs = remember(data.transactions, wallet.id) { data.transactionsOf(wallet.id) }
    val now = Instant.now()
    val balance = Analytics.balance(txs)
    val today = Analytics.todaySpent(txs, now, zone)
    val (addPresets, spendPresets) = data.presetsOf(wallet, pro)
    val context = LocalContext.current

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

    // The balance moves into the top bar once the hero scrolls under it.
    val list = rememberLazyListState()
    var heroHeight by remember { mutableIntStateOf(1) }
    val heroGone by remember { derivedStateOf { list.firstVisibleItemIndex > 0 || list.firstVisibleItemScrollOffset > heroHeight * 0.6f } }

    Column(Modifier.fillMaxSize()) {
        TopBar(app, data, wallet, pro, balanceText = wallet.money(balance), showBalance = heroGone, scrolled = heroGone)
        LazyColumn(
            state = list,
            modifier = Modifier.fillMaxSize().nestedScroll(pull),
            contentPadding = PaddingValues(bottom = BarClearance),
        ) {
            item(key = "hero") {
                Hero(wallet, balance, today, data.budgetOf(wallet), Modifier.onSizeChanged { heroHeight = it.height.coerceAtLeast(1) }) { app.open(Sheet.Count) }
            }
            item(key = "pad") {
                ActionPad(
                    add = addPresets,
                    spend = spendPresets,
                    onQuick = { amount, positive ->
                        app.scope.launch {
                            app.store.quickAdd(wallet.id, amount, positive, fromWidget = false)
                                ?.let { app.announce(it, addedMessage(context, it, wallet.signed(it.amount))) }
                        }
                    },
                    onCustom = { app.open(Sheet.Keypad(add = false)) },
                )
            }
            if (pro) item(key = "insight") { InsightLine(wallet, txs, balance, now) { app.navigate(Destination.INSIGHTS) } }
            if (pro) cycleFeed(app, data, wallet, txs, now) else weekFeed(app, data, wallet, txs)
        }
    }
}

@Composable
private fun TopBar(app: AppScope, data: AppData, wallet: Wallet, pro: Boolean, balanceText: String, showBalance: Boolean, scrolled: Boolean) {
    var menu by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.background).statusBarsPadding()) {
        Box(Modifier.fillMaxWidth().height(56.dp).padding(horizontal = 8.dp)) {
            Row(Modifier.align(Alignment.CenterStart), verticalAlignment = Alignment.CenterVertically) {
                Box {
                    Row(
                        Modifier.clip(RoundedCornerShape(12.dp)).clickable { menu = true }.padding(horizontal = 12.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(wallet.name, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(end = 2.dp))
                        Icon(Icons.Rounded.ExpandMore, contentDescription = stringResource(R.string.home_switch_wallet), modifier = Modifier.size(20.dp))
                    }
                    WalletMenu(app, data, wallet, pro, menu) { menu = false }
                }
                if (pro) ProBadge()
            }
            BarBalance(showBalance, balanceText, Modifier.align(Alignment.Center))
        }
        if (scrolled) Hairline()
    }
}

/** The small balance in the top bar; fades and slides in once the big balance is under the bar. */
@Composable
private fun BarBalance(visible: Boolean, text: String, modifier: Modifier) {
    AnimatedVisibility(
        visible = visible,
        modifier = modifier,
        enter = fadeIn() + slideInVertically { it / 2 },
        exit = fadeOut() + slideOutVertically { it / 2 },
    ) {
        Text(text, style = Tabular, fontSize = 17.sp, maxLines = 1)
    }
}

@Composable
private fun WalletMenu(app: AppScope, data: AppData, wallet: Wallet, pro: Boolean, expanded: Boolean, onClose: () -> Unit) {
    DropdownMenu(expanded = expanded, onDismissRequest = onClose) {
        data.sortedWallets.forEach { w ->
            DropdownMenuItem(
                text = { Text(w.name) },
                trailingIcon = { if (w.id == wallet.id) Icon(Icons.Rounded.Check, contentDescription = null) },
                onClick = {
                    onClose()
                    if (w.id == wallet.id) return@DropdownMenuItem
                    if (!pro) app.paywall() else app.scope.launch { app.store.updateSettings { it.copy(selectedWalletId = w.id) } }
                },
            )
        }
        Hairline()
        DropdownMenuItem(
            text = { Text(stringResource(R.string.wallet_new)) },
            leadingIcon = { Icon(Icons.Outlined.Add, contentDescription = null) },
            trailingIcon = { if (!pro) LockIcon() },
            onClick = { onClose(); if (pro) app.open(Sheet.WalletEditor(null)) else app.paywall() },
        )
        if (data.wallets.size > 1) {
            DropdownMenuItem(
                text = { Text(stringResource(R.string.transfer_title_long)) },
                leadingIcon = { Icon(Icons.Outlined.SwapHoriz, contentDescription = null) },
                trailingIcon = { if (!pro) LockIcon() },
                onClick = { onClose(); if (pro) app.open(Sheet.Transfer) else app.paywall() },
            )
        }
    }
}

/** The balance rolls to its new value: old digits slide out, new ones in. */
@Composable
private fun RollingBalance(text: String, negative: Boolean, size: Int) {
    AnimatedContent(
        targetState = text,
        transitionSpec = {
            ContentTransform(
                targetContentEnter = fadeIn() + slideInVertically { it / 3 },
                initialContentExit = fadeOut() + slideOutVertically { -it / 3 },
            )
        },
        label = "balance",
    ) { value ->
        Text(value, style = HeroStyle.copy(fontSize = size.sp), color = if (negative) Cash.colors.over else MaterialTheme.colorScheme.onBackground, maxLines = 1)
    }
}

@Composable
private fun Hero(wallet: Wallet, balance: BigDecimal, today: BigDecimal, budget: BigDecimal?, modifier: Modifier, onCount: () -> Unit) {
    val text = wallet.money(balance)
    val size = when {
        text.length > 13 -> 40
        text.length > 10 -> 50
        else -> 64
    }
    Column(
        modifier.fillMaxWidth().padding(top = 28.dp, bottom = 32.dp).clickable(interactionSource = null, indication = null, onClick = onCount),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        RollingBalance(text, balance.signum() < 0, size)
        Spacer(Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                if (today.signum() == 0) stringResource(R.string.home_nothing_today) else stringResource(R.string.home_today, "$MINUS${wallet.money(today)}"),
                style = Tabular,
                color = Cash.colors.subtle,
            )
            if (budget != null) {
                val left = budget - today
                Text("  ·  ", color = Cash.colors.subtle)
                if (left.signum() >= 0) Text(stringResource(R.string.home_left_today, wallet.money(left)), style = Tabular, color = Cash.colors.subtle)
                else Text(stringResource(R.string.home_over_by, wallet.money(left.negate())), style = Tabular, color = Cash.colors.over)
            }
        }
    }
}

@Composable
private fun ActionPad(add: List<BigDecimal>, spend: List<BigDecimal>, onQuick: (BigDecimal, Boolean) -> Unit, onCustom: () -> Unit) {
    val colors = Cash.colors
    Column(Modifier.padding(horizontal = ScreenPadding), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            add.forEach { amount ->
                PadButton(MoneyFormat.compact(amount, '+'), colors.cashInContainer, colors.onCashInContainer) { onQuick(amount, true) }
            }
            Row(
                Modifier.weight(1f).height(58.dp).clip(RoundedCornerShape(16.dp)).background(colors.spendContainer).clickable(onClick = onCustom),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Outlined.Dialpad, contentDescription = null, modifier = Modifier.size(18.dp), tint = colors.onSpendContainer)
                Spacer(Modifier.width(6.dp))
                Text(stringResource(R.string.home_custom), color = colors.onSpendContainer, fontSize = 16.sp, maxLines = 1)
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            spend.forEach { amount ->
                PadButton(MoneyFormat.compact(amount, MINUS), colors.spendContainer, colors.onSpendContainer) { onQuick(amount, false) }
            }
        }
    }
}

/** Pro: one quiet line with the burn rate and runway; tapping it opens Insights. */
@Composable
private fun InsightLine(wallet: Wallet, txs: List<CashTransaction>, balance: BigDecimal, now: Instant, onClick: () -> Unit) {
    val burn = Analytics.burnRatePerDay(txs, now)
    val runway = Analytics.runwayDays(balance, burn)
    val text = when {
        burn == null || burn <= 0.0 -> stringResource(R.string.home_insight_empty)
        runway != null -> stringResource(
            R.string.home_insight_runway,
            wallet.money(BigDecimal.valueOf(burn.roundToLong())),
            longDate(now.plusSeconds((runway * 86_400).toLong())),
        )
        else -> stringResource(R.string.home_insight_rate, wallet.money(BigDecimal.valueOf(burn.roundToLong())))
    }
    Text(
        text,
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = ScreenPadding, vertical = 16.dp),
        color = Cash.colors.subtle,
        fontSize = 14.sp,
        style = Tabular,
    )
}

// MARK: Feed

private fun LazyListScope.feedTitle(text: Int) {
    item {
        Text(
            stringResource(text),
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(start = ScreenPadding, end = ScreenPadding, top = 32.dp, bottom = 4.dp),
        )
    }
}

/** Free: the last 7 days, grouped by day. */
private fun LazyListScope.weekFeed(app: AppScope, data: AppData, wallet: Wallet, txs: List<CashTransaction>) {
    val today = LocalDate.now(zone)
    val since = today.minusDays(6)
    val recent = txs.filter { !it.timestamp.toLocalDate().isBefore(since) }.sortedByDescending { it.timestamp }
    val older = txs.size - recent.size
    feedTitle(R.string.feed_last_7_days)
    if (recent.isEmpty()) item { EmptyFeed() }
    recent.groupBy { it.timestamp.toLocalDate() }.forEach { (day, list) ->
        item(key = "day-$day") { GroupHeader(dayHeader(LocalContext.current, day, today), null, null) }
        items(list, key = { it.id }) { tx -> FeedRow(app, data, wallet, tx) }
    }
    if (older > 0) {
        item(key = "older") {
            Row(
                Modifier.fillMaxWidth().clickable { app.paywall() }.padding(horizontal = ScreenPadding, vertical = 18.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(stringResource(R.string.feed_older), modifier = Modifier.weight(1f), color = Cash.colors.subtle, fontSize = 14.sp)
                LockIcon()
            }
        }
    }
}

@Composable
private fun EmptyFeed() {
    Text(stringResource(R.string.feed_empty), color = Cash.colors.subtle, modifier = Modifier.padding(horizontal = ScreenPadding, vertical = 8.dp))
}

/** Pro: full history, grouped by ATM cycle with collapsible headers. */
private fun LazyListScope.cycleFeed(app: AppScope, data: AppData, wallet: Wallet, txs: List<CashTransaction>, now: Instant) {
    val cycles = Analytics.cycles(txs, now).reversed()
    feedTitle(R.string.feed_cycles)
    if (cycles.isEmpty()) item { EmptyFeed() }
    cycles.forEachIndexed { index, cycle ->
        val key = cycle.start.toEpochMilli()
        item(key = "cycle-$key") {
            val expanded = CycleState.isExpanded(key, index)
            GroupHeader(
                title = cycleHeader(LocalContext.current, cycle, wallet),
                trailing = "$MINUS${wallet.money(cycle.spent)}",
                expanded = expanded,
                onToggle = { CycleState.toggle(key, index) },
            )
        }
        if (CycleState.isExpanded(key, index)) {
            items(cycle.transactions.sortedByDescending { it.timestamp }, key = { it.id }) { tx -> FeedRow(app, data, wallet, tx, showDay = true) }
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
        Modifier.fillMaxWidth().padding(top = 18.dp)
            .then(if (onToggle != null) Modifier.clickable(onClick = onToggle) else Modifier)
            .padding(horizontal = ScreenPadding, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(title, style = MaterialTheme.typography.labelLarge, color = Cash.colors.subtle, modifier = Modifier.weight(1f))
        if (trailing != null) Text(trailing, style = Tabular, fontSize = 13.sp, color = Cash.colors.subtle)
        if (expanded != null) {
            Icon(
                if (expanded) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore,
                contentDescription = stringResource(if (expanded) R.string.feed_collapse else R.string.feed_expand),
                tint = Cash.colors.subtle,
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FeedRow(app: AppScope, data: AppData, wallet: Wallet, tx: CashTransaction, showDay: Boolean = false) {
    val context = LocalContext.current
    val state = rememberSwipeToDismissBoxState()
    LaunchedEffect(state.currentValue) {
        if (state.currentValue == SwipeToDismissBoxValue.EndToStart) app.deleteWithUndo(tx)
    }
    SwipeToDismissBox(
        state = state,
        enableDismissFromStartToEnd = false,
        modifier = Modifier.animateContentSize(),
        backgroundContent = {
            Box(Modifier.fillMaxSize().background(Cash.colors.over).padding(horizontal = 24.dp), contentAlignment = Alignment.CenterEnd) {
                Icon(Icons.Outlined.Delete, contentDescription = stringResource(R.string.action_delete), tint = Color.White)
            }
        },
    ) {
        Column(Modifier.background(MaterialTheme.colorScheme.background)) {
            Row(
                Modifier.fillMaxWidth().clickable { app.open(Sheet.Edit(tx.id)) }.padding(horizontal = ScreenPadding, vertical = 13.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                val positive = tx.amount.signum() > 0
                Icon(transactionIcon(tx), contentDescription = null, modifier = Modifier.size(20.dp), tint = Cash.colors.subtle)
                Spacer(Modifier.width(16.dp))
                Column(Modifier.weight(1f)) {
                    Text(tx.note ?: kindLabel(context, tx, data.transferPartner(tx)), maxLines = 1, overflow = TextOverflow.Ellipsis, fontSize = 15.sp)
                    val time = timeText(tx.timestamp)
                    Text(if (showDay) "${dayHeader(context, tx.timestamp.toLocalDate())} · $time" else time, fontSize = 12.sp, color = Cash.colors.subtle)
                }
                Text(
                    wallet.signed(tx.amount),
                    style = Tabular,
                    fontSize = 15.sp,
                    color = if (positive) Cash.colors.cashIn else MaterialTheme.colorScheme.onBackground,
                )
            }
            Hairline(Modifier.padding(start = ScreenPadding + 36.dp))
        }
    }
}
