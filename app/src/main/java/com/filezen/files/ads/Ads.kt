package com.filezen.files.ads

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.google.android.gms.ads.AdRequest
import com.google.android.gms.ads.AdSize
import com.google.android.gms.ads.AdView

object Ads {
    // PRODUCTION TODO: replace with your real banner ad unit from AdMob.
    // Google's public TEST banner unit — safe to keep during development:
    const val BANNER_UNIT_ID = "ca-app-pub-3940256099942544/6300978111"
}

/** Banner shown only on Home / Storage summary — never inside search, preview or ops. */
@Composable
fun ZenBanner(modifier: Modifier = Modifier) {
    AndroidView(
        modifier = modifier.fillMaxWidth().height(50.dp),
        factory = { ctx ->
            AdView(ctx).apply {
                setAdSize(AdSize.BANNER)
                adUnitId = Ads.BANNER_UNIT_ID
                loadAd(AdRequest.Builder().build())
            }
        },
    )
}
