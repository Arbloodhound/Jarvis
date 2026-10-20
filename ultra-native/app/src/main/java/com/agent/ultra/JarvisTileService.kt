package com.agent.ultra

import android.content.ComponentName
import android.content.Context
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService

/** The "Jarvis" tile in the pull-down Quick Settings: one tap turns Jarvis on or off ([JarvisPower]). */
class JarvisTileService : TileService() {

    override fun onStartListening() {
        super.onStartListening()
        show()
    }

    override fun onClick() {
        super.onClick()
        JarvisPower.toggle(this)
        show()
    }

    private fun show() {
        val tile = qsTile ?: return
        val on = JarvisPower.isOn(this)
        tile.state = if (on) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        tile.label = "Jarvis"
        if (android.os.Build.VERSION.SDK_INT >= 29) tile.subtitle = if (on) "On" else "Off"
        tile.updateTile()
    }

    companion object {
        /** Redraw the tile after a switch made elsewhere (Settings). */
        fun refresh(ctx: Context) {
            try { requestListeningState(ctx, ComponentName(ctx, JarvisTileService::class.java)) } catch (_: Exception) {}
        }
    }
}
