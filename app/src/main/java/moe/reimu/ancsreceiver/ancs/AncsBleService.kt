package moe.reimu.ancsreceiver.ancs

import android.bluetooth.BluetoothGattCharacteristic
import moe.reimu.ancsreceiver.BleDevice
import java.util.UUID

class AncsBleService(device: BleDevice) {
    val notificationSource: BluetoothGattCharacteristic
    val controlPoint: BluetoothGattCharacteristic
    val dataSource: BluetoothGattCharacteristic

    init {
        val service = device.findService(serviceUuid) ?: throw ServiceNotFoundException()
        val notificationSource = service.getCharacteristic(notificationSourceUuid)
        val controlPoint = service.getCharacteristic(controlPointUuid)
        val dataSource = service.getCharacteristic(dataSourceUuid)

        if (notificationSource == null || controlPoint == null || dataSource == null) {
            throw ServiceNotFoundException()
        }

        this.notificationSource = notificationSource
        this.controlPoint = controlPoint
        this.dataSource = dataSource
    }

    class ServiceNotFoundException : Exception("ANCS service not found")

    companion object {
        val serviceUuid = UUID.fromString("7905F431-B5CE-4E99-A40F-4B1E122D00D0")
        val notificationSourceUuid = UUID.fromString("9FBF120D-6301-42D9-8C58-25E699A21DBD")
        val controlPointUuid = UUID.fromString("69D1D8F3-45E1-49A8-9821-9BBDFDAAD9D9")
        val dataSourceUuid = UUID.fromString("22EAC6E9-24D6-4BB5-BE44-B36ACE7C7BFB")
    }
}