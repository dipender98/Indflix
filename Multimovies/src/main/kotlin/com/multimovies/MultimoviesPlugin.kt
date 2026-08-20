package com.multimovies

import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

/**
 * Registers the Multimovies provider with CloudStream.
 * The [cloudstream] extension id in build.gradle must match the package name.
 */
@CloudstreamPlugin
class MultimoviesPlugin : Plugin() {
    override fun load() {
        // All providers/extractors added here are registered in the app.
        registerExtension(MultimoviesProvider())
    }
}
