package io.heckel.ntfy.xiaogou

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.webkit.CookieManager
import java.net.URLEncoder

/**
 * 用他的声音把一句话念出来（不靠网页）。
 *
 * 锁屏 / 息屏时提醒到点用它：那时候卡片不一定显示得出来（要系统肯让我们盖在锁屏上面），声音得有保证。
 * 声音取的是服务器上聊天语音条的那个口（没合成过就现合成），边下边放。
 */
object RemindVoice {
    private const val TAG = "XiaogouQuick"
    private val main = Handler(Looper.getMainLooper())
    private var player: MediaPlayer? = null
    private var pending: Runnable? = null

    /** 过 delayMs 毫秒念「标题……正文」（通知自己会先响一声，等它响完）。哪个线程调都行 */
    fun speakLater(context: Context, title: String, desc: String, delayMs: Long) {
        val app = context.applicationContext
        val head = clean(title)
        val body = clean(desc)
        val text = (if (body.isNotEmpty() && body != head) "$head……$body" else head).take(280)
        if (text.isEmpty()) return
        main.post {
            stopNow()
            val job = Runnable { start(app, text) }
            pending = job
            main.postDelayed(job, delayMs)
        }
    }

    /** 她点了「知道了 / 稍后提醒」：别念了 */
    fun stop() {
        main.post { stopNow() }
    }

    private fun stopNow() {
        pending?.let { main.removeCallbacks(it) }
        pending = null
        val old = player ?: return
        player = null
        try {
            old.release()
        } catch (_: Exception) {
        }
    }

    private fun start(context: Context, text: String) {
        pending = null
        val origin = QuickPrefs.origin(context)
        if (origin.isEmpty()) return
        try {
            val cookie = CookieManager.getInstance().getCookie(origin) ?: ""
            val uri = Uri.parse("$origin/api/voice/clip?t=" + URLEncoder.encode(text, "UTF-8"))
            val created = MediaPlayer()
            created.setAudioAttributes(
                AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ASSISTANT).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build()
            )
            created.setDataSource(context, uri, mapOf("Cookie" to cookie))
            created.setOnPreparedListener { it.start() }
            created.setOnCompletionListener { if (player === it) stopNow() }
            created.setOnErrorListener { failed, what, extra ->
                Log.w(TAG, "remind voice failed: what=$what extra=$extra")
                if (player === failed) stopNow()
                true
            }
            player = created
            // 他念的这一阵，耳朵先别找唤醒词（他的声音里也会有那个名字）
            QuickOverlayService.earHoldUntil = System.currentTimeMillis() + 20_000L
            created.prepareAsync()
            Log.d(TAG, "remind voice: speaking")
        } catch (e: Exception) {
            Log.w(TAG, "remind voice failed: ${e.javaClass.simpleName} ${e.message}")
            stopNow()
        }
    }

    /** 念之前把表情这类念不出来的东西去掉 */
    private fun clean(value: String): String {
        return value
            .replace(Regex("[\\x{1F000}-\\x{1FAFF}\\x{2600}-\\x{27BF}\\x{FE0F}\\x{200D}]"), "")
            .replace(Regex("\\s+"), " ")
            .trim()
    }
}
