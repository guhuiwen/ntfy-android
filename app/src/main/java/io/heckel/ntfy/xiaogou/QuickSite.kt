package io.heckel.ntfy.xiaogou

import android.content.Context
import android.net.Uri
import android.util.Log
import android.webkit.CookieManager
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * 和自家网站打交道的那几件事：登录（拿 cookie）、看登录还在不在。
 *
 * 登录用的是主人已有的登录链接：服务器在跳转里种下登录 cookie，我们把它存进 App 自带的浏览器数据里，
 * 之后悬浮窗里的网页就带着它访问。链接本身用完就丢，不保存、不进日志。
 */
object QuickSite {
    private const val TAG = "XiaogouQuick"

    data class Result(val ok: Boolean, val message: String)

    private val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .followRedirects(false)
            .followSslRedirects(false)
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .build()
    }

    /** 从登录链接里取出「https://主机[:端口]」；不是 https 链接返回 null */
    fun originOf(link: String): String? {
        val uri = try {
            Uri.parse(link.trim())
        } catch (_: Exception) {
            return null
        }
        val scheme = uri.scheme?.lowercase() ?: return null
        val host = uri.host ?: return null
        if (scheme != "https" || host.isEmpty()) return null
        return if (uri.port > 0 && uri.port != 443) "https://$host:${uri.port}" else "https://$host"
    }

    /** 用登录链接登一次。要在后台线程里调（主线程先调过一次 CookieManager.getInstance()） */
    fun login(context: Context, link: String): Result {
        val origin = originOf(link) ?: return Result(false, "这不像登录链接（要以 https:// 开头）")
        try {
            val cookies = CookieManager.getInstance()
            cookies.setAcceptCookie(true)
            val request = Request.Builder().url(link.trim()).get().build()
            client.newCall(request).execute().use { response ->
                val setCookies = response.headers("Set-Cookie")
                if (setCookies.isEmpty()) {
                    return Result(false, "服务器没有给登录凭证（返回 ${response.code}），链接对吗？")
                }
                setCookies.forEach { cookies.setCookie(origin, it) }
                cookies.flush()
            }
        } catch (e: Exception) {
            Log.w(TAG, "login failed: ${e.javaClass.simpleName}")
            return Result(false, "连不上服务器（${e.javaClass.simpleName}）")
        }
        QuickPrefs.setOrigin(context, origin)
        return check(context)
    }

    /** 看登录还在不在：带着 cookie 取一次快捷唤醒的配置，顺便把称呼记下来。要在后台线程里调 */
    fun check(context: Context): Result {
        val origin = QuickPrefs.origin(context)
        if (origin.isEmpty()) return Result(false, "还没登录")
        return try {
            val cookie = CookieManager.getInstance().getCookie(origin) ?: ""
            if (cookie.isEmpty()) return Result(false, "还没登录")
            val request = Request.Builder().url("$origin/api/call/quick/config").header("Cookie", cookie).get().build()
            client.newCall(request).execute().use { response ->
                if (response.code == 404) {
                    return Result(false, "登录失效了，重新粘贴一次登录链接")
                }
                if (response.code != 200) {
                    return Result(false, "服务器返回 ${response.code}，等一下再试")
                }
                val json = JSONObject(response.body?.string() ?: "{}")
                val name = json.optJSONArray("wake_words")?.optString(0, "") ?: ""
                if (name.isNotEmpty()) QuickPrefs.setDisplayName(context, name)
                if (json.optBoolean("enabled", true)) Result(true, "已登录") else Result(false, "服务器上快捷唤醒没开")
            }
        } catch (e: Exception) {
            Log.w(TAG, "check failed: ${e.javaClass.simpleName}")
            Result(false, "连不上服务器（${e.javaClass.simpleName}）")
        }
    }

    /** 退出登录：把 App 自带浏览器里的 cookie 清掉（这里面只有自家网站的） */
    fun logout(context: Context) {
        try {
            val cookies = CookieManager.getInstance()
            cookies.removeAllCookies(null)
            cookies.flush()
        } catch (_: Exception) {
        }
        QuickPrefs.clearLogin(context)
    }
}
