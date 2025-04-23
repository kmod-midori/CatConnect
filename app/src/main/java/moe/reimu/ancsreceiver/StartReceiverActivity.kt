package moe.reimu.ancsreceiver

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Bundle
import moe.reimu.ancsreceiver.services.AncsService

class StartReceiverActivity: Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        if (intent.getBooleanExtra("shouldStop", false)) {
            stopService(AncsService.getIntent(this))
        } else {
            startService(AncsService.getIntent(this))
        }

        finish()
    }

    companion object {
        fun getIntent(context: Context, shouldStop: Boolean): Intent {
            return Intent(context, StartReceiverActivity::class.java).apply {
                putExtra("shouldStop", shouldStop)
            }
        }
    }
}