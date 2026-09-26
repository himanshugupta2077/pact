package app.pact.ship

import android.content.Context
import android.content.pm.PackageManager
import android.provider.Settings
import android.util.Log
import app.pact.PactApp
import app.pact.util.hasUsageAccess
import app.pact.util.startOfDayMs
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.time.Instant
import java.time.ZoneId

/**
 * Posts today's foreground time to the toolkit host over Tailscale.
 * The cable is not involved. ActivityWatch on the phone stays local.
 */
class PhoneShip(private val context: Context) {
    fun uploadToday() {
        val token = readToken() ?: return
        if (!hasUsageAccess(context)) {
            Log.i(TAG, "usage access is off, so nothing was uploaded")
            return
        }
        val app = context.applicationContext as PactApp
        val now = System.currentTimeMillis()
        val spans = app.usage.foregroundSpans(startOfDayMs(now), now)
        val labels = HashMap<String, String>()
        val packages = context.packageManager
        for (span in spans) {
            if (span.packageName !in labels) {
                labels[span.packageName] = applicationLabel(packages, span.packageName)
            }
        }
        val report = buildReport(
            deviceId = DEVICE_ID,
            deviceName = deviceName(),
            hostname = HOSTNAME,
            tz = ZoneId.systemDefault(),
            now = Instant.ofEpochMilli(now),
            spans = spans,
            labels = labels,
        )
        val body = report.toString().toByteArray(Charsets.UTF_8)
        val code = post(token, body)
        Log.i(
            TAG,
            "shipped ${report.getString("day")} ${report.get("screen_seconds")}s http $code",
        )
    }

    private fun readToken(): String? {
        val file = File(context.filesDir, "ship/token")
        if (!file.isFile) {
            if (!missingTokenLogged) {
                missingTokenLogged = true
                Log.i(TAG, "no upload token in ${file.path}")
            }
            return null
        }
        val token = file.readText().trim()
        if (token.length < 16) return null
        return token
    }

    private fun deviceName(): String {
        val stored = Settings.Global.getString(context.contentResolver, "device_name")?.trim().orEmpty()
        return stored.ifBlank { "Nothing Phone (2)" }
    }

    private fun applicationLabel(packages: PackageManager, packageName: String): String {
        return runCatching {
            packages.getApplicationLabel(packages.getApplicationInfo(packageName, 0)).toString()
        }.getOrDefault(packageName.substringAfterLast('.'))
    }

    private fun post(token: String, body: ByteArray): Int {
        val conn = (URL(UPLOAD_URL).openConnection() as HttpURLConnection).apply {
            connectTimeout = 10_000
            readTimeout = 20_000
            requestMethod = "POST"
            doOutput = true
            setFixedLengthStreamingMode(body.size)
            setRequestProperty("Authorization", "Bearer $token")
            setRequestProperty("Content-Type", "application/json; charset=utf-8")
            setRequestProperty("Accept", "application/json")
        }
        try {
            conn.outputStream.use { it.write(body) }
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val text = stream?.bufferedReader()?.readText().orEmpty()
            if (code !in 200..299) {
                throw IOException("upload http $code ${text.take(160)}")
            }
            return code
        } finally {
            conn.disconnect()
        }
    }

    companion object {
        private const val TAG = "PactShip"
        const val DEVICE_ID = "11111111-1111-4111-8111-111111111111"
        private const val HOSTNAME = "phone"
        const val UPLOAD_URL = "http://pact.example:8765/activity"

        @Volatile
        private var missingTokenLogged = false
    }
}
