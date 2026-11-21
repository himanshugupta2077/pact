package app.pact.enforce

import android.app.admin.DeviceAdminReceiver
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent

/**
 * While this admin is active, `pm uninstall` fails and Force stop is greyed out. Uninstall
 * in Settings first asks to deactivate the admin, and the accessibility service closes
 * that page. Switched on from the Mac:
 * adb shell dpm set-active-admin app.pact/.enforce.PactAdmin
 */
class PactAdmin : DeviceAdminReceiver() {
    override fun onDisableRequested(context: Context, intent: Intent): CharSequence {
        SecureSettings.ensureAccessibility(context)
        return "Pact's limits stop working without this."
    }

    companion object {
        fun component(context: Context) = ComponentName(context, PactAdmin::class.java)

        fun isActive(context: Context): Boolean =
            context.getSystemService(DevicePolicyManager::class.java)
                ?.isAdminActive(component(context)) == true

        /** Lets `adb uninstall` work. Only reachable from the Mac, through UsbControl. */
        fun release(context: Context) {
            context.getSystemService(DevicePolicyManager::class.java)
                ?.removeActiveAdmin(component(context))
        }
    }
}
