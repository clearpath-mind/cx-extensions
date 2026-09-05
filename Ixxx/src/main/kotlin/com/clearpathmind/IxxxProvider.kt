package com.clearpathmind

import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class IxxxProvider : Plugin() {
    override fun load(context: Context) {
        registerMainAPI(Ixxx())
    }
}
