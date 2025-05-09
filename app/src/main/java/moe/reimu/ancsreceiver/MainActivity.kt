package moe.reimu.ancsreceiver

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Done
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AlertDialogDefaults
import androidx.compose.material3.BasicAlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.livedata.observeAsState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import kotlinx.coroutines.launch
import androidx.lifecycle.viewmodel.compose.viewModel
import com.google.accompanist.permissions.ExperimentalPermissionsApi
import com.google.accompanist.permissions.rememberMultiplePermissionsState
import moe.reimu.ancsreceiver.services.AncsService
import moe.reimu.ancsreceiver.ui.theme.ANCSReceiverTheme
import moe.reimu.ancsreceiver.utils.getReceiverFlags
import moe.reimu.ancsreceiver.utils.getRequiredPermissions
import moe.reimu.ancsreceiver.utils.registerInternalBroadcastReceiver

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            MainActivityContent()
        }
    }
}

data class BondedDevice(val device: BluetoothDevice, val name: String)

@OptIn(ExperimentalPermissionsApi::class)
@Composable
fun MainActivityContent(mainViewModel: MainViewModel = viewModel()) {
    val context = LocalContext.current

    val requiredBtPermissions = getRequiredPermissions()
    val permissions = rememberMultiplePermissionsState(requiredBtPermissions)

    val isPreview = LocalInspectionMode.current

    var bondedDevices by remember { mutableStateOf(listOf<BondedDevice>()) }
    var btErrorMessage by remember { mutableStateOf<Int?>(null) }
    val bluetoothConnectGranted =
        permissions.permissions.find { it.permission == Manifest.permission.BLUETOOTH_CONNECT }?.status

    DisposableEffect(bluetoothConnectGranted, context, isPreview) {
        fun updateBondedDevices() {
            if (isPreview) {
                return
            }

            Log.i("UpdateBondedDevices", "Updating")

            val btManager = context.getSystemService(BluetoothManager::class.java)
            val btAdapter = btManager.adapter

            try {
                if (btAdapter == null || !btAdapter.isEnabled) {
                    btErrorMessage = R.string.bluetooth_disabled
                } else {
                    bondedDevices = btAdapter.bondedDevices.map {
                        BondedDevice(it, it.name)
                    }
                    btErrorMessage = null
                }
            } catch (e: SecurityException) {
                Log.e("UpdateBondedDevices", "Got SecurityException", e)
                btErrorMessage = R.string.bluetooth_not_granted
            } catch (e: Throwable) {
                Log.e("UpdateBondedDevices", "Got Exception", e)
                btErrorMessage = R.string.failed_to_list_devices
            }
        }

        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                updateBondedDevices()
            }
        }

        context.registerReceiver(
            receiver,
            IntentFilter().apply {
                addAction(BluetoothDevice.ACTION_BOND_STATE_CHANGED)
                addAction(BluetoothAdapter.ACTION_STATE_CHANGED)
            },
            getReceiverFlags()
        )

        updateBondedDevices()

        onDispose {
            context.unregisterReceiver(receiver)
        }
    }

    var showPairDialog by remember { mutableStateOf(false) }


    val currentDeviceAddress by mainViewModel.deviceAddress.observeAsState()
    val setupState by mainViewModel.setupState.observeAsState()

    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }

    ANCSReceiverTheme {
        MyScaffold(snackbarHost = { SnackbarHost(hostState = snackbarHostState) }) { innerPadding ->
            MainList(modifier = Modifier.padding(innerPadding)) {
                item {
                    DefaultCard {
                        Row(
                            modifier = Modifier.padding(16.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            MyIcon(Icons.Filled.Notifications)
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = stringResource(R.string.enable_service),
                                    style = MaterialTheme.typography.titleMedium,
                                )
                                Text(
                                    text = stringResource(R.string.enable_service_desc),
                                )
                            }
                            ServiceSwitch(
                                modifier = Modifier.padding(start = 8.dp),
                                enabled = permissions.allPermissionsGranted && currentDeviceAddress != null
                            )
                        }
                    }
                }

                item {
                    ServerServiceCard()
                }

                if (!permissions.allPermissionsGranted) {
                    item {
                        DefaultCard(onClick = {
                            permissions.launchMultiplePermissionRequest()
                        }) {
                            Row(
                                modifier = Modifier.padding(16.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                MyIcon(Icons.Filled.Warning)
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        stringResource(R.string.perm_required),
                                        style = MaterialTheme.typography.titleMedium
                                    )
                                    Text(stringResource(R.string.perm_required_desc))
                                }
                            }
                        }
                    }
                }

                item {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(text = stringResource(R.string.paired_devices))
                        IconButton(onClick = {
                            showPairDialog = true
                        }) {
                            Icon(
                                imageVector = Icons.Filled.Add,
                                contentDescription = null,
                            )
                        }
                    }
                }
                if (btErrorMessage == null) {
                    items(bondedDevices.size, key = { bondedDevices[it].device.address }) {
                        val device = bondedDevices[it]
                        val setupDoneMessage = stringResource(R.string.setup_done)

                        DefaultCard(onClick = {
                            mainViewModel.setupDevice(device.device, onDone = {
                                scope.launch {
                                    snackbarHostState.showSnackbar(setupDoneMessage)
                                }
                            }, onFailed = {
                                scope.launch {
                                    val message = it.message
                                    if (message != null) {
                                        snackbarHostState.showSnackbar(message)
                                    }
                                }
                            })
                        }) {
                            Row(
                                modifier = Modifier.padding(16.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                MyIcon(ImageVector.vectorResource(R.drawable.baseline_phone_iphone))
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = device.name,
                                        style = MaterialTheme.typography.titleMedium,
                                    )
//                                    Text(
//                                        text = device.device.address,
//                                    )
                                }
                                if (device.device.address == currentDeviceAddress) {
                                    Icon(
                                        imageVector = Icons.Filled.Done,
                                        contentDescription = null,
                                    )
                                }
                            }
                        }
                    }
                } else {
                    item {
                        DefaultCard {
                            Row(
                                modifier = Modifier.padding(16.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                MyIcon(Icons.Filled.Warning)
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        stringResource(btErrorMessage!!),
                                        style = MaterialTheme.typography.titleMedium
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
        if (showPairDialog) {
            BeforePairingDialog(
                onDismissRequest = { showPairDialog = false },
                onConfirmation = {
                    showPairDialog = false
                    val intent = Intent(Settings.ACTION_BLUETOOTH_SETTINGS)
                    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    context.startActivity(intent)
                }
            )
        }
        if (setupState != null) {
            SetupProgressDialog(
                onDismissRequest = {
                    mainViewModel.cancelSetup()
                },
                progressText = when (setupState) {
                    0 -> stringResource(R.string.setup_connecting)
                    1 -> stringResource(R.string.setup_info)
                    else -> ""
                }
            )
        }
    }
}

@Composable
@Preview(locale = "zh-rCN")
fun MainActivityPreview() {
    MainActivityContent()
}

@Composable
fun MyIcon(imageVector: ImageVector) {
    Icon(
        imageVector = imageVector,
        contentDescription = null,
        modifier = Modifier
            .size(48.dp)
            .padding(end = 16.dp),
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MyScaffold(
    snackbarHost: @Composable () -> Unit = {},
    content: @Composable (PaddingValues) -> Unit
) {
    Scaffold(topBar = {
        TopAppBar(title = { Text(text = stringResource(R.string.app_name)) })
    }, content = content, snackbarHost = snackbarHost)
}

@Composable
fun MainList(modifier: Modifier = Modifier, content: LazyListScope.() -> Unit) {
    val listState = rememberLazyListState()
    LazyColumn(
        modifier = modifier,
        state = listState,
        verticalArrangement = Arrangement.spacedBy(8.dp),
        contentPadding = PaddingValues(horizontal = 16.dp),
        content = content
    )
}

@Composable
fun DefaultCard(
    modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit
) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
        ), modifier = modifier.fillMaxWidth(), content = content
    )
}

@Composable
fun DefaultCard(
    onClick: () -> Unit, modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit
) {
    Card(
        onClick = onClick, colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
        ), modifier = modifier.fillMaxWidth(), content = content
    )
}

/**
 * Tell the user how to pair the device
 */
@Composable
fun BeforePairingDialog(onDismissRequest: () -> Unit, onConfirmation: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismissRequest,
        confirmButton = {
            TextButton(
                onClick = {
                    onConfirmation()
                }
            ) {
                Text(stringResource(android.R.string.ok))
            }
        },
        title = { Text(stringResource(R.string.pair_dialog_title)) },
        text = { Text(stringResource(R.string.pair_dialog_text)) }
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SetupProgressDialog(onDismissRequest: () -> Unit, progressText: String) {
    BasicAlertDialog(
        onDismissRequest = onDismissRequest
    ) {
        Surface(
            modifier = Modifier
                .wrapContentWidth()
                .wrapContentHeight(),
            shape = MaterialTheme.shapes.large,
            tonalElevation = AlertDialogDefaults.TonalElevation
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                LinearProgressIndicator(
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = progressText,
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier
                        .fillMaxWidth()
                        .wrapContentWidth(Alignment.CenterHorizontally)
                )
            }
        }
    }
}

@Composable
fun ServiceSwitch(modifier: Modifier = Modifier, enabled: Boolean) {
    val context = LocalContext.current

    var checked by remember { mutableStateOf(false) }

    DisposableEffect(context) {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                if (intent.action == ServiceState.ACTION_UPDATE_RECEIVER_STATE) {
                    checked = intent.getBooleanExtra("isRunning", false)
                }
            }
        }

        context.registerInternalBroadcastReceiver(
            receiver,
            IntentFilter(ServiceState.ACTION_UPDATE_RECEIVER_STATE),
        )
        context.sendBroadcast(ServiceState.getQueryIntent())

        onDispose {
            context.unregisterReceiver(receiver)
        }
    }

    Switch(modifier = modifier, checked = checked, onCheckedChange = {
        if (it) {
            AncsService.start(context)
        } else {
            AncsService.stop(context)
        }
    }, enabled = enabled)
}

@Composable
fun ServerServiceCard() {
    val context = LocalContext.current
    var enabled by remember { mutableStateOf(false) }

    LifecycleResumeEffect(context) {
        val enabledListeners = Settings.Secure.getString(
            context.contentResolver, "enabled_notification_listeners"
        )
        enabled = enabledListeners?.contains(context.packageName) == true

        onPauseOrDispose { }
    }

    DefaultCard(onClick = {
        val intent = Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
    }) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            MyIcon(Icons.Filled.Share)
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    stringResource(R.string.server_service_name),
                    style = MaterialTheme.typography.titleMedium
                )
                Text(stringResource(R.string.server_service_desc))
            }
            Text(
                text = if (enabled) {
                    stringResource(R.string.service_enabled)
                } else {
                    stringResource(R.string.service_disabled)
                }, modifier = Modifier.padding(start = 8.dp)
            )
        }
    }
}

