package com.pardocs.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.pardocs.app.ads.AdsConsentManager
import com.pardocs.app.ui.ParDocsAppRoot
import com.pardocs.app.ui.theme.ParDocsTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        // Run the official Google UMP consent flow before any ad is requested.
        // Non-blocking: the UI keeps working while consent is resolved.
        AdsConsentManager.gatherConsent(this)
        setContent {
            ParDocsTheme {
                ParDocsAppRoot()
            }
        }
    }
}
