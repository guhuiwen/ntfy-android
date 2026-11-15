package io.heckel.ntfy.xiaogou

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.WindowManager
import org.json.JSONObject

/**
 * 提醒卡片的锁屏版：手机锁着 / 息屏时提醒到点，像闹钟那样亮屏、盖在锁屏上面显示。
 * （悬浮窗盖不到锁屏上面，所以锁屏时走这一页；页面还是服务器上那张 /quick/remind.html，只是多了底色和大钟点。）
 * 两分钟没人理就自己收掉让屏幕休息，通知栏里那一条还在。
 */
class RemindLockActivity : Activity(), RemindPage.Listener {
    private val main = Handler(Looper.getMainLooper())
    private var page: RemindPage? = null
    private var pageReady = false
    private var current: JSONObject? = null
    private var notificationId = 0
    private val autoClose = Runnable { finish() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON)
        }
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        val origin = QuickPrefs.origin(this)
        if (origin.isEmpty() || !take(intent)) {
            finish()
            return
        }
        try {
            val created = RemindPage(this, origin, this)
            created.view.setBackgroundColor(BACKDROP)      // 网页画出来之前先垫一层暖色，别闪一下黑的
            page = created
            setContentView(created.view)
        } catch (e: Exception) {
            Log.w(TAG, "lock card failed: ${e.javaClass.simpleName} ${e.message}")
            finish()
            return
        }
        main.postDelayed(autoClose, AUTO_CLOSE_MS)
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        if (take(intent)) {
            main.removeCallbacks(autoClose)
            main.postDelayed(autoClose, AUTO_CLOSE_MS)
            val data = current
            if (pageReady && data != null) page?.show(data)
        }
    }

    private fun take(intent: Intent?): Boolean {
        val data = RemindCenter.dataOf(intent, "lock") ?: return false
        current = data
        notificationId = intent?.getIntExtra(RemindCenter.EXTRA_NOTIFICATION_ID, 0) ?: 0
        return true
    }

    override fun onDestroy() {
        main.removeCallbacksAndMessages(null)
        page?.destroy()
        page = null
        super.onDestroy()
    }

    override fun onPageReady() {
        pageReady = true
        val data = current
        if (data != null) {
            QuickOverlayService.earHoldUntil = System.currentTimeMillis() + 20_000L     // 他念提醒的这一阵，耳朵先别找唤醒词
            page?.show(data)
        }
    }

    override fun onPageResize(heightCss: Int) {
        // 整屏显示，不用跟着卡片改大小
    }

    override fun onPageDone(action: String) {
        Log.d(TAG, "lock card done: $action")
        if (action == "ok" || action == "snooze") {
            RemindCenter.cancelNotification(this, notificationId)
        }
        finish()
    }

    override fun onPageFailed(code: Int) {
        Log.w(TAG, "lock card page failed: code=$code")
        finish()        // 通知已经照常响过了，这里收掉就行
    }

    companion object {
        private const val TAG = "XiaogouQuick"
        private const val AUTO_CLOSE_MS = 2 * 60 * 1000L
        private val BACKDROP = Color.rgb(0xF6, 0xE4, 0xE6)
    }
}
