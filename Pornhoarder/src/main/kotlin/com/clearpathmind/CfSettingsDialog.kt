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
 * PornHoarder settings: full-screen WebView for the Cloudflare check plus
 * orientation quick-set buttons (Straight / Bi / Gay) that tap the site's
 * own orientation control. Cookies (including the orientation choice) are
 * persisted via [CloudflareSolver] and attached to every [Pornhoarder] request.
 */
class CfSettingsDialog(
    private val activity: AppCompatActivity,
    private val siteUrl: String = "https://ww8.pornhoarder.org"
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
            text = "Cloudflare check & orientation"
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
        val orientationLabel = TextView(activity).apply {
            text = "My sexual orientation"
            setTextColor(Color.parseColor(Style.DIM))
            textSize = 14f
            setPadding(Style.PAD, 20, Style.PAD, 8)
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
        webView.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                setStatus(status, "Page loaded — checking for challenge…", Style.GRAY)
                startDetection(webView, status)
            }
        }

        var dialog: Dialog? = null
        val straight = styledButton("I'm Straight") { setOrientation(webView, status, "straight") }
        val bi = styledButton("Bi") { setOrientation(webView, status, "bi") }
        val gay = styledButton("Gay") { setOrientation(webView, status, "gay") }
        val orientationRow = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(16, 0, 16, 8)
            addView(straight, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(bi, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(gay, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
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
                orientationLabel,
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )
            )
            addView(
                orientationRow,
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )
            )
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

    /**
     * Taps the site's own orientation control by matching clickable elements
     * ("I'm Straight" / "Bi" / "Gay"). The site stores the choice itself;
     * Save & Close persists it with the cookies.
     */
    private fun setOrientation(webView: WebView, status: TextView, want: String) {
        setStatus(status, "Setting orientation: $want…", Style.GRAY)
        webView.evaluateJavascript(
            "(function(){var want=\"$want\";" +
                "var els=document.querySelectorAll('a,button,input[type=button],input[type=submit]');" +
                "for(var i=0;i<els.length;i++){" +
                "var t=((els[i].innerText||els[i].value||'').trim().toLowerCase());" +
                "if(t.length>40)continue;" +
                "var hit=(want==='straight')?t.indexOf('straight')>=0:t===want;" +
                "if(hit){els[i].click();return 'tapped:'+t;}}" +
                "return 'notfound';})();"
        ) { res ->
            val clean = res?.removeSurrounding("\"").orEmpty()
            if (clean.startsWith("tapped:")) {
                setStatus(
                    status,
                    "Orientation set (${clean.removePrefix("tapped:")}) — Save & Close to keep it.",
                    Style.GREEN
                )
            } else {
                setStatus(
                    status,
                    "Orientation option not found on this page — set it in the page above, then Save & Close.",
                    Style.AMBER
                )
            }
        }
    }

    /** Polls page state: solved (cf_clearance) vs challenge visible vs clean. */
    private fun startDetection(webView: WebView, status: TextView) {
        stopDetection()
        val task = object : Runnable {
            override fun run() {
                val cookies = CookieManager.getInstance().getCookie(siteUrl).orEmpty()
                if (cookies.contains("cf_clearance")) {
                    setStatus(status, "Solved ✓ — tap Save & Close.", Style.GREEN)
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
