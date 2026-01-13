package moe.reimu.ancsreceiver.services

import android.Manifest
import android.annotation.SuppressLint
import android.app.ForegroundServiceStartNotAllowedException
import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.support.v4.media.MediaMetadataCompat
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat
import android.util.Log
import android.widget.Toast
import androidx.annotation.RequiresPermission
import androidx.core.app.NotificationChannelCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import moe.reimu.ancsreceiver.ancs.AncsConstants
import moe.reimu.ancsreceiver.ble.BleDevice
import moe.reimu.ancsreceiver.BuildConfig
import moe.reimu.ancsreceiver.MainActivity
import moe.reimu.ancsreceiver.MyApplication
import moe.reimu.ancsreceiver.R
import moe.reimu.ancsreceiver.ServiceState
import moe.reimu.ancsreceiver.ams.AmsBleService
import moe.reimu.ancsreceiver.ams.EntityUpdateNotification
import moe.reimu.ancsreceiver.ancs.AncsActionLabels
import moe.reimu.ancsreceiver.ancs.AncsBleService
import moe.reimu.ancsreceiver.ancs.AppAttributeRequest
import moe.reimu.ancsreceiver.ancs.AttributeRequest
import moe.reimu.ancsreceiver.ancs.NotificationAttributeRequest
import moe.reimu.ancsreceiver.ancs.NotificationEvent
import moe.reimu.ancsreceiver.utils.CHANNEL_ID_MEDIA
import moe.reimu.ancsreceiver.utils.CHANNEL_ID_PERSIST
import moe.reimu.ancsreceiver.utils.GROUP_ID_FWD
import moe.reimu.ancsreceiver.utils.NOTI_ID_FWD_BASE
import moe.reimu.ancsreceiver.utils.NOTI_ID_MEDIA
import moe.reimu.ancsreceiver.utils.NOTI_ID_PERSIST
import moe.reimu.ancsreceiver.utils.checkPermissions
import moe.reimu.ancsreceiver.utils.getReceiverFlags
import moe.reimu.ancsreceiver.utils.readTlvAsMap
import moe.reimu.ancsreceiver.utils.registerInternalBroadcastReceiver
import moe.reimu.ancsreceiver.utils.showBluetoothToast
import moe.reimu.ancsreceiver.utils.EXTRA_DEVICE_ADDRESS
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.cancellation.CancellationException
import kotlin.coroutines.coroutineContext
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

class AncsService : Service() {
    private lateinit var btManager: BluetoothManager
    private lateinit var btAdapter: BluetoothAdapter

    private val appNames = ConcurrentHashMap<String, String>()

    private var mainTask: Job? = null

    private lateinit var notificationManager: NotificationManagerCompat

    /**
     * Flow for positive action events.
     * Emits the UID of the notification for which the positive action was triggered.
     */
    private var positiveActionFlow = MutableSharedFlow<Int>()

    /**
     * Flow for negative action events.
     * Emits the UID of the notification for which the negative action was triggered.
     */
    private var negativeActionFlow = MutableSharedFlow<Int>()

    private val internalReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                ServiceState.ACTION_STOP_SERVICE -> {
                    Log.i(TAG, "Received ACTION_STOP_SERVICE")
                    stopSelf()
                }

                ServiceState.ACTION_QUERY_RECEIVER_STATE -> {
                    sendBroadcast(ServiceState.getUpdateIntent(true))
                }

                ACTION_POSITIVE_ACTION -> {
                    val uid = intent.getIntExtra("ancs.uid", -1)
                    if (uid != -1) {
                        positiveActionFlow.tryEmit(uid)
                    }
                }

                ACTION_NEGATIVE_ACTION -> {
                    val uid = intent.getIntExtra("ancs.uid", -1)
                    if (uid != -1) {
                        negativeActionFlow.tryEmit(uid)
                    }
                }

                else -> {
                    Log.w(TAG, "Unknown action: ${intent.action}")
                }
            }
        }
    }

    private val bluetoothReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val currentState = intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, -1)
            if (currentState == BluetoothAdapter.STATE_TURNING_OFF || currentState == BluetoothAdapter.STATE_OFF) {
                Log.i(TAG, "BT is turning off, stopping self")
                stopSelf()
            }
        }
    }

    override fun onBind(intent: Intent): IBinder? = null


    override fun onCreate() {
        super.onCreate()

        registerInternalBroadcastReceiver(
            internalReceiver,
            IntentFilter().apply {
                addAction(ServiceState.ACTION_QUERY_RECEIVER_STATE)
                addAction(ServiceState.ACTION_STOP_SERVICE)
                addAction(ACTION_POSITIVE_ACTION)
                addAction(ACTION_NEGATIVE_ACTION)
            },
        )
        registerReceiver(bluetoothReceiver, IntentFilter().apply {
            addAction(BluetoothAdapter.ACTION_STATE_CHANGED)
        }, getReceiverFlags())

        if (!checkPermissions()) {
            showBluetoothToast()
            stopSelf()
            return
        }

        val targetDeviceAddress = MyApplication.Companion.getInstance().getSettings().deviceAddress
        if (targetDeviceAddress == null) {
            Toast.makeText(this, R.string.device_not_selected, Toast.LENGTH_SHORT).show()
            stopSelf()
            return
        }

        notificationManager = NotificationManagerCompat.from(this)

        try {
            btManager = getSystemService(BluetoothManager::class.java)
            val btAdapter = btManager.adapter
            if (btAdapter == null || !btAdapter.isEnabled) {
                throw IllegalStateException("Bluetooth not enabled")
            }
            this.btAdapter = btAdapter
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize BT", e)
            showBluetoothToast()
            stopSelf()
            return
        }

        try {
            startForeground(
                NOTI_ID_PERSIST,
                createNotification(false, null, null),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
            )
        } catch (e: Exception) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && e is ForegroundServiceStartNotAllowedException) {
                Log.e(TAG, "Service startup not allowed", e)
            } else {
                Log.e(TAG, "Service startup failed", e)
            }
            stopSelf()
            return
        }

        var targetDevice: BluetoothDevice? = null

        try {
            for (dev in btAdapter.bondedDevices) {
                val macAddr = dev.address
                if (macAddr == targetDeviceAddress) {
                    targetDevice = dev
                    break
                }
            }
        } catch (e: SecurityException) {
            Log.e(TAG, "Failed to read bonded devices", e)
            stopSelf()
            return
        }

        if (targetDevice == null) {
            Log.e(TAG, "Failed to get bonded device")
            stopSelf()
            return
        }

        mainTask = CoroutineScope(Dispatchers.IO + SupervisorJob()).launch {
            while (true) {
                try {
                    mainTask(targetDevice)
                } catch (_: ServiceDestroyedException) {
                    break
                } catch (e: Throwable) {
                    Log.e(TAG, "Main task failed", e)
                }
                Log.i(TAG, "Waiting for 10s before reconnecting")
                delay(1000 * 10)
            }
        }

        sendBroadcast(ServiceState.getUpdateIntent(true))
    }

    @SuppressLint("MissingPermission")
    private suspend fun handleAncs(bleDevice: BleDevice, flowScope: CoroutineScope) {
        val ancsService = AncsBleService(bleDevice)
        val notificationFlow = bleDevice.subscribe(ancsService.notificationSource, 32)
        val dataFlow = bleDevice.subscribe(ancsService.dataSource, 32)
        withTimeout(5000) {
            bleDevice.setNotification(ancsService.notificationSource, true)
            bleDevice.setNotification(ancsService.dataSource, true)
        }

        val events = ConcurrentHashMap<Int, NotificationEvent>()
        // Notifications that are queued until we get the app name
        val queuedNotifications = mutableMapOf<String, MutableSet<NotificationCompat.Builder>>()
        val queuedNotificationsMutex = Mutex()

        flowScope.launch {
            notificationFlow.collect {
                val event = try {
                    NotificationEvent.Companion.parse((it))
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to parse NotificationEvent", e)
                    return@collect
                }

                Log.d(TAG, "$event, silent: ${event.isSilent}, existing: ${event.isExisting}")

                when (event.eventId.toInt()) {
                    in 0..1 -> {
                        if (event.isSilent) {
                            return@collect
                        }

                        val attrs = mutableListOf(
                            AttributeRequest(AncsConstants.NotificationAttributeIDAppIdentifier),
                            AttributeRequest(AncsConstants.NotificationAttributeIDTitle, 64),
                            AttributeRequest(AncsConstants.NotificationAttributeIDSubtitle, 64),
                            AttributeRequest(AncsConstants.NotificationAttributeIDMessage, 256),
                        )
                        if (event.hasPositiveAction) {
                            attrs.add(
                                AttributeRequest(AncsConstants.NotificationAttributeIDPositiveActionLabel)
                            )
                        }
                        if (event.hasNegativeAction) {
                            attrs.add(
                                AttributeRequest(AncsConstants.NotificationAttributeIDNegativeActionLabel)
                            )
                        }

                        val request = NotificationAttributeRequest(event.uid, attrs)

                        events[event.uid] = event

                        launch {
                            writeControlPointAndLog(
                                bleDevice, ancsService.controlPoint, request.toByteArray()
                            )
                        }
                    }

                    2 -> {
                        notificationManager.cancel(NOTI_ID_FWD_BASE + event.uid)
                    }
                }
            }
        }

        flowScope.launch {
            dataFlow.collect {
                val buffer = ByteBuffer.wrap(it)
                buffer.order(ByteOrder.LITTLE_ENDIAN)

                val commandId = buffer.get().toInt()
                when (commandId) {
                    0 -> {
                        val uid = buffer.getInt()
                        val attributes = readTlvAsMap(buffer)

                        val appId = attributes[AncsConstants.NotificationAttributeIDAppIdentifier]
                        val title = attributes[AncsConstants.NotificationAttributeIDTitle]
                        val subtitle = attributes[AncsConstants.NotificationAttributeIDSubtitle]
                        val message = attributes[AncsConstants.NotificationAttributeIDMessage]

                        if (appId == null || title == null) {
                            return@collect
                        }

                        val appName = appNames[appId]

                        val event = events.remove(uid)

                        val extras = Bundle().apply {
                            putString("ancs.appId", appId)
                            putString(EXTRA_DEVICE_ADDRESS, bleDevice.address)
                            putString("ancs.origTitle", title)
                            putInt("ancs.uid", uid)

                            if (event != null) {
                                putByte("ancs.categoryId", event.categoryId)
                            }
                        }

                        val builder = NotificationCompat.Builder(this@AncsService, appId)
                            .setSmallIcon(R.drawable.baseline_phone_iphone).setContentTitle(title)
                            .setExtras(extras).setAutoCancel(true)

                        val positiveAction =
                            attributes[AncsConstants.NotificationAttributeIDPositiveActionLabel]
                        if (positiveAction != null) {
                            builder.addAction(
                                R.drawable.ic_done,
                                positiveAction,
                                getActionIntent(ACTION_POSITIVE_ACTION, uid)
                            )
                        }

                        val negativeAction =
                            attributes[AncsConstants.NotificationAttributeIDNegativeActionLabel]
                        if (negativeAction != null) {
                            builder.addAction(
                                R.drawable.ic_close,
                                negativeAction,
                                getActionIntent(ACTION_NEGATIVE_ACTION, uid)
                            )

                            // Set delete intent only when the negative action is "Clear"
                            if (AncsActionLabels.isClearAction(negativeAction)) {
                                builder.setDeleteIntent(
                                    getActionIntent(
                                        ACTION_NEGATIVE_ACTION,
                                        uid
                                    )
                                )
                            }
                        }

                        val contentLines = mutableListOf<String>()
                        if (!subtitle.isNullOrBlank()) {
                            contentLines.add(subtitle)
                        }
                        if (!message.isNullOrBlank()) {
                            contentLines.add(message)
                        }
                        if (contentLines.isNotEmpty()) {
                            builder.setContentText(contentLines.joinToString("\n"))
                        }

                        if (appName != null) {
                            postNotification(bleDevice.name, appId, appName, builder)
                        } else {
                            Log.i(TAG, "AppId $appId not found in cache, requesting")

                            // Queue the notification until we get the app name
                            queuedNotificationsMutex.withLock {
                                queuedNotifications.getOrPut(appId) { mutableSetOf() }.add(builder)
                            }

                            val request = AppAttributeRequest(appId)
                            request.addAttribute(0)
                            launch {
                                writeControlPointAndLog(
                                    bleDevice, ancsService.controlPoint, request.toByteArray()
                                )
                            }
                        }
                    }

                    1 -> {
                        val appIdBytes = mutableListOf<Byte>()
                        while (buffer.hasRemaining()) {
                            val b = buffer.get()
                            if (b == 0.toByte()) {
                                break
                            }
                            appIdBytes.add(b)
                        }
                        val appId = appIdBytes.toByteArray().decodeToString()
                        val attrs = readTlvAsMap(buffer)

                        val appName = attrs[0]
                        if (appName != null) {
                            appNames[appId] = appName
                            Log.i(TAG, "AppId $appId is $appName")

                            // Post all queued notifications
                            queuedNotificationsMutex.withLock {
                                queuedNotifications.remove(appId)?.forEach { builder ->
                                    postNotification(bleDevice.name, appId, appName, builder)
                                }
                            }
                        }
                    }
                }
            }
        }

        positiveActionFlow = MutableSharedFlow(extraBufferCapacity = 8)
        flowScope.launch {
            positiveActionFlow.collect {
                Log.i(TAG, "Positive action for uid $it")
                val request = getActionRequest(it, 0) // ActionIDPositive
                writeControlPointAndLog(
                    bleDevice, ancsService.controlPoint, request
                )
            }
        }

        negativeActionFlow = MutableSharedFlow(extraBufferCapacity = 8)
        flowScope.launch {
            negativeActionFlow.collect {
                Log.i(TAG, "Negative action for uid $it")
                val request = getActionRequest(it, 1) // ActionIDNegative
                writeControlPointAndLog(
                    bleDevice, ancsService.controlPoint, request
                )
            }
        }
    }

    @SuppressLint("MissingPermission")
    private suspend fun handleBattery(bleDevice: BleDevice, flowScope: CoroutineScope): Byte? {
        val batteryService =
            bleDevice.findService(UUID.fromString("0000180f-0000-1000-8000-00805f9b34fb"))
        val batteryChar =
            batteryService?.getCharacteristic(UUID.fromString("00002a19-0000-1000-8000-00805f9b34fb"))

        return if (batteryChar != null) {
            val batteryFlow = bleDevice.subscribe(batteryChar, 8)
            flowScope.launch {
                batteryFlow.collect {
                    updateConnectedNotification(bleDevice.name, it.getOrNull(0))
                }
            }
            withTimeout(2000) {
                bleDevice.setNotification(batteryChar, true)
                bleDevice.readCharacteristic(batteryChar).getOrNull(0)
            }
        } else {
            null
        }
    }

    private var mediaSession: MediaSessionCompat? = null
    private val mediaSessionMutex = Mutex()

    /**
     * Disable the media notification and release the media session.
     */
    private suspend fun cancelMediaNotification() {
        NotificationManagerCompat.from(this@AncsService).cancel(NOTI_ID_MEDIA)
        mediaSessionMutex.withLock {
            mediaSession?.apply {
                setPlaybackState(PlaybackStateCompat.Builder().build())
                setMetadata(MediaMetadataCompat.Builder().build())
                isActive = false
                release()
            }
            mediaSession = null
        }
    }

    @SuppressLint("MissingPermission")
    private suspend fun handleAms(bleDevice: BleDevice, flowScope: CoroutineScope) {
        val amsService = try {
            AmsBleService(bleDevice)
        } catch (e: AmsBleService.ServiceNotFoundException) {
            Log.w(TAG, "AMS service not found", e)
            return
        }

        val stateUpdateFlow = MutableSharedFlow<Unit>(1)

        var playbackState = 0
        var playbackRate: Double? = null
        var elapsedTime: Double? = null
        var trackTitle: String? = null
        var trackAlbum: String? = null
        var trackArtist: String? = null
        var trackDuration: Double? = null

        val allowedActions = mutableSetOf<Int>()

        fun executeRemoteCommand(action: Byte) {
            val rc = amsService.remoteCommand
            if (rc != null) {
                flowScope.launch {
                    try {
                        bleDevice.writeCharacteristic(rc, byteArrayOf(action))
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed to execute remote command", e)
                    }
                }
            }
        }

        val sessionCallback = object : MediaSessionCompat.Callback() {
            override fun onPlay() {
                executeRemoteCommand(0)
            }

            override fun onPause() {
                executeRemoteCommand(1)
            }

            override fun onSkipToNext() {
                executeRemoteCommand(3)
            }

            override fun onSkipToPrevious() {
                executeRemoteCommand(4)
            }
        }

        /**
         * Update the media notification with the current playback state and metadata.
         *
         * Do not call this function directly, use the flow to trigger it.
         */
        suspend fun updateMediaNotification() {
            if (playbackState == 0 && playbackRate == null && elapsedTime == null) {
                cancelMediaNotification()
                return
            }

            val metadata = MediaMetadataCompat.Builder()

            trackTitle?.run {
                metadata.putString(MediaMetadataCompat.METADATA_KEY_TITLE, this)
            }
            trackArtist?.run {
                metadata.putString(MediaMetadataCompat.METADATA_KEY_ARTIST, this)
            }
            trackAlbum?.run {
                metadata.putString(MediaMetadataCompat.METADATA_KEY_ALBUM, this)
            }
            trackDuration?.times(1000.0)?.toLong()?.run {
                metadata.putLong(MediaMetadataCompat.METADATA_KEY_DURATION, this)
            }

            val notification = NotificationCompat.Builder(this, CHANNEL_ID_MEDIA)
                .setSmallIcon(R.drawable.ic_play_circle).setAutoCancel(false).setShowWhen(false)
                .setContentTitle(trackTitle).setSubText(bleDevice.name)

            val playbackStateCompat = PlaybackStateCompat.Builder()
            val isPlaying = when (playbackState) {
                0 -> {
                    playbackStateCompat.setState(
                        PlaybackStateCompat.STATE_PAUSED,
                        elapsedTime?.times(1000.0)?.toLong() ?: 0,
                        playbackRate?.toFloat() ?: 1.0f
                    )
                    false
                }

                in 1..3 -> {
                    playbackStateCompat.setState(
                        PlaybackStateCompat.STATE_PLAYING,
                        elapsedTime?.times(1000.0)?.toLong() ?: 0,
                        playbackRate?.toFloat() ?: 1.0f
                    )
                    notification.setOngoing(true)
                    true
                }

                else -> false
            }

            val contentText = if (trackAlbum != null && trackArtist != null) {
                "$trackAlbum - $trackArtist"
            } else if (trackAlbum != null) {
                "$trackAlbum"
            } else if (trackArtist != null) {
                "$trackArtist"
            } else {
                ""
            }
            if (contentText.isNotEmpty()) {
                notification.setContentText(contentText)
            }

            var playbackActions: Long = 0
            var numActions = 0
            if (allowedActions.contains(4)) {
                // Prev
                playbackActions = playbackActions or PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS
                numActions += 1
            }
            if (!isPlaying && allowedActions.contains(1)) {
                // Pause
                playbackActions = playbackActions or PlaybackStateCompat.ACTION_PLAY
                numActions += 1
            }
            if (isPlaying && allowedActions.contains(0)) {
                // Play
                playbackActions = playbackActions or PlaybackStateCompat.ACTION_PAUSE
                numActions += 1
            }
            if (allowedActions.contains(3)) {
                // Next
                playbackActions = playbackActions or PlaybackStateCompat.ACTION_SKIP_TO_NEXT
                numActions += 1
            }
            playbackStateCompat.setActions(playbackActions)

            val mediaStyle = androidx.media.app.NotificationCompat.MediaStyle()
            if (numActions == 1) {
                mediaStyle.setShowActionsInCompactView(0)
            } else if (numActions == 2) {
                mediaStyle.setShowActionsInCompactView(0, 1)
            } else if (numActions >= 3) {
                mediaStyle.setShowActionsInCompactView(0, 1, 2)
            }

            mediaSessionMutex.withLock {
                val currentSession = mediaSession ?: MediaSessionCompat(this, "appleAms")
                currentSession.apply {
                    setCallback(sessionCallback, Handler(Looper.getMainLooper()))
                    setMetadata(metadata.build())
                    setPlaybackState(playbackStateCompat.build())
                    isActive = true
                }
                mediaStyle.setMediaSession(currentSession.sessionToken)
                notification.setStyle(mediaStyle)
                NotificationManagerCompat.from(this@AncsService)
                    .notify(NOTI_ID_MEDIA, notification.build())
            }
        }

        flowScope.launch @FlowPreview {
            stateUpdateFlow.debounce(500).collect {
                updateMediaNotification()
            }
        }

        if (amsService.remoteCommand != null) {
            val remoteCommandFlow = bleDevice.subscribe(amsService.remoteCommand, 8)
            flowScope.launch {
                remoteCommandFlow.collect {
                    Log.i(TAG, "AMS remote command: ${it.toList()}")

                    allowedActions.clear()
                    it.forEach { action ->
                        allowedActions.add(action.toInt())
                    }

                    stateUpdateFlow.tryEmit(Unit)
                }
            }
            withTimeout(2000) {
                bleDevice.setNotification(amsService.remoteCommand, true)
            }
            Log.i(TAG, "AMS remote command enabled")
        }

        if (amsService.entityUpdate != null) {
            val entityUpdateFlow = bleDevice.subscribe(amsService.entityUpdate, 8)
            flowScope.launch {
                entityUpdateFlow.collect {
                    val update = try {
                        EntityUpdateNotification.Companion.parse(it)
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed to parse EntityUpdateNotification", e)
                        return@collect
                    }

                    Log.i(TAG, "AMS entity update: $update")

                    when (update.entityId.toInt()) {
                        0 -> {
                            // EntityIDPlayer
                            if (update.attributeId.toInt() == 1) {
                                val playerStates = update.value.split(',')
                                if (playerStates.size != 3) {
                                    Log.e(TAG, "Invalid player state: ${update.value}")
                                    return@collect
                                }
                                playbackState = playerStates[0].toIntOrNull() ?: 0
                                playbackRate = playerStates[1].toDoubleOrNull()
                                elapsedTime = playerStates[2].toDoubleOrNull()
                            }
                        }

                        2 -> {
                            // EntityIDTrack
                            when (update.attributeId.toInt()) {
                                0 -> {
                                    trackArtist = update.value
                                }

                                1 -> {
                                    trackAlbum = update.value
                                }

                                2 -> {
                                    trackTitle = update.value
                                }

                                3 -> {
                                    trackDuration = update.value.toDoubleOrNull()
                                }
                            }
                        }
                    }

                    stateUpdateFlow.tryEmit(Unit)
                }
            }
            // Subscribe to entity update
            withTimeout(2000) {
                bleDevice.setNotification(amsService.entityUpdate, true)
                // EntityIDPlayer, PlayerAttributeIDPlaybackInfo
                bleDevice.writeCharacteristic(amsService.entityUpdate, byteArrayOf(0, 1))
                // EntityIDTrack, TrackAttributeIDArtist, TrackAttributeIDAlbum
                // TrackAttributeIDTitle, TrackAttributeIDDuration
                bleDevice.writeCharacteristic(amsService.entityUpdate, byteArrayOf(2, 0, 1, 2, 3))
            }
            Log.i(TAG, "AMS entity update enabled")
        }
    }

    @SuppressLint("MissingPermission")
    private suspend fun mainTask(device: BluetoothDevice) {
        val flowFuture = CompletableDeferred<Unit>()
        val flowScope =
            CoroutineScope(coroutineContext + SupervisorJob() + CoroutineExceptionHandler { _, e ->
                Log.e(TAG, "Flow scope error", e)
                flowFuture.completeExceptionally(e)
            })

        Log.i(TAG, "Connecting to $device")

        BleDevice(device).use { bleDevice ->
            try {
                withTimeout(1.minutes) {
                    bleDevice.connect(this@AncsService, true)
                }
                Log.i(TAG, "Connected to $device")

                val stateFlow = bleDevice.connectionStateFlow
                val completeFuture = CompletableDeferred<Unit>()
                flowScope.launch {
                    stateFlow.collect {
                        if (it != BluetoothGatt.STATE_CONNECTED) {
                            Log.e(TAG, "State is no longer connected, completing future")
                            completeFuture.complete(Unit)
                        }
                    }
                }

                withTimeout(10.seconds) {
                    bleDevice.requestMtu(512)
                    bleDevice.discoverServices()

                    val initialBatteryLevel = handleBattery(bleDevice, flowScope)
                    handleAncs(bleDevice, flowScope)
                    handleAms(bleDevice, flowScope)
                    updateConnectedNotification(bleDevice.name, initialBatteryLevel)
                }

                Log.i(TAG, "Setup complete")

                select {
                    flowFuture.onAwait {}
                    completeFuture.onAwait {
                        Log.i(TAG, "Disconnected, ending mainTask")
                    }
                }

                notificationManager.notify(
                    NOTI_ID_PERSIST, createNotification(false, bleDevice.name, null)
                )
            } finally {
                flowScope.cancel()
            }
        }
    }

    private fun postNotification(
        deviceName: String, appId: String, appName: String, builder: NotificationCompat.Builder
    ) {
        val currentChannel = notificationManager.getNotificationChannel(appId)
        val channelBuilder = if (currentChannel == null || currentChannel.name != appName) {
            NotificationChannelCompat.Builder(
                appId, NotificationManagerCompat.IMPORTANCE_MAX
            ).setGroup(GROUP_ID_FWD).setDescription("App ID: $appId").setName(appName)
        } else {
            null
        }
        if (channelBuilder != null) {
            notificationManager.createNotificationChannel(channelBuilder.build())
        }

        builder.setContentTitle("$appName | ${builder.extras.getString("ancs.origTitle")}")
        builder.setGroup(appId)
        builder.setSubText(deviceName)

        val notification = builder.build()

        try {
            notificationManager.notify(
                NOTI_ID_FWD_BASE + notification.extras.getInt("ancs.uid"), notification
            )
        } catch (e: SecurityException) {
            Log.e(TAG, "Failed to post notification", e)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        return START_STICKY
    }

    private var actionRequestCode = 10
    private fun getActionIntent(action: String, uid: Int): PendingIntent {
        val intent = Intent(action)
        intent.putExtra("ancs.uid", uid)
        intent.setPackage(BuildConfig.APPLICATION_ID)
        actionRequestCode += 1
        return PendingIntent.getBroadcast(
            this, actionRequestCode, intent, PendingIntent.FLAG_IMMUTABLE
        )
    }

    private fun getActionRequest(uid: Int, actionId: Byte): ByteArray {
        val buffer = ByteBuffer.allocate(6)

        buffer.order(ByteOrder.LITTLE_ENDIAN)
        buffer.put(2) // CommandIDPerformNotificationAction
        buffer.putInt(uid)
        buffer.put(actionId)

        return buffer.array().copyOf(buffer.position())
    }

    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    private suspend fun writeControlPointAndLog(
        device: BleDevice, char: BluetoothGattCharacteristic, data: ByteArray
    ) {
        try {
            device.writeCharacteristic(char, data)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to write control point", e)
        }
    }

    @SuppressLint("MissingPermission")
    private fun updateConnectedNotification(deviceName: String, batteryLevel: Byte?) {
        notificationManager.notify(
            NOTI_ID_PERSIST, createNotification(true, deviceName, batteryLevel)
        )
    }

    private fun createNotification(
        connected: Boolean, deviceName: String?, batteryLevel: Byte?
    ): Notification {
        val pi = PendingIntent.getBroadcast(
            this, 0, ServiceState.getStopIntent(), PendingIntent.FLAG_IMMUTABLE
        )

        val builder = NotificationCompat.Builder(this, CHANNEL_ID_PERSIST).setSmallIcon(
            if (connected) {
                R.drawable.baseline_phonelink
            } else {
                R.drawable.baseline_phonelink_off
            }
        ).setContentTitle(
            if (connected) {
                if (batteryLevel != null) {
                    getString(R.string.connected_with_batt, batteryLevel)
                } else {
                    getString(R.string.connected)
                }
            } else {
                getString(R.string.not_connected)
            }
        ).setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .addAction(R.drawable.ic_close, getString(R.string.stop), pi)
            .setContentIntent(
                PendingIntent.getActivity(
                    this,
                    0,
                    Intent(this, MainActivity::class.java),
                    PendingIntent.FLAG_IMMUTABLE
                )
            )

        if (connected) {
            builder.setSubText(deviceName)
        }

        return builder.build()
    }

    private class ServiceDestroyedException : CancellationException("Service destroyed")

    @OptIn(DelicateCoroutinesApi::class)
    @SuppressLint("MissingPermission")
    override fun onDestroy() {
        super.onDestroy()

        unregisterReceiver(internalReceiver)
        unregisterReceiver(bluetoothReceiver)
        sendBroadcast(ServiceState.getUpdateIntent(false))

        GlobalScope.launch {
            try {
                cancelMediaNotification()
            } catch (e: Exception) {
                Log.e(TAG, "Failed to disable media notification in onDestroy", e)
            }
        }

        mainTask?.cancel(ServiceDestroyedException())
    }

    companion object {
        private const val TAG = "ClientService"
        private const val ACTION_POSITIVE_ACTION = "${BuildConfig.APPLICATION_ID}.POSITIVE_ACTION"
        private const val ACTION_NEGATIVE_ACTION = "${BuildConfig.APPLICATION_ID}.NEGATIVE_ACTION"

        fun getIntent(context: Context): Intent {
            return Intent(context, AncsService::class.java)
        }

        fun start(context: Context) {
            context.startService(getIntent(context))
        }

        fun stop(context: Context) {
            context.stopService(getIntent(context))
        }
    }
}