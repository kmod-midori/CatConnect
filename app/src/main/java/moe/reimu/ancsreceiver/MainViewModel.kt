package moe.reimu.ancsreceiver

import android.annotation.SuppressLint
import android.app.Application
import android.bluetooth.BluetoothDevice
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import moe.reimu.ancsreceiver.ancs.AncsBleService
import moe.reimu.ancsreceiver.ble.BleDevice

class MainViewModel(app: Application) : AndroidViewModel(app) {
    private val appContext = app as MyApplication

    private val _setupState = MutableLiveData<Int?>(null)
    val setupState: LiveData<Int?> = _setupState

    private var setupJob: Job? = null

    @SuppressLint("MissingPermission")
    fun setupDevice(device: BluetoothDevice, onDone: () -> Unit, onFailed: (e: Exception) -> Unit) {
        if (setupJob != null) {
            setupJob?.cancel()
            setupJob = null
        }

        setupJob = viewModelScope.launch {
            _setupState.postValue(0)

            val bleDevice = BleDevice(device)
            try {
                bleDevice.connect(getApplication(), false)

                Log.i("MainViewModel", "Connected to device")
                _setupState.postValue(1)

                bleDevice.discoverServices()
                Log.i("MainViewModel", "Services discovered")

                withTimeout(5000) {
                    val ancsService = AncsBleService(bleDevice)
                    bleDevice.setNotification(ancsService.notificationSource, true)
                    bleDevice.setNotification(ancsService.dataSource, true)
                }

                appContext.getSettings().deviceAddress = device.address

                onDone.invoke()
            } catch (e: Exception) {
                Log.e("MainViewModel", "Error setting up device", e)
                onFailed.invoke(e)
            } finally {
                _setupState.postValue(null)
                bleDevice.close()
            }
        }
    }

    fun cancelSetup() {
        setupJob?.cancel()
        setupJob = null
    }

    val deviceAddress = appContext.getSettings().deviceAddressLive
}