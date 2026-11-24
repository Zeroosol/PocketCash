package com.patternsystems.pocketcash.widget

import android.app.Activity
import android.appwidget.AppWidgetManager
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.glance.appwidget.GlanceAppWidgetManager
import androidx.glance.appwidget.state.updateAppWidgetState
import androidx.lifecycle.lifecycleScope
import com.patternsystems.pocketcash.core.MoneyFormat
import com.patternsystems.pocketcash.data.CashStore
import com.patternsystems.pocketcash.data.isPro
import com.patternsystems.pocketcash.ui.theme.PocketCashTheme
import kotlinx.coroutines.launch

/**
 * Picks which wallet a widget shows (Pro). With Free or a single wallet there's nothing to pick, so it finishes at once
 * and the widget shows the default wallet. On Android 12+ adding a widget skips this screen (configuration_optional);
 * long-press the widget and choose its settings to change the wallet.
 */
class WidgetConfigActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        val widgetId = intent?.extras?.getInt(AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID)
            ?: AppWidgetManager.INVALID_APPWIDGET_ID
        val result = Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, widgetId)
        setResult(Activity.RESULT_CANCELED, result)
        if (widgetId == AppWidgetManager.INVALID_APPWIDGET_ID) {
            finish()
            return
        }
        val store = CashStore.get(this)
        val data = store.data.value
        if (!data.isPro || data.wallets.size < 2) {
            setResult(Activity.RESULT_OK, result)
            finish()
            return
        }
        setContent {
            PocketCashTheme {
                Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    Column(Modifier.safeDrawingPadding()) {
                        Text(
                            "Which wallet should this widget show?",
                            style = MaterialTheme.typography.titleLarge,
                            modifier = Modifier.padding(20.dp),
                        )
                        data.sortedWallets.forEach { wallet ->
                            ListItem(
                                headlineContent = { Text(wallet.name) },
                                supportingContent = {
                                    Text(MoneyFormat.full(data.balanceOf(wallet.id), wallet.currencyCode, wallet.symbol))
                                },
                                modifier = Modifier.clickable {
                                    lifecycleScope.launch {
                                        val glanceId = GlanceAppWidgetManager(this@WidgetConfigActivity).getGlanceIdBy(widgetId)
                                        updateAppWidgetState(this@WidgetConfigActivity, glanceId) { it[CashWidget.WALLET_KEY] = wallet.id }
                                        Widgets.refresh(this@WidgetConfigActivity)
                                        setResult(Activity.RESULT_OK, result)
                                        finish()
                                    }
                                },
                            )
                            HorizontalDivider()
                        }
                    }
                }
            }
        }
    }
}
