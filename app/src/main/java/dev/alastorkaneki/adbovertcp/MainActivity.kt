package dev.alastorkaneki.adbovertcp

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : AppCompatActivity() {
    private lateinit var controller: AdbController
    private lateinit var output: TextView
    private lateinit var pairAddress: EditText
    private lateinit var pairCode: EditText
    private lateinit var connectPort: EditText

    private val notificationPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        controller = AdbController(this)
        output = findViewById(R.id.output)
        pairAddress = findViewById(R.id.pairAddress)
        pairCode = findViewById(R.id.pairCode)
        connectPort = findViewById(R.id.connectPort)
        findViewById<TextView>(R.id.networkStatus).text = NetworkClassifier.describe(this)

        val prefs = getSharedPreferences("adb_tcp", MODE_PRIVATE)
        pairAddress.setText(prefs.getString("pair_address", ""))
        connectPort.setText(prefs.getString("connect_port", ""))

        findViewById<Button>(R.id.openSettings).setOnClickListener { openWirelessSettings() }
        findViewById<Button>(R.id.pairEnable).setOnClickListener {
            val address = pairAddress.text.toString()
            val code = pairCode.text.toString()
            val port = connectPort.text.toString().toIntOrNull()
            if (port == null) {
                output.text = "Enter the new connection port shown on the main Wireless Debugging page."
                return@setOnClickListener
            }
            prefs.edit().putString("pair_address", address).putString("connect_port", port.toString()).apply()
            runTask("Pairing and switching to TCP 5555…") {
                controller.pairAndEnable(address, code, port)
            }
        }
        findViewById<Button>(R.id.reconnect).setOnClickListener {
            runTask("Reconnecting…") { controller.reconnect() }
        }
        findViewById<Button>(R.id.safeOff).setOnClickListener {
            runTask("Disconnecting safely…") { controller.safeOff().toString() }
        }
        findViewById<Button>(R.id.fullOff).setOnClickListener {
            runTask("Shutting down TCP ADB…") { controller.fullOff().toString() }
        }
        findViewById<Button>(R.id.grantPermissions).setOnClickListener {
            runTask("Granting eligible permissions/app-ops…") { controller.grantAppPermissions() }
        }
        findViewById<Button>(R.id.bootScan).setOnClickListener {
            runTask("Scanning boot compatibility and readable OEM init rules…") {
                controller.bootCompatibilityScan()
            }
        }
        findViewById<Button>(R.id.overlay).setOnClickListener { enableOverlay() }

        requestNotificationPermission()
        runTask("Checking local ADB listener…") { controller.reconnect() }
    }

    private fun runTask(initial: String, task: suspend () -> String) {
        output.text = initial
        lifecycleScope.launch {
            output.text = runCatching { withContext(Dispatchers.IO) { task() } }
                .getOrElse { it.stackTraceToString() }
        }
    }

    private fun openWirelessSettings() {
        val direct = Intent().apply {
            setClassName("com.android.settings", "com.android.settings.SubSettings")
            putExtra(":settings:show_fragment", "com.android.settings.development.WirelessDebuggingFragment")
            putExtra(":settings:show_fragment_as_subsetting", true)
        }
        runCatching { startActivity(direct) }.getOrElse {
            startActivity(Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS))
        }
    }

    private fun enableOverlay() {
        if (!Settings.canDrawOverlays(this)) {
            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
            output.text = "Grant Display over other apps, return here, then press the overlay button again."
            return
        }
        getSharedPreferences("adb_tcp", MODE_PRIVATE).edit().putBoolean("overlay_enabled", true).apply()
        ContextCompat.startForegroundService(this, Intent(this, OverlayService::class.java))
        output.text = "Overlay started."
    }

    private fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
    }
}
