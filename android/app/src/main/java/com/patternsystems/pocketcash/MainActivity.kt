package com.patternsystems.pocketcash

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.patternsystems.pocketcash.data.AppLock
import com.patternsystems.pocketcash.data.CashStore
import com.patternsystems.pocketcash.data.ProBilling
import com.patternsystems.pocketcash.data.appLockOn
import com.patternsystems.pocketcash.ui.screens.LockScreen
import com.patternsystems.pocketcash.ui.screens.PocketCashRoot
import com.patternsystems.pocketcash.ui.theme.PocketCashTheme
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/** Where a pocketcash:// link (shortcut, notification, widget) should take the app (docs/spec.md section 1). */
sealed interface DeepLink {
    data class Keypad(val add: Boolean) : DeepLink
    data object Count : DeepLink
    data class Wallet(val id: String) : DeepLink
    data object Pro : DeepLink

    companion object {
        fun parse(uri: Uri?): DeepLink? {
            if (uri?.scheme != "pocketcash") return null
            return when (uri.host) {
                "keypad" -> Keypad(add = uri.getQueryParameter("mode") == "add")
                "count" -> Count
                "wallet" -> uri.getQueryParameter("id")?.let { Wallet(it) }
                "pro" -> Pro
                else -> null
            }
        }
    }
}

/**
 * The one activity. A FragmentActivity because App lock's BiometricPrompt needs one. A link that arrives while the
 * app is locked waits until it's unlocked.
 */
class MainActivity : FragmentActivity() {
    private val links = MutableStateFlow<DeepLink?>(null)
    private val store by lazy { CashStore.get(this) }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        val billing = ProBilling.get(this)
        billing.connect()
        AppLock.start(store.data.value.appLockOn)
        if (savedInstanceState == null) links.value = DeepLink.parse(intent?.data)

        // While App lock is on, the app switcher shows no thumbnail (Android 13+). Screenshots stay allowed.
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.CREATED) {
                store.data.map { it.appLockOn }.distinctUntilChanged().collect { on ->
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) setRecentsScreenshotEnabled(!on)
                    if (!on) AppLock.disabled()
                }
            }
        }

        setContent {
            val data by store.data.collectAsStateWithLifecycle()
            val link by links.collectAsStateWithLifecycle()
            // Not lifecycle-bound: the lock screen must already be in place when the app comes back.
            val locked by AppLock.locked.collectAsState()
            PocketCashTheme {
                if (locked && data.appLockOn) {
                    LockScreen(onUnlock = { AppLock.unlock(this) })
                } else {
                    PocketCashRoot(data = data, link = link, onLinkHandled = { links.value = null })
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        DeepLink.parse(intent.data)?.let { links.value = it }
    }

    override fun onStart() {
        super.onStart()
        AppLock.onStart(store.data.value.appLockOn)
    }

    override fun onResume() {
        super.onResume()
        // A purchase made or refunded elsewhere shows up here.
        ProBilling.get(this).connect()
        AppLock.onResume(this, store.data.value.appLockOn)
    }

    override fun onStop() {
        super.onStop()
        AppLock.onStop(this, store.data.value.appLockOn)
    }
}
