package com.patternsystems.pocketcash.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.ui.Alignment
import androidx.compose.ui.unit.dp
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import com.patternsystems.pocketcash.DeepLink
import com.patternsystems.pocketcash.core.AppData
import com.patternsystems.pocketcash.core.CashTransaction
import com.patternsystems.pocketcash.core.Kind
import com.patternsystems.pocketcash.data.CashStore
import com.patternsystems.pocketcash.data.isPro
import com.patternsystems.pocketcash.ui.confirmHaptic
import com.patternsystems.pocketcash.ui.signed
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

private enum class Route { HOME, SETTINGS, INSIGHTS }

/** What the screens can ask the root to open. */
sealed interface Sheet {
    data class Keypad(val add: Boolean) : Sheet
    data object Count : Sheet
    data class Edit(val transactionId: String) : Sheet
    data class WalletEditor(val walletId: String?) : Sheet
    data object Transfer : Sheet
    data object Presets : Sheet
}

/** Shared actions and state handed to every screen. */
class AppScope(
    val store: CashStore,
    val scope: CoroutineScope,
    val snackbar: SnackbarHostState,
    val open: (Sheet) -> Unit,
    val paywall: () -> Unit,
    val haptic: () -> Unit,
) {
    /** "−₱50 spent · Undo" for 5 seconds after every add (docs/spec.md section 7). */
    fun announce(tx: CashTransaction, message: String) {
        haptic()
        scope.launch {
            snackbar.currentSnackbarData?.dismiss()
            val result = withTimeoutOrNull(5_000) { snackbar.showSnackbar(message, actionLabel = "Undo", duration = SnackbarDuration.Indefinite) }
            if (result == SnackbarResult.ActionPerformed) store.delete(tx.id)
        }
    }

    /** Deletes with an Undo that puts it back. */
    fun deleteWithUndo(tx: CashTransaction) {
        scope.launch {
            val removed = store.delete(tx.id)
            snackbar.currentSnackbarData?.dismiss()
            val result = withTimeoutOrNull(5_000) { snackbar.showSnackbar("Deleted", actionLabel = "Undo", duration = SnackbarDuration.Indefinite) }
            if (result == SnackbarResult.ActionPerformed) store.restore(removed)
        }
    }
}

fun addedMessage(tx: CashTransaction, signedAmount: String): String = when (tx.kind) {
    Kind.SPEND -> "$signedAmount spent"
    Kind.ATM -> "$signedAmount from the ATM"
    Kind.ADJUST -> "Balance updated · $signedAmount"
    else -> "$signedAmount added"
}

@Composable
fun PocketCashRoot(data: AppData, link: DeepLink?, onLinkHandled: () -> Unit) {
    val context = LocalContext.current
    val view = LocalView.current
    val store = remember { CashStore.get(context) }
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    var route by rememberSaveable { mutableStateOf(Route.HOME) }
    var sheet by remember { mutableStateOf<Sheet?>(null) }
    var paywall by rememberSaveable { mutableStateOf(false) }
    val pro = data.isPro

    val app = remember(store, scope, snackbar) {
        AppScope(
            store = store,
            scope = scope,
            snackbar = snackbar,
            open = { sheet = it },
            paywall = { paywall = true },
            haptic = { view.confirmHaptic(store.data.value.settings.haptics) },
        )
    }

    if (!data.settings.onboarded || data.wallets.isEmpty()) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            OnboardingScreen(app, data)
        }
        return
    }

    // pocketcash:// links from shortcuts, notifications and widgets.
    LaunchedEffect(link) {
        when (link) {
            null -> return@LaunchedEffect
            is DeepLink.Keypad -> { route = Route.HOME; sheet = Sheet.Keypad(link.add) }
            DeepLink.Count -> { route = Route.HOME; sheet = Sheet.Count }
            DeepLink.Pro -> paywall = true
            is DeepLink.Wallet -> {
                route = Route.HOME
                if (pro && data.wallet(link.id) != null) store.updateSettings { it.copy(selectedWalletId = link.id) }
            }
        }
        onLinkHandled()
    }

    // One soft paywall nudge on the third day of use (docs/spec.md section 5).
    LaunchedEffect(Unit) {
        val first = data.settings.firstOpenAt
        if (first == null) {
            store.updateSettings { it.copy(firstOpenAt = System.currentTimeMillis()) }
        } else if (!pro && !data.settings.paywallNudgeShown && System.currentTimeMillis() - first >= 2 * 86_400_000L) {
            store.updateSettings { it.copy(paywallNudgeShown = true) }
            paywall = true
        }
    }

    BackHandler(enabled = route != Route.HOME) { route = Route.HOME }

    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Box(Modifier.fillMaxSize()) {
            AnimatedContent(targetState = route, transitionSpec = { fadeIn() togetherWith fadeOut() }, label = "route") { r ->
                when (r) {
                    Route.HOME -> HomeScreen(
                        app = app,
                        data = data,
                        onSettings = { route = Route.SETTINGS },
                        onInsights = { if (pro) route = Route.INSIGHTS else paywall = true },
                    )
                    Route.SETTINGS -> SettingsScreen(app = app, data = data, onBack = { route = Route.HOME })
                    Route.INSIGHTS -> InsightsScreen(data = data, onBack = { route = Route.HOME })
                }
            }
            SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(12.dp))
        }
    }

    val wallet = data.selectedWallet(pro)
    when (val s = sheet) {
        null -> Unit
        is Sheet.Keypad -> if (wallet != null) KeypadSheet(
            wallet = wallet,
            isPro = pro,
            startAdd = s.add,
            fromAtmDefault = data.settings.plusButtonsAreATM,
            onDismiss = { sheet = null },
            onPaywall = { sheet = null; paywall = true },
            onSave = { magnitude, kind, tag, note ->
                sheet = null
                scope.launch {
                    store.add(wallet.id, magnitude, kind, tag, note)?.let { app.announce(it, addedMessage(it, wallet.signed(it.amount))) }
                }
            },
        )
        Sheet.Count -> if (wallet != null) CountSheet(
            wallet = wallet,
            balance = data.balanceOf(wallet.id),
            onDismiss = { sheet = null },
            onSave = { actual ->
                sheet = null
                scope.launch {
                    store.reconcile(wallet.id, actual)?.let { app.announce(it, addedMessage(it, wallet.signed(it.amount))) }
                }
            },
        )
        is Sheet.Edit -> {
            val tx = data.transactions.firstOrNull { it.id == s.transactionId }
            val txWallet = tx?.let { data.wallet(it.walletId) }
            if (tx != null && txWallet != null) EditTransactionSheet(
                tx = tx,
                wallet = txWallet,
                isPro = pro,
                onDismiss = { sheet = null },
                onPaywall = { sheet = null; paywall = true },
                onSave = { updated -> sheet = null; scope.launch { store.updateTransaction(updated) } },
                onDelete = { sheet = null; app.deleteWithUndo(tx) },
            ) else LaunchedEffect(s) { sheet = null }
        }
        is Sheet.WalletEditor -> WalletEditorSheet(
            app = app,
            data = data,
            wallet = data.wallet(s.walletId),
            onDismiss = { sheet = null },
        )
        Sheet.Transfer -> TransferSheet(app = app, data = data, onDismiss = { sheet = null })
        Sheet.Presets -> if (wallet != null) PresetEditorSheet(app = app, wallet = wallet, onDismiss = { sheet = null })
    }

    if (paywall) PaywallSheet(data = data, onDismiss = { paywall = false })
}
