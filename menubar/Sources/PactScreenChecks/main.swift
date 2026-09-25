import Foundation
import PactScreenCore

/// Command Line Tools on this Mac ship Swift Testing macros without the plugin,
/// and they do not ship XCTest. This target is the check runner instead.
@main
enum PactScreenChecks {
    static func main() {
        var failed = 0
        func expect(_ condition: @autoclosure () -> Bool, _ message: String) {
            if !condition() {
                failed += 1
                fputs("FAIL \(message)\n", stderr)
            }
        }

        expect(DayClock.date(from: "2026-02-29") == nil, "2026 is not a leap year")
        expect(DayClock.date(from: "2026-09-31") == nil, "september has 30 days")
        expect(formatDay("2026-09-25") == "Fri 25 Sep", "day title \(formatDay("2026-09-25"))")
        expect(DayClock.shift("2026-09-25", by: -1, today: "2026-09-25") == "2026-09-24", "previous day")
        expect(DayClock.shift("2026-09-30", by: 1, today: "2026-10-02") == "2026-10-01", "next month")
        expect(DayClock.shift("2026-09-25", by: 1, today: "2026-09-25") == "2026-09-25", "do not pass today")

        expect(formatDuration(0) == "0m", "zero")
        expect(formatDuration(30) == "30s", "seconds")
        expect(formatDuration(60) == "1m", "one minute")
        expect(formatDuration(1800) == "30m", "half hour")
        expect(formatDuration(3600) == "1h", "hour")
        expect(formatDuration(5400) == "1h 30m", "hour and minutes")
        expect(formatDuration(7200) == "2h", "two hours")

        do {
            let json = """
            {"day":"2026-09-25","devices":[{"device_id":"abc","device_name":"Mac","hostname":"mac.local","platform":"macos","screen_seconds":10.5,"engaged_seconds":8,"by_category":[{"category":"youtube","seconds":10.5}],"by_app":[{"app":"Zen","seconds":10.5}],"hours":[{"hour":10,"categories":{"youtube":10.5}}]}]}
            """.data(using: .utf8)!
            let payload = try DayPayload.decode(json)
            expect(payload.devices[0].deviceId == "abc", "device id")
            expect(payload.devices[0].screenSeconds == 10.5, "screen seconds")
            expect(payload.devices[0].hours[0].categories["youtube"] == 10.5, "hour category")
            let again = try DayPayload.decode(try payload.encoded())
            expect(again == payload, "round trip")
        } catch {
            expect(false, "decode threw \(error)")
        }

        do {
            let json = #"{"assignments":{"youtube":"work"},"ssh":"pact@pact-host;rm"}"#.data(using: .utf8)!
            let prefs = try JSONDecoder().decode(Preferences.self, from: json)
            expect(prefs.assignments["youtube"] == "work", "assignment kept")
            expect(prefs.showTotal, "show total defaults on")
            expect(prefs.ssh == "pact@pact-host", "bad ssh is replaced")
            expect(prefs.buckets.contains { $0.id == "entertainment" }, "default buckets restored")
        } catch {
            expect(false, "preferences threw \(error)")
        }
        expect(SSHTarget.isValid("pact@pact-host"), "ssh ok")
        expect(!SSHTarget.isValid("pact@pact-host;rm"), "ssh rejects a shell command")

        let prefs = Preferences.standard
        expect(effectiveBucket("youtube", preferences: prefs).id == "entertainment", "youtube")
        expect(effectiveBucket("hotstar", preferences: prefs).id == "entertainment", "hotstar")
        expect(effectiveBucket("netflix", preferences: prefs).id == "entertainment", "netflix")
        expect(effectiveBucket("app:ghostty", preferences: prefs).id == "work", "ghostty")
        expect(effectiveBucket("app:microsoft-teams", preferences: prefs).id == "work", "teams")
        expect(effectiveBucket("app:zen", preferences: prefs).id == "work", "zen")
        expect(effectiveBucket("site:localhost", preferences: prefs).id == "work", "localhost")
        expect(effectiveBucket("github", preferences: prefs).id == "work", "github")
        expect(effectiveBucket("chatgpt", preferences: prefs).id == "work", "chatgpt")
        expect(effectiveBucket("app:brawl-stars", preferences: prefs).id == "gaming", "brawl")
        expect(effectiveBucket("app:steam-helper", preferences: prefs).id == "gaming", "steam")
        expect(effectiveBucket("whatsapp", preferences: prefs).id == "communication", "whatsapp")
        expect(effectiveBucket("app:messages", preferences: prefs).id == "communication", "messages")
        expect(effectiveBucket("app:brave", preferences: prefs).id == "other", "brave stays unmarked")
        expect(effectiveBucket("app:finder", preferences: prefs).id == "other", "finder")
        expect(!effectiveBucket("app:finder", preferences: prefs).marked, "finder is not marked")

        var marked = prefs
        marked.assignments["youtube"] = "work"
        marked.assignments["app:finder"] = "work"
        marked.assignments["app:brave"] = "entertainment"
        expect(effectiveBucket("youtube", preferences: marked).id == "work", "marked youtube")
        expect(effectiveBucket("youtube", preferences: marked).marked, "youtube marked flag")
        expect(effectiveBucket("app:finder", preferences: marked).marked, "finder marked")
        marked.assignments["youtube"] = "nope"
        let fallback = effectiveBucket("youtube", preferences: marked)
        expect(fallback.id == "entertainment", "unknown mark falls back")
        expect(!fallback.marked, "fallback is not a mark")
        marked.assignments["youtube"] = "other"
        expect(effectiveBucket("youtube", preferences: marked).id == "other", "explicit other")

        let payload = DayPayload(day: "2026-09-25", devices: [
            DeviceDay(
                deviceId: "mac",
                deviceName: "MacBook Air",
                hostname: "mac.local",
                platform: "macos",
                screenSeconds: 5400,
                engagedSeconds: 4000,
                byCategory: [
                    CategorySeconds(category: "app:ghostty", seconds: 3600),
                    CategorySeconds(category: "youtube", seconds: 1800),
                ],
                byApp: [AppSeconds(app: "Ghostty", seconds: 3600), AppSeconds(app: "Zen", seconds: 1800)],
                hours: [HourBucket(hour: 10, categories: ["app:ghostty": 3600, "youtube": 1800])]
            ),
            DeviceDay(
                deviceId: "phone",
                deviceName: "Nothing Phone (2)",
                hostname: "phone",
                platform: "android",
                screenSeconds: 1800,
                engagedSeconds: 1800,
                byCategory: [CategorySeconds(category: "whatsapp", seconds: 1800)],
                byApp: [AppSeconds(app: "WhatsApp", seconds: 1800)],
                hours: [
                    HourBucket(hour: 10, categories: ["whatsapp": 900]),
                    HourBucket(hour: 11, categories: ["whatsapp": 900]),
                ]
            ),
        ])
        let rolled = rollup(payload, preferences: .standard)
        expect(rolled.screenSeconds == 7200, "total")
        expect(rolled.buckets.first { $0.id == "work" }?.seconds == 3600, "work")
        expect(rolled.buckets.first { $0.id == "entertainment" }?.seconds == 1800, "entertainment")
        expect(rolled.buckets.first { $0.id == "communication" }?.seconds == 1800, "communication")
        expect(rolled.devices.map(\.name) == ["MacBook Air", "Nothing Phone (2)"], "device order")
        expect(abs(rolled.hours[10].seconds - 6300) < 0.1, "hour 10 \(rolled.hours[10].seconds)")
        expect(rolled.categories.first { $0.category == "youtube" }?.name == "YouTube", "youtube name")
        expect(displayName(for: "app:microsoft-teams") == "Microsoft Teams", "teams name")
        expect(displayName(for: "site:localhost") == "localhost", "site name")
        expect(menuBarTitle(for: rolled, preferences: .standard) == "2h", "menu total")

        var menu = Preferences.standard
        menu.showTotal = false
        menu.menuBucketIds = ["work", "entertainment"]
        expect(menuBarTitle(for: rolled, preferences: menu) == "W 1h · E 30m", "menu buckets \(menuBarTitle(for: rolled, preferences: menu))")
        menu.menuBucketIds = []
        menu.showDevices = true
        expect(menuBarTitle(for: rolled, preferences: menu) == "Mac 1h 30m · Phone 30m", "menu devices \(menuBarTitle(for: rolled, preferences: menu))")
        menu.deviceLabels["mac"] = "Air"
        expect(menuBarTitle(for: rolled, preferences: menu) == "Air 1h 30m · Phone 30m", "menu label")

        var moved = Preferences.standard
        moved.assignments["youtube"] = "work"
        let worked = rollup(payload, preferences: moved)
        expect(worked.buckets.first { $0.id == "work" }?.seconds == 5400, "youtube moved to work")
        expect(worked.buckets.first { $0.id == "entertainment" } == nil, "entertainment empty")

        expect(bucketId(from: "Reading", existing: Bucket.defaults) == "reading", "reading id")
        expect(bucketId(from: "Work", existing: Bucket.defaults) == "work-2", "work id collision")
        expect(bucketId(from: "???", existing: []) == nil, "blank id")
        let merged = Preferences.mergingDefaults([Bucket(id: "reading", name: "Reading")])
        expect(merged.map(\.id) == ["work", "entertainment", "gaming", "communication", "reading"], "merge \(merged.map(\.id))")

        if failed > 0 {
            fputs("\(failed) failed\n", stderr)
            exit(1)
        }
        print("ok")
    }
}
