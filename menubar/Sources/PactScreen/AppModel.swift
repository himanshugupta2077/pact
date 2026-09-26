import Foundation
import Observation
import PactScreenCore
import ServiceManagement

enum Store {
    static var base: URL {
        FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0]
            .appendingPathComponent("PactScreen", isDirectory: true)
    }

    static var rules: URL { base.appendingPathComponent("rules.json") }
    static var cache: URL { base.appendingPathComponent("cache", isDirectory: true) }
}

@MainActor
@Observable
final class AppModel {
    var menuTitle = "…"

    var onMenuTitle: ((String) -> Void)?
    var preferences = Preferences.standard
    var dayRollup: DayRollup?
    var weekRollup: DayRollup?
    var refreshing = false
    var errorText: String?
    var day = ""
    var fetchedAt: Date?
    var loginNote: String?
    var followsToday = true

    private var payloadsByDay: [String: DayPayload] = [:]
    private var started = false
    private var refreshGeneration = 0

    var freshness: String {
        if refreshing { return "Refreshing…" }
        if let fetchedAt {
            let time = Self.time.string(from: fetchedAt)
            if errorText != nil { return "Couldn't refresh · showing \(time)" }
            return "Updated \(time)"
        }
        return errorText ?? "Not loaded yet"
    }

    func start() {
        guard !started else { return }
        started = true
        preferences = Self.loadPreferences()
        day = DayClock.today()
        followsToday = true
        loadCachedWeek()
        recompute()
        pruneCache()
        applyLoginItem()
        refresh()
        Task {
            while !Task.isCancelled {
                try? await Task.sleep(for: .seconds(300))
                refresh()
            }
        }
    }

    func adopt(preferences: Preferences, payload: DayPayload, fetchedAt: Date) {
        adopt(preferences: preferences, payloads: [payload], day: payload.day, fetchedAt: fetchedAt)
    }

    func adopt(preferences: Preferences, payloads: [DayPayload], day: String, fetchedAt: Date) {
        self.preferences = preferences
        self.day = day
        self.fetchedAt = fetchedAt
        errorText = nil
        store(payloads)
        if payloadsByDay[day] == nil {
            payloadsByDay[day] = DayPayload(day: day, devices: [])
        }
        recompute()
    }

    func refresh() {
        if followsToday {
            let today = DayClock.today()
            if today != day {
                day = today
                loadCachedWeek()
                recompute()
            }
        }
        refreshGeneration += 1
        let generation = refreshGeneration
        let requestedDay = day
        let ssh = preferences.ssh
        refreshing = true
        Task {
            await self.load(generation: generation, day: requestedDay, ssh: ssh)
        }
    }

    func shiftDay(_ delta: Int) {
        let next = DayClock.shift(day, by: delta)
        guard next != day else { return }
        day = next
        followsToday = next == DayClock.today()
        errorText = nil
        loadCachedWeek()
        recompute()
        refresh()
    }

    func setShowTotal(_ on: Bool) { update { $0.showTotal = on } }
    func setShowDevices(_ on: Bool) { update { $0.showDevices = on } }

    func setMenuBucket(_ id: String, on: Bool) {
        update { prefs in
            if on {
                if !prefs.menuBucketIds.contains(id) { prefs.menuBucketIds.append(id) }
            } else {
                prefs.menuBucketIds.removeAll { $0 == id }
            }
        }
    }

    func setDeviceLabel(_ id: String, _ label: String) {
        update { prefs in
            if label.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty {
                prefs.deviceLabels.removeValue(forKey: id)
            } else {
                prefs.deviceLabels[id] = label
            }
        }
    }

    func setSSH(_ value: String) {
        let trimmed = value.trimmingCharacters(in: .whitespacesAndNewlines)
        guard SSHTarget.isValid(trimmed), trimmed != preferences.ssh else { return }
        update { $0.ssh = trimmed }
        refresh()
    }

    func setOpenAtLogin(_ on: Bool) {
        update { $0.openAtLogin = on }
        applyLoginItem()
    }

    func setAssignment(category: String, bucketId: String) {
        let allowed = Set(preferences.buckets.map(\.id)).union([Bucket.work.id, Bucket.personal.id])
        guard allowed.contains(bucketId) else { return }
        update { $0.assignments[category] = bucketId }
    }

    func resetAssignment(_ category: String) {
        update { $0.assignments.removeValue(forKey: category) }
    }

    @discardableResult
    func addBucket(name: String, parent: String) -> Bool {
        let trimmed = name.trimmingCharacters(in: .whitespacesAndNewlines)
        guard let id = bucketId(from: trimmed, existing: preferences.buckets) else { return false }
        let parent = parent == Bucket.work.id ? Bucket.work.id : Bucket.personal.id
        update { $0.buckets.append(Bucket(id: id, name: trimmed, parent: parent)) }
        return true
    }

    func setBucketParent(_ id: String, _ parent: String) {
        guard Bucket.isValidId(id) else { return }
        let parent = parent == Bucket.work.id ? Bucket.work.id : Bucket.personal.id
        update { prefs in
            guard let index = prefs.buckets.firstIndex(where: { $0.id == id }) else { return }
            prefs.buckets[index].parent = parent
        }
    }

    func removeBucket(_ id: String) {
        guard Bucket.isValidId(id) else { return }
        update { prefs in
            prefs.buckets.removeAll { $0.id == id }
            prefs.menuBucketIds.removeAll { $0 == id }
            for (category, assigned) in prefs.assignments where assigned == id {
                prefs.assignments.removeValue(forKey: category)
            }
        }
    }

    private func load(generation: Int, day requestedDay: String, ssh: String) async {
        defer {
            if generation == refreshGeneration { refreshing = false }
        }
        do {
            let next = try await Fetcher.fetch(day: requestedDay, ssh: ssh)
            guard generation == refreshGeneration, requestedDay == day else { return }
            errorText = nil
            let when = Date()
            fetchedAt = when
            store(next)
            for payload in next {
                saveCache(day: payload.day, payload: payload, fetchedAt: when)
            }
            if payloadsByDay[requestedDay] == nil {
                let empty = DayPayload(day: requestedDay, devices: [])
                payloadsByDay[requestedDay] = empty
                saveCache(day: requestedDay, payload: empty, fetchedAt: when)
            }
            recompute()
        } catch {
            guard generation == refreshGeneration, requestedDay == day else { return }
            errorText = (error as? LocalizedError)?.errorDescription ?? error.localizedDescription
            recompute()
        }
    }

    private func update(_ body: (inout Preferences) -> Void) {
        var next = preferences
        body(&next)
        preferences = next
        persist()
        recompute()
    }

    private func store(_ payloads: [DayPayload]) {
        for payload in payloads where !payload.day.isEmpty {
            payloadsByDay[payload.day] = payload
        }
    }

    private func recompute() {
        let prefs = preferences
        if let payload = payloadsByDay[day] {
            dayRollup = PactScreenCore.rollup(payload, preferences: prefs)
        } else {
            dayRollup = nil
        }
        let weekDays = DayClock.weekDays(containing: day.isEmpty ? DayClock.today() : day)
        let present = weekDays.compactMap { payloadsByDay[$0] }
        weekRollup = present.isEmpty ? nil : rollupWeek(present, day: day, preferences: prefs)
        if let dayRollup {
            setTitle(menuBarTitle(for: dayRollup, preferences: prefs))
        } else {
            setTitle("…")
        }
    }

    private func setTitle(_ title: String) {
        guard title != menuTitle else { return }
        menuTitle = title
        onMenuTitle?(title)
    }

    private func loadCachedWeek() {
        let anchor = day
        let hadAnchor = payloadsByDay[anchor] != nil
        for item in DayClock.weekDays(containing: anchor) where payloadsByDay[item] == nil {
            guard let envelope = readCache(item), envelope.payload.day == item else { continue }
            payloadsByDay[item] = envelope.payload
            if item == anchor {
                fetchedAt = envelope.fetchedAt
            }
        }
        if !hadAnchor, payloadsByDay[anchor] == nil {
            fetchedAt = nil
        }
    }

    private func readCache(_ day: String) -> CacheEnvelope? {
        let url = Store.cache.appendingPathComponent("\(day).json")
        guard let data = try? Data(contentsOf: url) else { return nil }
        let decoder = JSONDecoder()
        decoder.keyDecodingStrategy = .convertFromSnakeCase
        return try? decoder.decode(CacheEnvelope.self, from: data)
    }

    private func saveCache(day: String, payload: DayPayload, fetchedAt: Date) {
        do {
            try FileManager.default.createDirectory(at: Store.cache, withIntermediateDirectories: true)
            let encoder = JSONEncoder()
            encoder.keyEncodingStrategy = .convertToSnakeCase
            encoder.outputFormatting = [.prettyPrinted, .sortedKeys]
            let data = try encoder.encode(CacheEnvelope(fetchedAt: fetchedAt, payload: payload))
            try data.write(to: Store.cache.appendingPathComponent("\(day).json"), options: .atomic)
        } catch {
            // The menu can keep the copy it already has. The next refresh tries again.
        }
    }

    private func pruneCache() {
        let cutoff = DayClock.shift(DayClock.today(), by: -40)
        let files = (try? FileManager.default.contentsOfDirectory(at: Store.cache, includingPropertiesForKeys: nil)) ?? []
        for file in files where file.pathExtension == "json" {
            let name = file.deletingPathExtension().lastPathComponent
            if name < cutoff { try? FileManager.default.removeItem(at: file) }
        }
    }

    private func persist() {
        do {
            try FileManager.default.createDirectory(at: Store.base, withIntermediateDirectories: true)
            let encoder = JSONEncoder()
            encoder.outputFormatting = [.prettyPrinted, .sortedKeys]
            try encoder.encode(preferences).write(to: Store.rules, options: .atomic)
        } catch {
            errorText = "Couldn't save settings."
        }
    }

    private static func loadPreferences() -> Preferences {
        guard let data = try? Data(contentsOf: Store.rules),
              let prefs = try? JSONDecoder().decode(Preferences.self, from: data)
        else { return .standard }
        return prefs
    }

    private func applyLoginItem() {
        let service = SMAppService.mainApp
        guard preferences.openAtLogin else {
            loginNote = nil
            if service.status == .enabled { try? service.unregister() }
            return
        }
        switch service.status {
        case .enabled:
            loginNote = nil
        case .requiresApproval:
            loginNote = "Allow Pact Screen in System Settings → General → Login Items."
        case .notRegistered, .notFound:
            do {
                try service.register()
                loginNote = service.status == .requiresApproval
                    ? "Allow Pact Screen in System Settings → General → Login Items."
                    : nil
            } catch {
                loginNote = "Open at login needs Pact Screen in Applications."
            }
        @unknown default:
            loginNote = nil
        }
    }

    private static let time: DateFormatter = {
        let formatter = DateFormatter()
        formatter.timeStyle = .short
        formatter.dateStyle = .none
        return formatter
    }()
}

private struct CacheEnvelope: Codable {
    var fetchedAt: Date
    var payload: DayPayload
}
