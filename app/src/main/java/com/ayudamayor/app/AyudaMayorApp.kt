package com.ayudamayor.app

import android.app.Application

class AyudaMayorApp : Application() {
    override fun onCreate() {
        super.onCreate()
        // Firebase y AdMob se activan cuando se configure google-services.json real
    }
}
