package moe.reimu.ancsreceiver

import android.app.Application
import moe.reimu.ancsreceiver.utils.createChannels

class MyApplication: Application() {
    private lateinit var settings: AppSettings

    override fun onCreate() {
        super.onCreate()
        instance = this
        createChannels(this)
        settings = AppSettings(this)
    }

    fun getSettings() = settings

    companion object {
        private var instance: MyApplication? = null

        fun getInstance() = instance!!
    }
}