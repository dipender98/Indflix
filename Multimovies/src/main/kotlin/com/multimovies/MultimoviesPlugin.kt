package com.multimovies

import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

/**
 * Registers the Multimovies provider with CloudStream.
 */
@CloudstreamPlugin
class MultimoviesPlugin : Plugin() {
    override fun load(context: Context) {
        // All providers/extractors added here are registered in the app.
        registerMainAPI(MultimoviesProvider())
    }
}

