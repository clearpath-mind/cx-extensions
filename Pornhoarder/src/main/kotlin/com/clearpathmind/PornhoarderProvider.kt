package com.clearpathmind

import android.content.Context
import android.util.Log
import androidx.appcompat.app.AppCompatActivity
import com.lagradost.cloudstream3.extractors.StreamTape
import com.lagradost.cloudstream3.extractors.Wishonly
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class PornhoarderProvider : Plugin() {
    override fun load(context: Context) {
        CloudflareSolver.init(context)
        registerMainAPI(Pornhoarder())
        registerExtractorAPI(StreamTape())
        registerExtractorAPI(Wishonly())

        this.openSettings = { ctx ->
            try {
                val act = ctx as AppCompatActivity
                if (!act.isFinishing && !act.isDestroyed) {
                    CfSettingsFragment().show(act.supportFragmentManager, "ph_cf")
                } else {
                    Log.e("PornHoarder", "Activity is not valid anymore, cannot show settings")
                }
            } catch (e: Exception) {
                Log.e("PornHoarder", "Failed to open settings: ${e.message}")
            }
        }
    }
}
