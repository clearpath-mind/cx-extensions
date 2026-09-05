package com.clearpathmind

import android.app.AlertDialog
import android.os.Handler
import android.os.Looper
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

/**
 * Manual Cloudflare solver: shows the challenge page in a visible WebView so
 * the user can tap "Verify you are human" themselves, then persists the
 * resulting cookies via [CloudflareSolver]. [Pornhoarder] attaches them to
 * every request.
 */
class CfSettingsDialog(
    private val activity: AppCompatActivity,
    private val siteUrl: String = "https://ww3.pornhoarder.org"
) {
    private val uiHandler = Handler(Looper.getMainLooper())
    private var poll: Runnable? = null

    /** Polls page state: solved (cf_clearance) vs challenge visible vs clean. */
    private fun startDetection(webView: WebView, status: TextView) {
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
                        "Challenge detected — tap the \"Verify you are human\" checkbox in the page."
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
    fun show() {
        val status = TextView(activity).apply {
            text = "Solve the challenge below, then close. Cookies are saved automatically."
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
        webView.loadUrl(siteUrl)
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
        }
        AlertDialog.Builder(activity)
            .setTitle("PornHoarder — Cloudflare check")
            .setView(layout)
            .setPositiveButton("Save & Close") { dialog, _ ->
                CloudflareSolver.saveCookies(siteUrl, Pornhoarder.USER_AGENT)
                CookieManager.getInstance().flush()
                dialog.dismiss()
            }
            .setNegativeButton("Close") { dialog, _ ->
                CloudflareSolver.saveCookies(siteUrl, Pornhoarder.USER_AGENT)
                CookieManager.getInstance().flush()
                dialog.dismiss()
            }
            .setOnDismissListener {
                stopDetection()
                try {
                    layout.removeView(webView)
                    webView.destroy()
                } catch (_: Exception) {
                }
            }
            .show()
    }
}
