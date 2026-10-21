package app.pact.ship

import app.pact.data.UsageSpan
import java.time.Instant
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Test

class ActivityReportTest {
    private val tz = ZoneId.of("Asia/Kolkata")

    @Test
    fun whatsappUsesTheSharedCategory() {
        assertEquals("whatsapp", categoryFor("com.whatsapp", "WhatsApp"))
        assertEquals("app:nothing-launcher", categoryFor("com.nothing.launcher", "Nothing Launcher"))
    }

    @Test
    fun shortGapInTheSameAppJoins() {
        val merged = mergeSpans(
            listOf(
                UsageSpan("com.whatsapp", 0L, 10_000L),
                UsageSpan("com.whatsapp", 11_000L, 20_000L),
                UsageSpan("com.android.systemui", 0L, 50_000L),
                UsageSpan("com.netflix.mediaclient", 30_000L, 30_400L),
            )
        )
        assertEquals(1, merged.size)
        assertEquals(20_000L, merged[0].endMs)
    }

    @Test
    fun reportTotalsMatchTheSpans() {
        val report = buildReport(
            deviceId = "device-phone-1111",
            deviceName = "Phone",
            hostname = "phone",
            tz = tz,
            now = Instant.parse("2026-09-26T04:00:00Z"),
            spans = listOf(UsageSpan("com.whatsapp", 1_758_835_200_000L, 1_758_835_260_000L)),
            labels = mapOf("com.whatsapp" to "WhatsApp"),
        )
        assertEquals(60.0, report.getDouble("screen_seconds"), 0.01)
        assertEquals("whatsapp", report.getJSONArray("by_category").getJSONObject(0).getString("category"))
        assertEquals("pact", report.getString("source"))
        assertEquals("2026-09-26", report.getString("day"))
    }
}
