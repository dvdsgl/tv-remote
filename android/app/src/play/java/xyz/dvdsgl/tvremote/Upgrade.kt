package xyz.dvdsgl.tvremote

import android.app.Activity
import android.content.Context
import com.android.billingclient.api.AcknowledgePurchaseParams
import com.android.billingclient.api.BillingClient
import com.android.billingclient.api.BillingClientStateListener
import com.android.billingclient.api.BillingFlowParams
import com.android.billingclient.api.BillingResult
import com.android.billingclient.api.PendingPurchasesParams
import com.android.billingclient.api.ProductDetails
import com.android.billingclient.api.Purchase
import com.android.billingclient.api.QueryProductDetailsParams
import com.android.billingclient.api.QueryPurchasesParams

/**
 * Dark mode as a one-time Google Play purchase (product id "dark_mode", $2.99). Ownership is
 * cached in the "remote" prefs ("owned") so dark mode works offline, and refreshed from Play
 * on every start.
 */
class Upgrade(private val activity: Activity, private val onChange: (owned: Boolean) -> Unit) {
    companion object {
        const val FREE = false
        const val PRODUCT = "dark_mode"
    }
    sealed interface Result { data object Bought : Result; data object Cancelled : Result; data class Failed(val message: String) : Result }

    private val prefs = activity.getSharedPreferences("remote", Context.MODE_PRIVATE)
    var owned = prefs.getBoolean("owned", false); private set
    var price: String? = null; private set
    private var details: ProductDetails? = null
    private var pending: ((Result) -> Unit)? = null

    private val client = BillingClient.newBuilder(activity)
        .setListener { result, purchases -> onPurchases(result, purchases) }
        .enablePendingPurchases(PendingPurchasesParams.newBuilder().enableOneTimeProducts().build())
        .build()

    fun start() {
        if (client.isReady) return refresh()
        client.startConnection(object : BillingClientStateListener {
            override fun onBillingSetupFinished(result: BillingResult) {
                if (result.responseCode == BillingClient.BillingResponseCode.OK) refresh()
            }
            override fun onBillingServiceDisconnected() {}
        })
    }

    fun stop() = client.endConnection()

    private fun refresh() {
        client.queryPurchasesAsync(QueryPurchasesParams.newBuilder().setProductType(BillingClient.ProductType.INAPP).build()) { result, purchases ->
            if (result.responseCode == BillingClient.BillingResponseCode.OK) handle(purchases, fromPlay = true)
        }
        val product = QueryProductDetailsParams.Product.newBuilder().setProductId(PRODUCT).setProductType(BillingClient.ProductType.INAPP).build()
        client.queryProductDetailsAsync(QueryProductDetailsParams.newBuilder().setProductList(listOf(product)).build()) { _, result ->
            details = result.productDetailsList.firstOrNull()
            price = details?.oneTimePurchaseOfferDetails?.formattedPrice
        }
    }

    fun buy(done: (Result) -> Unit) {
        val d = details
        if (!client.isReady || d == null) return done(Result.Failed("Purchases aren't available right now. Make sure Remote came from Google Play and you're online."))
        pending = done
        val params = BillingFlowParams.ProductDetailsParams.newBuilder().setProductDetails(d).build()
        client.launchBillingFlow(activity, BillingFlowParams.newBuilder().setProductDetailsParamsList(listOf(params)).build())
    }

    private fun onPurchases(result: BillingResult, purchases: List<Purchase>?) {
        val done = pending.also { pending = null }
        when (result.responseCode) {
            BillingClient.BillingResponseCode.OK -> handle(purchases.orEmpty(), fromPlay = false).also { activity.runOnUiThread { done?.invoke(if (owned) Result.Bought else Result.Cancelled) } }
            BillingClient.BillingResponseCode.ITEM_ALREADY_OWNED -> { set(true); activity.runOnUiThread { done?.invoke(Result.Bought) } }
            BillingClient.BillingResponseCode.USER_CANCELED -> activity.runOnUiThread { done?.invoke(Result.Cancelled) }
            else -> activity.runOnUiThread { done?.invoke(Result.Failed(result.debugMessage.ifEmpty { "Google Play couldn't complete the purchase." })) }
        }
    }

    /** fromPlay: the full list of purchases, so a missing one means not owned (e.g. refunded). */
    private fun handle(purchases: List<Purchase>, fromPlay: Boolean) {
        val bought = purchases.firstOrNull { PRODUCT in it.products && it.purchaseState == Purchase.PurchaseState.PURCHASED }
        if (bought != null && !bought.isAcknowledged)
            client.acknowledgePurchase(AcknowledgePurchaseParams.newBuilder().setPurchaseToken(bought.purchaseToken).build()) {}
        if (bought != null) set(true) else if (fromPlay) set(false)
    }

    private fun set(value: Boolean) {
        if (value == owned) return
        owned = value
        prefs.edit().putBoolean("owned", value).apply()
        activity.runOnUiThread { onChange(value) }
    }
}
