package moe.reimu.ancsreceiver.utils

import android.content.Context
import android.widget.Toast
import androidx.core.app.NotificationChannelCompat
import androidx.core.app.NotificationChannelGroupCompat
import androidx.core.app.NotificationManagerCompat
import moe.reimu.ancsreceiver.R

const val CHANNEL_ID_PERSIST = "persist"
const val CHANNEL_ID_MEDIA = "media"
const val NOTI_ID_PERSIST = 10
const val NOTI_ID_FWD_BASE = 20
const val NOTI_ID_MEDIA = 8
const val GROUP_ID_FWD = "fwd"
const val EXTRA_DEVICE_ADDRESS = "ancs.deviceAddress"

fun createChannels(context: Context) {
    val manager = NotificationManagerCompat.from(context)

    val channels = listOf(
        NotificationChannelCompat.Builder(
            CHANNEL_ID_PERSIST,
            NotificationManagerCompat.IMPORTANCE_LOW
        ).setName("Receiver persistent notification (can be disabled)").build(),
        NotificationChannelCompat.Builder(
            CHANNEL_ID_MEDIA,
            NotificationManagerCompat.IMPORTANCE_DEFAULT
        ).setName("Media Control").build()
    )

    manager.createNotificationChannelsCompat(channels)

    manager.createNotificationChannelGroupsCompat(
        listOf(
            NotificationChannelGroupCompat.Builder(GROUP_ID_FWD)
                .setName(context.getString(R.string.fwd_group_desc)).build()
        )
    )
}

fun Context.showBluetoothToast() {
    Toast.makeText(this, R.string.bluetooth_disabled, Toast.LENGTH_LONG).show()
}