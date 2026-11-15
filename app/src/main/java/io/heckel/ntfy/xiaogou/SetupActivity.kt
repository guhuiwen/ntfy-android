package io.heckel.ntfy.xiaogou

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.text.InputType
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.webkit.CookieManager
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.google.android.material.button.MaterialButton
import com.google.android.material.materialswitch.MaterialSwitch
import io.heckel.ntfy.R

/**
 * 快捷唤醒的设置页（小狗之心）：登录一次、给权限、开关入口、试一下。
 * 界面直接用代码搭（就这一页，不值得再配一套布局文件）。
 */
class SetupActivity : AppCompatActivity() {
    private lateinit var column: LinearLayout
    private lateinit var loginStatus: TextView
    private lateinit var linkInput: EditText
    private lateinit var loginButton: MaterialButton
    private lateinit var micStatus: TextView
    private lateinit var overlayStatus: TextView
    private lateinit var notifyStatus: TextView
    private lateinit var pageInput: EditText

    // 问系统要权限（麦克风 / 通知），她点完以后把状态刷新一下
    private val permissionLauncher = registerForActivityResult(ActivityResultContracts.RequestPermission()) {
        refreshPermissions()
        QuickEntry.refresh(this)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        try {
            CookieManager.getInstance()      // 先在主线程把 App 自带的浏览器内核叫起来，后台线程才好用它的 cookie
        } catch (e: Exception) {
            Log.w(TAG, "cookie manager unavailable: ${e.message}")
        }
        title = getString(R.string.xiaogou_quick_title)
        setContentView(buildContent())
        checkLogin()
    }

    override fun onResume() {
        super.onResume()
        refreshPermissions()
    }

    // ---------------------------------------------------------------- 搭界面 ----------------------------------------------------------------

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private fun buildContent(): View {
        column = LinearLayout(this)
        column.orientation = LinearLayout.VERTICAL
        column.setPadding(dp(20), dp(20), dp(20), dp(32))
        val scroll = ScrollView(this)
        scroll.isFillViewport = true
        scroll.addView(column, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        ViewCompat.setOnApplyWindowInsetsListener(scroll) { view, insets ->
            val bars = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout() or WindowInsetsCompat.Type.ime()
            )
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }

        column.addView(text(getString(R.string.xiaogou_quick_title), 22f, bold = true))
        column.addView(text("喊一声，他就用声音答、顺手把事办了。先做完下面三步。（版本 ${QuickPrefs.BUILD}）", 13f, dim = true, top = 4))

        // ① 登录
        column.addView(text("① 登录", 16f, bold = true, top = 22))
        loginStatus = text("正在看登录还在不在……", 13f, dim = true, top = 4)
        column.addView(loginStatus)
        linkInput = EditText(this)
        linkInput.hint = "把登录链接粘贴到这里"
        linkInput.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        linkInput.setSingleLine(true)
        linkInput.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
        column.addView(linkInput, rowParams(6))
        val loginRow = LinearLayout(this)
        loginRow.orientation = LinearLayout.HORIZONTAL
        loginButton = button("登录", top = 0) { doLogin() }
        loginRow.addView(loginButton)
        val logout = button("退出登录", top = 0) { doLogout() }
        (logout.layoutParams as LinearLayout.LayoutParams).marginStart = dp(10)
        loginRow.addView(logout)
        column.addView(loginRow, rowParams(2))
        column.addView(text("链接只用这一次，App 不保存它，只留下登录状态。", 12f, dim = true, top = 2))

        // ② 权限
        column.addView(text("② 权限", 16f, bold = true, top = 22))
        micStatus = permissionRow("麦克风", "去允许") {
            permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
        }
        overlayStatus = permissionRow("悬浮窗（显示在其他应用上层）", "去打开") {
            open(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
        }
        notifyStatus = permissionRow("通知", "去允许") {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                permissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
        permissionRow("小米手机再多做一步", "应用详情") {
            open(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
        }.text = "权限管理里打开「后台弹出界面」「显示悬浮窗」；省电策略选「无限制」；允许自启动"

        // ③ 入口
        column.addView(text("③ 入口", 16f, bold = true, top = 22))
        val entrySwitch = MaterialSwitch(this)
        entrySwitch.text = "通知栏里常驻一条，点一下就喊他"
        entrySwitch.isChecked = QuickPrefs.entryEnabled(this)
        entrySwitch.setOnCheckedChangeListener { _, checked ->
            QuickPrefs.setEntryEnabled(this, checked)
            QuickEntry.refresh(this)
        }
        column.addView(entrySwitch, rowParams(4))
        column.addView(text("控制中心的按钮：下拉控制中心 → 编辑 → 把带小猪图标的那颗拖进去。", 13f, dim = true, top = 2))

        // 试一下
        column.addView(text("试一下", 16f, bold = true, top = 22))
        val tryRow = LinearLayout(this)
        tryRow.orientation = LinearLayout.HORIZONTAL
        tryRow.addView(button("喊一声", top = 0) { tryWake(false) })
        val probe = button("试小猪片子", top = 0) { tryWake(true) }
        (probe.layoutParams as LinearLayout.LayoutParams).marginStart = dp(10)
        tryRow.addView(probe)
        column.addView(tryRow, rowParams(4))

        // 调试
        column.addView(text("调试", 16f, bold = true, top = 22))
        val debugSwitch = MaterialSwitch(this)
        debugSwitch.text = "记日志、允许连着的电脑查看浮层网页"
        debugSwitch.isChecked = QuickPrefs.webDebug(this)
        debugSwitch.setOnCheckedChangeListener { _, checked ->
            QuickPrefs.setWebDebug(this, checked)
            restartOverlay()
        }
        column.addView(debugSwitch, rowParams(4))
        pageInput = EditText(this)
        pageInput.hint = QuickPrefs.DEFAULT_PAGE
        pageInput.setText(QuickPrefs.pagePath(this))
        pageInput.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        pageInput.setSingleLine(true)
        pageInput.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
        column.addView(text("浮层网页的路径（一般不用动）", 13f, dim = true, top = 8))
        column.addView(pageInput, rowParams(2))
        column.addView(button("保存路径并重新载入", top = 2) {
            QuickPrefs.setPagePath(this, pageInput.text?.toString() ?: "")
            pageInput.setText(QuickPrefs.pagePath(this))
            restartOverlay()
            toast("好了，下次喊他会重新载入网页")
        })
        return scroll
    }

    private fun rowParams(top: Int): LinearLayout.LayoutParams {
        val params = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        params.topMargin = dp(top)
        return params
    }

    private fun text(value: String, sizeSp: Float, bold: Boolean = false, dim: Boolean = false, top: Int = 0): TextView {
        val view = TextView(this)
        view.text = value
        view.setTextSize(TypedValue.COMPLEX_UNIT_SP, sizeSp)
        if (bold) view.setTypeface(view.typeface, Typeface.BOLD)
        if (dim) view.alpha = 0.66f
        view.setLineSpacing(0f, 1.25f)
        view.layoutParams = rowParams(top)
        return view
    }

    private fun button(label: String, top: Int = 8, onClick: () -> Unit): MaterialButton {
        val view = MaterialButton(this)
        view.text = label
        view.isAllCaps = false
        view.setOnClickListener { onClick() }
        val params = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        params.topMargin = dp(top)
        view.layoutParams = params
        return view
    }

    /** 一行权限：左边名字 + 状态，右边按钮。返回那行状态文字，方便之后更新 */
    private fun permissionRow(title: String, action: String, onClick: () -> Unit): TextView {
        val status = text("", 13f, dim = true)
        val left = LinearLayout(this)
        left.orientation = LinearLayout.VERTICAL
        left.addView(text(title, 15f))
        left.addView(status)
        val row = LinearLayout(this)
        row.orientation = LinearLayout.HORIZONTAL
        row.gravity = Gravity.CENTER_VERTICAL
        row.addView(left, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        val go = button(action, top = 0, onClick = onClick)
        (go.layoutParams as LinearLayout.LayoutParams).marginStart = dp(10)
        row.addView(go)
        column.addView(row, rowParams(10))
        return status
    }

    // ---------------------------------------------------------------- 动作 ----------------------------------------------------------------

    private fun refreshPermissions() {
        val mic = ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        micStatus.text = if (mic) "已允许 ✓" else "还没允许"
        overlayStatus.text = if (Settings.canDrawOverlays(this)) "已打开 ✓" else "还没打开"
        val notify = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        notifyStatus.text = if (notify) "已允许 ✓" else "还没允许（入口通知要用）"
    }

    private fun showLogin(result: QuickSite.Result) {
        if (result.ok) {
            val host = Uri.parse(QuickPrefs.origin(this)).host ?: ""
            loginStatus.text = "已登录 ✓  $host · 称呼：${QuickPrefs.displayName(this)}"
        } else {
            loginStatus.text = result.message
        }
    }

    private fun checkLogin() {
        if (QuickPrefs.origin(this).isEmpty()) {
            loginStatus.text = "还没登录"
            return
        }
        Thread {
            val result = QuickSite.check(applicationContext)
            runOnUiThread {
                showLogin(result)
                QuickEntry.refresh(this)
            }
        }.start()
    }

    private fun doLogin() {
        val link = linkInput.text?.toString()?.trim() ?: ""
        if (link.isEmpty()) {
            toast("先把登录链接粘贴进来")
            return
        }
        loginButton.isEnabled = false
        loginStatus.text = "正在登录……"
        Thread {
            val result = QuickSite.login(applicationContext, link)
            runOnUiThread {
                loginButton.isEnabled = true
                if (result.ok) linkInput.setText("")
                showLogin(result)
                restartOverlay()
                QuickEntry.refresh(this)
            }
        }.start()
    }

    private fun doLogout() {
        restartOverlay()
        QuickSite.logout(this)
        loginStatus.text = "还没登录"
        QuickEntry.refresh(this)
    }

    private fun tryWake(probe: Boolean) {
        if (!QuickOverlayService.canStart(this)) {
            toast("先把上面的登录和麦克风、悬浮窗两个权限弄好")
            return
        }
        open(Intent(this, QuickWakeActivity::class.java).putExtra(QuickWakeActivity.EXTRA_PROBE, probe))
    }

    /** 让悬浮窗服务停掉：下次喊的时候按新的设置重新载入网页 */
    private fun restartOverlay() {
        try {
            stopService(Intent(this, QuickOverlayService::class.java))
        } catch (e: Exception) {
            Log.w(TAG, "stop overlay failed: ${e.message}")
        }
    }

    private fun open(intent: Intent) {
        try {
            startActivity(intent)
        } catch (e: Exception) {
            Log.w(TAG, "open failed: ${e.javaClass.simpleName} ${e.message}")
            toast("这台手机打不开这个设置页，请到系统设置里找一下")
        }
    }

    private fun toast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
    }

    companion object {
        private const val TAG = "XiaogouQuick"
    }
}
