package moe.reimu.ancsreceiver.ble

import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import moe.reimu.ancsreceiver.BleDevice
import java.util.UUID

class BleServerCharacteristic(
    val uuid: UUID,
    val onRead: ((BluetoothDevice) -> ByteArray)? = null,
    val onWrite: ((BluetoothDevice, ByteArray) -> Unit)? = null,
    val canNotify: Boolean = false,
) {
    fun toNativeCharacteristic(): BluetoothGattCharacteristic {
        var properties = 0
        var permissions = 0
        if (onRead != null) {
            properties =BluetoothGattCharacteristic.PROPERTY_READ
            permissions = BluetoothGattCharacteristic.PERMISSION_READ
        }
        if (onWrite != null) {
            properties = properties or BluetoothGattCharacteristic.PROPERTY_WRITE
            permissions = permissions or BluetoothGattCharacteristic.PERMISSION_WRITE
        }
        if (canNotify) {
            properties = properties or BluetoothGattCharacteristic.PROPERTY_NOTIFY
        }
        return BluetoothGattCharacteristic(uuid, properties, permissions).apply {
            if (canNotify) {
                addDescriptor(
                    BluetoothGattDescriptor(
                        BleDevice.clientConfigurationUuid,
                        BluetoothGattCharacteristic.PERMISSION_READ or BluetoothGattCharacteristic.PERMISSION_WRITE,
                    )
                )
            }
        }
    }
}