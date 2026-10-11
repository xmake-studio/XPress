package com.xpress.app

import android.app.Application
import com.xpress.app.job.CompressionService

class XPressApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        CompressionService.createChannels(this)
    }
}
