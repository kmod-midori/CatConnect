package moe.reimu.ancsreceiver

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import android.util.Log
import moe.reimu.ancsreceiver.utils.BROADCAST_PERMISSION
import java.lang.ref.WeakReference
import kotlin.random.Random

class ReceiverTileService : TileService() {
    private class MyReceiver(tileService: ReceiverTileService) : BroadcastReceiver() {
        private val serviceRef = WeakReference(tileService)

        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == ServiceState.ACTION_UPDATE_RECEIVER_STATE) {
                serviceRef.get()?.setState(
                    intent.getBooleanExtra("isRunning", false)
                )
            }
        }

    }

    private var receiver: MyReceiver? = null

    @SuppressLint("StartActivityAndCollapseDeprecated")
    @Suppress("DEPRECATION")
    override fun onClick() {
        val intent = when (qsTile.state) {
            Tile.STATE_ACTIVE -> StartReceiverActivity.getIntent(this, true)
            Tile.STATE_INACTIVE -> StartReceiverActivity.getIntent(this, false)
            else -> null
        }

        intent?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        intent?.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TASK)

        if (intent != null) {
            if (Build.VERSION.SDK_INT >= 34) {
                startActivityAndCollapse(
                    PendingIntent.getActivity(
                        this,
                        Random.nextInt(),
                        intent,
                        PendingIntent.FLAG_IMMUTABLE
                    )
                )
            } else {
                startActivityAndCollapse(intent)
            }
        }
    }

    private fun setState(enabled: Boolean) {
        qsTile?.state = if (enabled) {
            Tile.STATE_ACTIVE
        } else {
            Tile.STATE_INACTIVE
        }
        qsTile?.updateTile()
    }

    override fun onStartListening() {
        super.onStartListening()
        Log.d("ReceiverTileService", "onStartListening")
        setState(false)

        val r = MyReceiver(this)
        registerReceiver(
            r, IntentFilter().apply {
                addAction(ServiceState.ACTION_UPDATE_RECEIVER_STATE)
            }, BROADCAST_PERMISSION, null, if (Build.VERSION.SDK_INT >= 33) {
                Context.RECEIVER_EXPORTED
            } else {
                0
            }
        )
        receiver = r

        sendBroadcast(ServiceState.getQueryIntent())
    }

    override fun onStopListening() {
        super.onStopListening()
        Log.d("ReceiverTileService", "onStopListening")
        receiver?.let {
            unregisterReceiver(it)
        }
        receiver = null
    }
}