package dev.alastorkaneki.adbovertcp

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.CheckBox
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
    private lateinit var pairCode: EditText
    private lateinit var startShizuku: CheckBox
    private lateinit var forgetAfterSetup: CheckBox
    private lateinit var networkStatus: TextView

    private val notificationPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        AdbHostService.start(this)
        controller = AdbController(this)
        output = findViewById(R.id.output)
        pairCode = findViewById(R.id.pairCode)
        startShizuku = findViewById(R.id.startShizuku)
        forgetAfterSetup = findViewById(R.id.forgetAfterSetup)
        networkStatus = findViewById(R.id.networkStatus)

        val prefs = getSharedPreferences(PairingService.PREFS, MODE_PRIVATE)
        startShizuku.isChecked = prefs.getBoolean(PairingService.PREF_START_SHIZUKU, true)
        forgetAfterSetup.isChecked = prefs.getBoolean(
            PairingService.PREF_FORGET_AFTER_SETUP,
            false
        )
        startShizuku.setOnCheckedChangeListener { _, checked ->
            prefs.edit().putBoolean(PairingService.PREF_START_SHIZUKU, checked).apply()
        }
        forgetAfterSetup.setOnCheckedChangeListener { _, checked ->
            prefs.edit().putBoolean(PairingService.PREF_FORGET_AFTER_SETUP, checked).apply()
        }

        findViewById<Button>(R.id.autoPair).setOnClickListener {
            startAutomaticPairing(startShizuku.isChecked, forgetAfterSetup.isChecked)
        }
        findViewById<Button>(R.id.submitCode).setOnClickListener {
            val code = pairCode.text.toString().filter(Char::isDigit)
            if (code.length != 6) {
                output.text = "Enter the six-digit pairing code."
            } else {
                PairingService.submitCode(this, code)
                pairCode.text?.clear()
                output.text = "Pairing code submitted. The app is discovering the normal ADB connection service."
            }
        }
        findViewById<Button>(R.id.openSettings).setOnClickListener { openWirelessSettings() }
        findViewById<Button>(R.id.reconnect).setOnClickListener {
            runTask("Reconnecting…") { controller.reconnect() }
        }
        findViewById<Button>(R.id.startShizukuNow).setOnClickListener {
            runTask("Starting Shizuku through loopback ADB…") { controller.startShizuku().toString() }
        }
        findViewById<Button>(R.id.toggleWifi).setOnClickListener {
            runTask("Toggling Wi-Fi through loopback ADB…") { controller.toggleWifi() }
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
        handleLaunchIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleLaunchIntent(intent)
    }

    override fun onResume() {
        super.onResume()
        networkStatus.text = NetworkClassifier.describe(this)
        val last = getSharedPreferences(PairingService.PREFS, MODE_PRIVATE)
            .getString(PairingService.PREF_LAST_RESULT, null)
        if (!last.isNullOrBlank()) output.text = last
    }

    private fun handleLaunchIntent(launchIntent: Intent?) {
        if (launchIntent?.action != ACTION_WIDGET_PAIR) return
        val widgetStartShizuku = launchIntent.getBooleanExtra(EXTRA_WIDGET_START_SHIZUKU, true)
        val widgetForgetAfter = launchIntent.getBooleanExtra(EXTRA_WIDGET_FORGET_AFTER, false)
        startShizuku.isChecked = widgetStartShizuku
        forgetAfterSetup.isChecked = widgetForgetAfter
        startAutomaticPairing(widgetStartShizuku, widgetForgetAfter)
        setIntent(Intent(this, MainActivity::class.java))
    }

    private fun startAutomaticPairing(
        startShizukuAfterSetup: Boolean,
        forgetAfter: Boolean
    ) {
        getSharedPreferences(PairingService.PREFS, MODE_PRIVATE)
            .edit()
            .putBoolean(PairingService.PREF_START_SHIZUKU, startShizukuAfterSetup)
            .putBoolean(PairingService.PREF_FORGET_AFTER_SETUP, forgetAfter)
            .apply()
        requestNotificationPermission()
        AdbHostService.start(this)
        PairingService.start(this, startShizukuAfterSetup, forgetAfter)
        output.text = if (forgetAfter) {
            "Automatic pairing started. After TCP setup${if (startShizukuAfterSetup) " and Shizuku startup" else ""}, the app will attempt to remove its own ADB key from Paired devices."
        } else {
            "Automatic pairing started. In Wireless debugging, enable the toggle and tap “Pair device with pairing code”. The app will detect the address and ports itself."
        }
        openWirelessSettings()
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
        getSharedPreferences(PairingService.PREFS, MODE_PRIVATE)
            .edit()
            .putBoolean("overlay_enabled", true)
            .apply()
        ContextCompat.startForegroundService(this, Intent(this, OverlayService::class.java))
        output.text = "Overlay started."
    }

    private fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
    }

    companion object {
        const val ACTION_WIDGET_PAIR =
            "dev.alastorkaneki.adbovertcp.action.WIDGET_PAIR"
        const val EXTRA_WIDGET_START_SHIZUKU = "widget_start_shizuku"
        const val EXTRA_WIDGET_FORGET_AFTER = "widget_forget_after"
    }
}
