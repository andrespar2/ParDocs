package com.pardocs.app

import android.app.Application
import com.pardocs.app.ads.AdsConsentManager
import com.pardocs.app.engine.PdfEngine

class ParDocsApp : Application() {
    override fun onCreate() {
        super.onCreate()
        // PDFBox-Android needs its resource directory resolved before first use.
        PdfEngine.init(this)
        // Initialise Google Mobile Ads on a background thread so the banner can
        // request ads early without ever slowing down app startup.
        AdsConsentManager.initializeSdk(this)
    }
}
