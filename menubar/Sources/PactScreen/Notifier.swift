import Foundation
import PactScreenCore

/// Hourly screen-time warnings. The Mac banner is local. The phone gets the
/// same text through the Discord webhook in Application Support, which stays
/// out of git.
enum ScreenTimeNotifier {
    static func announce(day: String, rollup: DayRollup, preferences: Preferences) {
        guard rollup.day == day, let hour = screenTimeMilestoneHour(seconds: rollup.screenSeconds) else { return }
        guard shouldSendScreenTimeNotice(day: day, hour: hour, previous: loadMark()) else { return }
        let devices = rollup.devices.map { device in
            NoticeDevice(
                name: noticeDeviceName(device.name, override: preferences.deviceLabels[device.deviceId]),
                seconds: device.seconds
            )
        }
        let notice = screenTimeNotice(totalSeconds: rollup.screenSeconds, devices: devices)
        save(NoticeMark(day: day, hour: hour))
        postDesktop(title: notice.title, body: notice.body)
        postDiscord(notice.discord)
        log("sent hour \(hour) for \(day)")
    }

    private static func postDesktop(title: String, body: String) {
        let source = "display notification \(appleScriptString(body)) with title \(appleScriptString(title)) sound name \"Glass\""
        var error: NSDictionary?
        NSAppleScript(source: source)?.executeAndReturnError(&error)
        if error != nil {
            log("desktop notification failed")
            let task = Process()
            task.executableURL = URL(fileURLWithPath: "/usr/bin/osascript")
            task.arguments = ["-e", source]
            task.standardOutput = FileHandle.nullDevice
            task.standardError = FileHandle.nullDevice
            try? task.run()
        }
    }

    private static func postDiscord(_ message: String) {
        guard let url = webhookURL() else {
            log("discord webhook missing")
            return
        }
        var request = URLRequest(url: url)
        request.httpMethod = "POST"
        request.timeoutInterval = 20
        request.cachePolicy = .reloadIgnoringLocalCacheData
        request.setValue("application/json", forHTTPHeaderField: "Content-Type")
        let text = message.count <= 1900 ? message : String(message.prefix(1899)) + "…"
        let body: [String: Any] = [
            "username": "Screen time",
            "content": text,
            "allowed_mentions": ["parse": []],
        ]
        guard let data = try? JSONSerialization.data(withJSONObject: body) else { return }
        request.httpBody = data
        URLSession.shared.dataTask(with: request) { _, response, _ in
            let code = (response as? HTTPURLResponse)?.statusCode ?? 0
            log(code == 0 ? "discord failed" : "discord \(code)")
        }.resume()
    }

    /// One line, `https://discord.com/api/webhooks/...`, mode 0600.
    private static func webhookURL() -> URL? {
        let file = Store.base.appendingPathComponent("discord-webhook")
        guard let raw = try? String(contentsOf: file, encoding: .utf8) else { return nil }
        let trimmed = raw.trimmingCharacters(in: .whitespacesAndNewlines)
        guard let url = URL(string: trimmed),
              url.scheme == "https",
              url.host == "discord.com" || url.host == "discordapp.com",
              url.path.hasPrefix("/api/webhooks/")
        else { return nil }
        return url
    }

    private static func loadMark() -> NoticeMark? {
        let url = Store.base.appendingPathComponent("notices.json")
        guard let data = try? Data(contentsOf: url) else { return nil }
        return try? JSONDecoder().decode(NoticeMark.self, from: data)
    }

    private static func save(_ mark: NoticeMark) {
        do {
            try FileManager.default.createDirectory(at: Store.base, withIntermediateDirectories: true)
            let data = try JSONEncoder().encode(mark)
            try data.write(to: Store.base.appendingPathComponent("notices.json"), options: .atomic)
        } catch {
            log("could not save notice mark")
        }
    }

    private static func appleScriptString(_ value: String) -> String {
        let cleaned = value
            .replacingOccurrences(of: "\n", with: " ")
            .replacingOccurrences(of: "\r", with: " ")
        let escaped = cleaned
            .replacingOccurrences(of: "\\", with: "\\\\")
            .replacingOccurrences(of: "\"", with: "\\\"")
        return "\"\(escaped)\""
    }

    private static func log(_ line: String) {
        let url = FileManager.default.homeDirectoryForCurrentUser
            .appendingPathComponent("Library/Logs/PactScreen.log")
        let stamp = ISO8601DateFormatter().string(from: Date())
        let text = "\(stamp) \(line)\n"
        guard let data = text.data(using: .utf8) else { return }
        if FileManager.default.fileExists(atPath: url.path),
           let handle = try? FileHandle(forWritingTo: url) {
            defer { try? handle.close() }
            _ = try? handle.seekToEnd()
            try? handle.write(contentsOf: data)
        } else {
            try? data.write(to: url)
        }
    }
}
