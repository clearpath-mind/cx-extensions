package com.clearpathmind

import android.app.Dialog
import android.graphics.Color
import android.graphics.Typeface
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

/**
 * PornHoarder settings: full-screen WebView opening the site settings page
 * (/settings/) so the Cloudflare challenge can be solved and the sexual
 * orientation set to Straight directly. Nothing auto-closes: tap
 * Save & Close yourself — cookies are persisted via [CloudflareSolver]
 * and attached to every [Pornhoarder] request.
 */
class CfSettingsDialog(
    private val activity: AppCompatActivity,
    private val siteUrl: String = "https://ww8.pornhoarder.org/settings/"
) {
    private val uiHandler = Handler(Looper.getMainLooper())
    private var poll: Runnable? = null

    private object Style {
        const val BG = "#101014"
        const val CARD = "#1C1C22"
        const val ACCENT = "#FF9800"
        const val TEXT = "#F5F5F5"
        const val DIM = "#9E9E9E"
        const val GREEN = "#4CAF50"
        const val AMBER = "#FFC107"
        const val GRAY = "#BDBDBD"
        const val PAD = 40
    }

    fun show() {
        val title = TextView(activity).apply {
            text = "PornHoarder"
            setTextColor(Color.parseColor(Style.TEXT))
            textSize = 22f
            typeface = Typeface.DEFAULT_BOLD
            setPadding(Style.PAD, Style.PAD, Style.PAD, 4)
        }
        val subtitle = TextView(activity).apply {
            text = "Solve the challenge, set orientation to Straight, then Save & Close"
            setTextColor(Color.parseColor(Style.DIM))
            textSize = 14f
            setPadding(Style.PAD, 0, Style.PAD, 16)
        }
        val status = TextView(activity).apply {
            text = "Loading…"
            setTextColor(Color.parseColor(Style.GRAY))
            textSize = 15f
            setPadding(Style.PAD, 28, Style.PAD, 28)
            setBackgroundColor(Color.parseColor(Style.CARD))
        }
        val webView = WebView(activity)
        webView.layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            0,
            1f
        )
        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true
            userAgentString = Pornhoarder.USER_AGENT
            useWideViewPort = true
            loadWithOverviewMode = true
        }
        CookieManager.getInstance().apply {
            setAcceptCookie(true)
            setAcceptThirdPartyCookies(webView, true)
        }
        var dialog: Dialog? = null
        webView.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                setStatus(status, "Page loaded — checking for challenge…", Style.GRAY)
                startDetection(webView, status)
            }
        }

        val reload = styledButton("Reload") {
            setStatus(status, "Reloading…", Style.GRAY)
            webView.reload()
        }
        val save = styledButton("Save & Close", accent = true) {
            CloudflareSolver.saveCookies(siteUrl, Pornhoarder.USER_AGENT)
            CookieManager.getInstance().flush()
            dialog?.dismiss()
        }
        val buttons = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(16, 8, 16, 20)
            addView(reload, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(save, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        }
        val layout = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor(Style.BG))
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
                ).apply { setMargins(Style.PAD, 0, Style.PAD, 8) }
            )
            addView(webView)
            addView(
                buttons,
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )
            )
        }
        dialog = Dialog(activity).apply {
            setContentView(layout)
            window?.setLayout(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            setOnDismissListener {
                stopDetection()
                try {
                    layout.removeView(webView)
                    webView.destroy()
                } catch (_: Exception) {
                }
            }
        }
        webView.loadUrl(siteUrl)
        dialog.show()
    }

    private fun styledButton(label: String, accent: Boolean = false, onClick: () -> Unit): Button {
        return Button(activity).apply {
            text = label
            setTextColor(
                if (accent) Color.parseColor("#101014")
                else Color.parseColor(Style.TEXT)
            )
            setBackgroundColor(
                if (accent) Color.parseColor(Style.ACCENT)
                else Color.parseColor(Style.CARD)
            )
            setOnClickListener { onClick() }
        }
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
                val cookies = CookieManager.getInstance().getCookie(siteUrl).orEmpty()
                if (cookies.contains("cf_clearance")) {
                    setStatus(status, "Solved ✓ — set orientation above, then Save & Close.", Style.GREEN)
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
                            Style.AMBER
                        )
                    } else {
                        setStatus(
                            status,
                            "No challenge on this page — Save & Close, then try the extension.",
                            Style.GRAY
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
}
