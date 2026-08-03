package dev.alastorkaneki.adbovertcp

import android.app.Activity
import android.app.Application
import android.app.Application.ActivityLifecycleCallbacks
import android.os.Bundle
import com.google.android.material.color.DynamicColors

class AdbTcpApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        DynamicColors.applyToActivitiesIfAvailable(this)
        registerActivityLifecycleCallbacks(
            object : ActivityLifecycleCallbacks {
                override fun onActivityCreated(activity: Activity, state: Bundle?) {
                    ImmersiveMode.install(activity)
                }

                override fun onActivityResumed(activity: Activity) {
                    ImmersiveMode.hide(activity)
                }

                override fun onActivityDestroyed(activity: Activity) {
                    ImmersiveMode.uninstall(activity)
                }

                override fun onActivityStarted(activity: Activity) = Unit
                override fun onActivityPaused(activity: Activity) = Unit
                override fun onActivityStopped(activity: Activity) = Unit
                override fun onActivitySaveInstanceState(activity: Activity, state: Bundle) = Unit
            }
        )
    }
}
