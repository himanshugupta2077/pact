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
    var refreshing = false
    var errorText: String?
    var day = ""
    var fetchedAt: Date?
    var loginNote: String?
    var followsToday = true

    private var payload: DayPayload?
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
        loadCache(for: day)
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
        self.preferences = preferences
        self.payload = payload
        self.fetchedAt = fetchedAt
        day = payload.day
        errorText = nil
        recompute()
    }

    func refresh() {
        if followsToday {
            let today = DayClock.today()
            if today != day {
                day = today
                loadCache(for: today)
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
        loadCache(for: next)
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
        update { $0.assignments[category] = bucketId }
    }

    func resetAssignment(_ category: String) {
        update { $0.assignments.removeValue(forKey: category) }
    }

    @discardableResult
    func addBucket(name: String) -> Bool {
        let trimmed = name.trimmingCharacters(in: .whitespacesAndNewlines)
        guard let id = bucketId(from: trimmed, existing: preferences.buckets) else { return false }
        update { $0.buckets.append(Bucket(id: id, name: trimmed)) }
        return true
    }

    func removeBucket(_ id: String) {
        guard !Bucket.defaults.contains(where: { $0.id == id }) else { return }
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
            payload = next
            errorText = nil
            let when = Date()
            fetchedAt = when
            saveCache(day: requestedDay, payload: next, fetchedAt: when)
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

    private func recompute() {
        guard let payload else {
            dayRollup = nil
            setTitle("…")
            return
        }
        let next = PactScreenCore.rollup(payload, preferences: preferences)
        dayRollup = next
        setTitle(menuBarTitle(for: next, preferences: preferences))
    }

    private func setTitle(_ title: String) {
        guard title != menuTitle else { return }
        menuTitle = title
        onMenuTitle?(title)
    }

    private func loadCache(for day: String) {
        let url = Store.cache.appendingPathComponent("\(day).json")
        guard let data = try? Data(contentsOf: url) else {
            payload = nil
            fetchedAt = nil
            return
        }
        let decoder = JSONDecoder()
        decoder.keyDecodingStrategy = .convertFromSnakeCase
        guard let envelope = try? decoder.decode(CacheEnvelope.self, from: data),
              envelope.payload.day == day
        else {
            payload = nil
            fetchedAt = nil
            return
        }
        payload = envelope.payload
        fetchedAt = envelope.fetchedAt
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
