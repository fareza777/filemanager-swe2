package com.filezen.files.billing

import android.app.Activity
import android.content.Context
import com.android.billingclient.api.*
import com.filezen.files.data.prefs.SettingsStore
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first

/**
 * One-time "Remove ads" purchase.
 *
 * PRODUCTION TODO:
 * 1. Create an in-app product in Play Console, e.g. id "remove_ads" (or change
 *    PRODUCT_ID below and keep it in sync).
 * 2. Upload a signed AAB; test with a license tester before release.
 */
class BillingManager(
    private val ctx: Context,
    private val settings: SettingsStore,
) : PurchasesUpdatedListener {

    companion object {
        const val PRODUCT_ID = "remove_ads" // PRODUCTION TODO: match Play Console product id
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private val _adFree = MutableStateFlow(false)
    val adFree: StateFlow<Boolean> = _adFree

    private val _price = MutableStateFlow<String?>(null)
    val price: StateFlow<String?> = _price

    private val _connected = MutableStateFlow(false)
    val connected: StateFlow<Boolean> = _connected

    private var productDetails: ProductDetails? = null

    private val client: BillingClient = BillingClient.newBuilder(ctx)
        .setListener(this)
        .enablePendingPurchases()
        .build()

    init {
        connect()
    }

    private fun connect() {
        if (client.isReady) { _connected.value = true; refresh() ; return }
        client.startConnection(object : BillingClientStateListener {
            override fun onBillingSetupFinished(result: BillingResult) {
                _connected.value = result.responseCode == BillingClient.BillingResponseCode.OK
                if (_connected.value) refresh()
            }
            override fun onBillingServiceDisconnected() {
                _connected.value = false
                scope.launch { delay(3000); connect() }
            }
        })
    }

    private fun refresh() {
        scope.launch {
            // cached flag first (works offline), then billing state
            _adFree.value = settings.adFree.first()
            queryProduct()
            restore()
        }
    }

    private fun queryProduct() {
        val params = QueryProductDetailsParams.newBuilder()
            .setProductList(listOf(
                QueryProductDetailsParams.Product.newBuilder()
                    .setProductId(PRODUCT_ID)
                    .setProductType(BillingClient.ProductType.INAPP)
                    .build()
            )).build()
        client.queryProductDetailsAsync(params) { result, list ->
            if (result.responseCode == BillingClient.BillingResponseCode.OK && list.isNotEmpty()) {
                productDetails = list.first()
                _price.value = list.first().oneTimePurchaseOfferDetails?.formattedPrice
            }
        }
    }

    private suspend fun restore() {
        val params = QueryPurchasesParams.newBuilder()
            .setProductType(BillingClient.ProductType.INAPP).build()
        val res = withContext(Dispatchers.IO) { client.queryPurchasesAsync(params) }
        val owned = res.purchasesList.any {
            it.products.contains(PRODUCT_ID) &&
                it.purchaseState == Purchase.PurchaseState.PURCHASED
        }
        if (owned) { settings.setAdFree(true); _adFree.value = true }
    }

    fun launchPurchase(activity: Activity) {
        val details = productDetails ?: return
        val params = BillingFlowParams.newBuilder()
            .setProductDetailsParamsList(listOf(
                BillingFlowParams.ProductDetailsParams.newBuilder()
                    .setProductDetails(details).build()
            )).build()
        client.launchBillingFlow(activity, params)
    }

    override fun onPurchasesUpdated(result: BillingResult, purchases: MutableList<Purchase>?) {
        if (result.responseCode != BillingClient.BillingResponseCode.OK || purchases == null) return
        purchases.filter { it.products.contains(PRODUCT_ID) }
            .forEach { handlePurchase(it) }
    }

    private fun handlePurchase(p: Purchase) {
        if (p.purchaseState != Purchase.PurchaseState.PURCHASED) return
        scope.launch { settings.setAdFree(true); _adFree.value = true }
        if (!p.isAcknowledged) {
            client.acknowledgePurchase(
                AcknowledgePurchaseParams.newBuilder().setPurchaseToken(p.purchaseToken).build()
            ) { }
        }
    }
}
