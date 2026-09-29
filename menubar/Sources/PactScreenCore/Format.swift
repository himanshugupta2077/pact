import Foundation

public func formatDuration(_ seconds: Double) -> String {
    let whole = max(0, Int(seconds.rounded()))
    if whole < 60 {
        return whole == 0 ? "0m" : "\(whole)s"
    }
    let minutes = Int((Double(whole) / 60).rounded())
    let hours = minutes / 60
    let mins = minutes % 60
    if hours > 0, mins > 0 { return "\(hours)h \(mins)m" }
    if hours > 0 { return "\(hours)h" }
    return "\(mins)m"
}

public func formatDay(_ day: String) -> String {
    guard let date = DayClock.date(from: day) else { return day }
    let formatter = DateFormatter()
    formatter.calendar = DayClock.calendar
    formatter.timeZone = DayClock.timeZone
    formatter.locale = Locale(identifier: "en_US_POSIX")
    formatter.dateFormat = "EEE d MMM"
    return formatter.string(from: date)
}

public func formatWeekRange(containing day: String) -> String {
    let days = DayClock.weekDays(containing: day)
    guard let first = days.first, let last = days.last,
          let start = DayClock.date(from: first),
          let end = DayClock.date(from: last)
    else { return "" }
    let calendar = DayClock.calendar
    let startParts = calendar.dateComponents([.day, .month], from: start)
    let endParts = calendar.dateComponents([.day, .month], from: end)
    let months = ["Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec"]
    func monthName(_ month: Int?) -> String {
        guard let month, (1...12).contains(month) else { return "" }
        return months[month - 1]
    }
    let startDay = startParts.day ?? 0
    let endDay = endParts.day ?? 0
    if startParts.month == endParts.month {
        return "\(startDay)–\(endDay) \(monthName(startParts.month))"
    }
    return "\(startDay) \(monthName(startParts.month)) – \(endDay) \(monthName(endParts.month))"
}

public func percentText(_ part: Double, of whole: Double) -> String {
    guard whole > 0, part > 0 else { return "0%" }
    let rounded = Int((part / whole * 100).rounded())
    if rounded <= 0 { return "<1%" }
    return "\(rounded)%"
}

public func displayName(for category: String) -> String {
    if let name = exactNames[category] { return name }
    if category.hasPrefix("site:") {
        let host = String(category.dropFirst(5))
        return host.isEmpty ? category : host
    }
    if category.hasPrefix("app:") {
        let slug = String(category.dropFirst(4))
        if let name = appNames[slug] { return name }
        return titleCase(slug)
    }
    return titleCase(category)
}

public func preferredDeviceName(_ name: String, override: String?) -> String {
    let custom = override?.trimmingCharacters(in: .whitespacesAndNewlines) ?? ""
    if !custom.isEmpty { return custom }
    return name.isEmpty ? "Device" : name
}

public func shortDeviceName(_ name: String, override: String?) -> String {
    let custom = override?.trimmingCharacters(in: .whitespacesAndNewlines) ?? ""
    if !custom.isEmpty { return custom }
    let lowered = name.lowercased()
    if lowered.contains("macbook") { return "Mac" }
    if lowered.contains("phone") { return "Phone" }
    if lowered.contains("laptop") { return "Laptop" }
    let first = name.split(whereSeparator: \.isWhitespace).first.map(String.init) ?? "Device"
    return String(first.prefix(12))
}

public func menuBarTitle(for rollup: DayRollup, preferences: Preferences) -> String {
    var parts: [String] = []
    if preferences.showTotal {
        parts.append(formatDuration(rollup.screenSeconds))
    }
    let buckets = Preferences.cleanedBuckets(preferences.buckets)
    for id in preferences.menuBucketIds {
        let seconds = rollup.buckets.first { $0.id == id }?.seconds ?? 0
        let name = buckets.first { $0.id == id }?.name
            ?? (id == Bucket.work.id ? Bucket.work.name : id)
        parts.append("\(Bucket(id: id, name: name).menuShort) \(formatDuration(seconds))")
    }
    if preferences.showDevices {
        for device in rollup.devices where device.seconds > 0 {
            let label = shortDeviceName(device.name, override: preferences.deviceLabels[device.deviceId])
            parts.append("\(label) \(formatDuration(device.seconds))")
        }
    }
    if parts.isEmpty {
        parts.append(formatDuration(rollup.screenSeconds))
    }
    return parts.joined(separator: " · ")
}

private func titleCase(_ slug: String) -> String {
    let words = slug.split(separator: "-").map { part -> String in
        guard let first = part.first else { return "" }
        return String(first).uppercased() + part.dropFirst()
    }
    let name = words.joined(separator: " ")
    return name.isEmpty ? slug : name
}

private let exactNames: [String: String] = [
    "youtube": "YouTube",
    "hotstar": "JioHotstar",
    "netflix": "Netflix",
    "primevideo": "Prime Video",
    "disney": "Disney+",
    "twitch": "Twitch",
    "tiktok": "TikTok",
    "spotify": "Spotify",
    "video": "Video",
    "instagram": "Instagram",
    "twitter": "X",
    "facebook": "Facebook",
    "reddit": "Reddit",
    "whatsapp": "WhatsApp",
    "discord": "Discord",
    "github": "GitHub",
    "chatgpt": "ChatGPT",
    "gmail": "Gmail",
]

private let appNames: [String: String] = [
    "gpay": "GPay",
    "myjio": "MyJio",
    "ticktick": "TickTick",
    "vscode": "VS Code",
    "visual-studio-code": "VS Code",
    "iterm2": "iTerm",
    "microsoft-teams": "Microsoft Teams",
    "microsoft-outlook": "Outlook",
    "sublime-text": "Sublime Text",
    "system-settings": "System Settings",
    "brawl-stars": "Brawl Stars",
    "clash-royale": "Clash Royale",
    "google-play-services": "Google Play services",
    "nothing-launcher": "Nothing Launcher",
]
