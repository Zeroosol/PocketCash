package com.patternsystems.pocketcash.data

import android.app.Activity
import android.annotation.SuppressLint
import android.content.Context
import android.util.Log
import com.android.billingclient.api.AcknowledgePurchaseParams
import com.android.billingclient.api.BillingClient
import com.android.billingclient.api.BillingClientStateListener
import com.android.billingclient.api.BillingFlowParams
import com.android.billingclient.api.BillingResult
import com.android.billingclient.api.PendingPurchasesParams
import com.android.billingclient.api.ProductDetails
import com.android.billingclient.api.Purchase
import com.android.billingclient.api.PurchasesUpdatedListener
import com.android.billingclient.api.QueryProductDetailsParams
import com.android.billingclient.api.QueryPurchasesParams
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * PocketCash Pro, the one-time in-app product `pocketcash.pro.lifetime` (docs/spec.md section 5), over Google Play
 * Billing. Whether it's owned is cached in the store, so widgets and the tile know without asking Play.
 */
class ProBilling private constructor(context: Context) : PurchasesUpdatedListener {
    private val app = context.applicationContext
    private val store = CashStore.get(app)
    private var product: ProductDetails? = null

    private val _price = MutableStateFlow<String?>(null)
    /** The store's localized price, once Play has answered. */
    val price: StateFlow<String?> = _price.asStateFlow()

    private val _message = MutableStateFlow<String?>(null)
    /** A short status for the paywall ("Purchase pending", "Google Play isn't available"). */
    val message: StateFlow<String?> = _message.asStateFlow()

    private val client = BillingClient.newBuilder(app)
        .setListener(this)
        .enablePendingPurchases(PendingPurchasesParams.newBuilder().enableOneTimeProducts().build())
        .enableAutoServiceReconnection()
        .build()

    /** Connects (once), loads the price and checks what's owned, so a refund or a new phone is picked up. */
    fun connect() {
        if (client.isReady) {
            refresh()
            return
        }
        client.startConnection(object : BillingClientStateListener {
            override fun onBillingSetupFinished(result: BillingResult) {
                if (result.responseCode == BillingClient.BillingResponseCode.OK) {
                    _message.value = null
                    refresh()
                } else {
                    _message.value = "Google Play isn't available right now."
                    Log.w(TAG, "Billing setup: ${result.responseCode} ${result.debugMessage}")
                }
            }

            override fun onBillingServiceDisconnected() = Unit
        })
    }

    private fun refresh() {
        queryProduct()
        queryPurchases()
    }

    private fun queryProduct() {
        val params = QueryProductDetailsParams.newBuilder()
            .setProductList(
                listOf(
                    QueryProductDetailsParams.Product.newBuilder()
                        .setProductId(PRODUCT_ID)
                        .setProductType(BillingClient.ProductType.INAPP)
                        .build(),
                ),
            )
            .build()
        client.queryProductDetailsAsync(params) { result, details ->
            if (result.responseCode != BillingClient.BillingResponseCode.OK) return@queryProductDetailsAsync
            product = details.productDetailsList.firstOrNull { it.productId == PRODUCT_ID }
            _price.value = product?.oneTimePurchaseOfferDetails?.formattedPrice
        }
    }

    /** Asks Play what's owned. Play's answer is authoritative, so a refunded purchase turns Pro off again. */
    private fun queryPurchases() {
        val params = QueryPurchasesParams.newBuilder().setProductType(BillingClient.ProductType.INAPP).build()
        client.queryPurchasesAsync(params) { result, purchases ->
            if (result.responseCode == BillingClient.BillingResponseCode.OK) handle(purchases, authoritative = true)
        }
    }

    fun restore() {
        _message.value = null
        if (client.isReady) queryPurchases() else connect()
    }

    /** Opens Play's purchase sheet. Returns false when the product hasn't loaded yet. */
    fun buy(activity: Activity): Boolean {
        val details = product ?: run {
            _message.value = "Still connecting to Google Play. Try again in a moment."
            connect()
            return false
        }
        val params = BillingFlowParams.newBuilder()
            .setProductDetailsParamsList(listOf(BillingFlowParams.ProductDetailsParams.newBuilder().setProductDetails(details).build()))
            .build()
        val result = client.launchBillingFlow(activity, params)
        if (result.responseCode == BillingClient.BillingResponseCode.ITEM_ALREADY_OWNED) queryPurchases()
        return result.responseCode == BillingClient.BillingResponseCode.OK
    }

    override fun onPurchasesUpdated(result: BillingResult, purchases: MutableList<Purchase>?) {
        when (result.responseCode) {
            BillingClient.BillingResponseCode.OK -> handle(purchases.orEmpty(), authoritative = false)
            BillingClient.BillingResponseCode.ITEM_ALREADY_OWNED -> queryPurchases()
            BillingClient.BillingResponseCode.USER_CANCELED -> Unit
            else -> _message.value = "The purchase didn't go through (${result.responseCode})."
        }
    }

    private fun handle(purchases: List<Purchase>, authoritative: Boolean) {
        val ours = purchases.filter { PRODUCT_ID in it.products }
        val owned = ours.any { it.purchaseState == Purchase.PurchaseState.PURCHASED }
        if (ours.any { it.purchaseState == Purchase.PurchaseState.PENDING } && !owned) {
            _message.value = "Purchase pending. Pro unlocks as soon as Google Play confirms it."
        }
        // Unacknowledged purchases are refunded by Play after three days.
        ours.filter { it.purchaseState == Purchase.PurchaseState.PURCHASED && !it.isAcknowledged }.forEach { purchase ->
            val params = AcknowledgePurchaseParams.newBuilder().setPurchaseToken(purchase.purchaseToken).build()
            client.acknowledgePurchase(params) { r ->
                if (r.responseCode != BillingClient.BillingResponseCode.OK) Log.w(TAG, "Acknowledge: ${r.responseCode}")
            }
        }
        if (owned || authoritative) store.scope.launch { store.setProPurchased(owned) }
        if (owned) _message.value = null
    }

    companion object {
        const val PRODUCT_ID = "pocketcash.pro.lifetime"
        private const val TAG = "ProBilling"

        // Holds only the application context, which lives as long as the process.
        @SuppressLint("StaticFieldLeak")
        @Volatile private var instance: ProBilling? = null

        fun get(context: Context): ProBilling =
            instance ?: synchronized(this) { instance ?: ProBilling(context).also { instance = it } }
    }
}
