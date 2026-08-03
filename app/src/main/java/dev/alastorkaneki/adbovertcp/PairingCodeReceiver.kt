package dev.alastorkaneki.adbovertcp

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.app.RemoteInput
import androidx.core.content.ContextCompat

class PairingCodeReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val code = RemoteInput.getResultsFromIntent(intent)
            ?.getCharSequence(PairingService.REMOTE_INPUT_CODE)
            ?.toString()
            ?.trim()
            .orEmpty()

        if (code.isBlank()) return

        val serviceIntent = Intent(context, PairingService::class.java).apply {
            action = PairingService.ACTION_SUBMIT_CODE
            putExtra(PairingService.EXTRA_PAIRING_CODE, code)
        }
        ContextCompat.startForegroundService(context, serviceIntent)
    }
}
