package moe.reimu.ancsreceiver

import android.content.Context
import android.content.SharedPreferences
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData

class AppSettings(context: Context) {
    private val prefs: SharedPreferences = context.getSharedPreferences("app", Context.MODE_PRIVATE)

    var deviceAddress: String?
        get() = prefs.getString("deviceAddress", null)
        set(value) {
            prefs.edit().apply {
                putString("deviceAddress", value)
                apply()
            }
            _deviceAddressLive.postValue(value)
        }

    private val _deviceAddressLive = MutableLiveData<String?>(deviceAddress)
    val deviceAddressLive: LiveData<String?> = _deviceAddressLive
}