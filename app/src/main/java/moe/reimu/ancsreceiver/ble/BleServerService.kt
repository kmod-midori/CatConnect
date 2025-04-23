package moe.reimu.ancsreceiver.ble

import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattService
import java.util.UUID

class BleServerService(val uuid: UUID) {
    private val characteristics = mutableMapOf<UUID, BleServerCharacteristic>()

    fun addCharacteristic(c: BleServerCharacteristic) {
        characteristics[c.uuid] = c
    }

    fun getCharacteristic(uuid: UUID): BleServerCharacteristic? {
        return characteristics[uuid]
    }

    fun toNativeService(): Pair<BluetoothGattService, Map<UUID, BluetoothGattCharacteristic>> {
        val service = BluetoothGattService(uuid, BluetoothGattService.SERVICE_TYPE_PRIMARY)
        val natives = characteristics.mapValues { it.value.toNativeCharacteristic() }
        for (c in natives.values) {
            service.addCharacteristic(c)
        }
        return Pair(service, natives)
    }
}