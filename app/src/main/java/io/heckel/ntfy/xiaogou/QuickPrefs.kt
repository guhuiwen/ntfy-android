package io.heckel.ntfy.xiaogou

import android.content.Context
import android.content.SharedPreferences
import io.heckel.ntfy.R

/**
 * 快捷唤醒的本地设置（小狗之心）。
 *
 * 服务器地址、称呼这些都只存在手机上：地址从主人粘贴的登录链接里取，称呼从服务器的配置里取。
 * 这个仓库是公开的，代码里不写任何地址、钥匙和唤醒词。
 */
object QuickPrefs {
    const val BUILD = "qw-2"
    const val DEFAULT_PAGE = "/quick/overlay.html"

    private const val FILE = "xiaogou_quick"
    private const val KEY_ORIGIN = "origin"
    private const val KEY_NAME = "display_name"
    private const val KEY_PAGE = "page_path"
    private const val KEY_ENTRY = "entry_notification"
    private const val KEY_DEBUG = "web_debug"
    private const val KEY_REMIND = "remind_card"

    private fun sp(context: Context): SharedPreferences =
        context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    /** 「https://主机」；空串 = 还没登录过 */
    fun origin(context: Context): String = sp(context).getString(KEY_ORIGIN, "") ?: ""

    fun setOrigin(context: Context, origin: String) {
        sp(context).edit().putString(KEY_ORIGIN, origin).apply()
    }

    /** 通知、磁贴上显示的称呼：服务器配置里的第一个唤醒词；还没拿到就用中性的「快捷唤醒」 */
    fun displayName(context: Context): String {
        val name = sp(context).getString(KEY_NAME, "") ?: ""
        return name.ifEmpty { context.getString(R.string.xiaogou_quick_title) }
    }

    fun setDisplayName(context: Context, name: String) {
        sp(context).edit().putString(KEY_NAME, name.trim()).apply()
    }

    /** 浮层那张网页在服务器上的路径（调试时可以换成别的页面） */
    fun pagePath(context: Context): String {
        val path = sp(context).getString(KEY_PAGE, "") ?: ""
        return if (path.startsWith("/")) path else DEFAULT_PAGE
    }

    fun setPagePath(context: Context, path: String) {
        sp(context).edit().putString(KEY_PAGE, path.trim()).apply()
    }

    fun entryEnabled(context: Context): Boolean = sp(context).getBoolean(KEY_ENTRY, true)

    fun setEntryEnabled(context: Context, enabled: Boolean) {
        sp(context).edit().putBoolean(KEY_ENTRY, enabled).apply()
    }

    /** 调试开关：把网页里的日志记进手机日志、允许连着的电脑查看这张网页 */
    fun webDebug(context: Context): Boolean = sp(context).getBoolean(KEY_DEBUG, true)

    fun setWebDebug(context: Context, enabled: Boolean) {
        sp(context).edit().putBoolean(KEY_DEBUG, enabled).apply()
    }

    /** 提醒到点时直接弹卡片（关掉 = 只有普通通知） */
    fun remindCard(context: Context): Boolean = sp(context).getBoolean(KEY_REMIND, true)

    fun setRemindCard(context: Context, enabled: Boolean) {
        sp(context).edit().putBoolean(KEY_REMIND, enabled).apply()
    }

    fun clearLogin(context: Context) {
        sp(context).edit().remove(KEY_ORIGIN).remove(KEY_NAME).apply()
    }
}
