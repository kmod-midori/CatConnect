package moe.reimu.ancsreceiver.ams

import android.bluetooth.BluetoothGattCharacteristic
import moe.reimu.ancsreceiver.ble.BleDevice
import java.util.UUID

class AmsBleService(device: BleDevice) {
    val remoteCommand: BluetoothGattCharacteristic?
    val entityUpdate: BluetoothGattCharacteristic?
    val entityAttribute: BluetoothGattCharacteristic?

    init {
        val service = device.findService(UUID.fromString("89D3502B-0F36-433A-8EF4-C502AD55F8DC"))
            ?: throw ServiceNotFoundException()
        remoteCommand =
            service.getCharacteristic(UUID.fromString("9B3C81D8-57B1-4A8A-B8DF-0E56F7CA51C2"))
        entityUpdate =
            service.getCharacteristic(UUID.fromString("2F7CABCE-808D-411F-9A0C-BB92BA96C102"))
        entityAttribute =
            service.getCharacteristic(UUID.fromString("C6B2F38C-23AB-46D8-A6AB-A3A870BBD5D7"))
    }

    class ServiceNotFoundException : Exception("AMS service not found")
}