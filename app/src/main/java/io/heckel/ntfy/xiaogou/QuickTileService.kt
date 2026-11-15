package io.heckel.ntfy.xiaogou

import android.annotation.SuppressLint
import android.content.Intent
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import android.util.Log

/**
 * 控制中心里的那颗按钮 = 耳朵的总开关：亮着 = 在听，喊一声就来；点一下关掉 = 先别听。
 * （这个包里没带唤醒词模型的话，它退回成「点一下 = 喊一声」。）
 */
class QuickTileService : TileService() {
    override fun onStartListening() {
        super.onStartListening()
        refresh()
    }

    private fun refresh() {
        try {
            val tile = qsTile ?: return
            val on = QuickOverlayService.earRunning
            tile.label = QuickPrefs.displayName(this)
            tile.state = if (on) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                tile.subtitle = if (on) "在听" else "没在听"
            }
            tile.updateTile()
        } catch (e: Exception) {
            Log.w(TAG, "tile update failed: ${e.message}")
        }
    }

    @SuppressLint("StartActivityAndCollapseDeprecated")
    override fun onClick() {
        super.onClick()
        try {
            if (QuickOverlayService.earRunning) {
                // 关：不用经过界面，直接告诉服务
                startService(Intent(this, QuickOverlayService::class.java).setAction(QuickOverlayService.ACTION_EAR_OFF))
                return
            }
            // 开（或者没有耳朵时的「喊一声」）：带麦克风的前台服务只能从界面起，所以先进透明中转页
            val ear = EarEngine.hasModel(this)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                startActivityAndCollapse(QuickEntry.wakeIntent(this, if (ear) 7 else 2, ear))
            } else {
                @Suppress("DEPRECATION")
                startActivityAndCollapse(
                    Intent(this, QuickWakeActivity::class.java)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        .putExtra(QuickWakeActivity.EXTRA_EAR, ear)
                )
            }
        } catch (e: Exception) {
            Log.w(TAG, "tile click failed: ${e.javaClass.simpleName} ${e.message}")
        }
    }

    companion object {
        private const val TAG = "XiaogouQuick"
    }
}
