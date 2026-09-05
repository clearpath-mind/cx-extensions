package com.clearpathmind

import android.R
import android.app.Activity
import android.content.Context
import android.content.SharedPreferences
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.MotionEvent
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine

/**
 * Hidden-WebView Cloudflare solver, ported from
 * Abodabodd/re-3arabi (Faselhd) and adapted for PornHoarder.
 *
 * Flow: run [solve] once from provider settings (needs an [Activity]),
 * solved cookies are persisted and attached to every request in [Pornhoarder].
 *
 * NOTE: [checkboxCssPath] was derived from FaselHD's challenge DOM — verify
 * and correct it against pornhoarder.tv's actual challenge page if the
 * auto-click misses.
 */
object CloudflareSolver {
    private const val TAG = "CF_Solver_PornHoarder"
    private const val PREFS = "Pornhoarder"
    private const val KEY_COOKIES = "cf_cookies"
    private const val KEY_UA = "cf_user_agent"

    /** CSS path of the Turnstile checkbox container — override after recon. */
    var checkboxCssPath: String =
        "html > body > div:nth-of-type(1) > div > div:nth-of-type(2) > div"

    private var appContext: Context? = null

    fun init(context: Context) {
        appContext = context.applicationContext
    }

    private fun prefs(): SharedPreferences? =
        appContext?.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun storedCookies(): String = prefs()?.getString(KEY_COOKIES, "").orEmpty()

    fun storedUserAgent(): String? = prefs()?.getString(KEY_UA, null)

    fun saveCookies(url: String, userAgent: String? = null) {
        val cookies = CookieManager.getInstance().getCookie(url) ?: return
        prefs()?.edit()
            ?.putString(KEY_COOKIES, cookies)
            ?.apply {
                if (userAgent != null) putString(KEY_UA, userAgent)
            }?.apply()
    }

    fun clearCookies() {
        prefs()?.edit()?.remove(KEY_COOKIES)?.remove(KEY_UA)?.apply()
    }

    suspend fun solve(activity: Activity?, url: String, userAgent: String): Document? {
        return suspendCoroutine { continuation ->
            if (activity == null || activity.isFinishing) {
                continuation.resume(null)
                return@suspendCoroutine
            }
            Handler(Looper.getMainLooper()).post {
                val rootView = activity.findViewById<ViewGroup>(R.id.content) ?: run {
                    continuation.resume(null)
                    return@post
                }
                val webView = WebView(activity)
                webView.layoutParams = FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
                )
                webView.alpha = 0f
                webView.translationX = 10000f
                webView.isFocusable = false
                webView.isFocusableInTouchMode = false
                webView.isClickable = false
                webView.settings.apply {
                    javaScriptEnabled = true
                    domStorageEnabled = true
                    databaseEnabled = true
                    this.userAgentString = userAgent
                    useWideViewPort = true
                    loadWithOverviewMode = true
                }
                val cookieManager = CookieManager.getInstance()
                cookieManager.setAcceptCookie(true)
                cookieManager.setAcceptThirdPartyCookies(webView, true)

                var isSolved = false
                var isProcessingClick = false
                val pollingHandler = Handler(Looper.getMainLooper())

                fun finishSuccess(html: String?) {
                    if (!isSolved) {
                        isSolved = true
                        cookieManager.flush()
                        try {
                            pollingHandler.removeCallbacksAndMessages(null)
                            rootView.removeView(webView)
                            webView.destroy()
                        } catch (e: Exception) {
                        }
                        if (html == null) {
                            continuation.resume(null)
                            return
                        }
                        saveCookies(url, userAgent)
                        var cleanHtml = html.removeSurrounding("\"")
                            .replace("\\u003C", "<")
                            .replace("\\u003E", ">")
                            .replace("\\\"", "\"")
                            .replace("\\\\", "\\")
                        continuation.resume(Jsoup.parse(cleanHtml))
                    }
                }

                pollingHandler.postDelayed({ finishSuccess(null) }, 60000)

                fun simulateRealTouch(view: WebView, cssX: Float, cssY: Float) {
                    val density = activity.resources.displayMetrics.density
                    val realX = cssX * density
                    val realY = cssY * density
                    val downTime = SystemClock.uptimeMillis()
                    val eventTime = SystemClock.uptimeMillis() + 50
                    val downEvent = MotionEvent.obtain(
                        downTime, downTime, MotionEvent.ACTION_DOWN, realX, realY, 0
                    )
                    view.dispatchTouchEvent(downEvent)
                    view.postDelayed({
                        val upEvent = MotionEvent.obtain(
                            downTime, eventTime, MotionEvent.ACTION_UP, realX, realY, 0
                        )
                        view.dispatchTouchEvent(upEvent)
                        downEvent.recycle()
                        upEvent.recycle()
                    }, 50)
                }

                fun startPolling() {
                    val runnable = object : Runnable {
                        override fun run() {
                            if (isSolved || isProcessingClick) {
                                pollingHandler.postDelayed(this, 2000)
                                return
                            }
                            val jsGetCoords = """
                                (function(){
                                    try{
                                        var box = document.querySelector("$checkboxCssPath");
                                        if(!box) return "NO_BOX";
                                        var r = box.getBoundingClientRect();
                                        if(r.width === 0 && r.height === 0) return "NO_BOX";
                                        var size = Math.min(36, Math.max(18, Math.round(r.height * 0.55)));
                                        var margin = Math.round(Math.max(8, r.width * 0.03));
                                        var centerY = r.top + (r.height / 2);
                                        var rightSideX = r.right - (size / 2) - margin;
                                        var leftSideX = r.left + (size / 2) + margin;
                                        return rightSideX + "," + centerY + "|" + leftSideX + "," + centerY;
                                    }catch(e){ return "ERROR"; }
                                })();
                            """.trimIndent()
                            webView.evaluateJavascript(jsGetCoords) { res ->
                                try {
                                    val clean = res?.removeSurrounding("\"")
                                    if (clean != null && clean.contains("|")) {
                                        isProcessingClick = true
                                        val sides = clean.split("|")
                                        val (rx, ry) = sides[0].split(",").map { it.toFloatOrNull() }
                                        val (lx, ly) = sides[1].split(",").map { it.toFloatOrNull() }
                                        if (rx != null && ry != null && lx != null && ly != null) {
                                            simulateRealTouch(webView, rx, ry)
                                            pollingHandler.postDelayed({
                                                simulateRealTouch(webView, lx, ly)
                                                pollingHandler.postDelayed({ isProcessingClick = false }, 3000)
                                            }, 250)
                                        } else {
                                            isProcessingClick = false
                                        }
                                    }
                                } catch (e: Exception) {
                                    isProcessingClick = false
                                }
                            }
                            pollingHandler.postDelayed(this, 2000)
                        }
                    }
                    pollingHandler.post(runnable)
                }

                var lastUrl: String? = null
                var stableSince = 0L
                var fetched = false
                fun waitUntilReady() {
                    if (isSolved) return
                    val js = """
                        (function(){
                            try{
                                var hasBox = document.querySelector("$checkboxCssPath") != null;
                                var html = document.documentElement.innerHTML || "";
                                var stillCloudflare = html.toLowerCase().includes("cloudflare") || html.toLowerCase().includes("checking your browser");
                                return location.href + "|" + document.readyState + "|" + hasBox + "|" + stillCloudflare;
                            }catch(e){ return location.href + "|loading|false|true"; }
                        })();
                    """.trimIndent()
                    webView.evaluateJavascript(js) { res ->
                        if (res == null) {
                            pollingHandler.postDelayed({ waitUntilReady() }, 200)
                            return@evaluateJavascript
                        }
                        val parts = res.replace("\"", "").split("|")
                        if (parts.size < 4) {
                            pollingHandler.postDelayed({ waitUntilReady() }, 200)
                            return@evaluateJavascript
                        }
                        val (currentUrl, ready, hasBox, stillCloudflare) = parts
                        val now = SystemClock.uptimeMillis()
                        if (currentUrl != lastUrl) {
                            lastUrl = currentUrl
                            stableSince = now
                        }
                        val stableTime = now - stableSince
                        if (hasBox == "false" && stillCloudflare == "false" && ready == "complete" && stableTime > 1500 && !fetched) {
                            fetched = true
                            pollingHandler.postDelayed({
                                webView.evaluateJavascript("document.documentElement.outerHTML") { html ->
                                    finishSuccess(html)
                                }
                            }, 500)
                            return@evaluateJavascript
                        }
                        pollingHandler.postDelayed({ waitUntilReady() }, 200)
                    }
                }
                webView.webViewClient = object : WebViewClient() {
                    override fun onPageFinished(view: WebView?, url: String?) {
                        super.onPageFinished(view, url)
                        isProcessingClick = false
                        startPolling()
                        waitUntilReady()
                    }
                }
                rootView.addView(webView)
                webView.loadUrl(url)
            }
        }
    }
}
