package com.patternsystems.pocketcash.ui.screens

import android.content.Context
import androidx.activity.compose.BackHandler
import androidx.annotation.StringRes
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.patternsystems.pocketcash.DeepLink
import com.patternsystems.pocketcash.R
import com.patternsystems.pocketcash.core.AppData
import com.patternsystems.pocketcash.core.CashTransaction
import com.patternsystems.pocketcash.data.CashStore
import com.patternsystems.pocketcash.data.isPro
import com.patternsystems.pocketcash.ui.addedMessage
import com.patternsystems.pocketcash.ui.confirmHaptic
import com.patternsystems.pocketcash.ui.signed
import com.patternsystems.pocketcash.ui.tickHaptic
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/** What the screens can ask the root to open. Every sheet hides the nav bar while it's open. */
sealed interface Sheet {
    data class Keypad(val add: Boolean) : Sheet
    data object Count : Sheet
    data class Edit(val transactionId: String) : Sheet
    data class WalletEditor(val walletId: String?) : Sheet
    data object Transfer : Sheet
    data object Presets : Sheet
    data object Fill : Sheet
}

/** Shared actions and state handed to every screen. */
class AppScope(
    val store: CashStore,
    val scope: CoroutineScope,
    val snackbar: SnackbarHostState,
    /** The activity, for strings in the app's language. */
    val context: Context,
    val open: (Sheet) -> Unit,
    val paywall: () -> Unit,
    val haptic: () -> Unit,
    val navigate: (Destination) -> Unit,
) {
    fun str(@StringRes id: Int, vararg args: Any): String = context.getString(id, *args)

    /** "−₱50 spent · Undo" for 5 seconds after every add (docs/spec.md section 7). */
    fun announce(tx: CashTransaction, message: String) {
        haptic()
        scope.launch {
            snackbar.currentSnackbarData?.dismiss()
            val result = withTimeoutOrNull(5_000) { snackbar.showSnackbar(message, actionLabel = str(R.string.action_undo), duration = SnackbarDuration.Indefinite) }
            if (result == SnackbarResult.ActionPerformed) store.delete(tx.id)
        }
    }

    /** A short note at the bottom ("Backup saved"). */
    fun toast(message: String) {
        scope.launch {
            snackbar.currentSnackbarData?.dismiss()
            snackbar.showSnackbar(message, duration = SnackbarDuration.Short)
        }
    }

    /** Deletes with an Undo that puts it back. */
    fun deleteWithUndo(tx: CashTransaction) {
        scope.launch {
            val removed = store.delete(tx.id)
            snackbar.currentSnackbarData?.dismiss()
            val result = withTimeoutOrNull(5_000) {
                snackbar.showSnackbar(str(R.string.deleted), actionLabel = str(R.string.action_undo), duration = SnackbarDuration.Indefinite)
            }
            if (result == SnackbarResult.ActionPerformed) store.restore(removed)
        }
    }
}

@Composable
fun PocketCashRoot(data: AppData, link: DeepLink?, onLinkHandled: () -> Unit) {
    val context = LocalContext.current
    val view = LocalView.current
    val store = remember { CashStore.get(context) }
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    var destination by rememberSaveable { mutableStateOf(Destination.HOME) }
    var sheet by remember { mutableStateOf<Sheet?>(null) }
    var paywall by rememberSaveable { mutableStateOf(false) }
    val bar = rememberBarScrollState()
    val pro = data.isPro

    val app = remember(store, scope, snackbar, context) {
        AppScope(
            store = store,
            scope = scope,
            snackbar = snackbar,
            context = context,
            open = { sheet = it },
            paywall = { paywall = true },
            haptic = { view.confirmHaptic(store.data.value.settings.haptics) },
            navigate = { d ->
                if (d != destination) view.tickHaptic(store.data.value.settings.haptics)
                destination = d
                bar.expanded = true
            },
        )
    }

    if (!data.settings.onboarded || data.wallets.isEmpty()) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            OnboardingScreen(app, data)
        }
        if (paywall) PaywallSheet(data = data, onDismiss = { paywall = false })
        return
    }

    // pocketcash:// links from shortcuts, notifications, widgets and the tile.
    LaunchedEffect(link) {
        when (link) {
            null -> return@LaunchedEffect
            is DeepLink.Keypad -> sheet = Sheet.Keypad(link.add)
            DeepLink.Count -> sheet = Sheet.Count
            DeepLink.Pro -> paywall = true
            is DeepLink.Wallet -> {
                destination = Destination.HOME
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

    BackHandler(enabled = destination != Destination.HOME) { app.navigate(Destination.HOME) }

    val barVisible = sheet == null && !paywall
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Box(Modifier.fillMaxSize()) {
            Box(Modifier.fillMaxSize().nestedScroll(bar.connection)) {
                AnimatedContent(targetState = destination, transitionSpec = { fadeIn() togetherWith fadeOut() }, label = "destination") { d ->
                    when (d) {
                        Destination.HOME -> HomeScreen(app = app, data = data)
                        Destination.INSIGHTS -> if (pro) {
                            InsightsScreen(data = data)
                        } else {
                            ProPreview(stringResource(R.string.insights_locked_title), stringResource(R.string.insights_locked_text), app.paywall) {
                                InsightsScreen(data = data)
                            }
                        }
                        Destination.ENVELOPES -> if (pro) {
                            EnvelopesScreen(app = app, data = data)
                        } else {
                            ProPreview(stringResource(R.string.envelopes_locked_title), stringResource(R.string.envelopes_locked_text), app.paywall) {
                                EnvelopesScreen(app = app, data = data)
                            }
                        }
                        Destination.SETTINGS -> SettingsScreen(app = app, data = data)
                    }
                }
            }
            val snackbarLift by animateDpAsState(if (barVisible) 84.dp else 12.dp, label = "snackbar")
            SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(start = 12.dp, end = 12.dp, bottom = snackbarLift))
            AnimatedVisibility(
                visible = barVisible,
                modifier = Modifier.align(Alignment.BottomCenter),
                enter = fadeIn() + slideInVertically { it },
                exit = fadeOut() + slideOutVertically { it },
            ) {
                BottomBar(
                    selected = destination,
                    expanded = bar.expanded,
                    onSelect = { app.navigate(it) },
                    onExpand = { bar.expanded = true },
                    onPlus = { sheet = Sheet.Keypad(add = false) },
                    onPlusLong = { app.haptic(); sheet = Sheet.Count },
                )
            }
        }
    }

    val wallet = data.selectedWallet(pro)
    when (val s = sheet) {
        null -> Unit
        is Sheet.Keypad -> if (wallet != null) KeypadSheet(
            wallet = wallet,
            isPro = pro,
            spendPresets = data.presetsOf(wallet, pro).second,
            startAdd = s.add,
            fromAtmDefault = data.settings.plusButtonsAreATM,
            onDismiss = { sheet = null },
            onPaywall = { sheet = null; paywall = true },
            onSave = { magnitude, kind, tag, note ->
                sheet = null
                scope.launch {
                    store.add(wallet.id, magnitude, kind, tag, note)?.let { app.announce(it, addedMessage(context, it, wallet.signed(it.amount))) }
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
                    store.reconcile(wallet.id, actual)?.let { app.announce(it, addedMessage(context, it, wallet.signed(it.amount))) }
                }
            },
        )
        is Sheet.Edit -> {
            val tx = data.transactions.firstOrNull { it.id == s.transactionId }
            val txWallet = tx?.let { data.wallet(it.walletId) }
            if (tx != null && txWallet != null) EditTransactionSheet(
                data = data,
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
        Sheet.Fill -> FillSheet(app = app, data = data, onDismiss = { sheet = null })
    }

    if (paywall) PaywallSheet(data = data, onDismiss = { paywall = false })
}
