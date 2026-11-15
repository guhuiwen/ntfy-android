package io.heckel.ntfy.xiaogou

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.View
import android.webkit.ConsoleMessage
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import org.json.JSONObject

/**
 * 提醒卡片的那张网页（服务器上的 /quick/remind.html）。悬浮窗和锁屏页都用它，装法只写这一份。
 *
 * 网页 ↔ 这里（window.XiaogouHost）：
 *   网页 → 这里：ready() 页面好了 · resize(高度) · done(动作)   动作 = ok（知道了）/ snooze（稍后提醒）/ hide
 *   这里 → 网页：ddRemind.show({…}) · ddRemind.hide()
 */
@SuppressLint("SetJavaScriptEnabled")
class RemindPage(context: Context, private val origin: String, private val listener: Listener) {
    interface Listener {
        fun onPageReady()
        fun onPageResize(heightCss: Int)
        fun onPageDone(action: String)
        fun onPageFailed(code: Int)
    }

    private val main = Handler(Looper.getMainLooper())
    private val debug = QuickPrefs.webDebug(context)
    private var destroyed = false
    val view: WebView = WebView(context)

    init {
        WebView.setWebContentsDebuggingEnabled(debug)
        CookieManager.getInstance().setAcceptCookie(true)
        view.setBackgroundColor(Color.TRANSPARENT)
        view.overScrollMode = View.OVER_SCROLL_NEVER
        view.isVerticalScrollBarEnabled = false
        view.isHorizontalScrollBarEnabled = false
        val settings = view.settings
        settings.javaScriptEnabled = true
        settings.domStorageEnabled = true
        settings.mediaPlaybackRequiresUserGesture = false        // 他的声音要能自己放出来
        settings.cacheMode = WebSettings.LOAD_DEFAULT
        settings.setSupportMultipleWindows(false)
        settings.setSupportZoom(false)
        settings.textZoom = 100
        settings.userAgentString = settings.userAgentString + " XiaogouQuick/" + QuickPrefs.BUILD
        view.addJavascriptInterface(Bridge(), "XiaogouHost")
        view.webViewClient = Client()
        view.webChromeClient = Chrome()
        view.loadUrl(origin + PAGE, mapOf("Cache-Control" to "max-age=0"))
    }

    fun show(data: JSONObject) {
        if (destroyed) return
        try {
            view.evaluateJavascript("window.ddRemind && ddRemind.show(" + data.toString() + ")", null)
        } catch (e: Exception) {
            Log.w(TAG, "remind show failed: ${e.message}")
        }
    }

    fun hide() {
        if (destroyed) return
        try {
            view.evaluateJavascript("window.ddRemind && ddRemind.hide()", null)
        } catch (_: Exception) {
        }
    }

    fun destroy() {
        if (destroyed) return
        destroyed = true
        main.removeCallbacksAndMessages(null)
        try {
            view.stopLoading()
            view.removeJavascriptInterface("XiaogouHost")
            view.destroy()
        } catch (e: Exception) {
            Log.w(TAG, "destroying remind page failed: ${e.message}")
        }
    }

    private fun post(block: () -> Unit) {
        main.post { if (!destroyed) block() }
    }

    inner class Bridge {
        @JavascriptInterface
        fun ready() {
            post { listener.onPageReady() }
        }

        @JavascriptInterface
        fun resize(height: Int) {
            post { listener.onPageResize(height) }
        }

        @JavascriptInterface
        fun done(action: String?) {
            post { listener.onPageDone(action ?: "") }
        }
    }

    private inner class Client : WebViewClient() {
        override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
            val url = request?.url?.toString() ?: return true
            return !url.startsWith("$origin/")
        }

        override fun onReceivedHttpError(view: WebView?, request: WebResourceRequest?, errorResponse: WebResourceResponse?) {
            if (request?.isForMainFrame == true) {
                val code = errorResponse?.statusCode ?: 0
                post { listener.onPageFailed(code) }
            }
        }

        override fun onReceivedError(view: WebView?, request: WebResourceRequest?, error: WebResourceError?) {
            if (request?.isForMainFrame == true) {
                val code = error?.errorCode ?: 0
                post { listener.onPageFailed(code) }
            }
        }

        override fun onRenderProcessGone(view: WebView?, detail: RenderProcessGoneDetail?): Boolean {
            post { listener.onPageFailed(-2) }
            return true
        }
    }

    private inner class Chrome : WebChromeClient() {
        override fun onConsoleMessage(consoleMessage: ConsoleMessage?): Boolean {
            if (consoleMessage != null && debug) {
                Log.d(TAG, "page: " + consoleMessage.message())
            }
            return true
        }
    }

    companion object {
        private const val TAG = "XiaogouQuick"
        const val PAGE = "/quick/remind.html"
    }
}
