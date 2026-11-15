package io.heckel.ntfy.xiaogou

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.app.NotificationCompat
import io.heckel.ntfy.R

/**
 * 快捷唤醒的入口：通知栏里常驻的一条（点一下 = 喊一声）。
 * 控制中心的那颗按钮在 [QuickTileService]。
 */
object QuickEntry {
    private const val TAG = "XiaogouQuick"
    const val CHANNEL_ENTRY = "xiaogou-quick-entry"
    const val CHANNEL_SESSION = "xiaogou-quick-session"
    const val NOTIFICATION_ENTRY_ID = 2600
    const val NOTIFICATION_SESSION_ID = 2601

    fun ensureChannels(context: Context) {
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val entry = NotificationChannel(CHANNEL_ENTRY, context.getString(R.string.xiaogou_quick_channel_entry), NotificationManager.IMPORTANCE_LOW)
        entry.setShowBadge(false)
        manager.createNotificationChannel(entry)
        val session = NotificationChannel(CHANNEL_SESSION, context.getString(R.string.xiaogou_quick_channel_session), NotificationManager.IMPORTANCE_LOW)
        session.setShowBadge(false)
        manager.createNotificationChannel(session)
    }

    /** 点下去直接进那张透明的中转页（安卓不许通知先进服务 / 广播再开界面） */
    fun wakeIntent(context: Context, requestCode: Int): PendingIntent {
        val intent = Intent(context, QuickWakeActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION)
        return PendingIntent.getActivity(context, requestCode, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }

    /** 按现在的设置把入口通知摆上 / 撤掉。没登录、或者开关关着，就不显示 */
    fun refresh(context: Context) {
        try {
            val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            if (!QuickPrefs.entryEnabled(context) || QuickPrefs.origin(context).isEmpty()) {
                manager.cancel(NOTIFICATION_ENTRY_ID)
                return
            }
            ensureChannels(context)
            val notification = NotificationCompat.Builder(context, CHANNEL_ENTRY)
                .setSmallIcon(R.drawable.ic_xiaogou_quick)
                .setContentTitle(QuickPrefs.displayName(context))
                .setContentText(context.getString(R.string.xiaogou_quick_entry_text))
                .setContentIntent(wakeIntent(context, 1))
                .setOngoing(true)
                .setShowWhen(false)
                .setSound(null)
                .setOnlyAlertOnce(true)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .build()
            manager.notify(NOTIFICATION_ENTRY_ID, notification)
        } catch (e: Exception) {
            Log.w(TAG, "entry notification failed: ${e.message}")
        }
    }
}
