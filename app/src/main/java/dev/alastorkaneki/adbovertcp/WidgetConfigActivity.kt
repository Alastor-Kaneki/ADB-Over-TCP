package dev.alastorkaneki.adbovertcp

import android.appwidget.AppWidgetManager
import android.content.Intent
import android.os.Bundle
import android.widget.Button
import android.widget.CheckBox
import androidx.appcompat.app.AppCompatActivity

class WidgetConfigActivity : AppCompatActivity() {
    private var appWidgetId: Int = AppWidgetManager.INVALID_APPWIDGET_ID

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setResult(RESULT_CANCELED)
        setContentView(R.layout.activity_widget_config)

        appWidgetId = intent?.extras?.getInt(
            AppWidgetManager.EXTRA_APPWIDGET_ID,
            AppWidgetManager.INVALID_APPWIDGET_ID
        ) ?: AppWidgetManager.INVALID_APPWIDGET_ID
        if (appWidgetId == AppWidgetManager.INVALID_APPWIDGET_ID) {
            finish()
            return
        }

        val startShizuku = findViewById<CheckBox>(R.id.widgetStartShizuku)
        val forgetAfter = findViewById<CheckBox>(R.id.widgetForgetAfter)
        startShizuku.isChecked = WidgetPreferences.startShizuku(this, appWidgetId)
        forgetAfter.isChecked = WidgetPreferences.forgetAfterSetup(this, appWidgetId)

        findViewById<Button>(R.id.saveWidget).setOnClickListener {
            WidgetPreferences.save(
                this,
                appWidgetId,
                startShizuku.isChecked,
                forgetAfter.isChecked
            )
            AdbPairingWidgetProvider.updateWidget(
                this,
                AppWidgetManager.getInstance(this),
                appWidgetId
            )
            setResult(
                RESULT_OK,
                Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId)
            )
            finish()
        }
    }
}
