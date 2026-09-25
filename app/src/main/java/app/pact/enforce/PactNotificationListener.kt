package app.pact.enforce

import android.app.Notification
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import app.pact.PactApp

class PactNotificationListener : NotificationListenerService() {

    private val seenKeys = HashSet<String>()

    override fun onListenerConnected() {
        super.onListenerConnected()
        instance = this
        synchronized(seenKeys) {
            seenKeys.clear()
            val active = runCatching { activeNotifications }.getOrNull()
            if (active != null) {
                for (sbn in active) seenKeys.add(sbn.key)
            }
        }
        cancelBlocked()
    }

    override fun onListenerDisconnected() {
        if (instance === this) instance = null
        super.onListenerDisconnected()
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        val app = application as? PactApp ?: return
        if (app.engine.shouldBlock(sbn.packageName)) {
            runCatching { cancelNotification(sbn.key) }
        }
        if (!shouldCount(sbn)) return
        val firstTime = synchronized(seenKeys) { seenKeys.add(sbn.key) }
        if (firstTime) app.notifications.increment()
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification) {
        synchronized(seenKeys) { seenKeys.remove(sbn.key) }
    }

    private fun shouldCount(sbn: StatusBarNotification): Boolean {
        if (sbn.packageName == packageName) return false
        if (sbn.isOngoing) return false
        val n = sbn.notification
        val flags = n.flags
        if (flags and Notification.FLAG_GROUP_SUMMARY != 0) return false
        if (flags and Notification.FLAG_ONGOING_EVENT != 0) return false
        if (flags and Notification.FLAG_FOREGROUND_SERVICE != 0) return false
        if (isMediaNotification(n)) return false
        when (n.category) {
            Notification.CATEGORY_TRANSPORT,
            Notification.CATEGORY_SERVICE,
            Notification.CATEGORY_PROGRESS,
            -> return false
        }
        val app = application as? PactApp ?: return true
        return !app.installed.isNoise(sbn.packageName)
    }

    fun cancelPackages(packages: Set<String>) {
        val active = runCatching { activeNotifications }.getOrNull() ?: return
        for (sbn in active) {
            if (sbn.packageName in packages) {
                runCatching { cancelNotification(sbn.key) }
            }
        }
    }

    private fun cancelBlocked() {
        val app = application as? PactApp ?: return
        cancelPackages(app.engine.currentlyBlockedPackages())
    }

    companion object {
        @Volatile
        var instance: PactNotificationListener? = null
            private set

        private fun isMediaNotification(n: Notification): Boolean {
            if (n.extras.containsKey(Notification.EXTRA_MEDIA_SESSION)) return true
            val template = n.extras.getString(Notification.EXTRA_TEMPLATE).orEmpty()
            if (template.contains("MediaStyle") ||
                template.contains("MediaCustomView") ||
                template.contains(".media.app.")
            ) {
                return true
            }
            return hasTransportActions(n)
        }

        private fun hasTransportActions(n: Notification): Boolean {
            val actions = n.actions ?: return false
            if (actions.size < 2) return false
            var transport = 0
            for (action in actions) {
                val title = action.title?.toString()?.lowercase() ?: continue
                if (title.contains("play") ||
                    title.contains("pause") ||
                    title.contains("next") ||
                    title.contains("previous") ||
                    title.contains("skip") ||
                    title.contains("stop")
                ) {
                    transport += 1
                }
            }
            return transport >= 2
        }
    }
}
