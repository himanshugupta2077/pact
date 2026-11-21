package app.pact.tablet

import android.content.Context
import android.net.Uri
import android.os.Build
import app.pact.BuildConfig
import app.pact.data.UsageSpan
import app.pact.ship.buildReport
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.Proxy
import java.net.URL
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/** Read only foreground-app events from the tablet's loopback ActivityWatch server. */
internal class TabletReports(private val context: Context) {
    private val prefs = context.getSharedPreferences("tablet_policy", Context.MODE_PRIVATE)
    private val deviceId = java.util.UUID.fromString(credential("device-id")).toString()
    private val watcherToken = credential("aw-token")
    private fun credential(name: String) = File(context.filesDir, "ship/$name").readText().trim()
        .also { require(it.length >= 16) }

    private fun read(path: String): String {
        val connection = URL("http://127.0.0.1:5600/api/0/buckets/$path")
            .openConnection(Proxy.NO_PROXY) as HttpURLConnection
        try {
            connection.instanceFollowRedirects = false
            connection.connectTimeout = 3000
            connection.readTimeout = 10_000
            connection.setRequestProperty("Authorization", "Bearer $watcherToken")
            check(connection.responseCode == 200)
            return connection.inputStream.bufferedReader().use { it.readText() }
        } finally { connection.disconnect() }
    }

    fun nextReport(): JSONObject {
        val now = Instant.now()
        val zone = ZoneId.systemDefault()
        val today = now.atZone(zone).toLocalDate()
        val day = LocalDate.parse(prefs.getString("day", today.toString())).coerceAtMost(today)
        if (!prefs.contains("day")) check(prefs.edit().putString("day", day.toString()).commit())
        val start = day.atStartOfDay(zone).toInstant()
        val end = minOf(day.plusDays(1).atStartOfDay(zone).toInstant(), now)
        // ActivityWatch's server runs only after its screen has opened in the current process.
        // Without it, Pact's own usage-stats record covers the same window.
        val (spans, labels, source) = try {
            watcherSpans(start, end).let { Triple(it.first, it.second, "activitywatch") }
        } catch (e: java.io.IOException) {
            val own = (context.applicationContext as app.pact.PactApp).usage
                .foregroundSpans(start.toEpochMilli(), end.toEpochMilli())
            val pm = context.packageManager
            val names = own.map { it.packageName }.distinct().associateWith {
                runCatching { pm.getApplicationLabel(pm.getApplicationInfo(it, 0)).toString() }.getOrDefault(it)
            }
            Triple(own, names, "usage-stats")
        }
        return buildReport(deviceId, Build.MODEL, BuildConfig.SHIP_HOSTNAME,
            zone, end.minusMillis(1), spans, labels).put("source", source)
    }

    private fun watcherSpans(start: Instant, end: Instant): Pair<List<UsageSpan>, Map<String, String>> {
        val buckets = JSONObject(read(""))
        val bucket = buckets.keys().asSequence().single { buckets.getJSONObject(it).getString("type") == "currentwindow" }
        // Android's server returns events in descending timestamp order.
        val query = "${Uri.encode(bucket)}/events?limit=-1&start=${start.minusSeconds(86400)}&end=$end"
        val events = JSONArray(read(query))
        val spans = mutableListOf<UsageSpan>()
        val labels = mutableMapOf<String, String>()
        var following = end.toEpochMilli()
        for (index in 0 until events.length()) {
            val e = events.getJSONObject(index)
            val t = Instant.parse(e.getString("timestamp")).toEpochMilli()
            val duration = (e.optDouble("duration", 0.0) * 1000).toLong()
            val closed = if (duration > 0) minOf(t + duration, following)
                else if (following - t in 1..10_000) following else t
            following = t
            val from = maxOf(t, start.toEpochMilli())
            val to = minOf(closed, end.toEpochMilli())
            if (to <= from) continue
            val data = e.getJSONObject("data")
            val pkg = data.getString("package")
            labels[pkg] = data.optString("app", pkg)
            spans.add(UsageSpan(pkg, from, to))
        }
        return spans to labels
    }

    fun acknowledge(report: JSONObject): Boolean {
        val day = LocalDate.parse(report.getString("day"))
        val current = day == LocalDate.now()
        val update = prefs.edit().putString("day", (if (current) day else day.plusDays(1)).toString())
        if (current) update.putString("sent", day.toString()).remove("retry")
        check(update.commit())
        android.util.Log.i(TabletService.TAG, "Receiver accepted ActivityWatch report for $day")
        return current
    }
}
