package io.heckel.ntfy.xiaogou

import android.app.KeyguardManager
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.media.RingtoneManager
import android.net.Uri
import android.os.PowerManager
import android.provider.Settings
import android.util.Log
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
 *   · 手机亮着、没锁 → 悬浮卡片（[RemindOverlayService]），那条通知改成静音（他会用声音念）；
 *   · 锁屏 / 息屏     → 全屏卡片（[RemindLockActivity]），通知照常响（万一系统不让弹，至少还有通知）；
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

    private const val MAX_AGE_SECONDS = 10 * 60L      // 迟到超过这么久（手机当时没连着）就只当普通通知

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
            val locked = isLocked(context)
            val intent = Intent(context, if (locked) RemindLockActivity::class.java else RemindOverlayService::class.java)
                .putExtra(EXTRA_ID, notification.id)
                .putExtra(EXTRA_TITLE, title)
                .putExtra(EXTRA_DESC, uri.getQueryParameter("r_desc") ?: "")
                .putExtra(EXTRA_TIME, uri.getQueryParameter("r_time") ?: "")
                .putExtra(EXTRA_SENT_AT, notification.timestamp)
                .putExtra(EXTRA_NOTIFICATION_ID, notification.notificationId)
            return start(context, intent, locked)
        } catch (e: Exception) {
            Log.w(TAG, "reminder card failed: ${e.javaClass.simpleName} ${e.message}")
            return NONE
        }
    }

    /** 设置页里的「试一下」：弹一张测试卡片（不连提醒、不会真的再提醒） */
    fun test(context: Context, forceLock: Boolean) {
        val now = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())
        val locked = forceLock || isLocked(context)
        val intent = Intent(context, if (locked) RemindLockActivity::class.java else RemindOverlayService::class.java)
            .putExtra(EXTRA_ID, "test")
            .putExtra(EXTRA_TITLE, "喝水")
            .putExtra(EXTRA_DESC, "这是一张测试卡片：到点的提醒就长这样")
            .putExtra(EXTRA_TIME, now)
            .putExtra(EXTRA_SENT_AT, System.currentTimeMillis() / 1000)
            .putExtra(EXTRA_NOTIFICATION_ID, 0)
            .putExtra(EXTRA_TEST, true)
        try {
            start(context, intent, locked)
        } catch (e: Exception) {
            Log.w(TAG, "test card failed: ${e.javaClass.simpleName} ${e.message}")
        }
    }

    private fun start(context: Context, intent: Intent, locked: Boolean): Int {
        return if (locked) {
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_USER_ACTION)
            context.startActivity(intent)
            Log.d(TAG, "reminder -> lock screen card")
            LOCK
        } else {
            context.startService(intent)
            Log.d(TAG, "reminder -> overlay card")
            OVERLAY
        }
    }

    fun isLocked(context: Context): Boolean {
        val keyguard = context.getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager
        val power = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        return keyguard.isKeyguardLocked || !power.isInteractive
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
