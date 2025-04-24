package moe.reimu.ancsreceiver.services

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import moe.reimu.ancsreceiver.ancs.AncsConstants
import moe.reimu.ancsreceiver.ancs.AncsBleService
import moe.reimu.ancsreceiver.ancs.Attribute
import moe.reimu.ancsreceiver.ancs.NotificationAttributeRequest
import moe.reimu.ancsreceiver.ancs.NotificationEvent
import moe.reimu.ancsreceiver.ble.BleServer
import moe.reimu.ancsreceiver.ble.BleServerCharacteristic
import moe.reimu.ancsreceiver.ble.BleServerService
import moe.reimu.ancsreceiver.utils.checkPermissions
import moe.reimu.ancsreceiver.utils.readNullTerminatedString
import moe.reimu.ancsreceiver.utils.showBluetoothToast
import moe.reimu.ancsreceiver.utils.EXTRA_DEVICE_ADDRESS
import java.nio.ByteBuffer
import java.nio.ByteOrder

@SuppressLint("MissingPermission")
class ServerService : NotificationListenerService() {
    private lateinit var btManager: BluetoothManager
    private lateinit var btAdapter: BluetoothAdapter

    private var isConnected = false

    private val serviceScope =
        CoroutineScope(SupervisorJob() + CoroutineExceptionHandler { _, e ->
            Log.e(TAG, "Coroutine error", e)
        })

    private val notificationSource = BleServerCharacteristic(
        uuid = AncsBleService.notificationSourceUuid,
        canNotify = true,
    )
    private val dataSource = BleServerCharacteristic(
        uuid = AncsBleService.dataSourceUuid,
        canNotify = true,
    )
    private val controlPoint = BleServerCharacteristic(
        uuid = AncsBleService.controlPointUuid,
        onWrite = { device, value ->
            try {
                when (val commandId = value[0].toInt()) {
                    0 -> {
                        val request = NotificationAttributeRequest.parse(value)
                        handleNotificationAttrRequest(device, request)
                    }

                    1 -> {
                        val buffer = ByteBuffer.wrap(value)
                        buffer.get()
                        val appId = readNullTerminatedString(buffer)
                        handleAppAttrRequest(device, appId)
                    }

                    else -> {
                        Log.w(TAG, "Unknown command ID: $commandId")
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error processing write from $device", e)
            }
        }
    )
    private val ancsService = BleServerService(uuid = AncsBleService.serviceUuid).apply {
        addCharacteristic(notificationSource)
        addCharacteristic(dataSource)
        addCharacteristic(controlPoint)
    }
    private var bleServer: BleServer? = null

    override fun onCreate() {
        super.onCreate()
        Log.i(TAG, "ServerService created")

        btManager = getSystemService(BluetoothManager::class.java)
        btAdapter = btManager.adapter

        openGattServer()
    }

    private fun openGattServer() {
        if (!checkPermissions()) {
            showBluetoothToast()
            return
        }

        if (!btAdapter.isEnabled) {
            Log.i(TAG, "Bluetooth is disabled")
            return
        }

        try {
            val bleServer = BleServer().apply {
                addService(ancsService)
            }
            bleServer.open(this, serviceScope)
            this.bleServer = bleServer
        } catch (e: SecurityException) {
            Log.e(TAG, "Not authorized to open GATT server", e)
            showBluetoothToast()
            return
        } catch (e: Exception) {
            Log.e(TAG, "Failed to open GATT server", e)
            return
        }

        Log.i(TAG, "GATT server opened")
    }

    private val sbnKeyToUid = mutableMapOf<String, Int>()
    private val uidToSbnKey = mutableMapOf<Int, String>()
    private var currentUid = 0

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        super.onNotificationPosted(sbn)

        var isUpdate = false
        val uid = synchronized(sbnKeyToUid) {
            val existingUid = sbnKeyToUid[sbn.key]
            if (existingUid != null) {
                isUpdate = true
                existingUid
            } else {
                currentUid++
                sbnKeyToUid[sbn.key] = currentUid
                uidToSbnKey[currentUid] = sbn.key
                currentUid
            }
        }

        val event = NotificationEvent(
            eventId = if (isUpdate) 1 else 0,
            eventFlags = 0,
            categoryId = 0,
            categoryCount = 0,
            uid = uid
        )

        val srcDeviceAddress = sbn.notification.extras.getString(EXTRA_DEVICE_ADDRESS)

        serviceScope.launch {
            bleServer?.notifyAllDevices(notificationSource.uuid, event.toByteArray(), srcDeviceAddress)
        }
    }

    override fun onNotificationRemoved(
        sbn: StatusBarNotification,
        rankingMap: RankingMap,
        reason: Int
    ) {
        super.onNotificationRemoved(sbn, rankingMap, reason)

        val uid = synchronized(sbnKeyToUid) {
            val existingUid = sbnKeyToUid[sbn.key] ?: return
            sbnKeyToUid.remove(sbn.key)
            uidToSbnKey.remove(existingUid)
            existingUid
        }

        val event = NotificationEvent(
            eventId = 2,
            eventFlags = 0,
            categoryId = 0,
            categoryCount = 0,
            uid = uid
        )

        val srcDeviceAddress = sbn.notification.extras.getString(EXTRA_DEVICE_ADDRESS)

        serviceScope.launch {
            bleServer?.notifyAllDevices(notificationSource.uuid, event.toByteArray(), srcDeviceAddress)
        }
    }

    override fun onListenerConnected() {
        super.onListenerConnected()
        Log.i(TAG, "Listener connected")
        isConnected = true
    }

    override fun onListenerDisconnected() {
        super.onListenerDisconnected()
        Log.i(TAG, "Listener disconnected")
        isConnected = false
    }

    private fun handleNotificationAttrRequest(
        device: BluetoothDevice,
        request: NotificationAttributeRequest
    ) {
        Log.i(TAG, "Notification attribute request from $device: $request")
        if (!isConnected) {
            Log.w(TAG, "Listener is not connected, ignoring request")
            return
        }

        val sbnKey = synchronized(sbnKeyToUid) {
            uidToSbnKey[request.uid] ?: return
        }

        val sbn = try {
            getActiveNotifications(arrayOf(sbnKey))[0]!!
        } catch (e: Exception) {
            Log.e(TAG, "Failed to get sbn for [$sbnKey]", e)
            return
        }

        val responseBuffer = ByteBuffer.allocate(1024)
        responseBuffer.order(ByteOrder.LITTLE_ENDIAN)
        responseBuffer.put(0)
        responseBuffer.putInt(request.uid)

        for (attr in request.attributes) {
            val attrValue = when (attr.id) {
                AncsConstants.NotificationAttributeIDAppIdentifier -> {
                    sbn.packageName
                }

                AncsConstants.NotificationAttributeIDTitle -> {
                    val titleString = sbn.notification.extras?.getString("android.title") ?: ""
                    truncateUtf8String(
                        titleString,
                        attr.lengthLimit?.toInt() ?: 0
                    )
                }

                AncsConstants.NotificationAttributeIDSubtitle -> {
                    val subtitleString = sbn.notification.extras?.getString("android.subText") ?: ""
                    truncateUtf8String(
                        subtitleString,
                        attr.lengthLimit?.toInt() ?: 0
                    )
                }

                AncsConstants.NotificationAttributeIDMessage -> {
                    val messageString = sbn.notification.extras?.getString("android.text") ?: ""
                    truncateUtf8String(
                        messageString,
                        attr.lengthLimit?.toInt() ?: 0
                    )
                }

                AncsConstants.NotificationAttributeIDPositiveActionLabel -> {
                    ""
                }

                AncsConstants.NotificationAttributeIDNegativeActionLabel -> {
                    ""
                }

                else -> {
                    Log.w(TAG, "Unknown attribute ID: ${attr.id}")
                    ""
                }
            }
            val attrRaw = attrValue.encodeToByteArray()
            responseBuffer.put(attr.id)
            responseBuffer.putShort(attrRaw.size.toShort())
            responseBuffer.put(attrRaw)
        }

        val response = responseBuffer.array().copyOf(responseBuffer.position())
        serviceScope.launch {
            bleServer?.notifySingleDevice(device, dataSource.uuid, response)
        }
    }

    private fun handleAppAttrRequest(device: BluetoothDevice, appId: String) {
        Log.i(TAG, "App attribute request from $device: $appId")

        val pm = packageManager
        val appName = try {
            val appInfo = pm.getApplicationInfo(appId, 0)
            pm.getApplicationLabel(appInfo).toString()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to get app name for $appId", e)
            null
        }

        val responseBuffer = ByteBuffer.allocate(1024)
        responseBuffer.order(ByteOrder.LITTLE_ENDIAN)
        responseBuffer.put(1)
        responseBuffer.put(appId.encodeToByteArray())
        responseBuffer.put(0) // NULL
        val attr = Attribute(0.toByte(), appName ?: "")
        attr.writeToBuffer(responseBuffer)

        val response = responseBuffer.array().copyOf(responseBuffer.position())

        serviceScope.launch {
            bleServer?.notifySingleDevice(device, dataSource.uuid, response)
        }
    }

    /**
     * Truncate a UTF-8 string to a maximum byte length, but ensure it doesn't cut off a character.
     */
    private fun truncateUtf8String(str: String, maxLength: Int): String {
        val bytes = str.toByteArray(Charsets.UTF_8)
        if (bytes.size <= maxLength) {
            return str
        }

        var truncated = str
        while (truncated.isNotEmpty() && truncated.toByteArray(Charsets.UTF_8).size > maxLength) {
            truncated = truncated.substring(0, truncated.length - 1)
        }
        return truncated
    }


    override fun onDestroy() {
        super.onDestroy()
        Log.i(TAG, "ServerService destroyed")

        serviceScope.cancel()

        try {
            bleServer?.close()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to close GATT server", e)
        }
    }

    companion object {
        private const val TAG = "ServerService"
    }
}