package com.patternsystems.pocketcash

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.getValue
import com.patternsystems.pocketcash.data.CashStore
import com.patternsystems.pocketcash.data.ProBilling
import com.patternsystems.pocketcash.ui.screens.PocketCashRoot
import com.patternsystems.pocketcash.ui.theme.PocketCashTheme
import kotlinx.coroutines.flow.MutableStateFlow

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

class MainActivity : ComponentActivity() {
    private val links = MutableStateFlow<DeepLink?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        val store = CashStore.get(this)
        val billing = ProBilling.get(this)
        billing.connect()
        if (savedInstanceState == null) links.value = DeepLink.parse(intent?.data)
        setContent {
            val data by store.data.collectAsStateWithLifecycle()
            val link by links.collectAsStateWithLifecycle()
            PocketCashTheme {
                PocketCashRoot(data = data, link = link, onLinkHandled = { links.value = null })
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        DeepLink.parse(intent.data)?.let { links.value = it }
    }

    override fun onResume() {
        super.onResume()
        // A purchase made or refunded elsewhere shows up here.
        ProBilling.get(this).connect()
    }
}
