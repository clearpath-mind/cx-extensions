package com.clearpathmind

import android.content.DialogInterface
import android.graphics.Color
import android.graphics.drawable.Drawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.ImageView
import android.widget.TextView
import androidx.fragment.app.DialogFragment
import com.lagradost.cloudstream3.CommonActivity.showToast
import com.lagradost.cloudstream3.MainActivity

/**
 * PornHoarder settings in the StreamPlay style: a DialogFragment inflated
 * from plugin resources, opening the site settings page (/settings/) so the
 * Cloudflare challenge can be solved and the orientation set to Straight.
 * Manual Save & Close persists cookies via [CloudflareSolver].
 */
class CfSettingsFragment(
    private val plugin: PornhoarderProvider
) : DialogFragment() {
    private val res = plugin.resources ?: throw Exception("Unable to access plugin resources")
    private val uiHandler = Handler(Looper.getMainLooper())
    private var poll: Runnable? = null
    private var webView: WebView? = null

    companion object {
        const val SITE_URL = "https://ww8.pornhoarder.org/settings/"
        const val COLOR_GREEN = "#4CAF50"
        const val COLOR_AMBER = "#FFC107"
        const val COLOR_GRAY = "#BDBDBD"
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        return inflater.inflate(R.layout.fragment_ph_cf, container, false)
    }

    override fun onStart() {
        super.onStart()
        dialog?.window?.apply {
            setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            setBackgroundDrawable(
                android.graphics.drawable.ColorDrawable(Color.TRANSPARENT)
            )
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        val status: TextView = view.findViewById(R.id.phStatus)
        val headerIcon: ImageView = view.findViewById(R.id.phHeaderIcon)
        val wv: WebView = view.findViewById(R.id.phWebView)
        val reload: Button = view.findViewById(R.id.phReload)
        val save: Button = view.findViewById(R.id.phSave)
        webView = wv

        try {
            headerIcon.setImageDrawable(res.getDrawable(R.drawable.settings_icon, null))
        } catch (_: Exception) {
        }

        wv.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true
            userAgentString = Pornhoarder.USER_AGENT
            useWideViewPort = true
            loadWithOverviewMode = true
        }
        CookieManager.getInstance().apply {
            setAcceptCookie(true)
            setAcceptThirdPartyCookies(wv, true)
        }
        wv.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                setStatus(status, "Page loaded — checking for challenge…", COLOR_GRAY)
                startDetection(wv, status)
            }
        }
        reload.setOnClickListener {
            setStatus(status, "Reloading…", COLOR_GRAY)
            wv.reload()
        }
        save.setOnClickListener {
            CloudflareSolver.saveCookies(SITE_URL, Pornhoarder.USER_AGENT)
            CookieManager.getInstance().flush()
            showToast("Cookies saved")
            dismiss()
        }
        wv.loadUrl(SITE_URL)
    }

    private fun setStatus(status: TextView, text: String, colorHex: String) {
        status.text = text
        status.setTextColor(Color.parseColor(colorHex))
    }

    /** Polls page state: solved (cf_clearance) vs challenge visible vs clean.
     * Never auto-closes: the user taps Save & Close manually. */
    private fun startDetection(webView: WebView, status: TextView) {
        stopDetection()
        val task = object : Runnable {
            override fun run() {
                val cookies = CookieManager.getInstance().getCookie(SITE_URL).orEmpty()
                if (cookies.contains("cf_clearance")) {
                    setStatus(status, "Solved ✓ — set orientation above, then Save & Close.", COLOR_GREEN)
                    poll = this
                    uiHandler.postDelayed(this, 5000)
                    return
                }
                webView.evaluateJavascript(
                    "(function(){var h=document.documentElement.innerHTML.toLowerCase();" +
                        "return h.includes(\"turnstile\")||h.includes(\"verify you are human\")" +
                        "||h.includes(\"checking your browser\")||h.includes(\"just a moment\");})();"
                ) { res ->
                    val challenged = res?.contains("true") == true
                    if (challenged) {
                        setStatus(
                            status,
                            "Challenge detected — tap the checkbox in the page above.",
                            COLOR_AMBER
                        )
                    } else {
                        setStatus(
                            status,
                            "No challenge on this page — Save & Close, then try the extension.",
                            COLOR_GRAY
                        )
                    }
                    poll = this
                    uiHandler.postDelayed(this, 2500)
                }
            }
        }
        uiHandler.postDelayed(task, 1500)
    }

    private fun stopDetection() {
        poll?.let { uiHandler.removeCallbacks(it) }
        poll = null
    }

    override fun onDismiss(dialog: DialogInterface) {
        super.onDismiss(dialog)
        stopDetection()
        try {
            (webView?.parent as? ViewGroup)?.removeView(webView)
            webView?.destroy()
        } catch (_: Exception) {
        }
        webView = null
        MainActivity.reloadHomeEvent.invoke(true)
    }
}
