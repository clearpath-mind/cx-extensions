package com.clearpathmind

import android.content.DialogInterface
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.fragment.app.DialogFragment
import com.lagradost.cloudstream3.MainActivity

/**
 * PornHoarder settings as a StreamPlay-style DialogFragment, but with fully
 * programmatic views (plugin resources are not reliably available at
 * runtime, which crashed the resource-inflated version).
 *
 * Opens the site settings page (/settings/) so the Cloudflare challenge can
 * be solved and the orientation set to Straight. Manual Save & Close
 * persists cookies via [CloudflareSolver].
 */
class CfSettingsFragment : DialogFragment() {
    private val uiHandler = Handler(Looper.getMainLooper())
    private var poll: Runnable? = null
    private var webView: WebView? = null
    private var statusView: TextView? = null

    companion object {
        const val SITE_URL = "https://ww8.pornhoarder.org/settings/"
        const val COLOR_GREEN = "#4CAF50"
        const val COLOR_AMBER = "#FFC107"
        const val COLOR_GRAY = "#BDBDBD"
        const val PAD = 40
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        val ctx = requireContext()
        val title = TextView(ctx).apply {
            text = "PornHoarder"
            setTextColor(Color.WHITE)
            textSize = 22f
            typeface = Typeface.DEFAULT_BOLD
            setPadding(PAD, PAD, PAD, 4)
        }
        val subtitle = TextView(ctx).apply {
            text = "Solve the challenge, set orientation to Straight, then Save & Close"
            setTextColor(Color.parseColor("#AAAAAA"))
            textSize = 12f
            setPadding(PAD, 0, PAD, 16)
        }
        val status = TextView(ctx).apply {
            text = "Loading…"
            setTextColor(Color.parseColor(COLOR_GRAY))
            textSize = 15f
            setPadding(PAD, 28, PAD, 28)
            setBackgroundColor(Color.parseColor("#1C1C22"))
        }
        statusView = status
        val wv = WebView(ctx)
        wv.layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            0,
            1f
        )
        webView = wv

        val reload = Button(ctx).apply {
            text = "Reload"
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.parseColor("#1C1C22"))
            setOnClickListener {
                setStatus("Reloading…", COLOR_GRAY)
                wv.reload()
            }
        }
        val save = Button(ctx).apply {
            text = "Save & Close"
            setTextColor(Color.parseColor("#101014"))
            setBackgroundColor(Color.parseColor("#FF9800"))
            setOnClickListener {
                try {
                    CloudflareSolver.saveCookies(SITE_URL, Pornhoarder.USER_AGENT)
                    CookieManager.getInstance().flush()
                } catch (_: Exception) {
                }
                dismissAllowingStateLoss()
            }
        }
        val buttons = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(16, 8, 16, 20)
            addView(reload, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(save, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        }
        return LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#121212"))
            setPadding(16, 16, 16, 16)
            addView(
                title,
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )
            )
            addView(
                subtitle,
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )
            )
            addView(
                status,
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { setMargins(PAD, 0, PAD, 8) }
            )
            addView(wv)
            addView(
                buttons,
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )
            )
        }
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
        val wv = webView ?: return
        val status = statusView ?: return
        try {
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
        } catch (_: Exception) {
        }
        wv.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                setStatus("Page loaded — checking for challenge…", COLOR_GRAY)
                startDetection(wv)
            }
        }
        wv.loadUrl(SITE_URL)
    }

    private fun setStatus(text: String, colorHex: String) {
        statusView?.let {
            it.text = text
            try {
                it.setTextColor(Color.parseColor(colorHex))
            } catch (_: Exception) {
            }
        }
    }

    /** Polls page state: solved (cf_clearance) vs challenge visible vs clean.
     * Never auto-closes: the user taps Save & Close manually. */
    private fun startDetection(webView: WebView) {
        stopDetection()
        val task = object : Runnable {
            override fun run() {
                try {
                    val cookies = CookieManager.getInstance().getCookie(SITE_URL).orEmpty()
                    if (cookies.contains("cf_clearance")) {
                        setStatus("Solved ✓ — set orientation above, then Save & Close.", COLOR_GREEN)
                        poll = this
                        uiHandler.postDelayed(this, 5000)
                        return
                    }
                    webView.evaluateJavascript(
                        "(function(){var h=document.documentElement.innerHTML.toLowerCase();" +
                            "return h.includes(\"turnstile\")||h.includes(\"verify you are human\")" +
                            "||h.includes(\"checking your browser\")||h.includes(\"just a moment\");})();"
                    ) { res ->
                        try {
                            val challenged = res?.contains("true") == true
                            if (challenged) {
                                setStatus(
                                    "Challenge detected — tap the checkbox in the page above.",
                                    COLOR_AMBER
                                )
                            } else {
                                setStatus(
                                    "No challenge on this page — Save & Close, then try the extension.",
                                    COLOR_GRAY
                                )
                            }
                        } catch (_: Exception) {
                        }
                        poll = this
                        uiHandler.postDelayed(this, 2500)
                    }
                } catch (_: Exception) {
                    poll = this
                    uiHandler.postDelayed(this, 2500)
                }
            }
        }
        uiHandler.postDelayed(task, 1500)
    }

    private fun stopDetection() {
        try {
            poll?.let { uiHandler.removeCallbacks(it) }
        } catch (_: Exception) {
        }
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
        statusView = null
        try {
            MainActivity.reloadHomeEvent.invoke(true)
        } catch (_: Exception) {
        }
    }
}
