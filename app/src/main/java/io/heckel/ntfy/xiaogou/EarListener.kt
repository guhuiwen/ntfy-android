package io.heckel.ntfy.xiaogou

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Process
import android.util.Log
import kotlin.math.sqrt

/**
 * 耳朵：一条后台线程一直录着音、喂给唤醒词引擎。
 *
 * 三种状态：
 *   LISTEN     在听唤醒词；
 *   AFTER_WAKE 刚听到了：不再找唤醒词，但接着录——她常常一口气把话说完（「××现在天气怎么样」），
 *              这一段要留着，等对话那头（悬浮窗里的网页）连上服务器、开了它自己的麦克风，再整段补上去；
 *   CLOSED     对话那头接手麦克风了：这边把麦克风放掉，直到 resume() 再回来听（他说话时耳朵闭着——他自己也会念那个名字）。
 */
class EarListener(private val context: Context, private val callback: Callback) {
    interface Callback {
        /** 引擎准备好了没有（这几个回调都在耳朵的线程上，自己转回主线程） */
        fun onEarReady(ok: Boolean)

        /** 听到唤醒词了（立刻） */
        fun onEarWake(keyword: String)

        /** 听到之后约 0.4 秒：她是不是接着在说话（是 = 不放应答，直接听） */
        fun onEarWakeSpeech(continued: Boolean)
    }

    private val lock = Any()
    private val ring = ShortArray(EarEngine.SAMPLE_RATE * RING_SECONDS)
    private var written = 0L                // 一共录进来多少个采样
    private var prerollFrom = -1L           // 「喊醒之后她说的话」从哪个采样算起；-1 = 没有
    private var thread: Thread? = null

    @Volatile
    private var running = false

    @Volatile
    private var mode = MODE_LISTEN

    val isRunning: Boolean
        get() = running

    fun start(keywordLines: List<String>, threshold: Float) {
        if (running) return
        running = true
        mode = MODE_LISTEN
        thread = Thread({ loop(keywordLines, threshold) }, "xiaogou-ear").also { it.start() }
    }

    fun stop() {
        running = false
        thread = null
    }

    /** 对话结束了：回来接着听 */
    fun resume() {
        synchronized(lock) { prerollFrom = -1 }
        mode = MODE_LISTEN
    }

    /** 这次不用补录音了（比如是手点的喊醒）：把麦克风让出来 */
    fun close() {
        synchronized(lock) { prerollFrom = -1 }
        mode = MODE_CLOSED
    }

    /** 应答放完了：从现在起录到的才算她说的话（应答自己的声音不能算进去） */
    fun markPrerollFromNow() {
        synchronized(lock) {
            if (mode == MODE_AFTER_WAKE) prerollFrom = written
        }
    }

    /** 对话那头的麦克风开了：把「喊醒之后她说的话」交出去（16kHz 单声道 16 位小端），然后这边放掉麦克风 */
    fun takePreroll(): ByteArray {
        val out = synchronized(lock) {
            val from = prerollFrom
            prerollFrom = -1
            if (mode != MODE_AFTER_WAKE || from < 0) {
                ByteArray(0)
            } else {
                var start = from
                if (written - MAX_PREROLL > start) start = written - MAX_PREROLL
                if (written - ring.size > start) start = written - ring.size
                if (start < 0) start = 0
                val count = (written - start).toInt().coerceAtLeast(0)
                val bytes = ByteArray(count * 2)
                for (i in 0 until count) {
                    val sample = ring[((start + i) % ring.size).toInt()].toInt()
                    bytes[i * 2] = (sample and 0xff).toByte()
                    bytes[i * 2 + 1] = ((sample shr 8) and 0xff).toByte()
                }
                bytes
            }
        }
        mode = MODE_CLOSED
        return out
    }

    private fun held(audio: AudioManager): Boolean {
        // 我们自己正在念提醒 / 手机正在通话或视频通话：先不找唤醒词（他的声音里也会有那个名字）
        if (System.currentTimeMillis() < QuickOverlayService.earHoldUntil) return true
        val audioMode = audio.mode
        return audioMode == AudioManager.MODE_IN_COMMUNICATION || audioMode == AudioManager.MODE_IN_CALL
    }

    private fun loop(keywordLines: List<String>, threshold: Float) {
        val engine = try {
            EarEngine.create(context, keywordLines, threshold)
        } catch (e: Throwable) {
            Log.w(TAG, "ear: engine failed: ${e.javaClass.simpleName} ${e.message}")
            null
        }
        if (engine == null) {
            running = false
            callback.onEarReady(false)
            return
        }
        callback.onEarReady(true)
        try {
            Process.setThreadPriority(Process.THREAD_PRIORITY_AUDIO)
        } catch (_: Exception) {
        }
        val audio = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val frame = ShortArray(FRAME)
        var record: AudioRecord? = null
        var afterWake = 0
        var loud = 0
        var lastMode = MODE_LISTEN
        try {
            while (running) {
                val now = mode
                if (now == MODE_CLOSED) {
                    if (record != null) {
                        release(record)
                        record = null
                    }
                    lastMode = now
                    Thread.sleep(120)
                    continue
                }
                if (record == null) {
                    record = open()
                    if (record == null) {
                        Thread.sleep(1500)
                        continue
                    }
                    engine.newStream()
                }
                if (now == MODE_LISTEN && lastMode != MODE_LISTEN) {
                    engine.newStream()
                }
                if (now == MODE_AFTER_WAKE && lastMode != MODE_AFTER_WAKE) {
                    afterWake = 0
                    loud = 0
                }
                lastMode = now
                val n = record.read(frame, 0, frame.size)
                if (n <= 0) {
                    Log.w(TAG, "ear: microphone read failed ($n), reopening")
                    release(record)
                    record = null
                    Thread.sleep(500)
                    continue
                }
                synchronized(lock) {
                    for (i in 0 until n) {
                        ring[((written + i) % ring.size).toInt()] = frame[i]
                    }
                    written += n
                }
                if (now == MODE_LISTEN) {
                    if (held(audio)) continue
                    val samples = FloatArray(n)
                    for (i in 0 until n) samples[i] = frame[i] / 32768f
                    val hit = engine.accept(samples)
                    if (hit != null) {
                        synchronized(lock) { prerollFrom = (written - EarEngine.SAMPLE_RATE / 5).coerceAtLeast(0) }
                        mode = MODE_AFTER_WAKE
                        Log.d(TAG, "ear: heard the wake word")
                        callback.onEarWake(hit)
                    }
                } else {
                    afterWake++
                    if (rms(frame, n) > SPEECH_RMS) loud++
                    if (afterWake == DECIDE_FRAMES) {
                        callback.onEarWakeSpeech(loud >= DECIDE_FRAMES - 1)
                    }
                    if (afterWake > MAX_AFTER_WAKE_FRAMES) {
                        // 等了这么久对话那头都没来接手：算了，回去接着听
                        Log.w(TAG, "ear: nobody took over after the wake, listening again")
                        synchronized(lock) { prerollFrom = -1 }
                        mode = MODE_LISTEN
                    }
                }
            }
        } catch (e: InterruptedException) {
            // 停了
        } catch (e: Throwable) {
            Log.w(TAG, "ear: loop crashed: ${e.javaClass.simpleName} ${e.message}")
        } finally {
            if (record != null) release(record)
            engine.release()
            running = false
            Log.d(TAG, "ear: stopped")
        }
    }

    @SuppressLint("MissingPermission")
    private fun open(): AudioRecord? {
        return try {
            val min = AudioRecord.getMinBufferSize(EarEngine.SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
            val record = AudioRecord(
                MediaRecorder.AudioSource.VOICE_RECOGNITION, EarEngine.SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, maxOf(min, FRAME * 2 * 4)
            )
            if (record.state != AudioRecord.STATE_INITIALIZED) {
                record.release()
                Log.w(TAG, "ear: microphone would not open")
                return null
            }
            record.startRecording()
            if (record.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
                record.release()
                Log.w(TAG, "ear: microphone would not start")
                return null
            }
            record
        } catch (e: Exception) {
            Log.w(TAG, "ear: opening microphone failed: ${e.javaClass.simpleName} ${e.message}")
            null
        }
    }

    private fun release(record: AudioRecord) {
        try {
            record.stop()
        } catch (_: Exception) {
        }
        try {
            record.release()
        } catch (_: Exception) {
        }
    }

    private fun rms(frame: ShortArray, n: Int): Double {
        if (n <= 0) return 0.0
        var sum = 0.0
        for (i in 0 until n) {
            val v = frame[i] / 32768.0
            sum += v * v
        }
        return sqrt(sum / n)
    }

    companion object {
        private const val TAG = "XiaogouQuick"
        private const val MODE_LISTEN = 0
        private const val MODE_AFTER_WAKE = 1
        private const val MODE_CLOSED = 2
        private const val FRAME = 1600                               // 100 毫秒
        private const val RING_SECONDS = 10
        private const val MAX_PREROLL = EarEngine.SAMPLE_RATE * 8L   // 最多补 8 秒
        private const val DECIDE_FRAMES = 4                          // 听到之后看 0.4 秒
        private const val MAX_AFTER_WAKE_FRAMES = 200                // 20 秒没人接手就回去听
        private const val SPEECH_RMS = 0.02                          // 比这个响算她在出声
    }
}
