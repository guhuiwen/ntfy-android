package io.heckel.ntfy.xiaogou

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
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
    private lateinit var bluetoothStatus: TextView
    private lateinit var earStatus: TextView
    private lateinit var miuiStatus: TextView
    private val ticker = Handler(Looper.getMainLooper())
    private val tick = object : Runnable {
        override fun run() {
            refreshEar()
            ticker.postDelayed(this, 1500)
        }
    }
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
        ticker.removeCallbacks(tick)
        ticker.post(tick)
    }

    override fun onPause() {
        ticker.removeCallbacks(tick)
        super.onPause()
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
        bluetoothStatus = permissionRow("蓝牙耳机（可选）", "去允许") {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                permissionLauncher.launch(Manifest.permission.BLUETOOTH_CONNECT)
            }
        }
        miuiStatus = permissionRow("小米手机再多做一步", "去打开") { openMiuiPermissions() }

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

        // ④ 提醒卡片
        column.addView(text("④ 提醒卡片", 16f, bold = true, top = 22))
        val remindSwitch = MaterialSwitch(this)
        remindSwitch.text = "提醒到点直接弹卡片，并用他的声音念"
        remindSwitch.isChecked = QuickPrefs.remindCard(this)
        remindSwitch.setOnCheckedChangeListener { _, checked -> QuickPrefs.setRemindCard(this, checked) }
        column.addView(remindSwitch, rowParams(4))
        column.addView(text("手机亮着时是屏幕下方的小卡片；锁屏时像闹钟那样亮屏显示。关掉就只有普通通知。", 13f, dim = true, top = 2))
        val remindRow = LinearLayout(this)
        remindRow.orientation = LinearLayout.HORIZONTAL
        remindRow.addView(button("试提醒卡片", top = 0) { tryRemind(false) })
        val lockTry = button("试锁屏卡片", top = 0) { tryRemind(true) }
        (lockTry.layoutParams as LinearLayout.LayoutParams).marginStart = dp(10)
        remindRow.addView(lockTry)
        column.addView(remindRow, rowParams(4))

        // ⑤ 耳朵
        column.addView(text("⑤ 耳朵（一直听着，喊一声就来）", 16f, bold = true, top = 22))
        earStatus = text("", 13f, dim = true, top = 4)
        column.addView(earStatus)
        val earRow = LinearLayout(this)
        earRow.orientation = LinearLayout.HORIZONTAL
        earRow.addView(button("开始听", top = 0) { earOn() })
        val earStop = button("先别听", top = 0) { earOff() }
        (earStop.layoutParams as LinearLayout.LayoutParams).marginStart = dp(10)
        earRow.addView(earStop)
        column.addView(earRow, rowParams(4))
        column.addView(text("灵敏度（改了会重新开始听）", 13f, dim = true, top = 10))
        val levelRow = LinearLayout(this)
        levelRow.orientation = LinearLayout.HORIZONTAL
        levelRow.addView(button("不容易误醒", top = 0) { setEarLevel(0.35f) })
        val mid = button("适中", top = 0) { setEarLevel(0.25f) }
        (mid.layoutParams as LinearLayout.LayoutParams).marginStart = dp(8)
        levelRow.addView(mid)
        val keen = button("更灵", top = 0) { setEarLevel(0.15f) }
        (keen.layoutParams as LinearLayout.LayoutParams).marginStart = dp(8)
        levelRow.addView(keen)
        column.addView(levelRow, rowParams(2))
        column.addView(text("开着的时候状态栏会一直有麦克风的小绿点，也会多费一点电。手机重启后要在通知上点一下才重新开始听。控制中心那颗小猪按钮就是它的开关。", 12f, dim = true, top = 4))

        // 试一下
        column.addView(text("试一下快捷唤醒", 16f, bold = true, top = 22))
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
        val bluetooth = Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
            ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED
        bluetoothStatus.text = if (bluetooth) "已允许 ✓" else "不戴蓝牙耳机可以不管"
        val lockShow = when (RemindCenter.miuiLockScreenAllowed(this)) {
            true -> "\n锁屏显示：已打开 ✓"
            false -> "\n锁屏显示：还没打开 → 锁屏时提醒只有声音，不会亮屏弹卡片"
            else -> ""
        }
        miuiStatus.text = "把「后台弹出界面」「显示悬浮窗」「锁屏显示」三个都打开；省电策略选「无限制」；允许自启动$lockShow"
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

    private fun refreshEar() {
        val threshold = QuickPrefs.earThreshold(this)
        val level = if (threshold >= 0.3f) "不容易误醒" else if (threshold <= 0.2f) "更灵" else "适中"
        val state = when {
            !EarEngine.hasModel(this) -> "这个包里没带唤醒词模型"
            QuickOverlayService.earRunning -> "在听着呢 ✓"
            QuickPrefs.earWanted(this) -> "该在听、但现在没在听：点「开始听」"
            else -> "没在听"
        }
        val last = QuickPrefs.lastWake(this)
        val woke = if (last > 0) {
            "，一共被喊醒 ${QuickPrefs.wakeCount(this)} 次，上一次 " + java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.US).format(java.util.Date(last))
        } else {
            ""
        }
        earStatus.text = "$state$woke\n灵敏度：$level · 应答存了 ${QuickLines.count(this)} 句 · 唤醒词 ${QuickPrefs.wakeKeywords(this).size} 行"
    }

    private fun earOn() {
        if (!QuickOverlayService.canStart(this)) {
            toast("先把上面的登录和麦克风、悬浮窗两个权限弄好")
            return
        }
        if (!EarEngine.hasModel(this)) {
            toast("这个包里没带唤醒词模型")
            return
        }
        open(Intent(this, QuickWakeActivity::class.java).putExtra(QuickWakeActivity.EXTRA_EAR, true))
    }

    private fun earOff() {
        try {
            startService(Intent(this, QuickOverlayService::class.java).setAction(QuickOverlayService.ACTION_EAR_OFF))
        } catch (e: Exception) {
            Log.w(TAG, "ear off failed: ${e.message}")
        }
    }

    private fun setEarLevel(value: Float) {
        QuickPrefs.setEarThreshold(this, value)
        if (QuickOverlayService.earRunning) {
            earOff()
            ticker.postDelayed({ earOn() }, 900)
        }
        refreshEar()
    }

    private fun tryRemind(lock: Boolean) {
        if (QuickPrefs.origin(this).isEmpty() || !Settings.canDrawOverlays(this)) {
            toast("先把上面的登录和悬浮窗权限弄好")
            return
        }
        if (lock) {
            toast("现在把手机锁屏，8 秒后弹出来")
            val app = applicationContext
            Handler(Looper.getMainLooper()).postDelayed({ RemindCenter.test(app, true) }, 8000)
        } else {
            RemindCenter.test(applicationContext, false)
        }
    }

    /** 让悬浮窗服务停掉：下次喊的时候按新的设置重新载入网页 */
    private fun restartOverlay() {
        try {
            stopService(Intent(this, QuickOverlayService::class.java))
        } catch (e: Exception) {
            Log.w(TAG, "stop overlay failed: ${e.message}")
        }
    }

    /** 直接进小米的「权限管理」那一页；进不去（不是小米 / 系统改了）就退回应用详情 */
    private fun openMiuiPermissions() {
        try {
            startActivity(
                Intent("miui.intent.action.APP_PERM_EDITOR")
                    .setClassName("com.miui.securitycenter", "com.miui.permcenter.permissions.PermissionsEditorActivity")
                    .putExtra("extra_pkgname", packageName)
            )
        } catch (e: Exception) {
            open(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
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
