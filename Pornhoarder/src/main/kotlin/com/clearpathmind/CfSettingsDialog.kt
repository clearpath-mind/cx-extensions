package com.clearpathmind

import android.app.Dialog
import android.graphics.Color
import android.graphics.Typeface
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Gravity
import android.view.MotionEvent
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

/**
 * Cloudflare solver dialog: shows the challenge page full-screen with an
 * "Auto-tap" button that scrolls the Turnstile widget into view and taps it
 * (up to 3 attempts). Solved cookies are persisted via [CloudflareSolver]
 * and attached to every [Pornhoarder] request.
 */
class CfSettingsDialog(
    private val activity: AppCompatActivity,
    private val siteUrl: String = "https://ww3.pornhoarder.org"
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
            text = "Cloudflare check"
            setTextColor(Color.parseColor(Style.DIM))
            textSize = 14f
            setPadding(Style.PAD, 0, Style.PAD, 16)
        }
        val status = TextView(activity).apply {
            text = "Loading challenge page…"
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
        webView.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                setStatus(status, "Page loaded — checking for challenge…", Style.GRAY)
                startDetection(webView, status)
            }
        }

        var dialog: Dialog? = null
        val autoTap = styledButton("Auto-tap") {
            autoTapCheckbox(webView, status, attemptsLeft = 3)
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
            setPadding(16, 20, 16, 20)
            addView(autoTap, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
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
                ).apply { setMargins(Style.PAD, 0, Style.PAD, 16) }
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

    /**
     * Finds the Turnstile widget (multiple selector fallbacks), scrolls it
     * into view, waits for layout, then dispatches a real tap. Retries with
     * status updates so failures are visible instead of silent.
     */
    private fun autoTapCheckbox(webView: WebView, status: TextView, attemptsLeft: Int) {
        if (attemptsLeft <= 0) {
            setStatus(
                status,
                "Auto-tap failed after 3 tries — tap the widget manually, then Save & Close.",
                Style.AMBER
            )
            return
        }
        setStatus(status, "Looking for checkbox… (try ${4 - attemptsLeft}/3)", Style.GRAY)
        webView.evaluateJavascript(FIND_WIDGET_JS) { res ->
            val clean = res?.removeSurrounding("\"").orEmpty()
            if (clean == "NO_WIDGET") {
                setStatus(
                    status,
                    "No Turnstile widget in page — Reload and try again.",
                    Style.AMBER
                )
                return@evaluateJavascript
            }
            val cx = clean.substringBefore(",").toFloatOrNull()
            val cy = clean.substringAfter(",", "").toFloatOrNull()
            if (cx == null || cy == null) {
                setStatus(status, "Widget not laid out yet ($clean) — retrying…", Style.GRAY)
                uiHandler.postDelayed({ autoTapCheckbox(webView, status, attemptsLeft - 1) }, 1500)
                return@evaluateJavascript
            }
            // Scroll it into the middle of the viewport, then tap after layout settles.
            webView.evaluateJavascript(SCROLL_WIDGET_JS) {}
            uiHandler.postDelayed({
                webView.evaluateJavascript(FIND_WIDGET_JS) { res2 ->
                    val c2 = res2?.removeSurrounding("\"").orEmpty()
                    val x = c2.substringBefore(",").toFloatOrNull() ?: cx
                    val y = c2.substringAfter(",", "").toFloatOrNull() ?: cy
                    dispatchTap(webView, x, y)
                    setStatus(status, "Tapped — waiting for clearance…", Style.GRAY)
                    uiHandler.postDelayed({
                        val cookies = CookieManager.getInstance().getCookie(siteUrl).orEmpty()
                        if (cookies.contains("cf_clearance")) {
                            setStatus(status, "Solved ✓ — tap Save & Close.", Style.GREEN)
                        } else {
                            autoTapCheckbox(webView, status, attemptsLeft - 1)
                        }
                    }, 3000)
                }
            }, 900)
        }
    }

    private fun dispatchTap(webView: WebView, cssX: Float, cssY: Float) {
        val density = activity.resources.displayMetrics.density
        val realX = cssX * density
        val realY = cssY * density
        val downTime = SystemClock.uptimeMillis()
        val down = MotionEvent.obtain(
            downTime, downTime, MotionEvent.ACTION_DOWN, realX, realY, 0
        )
        webView.dispatchTouchEvent(down)
        webView.postDelayed({
            val up = MotionEvent.obtain(
                downTime, SystemClock.uptimeMillis(), MotionEvent.ACTION_UP, realX, realY, 0
            )
            webView.dispatchTouchEvent(up)
            down.recycle()
            up.recycle()
        }, 150)
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
                            "Challenge detected — tap Auto-tap (or the widget if you see it).",
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

    companion object {
        private const val WIDGET_SELECTORS =
            "document.querySelector('iframe[src*=\"challenges.cloudflare.com\"]')" +
                "||document.querySelector('iframe[src*=\"turnstile\"]')" +
                "||document.querySelector('.cf-turnstile iframe')" +
                "||document.querySelector('#cf-turnstile iframe')"
        private const val FIND_WIDGET_JS =
            "(function(){var f=$WIDGET_SELECTORS;" +
                "if(!f)return \"NO_WIDGET\";var r=f.getBoundingClientRect();" +
                "if(r.width===0&&r.height===0)return \"0,0\";" +
                "return (r.left+r.width/2)+\",\"+(r.top+r.height/2);})();"
        private const val SCROLL_WIDGET_JS =
            "(function(){var f=$WIDGET_SELECTORS;" +
                "if(f){f.scrollIntoView({block:\"center\"});}return \"ok\";})();"
    }
}
