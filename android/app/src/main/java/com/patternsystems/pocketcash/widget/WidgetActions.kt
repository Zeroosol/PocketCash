package com.patternsystems.pocketcash.widget

import android.content.Context
import androidx.glance.GlanceId
import androidx.glance.action.ActionParameters
import androidx.glance.appwidget.action.ActionCallback
import com.patternsystems.pocketcash.data.CashStore
import com.patternsystems.pocketcash.data.Haptics

/** A widget button: saves the amount through the store (which redraws every widget) and buzzes once. */
class AdjustCashAction : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        val walletId = parameters[WalletKey] ?: return
        val amount = parameters[AmountKey]?.toBigDecimalOrNull() ?: return
        val positive = parameters[SignKey] == "+"
        val tx = CashStore.get(context).quickAdd(walletId, amount, positive, fromWidget = true)
        if (tx != null) Haptics.confirm(context)
    }
}

/** The widget's Undo chip: deletes the transaction it shows, within 2 minutes of the tap. */
class UndoAction : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        val id = parameters[TransactionKey] ?: return
        CashStore.get(context).undoWidget(id)
        Haptics.confirm(context)
    }
}
