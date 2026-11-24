package com.patternsystems.pocketcash

import android.app.Application
import com.patternsystems.pocketcash.data.CashStore
import com.patternsystems.pocketcash.data.LowCashNotifier
import com.patternsystems.pocketcash.widget.Widgets

class PocketCashApp : Application() {
    override fun onCreate() {
        super.onCreate()
        LowCashNotifier.createChannel(this)
        CashStore.get(this)
        // Widgets show "today", which resets at midnight even when nothing else changes.
        Widgets.scheduleMidnightRefresh(this)
    }
}
