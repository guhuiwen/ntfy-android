package io.heckel.ntfy.xiaogou

import android.content.Context
import android.util.Log
import java.io.File
import java.security.MessageDigest

/**
 * 录好的应答（「我在……宝宝……」这类）：喊醒的那一刻由手机自己放，不用等服务器。
 *
 * 台词的字在服务器的配置里；声音是服务器用他的声音合成的，这里按台词把 mp3 下到手机上存着
 * （取的是聊天里语音条的那个口，同一句合成过就直接给）。台词改了，下次同步会下新的、删旧的。
 */
object QuickLines {
    private const val TAG = "XiaogouQuick"

    private fun dir(context: Context): File = File(context.filesDir, "quick_lines")

    private fun fileFor(context: Context, text: String): File {
        val digest = MessageDigest.getInstance("SHA-1").digest(text.toByteArray(Charsets.UTF_8))
        val name = digest.take(8).joinToString("") { "%02x".format(it) }
        return File(dir(context), "ack_$name.mp3")
    }

    /** 把还没存的应答下下来、把不再用的删掉。要在后台线程里调 */
    fun sync(context: Context, lines: List<String>) {
        try {
            val folder = dir(context)
            if (!folder.exists()) folder.mkdirs()
            val wanted = HashSet<String>()
            var fetched = 0
            for (text in lines) {
                val line = text.trim()
                if (line.isEmpty()) continue
                val file = fileFor(context, line)
                wanted.add(file.name)
                if (file.length() > 500) continue
                if (QuickSite.downloadVoice(context, line, file)) fetched++
            }
            folder.listFiles()?.forEach { if (it.name !in wanted) it.delete() }
            if (fetched > 0) Log.d(TAG, "lines: fetched $fetched new ack line(s)")
        } catch (e: Exception) {
            Log.w(TAG, "lines: sync failed: ${e.javaClass.simpleName} ${e.message}")
        }
    }

    /** 随机挑一句存好的应答；一句都没有返回 null */
    fun pick(context: Context): File? {
        return try {
            dir(context).listFiles()?.filter { it.length() > 500 }?.randomOrNull()
        } catch (_: Exception) {
            null
        }
    }

    fun count(context: Context): Int {
        return try {
            dir(context).listFiles()?.count { it.length() > 500 } ?: 0
        } catch (_: Exception) {
            0
        }
    }
}
