package com.patternsystems.pocketcash.ui.screens

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AccountBalanceWallet
import androidx.compose.material.icons.outlined.Calculate
import androidx.compose.material.icons.outlined.Category
import androidx.compose.material.icons.outlined.Dialpad
import androidx.compose.material.icons.outlined.Email
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Insights
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.NotificationsNone
import androidx.compose.material.icons.outlined.Speed
import androidx.compose.material.icons.outlined.TouchApp
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.patternsystems.pocketcash.R
import com.patternsystems.pocketcash.core.AppData
import com.patternsystems.pocketcash.data.ProBilling
import com.patternsystems.pocketcash.data.isPro
import com.patternsystems.pocketcash.ui.PrimaryButton
import com.patternsystems.pocketcash.ui.theme.Cash
import kotlinx.coroutines.delay

/** PocketCash Pro (docs/spec.md section 5): what it adds, the store's localized price, Buy and Restore. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PaywallSheet(data: AppData, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val billing = remember { ProBilling.get(context) }
    val price by billing.price.collectAsState()
    val message by billing.message.collectAsState()
    val pro = data.isPro

    LaunchedEffect(Unit) { billing.connect() }
    LaunchedEffect(pro) {
        if (pro) {
            delay(1_200)
            onDismiss()
        }
    }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp).navigationBarsPadding(),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Text(stringResource(R.string.pro_name), style = MaterialTheme.typography.headlineMedium)
            Text(stringResource(R.string.paywall_subtitle), color = Cash.colors.subtle)
            // Pro's features in the order of docs/spec.md section 5.
            Feature(Icons.Outlined.Email, R.string.paywall_envelopes, R.string.paywall_envelopes_text)
            Feature(Icons.Outlined.Insights, R.string.paywall_insights, R.string.paywall_insights_text)
            Feature(Icons.Outlined.AccountBalanceWallet, R.string.paywall_wallets, R.string.paywall_wallets_text)
            Feature(Icons.Outlined.TouchApp, R.string.paywall_quick, R.string.paywall_quick_text)
            Feature(Icons.Outlined.Calculate, R.string.paywall_change, R.string.paywall_change_text)
            Feature(Icons.Outlined.Speed, R.string.paywall_budget, R.string.paywall_budget_text)
            Feature(Icons.Outlined.Category, R.string.paywall_categories, R.string.paywall_categories_text)
            Feature(Icons.Outlined.Dialpad, R.string.paywall_buttons, R.string.paywall_buttons_text)
            Feature(Icons.Outlined.Lock, R.string.paywall_lock, R.string.paywall_lock_text)
            Feature(Icons.Outlined.NotificationsNone, R.string.paywall_low_cash, R.string.paywall_low_cash_text)
            Feature(Icons.Outlined.History, R.string.paywall_history, R.string.paywall_history_text)
            if (pro) {
                Text(stringResource(R.string.paywall_thanks), color = Cash.colors.cashIn, fontWeight = FontWeight.SemiBold, fontSize = 18.sp)
            } else {
                PrimaryButton(
                    price?.let { stringResource(R.string.paywall_buy, it) } ?: stringResource(R.string.paywall_connecting),
                    onClick = { context.findActivity()?.let { billing.buy(it) } },
                    enabled = price != null,
                )
                message?.let { Text(it, color = Cash.colors.subtle, fontSize = 13.sp) }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    TextButton(onClick = { billing.restore() }) { Text(stringResource(R.string.paywall_restore)) }
                    TextButton(onClick = onDismiss) { Text(stringResource(R.string.paywall_not_now)) }
                }
            }
            Spacer(Modifier.height(8.dp))
        }
    }
}

@Composable
private fun Feature(icon: ImageVector, title: Int, text: Int) {
    Row(verticalAlignment = Alignment.Top) {
        Icon(icon, contentDescription = null, tint = Cash.colors.subtle, modifier = Modifier.padding(top = 2.dp).size(22.dp))
        Spacer(Modifier.width(16.dp))
        Column {
            Text(stringResource(title), fontWeight = FontWeight.Medium)
            Text(stringResource(text), color = Cash.colors.subtle, fontSize = 14.sp)
        }
    }
}

/** The activity behind a Compose context (sheets wrap it). */
tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}
