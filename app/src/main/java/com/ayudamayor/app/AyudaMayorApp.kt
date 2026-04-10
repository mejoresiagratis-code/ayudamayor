package com.ayudamayor.app

import android.app.Application
import com.google.android.gms.ads.MobileAds

class AyudaMayorApp : Application() {

    override fun onCreate() {
        super.onCreate()
        // Inicializar AdMob (necesario antes de cargar anuncios)
        MobileAds.initialize(this)
    }
}
