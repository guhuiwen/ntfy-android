package io.heckel.ntfy.xiaogou

import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import android.view.Display
import android.view.Gravity
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import org.json.JSONObject

/**
 * 提醒卡片的悬浮窗（手机亮着、没锁的时候用）。
 *
 * 不用麦克风，所以不是前台服务：App 本来就有一条常驻的「正在接收」服务撑着，这里只管把卡片挂到屏幕下方。
 * 正在快捷唤醒（他正在说话）的话先等等，那边收起了再弹，免得两个声音叠在一起。
 */
class RemindOverlayService : Service(), RemindPage.Listener {
    private val main = Handler(Looper.getMainLooper())
    private var uiContext: Context? = null
    private var windowManager: WindowManager? = null
    private var container: FrameLayout? = null
    private var page: RemindPage? = null
    private var layoutParams: WindowManager.LayoutParams? = null
    private var attached = false
    private var pageReady = false
    private var current: JSONObject? = null
    private var notificationId = 0
    private var silentNotification = false      // 这张卡片对应的通知是静音的吗（卡片没弹成时要补一声）
    private var waitedMs = 0L
    private var density = 1f

    private val release = Runnable { stopSelf() }
    private val loadTimeout = Runnable { onPageFailed(-1) }
    private val retry = Runnable { showWhenFree() }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        density = resources.displayMetrics.density
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val data = RemindCenter.dataOf(intent, "overlay")
        if (data == null) {
            if (!attached) stopSelf()
            return START_NOT_STICKY
        }
        current = data
        notificationId = intent?.getIntExtra(RemindCenter.EXTRA_NOTIFICATION_ID, 0) ?: 0
        silentNotification = notificationId != 0
        waitedMs = 0
        main.removeCallbacks(release)
        main.removeCallbacks(retry)
        showWhenFree()
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        main.removeCallbacksAndMessages(null)
        destroyPage()
        super.onDestroy()
    }

    private fun showWhenFree() {
        if (QuickOverlayService.showing && waitedMs < MAX_WAIT_MS) {
            waitedMs += RETRY_MS
            main.postDelayed(retry, RETRY_MS)
            return
        }
        val origin = QuickPrefs.origin(this)
        if (origin.isEmpty() || !ensurePage(origin) || !attach()) {
            giveUp()
            return
        }
        val data = current
        if (pageReady && data != null) {
            page?.show(data)
        } else {
            main.removeCallbacks(loadTimeout)
            main.postDelayed(loadTimeout, LOAD_TIMEOUT_MS)
        }
    }

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

    private fun ensurePage(origin: String): Boolean {
        if (page != null) return true
        return try {
            val context = overlayContext()
            val created = RemindPage(context, origin, this)
            val box = FrameLayout(context)
            box.addView(created.view, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            page = created
            container = box
            pageReady = false
            true
        } catch (e: Exception) {
            Log.w(TAG, "creating remind page failed: ${e.javaClass.simpleName} ${e.message}")
            false
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
            it.title = "XiaogouRemind"
            layoutParams = it
        }
        return try {
            windowManager?.addView(box, params)
            attached = true
            page?.view?.onResume()
            true
        } catch (e: Exception) {
            Log.w(TAG, "remind addView failed: ${e.javaClass.simpleName} ${e.message}")
            false
        }
    }

    private fun detach() {
        val box = container
        if (!attached || box == null) {
            attached = false
            return
        }
        attached = false
        try {
            page?.view?.onPause()
        } catch (_: Exception) {
        }
        try {
            windowManager?.removeView(box)
        } catch (e: Exception) {
            Log.w(TAG, "remind removeView failed: ${e.message}")
        }
    }

    private fun destroyPage() {
        detach()
        page?.destroy()
        page = null
        try {
            container?.removeAllViews()
        } catch (_: Exception) {
        }
        container = null
        pageReady = false
    }

    /** 卡片弹不出来：通知是静音的，补一声提示音；通知本身还留在通知栏里 */
    private fun giveUp() {
        Log.w(TAG, "reminder card could not be shown, falling back to the notification")
        if (silentNotification) RemindCenter.fallbackSound(this)
        current = null
        destroyPage()
        main.removeCallbacks(release)
        main.postDelayed(release, 1000)
    }

    override fun onPageReady() {
        pageReady = true
        main.removeCallbacks(loadTimeout)
        val data = current
        if (data != null && attached) page?.show(data)
    }

    override fun onPageResize(heightCss: Int) {
        val params = layoutParams ?: return
        val maxPx = (resources.displayMetrics.heightPixels * 0.85f).toInt()
        val px = (heightCss.coerceAtLeast(60) * density).toInt().coerceAtMost(maxPx)
        if (params.height == px) return
        params.height = px
        if (attached) {
            try {
                windowManager?.updateViewLayout(container, params)
            } catch (e: Exception) {
                Log.w(TAG, "remind resize failed: ${e.message}")
            }
        }
    }

    override fun onPageDone(action: String) {
        Log.d(TAG, "reminder card done: $action")
        if (action == "ok" || action == "snooze") {
            RemindCenter.cancelNotification(this, notificationId)
        }
        current = null
        detach()
        main.removeCallbacks(release)
        main.postDelayed(release, RELEASE_MS)       // 网页先留一会儿，接着又有提醒就不用重新载
    }

    override fun onPageFailed(code: Int) {
        Log.w(TAG, "remind page failed: code=$code")
        main.removeCallbacks(loadTimeout)
        giveUp()
    }

    companion object {
        private const val TAG = "XiaogouQuick"
        private const val DEFAULT_HEIGHT_DP = 300
        private const val LOAD_TIMEOUT_MS = 15_000L
        private const val RELEASE_MS = 2 * 60 * 1000L
        private const val RETRY_MS = 3000L
        private const val MAX_WAIT_MS = 5 * 60 * 1000L
    }
}
