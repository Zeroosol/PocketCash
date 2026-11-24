package com.patternsystems.pocketcash.data

import android.Manifest
import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import com.patternsystems.pocketcash.MainActivity
import com.patternsystems.pocketcash.R
import com.patternsystems.pocketcash.core.AppData
import com.patternsystems.pocketcash.core.MoneyFormat
import com.patternsystems.pocketcash.core.Wallet

/** "Only ₱120 left in Main Wallet": a local notification when a wallet drops below its threshold (docs/spec.md 5). */
object LowCashNotifier {
    const val CHANNEL = "low_cash"

    fun createChannel(context: Context) {
        val channel = NotificationChannel(CHANNEL, context.getString(R.string.channel_low_cash), NotificationManager.IMPORTANCE_DEFAULT)
        channel.description = context.getString(R.string.channel_low_cash_description)
        context.getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    fun canNotify(context: Context): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    @SuppressLint("MissingPermission") // checked by canNotify
    fun notify(context: Context, data: AppData, wallets: List<Wallet>) {
        if (wallets.isEmpty() || !canNotify(context)) return
        val manager = NotificationManagerCompat.from(context)
        for (wallet in wallets) {
            val balance = MoneyFormat.full(data.balanceOf(wallet.id), wallet.currencyCode, wallet.symbol)
            val limit = wallet.lowCashThreshold?.let { MoneyFormat.full(it, wallet.currencyCode, wallet.symbol) } ?: continue
            val open = PendingIntent.getActivity(
                context, wallet.id.hashCode(),
                Intent(context, MainActivity::class.java).setAction(Intent.ACTION_VIEW)
                    .setData("pocketcash://wallet?id=${wallet.id}".toUri()),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            val notification = NotificationCompat.Builder(context, CHANNEL)
                .setSmallIcon(R.drawable.ic_tile_wallet)
                .setContentTitle("Only $balance left in ${wallet.name}")
                .setContentText("It's below your $limit reminder. Time for an ATM run?")
                .setContentIntent(open)
                .setAutoCancel(true)
                .setCategory(NotificationCompat.CATEGORY_REMINDER)
                .build()
            if (canNotify(context)) manager.notify(wallet.id.hashCode(), notification)
        }
    }
}
