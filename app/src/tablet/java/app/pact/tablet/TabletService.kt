package app.pact.tablet

import android.app.*
import android.app.admin.DevicePolicyManager
import android.bluetooth.BluetoothManager
import android.content.*
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager
import android.os.*
import android.util.Log
import app.pact.PactApp
import app.pact.enforce.PactAdmin
import kotlinx.coroutines.*

class TabletApp : PactApp() {
    override fun startUploader() {
        if (getSystemService(DevicePolicyManager::class.java).isDeviceOwnerApp(packageName)) {
            startForegroundService(Intent(this, TabletService::class.java))
        }
    }
}

class TabletService : Service() {
    private val policy by lazy { getSystemService(DevicePolicyManager::class.java) }
    private val admin by lazy { PactAdmin.component(this) }
    private val wifi by lazy { applicationContext.getSystemService(WifiManager::class.java) }
    private val power by lazy { getSystemService(PowerManager::class.java) }
    private val alarms by lazy { getSystemService(AlarmManager::class.java) }
    private val connectivity by lazy { getSystemService(ConnectivityManager::class.java) }
    private val prefs by lazy { getSharedPreferences("tablet_policy", MODE_PRIVATE) }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var upload: Job? = null

    private fun alarm(action: String) = PendingIntent.getForegroundService(this, 0,
        Intent(this, TabletService::class.java).setAction(action), PendingIntent.FLAG_IMMUTABLE)

    private fun arm(action: String, deadline: Long = SystemClock.elapsedRealtime() + 300_000) {
        alarms.setExactAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP,
            deadline, alarm(action))
    }

    private fun scheduleRadioShutdown(newSleep: Boolean) {
        val boot = android.provider.Settings.Global.getInt(contentResolver, "boot_count", -1)
        val saved = if (prefs.getInt("boot", -2) == boot) prefs.getLong("sleepDeadline", 0) else 0
        val deadline = if (newSleep || saved == 0L) SystemClock.elapsedRealtime() + 300_000 else saved
        prefs.edit().putInt("boot", boot).putLong("sleepDeadline", deadline).apply()
        arm(SLEEP, deadline)
    }

    private val screen = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == Intent.ACTION_SCREEN_ON) {
                prefs.edit().remove("sleepDeadline").apply()
                alarms.cancel(alarm(SLEEP))
                wifi.setWifiEnabled(true)
                getSystemService(BluetoothManager::class.java).adapter?.enable()
            } else scheduleRadioShutdown(true)
        }
    }

    override fun onCreate() {
        super.onCreate()
        val channel = NotificationChannel("tablet", "Tablet controls", NotificationManager.IMPORTANCE_LOW)
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        startForeground(5406, Notification.Builder(this, channel.id)
            .setSmallIcon(android.R.drawable.ic_lock_idle_alarm).setContentTitle("Pact tablet controls").build())
        // NetGuard keeps the VPN slot. Reports travel over Pact's own userspace Tailscale node.
        restoreFirewall()
        registerReceiver(screen, IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_SCREEN_ON)
        })
        if (!power.isInteractive) scheduleRadioShutdown(false)
        scope.launch {
            Log.i(TAG, "Controller started")
            while (isActive) {
                if (upload?.isActive != true && due()) upload = scope.launch { sync() }
                delay(60_000)
            }
        }
    }

    private fun due(): Boolean {
        val now = System.currentTimeMillis()
        val last = prefs.getLong("lastOk", 0)
        return now >= prefs.getLong("retry", 0) && (now - last >= HOUR || last > now) &&
            connectivity.allNetworks.any {
                connectivity.getNetworkCapabilities(it)?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
            }
    }

    private fun restoreFirewall() {
        if (policy.getAlwaysOnVpnPackage(admin) != FIREWALL) {
            policy.setAlwaysOnVpnPackage(admin, FIREWALL, false)
        }
    }

    private suspend fun sync() {
        prefs.edit().putLong("retry", System.currentTimeMillis() + 15 * 60_000).apply()
        Log.i(TAG, "Upload starting")
        var bridge: TailnetBridge? = null
        try {
            bridge = TailnetBridge(this)
            val reports = TabletReports(this)
            var report = withContext(Dispatchers.IO) { reports.nextReport() }
            try {
                withContext(Dispatchers.IO) { bridge.up(90) }
            } catch (e: TailnetBridge.NeedsLogin) {
                // Keep this node waiting so the link in the notification stays valid.
                Log.w(TAG, "Tailnet login needed: ${e.url}")
                showLogin(e.url)
                withContext(Dispatchers.IO) { bridge.up(600) }
            }
            Log.i(TAG, "Tailnet node running")
            while (true) {
                withContext(Dispatchers.IO) { bridge.post(report) }
                if (reports.acknowledge(report)) break
                report = withContext(Dispatchers.IO) { reports.nextReport() }
            }
            prefs.edit().putLong("lastOk", System.currentTimeMillis()).remove("retry").apply()
            getSystemService(NotificationManager::class.java).cancel(LOGIN_NOTICE)
        } catch (e: TailnetBridge.NeedsLogin) {
            Log.w(TAG, "Tailnet login still needed")
            prefs.edit().putLong("retry", System.currentTimeMillis() + 60_000).apply()
        } catch (e: Exception) {
            Log.w(TAG, "Upload deferred: ${e.javaClass.simpleName}: ${e.message}")
        } finally {
            withContext(Dispatchers.IO + NonCancellable) { bridge?.close() }
        }
    }

    private fun showLogin(url: String) {
        val open = PendingIntent.getActivity(this, 1,
            Intent(Intent.ACTION_VIEW, android.net.Uri.parse(url)), PendingIntent.FLAG_IMMUTABLE)
        getSystemService(NotificationManager::class.java).notify(LOGIN_NOTICE,
            Notification.Builder(this, "tablet").setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
                .setContentTitle("Pact needs a Tailscale login").setContentText(url)
                .setContentIntent(open).build())
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            SLEEP -> if (!power.isInteractive && SystemClock.elapsedRealtime() >= prefs.getLong("sleepDeadline", Long.MAX_VALUE)) {
                wifi.setWifiEnabled(false)
                getSystemService(BluetoothManager::class.java).adapter?.disable()
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        unregisterReceiver(screen)
        scope.cancel()
        restoreFirewall()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?) = null

    companion object {
        private const val FIREWALL = "eu.faircode.netguard"
        private const val SLEEP = "tablet.sleep"
        private const val HOUR = 3_600_000L
        private const val LOGIN_NOTICE = 5407
        const val TAG = "PactTablet"
    }
}
