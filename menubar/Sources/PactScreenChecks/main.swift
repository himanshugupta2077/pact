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
        expect(
            DayClock.weekDays(containing: "2026-09-25") == [
                "2026-09-21", "2026-09-22", "2026-09-23", "2026-09-24", "2026-09-25", "2026-09-26", "2026-09-27",
            ],
            "friday week \(DayClock.weekDays(containing: "2026-09-25"))"
        )
        expect(DayClock.weekDays(containing: "2026-09-21").first == "2026-09-21", "monday starts the week")
        expect(DayClock.weekDays(containing: "2026-09-27").first == "2026-09-21", "sunday stays in the week")
        expect(DayClock.weekDays(containing: "2026-09-20").last == "2026-09-20", "previous sunday")
        expect(formatWeekRange(containing: "2026-09-25") == "21–27 Sep", "week title \(formatWeekRange(containing: "2026-09-25"))")
        expect(formatWeekRange(containing: "2026-09-28") == "28 Sep – 4 Oct", "cross month \(formatWeekRange(containing: "2026-09-28"))")

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
            expect(prefs.buckets.map(\.id) == ["entertainment", "gaming", "communication", "other"], "shipped buckets \(prefs.buckets.map(\.id))")
            expect(!prefs.buckets.contains { $0.id == "work" }, "work is a class")
        } catch {
            expect(false, "preferences threw \(error)")
        }
        do {
            let kept = #"{"buckets":[{"id":"gaming","name":"Gaming"}]}"#.data(using: .utf8)!
            let prefs = try JSONDecoder().decode(Preferences.self, from: kept)
            expect(prefs.buckets.map(\.id) == ["gaming"], "deleted buckets stay deleted \(prefs.buckets.map(\.id))")
            expect(prefs.buckets.first?.parent == Bucket.personal.id, "missing parent stays personal")
            expect(effectiveBucket("youtube", preferences: prefs).id == Bucket.unassigned.id, "missing entertainment is unassigned")
            expect(effectiveBucket("app:ghostty", preferences: prefs).id == "work", "work class survives empty buckets")
        } catch {
            expect(false, "trimmed buckets threw \(error)")
        }
        do {
            let legacy = #"{"buckets":[{"id":"work","name":"Work"},{"id":"entertainment","name":"Shows"},{"id":"gaming","name":"Gaming"},{"id":"communication","name":"Communication"}]}"#.data(using: .utf8)!
            let prefs = try JSONDecoder().decode(Preferences.self, from: legacy)
            expect(prefs.buckets.map(\.id) == ["entertainment", "gaming", "communication", "other"], "legacy buckets \(prefs.buckets.map(\.id))")
            expect(prefs.buckets.first { $0.id == "entertainment" }?.name == "Shows", "stored entertainment name")
            let again = try JSONDecoder().decode(Preferences.self, from: JSONEncoder().encode(prefs))
            expect(again.buckets.map(\.id) == prefs.buckets.map(\.id), "migration sticks")
        } catch {
            expect(false, "legacy buckets threw \(error)")
        }
        do {
            var prefs = Preferences.standard
            prefs.buckets.removeAll { $0.id == "entertainment" || $0.id == "other" }
            let again = try JSONDecoder().decode(Preferences.self, from: JSONEncoder().encode(prefs))
            expect(again.buckets.map(\.id) == ["gaming", "communication"], "round trip keeps the deletion")
            var parented = Preferences.standard
            parented.buckets[0].parent = Bucket.work.id
            let keptParent = try JSONDecoder().decode(Preferences.self, from: JSONEncoder().encode(parented))
            expect(keptParent.buckets[0].id == "entertainment", "entertainment stays first")
            expect(keptParent.buckets[0].parent == Bucket.work.id, "work parent sticks")
        } catch {
            expect(false, "bucket round trip threw \(error)")
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
        expect(Bucket.defaults.allSatisfy { $0.parent == Bucket.personal.id }, "shipped buckets start under personal")

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
        var personalMark = Preferences.standard
        personalMark.assignments["app:ghostty"] = Bucket.personal.id
        let ghosttyPersonal = effectiveBucket("app:ghostty", preferences: personalMark)
        expect(ghosttyPersonal.id == Bucket.personal.id, "ghostty assigned to personal")
        expect(ghosttyPersonal.marked, "personal assignment is a mark")

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
        expect(worked.classes.map(\.id) == ["work", "personal"], "two classes")
        expect(worked.classes[0].seconds == 5400, "work class")
        expect(worked.classes[1].seconds == 1800, "personal class")
        expect(worked.classes[1].buckets.map(\.id) == ["communication"], "personal buckets \(worked.classes[1].buckets.map(\.id))")

        expect(rolled.classes[0].seconds == 3600, "standard work class")
        expect(rolled.classes[1].seconds == 3600, "standard personal class")
        expect(rolled.classes[1].buckets.map(\.id) == ["communication", "entertainment"], "personal split \(rolled.classes[1].buckets.map(\.id))")
        let slices = usageSlices(day: rolled, week: nil)
        expect(slices.map(\.id) == ["work", "communication", "entertainment"], "slice order \(slices.map(\.id))")

        var noFun = Preferences.standard
        noFun.buckets.removeAll { $0.id == "entertainment" }
        let shifted = rollup(payload, preferences: noFun)
        expect(shifted.classes[1].buckets.first { $0.id == "other" }?.seconds == 1800, "youtube falls into other")
        expect(shifted.classes[0].seconds == 3600, "ghostty stays work")

        var none = Preferences.standard
        none.buckets = []
        let bare = rollup(payload, preferences: none)
        expect(bare.classes.map(\.id) == ["work", "personal"], "classes stay when every bucket is gone")
        expect(bare.classes[0].seconds == 3600, "bare work")
        expect(bare.classes[1].buckets.map(\.id) == ["unassigned"], "bare personal \(bare.classes[1].buckets.map(\.id))")
        expect(bare.classes[1].seconds == 3600, "bare personal seconds")

        var underWork = Preferences.standard
        underWork.buckets[0].parent = Bucket.work.id
        let movedClass = rollup(payload, preferences: underWork)
        expect(movedClass.classes[0].seconds == 5400, "entertainment under work joins work \(movedClass.classes[0].seconds)")
        expect(movedClass.classes[0].buckets.map(\.id) == ["entertainment"], "work bucket \(movedClass.classes[0].buckets.map(\.id))")
        expect(movedClass.classes[1].buckets.map(\.id) == ["communication"], "personal keeps communication")

        var assignedPersonal = Preferences.standard
        assignedPersonal.assignments["app:ghostty"] = Bucket.personal.id
        let loose = rollup(payload, preferences: assignedPersonal)
        expect(loose.classes[0].seconds == 0, "direct personal leaves work")
        expect(loose.classes[1].buckets.first { $0.id == "unassigned" }?.seconds == 3600, "direct personal is unassigned")

        let heavy = DayPayload(day: "2026-09-25", devices: [
            DeviceDay(
                deviceId: "mac",
                deviceName: "Mac",
                hostname: "Mac",
                platform: "macos",
                screenSeconds: 5100,
                engagedSeconds: 5100,
                byCategory: [
                    CategorySeconds(category: "app:ghostty", seconds: 100),
                    CategorySeconds(category: "youtube", seconds: 5000),
                ],
                byApp: [],
                hours: []
            ),
        ])
        let heavySlices = usageSlices(day: rollup(heavy, preferences: .standard), week: nil)
        expect(heavySlices.map(\.id) == ["entertainment", "work"], "largest bucket leads \(heavySlices.map(\.id))")

        let monday = DayPayload(day: "2026-09-21", devices: [
            DeviceDay(
                deviceId: "phone",
                deviceName: "Nothing Phone (2)",
                hostname: "phone",
                platform: "android",
                screenSeconds: 600,
                engagedSeconds: 600,
                byCategory: [CategorySeconds(category: "youtube", seconds: 600)],
                byApp: [],
                hours: []
            ),
        ])
        let week = rollupWeek([payload, monday], day: "2026-09-25", preferences: .standard)
        expect(week.classes[0].seconds == 3600, "week work \(week.classes[0].seconds)")
        expect(week.classes[1].seconds == 4200, "week personal \(week.classes[1].seconds)")
        expect(week.classes[1].buckets.first { $0.id == "entertainment" }?.seconds == 2400, "week entertainment")
        expect(rolled.devices.first?.platform == "macos", "mac platform")
        expect(rolled.devices.last?.platform == "android", "phone platform")

        var custom = Preferences.standard
        custom.buckets.append(Bucket(id: "reading", name: "Reading"))
        custom.assignments["app:slack"] = "reading"
        custom.assignments["hotstar"] = "work"
        expect(effectiveBucket("app:slack", preferences: custom).id == "reading", "slack in a new bucket")
        expect(effectiveBucket("hotstar", preferences: custom).id == "work", "hotstar moved to work")

        let parts = classParts([
            BucketTotal(id: "work", name: "Work", seconds: 10),
            BucketTotal(id: "entertainment", name: "Entertainment", seconds: 5),
            BucketTotal(id: "other", name: "Other", seconds: 5),
        ])
        expect(parts.map(\.id) == ["work", "personal"], "chart classes")
        expect(parts[1].seconds == 10, "personal chart total")

        let named = ["netflix", "chatgpt", "app:system-settings", "instagram", "github"]
        for category in named {
            let alone = categoryGroups([
                CategoryRow(category: category, name: category, seconds: 10, bucketId: "work", marked: false),
            ])
            expect(alone.count == 1 && alone[0].title == nil, "\(category) stays its own row")
        }
        let grouped = categoryGroups([
            CategoryRow(category: "netflix", name: "Netflix", seconds: 60, bucketId: "entertainment", marked: false),
            CategoryRow(category: "site:dash.cloudflare.com", name: "dash.cloudflare.com", seconds: 30, bucketId: "work", marked: false),
            CategoryRow(category: "site:app.us.strut.io", name: "app.us.strut.io", seconds: 3600, bucketId: "entertainment", marked: true),
            CategoryRow(category: "github", name: "GitHub", seconds: 50, bucketId: "work", marked: false),
            CategoryRow(category: "chatgpt", name: "ChatGPT", seconds: 40, bucketId: "work", marked: false),
            CategoryRow(category: "app:system-settings", name: "System Settings", seconds: 20, bucketId: "other", marked: false),
            CategoryRow(category: "instagram", name: "Instagram", seconds: 15, bucketId: "entertainment", marked: false),
        ])
        let urls = grouped.filter { $0.title == "URLs" }
        expect(urls.count == 1, "one URLs group")
        expect(urls.first?.rows.map(\.category) == ["site:app.us.strut.io", "site:dash.cloudflare.com"], "sites share a group \(urls.first?.rows.map(\.category) ?? [])")
        expect(urls.first?.rows.first { $0.category == "site:dash.cloudflare.com" }?.bucketId == "work", "short site keeps work")
        expect(urls.first?.rows.first { $0.category == "site:app.us.strut.io" }?.bucketId == "entertainment", "long site keeps its bucket")
        expect(grouped.map { $0.title ?? $0.rows[0].name } == ["URLs", "Netflix", "GitHub", "ChatGPT", "System Settings", "Instagram"], "group order \(grouped.map { $0.title ?? $0.rows[0].name })")

        expect(bucketId(from: "Reading", existing: Bucket.defaults) == "reading", "reading id")
        expect(bucketId(from: "Work", existing: []) == "work-2", "work is reserved")
        expect(bucketId(from: "Personal", existing: []) == "personal-2", "personal is reserved")
        expect(bucketId(from: "???", existing: []) == nil, "blank id")
        let cleaned = Preferences.cleanedBuckets([
            Bucket(id: "gaming", name: "Gaming"),
            Bucket(id: "gaming", name: "Games"),
            Bucket(id: "work", name: "Work"),
            Bucket(id: "bad id", name: "Nope"),
            Bucket(id: "reading", name: "  "),
        ])
        expect(cleaned.map(\.id) == ["gaming"], "cleaned \(cleaned.map(\.id))")

        if failed > 0 {
            fputs("\(failed) failed\n", stderr)
            exit(1)
        }
        print("ok")
    }
}
