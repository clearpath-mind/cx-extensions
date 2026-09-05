package com.clearpathmind

import android.app.AlertDialog
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
    fun show() {
        val status = TextView(activity).apply {
            text = "Solve the challenge below, then close. Cookies are saved automatically."
            setPadding(32, 24, 32, 24)
        }
        val webView = WebView(activity).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                0,
                1f
            )
            settings.apply {
                javaScriptEnabled = true
                domStorageEnabled = true
                databaseEnabled = true
                userAgentString = Pornhoarder.USER_AGENT
                useWideViewPort = true
                loadWithOverviewMode = true
            }
            CookieManager.getInstance().apply {
                setAcceptCookie(true)
                setAcceptThirdPartyCookies(this@apply, true)
            }
            webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView?, url: String?) {
                    super.onPageFinished(view, url)
                    status.text = "Page loaded. Tap \"Verify you are human\" if shown, then close."
                }
            }
            loadUrl(siteUrl)
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
                try {
                    layout.removeView(webView)
                    webView.destroy()
                } catch (_: Exception) {
                }
            }
            .show()
    }
}
