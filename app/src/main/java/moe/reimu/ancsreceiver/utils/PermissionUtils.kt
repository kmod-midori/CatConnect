package moe.reimu.ancsreceiver.utils

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import moe.reimu.ancsreceiver.BuildConfig

const val BROADCAST_PERMISSION = "${BuildConfig.APPLICATION_ID}.INTERNAL_BROADCASTS"

fun getRequiredPermissions(): List<String> {
    val requiredPermissions = mutableListOf(
        Manifest.permission.BLUETOOTH_SCAN,
        Manifest.permission.BLUETOOTH_CONNECT,
    )
    if (Build.VERSION.SDK_INT <= 32) {
        requiredPermissions.add(Manifest.permission.ACCESS_FINE_LOCATION)
    }
    if (Build.VERSION.SDK_INT >= 33) {
        requiredPermissions.add(Manifest.permission.POST_NOTIFICATIONS)
    }
    return requiredPermissions
}

fun Context.checkPermissions(): Boolean {
    val permissions = getRequiredPermissions()

    for (permission in permissions) {
        if (ContextCompat.checkSelfPermission(this, permission) != PackageManager.PERMISSION_GRANTED) {
            return false
        }
    }

    return true
}

fun Context.registerInternalBroadcastReceiver(receiver: BroadcastReceiver, filter: IntentFilter) {
    registerReceiver(receiver, filter, BROADCAST_PERMISSION, null, getReceiverFlags())
}

fun getReceiverFlags(): Int {
    return if (Build.VERSION.SDK_INT >= 33) {
        Context.RECEIVER_EXPORTED
    } else {
        0
    }
}