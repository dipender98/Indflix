package com.ottmirror

import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class OTTMirrorPlugin : Plugin() {
    override fun load(context: Context) {
        registerMainAPI(OTTMirrorNetflix())
        registerMainAPI(OTTMirrorHotstar())
        registerMainAPI(OTTMirrorPrimeVideo())
        registerMainAPI(OTTMirrorDisneyPlus())
    }
}