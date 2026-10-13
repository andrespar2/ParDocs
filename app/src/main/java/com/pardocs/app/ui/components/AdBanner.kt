package com.pardocs.app.ui.components

import android.util.Log
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.google.android.gms.ads.AdListener
import com.google.android.gms.ads.AdRequest
import com.google.android.gms.ads.AdSize
import com.google.android.gms.ads.AdView
import com.google.android.gms.ads.LoadAdError
import com.pardocs.app.BuildConfig
import com.pardocs.app.ads.AdsConsentManager

private const val TAG = "ParDocsAds"

/**
 * Anchored adaptive banner ad.
 *
 * - Requests an [AdSize.getCurrentOrientationAnchoredAdaptiveBannerAdSize] that
 *   always matches the available width, so it fits every screen size/orientation.
 * - The `AdView` is only attached to the layout once an ad has actually loaded,
 *   so it never overlays content nor leaves a permanent empty gap when there is
 *   no network, no consent or no inventory.
 * - Silently degrades: any failure leaves the UI untouched.
 */
@Composable
fun AnchoredAdaptiveBanner(modifier: Modifier = Modifier) {
    // Wait until the UMP consent flow allows requesting ads.
    if (!AdsConsentManager.canRequestAds) return

    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    BoxWithConstraints(modifier = modifier) {
        val widthDp = maxWidth.value.toInt().coerceAtLeast(1)

        var adView by remember { mutableStateOf<AdView?>(null) }
        var adLoaded by remember { mutableStateOf(false) }

        // (Re)create and load the banner whenever the available width changes.
        LaunchedEffect(widthDp) {
            adLoaded = false
            val size = AdSize.getCurrentOrientationAnchoredAdaptiveBannerAdSize(context, widthDp)
            val view = AdView(context).apply {
                adUnitId = BuildConfig.ADMOB_BANNER_UNIT_ID
                setAdSize(size)
                adListener = object : AdListener() {
                    override fun onAdLoaded() {
                        adLoaded = true
                    }

                    override fun onAdFailedToLoad(adError: LoadAdError) {
                        // No ad: keep the UI identical (no empty reserved space).
                        adLoaded = false
                        Log.w(TAG, "Banner no disponible: ${adError.code} ${adError.message}")
                    }
                }
            }
            // loadAd() must run on the main thread (LaunchedEffect runs on Main).
            view.loadAd(AdRequest.Builder().build())
            adView = view
        }

        // Keep the ad in sync with the host lifecycle and release it on dispose.
        val current = adView
        DisposableEffect(current) {
            val observer = LifecycleEventObserver { _, event ->
                when (event) {
                    Lifecycle.Event.ON_RESUME -> current?.resume()
                    Lifecycle.Event.ON_PAUSE -> current?.pause()
                    Lifecycle.Event.ON_DESTROY -> current?.destroy()
                    else -> Unit
                }
            }
            lifecycleOwner.lifecycle.addObserver(observer)
            onDispose {
                lifecycleOwner.lifecycle.removeObserver(observer)
                current?.destroy()
            }
        }

        val view = adView
        if (adLoaded && view != null) {
            // key(view) makes Compose rebuild the AndroidView node if the instance changes.
            key(view) {
                AndroidView(
                    factory = { view },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}
