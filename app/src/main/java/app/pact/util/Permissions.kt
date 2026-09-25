package app.pact.util

import android.accessibilityservice.AccessibilityServiceInfo
import android.app.AppOpsManager
import android.app.NotificationManager
import android.content.ComponentName
import android.content.Context
import android.os.Build
import android.os.Process
import android.provider.Settings
import android.view.accessibility.AccessibilityManager
import app.pact.enforce.PactAccessibilityService
import app.pact.enforce.PactNotificationListener

fun hasUsageAccess(context: Context): Boolean {
    val appOps = context.getSystemService(AppOpsManager::class.java) ?: return false
    val mode = appOps.unsafeCheckOpNoThrow(
        AppOpsManager.OPSTR_GET_USAGE_STATS,
        Process.myUid(),
        context.packageName,
    )
    return mode == AppOpsManager.MODE_ALLOWED
}

fun hasAccessibility(context: Context): Boolean {
    val expected = ComponentName(context, PactAccessibilityService::class.java)
    val am = context.getSystemService(AccessibilityManager::class.java)
    val enabled = am?.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK).orEmpty()
    if (enabled.any {
            ComponentName(it.resolveInfo.serviceInfo.packageName, it.resolveInfo.serviceInfo.name) == expected
        }
    ) {
        return true
    }
    val raw = Settings.Secure.getString(
        context.contentResolver,
        Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
    ) ?: return false
    return raw.split(':').any { value ->
        runCatching { ComponentName.unflattenFromString(value) == expected }.getOrDefault(false)
    }
}

fun hasNotificationAccess(context: Context): Boolean {
    val cn = ComponentName(context, PactNotificationListener::class.java)
    if (Build.VERSION.SDK_INT >= 27) {
        val nm = context.getSystemService(NotificationManager::class.java)
        if (nm != null) return nm.isNotificationListenerAccessGranted(cn)
    }
    val raw = Settings.Secure.getString(context.contentResolver, "enabled_notification_listeners") ?: return false
    return raw.split(':').any { value ->
        runCatching { ComponentName.unflattenFromString(value) == cn }.getOrDefault(false)
    }
}
