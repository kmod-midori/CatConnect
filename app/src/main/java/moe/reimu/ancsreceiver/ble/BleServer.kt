package moe.reimu.ancsreceiver.ble

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothGattServer
import android.bluetooth.BluetoothGattServerCallback
import android.bluetooth.BluetoothManager
import android.content.Context
import android.util.Log
import androidx.annotation.RequiresPermission
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import moe.reimu.ancsreceiver.BleDevice
import moe.reimu.ancsreceiver.services.ServerService
import java.io.Closeable
import java.util.UUID
import kotlin.collections.sortedBy
import kotlin.collections.sumOf

class BleServer: Closeable {
    private var gattServer: BluetoothGattServer? = null
    private var notificationJob: Job? = null
    private val services = mutableMapOf<UUID, BleServerService>()
    private val nativeChars = mutableMapOf<UUID, BluetoothGattCharacteristic>()

    data class ConnectionState(
        val device: BluetoothDevice,
        val status: Int,
        val state: Int
    )

    private val _connectionStateFlow = MutableSharedFlow<ConnectionState>()

    class ConnectionInfo() {
        val enabledNotifications = mutableMapOf<UUID, Boolean>()
    }

    private val connections = mutableMapOf<BluetoothDevice, ConnectionInfo>()

    val connectedDevices
        get() = connections.keys

    private class GattServerContext(
        val device: BluetoothDevice,
        val requestId: Int,
        val responseNeeded: Boolean,
        val offset: Int,
    )

    private class PreparedWrite(
        val characteristic: BleServerCharacteristic,
        val segments: MutableList<Pair<Int, ByteArray>> = mutableListOf(),
    )

    private var notificationSent = CompletableDeferred<Unit>()

    private class NotificationRequest(
        val device: BluetoothDevice,
        val characteristic: BluetoothGattCharacteristic,
        val value: ByteArray,
    )

    private val notificationFlow = MutableSharedFlow<NotificationRequest>()

    @SuppressLint("MissingPermission")
    private val gattServerCallback = object : BluetoothGattServerCallback() {
        override fun onConnectionStateChange(device: BluetoothDevice, status: Int, newState: Int) {
            Log.d(TAG, "onConnectionStateChange: $device, status: $status, newState: $newState")
            _connectionStateFlow.tryEmit(ConnectionState(device, status, newState))

            synchronized(connections) {
                when (newState) {
                    BluetoothGattServer.STATE_CONNECTED -> {
                        Log.i(TAG, "Device connected: $device")
                        connections[device] = ConnectionInfo()
                    }

                    BluetoothGattServer.STATE_DISCONNECTED -> {
                        Log.i(TAG, "Device disconnected: $device")
                        connections.remove(device)
                    }
                }
            }
        }

        override fun onDescriptorReadRequest(
            device: BluetoothDevice,
            requestId: Int,
            offset: Int,
            descriptor: BluetoothGattDescriptor
        ) {
            val context = GattServerContext(device, requestId, true, offset)
            val conn = ensureConnection(context) ?: return
            val bleCharacteristic =
                ensureCharacteristic(context, descriptor.characteristic.uuid) ?: return

            if (descriptor.uuid != BleDevice.clientConfigurationUuid || !bleCharacteristic.canNotify) {
                sendError(context, BluetoothGatt.GATT_REQUEST_NOT_SUPPORTED)
                return
            }

            val response = synchronized(conn) {
                if (conn.enabledNotifications[descriptor.characteristic.uuid] == true) {
                    BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                } else {
                    BluetoothGattDescriptor.DISABLE_NOTIFICATION_VALUE
                }
            }

            gattServer?.sendResponse(
                device,
                requestId,
                BluetoothGatt.GATT_SUCCESS,
                offset,
                response.copyOfRange(offset, response.size)
            )
        }

        override fun onDescriptorWriteRequest(
            device: BluetoothDevice,
            requestId: Int,
            descriptor: BluetoothGattDescriptor,
            preparedWrite: Boolean,
            responseNeeded: Boolean,
            offset: Int,
            value: ByteArray
        ) {
            val context = GattServerContext(device, requestId, responseNeeded, offset)
            val conn = ensureConnection(context) ?: return
            val bleCharacteristic =
                ensureCharacteristic(context, descriptor.characteristic.uuid) ?: return

            if (device.bondState != BluetoothDevice.BOND_BONDED) {
                sendError(context, BluetoothGatt.GATT_REQUEST_NOT_SUPPORTED)
                return
            }

            if (descriptor.uuid != BleDevice.clientConfigurationUuid || !bleCharacteristic.canNotify) {
                sendError(context, BluetoothGatt.GATT_REQUEST_NOT_SUPPORTED)
                return
            }

            if (offset != 0) {
                Log.w(
                    TAG,
                    "onDescriptorWriteRequest($device, ${descriptor.characteristic}): offset != 0"
                )
                sendError(context, BluetoothGatt.GATT_INVALID_OFFSET)
                return
            }

            val enabled = when (value[0]) {
                BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE[0] -> {
                    Log.i(
                        TAG,
                        "onDescriptorWriteRequest($device, ${descriptor.characteristic.uuid}): Notification enabled"
                    )
                    true
                }

                BluetoothGattDescriptor.DISABLE_NOTIFICATION_VALUE[0] -> {
                    Log.i(
                        TAG,
                        "onDescriptorWriteRequest($device, ${descriptor.characteristic.uuid}): Notification disabled"
                    )
                    false
                }

                else -> {
                    sendError(context, BluetoothGatt.GATT_REQUEST_NOT_SUPPORTED)
                    return
                }
            }

            synchronized(conn) {
                conn.enabledNotifications[descriptor.characteristic.uuid] = enabled
            }

            if (responseNeeded) {
                gattServer?.sendResponse(
                    device,
                    requestId,
                    BluetoothGatt.GATT_SUCCESS,
                    offset,
                    null
                )
            }
        }

        private val preparedWrites = mutableMapOf<BluetoothDevice, PreparedWrite>()

        override fun onCharacteristicWriteRequest(
            device: BluetoothDevice,
            requestId: Int,
            characteristic: BluetoothGattCharacteristic,
            preparedWrite: Boolean,
            responseNeeded: Boolean,
            offset: Int,
            value: ByteArray
        ) {
            val context = GattServerContext(device, requestId, responseNeeded, offset)
            ensureConnection(context) ?: return
            val bleCharacteristic = ensureCharacteristic(context, characteristic.uuid) ?: return

            if (device.bondState != BluetoothDevice.BOND_BONDED) {
                Log.w(
                    TAG,
                    "onCharacteristicWriteRequest($device, $characteristic): device not bonded"
                )
                sendError(context, BluetoothGatt.GATT_REQUEST_NOT_SUPPORTED)
                return
            }

            if (bleCharacteristic.onWrite == null) {
                Log.w(
                    TAG,
                    "onCharacteristicWriteRequest($device, $characteristic): characteristic does not support write"
                )
                sendError(context, BluetoothGatt.GATT_REQUEST_NOT_SUPPORTED)
                return
            }

            if (preparedWrite) {
                // Queue the write fragment
                val write = preparedWrites.getOrPut(device) { PreparedWrite(bleCharacteristic) }
                write.segments.add(offset to value)
            } else {
                // Immediate write: process directly
                bleCharacteristic.onWrite.invoke(device, value)
            }

            if (responseNeeded) {
                gattServer?.sendResponse(
                    device,
                    requestId,
                    BluetoothGatt.GATT_SUCCESS,
                    offset,
                    null
                )
            }
        }

        override fun onExecuteWrite(device: BluetoothDevice, requestId: Int, execute: Boolean) {
            val context = GattServerContext(device, requestId, true, 0)
            ensureConnection(context) ?: return

            if (execute) {
                val write = preparedWrites[device]
                if (write != null) {
                    // Execute the queued write
                    val totalLength = write.segments.sumOf { it.second.size }
                    val assembledValue = ByteArray(totalLength)
                    var currentOffset = 0
                    write.segments.sortedBy { it.first }.forEach { (_, chunk) ->
                        System.arraycopy(chunk, 0, assembledValue, currentOffset, chunk.size)
                        currentOffset += chunk.size
                    }
                    write.characteristic.onWrite?.invoke(device, assembledValue)
                } else {
                    Log.w(TAG, "onExecuteWrite($device): no prepared write found")
                }
            }

            synchronized(preparedWrites) {
                preparedWrites.remove(device)
            }

            gattServer?.sendResponse(
                device,
                requestId,
                BluetoothGatt.GATT_SUCCESS,
                0,
                null
            )
        }

        @SuppressLint("MissingPermission")
        private fun ensureConnection(
            context: GattServerContext
        ): ConnectionInfo? {
            val conn = synchronized(connections) {
                connections[context.device]
            }

            if (conn != null) {
                return conn
            } else {
                sendError(context, BluetoothGatt.GATT_FAILURE)
                return null
            }
        }

        @SuppressLint("MissingPermission")
        private fun ensureCharacteristic(
            context: GattServerContext,
            uuid: UUID,
        ): BleServerCharacteristic? {
            val characteristic = findCharacteristic(uuid)
            if (characteristic != null) {
                return characteristic
            } else {
                sendError(context, BluetoothGatt.GATT_REQUEST_NOT_SUPPORTED)
                return null
            }
        }

        @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
        private fun sendError(
            context: GattServerContext,
            status: Int,
        ) {
            if (context.responseNeeded) {
                gattServer?.sendResponse(
                    context.device,
                    context.requestId,
                    status,
                    context.offset,
                    null
                )
            }
        }

        override fun onNotificationSent(device: BluetoothDevice, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                Log.i(TAG, "Notification sent to $device")
                notificationSent.complete(Unit)
            } else {
                Log.w(TAG, "Failed to send notification to $device: $status")
                notificationSent.completeExceptionally(
                    Exception("Failed to send notification to $device: $status")
                )
            }
        }
    }

    private fun getConnectionInfo(device: BluetoothDevice): ConnectionInfo? {
        synchronized(connections) { return connections[device] }
    }

    private fun findCharacteristic(uuid: UUID): BleServerCharacteristic? {
        for ((_, service) in services) {
            val characteristic = service.getCharacteristic(uuid)
            if (characteristic != null) {
                return characteristic
            }
        }
        return null
    }

    fun addService(s: BleServerService) {
        services[s.uuid] = s
    }

    suspend fun notifyAllDevices(uuid: UUID, data: ByteArray, excludeAddress: String? = null) {
        val characteristic = findCharacteristic(uuid)
        if (characteristic == null) {
            Log.w(TAG, "Characteristic not found: $uuid")
            return
        }

        for ((device, _) in connections) {
            if (device.address == excludeAddress) {
                continue
            }
            notifySingleDevice(device, characteristic.uuid, data)
        }
    }

    suspend fun notifySingleDevice(device: BluetoothDevice, uuid: UUID, data: ByteArray) {
        val characteristic = findCharacteristic(uuid)
        if (characteristic == null) {
            Log.w(TAG, "Characteristic not found: $uuid")
            return
        }
        val nativeCharacteristic = nativeChars[uuid]
        if (nativeCharacteristic == null) {
            Log.w(TAG, "Native characteristic not found: $uuid")
            return
        }

        notificationFlow.emit(NotificationRequest(device, nativeCharacteristic, data))
    }

    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    @Suppress("DEPRECATION")
    fun open(context: Context, scope: CoroutineScope) {
        val btManager = context.getSystemService(BluetoothManager::class.java)
        if (!btManager.adapter.isEnabled) {
            throw IllegalStateException("Bluetooth is not enabled")
        }

        val gattServer = btManager.openGattServer(context, gattServerCallback)
        nativeChars.clear()
        for ((_, s) in services) {
            val svc = s.toNativeService()
            gattServer.addService(svc.first)
            nativeChars.putAll(svc.second)
        }
        this.gattServer = gattServer

        notificationJob = scope.launch {
            notificationFlow.collect { request ->
                val device = request.device
                val characteristic = request.characteristic

                if (getConnectionInfo(device)?.enabledNotifications?.get(characteristic.uuid) != true) {
                    Log.w(TAG, "Notification not enabled for ${characteristic.uuid} on $device")
                    return@collect
                }

                val value = request.value
                characteristic.value = value

                notificationSent = CompletableDeferred()
                val result = gattServer?.notifyCharacteristicChanged(device, characteristic, false)
                if (result != true) {
                    Log.w(TAG, "Failed to notify characteristic changed")
                    return@collect
                } else {
                    Log.i(TAG, "Notification initiated for ${characteristic.uuid} to $device")
                }

                try {
                    notificationSent.await()
                } catch (e: Exception) {
                    Log.e(TAG, "Error waiting for notification sent", e)
                }
            }
        }
    }

    @SuppressLint("MissingPermission")
    override fun close() {
        notificationJob?.cancel()
        notificationJob = null

        try {
            gattServer?.close()
            gattServer = null
        } catch (e: Exception) {
            Log.e(TAG, "Error closing GATT server", e)
        }
    }

    companion object {
        private const val TAG = "BleServer"
    }
}