package io.heckel.ntfy.xiaogou

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.core.content.ContextCompat

/**
 * 透明的中转页：通知 / 控制中心按钮 / 设置页点下来先到这儿，再把悬浮窗服务拉起来，然后自己走掉。
 *
 * 一定要经过一个界面：安卓规定「带麦克风的前台服务」只能在 App 有界面看得见的时候启动。
 * 带 EXTRA_EAR = 把耳朵开起来（之后服务一直在前台，喊一声就来，不用再点）；不带 = 这一次喊一声。
 */
class QuickWakeActivity : Activity() {
    private val handler = Handler(Looper.getMainLooper())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        handle(intent)
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        handle(intent)
    }

    private fun handle(intent: Intent?) {
        handler.removeCallbacksAndMessages(null)
        if (!QuickOverlayService.canStart(this)) {
            // 还没登录 / 权限没给全：带她去设置页
            try {
                startActivity(Intent(this, SetupActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            } catch (e: Exception) {
                Log.w(TAG, "open setup failed: ${e.message}")
            }
            leave()
            return
        }
        val action = when {
            intent?.getBooleanExtra(EXTRA_EAR, false) == true -> QuickOverlayService.ACTION_EAR_ON
            intent?.getBooleanExtra(EXTRA_PROBE, false) == true -> QuickOverlayService.ACTION_PROBE
            else -> QuickOverlayService.ACTION_WAKE
        }
        try {
            ContextCompat.startForegroundService(this, Intent(this, QuickOverlayService::class.java).setAction(action))
        } catch (e: Exception) {
            Log.w(TAG, "start overlay service failed: ${e.javaClass.simpleName} ${e.message}")
        }
        // 留半秒多再走：等服务真的进了前台（这期间 App 还算「看得见」）
        handler.postDelayed({ leave() }, 700)
    }

    @Suppress("DEPRECATION")
    private fun leave() {
        if (isFinishing) return
        finish()
        overridePendingTransition(0, 0)
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    companion object {
        private const val TAG = "XiaogouQuick"
        const val EXTRA_PROBE = "probe"
        const val EXTRA_EAR = "ear"
    }
}
