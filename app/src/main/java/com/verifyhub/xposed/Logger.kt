package com.verifyhub.xposed

import android.util.Log
import io.github.libxposed.api.XposedInterface

object Logger {
    const val TAG = "VerifyHub"
    var xposed: XposedInterface? = null

    fun i(msg: String) {
        xposed?.log(Log.INFO, TAG, msg) ?: Log.i(TAG, msg)
    }

    fun w(msg: String, t: Throwable? = null) {
        if (t != null) xposed?.log(Log.WARN, TAG, "$msg: $t") ?: Log.w(TAG, msg, t)
        else xposed?.log(Log.WARN, TAG, msg) ?: Log.w(TAG, msg)
    }

    fun e(msg: String, t: Throwable? = null) {
        if (t != null) xposed?.log(Log.ERROR, TAG, "$msg: $t") ?: Log.e(TAG, msg, t)
        else xposed?.log(Log.ERROR, TAG, msg) ?: Log.e(TAG, msg)
    }
}
