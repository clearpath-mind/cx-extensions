package com.clearpathmind

import android.content.DialogInterface
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.fragment.app.DialogFragment
import com.lagradost.cloudstream3.MainActivity

/**
 * PornHoarder settings as a DialogFragment with a MangoAyarlar-style rounded
 * floating dialog. Opens the site settings page (/settings/) for the
 * Cloudflare challenge.
 *
 * Auto-click (SimpCityLogin pattern): focusable WebView, popup handling via
 * WebChromeClient.onCreateWindow, and in-page JS simulateClick on the
 * Turnstile widget — up to 5 automatic attempts when a challenge is
 * detected. Manual tapping always works in parallel. Manual Save & Close
 * persists cookies via [CloudflareSolver].
 */
class CfSettingsFragment : DialogFragment() {
    private val uiHandler = Handler(Looper.getMainLooper())
    private var poll: Runnable? = null
    private var webView: WebView? = null
    private var statusView: TextView? = null

    companion object {
        const val SITE_URL = "https://ww8.pornhoarder.org/settings/"
        const val COLOR_BG = "#121212"
        const val COLOR_CARD = "#1C1C22"
        const val COLOR_ACCENT = "#FF9800"
        const val COLOR_FOCUS = "#3A3A44"
        const val COLOR_GREEN = "#4CAF50"
        const val COLOR_AMBER = "#FFC107"
        const val COLOR_GRAY = "#BDBDBD"

        private const val CHECK_CHALLENGE_JS =
            "(function(){var h=document.documentElement.innerHTML.toLowerCase();" +
                "return h.includes(\"turnstile\")||h.includes(\"verify you are human\")" +
                "||h.includes(\"checking your browser\")||h.includes(\"just a moment\");})();"
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
            gravity = Gravity.CENTER
            setPadding(0, 24, 0, 8)
        }
        val subtitle = TextView(ctx).apply {
            text = "Solve the challenge, set orientation to Straight, then Save & Close"
            setTextColor(Color.parseColor("#AAAAAA"))
            textSize = 12f
            gravity = Gravity.CENTER
            setPadding(32, 0, 32, 16)
        }
        val status = TextView(ctx).apply {
            text = "Loading…"
            setTextColor(Color.parseColor(COLOR_GRAY))
            textSize = 15f
            gravity = Gravity.CENTER
            setPadding(32, 28, 32, 28)
            background = cardDrawable(Color.parseColor(COLOR_CARD))
        }
        statusView = status
        val wv = WebView(ctx)
        // Fixed height inside the dialog window: large enough to use,
        // small enough to leave title/status/buttons visible.
        val wvHeight = (ctx.resources.displayMetrics.heightPixels * 0.45).toInt()
        wv.layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            wvHeight
        )
        webView = wv

        val reload = actionButton("RELOAD", COLOR_CARD) {
            setStatus("Reloading…", COLOR_GRAY)
            wv.reload()
        }
        val save = actionButton("SAVE & CLOSE", COLOR_ACCENT, darkText = true) {
            try {
                CloudflareSolver.saveCookies(SITE_URL, Pornhoarder.USER_AGENT)
                CookieManager.getInstance().flush()
            } catch (_: Exception) {
            }
            dismissAllowingStateLoss()
        }
        val buttons = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(20, 12, 20, 20)
            addView(reload, LinearLayout.LayoutParams(0, dp(50), 1f).apply {
                marginEnd = 20
            })
            addView(save, LinearLayout.LayoutParams(0, dp(50), 1f).apply {
                marginStart = 20
            })
        }
        return LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
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
                ).apply { setMargins(20, 0, 20, 8) }
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

    private fun dp(v: Int): Int =
        (v * (activity?.resources?.displayMetrics?.density ?: 1f)).toInt()

    private fun cardDrawable(color: Int): GradientDrawable = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        setColor(color)
        cornerRadius = 16f
    }

    /** MangoAyarlar-style button: bold text, rounded, white-stroke focused state. */
    private fun actionButton(
        label: String,
        colorHex: String,
        darkText: Boolean = false,
        onClick: () -> Unit
    ): Button {
        return Button(requireContext()).apply {
            text = label
            setTextColor(if (darkText) Color.parseColor("#101014") else Color.WHITE)
            setTypeface(null, Typeface.BOLD)
            textSize = 14f
            background = StateListDrawable().apply {
                addState(intArrayOf(android.R.attr.state_focused), GradientDrawable().apply {
                    setColor(Color.parseColor(COLOR_FOCUS))
                    cornerRadius = 16f
                    setStroke(4, Color.WHITE)
                })
                addState(intArrayOf(), GradientDrawable().apply {
                    setColor(Color.parseColor(colorHex))
                    cornerRadius = 16f
                })
            }
            setOnClickListener { onClick() }
        }
    }

    override fun onStart() {
        super.onStart()
        dialog?.window?.apply {
            activity?.let {
                val dm = it.resources.displayMetrics
                setLayout((dm.widthPixels * 0.92).toInt(), (dm.heightPixels * 0.88).toInt())
            }
            setBackgroundDrawable(GradientDrawable().apply {
                setColor(Color.parseColor(COLOR_BG))
                cornerRadius = 32f
                setStroke(3, Color.parseColor(COLOR_ACCENT))
            })
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        val wv = webView ?: return
        try {
            wv.settings.apply {
                javaScriptEnabled = true
                domStorageEnabled = true
                databaseEnabled = true
                javaScriptCanOpenWindowsAutomatically = true
                setSupportMultipleWindows(true)
                builtInZoomControls = true
                displayZoomControls = false
                setNeedInitialFocus(true)
                userAgentString = Pornhoarder.USER_AGENT
                useWideViewPort = true
                loadWithOverviewMode = true
            }
            wv.isFocusable = true
            wv.isFocusableInTouchMode = true
            wv.setBackgroundColor(Color.BLACK)
            CookieManager.getInstance().apply {
                setAcceptCookie(true)
                setAcceptThirdPartyCookies(wv, true)
                // Baked Straight-config cookies for every known mirror host.
                for (host in Pornhoarder.MIRROR_HOSTS) {
                    for ((name, value) in Pornhoarder.BAKED_COOKIES) {
                        try {
                            setCookie(host, "$name=$value; path=/")
                        } catch (_: Exception) {
                        }
                    }
                }
            }
        } catch (_: Exception) {
        }
        // Turnstile sometimes opens a popup window; keep it in the same view.
        wv.webChromeClient = object : WebChromeClient() {
            override fun onCreateWindow(
                view: WebView?,
                isDialog: Boolean,
                isUserGesture: Boolean,
                resultMsg: android.os.Message?
            ): Boolean {
                val transport = resultMsg?.obj as? WebView.WebViewTransport ?: return false
                transport.webView = view
                resultMsg.sendToTarget()
                return true
            }
        }
        // TV/DPAD: synthesize clicks on the focused element.
        wv.setOnKeyListener { _, keyCode, event ->
            if (event.action == KeyEvent.ACTION_DOWN &&
                (keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER)
            ) {
                wv.evaluateJavascript(
                    """(function(){var el=document.activeElement;if(!el)return;
                    |var o={bubbles:true,cancelable:true,view:window};
                    |el.dispatchEvent(new MouseEvent('mousedown',o));
                    |el.dispatchEvent(new MouseEvent('mouseup',o));
                    |el.dispatchEvent(new MouseEvent('click',o));})();""".trimMargin(),
                    null
                )
                true
            } else false
        }
        wv.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                setStatus("Page loaded — checking for challenge…", COLOR_GRAY)
                startDetection(wv)
            }
        }
        wv.loadUrl(SITE_URL)
        dialog?.setOnShowListener { wv.requestFocus() }
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

    /** Passive challenge monitor: auto Save & Close shortly after clearance. */
    private fun startDetection(webView: WebView) {
        stopDetection()
        val task = object : Runnable {
            override fun run() {
                try {
                    val cookies = CookieManager.getInstance().getCookie(SITE_URL).orEmpty()
                    if (cookies.contains("cf_clearance")) {
                        setStatus("Solved ✓ — saving & closing…", COLOR_GREEN)
                        try {
                            CloudflareSolver.saveCookies(SITE_URL, Pornhoarder.USER_AGENT)
                            CookieManager.getInstance().flush()
                        } catch (_: Exception) {
                        }
                        stopDetection()
                        uiHandler.postDelayed({ dismissAllowingStateLoss() }, 1200)
                        return
                    }
                    webView.evaluateJavascript(CHECK_CHALLENGE_JS) { res ->
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
