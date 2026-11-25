package com.patternsystems.pocketcash.widget

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.LocalContext
import androidx.glance.LocalSize
import androidx.glance.action.ActionParameters
import androidx.glance.action.actionParametersOf
import androidx.glance.action.actionStartActivity
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.SizeMode
import androidx.glance.appwidget.action.actionRunCallback
import androidx.glance.appwidget.appWidgetBackground
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.provideContent
import androidx.glance.background
import androidx.glance.color.ColorProvider
import androidx.glance.currentState
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.width
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextAlign
import androidx.glance.text.TextStyle
import com.patternsystems.pocketcash.MainActivity
import com.patternsystems.pocketcash.R
import com.patternsystems.pocketcash.core.Analytics
import com.patternsystems.pocketcash.core.AppData
import com.patternsystems.pocketcash.core.MINUS
import com.patternsystems.pocketcash.core.MoneyFormat
import com.patternsystems.pocketcash.core.Wallet
import com.patternsystems.pocketcash.data.CashStore
import com.patternsystems.pocketcash.data.budgetOf
import com.patternsystems.pocketcash.data.isPro
import com.patternsystems.pocketcash.data.privacyOn
import java.math.BigDecimal
import java.time.Instant
import java.time.ZoneId

/**
 * The Home Screen widget (docs/spec.md section 8). One responsive layout: 2×2 shows the balance and two spend
 * buttons, 4×2 and wider adds the wallet name, today's spending (or what's left of the daily budget) and a + button.
 * Buttons write through [CashStore], which redraws every widget after the save.
 */
abstract class CashWidget : GlanceAppWidget() {
    override val sizeMode = SizeMode.Responsive(setOf(SMALL, MEDIUM))

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val store = CashStore.get(context)
        provideContent {
            val data by store.data.collectAsState()
            val walletId = currentState<Preferences>()[WALLET_KEY]
            WidgetBody(data, walletId)
        }
    }

    companion object {
        val SMALL = DpSize(110.dp, 110.dp)
        val MEDIUM = DpSize(250.dp, 110.dp)
        /** The wallet this widget shows (Pro, set by WidgetConfigActivity). */
        val WALLET_KEY = stringPreferencesKey("walletId")
    }
}

class SmallCashWidget : CashWidget()
class MediumCashWidget : CashWidget()

class SmallWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = SmallCashWidget()
}

class MediumWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = MediumCashWidget()
}

internal val WalletKey = ActionParameters.Key<String>("walletId")
internal val AmountKey = ActionParameters.Key<String>("amount")
internal val SignKey = ActionParameters.Key<String>("sign")
internal val TransactionKey = ActionParameters.Key<String>("transactionId")

private object W {
    val background = ColorProvider(day = Color(0xFFFAFAF9), night = Color(0xFF131414))
    val text = ColorProvider(day = Color(0xFF151617), night = Color(0xFFF1F1F1))
    val secondary = ColorProvider(day = Color(0xFF6B6F76), night = Color(0xFF9A9EA4))
    val over = ColorProvider(day = Color(0xFFDC2626), night = Color(0xFFF87171))
    val cashIn = ColorProvider(day = Color(0xFFE3F3EC), night = Color(0xFF10291F))
    val onCashIn = ColorProvider(day = Color(0xFF065F46), night = Color(0xFFA7F3D0))
    val spend = ColorProvider(day = Color(0xFFF0F0EF), night = Color(0xFF212222))
    val chip = ColorProvider(day = Color(0xFF151617), night = Color(0xFFF1F1F1))
    val onChip = ColorProvider(day = Color(0xFFFFFFFF), night = Color(0xFF151617))
}

private const val HIDDEN = "••••"

@Composable
private fun WidgetBody(data: AppData, walletId: String?) {
    val pro = data.isPro
    val wallet = (if (pro) data.wallet(walletId) else null) ?: data.defaultWallet
    val wide = LocalSize.current.width >= 200.dp
    Column(
        modifier = GlanceModifier.fillMaxSize().appWidgetBackground().cornerRadius(22.dp).background(W.background)
            .padding(horizontal = 12.dp, vertical = 10.dp),
    ) {
        if (wallet == null) {
            Box(GlanceModifier.fillMaxSize().clickable(actionStartActivity<MainActivity>()), contentAlignment = Alignment.Center) {
                Text(LocalContext.current.getString(R.string.widget_setup), style = TextStyle(color = W.secondary, fontSize = 13.sp, textAlign = TextAlign.Center))
            }
            return@Column
        }
        val txs = data.transactionsOf(wallet.id)
        val now = Instant.now()
        val balance = Analytics.balance(txs)
        val today = Analytics.todaySpent(txs, now, ZoneId.systemDefault())
        val (add, spend) = data.presetsOf(wallet, pro)
        val privacy = data.privacyOn
        val budget = data.budgetOf(wallet)
        val undo = data.widgetUndo?.takeIf { it.walletId == wallet.id && now.toEpochMilli() - it.at < 120_000 }

        // Top row: the Undo chip right after a tap, else the wallet name and today's spending.
        Row(GlanceModifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            if (undo != null) {
                Box(
                    GlanceModifier.cornerRadius(12.dp).background(W.chip).padding(horizontal = 10.dp, vertical = 3.dp)
                        .clickable(actionRunCallback<UndoAction>(actionParametersOf(TransactionKey to undo.transactionId))),
                ) {
                    val amount = if (privacy) HIDDEN else MoneyFormat.signed(undo.amount, wallet.currencyCode, wallet.symbol)
                    Text(LocalContext.current.getString(R.string.widget_undo, amount), style = TextStyle(color = W.onChip, fontSize = 12.sp, fontWeight = FontWeight.Medium), maxLines = 1)
                }
            } else {
                Text(
                    wallet.name,
                    modifier = GlanceModifier.defaultWeight(),
                    style = TextStyle(color = W.secondary, fontSize = 12.sp, fontWeight = FontWeight.Medium),
                    maxLines = 1,
                )
                if (wide) TodayLabel(wallet, today, budget, privacy)
            }
        }

        Spacer(GlanceModifier.defaultWeight())
        Text(
            if (privacy) HIDDEN else MoneyFormat.full(balance, wallet.currencyCode, wallet.symbol),
            modifier = GlanceModifier.fillMaxWidth().clickable(actionStartActivity<MainActivity>()),
            style = TextStyle(
                color = if (balance.signum() < 0) W.over else W.text,
                fontSize = balanceSize(wide, balance, wallet),
                fontWeight = FontWeight.Medium,
            ),
            maxLines = 1,
        )
        Spacer(GlanceModifier.defaultWeight())

        // Buttons: two spends on the small widget; + and three spends when wide (docs/spec.md section 8).
        val buttons = if (wide) {
            listOfNotNull(add.firstOrNull()?.let { it to true }) + spend.take(3).map { it to false }
        } else {
            spend.take(2).map { it to false }
        }
        Row(GlanceModifier.fillMaxWidth()) {
            buttons.forEachIndexed { i, (amount, positive) ->
                if (i > 0) Spacer(GlanceModifier.width(6.dp))
                CashButton(wallet, amount, positive)
            }
        }
    }
}

@Composable
private fun TodayLabel(wallet: Wallet, today: BigDecimal, budget: BigDecimal?, privacy: Boolean) {
    val context = LocalContext.current
    fun money(v: BigDecimal) = MoneyFormat.full(v, wallet.currencyCode, wallet.symbol)
    // Free shows today's spending; a daily budget (Pro) shows what's left instead (docs/spec.md section 8).
    val (text, color) = when {
        privacy -> HIDDEN to W.secondary
        budget != null -> {
            val left = budget - today
            if (left.signum() >= 0) context.getString(R.string.widget_left, money(left)) to W.secondary
            else context.getString(R.string.home_over_by, money(left.negate())) to W.over
        }
        today.signum() == 0 -> context.getString(R.string.home_nothing_today) to W.secondary
        else -> context.getString(R.string.widget_today, "$MINUS${money(today)}") to W.secondary
    }
    Text(text, style = TextStyle(color = color, fontSize = 12.sp, fontWeight = FontWeight.Medium, textAlign = TextAlign.End), maxLines = 1)
}

@Composable
private fun androidx.glance.layout.RowScope.CashButton(wallet: Wallet, amount: BigDecimal, positive: Boolean) {
    val params = actionParametersOf(
        WalletKey to wallet.id,
        AmountKey to amount.toPlainString(),
        SignKey to if (positive) "+" else "-",
    )
    Box(
        modifier = GlanceModifier.defaultWeight().height(40.dp).cornerRadius(14.dp)
            .background(if (positive) W.cashIn else W.spend)
            .clickable(actionRunCallback<AdjustCashAction>(params)),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            MoneyFormat.compact(amount, if (positive) '+' else MINUS),
            style = TextStyle(color = if (positive) W.onCashIn else W.text, fontSize = 15.sp, fontWeight = FontWeight.Medium),
            maxLines = 1,
        )
    }
}

private fun balanceSize(wide: Boolean, balance: BigDecimal, wallet: Wallet): TextUnit {
    val length = MoneyFormat.full(balance, wallet.currencyCode, wallet.symbol).length
    val base = if (wide) 32 else 26
    return when {
        length > 12 -> (base - 10).sp
        length > 9 -> (base - 5).sp
        else -> base.sp
    }
}
