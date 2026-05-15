package moe.reimu.ancsreceiver.utils

import android.content.Context
import androidx.annotation.StringRes

open class LocalizedException: Exception {
    @StringRes
    private val stringRes: Int

    private val stringArgs: Array<out Any>

    constructor(message: String, @StringRes stringRes: Int, vararg stringArgs: Any): super(message) {
        this.stringRes = stringRes
        this.stringArgs = stringArgs
    }

    fun getLocalizedMessage(context: Context) = context.getString(stringRes, *stringArgs)
}