package com.pardocs.app.ads

import android.app.Activity
import android.content.Context
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.google.android.gms.ads.MobileAds
import com.google.android.ump.ConsentInformation
import com.google.android.ump.ConsentRequestParameters
import com.google.android.ump.UserMessagingPlatform

/**
 * Centralises everything related to Google AdMob: SDK initialisation and the
 * Google User Messaging Platform (UMP) consent flow.
 *
 * Design goals (ParDocs is a local-first app):
 *  - Never block or slow down startup: the SDK is initialised on a background thread.
 *  - Never crash when there is no network, no consent or no ad inventory.
 *  - Only request ads once [canRequestAds] is true (consent obtained / not required).
 *
 * The banner unit id and the AdMob App ID are injected per build type (see
 * `app/build.gradle.kts`): the official Google test identifiers for debug builds
 * and the real ParDocs identifiers for release builds.
 */
object AdsConsentManager {

    private const val TAG = "ParDocsAds"

    /**
     * True when the UMP SDK allows requesting ads (consent obtained, not required
     * in this region, or carried over from a previous session). Read from Compose
     * to react automatically once consent becomes available.
     */
    var canRequestAds by mutableStateOf(false)
        private set

    /**
     * True when the current privacy message requires a publisher-rendered entry
     * point so the user can change their privacy options at any time.
     */
    var privacyOptionsRequired by mutableStateOf(false)
        private set

    private var sdkInitialized = false
    private var consentUpdateRequested = false

    /**
     * Initialise the Mobile Ads SDK. Called once from [com.pardocs.app.ParDocsApp].
     * Runs on a background thread so it never delays the first frame.
     */
    fun initializeSdk(context: Context) {
        if (sdkInitialized) return
        sdkInitialized = true
        val appContext = context.applicationContext
        Thread {
            try {
                MobileAds.initialize(appContext) { }
            } catch (t: Throwable) {
                // Ads are optional: never let a failure here affect the app.
                Log.w(TAG, "No se pudo inicializar Google Mobile Ads", t)
            }
        }.apply {
            name = "pardocs-admob-init"
            isDaemon = true
        }.start()
    }

    /**
     * Gather (or refresh) the user's consent information. Must be called from an
     * Activity on the main thread. Safe to call once per process.
     *
     * The same flow works for debug and release; no test-specific geography is
     * forced so production behaviour is not altered by the debug build.
     */
    fun gatherConsent(activity: Activity) {
        val consentInformation = try {
            UserMessagingPlatform.getConsentInformation(activity)
        } catch (t: Throwable) {
            Log.w(TAG, "UMP no disponible; se solicitan anuncios si es posible", t)
            canRequestAds = true
            return
        }

        // Consent carried over from a previous session may already allow ad requests.
        if (consentInformation.canRequestAds()) {
            canRequestAds = true
        }

        if (consentUpdateRequested) return
        consentUpdateRequested = true

        val params = ConsentRequestParameters.Builder().build()

        consentInformation.requestConsentInfoUpdate(
            activity,
            params,
            {
                privacyOptionsRequired =
                    consentInformation.privacyOptionsRequirementStatus ==
                        ConsentInformation.PrivacyOptionsRequirementStatus.REQUIRED

                UserMessagingPlatform.loadAndShowConsentFormIfRequired(activity) { formError ->
                    if (formError != null) {
                        Log.w(TAG, "Formulario de consentimiento: ${formError.message}")
                    }
                    if (consentInformation.canRequestAds()) {
                        canRequestAds = true
                    }
                }
            },
            { requestError ->
                // On failure the SDK keeps the previous session's consent status.
                Log.w(TAG, "No se pudo actualizar el consentimiento: ${requestError.message}")
                if (consentInformation.canRequestAds()) {
                    canRequestAds = true
                }
            },
        )
    }

    /** Present the privacy options form (only meaningful when required). */
    fun showPrivacyOptions(activity: Activity) {
        try {
            UserMessagingPlatform.showPrivacyOptionsForm(activity) { formError ->
                if (formError != null) {
                    Log.w(TAG, "Opciones de privacidad: ${formError.message}")
                }
            }
        } catch (t: Throwable) {
            Log.w(TAG, "No se pudieron mostrar las opciones de privacidad", t)
        }
    }
}
