package dev.alastorkaneki.adbovertcp

import android.app.Application
import com.google.android.material.color.DynamicColors

class AdbTcpApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        DynamicColors.applyToActivitiesIfAvailable(this)
    }
}
