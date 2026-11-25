package com.patternsystems.pocketcash.tile

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.Intent
import android.graphics.drawable.Icon
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import androidx.core.net.toUri
import com.patternsystems.pocketcash.MainActivity
import com.patternsystems.pocketcash.R
import com.patternsystems.pocketcash.core.MINUS
import com.patternsystems.pocketcash.core.MoneyFormat
import com.patternsystems.pocketcash.data.CashStore
import com.patternsystems.pocketcash.data.Haptics
import com.patternsystems.pocketcash.data.isPro
import com.patternsystems.pocketcash.data.privacyOn
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Quick Settings tile "Quick spend" (Pro, docs/spec.md sections 5 and 8): one tap deducts the Quick spend amount (the
 * daily commute fare, by default the smallest spend button). The subtitle shows the balance, or •••• in privacy mode.
 * Added while Free, it says "PocketCash Pro" and a tap opens the paywall instead of spending.
 */
class QuickSpendTileService : TileService() {
    override fun onStartListening() {
        super.onStartListening()
        render()
    }

    override fun onClick() {
        super.onClick()
        val store = CashStore.get(this)
        if (!store.data.value.isPro) {
            openPaywall()
            return
        }
        store.scope.launch {
            val tx = store.quickSpend()
            withContext(Dispatchers.Main) {
                if (tx != null) Haptics.confirm(this@QuickSpendTileService)
                render()
            }
        }
    }

    private fun openPaywall() {
        val intent = Intent(this, MainActivity::class.java)
            .setAction(Intent.ACTION_VIEW)
            .setData("pocketcash://pro".toUri())
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startActivityAndCollapse(PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT))
        } else {
            startActivityAndCollapseLegacy(intent)
        }
    }

    /** Android 13 and older only have the Intent version (Android 14+ takes the PendingIntent one above). */
    @SuppressLint("StartActivityAndCollapseDeprecated")
    @Suppress("DEPRECATION")
    private fun startActivityAndCollapseLegacy(intent: Intent) = startActivityAndCollapse(intent)

    private fun render() {
        val tile = qsTile ?: return
        val data = CashStore.get(this).data.value
        val pro = data.isPro
        val wallet = data.quickSpendWallet(pro)
        val amount = data.quickSpendAmount(pro)
        when {
            !pro -> {
                tile.icon = Icon.createWithResource(this, R.drawable.ic_tile_lock)
                tile.label = getString(R.string.tile_label)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) tile.subtitle = getString(R.string.pro_name)
                tile.state = Tile.STATE_INACTIVE
            }
            wallet == null || amount == null -> {
                tile.icon = Icon.createWithResource(this, R.drawable.ic_tile_wallet)
                tile.label = getString(R.string.tile_label)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) tile.subtitle = getString(R.string.tile_open_app)
                tile.state = Tile.STATE_UNAVAILABLE
            }
            else -> {
                tile.icon = Icon.createWithResource(this, R.drawable.ic_tile_wallet)
                tile.label = getString(R.string.tile_spend, "$MINUS${MoneyFormat.full(amount, wallet.currencyCode, wallet.symbol)}")
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    tile.subtitle = if (data.privacyOn) "••••"
                    else getString(R.string.tile_left, MoneyFormat.full(data.balanceOf(wallet.id), wallet.currencyCode, wallet.symbol))
                }
                tile.state = Tile.STATE_ACTIVE
            }
        }
        tile.updateTile()
    }
}
