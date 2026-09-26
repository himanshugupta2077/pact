package app.pact.ship

import app.pact.data.UsageSpan
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.math.round
import org.json.JSONArray
import org.json.JSONObject

/** Same package names as collector/aw_usage.py so the menu bar categories line up. */
private val PACKAGE_CATEGORIES = mapOf(
    "com.google.android.youtube" to "youtube",
    "com.google.android.apps.youtube.music" to "youtube",
    "com.google.android.apps.youtube.kids" to "youtube",
    "com.netflix.mediaclient" to "netflix",
    "in.startv.hotstar" to "hotstar",
    "com.jio.media.jiobeats" to "hotstar",
    "com.instagram.android" to "instagram",
    "com.whatsapp" to "whatsapp",
    "com.whatsapp.w4b" to "whatsapp",
    "com.spotify.music" to "spotify",
    "com.discord" to "discord",
    "com.openai.chatgpt" to "chatgpt",
    "com.github.android" to "github",
    "com.zhiliaoapp.musically" to "tiktok",
    "com.ss.android.ugc.trill" to "tiktok",
    "com.twitter.android" to "twitter",
    "com.reddit.frontpage" to "reddit",
    "com.facebook.katana" to "facebook",
    "com.amazon.avod.thirdpartyclient" to "primevideo",
)

private val SKIP_PACKAGES = setOf("com.android.systemui", "com.android.keyguard")

fun appSlug(label: String): String {
    val slug = label.lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-')
    return slug.ifEmpty { "unknown" }
}

fun categoryFor(packageName: String, label: String): String {
    PACKAGE_CATEGORIES[packageName]?.let { return it }
    val name = label.ifBlank { packageName.substringAfterLast('.') }
    return "app:" + appSlug(name)
}

/** Drop the lock UI and sub-second flickers, then join the same app across a short gap. */
fun mergeSpans(spans: List<UsageSpan>, gapMs: Long = 2_000L): List<UsageSpan> {
    val kept = spans
        .filter { it.packageName !in SKIP_PACKAGES && it.endMs - it.startMs >= 1_000L }
        .sortedBy { it.startMs }
    val merged = ArrayList<UsageSpan>()
    for (span in kept) {
        val last = merged.lastOrNull()
        if (last != null && last.packageName == span.packageName && span.startMs - last.endMs <= gapMs) {
            merged[merged.lastIndex] = last.copy(endMs = maxOf(last.endMs, span.endMs))
        } else {
            merged.add(span)
        }
    }
    return merged
}

private fun seconds(ms: Long): Double = round(ms / 100.0) / 10.0

private val STAMP: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ssXXX")

fun buildReport(
    deviceId: String,
    deviceName: String,
    hostname: String,
    tz: ZoneId,
    now: Instant,
    spans: List<UsageSpan>,
    labels: Map<String, String>,
): JSONObject {
    val merged = mergeSpans(spans)
    val catMs = LinkedHashMap<String, Long>()
    val appMs = LinkedHashMap<String, Long>()
    val segments = JSONArray()
    for (span in merged) {
        val label = labels[span.packageName].orEmpty().ifBlank { span.packageName.substringAfterLast('.') }
        val category = categoryFor(span.packageName, label)
        val ms = span.endMs - span.startMs
        catMs[category] = (catMs[category] ?: 0L) + ms
        appMs[label] = (appMs[label] ?: 0L) + ms
        segments.put(
            JSONObject()
                .put("start", Instant.ofEpochMilli(span.startMs).atZone(tz).format(STAMP))
                .put("end", Instant.ofEpochMilli(span.endMs).atZone(tz).format(STAMP))
                .put("seconds", seconds(ms))
                .put("app", label)
                .put("title", "")
                .put("url", "")
                .put("domain", "")
                .put("category", category)
        )
    }
    val zoned = now.atZone(tz)
    val screen = seconds(catMs.values.sum())
    return JSONObject()
        .put("schema", 1)
        .put("source", "pact")
        .put("device_id", deviceId)
        .put("hostname", hostname)
        .put("device_name", deviceName.ifBlank { hostname })
        .put("platform", "android")
        .put("tz", tz.id)
        .put("day", zoned.toLocalDate().toString())
        .put("gathered_at", zoned.format(STAMP))
        .put("afk_available", false)
        .put("screen_seconds", screen)
        .put("engaged_seconds", screen)
        .put("by_category", rows(catMs, "category"))
        .put("by_app", rows(appMs, "app"))
        .put("by_domain", JSONArray())
        .put("buckets", JSONArray())
        .put("segments", segments)
}

private fun rows(totals: Map<String, Long>, field: String): JSONArray {
    val array = JSONArray()
    totals.entries
        .map { it.key to seconds(it.value) }
        .filter { it.second > 0.0 }
        .sortedWith(compareByDescending<Pair<String, Double>> { it.second }.thenBy { it.first })
        .forEach { (name, value) ->
            array.put(
                JSONObject()
                    .put(field, name)
                    .put("seconds", value)
                    .put("engaged_seconds", value)
            )
        }
    return array
}
