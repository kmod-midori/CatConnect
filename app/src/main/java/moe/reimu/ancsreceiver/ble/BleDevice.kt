package moe.reimu.ancsreceiver.ble

import android.Manifest
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothGattService
import android.content.Context
import android.util.Log
import androidx.annotation.RequiresPermission
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.Closeable
import java.util.UUID

class BleDevice(private val nativeDevice: BluetoothDevice) : Closeable {
    private val TAG = "BleDevice"

    private var connectedFuture = CompletableDeferred<Unit>()
    private var servicesFuture = CompletableDeferred<Unit>()
    private var mtuFuture = CompletableDeferred<Int>()

    private val readEvents =
        mutableMapOf<BluetoothGattCharacteristic, MutableList<CompletableDeferred<ByteArray>>>()
    private val writeEvents =
        mutableMapOf<BluetoothGattCharacteristic, CompletableDeferred<Unit>>()
    private val writeDescriptorEvents =
        mutableMapOf<BluetoothGattDescriptor, CompletableDeferred<Unit>>()

    private val subscriptions =
        mutableMapOf<BluetoothGattCharacteristic, MutableSharedFlow<ByteArray>>()

    private val _connectionStateFlow = MutableStateFlow(BluetoothGatt.STATE_DISCONNECTED)
    val connectionStateFlow = _connectionStateFlow.asStateFlow()

    private var gatt: BluetoothGatt? = null
    private val gattCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(gatt: BluetoothGatt?, status: Int, newState: Int) {
            when (newState) {
                BluetoothGatt.STATE_CONNECTING -> {
                    Log.i(TAG, "onConnectionStateChange: $status, Connecting")
                }

                BluetoothGatt.STATE_CONNECTED -> {
                    Log.i(TAG, "onConnectionStateChange: $status, Connected")
                    connectedFuture.complete(Unit)
                }

                BluetoothGatt.STATE_DISCONNECTED -> {
                    Log.i(TAG, "onConnectionStateChange: $status, Disconnected")
                    connectedFuture.completeExceptionally(
                        ConnectionFailedException(status)
                    )
                }
            }

            _connectionStateFlow.value = newState
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt?, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                Log.d(TAG, "onServicesDiscovered: SUCCESS")
                servicesFuture.complete(Unit)
            } else {
                Log.e(TAG, "onServicesDiscovered: $status")
                servicesFuture.completeExceptionally(
                    OperationFailedException(status)
                )
            }
        }

        override fun onCharacteristicRead(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray,
            status: Int
        ) {
            synchronized(readEvents) {
                if (status == BluetoothGatt.GATT_SUCCESS) {
                    Log.d(TAG, "onCharacteristicRead(${characteristic.uuid}): SUCCESS")
                    readEvents.remove(characteristic)?.forEach {
                        it.complete(value)
                    }
                } else {
                    Log.e(TAG, "onCharacteristicRead(${characteristic.uuid}): $status")
                    readEvents.remove(characteristic)?.forEach {
                        it.completeExceptionally(OperationFailedException(status))
                    }
                }
            }
        }

        override fun onCharacteristicWrite(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            status: Int
        ) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                Log.d(TAG, "onCharacteristicWrite(${characteristic.uuid}): SUCCESS")
                writeEvents.remove(characteristic)?.complete(Unit)
            } else {
                Log.e(TAG, "onCharacteristicWrite(${characteristic.uuid}): $status")
                writeEvents.remove(characteristic)?.completeExceptionally(
                    OperationFailedException(status)
                )
            }
        }

        override fun onDescriptorWrite(
            gatt: BluetoothGatt,
            descriptor: BluetoothGattDescriptor,
            status: Int
        ) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                Log.i(
                    TAG,
                    "onDescriptorWrite(${descriptor.characteristic.uuid}, ${descriptor.uuid}): SUCCESS"
                )
                writeDescriptorEvents.remove(descriptor)?.complete(Unit)
            } else {
                val statusString = when (status) {
                    BluetoothGatt.GATT_WRITE_NOT_PERMITTED -> "GATT_WRITE_NOT_PERMITTED"
                    BluetoothGatt.GATT_INSUFFICIENT_AUTHENTICATION -> "GATT_INSUFFICIENT_AUTHENTICATION"
                    BluetoothGatt.GATT_INSUFFICIENT_ENCRYPTION -> "GATT_INSUFFICIENT_ENCRYPTION"
                    BluetoothGatt.GATT_READ_NOT_PERMITTED -> "GATT_READ_NOT_PERMITTED"
                    BluetoothGatt.GATT_REQUEST_NOT_SUPPORTED -> "GATT_REQUEST_NOT_SUPPORTED"
                    else -> "$status"
                }
                Log.e(
                    TAG,
                    "onDescriptorWrite(${descriptor.characteristic.uuid}, ${descriptor.uuid}): $statusString"
                )
                writeDescriptorEvents.remove(descriptor)?.completeExceptionally(
                    OperationFailedException(status)
                )
            }
        }

        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray
        ) {
            Log.i(TAG, "onCharacteristicChanged(${characteristic.uuid})")

            subscriptions[characteristic]?.tryEmit(value)
                ?: Log.w(TAG, "onCharacteristicChanged: Buffer overflow for ${characteristic.uuid}")
        }

        override fun onMtuChanged(gatt: BluetoothGatt, mtu: Int, status: Int) {
            Log.i(TAG, "onMtuChanged: $mtu, $status")

            if (status == BluetoothGatt.GATT_SUCCESS) {
                mtuFuture.complete(mtu)
            } else {
                mtuFuture.completeExceptionally(
                    OperationFailedException(status)
                )
            }
        }
    }

    private fun requireGatt() =
        gatt ?: throw IllegalStateException("This device is closed or never connected")

    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    suspend fun connect(context: Context, autoConnect: Boolean) {
        connectedFuture.cancel()
        connectedFuture = CompletableDeferred()

        gatt = nativeDevice.connectGatt(
            context,
            autoConnect,
            gattCallback,
            BluetoothDevice.TRANSPORT_LE,
        )

        connectedFuture.await()
    }

    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    fun reconnect() {
        requireGatt().connect()
    }

    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    fun disconnect() {
        requireGatt().disconnect()
    }

    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    suspend fun requestMtu(mtu: Int): Int {
        val gatt = requireGatt()

        mtuFuture.cancel()
        mtuFuture = CompletableDeferred()

        if (!gatt.requestMtu(mtu)) {
            throw BleException("Failed to request MTU")
        }

        return mtuFuture.await()
    }

    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    suspend fun discoverServices() {
        val gatt = requireGatt()

        servicesFuture.cancel()
        servicesFuture = CompletableDeferred()

        if (!gatt.discoverServices()) {
            throw BleException("Failed to discover services")
        }

        servicesFuture.await()
    }

    fun findService(uuid: UUID): BluetoothGattService? {
        val gatt = requireGatt()
        return gatt.getService(uuid)
    }

    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    suspend fun readCharacteristic(
        characteristic: BluetoothGattCharacteristic
    ): ByteArray {
        val gatt = requireGatt()

        val future = CompletableDeferred<ByteArray>()
        synchronized(readEvents) {
            readEvents.getOrPut(characteristic) {
                mutableListOf()
            }.add(future)
        }

        if (!gatt.readCharacteristic(characteristic)) {
            throw BleException("Failed to read characteristic")
        }

        return future.await()
    }

    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    @Suppress("DEPRECATION")
    suspend fun writeCharacteristic(
        characteristic: BluetoothGattCharacteristic,
    ) {
        val gatt = requireGatt()

        val future = CompletableDeferred<Unit>()
        synchronized(writeEvents) {
            val current = writeEvents.remove(characteristic)
            if (current != null) {
                Log.w(TAG, "writeCharacteristic: completing existing future")
                current.complete(Unit)
            }
            writeEvents[characteristic] = future
        }

        if (!gatt.writeCharacteristic(characteristic)) {
            throw BleException("Failed to write characteristic")
        }

        future.await()
    }

    @Suppress("DEPRECATION")
    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    suspend fun writeCharacteristic(
        characteristic: BluetoothGattCharacteristic,
        value: ByteArray
    ) {
        characteristic.value = value
        writeCharacteristic(characteristic)
    }

    @Suppress("DEPRECATION")
    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    suspend fun writeDescriptor(
        descriptor: BluetoothGattDescriptor,
    ) {
        val gatt = requireGatt()

        val future = CompletableDeferred<Unit>()
        synchronized(writeDescriptorEvents) {
            val current = writeDescriptorEvents.remove(descriptor)
            if (current != null) {
                Log.w(TAG, "writeDescriptor: completing existing future")
                current.complete(Unit)
            }
            writeDescriptorEvents[descriptor] = future
        }

        if (!gatt.writeDescriptor(descriptor)) {
            throw BleException("Failed to initiate write descriptor")
        }

        future.await()
    }

    /**
     * Enable or disable notifications for a characteristic.
     */
    @Suppress("DEPRECATION")
    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    suspend fun setNotification(
        characteristic: BluetoothGattCharacteristic,
        enabled: Boolean
    ) {
        val gatt = requireGatt()

        if (!gatt.setCharacteristicNotification(characteristic, true)) {
            throw BleException("Failed to enable notification")
        }

        val descriptor = characteristic.getDescriptor(clientConfigurationUuid)
            ?: throw IllegalStateException("Notification descriptor not found")

        descriptor.value = if (enabled) {
            BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
        } else {
            BluetoothGattDescriptor.DISABLE_NOTIFICATION_VALUE
        }

        writeDescriptor(descriptor)
    }

    /**
     * Subscribe to notifications from a characteristic.
     * This does not enable notifications, you need to call setNotification() to actually
     * receive notifications.
     *
     * Notifications will be emitted as ByteArray.
     */
    fun subscribe(
        characteristic: BluetoothGattCharacteristic,
        bufferCapacity: Int = 0
    ): Flow<ByteArray> {
        return subscriptions.getOrPut(characteristic) {
            MutableSharedFlow(extraBufferCapacity = bufferCapacity)
        }
    }

    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    override fun close() {
        Log.i(TAG, "close()")
        gatt?.close()
        gatt = null
    }

    val address
        get() = nativeDevice.address

    val name
        @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
        get() = nativeDevice.name

    open class BleException(message: String) : Exception(message)
    class ConnectionFailedException(val status: Int) :
        BleException("Connection failed with status $status")

    class OperationFailedException(val status: Int) :
        BleException("Operation failed with status $status")

    companion object {
        val clientConfigurationUuid = UUID.fromString(
            "00002902-0000-1000-8000-00805f9b34fb"
        )
    }
}