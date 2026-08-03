package dev.alastorkaneki.adbovertcp

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Settings
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED && intent.action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val controller = AdbController(context)
                val listening = controller.isLoopbackListening(1_500)
                val result = if (listening) controller.reconnect() else "No loopback ADB listener after boot."
                context.getSharedPreferences("adb_tcp", Context.MODE_PRIVATE).edit()
                    .putBoolean("last_boot_listener", listening)
                    .putString("last_boot_result", result)
                    .apply()

                val overlayEnabled = context.getSharedPreferences("adb_tcp", Context.MODE_PRIVATE)
                    .getBoolean("overlay_enabled", false)
                if (overlayEnabled && Settings.canDrawOverlays(context)) {
                    runCatching {
                        ContextCompat.startForegroundService(context, Intent(context, OverlayService::class.java))
                    }
                }
            } finally {
                pending.finish()
            }
        }
    }
}
