package dev.alastorkaneki.adbovertcp

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.PixelFormat
import android.os.Build
import android.os.IBinder
import android.provider.Settings
import android.view.Gravity
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class OverlayService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var view: LinearLayout? = null
    private lateinit var windowManager: WindowManager
    private lateinit var status: TextView
    private lateinit var controller: AdbController

    override fun onCreate() {
        super.onCreate()
        controller = AdbController(this)
        createChannel()
        startAsForeground()
        if (Settings.canDrawOverlays(this)) addOverlay() else stopSelf()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun addOverlay() {
        windowManager = getSystemService(WindowManager::class.java)
        status = TextView(this).apply {
            setTextColor(0xFFFFFFFF.toInt())
            text = "ADB: checking"
            setPadding(16, 12, 16, 8)
        }
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(0xEE080808.toInt())
            addView(status)
            addView(button("Start") { action { controller.reconnect() } })
            addView(button("Safe Off") { action { controller.safeOff().toString() } })
            addView(button("Full Off") { action { controller.fullOff().toString() } })
            addView(button("Open") {
                startActivity(Intent(this@OverlayService, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            })
            addView(button("×") { stopSelf() })
        }
        view = row
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            if (Build.VERSION.SDK_INT >= 26) WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY else WindowManager.LayoutParams.TYPE_PHONE,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            y = 40
        }
        windowManager.addView(row, params)
        action { controller.reconnect() }
    }

    private fun button(label: String, click: () -> Unit) = Button(this).apply {
        text = label
        isAllCaps = false
        minWidth = 0
        minimumWidth = 0
        setOnClickListener { click() }
    }

    private fun action(block: () -> String) {
        status.text = "ADB: working"
        scope.launch {
            val result = withContext(Dispatchers.IO) { runCatching(block).getOrElse { it.message.orEmpty() } }
            status.text = if (controller.isLoopbackListening()) "ADB: TCP open" else "ADB: TCP closed"
            if (result.isNotBlank()) status.contentDescription = result
        }
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            getSystemService(NotificationManager::class.java).createNotificationChannel(
                NotificationChannel("adb_overlay", getString(R.string.notification_channel_name), NotificationManager.IMPORTANCE_LOW).apply {
                    description = getString(R.string.notification_channel_description)
                }
            )
        }
    }

    private fun startAsForeground() {
        val notification: Notification = NotificationCompat.Builder(this, "adb_overlay")
            .setSmallIcon(R.drawable.ic_launcher)
            .setContentTitle("ADB Over TCP")
            .setContentText("Local ADB overlay is active")
            .setOngoing(true)
            .build()
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(5555, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else startForeground(5555, notification)
    }

    override fun onDestroy() {
        view?.let { runCatching { windowManager.removeView(it) } }
        scope.cancel()
        super.onDestroy()
    }
}
