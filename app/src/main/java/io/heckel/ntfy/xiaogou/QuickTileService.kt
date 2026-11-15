package io.heckel.ntfy.xiaogou

import android.annotation.SuppressLint
import android.content.Intent
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import android.util.Log

/**
 * 控制中心里的那颗按钮。现在：点一下 = 喊一声。
 * 以后装上「耳朵」（一直听着的唤醒词），它就变成「听 / 不听」的总开关。
 */
class QuickTileService : TileService() {
    override fun onStartListening() {
        super.onStartListening()
        try {
            val tile = qsTile ?: return
            tile.label = QuickPrefs.displayName(this)
            tile.state = Tile.STATE_INACTIVE
            tile.updateTile()
        } catch (e: Exception) {
            Log.w(TAG, "tile update failed: ${e.message}")
        }
    }

    @SuppressLint("StartActivityAndCollapseDeprecated")
    override fun onClick() {
        super.onClick()
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                startActivityAndCollapse(QuickEntry.wakeIntent(this, 2))
            } else {
                @Suppress("DEPRECATION")
                startActivityAndCollapse(Intent(this, QuickWakeActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }
        } catch (e: Exception) {
            Log.w(TAG, "tile click failed: ${e.javaClass.simpleName} ${e.message}")
        }
    }

    companion object {
        private const val TAG = "XiaogouQuick"
    }
}
