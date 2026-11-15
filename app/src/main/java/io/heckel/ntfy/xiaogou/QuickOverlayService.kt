package io.heckel.ntfy.xiaogou

import android.Manifest
import android.annotation.SuppressLint
import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.graphics.Color
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import android.util.Log
import android.view.Display
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.webkit.ConsoleMessage
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.PermissionRequest
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.Toast
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import io.heckel.ntfy.R

/**
 * 快捷唤醒的悬浮窗（小狗之心）。
 *
 * 「网页画皮、原生骨头」：画面和这一次对话的逻辑（连服务器、麦克风、放声音、字幕）都在服务器上的一张网页里，
 * 这里只负责骨头——把那张网页放进一个背景透明的悬浮窗（卡片多高窗口就多高，别处的触摸照样落到下面的 App 上）、
 * 以「带麦克风的前台服务」的身份撑着它、把麦克风权限交给它、替它打开别的界面。改样子只要改网页，不用重装 App。
 *
 * 网页 ↔ 这里（window.XiaogouHost）：
 *   网页 → 这里：ready() 页面好了 · resize(高度) · close(原因) 可以把窗口撤了 · openCall(地址) 切去打电话 · openUrl(地址)
 *   这里 → 网页：ddQuick.wake() 喊醒 · ddQuick.hide() 收起 · ddQuick.probe() 试小猪片子
 */
class QuickOverlayService : Service() {
    private val main = Handler(Looper.getMainLooper())
    private var uiContext: Context? = null
    private var windowManager: WindowManager? = null
    private var container: FrameLayout? = null
    private var webView: WebView? = null
    private var layoutParams: WindowManager.LayoutParams? = null
    private var attached = false            // 悬浮窗现在挂在屏幕上吗
    private var pageReady = false           // 网页说过「我好了」
    private var pageOrigin = ""
    private var pending: String? = null     // 页面还没好时先记下要它做的事（wake / probe）
    private var foreground = false
    private var density = 1f
    private var shownAt = 0L

    private val idleRelease = Runnable {
        Log.d(TAG, "idle for a while, releasing the page")
        stopSelf()
    }
    private val loadTimeout = Runnable { onPageFailed(-1, "timeout") }
    private val hideFallback = Runnable { if (attached) finishSession("hide_fallback") }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        density = resources.displayMetrics.density
        QuickEntry.ensureChannels(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action
        Log.d(TAG, "onStartCommand action=$action attached=$attached ready=$pageReady")
        when (action) {
            ACTION_WAKE, ACTION_PROBE -> {
                if (!enterForeground()) {
                    stopSelf()
                    return START_NOT_STICKY
                }
                main.removeCallbacks(idleRelease)
                main.removeCallbacks(hideFallback)
                show(if (action == ACTION_PROBE) "probe" else "wake")
            }
            ACTION_HIDE -> {
                // 通知上的「收起」：先请网页自己收（它会把话停掉、把卡片滑下去），两秒没动静就直接撤
                if (attached) {
                    callPage("window.ddQuick && ddQuick.hide()")
                    main.removeCallbacks(hideFallback)
                    main.postDelayed(hideFallback, 2000)
                } else if (!foreground) {
                    stopSelf()
                }
            }
            else -> if (!foreground && !attached) stopSelf()
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        Log.d(TAG, "service destroyed")
        main.removeCallbacksAndMessages(null)
        destroyWebView()
        leaveForeground()
        super.onDestroy()
    }

    // ---------------------------------------------------------------- 前台服务 ----------------------------------------------------------------

    private fun enterForeground(): Boolean {
        val notification = sessionNotification()
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                startForeground(QuickEntry.NOTIFICATION_SESSION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
            } else {
                startForeground(QuickEntry.NOTIFICATION_SESSION_ID, notification)
            }
            foreground = true
            return true
        } catch (e: Exception) {
            Log.w(TAG, "startForeground(microphone) failed: ${e.javaClass.simpleName} ${e.message}")
        }
        // 带麦克风的那种没起成（权限没给 / 系统不让）：换一种把「我进前台了」这件事交代掉（不然系统会把 App 杀掉），再提示她
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                startForeground(QuickEntry.NOTIFICATION_SESSION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
            } else {
                startForeground(QuickEntry.NOTIFICATION_SESSION_ID, notification)
            }
            stopForeground(STOP_FOREGROUND_REMOVE)
        } catch (e: Exception) {
            Log.w(TAG, "startForeground fallback failed: ${e.javaClass.simpleName} ${e.message}")
        }
        toast(R.string.xiaogou_quick_toast_no_mic)
        return false
    }

    private fun leaveForeground() {
        if (!foreground) return
        foreground = false
        try {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } catch (e: Exception) {
            Log.w(TAG, "stopForeground failed: ${e.message}")
        }
    }

    private fun sessionNotification(): Notification {
        val hide = PendingIntent.getService(
            this, 3, Intent(this, QuickOverlayService::class.java).setAction(ACTION_HIDE),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, QuickEntry.CHANNEL_SESSION)
            .setSmallIcon(R.drawable.ic_xiaogou_quick)
            .setContentTitle(QuickPrefs.displayName(this))
            .setContentText(getString(R.string.xiaogou_quick_session_text))
            .setOngoing(true)
            .setShowWhen(false)
            .setSound(null)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .addAction(NotificationCompat.Action.Builder(0, getString(R.string.xiaogou_quick_session_hide), hide).build())
            .build()
    }

    // ---------------------------------------------------------------- 悬浮窗 ----------------------------------------------------------------

    private fun show(what: String) {
        val origin = QuickPrefs.origin(this)
        if (origin.isEmpty()) {
            toast(R.string.xiaogou_quick_toast_login)
            finishSession("not_logged_in")
            return
        }
        shownAt = System.currentTimeMillis()
        if (!ensureWebView(origin) || !attach()) {
            return
        }
        if (pageReady) {
            runPage(what)
        } else {
            pending = what
            main.removeCallbacks(loadTimeout)
            main.postDelayed(loadTimeout, LOAD_TIMEOUT_MS)
        }
    }

    /** 给悬浮窗用的上下文：安卓 11 起要用「窗口上下文」来造悬浮窗里的东西 */
    private fun overlayContext(): Context {
        uiContext?.let { return it }
        var context: Context = this
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            try {
                val display = (getSystemService(Context.DISPLAY_SERVICE) as DisplayManager).getDisplay(Display.DEFAULT_DISPLAY)
                context = createDisplayContext(display).createWindowContext(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY, null)
            } catch (e: Exception) {
                Log.w(TAG, "window context failed, using service context: ${e.message}")
            }
        }
        uiContext = context
        windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        return context
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun ensureWebView(origin: String): Boolean {
        if (webView != null && pageOrigin == origin) return true
        destroyWebView()
        pageOrigin = origin
        pageReady = false
        try {
            val context = overlayContext()
            WebView.setWebContentsDebuggingEnabled(QuickPrefs.webDebug(this))
            CookieManager.getInstance().setAcceptCookie(true)
            val web = WebView(context)
            web.setBackgroundColor(Color.TRANSPARENT)
            web.overScrollMode = View.OVER_SCROLL_NEVER
            web.isVerticalScrollBarEnabled = false
            web.isHorizontalScrollBarEnabled = false
            val settings = web.settings
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.mediaPlaybackRequiresUserGesture = false        // 他的声音要能自己放出来
            settings.cacheMode = WebSettings.LOAD_DEFAULT
            settings.setSupportMultipleWindows(false)
            settings.setSupportZoom(false)
            settings.textZoom = 100                                  // 不跟系统字号走，样子以网页为准
            settings.userAgentString = settings.userAgentString + " XiaogouQuick/" + QuickPrefs.BUILD
            web.addJavascriptInterface(Bridge(), "XiaogouHost")
            web.webViewClient = PageClient()
            web.webChromeClient = ChromeClient()
            val box = FrameLayout(context)
            box.addView(web, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            container = box
            webView = web
            val path = QuickPrefs.pagePath(this)
            Log.d(TAG, "loading page $path")
            // max-age=0：每次都问服务器一声「这页改过没有」（没改只回一个很小的 304），改了网页不用等缓存过期
            web.loadUrl(origin + path, mapOf("Cache-Control" to "max-age=0"))
            return true
        } catch (e: Exception) {
            Log.w(TAG, "creating web view failed: ${e.javaClass.simpleName} ${e.message}")
            toast(R.string.xiaogou_quick_toast_load_failed)
            destroyWebView()
            finishSession("webview_failed")
            return false
        }
    }

    private fun attach(): Boolean {
        val box = container ?: return false
        if (attached) return true
        val params = layoutParams ?: WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            (DEFAULT_HEIGHT_DP * density).toInt(),
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
            PixelFormat.TRANSLUCENT
        ).also {
            it.gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
            it.title = "XiaogouQuick"
            layoutParams = it
        }
        try {
            windowManager?.addView(box, params)
            attached = true
            showing = true
            webView?.onResume()
            return true
        } catch (e: Exception) {
            Log.w(TAG, "addView failed: ${e.javaClass.simpleName} ${e.message}")
            toast(R.string.xiaogou_quick_toast_no_overlay)
            finishSession("no_overlay")
            return false
        }
    }

    private fun detach() {
        val box = container
        if (!attached || box == null) {
            attached = false
            showing = false
            return
        }
        attached = false
        showing = false
        try {
            webView?.onPause()
        } catch (_: Exception) {
        }
        try {
            windowManager?.removeView(box)
        } catch (e: Exception) {
            Log.w(TAG, "removeView failed: ${e.message}")
        }
    }

    private fun destroyWebView() {
        detach()
        val web = webView
        webView = null
        try {
            container?.removeAllViews()
        } catch (_: Exception) {
        }
        container = null
        pageReady = false
        if (web != null) {
            try {
                web.stopLoading()
                web.removeJavascriptInterface("XiaogouHost")
                web.destroy()
            } catch (e: Exception) {
                Log.w(TAG, "destroying web view failed: ${e.message}")
            }
        }
    }

    /** 这一次用完了：撤掉悬浮窗、退出前台；网页先留着（十分钟内再喊能马上出来），之后自己放掉 */
    private fun finishSession(reason: String) {
        Log.d(TAG, "session finished: $reason")
        pending = null
        main.removeCallbacks(loadTimeout)
        main.removeCallbacks(hideFallback)
        detach()
        leaveForeground()
        main.removeCallbacks(idleRelease)
        main.postDelayed(idleRelease, IDLE_RELEASE_MS)
    }

    private fun runPage(what: String) {
        Log.d(TAG, "page $what (${System.currentTimeMillis() - shownAt} ms after tap)")
        callPage(if (what == "probe") "window.ddQuick && ddQuick.probe()" else "window.ddQuick && ddQuick.wake()")
    }

    private fun callPage(script: String) {
        try {
            webView?.evaluateJavascript(script, null)
        } catch (e: Exception) {
            Log.w(TAG, "calling page failed: ${e.message}")
        }
    }

    // ---------------------------------------------------------------- 网页那头来的事 ----------------------------------------------------------------

    private fun onPageReady() {
        Log.d(TAG, "page ready (${System.currentTimeMillis() - shownAt} ms)")
        pageReady = true
        main.removeCallbacks(loadTimeout)
        val what = pending
        pending = null
        if (what != null && attached) runPage(what)
    }

    private fun onPageResize(heightCss: Int) {
        val params = layoutParams ?: return
        val maxPx = (resources.displayMetrics.heightPixels * 0.85f).toInt()
        val px = (heightCss.coerceAtLeast(60) * density).toInt().coerceAtMost(maxPx)
        if (params.height == px) return
        params.height = px
        if (attached) {
            try {
                windowManager?.updateViewLayout(container, params)
            } catch (e: Exception) {
                Log.w(TAG, "resize failed: ${e.message}")
            }
        }
    }

    private fun onPageFailed(code: Int, what: String) {
        Log.w(TAG, "page failed: $what code=$code")
        toast(if (code == 404) R.string.xiaogou_quick_toast_login else R.string.xiaogou_quick_toast_load_failed)
        destroyWebView()        // 下次再喊重新载一遍
        finishSession("page_failed")
    }

    /** 打开自家网站上的一个地址：手机上装了网页版（WebAPK）就交给它，没有就交给浏览器 */
    private fun openExternal(pathOrUrl: String) {
        val url = when {
            pathOrUrl.startsWith("/") -> pageOrigin + pathOrUrl
            pathOrUrl.startsWith("$pageOrigin/") -> pathOrUrl
            else -> ""
        }
        if (url.isEmpty() || pageOrigin.isEmpty()) {
            Log.w(TAG, "refusing to open a foreign url")
            return
        }
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val webApk = findWebApk()
        Log.d(TAG, "opening external page (webapk=${webApk != null})")
        if (webApk != null) {
            try {
                startActivity(Intent(intent).setPackage(webApk))
                return
            } catch (e: Exception) {
                Log.w(TAG, "the web app would not open it, using the browser: ${e.javaClass.simpleName}")
            }
        }
        try {
            startActivity(intent)
        } catch (e: Exception) {
            Log.w(TAG, "open external failed: ${e.javaClass.simpleName} ${e.message}")
            toast(R.string.xiaogou_quick_toast_open_failed)
        }
    }

    /** 找手机上装的自家网页版（WebAPK）：包名以 org.chromium.webapk 开头、清单里记的地址在自家网站上的那个 */
    private fun findWebApk(): String? {
        try {
            val launcher = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
            @Suppress("DEPRECATION")
            val apps = packageManager.queryIntentActivities(launcher, 0)
            for (info in apps) {
                val name = info.activityInfo?.packageName ?: continue
                if (!name.startsWith("org.chromium.webapk")) continue
                val meta = packageManager.getApplicationInfo(name, PackageManager.GET_META_DATA).metaData ?: continue
                for (key in meta.keySet()) {
                    @Suppress("DEPRECATION")
                    val value = meta.get(key)?.toString() ?: continue
                    if (value == pageOrigin || value.startsWith("$pageOrigin/")) return name
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "looking for the web app failed: ${e.message}")
        }
        return null
    }

    private fun toast(resId: Int) {
        main.post {
            try {
                Toast.makeText(applicationContext, getString(resId), Toast.LENGTH_LONG).show()
            } catch (_: Exception) {
            }
        }
    }

    /** 网页调过来的口子。这些方法跑在网页的线程上，所以一律转回主线程再做事 */
    inner class Bridge {
        @JavascriptInterface
        fun ready() {
            main.post { onPageReady() }
        }

        @JavascriptInterface
        fun resize(height: Int) {
            main.post { onPageResize(height) }
        }

        @JavascriptInterface
        fun close(reason: String?) {
            main.post { finishSession("page:" + (reason ?: "")) }
        }

        @JavascriptInterface
        fun openCall(url: String?) {
            // 先开通话页再撤悬浮窗：悬浮窗还在的时候，系统才肯让我们从后台打开别的界面
            main.post {
                openExternal(url ?: "")
                finishSession("call")
            }
        }

        @JavascriptInterface
        fun openUrl(url: String?) {
            main.post { openExternal(url ?: "") }
        }
    }

    private inner class PageClient : WebViewClient() {
        override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
            val url = request?.url?.toString() ?: return true
            return !url.startsWith("$pageOrigin/")       // 自家网站里走动放行；别处的网址不在悬浮窗里开
        }

        override fun onReceivedHttpError(view: WebView?, request: WebResourceRequest?, errorResponse: WebResourceResponse?) {
            if (request?.isForMainFrame == true) {
                val code = errorResponse?.statusCode ?: 0
                main.post { onPageFailed(code, "http") }
            }
        }

        override fun onReceivedError(view: WebView?, request: WebResourceRequest?, error: WebResourceError?) {
            if (request?.isForMainFrame == true) {
                val code = error?.errorCode ?: 0
                Log.w(TAG, "page load error: ${error?.description}")
                main.post { onPageFailed(code, "network") }
            }
        }

        override fun onRenderProcessGone(view: WebView?, detail: RenderProcessGoneDetail?): Boolean {
            Log.w(TAG, "web render process gone (crashed=${detail?.didCrash()})")
            main.post {
                val wasShowing = attached
                destroyWebView()
                if (wasShowing) finishSession("renderer_gone")
            }
            return true
        }
    }

    private inner class ChromeClient : WebChromeClient() {
        override fun onPermissionRequest(request: PermissionRequest?) {
            if (request == null) return
            main.post {
                try {
                    val origin = request.origin?.toString()?.trimEnd('/') ?: ""
                    val wantsMic = request.resources?.contains(PermissionRequest.RESOURCE_AUDIO_CAPTURE) == true
                    val hasMic = ContextCompat.checkSelfPermission(this@QuickOverlayService, Manifest.permission.RECORD_AUDIO) ==
                        PackageManager.PERMISSION_GRANTED
                    if (wantsMic && hasMic && origin == pageOrigin) {
                        Log.d(TAG, "microphone handed to the page")
                        request.grant(arrayOf(PermissionRequest.RESOURCE_AUDIO_CAPTURE))
                    } else {
                        Log.w(TAG, "page permission denied (wantsMic=$wantsMic hasMic=$hasMic sameOrigin=${origin == pageOrigin})")
                        request.deny()
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "permission request failed: ${e.message}")
                }
            }
        }

        override fun onConsoleMessage(consoleMessage: ConsoleMessage?): Boolean {
            // 网页里的日志带着对话内容，只在调试开关开着时记，而且只进手机日志（不进能导出的 App 日志）
            if (consoleMessage != null && QuickPrefs.webDebug(this@QuickOverlayService)) {
                Log.d(TAG, "page: " + consoleMessage.message())
            }
            return true
        }
    }

    companion object {
        const val TAG = "XiaogouQuick"
        const val ACTION_WAKE = "io.heckel.ntfy.xiaogou.WAKE"
        const val ACTION_PROBE = "io.heckel.ntfy.xiaogou.PROBE"
        const val ACTION_HIDE = "io.heckel.ntfy.xiaogou.HIDE"

        /** 浮层现在挂在屏幕上吗（提醒卡片看它：他正在说话就先等等） */
        @Volatile
        var showing = false
        private const val DEFAULT_HEIGHT_DP = 300
        private const val LOAD_TIMEOUT_MS = 20_000L
        private const val IDLE_RELEASE_MS = 10 * 60 * 1000L

        /** 登录过、麦克风和悬浮窗权限都给了，才能喊 */
        fun canStart(context: Context): Boolean {
            return QuickPrefs.origin(context).isNotEmpty() &&
                ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED &&
                Settings.canDrawOverlays(context)
        }
    }
}
