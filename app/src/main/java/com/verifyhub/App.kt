package com.verifyhub

import android.app.Application
import io.github.libxposed.service.XposedService
import io.github.libxposed.service.XposedServiceHelper
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

class App : Application(), XposedServiceHelper.OnServiceListener {

    override fun onCreate() {
        super.onCreate()
        instance = this
        // Probe whether the LSPosed framework is alive and aware of this module
        XposedServiceHelper.registerListener(this)
    }

    override fun onServiceBind(service: XposedService) {
        _xposedService.value = service
    }

    override fun onServiceDied(service: XposedService) {
        _xposedService.value = null
    }

    companion object {
        lateinit var instance: App
            private set

        private val _xposedService = MutableStateFlow<XposedService?>(null)
        val xposedService: StateFlow<XposedService?> = _xposedService
    }
}
