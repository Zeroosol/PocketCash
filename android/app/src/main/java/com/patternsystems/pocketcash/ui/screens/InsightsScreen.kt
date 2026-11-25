package com.patternsystems.pocketcash.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.patternsystems.pocketcash.R
import com.patternsystems.pocketcash.core.Analytics
import com.patternsystems.pocketcash.core.AppData
import com.patternsystems.pocketcash.core.Cycle
import com.patternsystems.pocketcash.core.Wallet
import com.patternsystems.pocketcash.ui.EmptyNote
import com.patternsystems.pocketcash.ui.Hairline
import com.patternsystems.pocketcash.ui.ProgressLine
import com.patternsystems.pocketcash.ui.ScreenPadding
import com.patternsystems.pocketcash.ui.SectionTitle
import com.patternsystems.pocketcash.ui.categoryIcon
import com.patternsystems.pocketcash.ui.categoryLabelRes
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
@Composable
fun InsightsScreen(data: AppData) {
    val wallet = data.selectedWallet(true) ?: return
    val txs = data.transactionsOf(wallet.id)
    val now = Instant.now()
    val balance = Analytics.balance(txs)
    val burn = Analytics.burnRatePerDay(txs, now)
    val runway = Analytics.runwayDays(balance, burn)
    val cycles = Analytics.cycles(txs, now).reversed()
    val categories = Analytics.spendingByTag(txs, now.toEpochMilli() - 30 * 86_400_000L, now)
    val categoryTotal = categories.fold(BigDecimal.ZERO) { s, (_, v) -> s + v }

    DestinationPage(title = stringResource(R.string.nav_insights), subtitle = wallet.name) {
        item {
            Column(Modifier.padding(horizontal = ScreenPadding, vertical = 8.dp)) {
                Text(stringResource(R.string.insights_burn_rate), color = Cash.colors.subtle)
                if (burn == null || burn <= 0.0) {
                    Text(stringResource(R.string.insights_not_enough), style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(top = 4.dp))
                } else {
                    Text(stringResource(R.string.insights_per_day, wallet.money(BigDecimal.valueOf(burn.roundToLong()))), style = HeroStyle.copy(fontSize = 40.sp))
                    Text(
                        if (runway != null) {
                            stringResource(R.string.insights_runway, wallet.money(balance), oneDecimal(runway), longDate(now.plusSeconds((runway * 86_400).toLong())))
                        } else {
                            stringResource(R.string.insights_average)
                        },
                        color = Cash.colors.subtle,
                    )
                }
            }
        }

        item { SectionTitle(stringResource(R.string.feed_cycles)) }
        if (cycles.isEmpty()) item { EmptyNote(stringResource(R.string.insights_no_cycles)) }
        items(cycles, key = { it.start.toEpochMilli() }) { cycle -> CycleRow(wallet, cycle) }

        item { SectionTitle(stringResource(R.string.insights_categories)) }
        if (categories.isEmpty()) item { EmptyNote(stringResource(R.string.insights_no_spending)) }
        items(categories, key = { it.first ?: "untagged" }) { (tag, amount) ->
            CategoryBar(wallet, tag, amount, categoryTotal)
        }
    }
}

@Composable
private fun CycleRow(wallet: Wallet, cycle: Cycle) {
    Column(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(horizontal = ScreenPadding, vertical = 14.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            val start = shortDate(cycle.start.toEpochMilli())
            val days = oneDecimal(cycle.days)
            val title = when {
                !cycle.isATM -> stringResource(R.string.cycle_before_first_atm)
                cycle.isOpen -> stringResource(R.string.insights_cycle_open, start)
                else -> stringResource(R.string.insights_cycle_range, start, shortDate(cycle.end!!.toEpochMilli()))
            }
            Text(title, fontSize = 15.sp)
            Text(
                when {
                    !cycle.isATM && cycle.isOpen -> stringResource(R.string.insights_days_so_far, days)
                    !cycle.isATM -> stringResource(R.string.insights_lasted, days)
                    cycle.isOpen -> stringResource(R.string.insights_withdrawn_so_far, wallet.money(cycle.withdrawn), days)
                    else -> stringResource(R.string.insights_withdrawn_lasted, wallet.money(cycle.withdrawn), days)
                },
                style = Tabular,
                color = Cash.colors.subtle,
                fontSize = 14.sp,
            )
            Row(Modifier.padding(top = 6.dp)) {
                Stat(stringResource(R.string.insights_spent), wallet.money(cycle.spent), Modifier.weight(1f))
                Stat(stringResource(R.string.insights_per_day_label), wallet.money(BigDecimal.valueOf(cycle.avgDaily.roundToLong())), Modifier.weight(1f))
            }
            cycle.topAmount?.let { top ->
                Text(
                    stringResource(R.string.insights_top_amount, wallet.money(top), cycle.topAmountCount, wallet.money(cycle.topAmountTotal)),
                    fontSize = 13.sp,
                    color = Cash.colors.subtle,
                )
            }
            cycle.topTag?.let { Text(stringResource(R.string.insights_top_tag, stringResource(categoryLabelRes(it))), fontSize = 13.sp, color = Cash.colors.subtle) }
        }
        Hairline(Modifier.padding(start = ScreenPadding))
    }
}

@Composable
private fun Stat(label: String, value: String, modifier: Modifier) {
    Column(modifier) {
        Text(label, fontSize = 12.sp, color = Cash.colors.subtle)
        Text(value, style = Tabular)
    }
}

@Composable
private fun CategoryBar(wallet: Wallet, tag: String?, amount: BigDecimal, total: BigDecimal) {
    val share = if (total.signum() > 0) (amount.toDouble() / total.toDouble()).toFloat() else 0f
    Row(Modifier.fillMaxWidth().padding(horizontal = ScreenPadding, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(categoryIcon(tag), contentDescription = null, modifier = Modifier.size(20.dp), tint = Cash.colors.subtle)
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row {
                Text(stringResource(categoryLabelRes(tag)), modifier = Modifier.weight(1f))
                Text(wallet.money(amount), style = Tabular, color = Cash.colors.subtle)
            }
            ProgressLine(share)
        }
    }
}
