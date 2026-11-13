package app.pact.enforce

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** Starts Pact after a reboot or an update so it can turn its accessibility service back on. */
class StartReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        SecureSettings.ensureAccessibility(context)
    }
}
