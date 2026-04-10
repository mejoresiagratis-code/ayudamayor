package com.ayudamayor.app.billing

import android.app.Activity
import android.content.Context
import com.android.billingclient.api.*

class BillingManager(private val activity: Activity) : PurchasesUpdatedListener {

    companion object {
        const val SKU_MONTHLY  = "familiar_mensual"
        const val SKU_ANNUAL   = "familiar_anual"
        const val SKU_LIFETIME = "familiar_lifetime"
    }

    private val billingClient = BillingClient.newBuilder(activity)
        .setListener(this)
        .enablePendingPurchases()
        .build()

    init {
        connectToPlayBilling()
    }

    private fun connectToPlayBilling() {
        billingClient.startConnection(object : BillingClientStateListener {
            override fun onBillingSetupFinished(result: BillingResult) {
                if (result.responseCode == BillingClient.BillingResponseCode.OK) {
                    queryActivePurchases()
                }
            }
            override fun onBillingServiceDisconnected() {
                // Reintentar tras desconexión
                connectToPlayBilling()
            }
        })
    }

    fun launchBillingFlow(plan: String) {
        val sku = when (plan) {
            "monthly"  -> SKU_MONTHLY
            "annual"   -> SKU_ANNUAL
            "lifetime" -> SKU_LIFETIME
            else       -> return
        }
        val productType = if (plan == "lifetime")
            BillingClient.ProductType.INAPP
        else
            BillingClient.ProductType.SUBS

        val params = QueryProductDetailsParams.newBuilder()
            .setProductList(
                listOf(
                    QueryProductDetailsParams.Product.newBuilder()
                        .setProductId(sku)
                        .setProductType(productType)
                        .build()
                )
            ).build()

        billingClient.queryProductDetailsAsync(params) { billingResult, productDetailsList ->
            if (billingResult.responseCode != BillingClient.BillingResponseCode.OK) return@queryProductDetailsAsync
            val product = productDetailsList.firstOrNull() ?: return@queryProductDetailsAsync

            val productDetailsParams = BillingFlowParams.ProductDetailsParams.newBuilder()
                .setProductDetails(product)
                .apply {
                    // Para suscripciones, seleccionar la primera oferta disponible
                    if (productType == BillingClient.ProductType.SUBS) {
                        val offerToken = product.subscriptionOfferDetails?.firstOrNull()?.offerToken
                        if (offerToken != null) setOfferToken(offerToken)
                    }
                }
                .build()

            val flowParams = BillingFlowParams.newBuilder()
                .setProductDetailsParamsList(listOf(productDetailsParams))
                .build()

            billingClient.launchBillingFlow(activity, flowParams)
        }
    }

    override fun onPurchasesUpdated(result: BillingResult, purchases: List<Purchase>?) {
        if (result.responseCode == BillingClient.BillingResponseCode.OK && purchases != null) {
            purchases.forEach { handlePurchase(it) }
        }
    }

    private fun handlePurchase(purchase: Purchase) {
        if (purchase.purchaseState != Purchase.PurchaseState.PURCHASED) return

        // Acknowledge para confirmar recepción a Google
        if (!purchase.isAcknowledged) {
            val ackParams = AcknowledgePurchaseParams.newBuilder()
                .setPurchaseToken(purchase.purchaseToken)
                .build()
            billingClient.acknowledgePurchase(ackParams) { }
        }

        // Determinar tier según SKU comprado
        val tier = when {
            purchase.products.contains(SKU_LIFETIME) -> "FAMILIAR_PRO"
            purchase.products.contains(SKU_ANNUAL)   -> "FAMILIAR_PRO"
            purchase.products.contains(SKU_MONTHLY)  -> "FAMILIAR_PRO"
            else -> "FAMILIAR_FREE"
        }
        saveTier(tier)
    }

    private fun queryActivePurchases() {
        // Consultar suscripciones activas
        billingClient.queryPurchasesAsync(
            QueryPurchasesParams.newBuilder()
                .setProductType(BillingClient.ProductType.SUBS)
                .build()
        ) { _, purchases ->
            val hasActive = purchases.any {
                it.purchaseState == Purchase.PurchaseState.PURCHASED
            }
            if (hasActive) saveTier("FAMILIAR_PRO")
        }

        // Consultar compras únicas (lifetime)
        billingClient.queryPurchasesAsync(
            QueryPurchasesParams.newBuilder()
                .setProductType(BillingClient.ProductType.INAPP)
                .build()
        ) { _, purchases ->
            val hasLifetime = purchases.any {
                it.purchaseState == Purchase.PurchaseState.PURCHASED &&
                        it.products.contains(SKU_LIFETIME)
            }
            if (hasLifetime) saveTier("FAMILIAR_PRO")
        }
    }

    fun getCurrentTier(): String {
        val prefs = activity.getSharedPreferences("ayudamayor", Context.MODE_PRIVATE)
        return prefs.getString("tier", "FAMILIAR_FREE") ?: "FAMILIAR_FREE"
    }

    private fun saveTier(tier: String) {
        activity.getSharedPreferences("ayudamayor", Context.MODE_PRIVATE)
            .edit()
            .putString("tier", tier)
            .apply()
    }
}
