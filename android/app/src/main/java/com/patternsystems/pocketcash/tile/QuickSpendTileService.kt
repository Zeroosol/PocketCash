package com.patternsystems.pocketcash.tile

import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import com.patternsystems.pocketcash.R
import com.patternsystems.pocketcash.core.MINUS
import com.patternsystems.pocketcash.core.MoneyFormat
import com.patternsystems.pocketcash.data.CashStore
import com.patternsystems.pocketcash.data.Haptics
import com.patternsystems.pocketcash.data.isPro
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Quick Settings tile "Quick spend" (docs/spec.md section 8): one tap deducts the Quick spend amount (the daily
 * commute fare, by default the smallest spend button). The subtitle shows the balance, or •••• in privacy mode.
 */
class QuickSpendTileService : TileService() {
    override fun onStartListening() {
        super.onStartListening()
        render()
    }

    override fun onClick() {
        super.onClick()
        val store = CashStore.get(this)
        store.scope.launch {
            val tx = store.quickSpend()
            withContext(Dispatchers.Main) {
                if (tx != null) Haptics.confirm(this@QuickSpendTileService)
                render()
            }
        }
    }

    private fun render() {
        val tile = qsTile ?: return
        val data = CashStore.get(this).data.value
        val pro = data.isPro
        val wallet = data.quickSpendWallet(pro)
        val amount = data.quickSpendAmount(pro)
        if (wallet == null || amount == null) {
            tile.label = getString(R.string.tile_label)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) tile.subtitle = "Open PocketCash first"
            tile.state = Tile.STATE_UNAVAILABLE
        } else {
            tile.label = "Spend $MINUS${MoneyFormat.full(amount, wallet.currencyCode, wallet.symbol)}"
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                tile.subtitle = if (data.settings.privacyMode) "••••"
                else "${MoneyFormat.full(data.balanceOf(wallet.id), wallet.currencyCode, wallet.symbol)} left"
            }
            tile.state = Tile.STATE_ACTIVE
        }
        tile.updateTile()
    }
}
