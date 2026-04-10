package com.ayudamayor.app.billing

import android.app.Activity
import android.content.Context

/**
 * Gestión de suscripciones Google Play Billing.
 * Activar cuando la app esté lista para publicar en Play Store.
 */
class BillingManager(private val activity: Activity) {

    fun launchBillingFlow(plan: String) {
        // TODO: implementar con billing-ktx cuando se publique en Play Store
    }

    fun getCurrentTier(): String {
        val prefs = activity.getSharedPreferences("ayudamayor", Context.MODE_PRIVATE)
        return prefs.getString("tier", "FAMILIAR_FREE") ?: "FAMILIAR_FREE"
    }
}
