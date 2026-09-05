package com.clearpathmind

import android.app.Dialog
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
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
 * Manual Cloudflare solver: shows the challenge page in a full-screen dialog
 * with an "Auto-tap checkbox" button that taps the Turnstile widget
 * coordinates directly (no need to find it visually). Solved cookies are
 * persisted via [CloudflareSolver] and attached to every [Pornhoarder] request.
 */
class CfSettingsDialog(
    private val activity: AppCompatActivity,
    private val siteUrl: String = "https://ww3.pornhoarder.org"
) {
    private val uiHandler = Handler(Looper.getMainLooper())
    private var poll: Runnable? = null

    fun show() {
        val status = TextView(activity).apply {
            text = "Loading challenge page…"
            setPadding(32, 24, 32, 24)
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
                status.text = "Page loaded — checking for challenge…"
                startDetection(webView, status)
            }
        }

        val autoTap = Button(activity).apply {
            text = "Auto-tap checkbox"
            setOnClickListener { autoTapCheckbox(webView, status) }
        }
        val reload = Button(activity).apply {
            text = "Reload"
            setOnClickListener {
                status.text = "Reloading…"
                webView.reload()
            }
        }

        var dialog: Dialog? = null
        val save = Button(activity).apply {
            text = "Save & Close"
            setOnClickListener {
                CloudflareSolver.saveCookies(siteUrl, Pornhoarder.USER_AGENT)
                CookieManager.getInstance().flush()
                dialog?.dismiss()
            }
        }
        val buttons = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(autoTap, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(reload, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(save, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        }
        val layout = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            addView(
                status,
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )
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
            setTitle("PornHoarder — Cloudflare check")
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

    /** Finds the Turnstile iframe center and dispatches a real tap on it. */
    private fun autoTapCheckbox(webView: WebView, status: TextView) {
        status.text = "Looking for checkbox…"
        webView.evaluateJavascript(
            "(function(){var f=document.querySelector('iframe[src*=\"challenges.cloudflare.com\"]');" +
                "if(!f)return \"NO_IFRAME\";var r=f.getBoundingClientRect();" +
                "if(r.width===0&&r.height===0)return \"HIDDEN\";" +
                "return (r.left+r.width/2)+\",\"+(r.top+r.height/2);})();"
        ) { res ->
            val clean = res?.removeSurrounding("\"").orEmpty()
            val cx = clean.substringBefore(",").toFloatOrNull()
            val cy = clean.substringAfter(",", "").toFloatOrNull()
            if (cx == null || cy == null) {
                status.text = "Checkbox not tappable ($clean) — Reload and try again."
                return@evaluateJavascript
            }
            val density = activity.resources.displayMetrics.density
            val realX = cx * density
            val realY = cy * density
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
                status.text = "Tapped — waiting for clearance…"
            }, 120)
        }
    }

    /** Polls page state: solved (cf_clearance) vs challenge visible vs clean. */
    private fun startDetection(webView: WebView, status: TextView) {
        stopDetection()
        val task = object : Runnable {
            override fun run() {
                val cookies = CookieManager.getInstance().getCookie(siteUrl).orEmpty()
                if (cookies.contains("cf_clearance")) {
                    status.text = "Solved ✓ — tap Save & Close."
                    return
                }
                webView.evaluateJavascript(
                    "(function(){var h=document.documentElement.innerHTML.toLowerCase();" +
                        "return h.includes(\"turnstile\")||h.includes(\"verify you are human\")" +
                        "||h.includes(\"checking your browser\")||h.includes(\"just a moment\");})();"
                ) { res ->
                    val challenged = res?.contains("true") == true
                    status.text = if (challenged)
                        "Challenge detected — tap Auto-tap checkbox (or tap the widget if you see it)."
                    else
                        "No challenge on this page — Save & Close, then try the extension."
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
