package com.patternsystems.pocketcash.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.patternsystems.pocketcash.core.Analytics
import com.patternsystems.pocketcash.core.AppData
import com.patternsystems.pocketcash.core.Categories
import com.patternsystems.pocketcash.core.Cycle
import com.patternsystems.pocketcash.core.Wallet
import com.patternsystems.pocketcash.data.isPro
import com.patternsystems.pocketcash.ui.CashCard
import com.patternsystems.pocketcash.ui.EmptyNote
import com.patternsystems.pocketcash.ui.SectionTitle
import com.patternsystems.pocketcash.ui.categoryIcon
import com.patternsystems.pocketcash.ui.longDate
import com.patternsystems.pocketcash.ui.money
import com.patternsystems.pocketcash.ui.oneDecimal
import com.patternsystems.pocketcash.ui.shortDate
import com.patternsystems.pocketcash.ui.theme.Cash
import com.patternsystems.pocketcash.ui.theme.HeroStyle
import com.patternsystems.pocketcash.ui.theme.Tabular
import java.math.BigDecimal
import java.time.Instant
import kotlin.math.roundToLong

/** Insights (Pro, docs/spec.md section 7): burn rate and runway, ATM cycles, spending by category. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun InsightsScreen(data: AppData, onBack: () -> Unit) {
    val wallet = data.selectedWallet(data.isPro) ?: return
    val txs = data.transactionsOf(wallet.id)
    val now = Instant.now()
    val balance = Analytics.balance(txs)
    val burn = Analytics.burnRatePerDay(txs, now)
    val runway = Analytics.runwayDays(balance, burn)
    val cycles = Analytics.cycles(txs, now).reversed()
    val categories = Analytics.spendingByTag(txs, now.toEpochMilli() - 30 * 86_400_000L, now)
    val categoryTotal = categories.fold(BigDecimal.ZERO) { s, (_, v) -> s + v }

    Column(Modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text("Insights · ${wallet.name}") },
            navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Back") } },
            colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
        )
        LazyColumn(contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 48.dp)) {
            item {
                CashCard {
                    Column(Modifier.padding(20.dp)) {
                        Text("Burn rate", color = Cash.colors.subtle)
                        if (burn == null || burn <= 0.0) {
                            Text("Not enough spending yet", style = MaterialTheme.typography.titleLarge)
                        } else {
                            Text("${wallet.money(BigDecimal.valueOf(burn.roundToLong()))}/day", style = HeroStyle.copy(fontSize = 36.sp))
                            Text(
                                if (runway != null) "At this pace your ${wallet.money(balance)} lasts ${oneDecimal(runway)} days, until about ${longDate(now.plusSeconds((runway * 86_400).toLong()))}."
                                else "Average over the last 14 days.",
                                color = Cash.colors.subtle,
                            )
                        }
                    }
                }
            }

            item { SectionTitle("ATM cycles") }
            if (cycles.isEmpty()) item { EmptyNote("Your first ATM withdrawal starts a cycle.") }
            items(cycles, key = { it.start.toEpochMilli() }) { cycle -> CycleCard(wallet, cycle) }

            item { SectionTitle("Last 30 days by category") }
            if (categories.isEmpty()) item { EmptyNote("No spending in the last 30 days.") }
            items(categories, key = { it.first ?: "untagged" }) { (tag, amount) ->
                CategoryBar(wallet, tag, amount, categoryTotal)
            }
        }
    }
}

@Composable
private fun CycleCard(wallet: Wallet, cycle: Cycle) {
    CashCard(Modifier.padding(bottom = 10.dp)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            val title = when {
                !cycle.isATM -> "Before first ATM"
                cycle.isOpen -> "Current cycle · since ${shortDate(cycle.start.toEpochMilli())}"
                else -> "${shortDate(cycle.start.toEpochMilli())} – ${shortDate(cycle.end!!.toEpochMilli())}"
            }
            Text(title, fontWeight = FontWeight.SemiBold)
            val lasted = if (cycle.isOpen) "${oneDecimal(cycle.days)} days so far" else "lasted ${oneDecimal(cycle.days)} days"
            Text(
                if (cycle.isATM) "${wallet.money(cycle.withdrawn)} $lasted" else lasted.replaceFirstChar { it.uppercase() },
                style = Tabular,
            )
            Row {
                Stat("Spent", wallet.money(cycle.spent), Modifier.weight(1f))
                Stat("Per day", wallet.money(BigDecimal.valueOf(cycle.avgDaily.roundToLong())), Modifier.weight(1f))
            }
            cycle.topAmount?.let { top ->
                Text(
                    "Drained most by ${wallet.money(top)} × ${cycle.topAmountCount} (${wallet.money(cycle.topAmountTotal)})",
                    fontSize = 13.sp,
                    color = Cash.colors.subtle,
                )
            }
            cycle.topTag?.let { Text("Mostly ${Categories.label(it)}", fontSize = 13.sp, color = Cash.colors.subtle) }
        }
    }
}

@Composable
private fun Stat(label: String, value: String, modifier: Modifier) {
    Column(modifier.padding(top = 6.dp)) {
        Text(label, fontSize = 12.sp, color = Cash.colors.subtle)
        Text(value, style = Tabular, fontWeight = FontWeight.Medium)
    }
}

@Composable
private fun CategoryBar(wallet: Wallet, tag: String?, amount: BigDecimal, total: BigDecimal) {
    val share = if (total.signum() > 0) (amount.toDouble() / total.toDouble()).toFloat().coerceIn(0.02f, 1f) else 0f
    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(categoryIcon(tag), contentDescription = null, modifier = Modifier.size(20.dp), tint = Cash.colors.subtle)
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Row {
                Text(Categories.label(tag), modifier = Modifier.weight(1f))
                Text(wallet.money(amount), style = Tabular, color = Cash.colors.subtle)
            }
            Box(Modifier.fillMaxWidth().height(8.dp).padding(top = 2.dp).clip(RoundedCornerShape(4.dp)).background(Cash.colors.spendContainer)) {
                Box(Modifier.fillMaxWidth(share).fillMaxHeight().clip(RoundedCornerShape(4.dp)).background(Cash.colors.cashIn))
            }
        }
    }
}
