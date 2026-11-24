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
import androidx.compose.material.icons.rounded.AccountBalanceWallet
import androidx.compose.material.icons.rounded.Category
import androidx.compose.material.icons.rounded.Dialpad
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.Insights
import androidx.compose.material.icons.rounded.Widgets
import androidx.compose.material3.Button
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.patternsystems.pocketcash.core.AppData
import com.patternsystems.pocketcash.data.ProBilling
import com.patternsystems.pocketcash.data.isPro
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
            Text("PocketCash Pro", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
            Text("One payment. Yours forever. No subscription, no account.", color = Cash.colors.subtle)
            Feature(Icons.Rounded.Dialpad, "Your own buttons", "Set each button to your real fares and bills.")
            Feature(Icons.Rounded.AccountBalanceWallet, "More wallets", "Coin pouch, emergency stash, a travel wallet in its own currency, and moves between them.")
            Feature(Icons.Rounded.Insights, "ATM cycle insights", "How long each withdrawal lasts, your daily burn and when cash runs out.")
            Feature(Icons.Rounded.Category, "Categories", "Tag spending after the fact with one tap, and see where cash goes.")
            Feature(Icons.Rounded.History, "Full history and CSV export", "Everything beyond 7 days, ready for a spreadsheet.")
            Feature(Icons.Rounded.Widgets, "A wallet per widget", "Each widget can show a different wallet.")
            if (pro) {
                Text("You're Pro. Thank you!", color = Cash.colors.cashIn, fontWeight = FontWeight.SemiBold, fontSize = 18.sp)
            } else {
                Button(
                    onClick = { context.findActivity()?.let { billing.buy(it) } },
                    enabled = price != null,
                    modifier = Modifier.fillMaxWidth().height(56.dp),
                    shape = RoundedCornerShape(18.dp),
                ) { Text(price?.let { "Unlock for $it" } ?: "Connecting to Google Play…", fontSize = 17.sp, fontWeight = FontWeight.SemiBold) }
                message?.let { Text(it, color = Cash.colors.subtle, fontSize = 13.sp) }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    TextButton(onClick = { billing.restore() }) { Text("Restore purchase") }
                    TextButton(onClick = onDismiss) { Text("Not now") }
                }
            }
            Spacer(Modifier.height(8.dp))
        }
    }
}

@Composable
private fun Feature(icon: ImageVector, title: String, text: String) {
    Row(verticalAlignment = Alignment.Top) {
        Box(Modifier.size(36.dp).clip(CircleShape).background(Cash.colors.cashInContainer), contentAlignment = Alignment.Center) {
            Icon(icon, contentDescription = null, tint = Cash.colors.onCashInContainer, modifier = Modifier.size(20.dp))
        }
        Spacer(Modifier.width(12.dp))
        Column {
            Text(title, fontWeight = FontWeight.SemiBold)
            Text(text, color = Cash.colors.subtle, fontSize = 14.sp)
        }
    }
}

/** The activity behind a Compose context (sheets wrap it). */
tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}
