package io.heckel.ntfy.xiaogou

import android.app.AppOpsManager
import android.app.KeyguardManager
import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.RingtoneManager
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.Process
import android.provider.Settings
import android.util.Log
import androidx.core.content.ContextCompat
import io.heckel.ntfy.db.Notification
import io.heckel.ntfy.db.Subscription
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 提醒到点 → 弹卡片（小狗之心）。
 *
 * 服务器发来的提醒本身就是一条普通通知，点开的地址里带着 reminder_popup=1 和标题 / 正文 / 时间。
 * 我们认出它以后：
 *   · 手机亮着、没锁 → 悬浮卡片（[RemindOverlayService]），那条通知改成静音（卡片那张网页会用他的声音念）；
 *   · 锁屏 / 息屏     → 通知照常响；他的声音由 [RemindVoice] 直接念（不靠卡片显示得出来）；系统肯让我们盖在锁屏上面
 *                      就亮屏弹全屏卡片（[RemindLockActivity]，小米要「锁屏显示」权限，没给的话系统会直接拦掉）；
 *                      卡片没露过面的话，等她解锁时补一张悬浮卡片；
 *   · 认不出 / 没登录 / 没给悬浮窗权限 / 迟到太久 → 什么都不做，它就是原来的通知。
 */
object RemindCenter {
    private const val TAG = "XiaogouQuick"
    const val NONE = 0
    const val OVERLAY = 1
    const val LOCK = 2

    const val EXTRA_ID = "id"
    const val EXTRA_TITLE = "title"
    const val EXTRA_DESC = "desc"
    const val EXTRA_TIME = "time"
    const val EXTRA_SENT_AT = "sent_at"
    const val EXTRA_NOTIFICATION_ID = "notification_id"
    const val EXTRA_TEST = "test"
    const val EXTRA_SPOKEN = "spoken"       // 已经由 RemindVoice 念过了：卡片那张网页就别再念一遍

    private const val MAX_AGE_SECONDS = 10 * 60L      // 迟到超过这么久（手机当时没连着）就只当普通通知
    private const val MIUI_OP_SHOW_WHEN_LOCKED = 10020

    /** 锁屏卡片现在露在屏幕上吗（解锁时看它：已经露着就不再补悬浮卡片） */
    @Volatile
    var lockCardVisible = false

    private val main = Handler(Looper.getMainLooper())
    private val lock = Any()
    private var pendingOverlay: Intent? = null      // 锁屏时到点的那条提醒：等她解锁时补一张悬浮卡片
    private var pendingAt = 0L
    private var unlockReceiver: BroadcastReceiver? = null

    /** 收到一条要弹通知的消息时调。返回上面三种之一（调用方据此决定通知要不要静音） */
    fun handle(context: Context, subscription: Subscription, notification: Notification): Int {
        try {
            if (!QuickPrefs.remindCard(context)) return NONE
            val origin = QuickPrefs.origin(context)
            if (origin.isEmpty()) return NONE
            val click = notification.click
            if (click.isEmpty() || !click.startsWith("$origin/")) return NONE
            val uri = Uri.parse(click)
            if (uri.getQueryParameter("reminder_popup") != "1") return NONE
            val title = uri.getQueryParameter("r_title") ?: ""
            if (title.isEmpty()) return NONE
            val age = System.currentTimeMillis() / 1000 - notification.timestamp
            if (age > MAX_AGE_SECONDS) {
                Log.d(TAG, "reminder is $age s old, leaving it as a plain notification")
                return NONE
            }
            if (!Settings.canDrawOverlays(context)) return NONE
            val extras = Bundle()
            extras.putString(EXTRA_ID, notification.id)
            extras.putString(EXTRA_TITLE, title)
            extras.putString(EXTRA_DESC, uri.getQueryParameter("r_desc") ?: "")
            extras.putString(EXTRA_TIME, uri.getQueryParameter("r_time") ?: "")
            extras.putLong(EXTRA_SENT_AT, notification.timestamp)
            extras.putInt(EXTRA_NOTIFICATION_ID, notification.notificationId)
            return if (isLocked(context)) {
                startLocked(context, extras, 1800)       // 通知自己先响一声，等它响完再念
                LOCK
            } else {
                startOverlay(context, extras)
                OVERLAY
            }
        } catch (e: Exception) {
            Log.w(TAG, "reminder card failed: ${e.javaClass.simpleName} ${e.message}")
            return NONE
        }
    }

    /** 设置页里的「试一下」：弹一张测试卡片（不连提醒、不会真的再提醒） */
    fun test(context: Context, forceLock: Boolean) {
        try {
            val extras = Bundle()
            extras.putString(EXTRA_ID, "test")
            extras.putString(EXTRA_TITLE, "喝水")
            extras.putString(EXTRA_DESC, "这是一张测试卡片：到点的提醒就长这样")
            extras.putString(EXTRA_TIME, SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date()))
            extras.putLong(EXTRA_SENT_AT, System.currentTimeMillis() / 1000)
            extras.putInt(EXTRA_NOTIFICATION_ID, 0)
            extras.putBoolean(EXTRA_TEST, true)
            if (forceLock || isLocked(context)) startLocked(context, extras, 600) else startOverlay(context, extras)
        } catch (e: Exception) {
            Log.w(TAG, "test card failed: ${e.javaClass.simpleName} ${e.message}")
        }
    }

    private fun startOverlay(context: Context, extras: Bundle) {
        context.startService(Intent(context, RemindOverlayService::class.java).putExtras(extras))
        Log.d(TAG, "reminder -> overlay card")
    }

    private fun startLocked(context: Context, extras: Bundle, voiceDelayMs: Long) {
        val app = context.applicationContext
        val spoken = Bundle(extras)
        spoken.putBoolean(EXTRA_SPOKEN, true)
        // ① 他的声音：这里直接念
        RemindVoice.speakLater(app, extras.getString(EXTRA_TITLE) ?: "", extras.getString(EXTRA_DESC) ?: "", voiceDelayMs)
        // ② 记下来：解锁时卡片还没露过面，就补一张悬浮卡片
        armUnlock(app, Intent(app, RemindOverlayService::class.java).putExtras(spoken))
        // ③ 能盖在锁屏上面就亮屏弹全屏卡片
        if (miuiLockScreenAllowed(app) == false) {
            Log.d(TAG, "reminder -> locked, and showing on the lock screen is switched off: voice now, card after unlock")
            return
        }
        try {
            app.startActivity(
                Intent(app, RemindLockActivity::class.java).putExtras(spoken)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_USER_ACTION)
            )
            Log.d(TAG, "reminder -> lock screen card")
        } catch (e: Exception) {
            Log.w(TAG, "lock screen card would not start: ${e.javaClass.simpleName} ${e.message}")
        }
    }

    private fun armUnlock(app: Context, overlay: Intent) {
        synchronized(lock) {
            pendingOverlay = overlay
            pendingAt = System.currentTimeMillis()
            if (unlockReceiver == null) {
                val receiver = object : BroadcastReceiver() {
                    override fun onReceive(context: Context?, intent: Intent?) {
                        // 稍等一下再看：锁屏卡片要是本来就在，这时候它正好露出来
                        main.postDelayed({ afterUnlock(app) }, 800)
                    }
                }
                try {
                    ContextCompat.registerReceiver(app, receiver, IntentFilter(Intent.ACTION_USER_PRESENT), ContextCompat.RECEIVER_NOT_EXPORTED)
                    unlockReceiver = receiver
                } catch (e: Exception) {
                    Log.w(TAG, "could not watch for unlock: ${e.message}")
                }
            }
        }
    }

    private fun afterUnlock(app: Context) {
        val overlay = synchronized(lock) {
            val waiting = pendingOverlay
            val fresh = System.currentTimeMillis() - pendingAt <= MAX_AGE_SECONDS * 1000
            pendingOverlay = null
            if (fresh) waiting else null
        } ?: return
        if (lockCardVisible) {
            Log.d(TAG, "unlocked: the lock screen card is already showing")
            return
        }
        try {
            app.startService(overlay)
            Log.d(TAG, "reminder -> overlay card after unlock")
        } catch (e: Exception) {
            Log.w(TAG, "card after unlock failed: ${e.javaClass.simpleName} ${e.message}")
        }
    }

    /** 她在卡片上点了「知道了 / 稍后提醒」：这条提醒办完了，解锁时不用再补 */
    fun handled() {
        synchronized(lock) { pendingOverlay = null }
        RemindVoice.stop()
    }

    fun isLocked(context: Context): Boolean {
        val keyguard = context.getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager
        val power = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        return keyguard.isKeyguardLocked || !power.isInteractive
    }

    /**
     * 小米的「锁屏显示」开没开：true 开着 / false 关着（系统会把锁屏上的界面直接拦掉）/ null 不是小米或者查不出来。
     * 这是小米自己加的开关，安卓没有正式的查法，只能这样问。
     */
    fun miuiLockScreenAllowed(context: Context): Boolean? {
        return try {
            val ops = context.getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
            val method = AppOpsManager::class.java.getMethod(
                "checkOpNoThrow", Int::class.javaPrimitiveType, Int::class.javaPrimitiveType, String::class.java
            )
            val mode = method.invoke(ops, MIUI_OP_SHOW_WHEN_LOCKED, Process.myUid(), context.packageName) as Int
            mode == AppOpsManager.MODE_ALLOWED
        } catch (_: Throwable) {
            null
        }
    }

    /** 把 Intent 里带的提醒内容变成给网页的那份数据；没有标题返回 null */
    fun dataOf(intent: Intent?, mode: String): JSONObject? {
        if (intent == null) return null
        val title = intent.getStringExtra(EXTRA_TITLE) ?: ""
        if (title.isEmpty()) return null
        return JSONObject()
            .put("id", intent.getStringExtra(EXTRA_ID) ?: "")
            .put("title", title)
            .put("desc", intent.getStringExtra(EXTRA_DESC) ?: "")
            .put("time", intent.getStringExtra(EXTRA_TIME) ?: "")
            .put("sent_at", intent.getLongExtra(EXTRA_SENT_AT, 0L))
            .put("mode", mode)
            .put("test", intent.getBooleanExtra(EXTRA_TEST, false))
            .put("spoken", intent.getBooleanExtra(EXTRA_SPOKEN, false))
    }

    /** 她点了「知道了 / 稍后提醒」：把通知栏里那一条也收掉 */
    fun cancelNotification(context: Context, notificationId: Int) {
        if (notificationId == 0) return
        try {
            (context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).cancel(notificationId)
        } catch (e: Exception) {
            Log.w(TAG, "cancel notification failed: ${e.message}")
        }
    }

    /** 卡片没弹成（网页载不出来）而通知又是静音的：补一声系统提示音，别让提醒悄无声息 */
    fun fallbackSound(context: Context) {
        try {
            val uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
            RingtoneManager.getRingtone(context.applicationContext, uri)?.play()
        } catch (e: Exception) {
            Log.w(TAG, "fallback sound failed: ${e.message}")
        }
    }
}
