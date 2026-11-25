package com.neko233.vpn233.service

import android.content.Intent
import android.net.VpnService
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import com.neko233.vpn233.MainActivity
import com.neko233.vpn233.data.VpnStatus

class Vpn233TileService : TileService() {
    override fun onStartListening() {
        super.onStartListening()
        syncTile()
    }

    override fun onClick() {
        super.onClick()
        when (Vpn233Service.status.value) {
            VpnStatus.RUNNING, VpnStatus.STARTING -> {
                Vpn233Service.stop(this)
            }
            else -> {
                if (VpnService.prepare(this) != null) {
                    val intent = Intent(this, MainActivity::class.java)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                        startActivityAndCollapse(
                            android.app.PendingIntent.getActivity(
                                this,
                                0,
                                intent,
                                android.app.PendingIntent.FLAG_IMMUTABLE
                            )
                        )
                    } else {
                        @Suppress("DEPRECATION")
                        startActivityAndCollapse(intent)
                    }
                } else {
                    Vpn233Service.start(this)
                }
            }
        }
        syncTile()
    }

    private fun syncTile() {
        val tile = qsTile ?: return
        val running = Vpn233Service.status.value == VpnStatus.RUNNING
        val starting = Vpn233Service.status.value == VpnStatus.STARTING
        tile.state = when {
            running || starting -> Tile.STATE_ACTIVE
            else -> Tile.STATE_INACTIVE
        }
        val node = Vpn233Service.currentNodeName.value
        tile.label = if (running && node.isNotBlank()) node else "vpn233"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            tile.subtitle = when (Vpn233Service.status.value) {
                VpnStatus.RUNNING -> "已连接"
                VpnStatus.STARTING -> "连接中"
                VpnStatus.STOPPING -> "断开中"
                VpnStatus.STOPPED -> "未连接"
            }
        }
        tile.updateTile()
    }
}
